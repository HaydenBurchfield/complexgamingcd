package org.cheetahv2.antigravity.client.tracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import java.util.*;

/**
 * PlayerTracker
 *
 * 1. Held-item history: last 10 distinct main-hand items per player.
 * 2. Inventory snapshots: every visible player's armor + hands + health is
 *    snapshotted continuously, so /invsee <name> can show what someone was
 *    carrying even after they leave render distance.
 */
public class PlayerTracker {

    // ── Held-item history ─────────────────────────────────────────────────
    private final Map<String, ArrayDeque<ItemStack>> heldHistory = new LinkedHashMap<>();

    // ── Inventory snapshots ───────────────────────────────────────────────

    public static class InvSnapshot {
        public final String name;              // exact-case player name
        public final ItemStack head, chest, legs, feet, mainHand, offHand;
        public final float health, maxHealth;
        public long seenAtMs;

        InvSnapshot(PlayerEntity p) {
            this.name      = p.getName().getString();
            this.head      = p.getInventory().getStack(39).copy();
            this.chest     = p.getInventory().getStack(38).copy();
            this.legs      = p.getInventory().getStack(37).copy();
            this.feet      = p.getInventory().getStack(36).copy();
            this.mainHand  = p.getMainHandStack().copy();
            this.offHand   = p.getOffHandStack().copy();
            this.health    = p.getHealth();
            this.maxHealth = p.getMaxHealth();
            this.seenAtMs  = System.currentTimeMillis();
        }
    }

    /** lower-case name → latest snapshot. Kept for the whole session. */
    private final Map<String, InvSnapshot> snapshots = new LinkedHashMap<>();

    private int tickCounter = 0;

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.world == null) return;
        boolean snapshotTick = ++tickCounter % 10 == 0; // refresh snapshots every 0.5s

        for (PlayerEntity p : mc.world.getPlayers()) {
            String name = p.getName().getString();

            // held-item history
            ItemStack hand = p.getMainHandStack();
            ArrayDeque<ItemStack> hist = heldHistory.computeIfAbsent(name, k -> new ArrayDeque<>());
            ItemStack last = hist.isEmpty() ? ItemStack.EMPTY : hist.peekFirst();
            if (!ItemStack.areItemsEqual(hand, last)) {
                if (!hand.isEmpty()) {
                    hist.addFirst(hand.copy());
                    while (hist.size() > 10) hist.pollLast();
                }
            }

            // inventory snapshot
            if (snapshotTick && p != mc.player) {
                snapshots.put(name.toLowerCase(), new InvSnapshot(p));
            }
        }
    }

    public List<ItemStack> getHistory(String playerName) {
        ArrayDeque<ItemStack> hist = heldHistory.get(playerName);
        return hist == null ? Collections.emptyList() : new ArrayList<>(hist);
    }

    /** Latest snapshot for this player (case-insensitive), or null if never seen. */
    public InvSnapshot getSnapshot(String playerName) {
        if (playerName == null) return null;
        return snapshots.get(playerName.toLowerCase().trim());
    }

    /** Names of all players we have snapshots for (exact case). */
    public List<String> getSnapshotNames() {
        List<String> names = new ArrayList<>(snapshots.size());
        for (InvSnapshot s : snapshots.values()) names.add(s.name);
        return names;
    }
}
