package org.cheetahv2.antigravity.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches EVERY action bar message.
 *
 * Bukkit servers usually send the action bar via the title system's
 * set-action-bar packet, which goes straight to InGameHud.setOverlayMessage
 * and never fires Fabric's ClientReceiveMessageEvents.GAME — which is why
 * "Mood Swings V has made you feel Lazy!" was invisible to the mod. Hooking
 * here covers both that path and the game-message overlay path.
 */
@Mixin(InGameHud.class)
public class InGameHudActionBarMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void antigravity$onActionBar(Text message, boolean tinted, CallbackInfo ci) {
        if (message == null) return;
        String plain = message.getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        if (plain.isEmpty()) return;
        AntigravityClient.MOOD_HUD.onActionBar(plain);
    }
}
