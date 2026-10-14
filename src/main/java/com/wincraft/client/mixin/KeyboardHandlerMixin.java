package com.wincraft.client.mixin;

import com.wincraft.client.input.WindowInputHandler;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void wc$key(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (WindowInputHandler.key(action, event.key(), event.scancode(), event.modifiers())) ci.cancel();
    }
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void wc$char(long window, CharacterEvent event, CallbackInfo ci) {
        if (WindowInputHandler.text(event.codepoint())) ci.cancel();
    }
}
