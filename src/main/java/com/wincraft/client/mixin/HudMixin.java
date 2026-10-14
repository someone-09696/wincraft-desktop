package com.wincraft.client.mixin;

import com.wincraft.client.input.WindowInputHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Extracts WinCraft's HUD overlays and the in-app cursor.
 * Captured panels are composited after Minecraft presents the finished frame.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    private static void wc$drawMinecraftCrosshair(GuiGraphicsExtractor g, int x, int y) {
        // Small, crisp 7x7 precision crosshair (half the size of vanilla)
        int outline = 0xAA000000;
        g.fill(x - 1, y - 3, x + 2, y + 4, outline);
        g.fill(x - 3, y - 1, x + 4, y + 2, outline);
        g.fill(x, y - 2, x + 1, y + 3, 0xFFFFFFFF);
        g.fill(x - 2, y, x + 3, y + 1, 0xFFFFFFFF);
    }

    private static void wc$drawFileCursor(GuiGraphicsExtractor g, int x, int y, String name,
                                           net.minecraft.client.gui.Font font) {
        int left = x + 10, top = y + 8;
        // Small paper glyph follows the reticle while a selected file is armed.
        g.fill(left - 1, top - 1, left + 12, top + 15, 0xF010151D);
        g.fill(left, top, left + 11, top + 14, 0xFFF1F5FA);
        g.fill(left + 7, top, left + 11, top + 4, 0xFF65C8F2);
        g.fill(left + 2, top + 6, left + 8, top + 7, 0xFF526477);
        g.fill(left + 2, top + 9, left + 8, top + 10, 0xFF526477);

        String label = name == null || name.isBlank() ? "File" : name;
        while (label.length() > 4 && font.width(label) > 128) {
            label = label.substring(0, label.length() - 4) + "...";
        }
        int textX = left + 15, textY = top + 1;
        int labelWidth = Math.min(136, font.width(label)) + 6;
        g.fill(textX - 3, textY - 2, textX + labelWidth, textY + 10, 0xE810151D);
        g.text(font, label, textX, textY, 0xFFFFFFFF, false);
        String hint = "Click panel to drop  ·  Right-click cancels";
        int hintWidth = Math.min(200, font.width(hint)) + 6;
        g.fill(textX - 3, textY + 10, textX + hintWidth, textY + 21, 0xD810151D);
        g.text(font, hint, textX, textY + 11, 0xFFB9D9E8, false);
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void wc$cursor(GuiGraphicsExtractor g, DeltaTracker delta, CallbackInfo ci) {
        int x = g.guiWidth() / 2, y = g.guiHeight() / 2;
        var font = Minecraft.getInstance().font;

        if (WindowInputHandler.fileDragActive()) {
            wc$drawMinecraftCrosshair(g, x, y);
            wc$drawFileCursor(g, x, y, WindowInputHandler.fileDragName(), font);
            ci.cancel();
            return;
        }

        boolean overPanel = WindowInputHandler.cursorActive();
        if (!overPanel) return; // let Minecraft draw its normal crosshair

        // Standard in-app cursor shapes (reported by the captured window's cursor)
        int shape = WindowInputHandler.cursorShape();
        int dark = 0xDD101010, bright = 0xFFFFFFFF;
        if (shape == 1 || (shape == -1 && WindowInputHandler.textCursor())) {
            // I-beam text cursor
            g.fill(x - 2, y - 5, x + 3, y - 4, dark);
            g.fill(x - 1, y - 4, x + 2, y - 3, bright);
            g.fill(x - 1, y - 3, x + 2, y + 4, dark);
            g.fill(x, y - 3, x + 1, y + 3, bright);
            g.fill(x - 1, y + 3, x + 2, y + 4, bright);
        } else if (shape == 2) {
            // Hand / pointer
            g.fill(x - 1, y - 5, x + 1, y + 2, dark);
            g.fill(x, y - 4, x + 1, y + 1, bright);
            g.fill(x - 2, y - 1, x + 3, y + 3, dark);
            g.fill(x - 1, y, x + 2, y + 2, bright);
        } else if (shape == 3) {
            // Horizontal resize
            g.fill(x - 5, y - 1, x + 6, y + 2, dark);
            g.fill(x - 4, y, x + 5, y + 1, bright);
            g.fill(x - 4, y - 2, x - 3, y + 3, bright);
            g.fill(x + 3, y - 2, x + 4, y + 3, bright);
        } else if (shape == 4) {
            // Vertical resize
            g.fill(x - 1, y - 5, x + 2, y + 6, dark);
            g.fill(x, y - 4, x + 1, y + 5, bright);
            g.fill(x - 2, y - 4, x + 3, y - 3, bright);
            g.fill(x - 2, y + 4, x + 3, y + 5, bright);
        } else {
            // Default in-app crosshair — plain white cross, same as Minecraft
            wc$drawMinecraftCrosshair(g, x, y);
        }
        ci.cancel();
    }
}
