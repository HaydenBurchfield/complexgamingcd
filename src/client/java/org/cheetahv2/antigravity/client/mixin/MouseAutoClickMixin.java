package org.cheetahv2.antigravity.client.mixin;

import net.minecraft.client.Mouse;
import org.cheetahv2.antigravity.client.AutoClickManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While AutoClick is active, cancels any attempt by the game to re-capture
 * the cursor (lockCursor).  This keeps the cursor free ("tabbed out") even
 * if the window gains focus, until the user runs /toggleclick again.
 */
@Mixin(Mouse.class)
public class MouseAutoClickMixin {

    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void cgc_blockLockIfAutoClick(CallbackInfo ci) {
        if (AutoClickManager.isActive()) {
            ci.cancel();
        }
    }
}
