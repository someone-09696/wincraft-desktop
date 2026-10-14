package com.wincraft.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wincraft.client.WinCraftClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Composite captured panels after Iris/shader post-processing and below the HUD. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private RenderTarget mainRenderTarget;

    @Inject(method = "render", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V",
        shift = At.Shift.BEFORE))
    private void wincraft$renderPanels(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        WinCraftClient.renderPanelsAfterPostProcessing(mainRenderTarget);
    }
}
