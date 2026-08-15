package org.cheetahv2.antigravity.client.utility;

import net.minecraft.client.MinecraftClient;

/**
 * ModuleSync — tiny cross-module coordination lock.
 *
 * Several automation modules stage items through hotbar slot 8 and the
 * offhand (cookie, both shields, totem, jack of hearts, sell soul). Without
 * coordination two of them can interleave swap packets mid-sequence and
 * scramble the inventory. Each multi-step sequence acquires this lock first
 * and releases it when done; other modules simply wait a tick and retry.
 *
 * The lock auto-expires (holdMs) so a crashed/aborted sequence can never
 * deadlock the others.
 */
public final class ModuleSync {

    private static String owner    = null;
    private static long   expiresMs = 0;

    private ModuleSync() {}

    /** Try to take (or refresh) the inventory lock. Returns false if another module holds it. */
    public static synchronized boolean acquire(String who, long holdMs) {
        long now = System.currentTimeMillis();
        if (owner != null && !owner.equals(who) && now < expiresMs) return false;
        owner = who;
        expiresMs = now + holdMs;
        return true;
    }

    /** Release the lock if this module holds it. */
    public static synchronized void release(String who) {
        if (who.equals(owner)) {
            owner = null;
            expiresMs = 0;
        }
    }

    /** True if a DIFFERENT module currently holds the lock. */
    public static synchronized boolean isBusy(String who) {
        return owner != null && !owner.equals(who) && System.currentTimeMillis() < expiresMs;
    }

    /**
     * True while a container screen (chest, crate, shop...) is open — swap
     * packets against the player inventory are invalid then, so sequences
     * must pause.
     */
    public static boolean inventoryLocked(MinecraftClient mc) {
        return mc.player == null
                || mc.player.currentScreenHandler != mc.player.playerScreenHandler;
    }

    /**
     * True while the player is actively using an item (blocking with a
     * shield, eating, drawing a bow). Modules that right-click items should
     * not interrupt this.
     */
    public static boolean handsInUse(MinecraftClient mc) {
        return mc.player != null && mc.player.isUsingItem();
    }
}
