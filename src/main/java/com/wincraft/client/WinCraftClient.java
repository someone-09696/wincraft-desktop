package com.wincraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wincraft.client.bridge.WindowHandleInfo;
import com.wincraft.client.bridge.OwnedWindowInfo;
import com.wincraft.client.audio.SpatialAudioService;
import com.wincraft.client.bridge.NativeCaptureBridge;
import com.wincraft.client.gui.AppLauncherScreen;
import com.wincraft.client.input.WindowInputHandler;
import com.wincraft.client.render.AppWindowEntity;
import com.wincraft.client.render.MatrixTransform3D;
import com.wincraft.client.render.WindowQuadRenderer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WinCraftClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("wincraft");
    private static final List<AppWindowEntity> PANELS = new CopyOnWriteArrayList<>();
    private static final WindowQuadRenderer RENDERER = new WindowQuadRenderer();
    public static final SpatialAudioService AUDIO = new SpatialAudioService();
    private static volatile boolean helpHudEnabled;
    private static volatile String rendererStatus;
    private static int popupScanTicks;
    private static int closedWindowScanTicks;
    public static List<AppWindowEntity> surfaces() { return PANELS; }
    public static boolean helpHudEnabled() { return helpHudEnabled; }
    public static void setHelpHudEnabled(boolean enabled) { helpHudEnabled = enabled; }
    public static void reportRendererStatus(String status) { rendererStatus = status; }
    public static void renderPanelsAfterPostProcessing(RenderTarget target) {
        updateSpatialAudio();
        RENDERER.renderAfterPostProcessing(target);
    }

    public static void updateSpatialAudio() {
        if (AUDIO.activePanels().isEmpty()) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        float lx, ly, lz, fx, fy, fz, ux = 0f, uy = 1f, uz = 0f;
        com.mojang.blaze3d.audio.ListenerTransform lt = null;
        try {
            if (mc.getSoundManager() != null) {
                lt = mc.getSoundManager().getListenerTransform();
            }
        } catch (RuntimeException ignored) { }

        if (lt != null) {
            Vec3 p = lt.position();
            Vec3 f = lt.forward();
            Vec3 u = lt.up();
            lx = (float) p.x; ly = (float) p.y; lz = (float) p.z;
            fx = (float) f.x; fy = (float) f.y; fz = (float) f.z;
            ux = (float) u.x; uy = (float) u.y; uz = (float) u.z;
        } else {
            Vec3 eye = mc.player.getEyePosition();
            Vec3 look = mc.player.getLookAngle();
            lx = (float) eye.x; ly = (float) eye.y; lz = (float) eye.z;
            fx = (float) look.x; fy = (float) look.y; fz = (float) look.z;
        }

        for (AppWindowEntity panel : PANELS) {
            if (AUDIO.activeFor(panel)) {
                org.joml.Vector3d src = panel.transform.position;
                AUDIO.update(panel,
                    (float) src.x, (float) src.y, (float) src.z,
                    lx, ly, lz,
                    fx, fy, fz,
                    ux, uy, uz);
            }
        }
    }
    public static boolean isMinecraftWindow(long hwnd) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ||
            mc.getWindow() == null || mc.getWindow().handle() == 0) return false;
        try {
            return GLFWNativeWin32.glfwGetWin32Window(mc.getWindow().handle()) == hwnd;
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("Could not identify the Minecraft HWND: {}", e.toString());
            return false;
        }
    }
    public static String firstCaptureFailure() {
        for (AppWindowEntity panel : PANELS) {
            if (panel.captureFailure() != null) return panel.info.title() + ": " + panel.captureFailure();
        }
        return rendererStatus;
    }
    public static void openLauncher() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new AppLauncherScreen());
    }
    @Override public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("wincraft", "controls"));
        KeyMapping launcher = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.wincraft.launcher", InputConstants.Type.KEYSYM, InputConstants.KEY_B, category));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
            ClientCommands.literal("gui")
                .then(ClientCommands.literal("on").executes(context -> {
                    setHelpHudEnabled(true);
                    context.getSource().sendFeedback(Component.literal("WinCraft help HUD is on. Use /gui off to hide it."));
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    setHelpHudEnabled(false);
                    context.getSource().sendFeedback(Component.literal("WinCraft help HUD is off."));
                    return 1;
                }))
                .executes(context -> {
                    context.getSource().sendFeedback(Component.literal("WinCraft help HUD: " + (helpHudEnabled ? "on" : "off") + ". Use /gui on or /gui off."));
                    return 1;
                })
        ));
        RENDERER.register();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null && !PANELS.isEmpty()) closeAll();
            if (mc.player != null && mc.level != null) {
                Vec3 eye = mc.player.getEyePosition();
                Vec3 look = mc.player.getLookAngle();
                Vector3d eyePos = new Vector3d(eye.x, eye.y, eye.z);
                Vector3d direction = new Vector3d(look.x, look.y, look.z);
                for (AppWindowEntity panel : PANELS) {
                    panel.transform.updateCameraPose(eyePos, direction, mc.player.getYRot(), mc.player.getXRot());
                }
                updateSpatialAudio();
            }
            // Poll owned dialogs often enough that ordinary app popups appear
            // in-world promptly, without enumerating the desktop every tick.
            if (++popupScanTicks >= 4) {
                popupScanTicks = 0;
                scanOwnedPopups(mc);
            }
            // Every ~20 ticks check whether source windows have been closed
            // by the user from Windows, and if so, detach the in-game panel.
            if (++closedWindowScanTicks >= 20) {
                closedWindowScanTicks = 0;
                scanClosedWindows();
            }
            while (launcher.consumeClick()) openLauncher();
        });
        // Native WGC/WGL teardown can block inside the AMD driver while Windows is
        // destroying the game window. The OS releases those process-owned handles
        // moments later, so avoid a synchronous native/GL teardown on exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            AUDIO.stop();
            WindowInputHandler.release();
        });
    }
    /** Place an app directly into the world in front of the player. */
    public static AppWindowEntity placePanel(WindowHandleInfo info) {
        return placePanel(info, false, 0);
    }

    private static AppWindowEntity placePanel(WindowHandleInfo info, boolean ownedPopup, long ownerHwnd) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (info == null || mc.player == null) return null;
        WindowInputHandler.release();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle().normalize();

        AppWindowEntity ownerPanel = null;
        if (ownedPopup && ownerHwnd != 0) {
            for (AppWindowEntity p : PANELS) {
                if (p.info.hwnd() == ownerHwnd) {
                    ownerPanel = p;
                    break;
                }
            }
        }

        MatrixTransform3D transform;
        boolean readOnly = isMinecraftWindow(info.hwnd());

        if (ownerPanel != null) {
            // Place the popup 1 block in front of the actual owner window, facing the same direction
            org.joml.Vector3d normal = new org.joml.Vector3d(0, 0, 1);
            new org.joml.Matrix4d()
                .rotateZ(ownerPanel.transform.roll)
                .rotateY(ownerPanel.transform.yaw)
                .rotateX(ownerPanel.transform.pitch)
                .transformDirection(normal);
            normal.normalize();

            double offsetDistance = 1.0; // 1 block in front of the owner panel
            org.joml.Vector3d pos = new org.joml.Vector3d(ownerPanel.transform.position)
                .add(normal.x * offsetDistance, normal.y * offsetDistance, normal.z * offsetDistance);

            // If the player is standing very close between the owner panel and 1.0 block, adjust so it stays visible
            double distToEye = pos.distance(new org.joml.Vector3d(eye.x, eye.y, eye.z));
            if (distToEye < 0.45) {
                double ownerDist = ownerPanel.transform.position.distance(new org.joml.Vector3d(eye.x, eye.y, eye.z));
                offsetDistance = Math.max(0.25, Math.min(1.0, ownerDist * 0.5));
                pos = new org.joml.Vector3d(ownerPanel.transform.position)
                    .add(normal.x * offsetDistance, normal.y * offsetDistance, normal.z * offsetDistance);
            }

            float aspect = Math.clamp((float)info.height() / Math.max(1, info.width()), .25f, 2.0f);
            float popupWidth;
            if (ownerPanel.info.width() > 0 && info.width() > 0) {
                float ratio = (float)info.width() / (float)ownerPanel.info.width();
                popupWidth = Math.clamp(ownerPanel.transform.width * ratio, 0.8f, ownerPanel.transform.width);
            } else {
                popupWidth = Math.clamp(ownerPanel.transform.width * 0.75f, 0.8f, 2.0f);
            }
            float popupHeight = Math.clamp(popupWidth * aspect, 0.4f, 3.0f);

            transform = new MatrixTransform3D(
                pos, ownerPanel.transform.pitch, ownerPanel.transform.yaw, ownerPanel.transform.roll,
                popupWidth, popupHeight);

            if (ownerPanel.transform.followCamera) {
                transform.pinToCamera(new org.joml.Vector3d(eye.x, eye.y, eye.z),
                    new org.joml.Vector3d(look.x, look.y, look.z),
                    mc.player.getYRot(), mc.player.getXRot());
            }
        } else {
            double placementDistance = 2.4;
            if (mc.level != null) {
                Vec3 rayEnd = eye.add(look.scale(8.0));
                var blockHit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                    eye, rayEnd, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
                if (blockHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                    placementDistance = Math.min(placementDistance,
                        Math.max(.35, eye.distanceTo(blockHit.getLocation()) - .35));
                }
            }
            Vec3 pos = eye.add(look.scale(placementDistance));
            float aspect = Math.clamp((float)info.height() / Math.max(1, info.width()), .25f, 2.0f);
            transform = new MatrixTransform3D(
                new Vector3d(pos.x, pos.y, pos.z),
                (float)Math.asin(Math.clamp(look.y, -1, 1)),
                (float)Math.atan2(-look.x, -look.z), 0,
                2.2f, Math.clamp(2.2f * aspect, .5f, 3.5f));
        }

        AppWindowEntity panel = new AppWindowEntity(info, transform, readOnly, ownedPopup, ownerHwnd);
        PANELS.add(panel);
        com.wincraft.client.render.TransformStorage.save(info.title(), transform);
        LOG.info("Placed '{}' in the world{}{}", info.title(),
            readOnly ? " (read-only Minecraft view)" : "", ownedPopup ? " (owned dialog in front of parent)" : "");
        return panel;
    }

    private static void scanOwnedPopups(net.minecraft.client.Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || PANELS.isEmpty()) return;
        try {
            long[] owners = PANELS.stream().mapToLong(panel -> panel.info.hwnd()).distinct().toArray();
            for (OwnedWindowInfo popup : NativeCaptureBridge.enumerateOwnedPopups(owners)) {
                boolean alreadyAttached = PANELS.stream().anyMatch(panel -> panel.info.hwnd() == popup.hwnd());
                if (alreadyAttached) continue;
                placePanel(popup.asWindow(), true, popup.ownerHwnd());
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("Owned dialog scan failed: {}", e.toString());
        }
    }

    /**
     * Detect panels whose source Windows app has been closed by the user
     * (e.g. via Alt+F4 or the taskbar) and remove them from the game world.
     */
    private static void scanClosedWindows() {
        if (PANELS.isEmpty()) return;
        for (AppWindowEntity panel : PANELS) {
            if (panel.sourceClosed()) continue;
            if (panel.ownedPopup && panel.ownerHwnd != 0) {
                boolean ownerAlive = PANELS.stream().anyMatch(p -> p.info.hwnd() == panel.ownerHwnd && !p.sourceClosed());
                if (!ownerAlive) {
                    LOG.info("Owner window for popup '{}' was closed – detaching popup.", panel.info.title());
                    panel.markSourceClosed();
                    detach(panel);
                    continue;
                }
            }
            try {
                if (!NativeCaptureBridge.isWindowOpen(panel.info.hwnd())) {
                    LOG.info("Source window '{}' was closed from Windows – detaching panel.", panel.info.title());
                    panel.markSourceClosed();
                    detach(panel);
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.debug("Window-open check failed for '{}': {}", panel.info.title(), e.toString());
            }
        }
    }
    public static void detach(AppWindowEntity panel) {
        if (!PANELS.contains(panel)) return;
        AUDIO.stopIfPanel(panel);
        WindowInputHandler.sourceClosed(panel);
        // Always remove from the list immediately — prevents repeated error spam
        // even if WGL teardown fails. The WGL/GL cleanup must happen on the render thread.
        PANELS.remove(panel);
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            if (!panel.closeOnRenderThread()) {
                LOG.warn("WGL interop teardown incomplete for '{}'; GL handles may leak.", panel.info.title());
            }
        });
    }
    /** Called from the render thread so WGL resources can be detached safely. */
    public static boolean removeIfSourceClosed(AppWindowEntity panel) {
        if (panel.sourceClosed()) return true;
        if (NativeCaptureBridge.isWindowOpen(panel.info.hwnd())) return false;
        if (PANELS.contains(panel)) {
            panel.markSourceClosed();
            LOG.info("Removing panel for closed source window '{}'", panel.info.title());
            detach(panel);
        }
        return true;
    }
    public static void reportPanelFailure(AppWindowEntity panel, RuntimeException exception) {
        LOG.warn("Capture/render failed for '{}': {}", panel.info.title(), exception.toString());
        detach(panel);
    }
    private static void closeAll() {
        AUDIO.stop();
        for (AppWindowEntity panel : PANELS) detach(panel);
        WindowInputHandler.release();
    }
}
