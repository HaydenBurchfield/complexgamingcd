package org.cheetahv2.antigravity.client.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import org.cheetahv2.antigravity.client.LockCamManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts Mouse#updateMouse() — the method that converts raw mouse deltas
 * into player yaw/pitch changes every tick.
 *
 * When LockCamManager is active we cancel the vanilla input and instead
 * force the player's yaw/pitch to the locked values every frame, so even
 * external jitter (e.g. held mouse) can't drift the camera.
 */
@Mixin(Mouse.class)
public class MouseLockMixin {

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void cgc_lockCamera(CallbackInfo ci) {
        if (!LockCamManager.isLocked()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        // Cancel the vanilla delta-application entirely
        ci.cancel();

        // Re-apply the locked angles every frame so nothing can drift them
        mc.player.setYaw(LockCamManager.getLockedYaw());
        mc.player.setPitch(LockCamManager.getLockedPitch());
    }
}
