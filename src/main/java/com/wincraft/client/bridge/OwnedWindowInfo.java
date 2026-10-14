package com.wincraft.client.bridge;

/** A regular same-process top-level dialog owned by an app window. */
public record OwnedWindowInfo(long hwnd, String title, int width, int height, long ownerHwnd) {
    public WindowHandleInfo asWindow() {
        return new WindowHandleInfo(hwnd, title, width, height);
    }
}
