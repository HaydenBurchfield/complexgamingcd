package org.cheetahv2.antigravity.client.utility;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AutoCookieModule — smart version
 *
 * Triggers when the player is frozen (getFrozenTicks > 0 or Slowness amp ≥ 5).
 *
 * Smart behaviour:
 *  - Aborts any in-progress attack or block-use before eating so it doesn't
 *    cancel an attack mid-swing or interrupt shielding.
 *  - Waits triggerDelayMs after freeze onset, respects cookieCooldownMs between uses.
 *  - Swaps cookie to hotbar slot 8, eats it, swaps back to original slot next tick,
 *    then restores the previously selected hotbar slot.
 *
 * Tick-delay state machine (one packet per tick):
 *   IDLE → WAIT_DELAY → SWAP_IN → USE → SWAP_BACK → IDLE
 */
public class AutoCookieModule implements UtilityModule {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled      = false;
        /** How long after freeze is detected before eating (ms). 0 = instant. */
        public int triggerDelayMs   = 200;
        /** Minimum time between cookie uses (ms). */
        public int cookieCooldownMs = 2000;
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_cookie.json");

    // ── State machine ─────────────────────────────────────────────────────
    private enum Phase { IDLE, WAIT_DELAY, SWAP_IN, USE, SWAP_BACK }
    private Phase phase          = Phase.IDLE;
    private long  frozeAt        = -1L;
    private long  lastCookieUseMs = 0L;

    // Saved during SWAP_IN, used during SWAP_BACK
    private int  cookieInvSlot   = -1;  // 0-35 slot cookie came from (-1 = was already in slot 8)
    private int  prevHotbarSlot  = -1;  // hotbar slot selected before we touched anything

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Auto Cookie"; }
    @Override public String getDescription()    { return "Eats Santa's Cookie when frozen"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (!config.enabled || mc.player == null || mc.world == null) return;

        boolean frozen = isFrozen(mc);
        long    now    = System.currentTimeMillis();

        switch (phase) {

            case IDLE: {
                if (!frozen) { frozeAt = -1L; return; }
                if (frozeAt < 0) frozeAt = now;
                if (now - lastCookieUseMs < config.cookieCooldownMs) return;
                phase = Phase.WAIT_DELAY;
                break;
            }

            case WAIT_DELAY: {
                if (!frozen) { reset(); return; }
                if (now - frozeAt < config.triggerDelayMs) break;

                // SMART gates — hold here (stay frozen-aware) until clear:
                //  • don't interrupt active item use (blocking with a shield,
                //    eating, drawing a bow)
                //  • don't fight another module's inventory sequence
                //  • don't send swaps while a container screen is open
                if (ModuleSync.handsInUse(mc)) break;
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("cookie", 1200)) break;

                phase = Phase.SWAP_IN;
                break;
            }

            case SWAP_IN: {
                if (!frozen) { reset(); return; }

                // Abort any in-progress attack/use so we don't cancel a swing
                abortCurrentAction(mc);

                // Find cookie
                int slot = findCookie(mc);
                if (slot < 0) { reset(); return; }  // no cookie, give up

                prevHotbarSlot = mc.player.getInventory().getSelectedSlot();

                if (slot == 7) {
                    // Already in hotbar 8 — skip the swap, go straight to USE
                    cookieInvSlot = -1;
                    phase = Phase.USE;
                } else {
                    cookieInvSlot = slot;
                    sendSwap(mc, slot, 7);  // swap cookie slot ↔ hotbar 8
                    phase = Phase.USE;      // USE runs next tick (packet already sent)
                }
                break;
            }

            case USE: {
                // One tick has passed since SWAP_IN — cookie is now in slot 8 on the server
                mc.player.getInventory().setSelectedSlot(7);
                mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(7));
                mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);

                lastCookieUseMs = now;
                frozeAt = -1L;

                if (cookieInvSlot >= 0) {
                    phase = Phase.SWAP_BACK;  // need to swap back next tick
                } else {
                    // Was already in slot 8, just restore selected slot
                    restoreHotbar(mc);
                    phase = Phase.IDLE;
                    ModuleSync.release("cookie");
                }
                break;
            }

            case SWAP_BACK: {
                // One tick has passed since USE — swap cookie back to original slot
                sendSwap(mc, cookieInvSlot, 7);
                restoreHotbar(mc);
                cookieInvSlot = -1;
                phase = Phase.IDLE;
                ModuleSync.release("cookie");
                break;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void reset() {
        phase         = Phase.IDLE;
        frozeAt       = -1L;
        cookieInvSlot = -1;
        prevHotbarSlot = -1;
        ModuleSync.release("cookie");
    }

    private void restoreHotbar(MinecraftClient mc) {
        int restoreTo = prevHotbarSlot >= 0 ? prevHotbarSlot : 0;
        mc.player.getInventory().setSelectedSlot(restoreTo);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(restoreTo));
        prevHotbarSlot = -1;
    }

    /**
     * Sends ABORT_DESTROY_BLOCK to cancel any in-progress mining/attack,
     * which prevents the cookie use from being dropped by a pending left-click action.
     */
    private void abortCurrentAction(MinecraftClient mc) {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                BlockPos.ORIGIN,
                Direction.DOWN
        ));
    }

    /**
     * Find Santa's Cookie in inventory.
     * Returns inventory slot 0-35, or -1 if not found.
     * Checks hotbar slot 8 (index 7) first, then main inv, then rest of hotbar.
     */
    private int findCookie(MinecraftClient mc) {
        // Slot 8 already?
        if (isCookie(mc.player.getInventory().getStack(7))) return 7;
        // Main inventory
        for (int i = 9; i < 36; i++) {
            if (isCookie(mc.player.getInventory().getStack(i))) return i;
        }
        // Rest of hotbar
        for (int i = 0; i < 7; i++) {
            if (isCookie(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /**
     * Sends a SWAP ClickSlotC2SPacket: swaps invSlot (0-35) with hotbar slot hotbarIdx (0-8).
     * PlayerScreenHandler screen-slot layout:
     *   hotbar 0-8   → screen slots 36-44
     *   main inv 9-35 → screen slots 9-35
     */
    private void sendSwap(MinecraftClient mc, int invSlot, int hotbarIdx) {
        if (mc.player == null) return;
        int screenSlot = invSlot < 9 ? 36 + invSlot : invSlot;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                mc.player.currentScreenHandler.syncId,
                mc.player.currentScreenHandler.getRevision(),
                (short) screenSlot,
                (byte)  hotbarIdx,
                SlotActionType.SWAP,
                new Int2ObjectOpenHashMap<>(),
                ItemStackHash.EMPTY
        ));
    }

    // ── Item / effect detection ───────────────────────────────────────────

    private boolean isCookie(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isOf(Items.COOKIE)) return false;
        if (stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME)) {
            return stack.getName().getString().toLowerCase().contains("santa");
        }
        return false;
    }

    private boolean isFrozen(MinecraftClient mc) {
        if (mc.player == null) return false;
        if (mc.player.getFrozenTicks() > 0) return true;
        if (mc.player.hasStatusEffect(StatusEffects.SLOWNESS)) {
            var effect = mc.player.getStatusEffect(StatusEffects.SLOWNESS);
            if (effect != null && effect.getAmplifier() >= 5) return true;
        }
        return false;
    }

    // ── Config persistence ────────────────────────────────────────────────
    @Override public void save() { ConfigHelper.save(FILE, config); }
    @Override public void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c != null) config = c;
    }

    public Config getConfig() { return config; }
}
