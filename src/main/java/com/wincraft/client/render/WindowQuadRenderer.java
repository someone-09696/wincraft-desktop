package com.wincraft.client.render;

import com.wincraft.client.WinCraftClient;
import com.wincraft.client.input.WindowInputHandler;
import com.wincraft.client.bridge.NativeCaptureBridge;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * World-pass projection with a post-shader composition point immediately below
 * the HUD:
 *
 * AFTER_TRANSLUCENT_TERRAIN:
 *   • Runs audio/input, snapshots MVP matrices, and copies ordinary world depth.
 *   • Does not touch WGL interop resources.
 * Before GuiRenderer.render:
 *   • Draws into Minecraft's post-processed main color texture, below the HUD.
 *   • Locks each WGL texture only while sampling it; never attaches it to an FBO.
 *
 * Vulkan is unsupported. Requires OpenGL + WGL_NV_DX_interop (AMD supports this).
 */
public final class WindowQuadRenderer {
    private static final Logger LOG = LoggerFactory.getLogger("wincraft/render");

    private int program, vao, vbo, matrixUniform, textureUniform;
    private int depthTextureUniform, viewportUniform, useDepthUniform, reverseDepthUniform;
    private int placeholderTex; // 1×1 grey, plain GL — never WGL-interop registered
    private int sceneDepthTex, outputFbo;
    private boolean sceneDepthAvailable, reverseDepth;
    private boolean depthFailureReported;
    private final boolean irisPresent = FabricLoader.getInstance().isModLoaded("iris");
    private boolean shaderDepthFallbackReported;
    private boolean initFailed;
    private boolean warnedNonGlContext;
    private final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    // Per-panel snapshot for the current world-render pass.
    private record PanelSnap(AppWindowEntity panel, Matrix4f mvp) {}
    private final List<PanelSnap> snaps = new ArrayList<>();

