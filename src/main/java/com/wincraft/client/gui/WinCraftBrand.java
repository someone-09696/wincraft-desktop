package com.wincraft.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared WinCraft pixel mark and palette for in-game screens. */
public final class WinCraftBrand {
    public static final int INK = 0xE9101420;
    public static final int RAIL = 0xEC151B27;
    public static final int ACCENT = 0xFF55D6F2;
    public static final int MUTED = 0xFF9EADBE;

    private WinCraftBrand() { }

    /** Draws the app-window W mark on a 16 by 16 pixel grid. */
    public static void drawMark(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF0A1019);
        graphics.fill(x + 1, y + 1, x + 15, y + 2, 0xFF496D86);
        graphics.fill(x + 1, y + 2, x + 2, y + 14, 0xFF496D86);
        graphics.fill(x + 2, y + 2, x + 14, y + 4, 0xFF1E3042);
        graphics.fill(x + 2, y + 2, x + 14, y + 3, ACCENT);
        graphics.fill(x + 2, y + 4, x + 14, y + 14, 0xFF0D1825);
        graphics.fill(x + 4, y + 3, x + 5, y + 4, 0xFFB8F4FF);
        graphics.fill(x + 6, y + 3, x + 7, y + 4, 0xFF55D6F2);
        graphics.fill(x + 8, y + 3, x + 9, y + 4, 0xFF8C83FF);

        // Block-built W: three rising strokes inside the desktop window.
        graphics.fill(x + 4, y + 6, x + 6, y + 10, ACCENT);
        graphics.fill(x + 5, y + 9, x + 7, y + 12, ACCENT);
        graphics.fill(x + 6, y + 11, x + 8, y + 13, 0xFFB8F4FF);
        graphics.fill(x + 8, y + 11, x + 10, y + 13, 0xFFB8F4FF);
        graphics.fill(x + 9, y + 9, x + 11, y + 12, ACCENT);
        graphics.fill(x + 10, y + 6, x + 12, y + 10, ACCENT);
        graphics.fill(x + 2, y + 14, x + 14, y + 15, 0xFF293D51);
    }
}
