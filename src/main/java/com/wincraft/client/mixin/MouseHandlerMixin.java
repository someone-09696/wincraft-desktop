package com.wincraft.client.mixin;

import com.wincraft.client.input.WindowInputHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    // 26.2 changed onPress(long,int,int,int) into onButton(long,MouseButtonInfo,int).
    @Inject(method="onButton", at=@At("HEAD"), cancellable=true)
    private void wc$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (WindowInputHandler.button(info.button(), action != 0, info.modifiers())) ci.cancel();
    }
    @Inject(method="onScroll", at=@At("HEAD"), cancellable=true)
    private void wc$scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        // Always shield the vanilla hotbar wheel while the reticle is on an
        // app surface, even if a fractional/unsupported delta was not routed.
        if (WindowInputHandler.scroll(horizontal, vertical) || WindowInputHandler.cursorActive()) ci.cancel();
    }
}
