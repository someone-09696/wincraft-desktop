package com.wincraft.client.bridge;

/** PostMessage is best-effort: apps using raw input/UIPI/elevated processes may ignore it. */
public final class InputBridge {
    public static final int MOVE = 0x0200, LEFT_DOWN = 0x0201, LEFT_UP = 0x0202;
    public static final int RIGHT_DOWN = 0x0204, RIGHT_UP = 0x0205;
    public static final int WHEEL = 0x020A, HWHEEL = 0x020E;
    public static final int KEY_DOWN = 0x0100, KEY_UP = 0x0101;
    public static final int MK_LBUTTON = 1, MK_RBUTTON = 2;
    private InputBridge() { }
    public static boolean mouse(long hwnd, int msg, int x, int y, int flags) {
        NativeCaptureBridge.load(); return nMouse(hwnd, msg, x, y, flags);
    }
    public static boolean key(long hwnd, int msg, int vk, int scan, boolean repeat) {
        NativeCaptureBridge.load(); return nKey(hwnd, msg, vk, scan, repeat);
    }
    public static boolean text(long hwnd, char utf16) {
        NativeCaptureBridge.load(); return nText(hwnd, utf16);
    }
    public static int getCursorShape(long hwnd, int x, int y) {
        NativeCaptureBridge.load(); return nGetCursorShape(hwnd, x, y);
    }
    public static boolean dropFiles(long hwnd, int x, int y, String[] files) {
        NativeCaptureBridge.load(); return nDropFiles(hwnd, x, y, files);
    }
    public static void wakeApp(long hwnd) {
        if (hwnd == 0) return;
        NativeCaptureBridge.load(); nWakeApp(hwnd);
    }
    private static native boolean nMouse(long hwnd, int msg, int x, int y, int flags);
    private static native boolean nKey(long hwnd, int msg, int vk, int scan, boolean repeat);
    private static native boolean nText(long hwnd, int utf16);
    private static native int nGetCursorShape(long hwnd, int x, int y);
    private static native boolean nDropFiles(long hwnd, int x, int y, String[] files);
    private static native void nWakeApp(long hwnd);
}
