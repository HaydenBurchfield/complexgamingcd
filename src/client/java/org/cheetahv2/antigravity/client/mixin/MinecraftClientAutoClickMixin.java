package org.cheetahv2.antigravity.client.mixin;

import net.minecraft.client.MinecraftClient;
import org.cheetahv2.antigravity.client.AutoClickManager;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects right before handleInputEvents() runs inside MinecraftClient.tick().
 *
 * This fires every tick regardless of window focus (multiplayer ticks continue
 * in the background). By setting LMB pressed HERE — before the game reads key
 * states — it overrides the GLFW key-up that fires on focus loss, so the click
 * is seen as held both focused and unfocused.
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientAutoClickMixin {

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;handleInputEvents()V"
        )
    )
    private void cgc_autoClickBeforeInput(CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient)(Object)this;
        if (!AutoClickManager.shouldClick()) return;
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        net.minecraft.client.option.KeyBinding.setKeyPressed(
            net.minecraft.client.util.InputUtil.Type.MOUSE
                .createFromCode(GLFW.GLFW_MOUSE_BUTTON_LEFT),
            true
        );
    }
}
