package com.wincraft.client.render;

import com.wincraft.client.bridge.NativeCaptureBridge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
/** Native WGL interop owns texture storage; never glTexImage2D/glGenerateMipmap here. */
public final class TextureManager3D {
    private TextureManager3D() { }
    public static void ensureAttached(AppWindowEntity surface) {
        if (surface.capture() != null) return;
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex); // glIsTexture must be true for WGL registration.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        try { surface.setCapture(new NativeCaptureBridge(surface.info.hwnd()), tex); }
        catch (RuntimeException e) { GL11.glDeleteTextures(tex); throw e; }
    }
}
