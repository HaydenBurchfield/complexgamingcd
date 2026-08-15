package org.cheetahv2.antigravity.client.utility;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.detection.RunicObstructionManager;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AutoJackOfHeartsModule
 *
 * When the local player is runiced (RunicObstructionManager.isSelfRuniced()),
 * automatically right-click uses a "Jack of Hearts" (minecraft:paper with
 * "jack" in the display name) from inventory.
 *
 * Flow:
 *  1. Detect runic debuff
 *  2. Wait <triggerDelayMs> ms
 *  3. Find Jack of Hearts in inventory (main inv first, then hotbar)
 *  4. If not already in slot 8: SWAP it to hotbar slot 8
 *  5. Select slot 8 + interactItem (right-click use)
 *  6. Next tick: swap back and restore selected slot
 *  7. Enforce <useCooldownMs> before triggering again
 */
public class AutoJackOfHeartsModule implements UtilityModule {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled         = false;
        /** How long after runic is detected before using the card (ms). 0 = instant. */
        public int triggerDelayMs      = 100;
        /** Minimum time between Jack of Hearts uses (ms). 0 = no cooldown limit. */
        public int useCooldownMs       = 3000;
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_jack_of_hearts.json");

    // ── State ─────────────────────────────────────────────────────────────
    private long    runicedAt        = -1L;
    private long    lastUseMs        = 0L;
    private boolean useInProgress    = false;
    private int     savedInvSlot     = -1;   // inventory slot the card came from
    private int     savedHotbarSlot  = -1;   // previously selected hotbar slot
    private boolean needsSwapBack    = false; // swap back on the next tick

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Auto Jack of Hearts"; }
    @Override public String getDescription()    { return "Uses Jack of Hearts when runiced"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (!config.enabled || mc.player == null || mc.world == null) return;

        // ── Swap-back phase: runs the tick AFTER we used the card ──────────
        if (needsSwapBack) {
            needsSwapBack = false;
            performSwapBack(mc);
            return;
        }

        boolean runiced = RunicObstructionManager.isSelfRuniced();

        if (!runiced) {
            runicedAt     = -1L;
            useInProgress = false;
            return;
        }

        long now = System.currentTimeMillis();

        // First tick we notice the runic
        if (runicedAt < 0) {
            runicedAt     = now;
            useInProgress = true;
        }

        if (!useInProgress) return;
        if (now - lastUseMs < config.useCooldownMs) return;
        if (now - runicedAt < config.triggerDelayMs) return;

        // SMART gates — keep useInProgress so we retry next tick instead of
        // losing the trigger:
        //  • don't interrupt blocking/eating
        //  • don't fight another module's inventory sequence / open container
        if (ModuleSync.handsInUse(mc)) return;
        if (ModuleSync.inventoryLocked(mc)) return;
        if (!ModuleSync.acquire("jack", 800)) return;

        useInProgress = false;
        tryUseCard(mc);
    }

    private void tryUseCard(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Check if hotbar slot 8 (index 7) already has the card
        ItemStack slot7 = mc.player.getInventory().getStack(7);
        if (isJackOfHearts(slot7)) {
            useCardFromSlot7(mc, -1);
            return;
        }

        // Search main inventory first (slots 9-35), then rest of hotbar (0-6)
        int cardSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (isJackOfHearts(mc.player.getInventory().getStack(i))) { cardSlot = i; break; }
        }
        if (cardSlot == -1) {
            for (int i = 0; i < 7; i++) {
                if (isJackOfHearts(mc.player.getInventory().getStack(i))) { cardSlot = i; break; }
            }
        }
        if (cardSlot == -1) { ModuleSync.release("jack"); return; } // no card found

        savedInvSlot    = cardSlot;
        savedHotbarSlot = mc.player.getInventory().getSelectedSlot();

        swapToSlot8(mc, cardSlot);
        useCardFromSlot7(mc, cardSlot);
    }

    private void useCardFromSlot7(MinecraftClient mc, int pulledFromSlot) {
        if (mc.player == null || mc.interactionManager == null) return;

        mc.player.getInventory().setSelectedSlot(7);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(7));
        mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);

        lastUseMs = System.currentTimeMillis();
        runicedAt = -1L;

        // Register 1:30 cooldown on the HUD
        AntigravityClient.ABILITY_COOLDOWN.triggerJackOfHearts();

        if (pulledFromSlot >= 0) {
            // Card was swapped in from another slot — swap back next tick
            needsSwapBack = true;
        } else {
            // Card was already in slot 8 — just restore the previously selected slot
            int restoreTo = savedHotbarSlot >= 0 ? savedHotbarSlot : 0;
            mc.player.getInventory().setSelectedSlot(restoreTo);
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(restoreTo));
            savedHotbarSlot = -1;
            ModuleSync.release("jack");
        }
    }

    private void performSwapBack(MinecraftClient mc) {
        if (mc.player == null || savedInvSlot < 0) return;

        swapToSlot8(mc, savedInvSlot); // reverses the original swap

        int restoreTo = savedHotbarSlot >= 0 ? savedHotbarSlot : 0;
        mc.player.getInventory().setSelectedSlot(restoreTo);
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(restoreTo));

        savedInvSlot    = -1;
        savedHotbarSlot = -1;
        ModuleSync.release("jack");
    }

    /**
     * SWAP ClickSlotC2SPacket: swaps invSlot (0-35) with hotbar slot 8 (index 7).
     * PlayerScreenHandler screen-slot layout:
     *   hotbar 0-8  → screen slots 36-44
     *   main inv 9-35 → screen slots 9-35
     */
    private void swapToSlot8(MinecraftClient mc, int invSlot) {
        if (mc.player == null) return;
        int screenSlot = invSlot < 9 ? 36 + invSlot : invSlot;
        mc.player.networkHandler.sendPacket(
                new ClickSlotC2SPacket(
                        mc.player.currentScreenHandler.syncId,
                        mc.player.currentScreenHandler.getRevision(),
                        (short) screenSlot,
                        (byte)  7,           // hotbar index 7 = slot 8
                        SlotActionType.SWAP,
                        new Int2ObjectOpenHashMap<>(),
                        ItemStackHash.EMPTY
                )
        );
    }

    // ── Item detection ────────────────────────────────────────────────────

    private boolean isJackOfHearts(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isOf(Items.PAPER)) return false;
        String name = stack.getName().getString().toLowerCase();
        return name.contains("jack") && name.contains("heart");
    }

    // ── Config persistence ────────────────────────────────────────────────
    @Override public void save() { ConfigHelper.save(FILE, config); }
    @Override public void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c != null) config = c;
    }

    public Config getConfig() { return config; }
}
