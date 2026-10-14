package com.wincraft.client.mixin;

import com.wincraft.client.input.WindowInputHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Stops a previously held Minecraft attack from continuing through an app panel. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void wincraft$guardStartAttack(CallbackInfoReturnable<Boolean> cir) {
        if (WindowInputHandler.cursorActive()) {
            Minecraft.getInstance().options.keyAttack.setDown(false);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void wincraft$guardContinueAttack(boolean breaking, CallbackInfo ci) {
        if (WindowInputHandler.cursorActive()) {
            Minecraft.getInstance().options.keyAttack.setDown(false);
            ci.cancel();
        }
    }
}