    public void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::phase1_captureState);
    }

    // ── Shader helpers ───────────────────────────────────────────────────────
    private static String shader(String name) {
        try (var in = WindowQuadRenderer.class.getResourceAsStream(
                "/assets/wincraft/shaders/core/" + name)) {
            if (in == null) throw new IOException("Missing: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src); GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0)
            throw new IllegalStateException("Shader:\n" + GL20.glGetShaderInfoLog(id));
        return id;
    }

    // ── Lazy GL init (render thread) ─────────────────────────────────────────
    private void init() {
        if (program != 0 || initFailed) return;
        try {
            int v = compile(GL20.GL_VERTEX_SHADER,   shader("window_quad.vsh"));
            int f = compile(GL20.GL_FRAGMENT_SHADER, shader("window_quad.fsh"));
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, v); GL20.glAttachShader(program, f);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(v); GL20.glDeleteShader(f);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException("Link:\n" + GL20.glGetProgramInfoLog(program));
            matrixUniform = GL20.glGetUniformLocation(program, "uMvp");
            textureUniform = GL20.glGetUniformLocation(program, "uTexture");
            depthTextureUniform = GL20.glGetUniformLocation(program, "uSceneDepth");
            viewportUniform = GL20.glGetUniformLocation(program, "uViewportSize");
            useDepthUniform = GL20.glGetUniformLocation(program, "uUseSceneDepth");
            reverseDepthUniform = GL20.glGetUniformLocation(program, "uReverseDepth");

            vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            // ±0.5 quad, UV 0→1
            float[] verts = {
                -.5f,-.5f, 0,0,    .5f,-.5f, 1,0,    .5f,.5f, 1,1,
                -.5f,-.5f, 0,0,    .5f,.5f,  1,1,   -.5f,.5f, 0,1
            };
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);
            GL20.glEnableVertexAttribArray(0); GL20.glVertexAttribPointer(0,2,GL11.GL_FLOAT,false,16,0);
            GL20.glEnableVertexAttribArray(1); GL20.glVertexAttribPointer(1,2,GL11.GL_FLOAT,false,16,8);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // Placeholder: 1×1 grey — plain GL texture, never touches WGL interop
            placeholderTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, placeholderTex);
            var grey = BufferUtils.createByteBuffer(4);
            grey.put(new byte[]{0x60,0x60,0x60,(byte)0xCC}).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,1,1,0,
                GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,grey);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            LOG.info("WinCraft GL renderer OK  GL={}",
                GL11.glGetString(GL11.GL_VERSION));
            WinCraftClient.reportRendererStatus(null);
        } catch (Exception e) {
            LOG.error("WinCraft GL renderer init FAILED — panels will not render: {}", e.toString());
            WinCraftClient.reportRendererStatus("OpenGL setup failed: " + e.getMessage());
            initFailed = true;
        }
    }

    // ── World render — cache MVPs and draw in the same depth-tested pass ─────
    private void phase1_captureState(LevelRenderContext ctx) {
        if (WinCraftClient.surfaces().isEmpty()) {
            snaps.clear(); sceneDepthAvailable = false; return;
        }
        // Spatial audio
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            var eye = mc.player.getEyePosition();
            var look = mc.player.getLookAngle();
            for (AppWindowEntity sp : WinCraftClient.AUDIO.activePanels()) {
                var p = sp.transform.position;
                WinCraftClient.AUDIO.update(sp,(float)p.x,(float)p.y,(float)p.z,
                    (float)eye.x,(float)eye.y,(float)eye.z,
                    (float)look.x,(float)look.y,(float)look.z);
            }
        }
        WindowInputHandler.frame();

        snaps.clear();

        Vector3d cam = new Vector3d(
            ctx.levelState().cameraRenderState.pos.x,
            ctx.levelState().cameraRenderState.pos.y,
            ctx.levelState().cameraRenderState.pos.z);

        for (AppWindowEntity panel : WinCraftClient.surfaces()) {
            try {
                // Closing the desktop window ends its WGC stream. Remove it on the
                // render thread where WGL resources can be detached safely.
                if (WinCraftClient.removeIfSourceClosed(panel)) continue;
                // Snapshot the MVP while the current camera matrices are available.
                Matrix4f mvp = new Matrix4f(ctx.levelState().cameraRenderState.projectionMatrix)
                    .mul(ctx.levelState().cameraRenderState.viewRotationMatrix)
                    .mul(ctx.poseStack().last().pose())
                    .translate((float)-cam.x, (float)-cam.y, (float)-cam.z)
                    .mul(panel.transform.model());
                snaps.add(new PanelSnap(panel, mvp));
            } catch (RuntimeException e) {
                LOG.warn("WinCraft matrix snapshot '{}': {}", panel.info.title(), e.toString());
            }
        }
        // Iris shader pipelines can replace the world depth buffer and projection
        // after this event. Comparing that depth with the final post-processed
        // target rejects every fragment on some shader packs. Use the existing
        // block-ray visibility fallback for Iris and keep depth-copy occlusion for
        // the vanilla pipeline.
        if (irisPresent) {
            sceneDepthAvailable = false;
            if (!shaderDepthFallbackReported) {
                LOG.info("WinCraft: Iris detected; using block-ray panel occlusion for shader compatibility");
                shaderDepthFallbackReported = true;
            }
        } else {
            captureSceneDepth();
        }
    }

    /** Copy the world depth attachment without locking or touching app textures. */
    private void captureSceneDepth() {
        sceneDepthAvailable = false;
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int x = viewport.get(0), y = viewport.get(1), width = viewport.get(2), height = viewport.get(3);
        if (width <= 0 || height <= 0 || GL11.glGetInteger(GL13.GL_SAMPLES) > 0) {
            reportDepthFailure("Depth copy unavailable for this render target; using block-ray occlusion.");
            return;
        }

        int sourceDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int oldActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (sceneDepthTex == 0) sceneDepthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepthTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceDrawFbo);
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24,
                x, y, width, height, 0);
            int copiedWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int copiedHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            sceneDepthAvailable = copiedWidth == width && copiedHeight == height;
            int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            reverseDepth = depthFunc == GL11.GL_GREATER || depthFunc == GL11.GL_GEQUAL;
            if (!sceneDepthAvailable) reportDepthFailure("Could not copy scene depth; using block-ray occlusion.");
            else {
                depthFailureReported = false;
                WinCraftClient.reportRendererStatus(null);
            }
        } catch (RuntimeException e) {
            reportDepthFailure("Could not copy scene depth: " + e.getMessage());
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFbo);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL13.glActiveTexture(oldActiveTexture);
        }
    }

    private void reportDepthFailure(String message) {
        if (!depthFailureReported) {
            LOG.warn("WinCraft {}", message);
            depthFailureReported = true;
        }
        WinCraftClient.reportRendererStatus(message);
    }

    /** Draw after shader post-processing, into Minecraft's color target before the HUD. */
    public void renderAfterPostProcessing(RenderTarget target) {
        if (snaps.isEmpty() || target == null || Minecraft.getInstance().level == null) return;
        if (GLFW.glfwGetCurrentContext() == 0) {
            if (!warnedNonGlContext) {
                LOG.warn("WinCraft capture requires Minecraft's OpenGL backend; Vulkan has no WGL context");
                warnedNonGlContext = true;
            }
            WinCraftClient.reportRendererStatus("No OpenGL context. WinCraft panels require the OpenGL renderer.");
            return;
        }

        init();
        if (initFailed) return;
        if (!(target.getColorTexture() instanceof GlTexture colorTexture)) {
            WinCraftClient.reportRendererStatus("WinCraft needs Minecraft's OpenGL main render target.");
            return;
        }

        int oldDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer viewport = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int[] oldViewport = {viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3)};
        try {
            if (outputFbo == 0) outputFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, outputFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, colorTexture.glId(), 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                WinCraftClient.reportRendererStatus("Could not bind Minecraft's final shader output for panels.");
                return;
            }
            GL11.glViewport(0, 0, target.width, target.height);
            renderWorldPanels();
        } catch (RuntimeException e) {
            LOG.error("WinCraft final panel composition failed: {}", e.toString());
            WinCraftClient.reportRendererStatus("Panel composition failed: " + e.getMessage());
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFbo);
            GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
        }
    }

    /** Draw to the post-processed target; the WGL texture is only ever sampled. */
    private void renderWorldPanels() {
        if (snaps.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (GLFW.glfwGetCurrentContext() == 0) {
            if (!warnedNonGlContext) {
                LOG.warn("WinCraft capture requires Minecraft's OpenGL backend; Vulkan has no WGL context");
                warnedNonGlContext = true;
            }
            WinCraftClient.reportRendererStatus("No OpenGL context. WinCraft panels require the OpenGL renderer.");
            return;
        }
        // ── Save all GL state we will touch ─────────────────────────────────
        int oldProg   = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVao    = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldVbo    = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTex    = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int oldTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int oldSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        IntBuffer state4 = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, state4);
        int[] oldViewport = {state4.get(0), state4.get(1), state4.get(2), state4.get(3)};
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean cull  = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        state4.clear();
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, state4);
        int[] oldColorMask = {state4.get(0), state4.get(1), state4.get(2), state4.get(3)};
        int srcRgb    = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int dstRgb    = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int srcAlpha  = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int dstAlpha  = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        try {
            init();
            if (initFailed) return;

            // The output FBO has no depth attachment. Scene depth is sampled from
            // the safe copy made in the world pass, so no app texture is FBO-bound.
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL33.glBindSampler(0, 0);
            GL33.glBindSampler(1, 0);
            GL20.glUseProgram(program);
            GL20.glUniform1i(textureUniform, 0);
            GL20.glUniform1i(depthTextureUniform, 1);
            GL20.glUniform1i(useDepthUniform, sceneDepthAvailable ? 1 : 0);
            GL20.glUniform1i(reverseDepthUniform, reverseDepth ? 1 : 0);
            GL20.glUniform2f(viewportUniform, (float)oldViewport[2], (float)oldViewport[3]);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneDepthAvailable ? sceneDepthTex : 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL30.glBindVertexArray(vao);

            for (PanelSnap snap : snaps) {
                if (!sceneDepthAvailable && centerOccluded(mc, snap.panel())) continue;
                if (snap.panel().captureFailure() != null) {
                    drawPlaceholder(snap.mvp());
                    continue;
                }
                try {
                    TextureManager3D.ensureAttached(snap.panel());
                } catch (RuntimeException e) {
                    LOG.warn("WinCraft capture init '{}': {}", snap.panel().info.title(), e.toString());
                    snap.panel().markCaptureFailed(e);
                    drawPlaceholder(snap.mvp());
                    continue;
                }
                NativeCaptureBridge cap = snap.panel().capture();
                if (cap == null) {
                    drawPlaceholder(snap.mvp());
                    continue;
                }

                // ── WGL interop lock (VALID: we only sample from it, never FBO-attach it) ──
                try {
                    cap.beginFrame(snap.panel().glTexture());
                } catch (RuntimeException e) {
                    // beginFrame can fail if the capture session died. Just skip this panel.
                    LOG.warn("WinCraft beginFrame '{}': {}", snap.panel().info.title(), e.toString());
                    snap.panel().markCaptureFailed(e);
                    drawPlaceholder(snap.mvp());
                    continue;
                }
                try {
                    matBuf.clear(); snap.mvp().get(matBuf);
                    GL20.glUniformMatrix4fv(matrixUniform, false, matBuf);

                    boolean hasFrame = cap.width() > 0 && cap.height() > 0;
                    // Bind interop texture as sampler only — NEVER as FBO color attachment
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D,
                        hasFrame ? snap.panel().glTexture() : placeholderTex);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
                } finally {
                    try {
                        // Do not leave a WGL-owned object bound after returning it to D3D.
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                    } finally {
                        try {
                            cap.endFrame(); // release WGL lock
                        } catch (RuntimeException e) {
                            LOG.error("WinCraft WGL unlock failed for '{}': {}",
                                snap.panel().info.title(), e.toString());
                        }
                    }
                }
            }
        } finally {
            // ── Restore all GL state ─────────────────────────────────────────
            GL30.glBindVertexArray(oldVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldVbo);
            GL20.glUseProgram(oldProg);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex);
            GL33.glBindSampler(0, oldSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTex1);
            GL33.glBindSampler(1, oldSampler1);
            GL13.glActiveTexture(oldActive);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDrawFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFbo);
            GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
            GL11.glDepthFunc(depthFunc);
            GL11.glDepthMask(depthMask);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else        GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
            else       GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (blend) GL11.glEnable(GL11.GL_BLEND);
            else       GL11.glDisable(GL11.GL_BLEND);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else         GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (stencil) GL11.glEnable(GL11.GL_STENCIL_TEST);
            else         GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(oldColorMask[0] != 0, oldColorMask[1] != 0,
                oldColorMask[2] != 0, oldColorMask[3] != 0);
        }
    }

    private static boolean centerOccluded(Minecraft mc, AppWindowEntity panel) {
        if (mc.player == null || mc.level == null) return false;
        var eye = mc.player.getEyePosition();
        var center = new net.minecraft.world.phys.Vec3(
            panel.transform.position.x, panel.transform.position.y, panel.transform.position.z);
        var blockHit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, center,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        return blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS &&
            eye.distanceTo(blockHit.getLocation()) < eye.distanceTo(center) - .05;
    }

    private void drawPlaceholder(Matrix4f mvp) {
        matBuf.clear();
        mvp.get(matBuf);
        GL20.glUniformMatrix4fv(matrixUniform, false, matBuf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, placeholderTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public void close() {
        if (vbo  != 0) GL15.glDeleteBuffers(vbo);
        if (vao  != 0) GL30.glDeleteVertexArrays(vao);
        if (program != 0) GL20.glDeleteProgram(program);
        if (placeholderTex != 0) GL11.glDeleteTextures(placeholderTex);
        if (sceneDepthTex != 0) GL11.glDeleteTextures(sceneDepthTex);
        if (outputFbo != 0) GL30.glDeleteFramebuffers(outputFbo);
        vbo = vao = program = placeholderTex = sceneDepthTex = outputFbo = 0;
    }
}
