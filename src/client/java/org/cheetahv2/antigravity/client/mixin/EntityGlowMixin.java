package org.cheetahv2.antigravity.client.mixin;

import net.minecraft.entity.Entity;
import org.cheetahv2.antigravity.client.detection.CustomGlowManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two injections into Entity:
 *
 * 1. isGlowing()        — makes the entity show the vanilla white outline.
 * 2. getTeamColorValue() — overrides the outline COLOUR so it uses whatever
 *                          colour was registered in CustomGlowManager instead
 *                          of always defaulting to white (0xFFFFFF).
 *
 * Both are purely client-side; no packets are sent.
 */
@Mixin(Entity.class)
public class EntityGlowMixin {

    /** Step 1 — force the entity into the glowing render pass. */
    @Inject(method = "isGlowing", at = @At("RETURN"), cancellable = true)
    private void cgc_overrideGlow(CallbackInfoReturnable<Boolean> cir) {
        // If already glowing for vanilla reasons, leave it alone
        if (cir.getReturnValue()) return;

        Entity self = (Entity)(Object) this;
        if (CustomGlowManager.activeGlowIds.contains(self.getId())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Step 2 — override the outline colour.
     *
     * Minecraft reads the glow outline colour from getTeamColorValue().
     * Without this injection the outline is always white, regardless of
     * what colour was registered in CustomGlowManager.
     */
    @Inject(method = "getTeamColorValue", at = @At("RETURN"), cancellable = true)
    private void cgc_overrideTeamColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity)(Object) this;
        if (CustomGlowManager.activeGlowIds.contains(self.getId())) {
            // Replace the vanilla team colour with our registered colour
            cir.setReturnValue(CustomGlowManager.getGlowColor(self.getName().getString()));
        }
    }
}