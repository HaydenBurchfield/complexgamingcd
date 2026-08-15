package org.cheetahv2.antigravity.client.utility;

import net.minecraft.client.MinecraftClient;

import java.util.List;

/**
 * UtilityModuleManager
 *
 * Central coordinator for all utility automation modules.
 * Add new modules to the MODULES list — the tick loop, settings UI, and
 * config save/load all iterate over it automatically.
 *
 * Registration: call UtilityModuleManager.INSTANCE.tick(mc) from
 * AntigravityClient's END_CLIENT_TICK handler.
 */
public class UtilityModuleManager {

    public static final UtilityModuleManager INSTANCE = new UtilityModuleManager();

    // ── Module registry ───────────────────────────────────────────────────
    // Add new modules here — everything else picks them up automatically.
    // index 0
    public final AutoCookieModule         AUTO_COOKIE          = new AutoCookieModule();
    // index 1
    public final AutoJackOfHeartsModule   AUTO_JACK_OF_HEARTS  = new AutoJackOfHeartsModule();
    // index 2
    public final AutoSacredShieldModule   AUTO_SACRED_SHIELD   = new AutoSacredShieldModule();
    // index 3
    public final AutoTropicalShieldModule AUTO_TROPICAL_SHIELD = new AutoTropicalShieldModule();
    // index 4
    public final AutoTotemModule          AUTO_TOTEM           = new AutoTotemModule();
    // index 5
    public final AutoSellSoulModule       AUTO_SELL_SOUL       = new AutoSellSoulModule();
    // index 6
    public final BidWarModule             BID_WAR              = new BidWarModule();
    // index 7
    public final AutoTikiModule           AUTO_TIKI            = new AutoTikiModule();
    // index 8
    public final AbilityKeybindModule     ABILITY_KEYS         = new AbilityKeybindModule();

    /** Ordered list used by the UI and tick loop. */
    public final List<UtilityModule> modules = List.of(
            AUTO_COOKIE,          // 0
            AUTO_JACK_OF_HEARTS,  // 1
            AUTO_SACRED_SHIELD,   // 2
            AUTO_TROPICAL_SHIELD, // 3
            AUTO_TOTEM,           // 4
            AUTO_SELL_SOUL,       // 5
            BID_WAR,              // 6
            AUTO_TIKI,            // 7
            ABILITY_KEYS          // 8
    );

    // ── Lifecycle ─────────────────────────────────────────────────────────

    public void load() {
        for (UtilityModule m : modules) m.load();
    }

    public void save() {
        for (UtilityModule m : modules) m.save();
    }

    /**
     * Tick all enabled modules. Called every client tick from
     * AntigravityClient when player and world are non-null.
     * Modules that are disabled skip their logic internally via isEnabled().
     */
    public void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        for (UtilityModule m : modules) {
            if (m.isEnabled()) {
                try {
                    m.tick(mc);
                } catch (Exception e) {
                    // Defensive: don't let one broken module crash the whole tick
                    System.err.println("[CGC] UtilityModule '" + m.getName() + "' threw: " + e.getMessage());
                }
            }
        }
    }
}
