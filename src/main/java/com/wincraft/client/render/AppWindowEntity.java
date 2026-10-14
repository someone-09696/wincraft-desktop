package com.wincraft.client.render;

import com.wincraft.client.bridge.NativeCaptureBridge;
import com.wincraft.client.bridge.WindowHandleInfo;
import java.util.UUID;
/** Client-local spatial surface, NOT a server/world Entity; never transmits HWND to peers. */
public final class AppWindowEntity implements AutoCloseable {
    public final UUID id = UUID.randomUUID();
    public final WindowHandleInfo info;
    public final MatrixTransform3D transform;
    public final boolean ownedPopup;
    public final long ownerHwnd;
    private final boolean readOnly;
    private NativeCaptureBridge capture;
    private int glTexture;
    private String captureFailure;
    private boolean sourceClosed;
    public AppWindowEntity(WindowHandleInfo info, MatrixTransform3D transform, boolean readOnly) {
        this(info, transform, readOnly, false, 0);
    }
    public AppWindowEntity(WindowHandleInfo info, MatrixTransform3D transform, boolean readOnly,
                           boolean ownedPopup, long ownerHwnd) {
        this.info = info; this.transform = transform; this.readOnly = readOnly;
        this.ownedPopup = ownedPopup; this.ownerHwnd = ownerHwnd;
        // Capturing Minecraft itself is supported as a view-only panel. Input
        // routing remains disabled by WindowInputHandler for readOnly panels.
        // WGC supplies a separate frame, so the panel can show the game without
        // routing its mouse/key input back into the same Minecraft window.
    }
    public NativeCaptureBridge capture() { return capture; }
    public boolean readOnly() { return readOnly; }
    public String captureFailure() { return captureFailure; }
    public boolean sourceClosed() { return sourceClosed; }
    public void markSourceClosed() { sourceClosed = true; }
    public void markCaptureFailed(RuntimeException failure) {
        captureFailure = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
    public void setCapture(NativeCaptureBridge capture, int texture) { this.capture = capture; this.glTexture = texture; }
    public int glTexture() { return glTexture; }
    public boolean closeOnRenderThread() {
        if (capture != null) {
            if (!capture.closeOnRenderThread()) return false;
            capture = null;
        }
        if (glTexture != 0) { org.lwjgl.opengl.GL11.glDeleteTextures(glTexture); glTexture = 0; }
        return true;
    }
    @Override public void close() { closeOnRenderThread(); }
}
