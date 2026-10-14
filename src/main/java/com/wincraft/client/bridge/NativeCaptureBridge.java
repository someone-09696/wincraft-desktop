package com.wincraft.client.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns an HWND capture. All session/GL methods must be invoked on the SAME current OpenGL thread. */
public final class NativeCaptureBridge implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("wincraft/capture");
    private static volatile boolean loaded;
    private long handle;
    private boolean frameLocked;

    public static synchronized void load() {
        if (loaded) return;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ||
                !"amd64".equals(System.getProperty("os.arch")))
            throw new IllegalStateException("WinCraft requires Windows x64");
        String resource = "/natives/windows-x86_64/wincraft_native.dll";
        try (InputStream in = NativeCaptureBridge.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing native DLL in JAR: " + resource);
            Path tmp = Files.createTempFile("wincraft-", ".dll");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load WinCraft native capture library", e);
        }
    }

    public static List<WindowHandleInfo> enumerate() { load(); return Arrays.asList(nEnumerate()); }
    public static OwnedWindowInfo[] enumerateOwnedPopups(long[] owners) { load(); return nEnumerateOwnedPopups(owners); }
    public static List<InstalledAppInfo> enumerateInstalledApps() { load(); return Arrays.asList(nEnumerateInstalledApps()); }
    public static byte[] extractIcon(String path, long hwnd) { load(); return nExtractIcon(path, hwnd); }
    public static long detectNewWindow(long[] existingHwnds, long timeoutMs) { load(); return nDetectNewWindow(existingHwnds, timeoutMs); }
    public static String chooseExecutable() { load(); return nChooseExecutable(); }
    public static String chooseFile() { load(); return nChooseFile(); }
    public static boolean launchApplication(String path) {
        load(); return path != null && nLaunchApplication(path);
    }
    public static boolean launchApplication(String path, String arguments) {
        load(); return path != null && nLaunchApplicationWithArguments(path, arguments == null ? "" : arguments);
    }
    /** Checks HWND liveness without changing foreground focus or touching capture/GL state. */
    public static boolean isWindowOpen(long hwnd) { return hwnd != 0 && nIsWindow(hwnd); }
    public NativeCaptureBridge(long hwnd) { load(); handle = nCreate(hwnd); }
    /** Must be paired with endFrame() after GL draw, including on error. */
    public synchronized int beginFrame(int glTextureId) {
        if (handle == 0 || frameLocked) throw new IllegalStateException("Invalid capture frame lifecycle");
        int result = nUpdate(handle, glTextureId);
        if (nWidth(handle) > 0) frameLocked = true;
        return result;
    }
    public synchronized void endFrame() {
        if (frameLocked) { nUnlock(handle); frameLocked = false; }
    }
    public synchronized int width() { return handle == 0 ? 0 : nWidth(handle); }
    public synchronized int height() { return handle == 0 ? 0 : nHeight(handle); }
    /** Detach WGL on the current render thread, then finish WGC teardown off-thread. */
    public synchronized boolean closeOnRenderThread() {
        if (handle == 0) return true;
        long closingHandle = handle;
        try {
            if (frameLocked) {
                nUnlock(closingHandle);
                frameLocked = false;
            }
        } catch (RuntimeException e) {
            LOG.error("WGL unlock failed during capture teardown: {}", e.toString());
            return false;
        }
        try {
            if (!nDetachGl(closingHandle)) return false;
        } catch (RuntimeException e) {
            LOG.error("WGL interop detach failed during capture teardown: {}", e.toString());
            return false;
        }

        handle = 0;
        frameLocked = false;
        Thread.ofPlatform().name("WinCraft WGC cleanup").daemon(true).start(() -> {
            try {
                nDestroy(closingHandle);
            } catch (RuntimeException e) {
                LOG.warn("Background Windows capture cleanup failed: {}", e.toString());
            }
        });
        return true;
    }
    @Override public void close() { closeOnRenderThread(); }
    private static native WindowHandleInfo[] nEnumerate();
    private static native OwnedWindowInfo[] nEnumerateOwnedPopups(long[] owners);
    private static native InstalledAppInfo[] nEnumerateInstalledApps();
    private static native byte[] nExtractIcon(String path, long hwnd);
    private static native long nDetectNewWindow(long[] existingHwnds, long timeoutMs);
    private static native String nChooseExecutable();
    private static native String nChooseFile();
    private static native boolean nLaunchApplication(String path);
    private static native boolean nLaunchApplicationWithArguments(String path, String arguments);
    private static native boolean nIsWindow(long hwnd);
    private static native long nCreate(long hwnd);
    private static native int nUpdate(long handle, int glTextureId);
    private static native void nUnlock(long handle);
    private static native boolean nDetachGl(long handle);
    private static native int nWidth(long handle);
    private static native int nHeight(long handle);
    private static native void nDestroy(long handle);
}
