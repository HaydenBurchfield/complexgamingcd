package org.cheetahv2.antigravity.client.utility;

import net.minecraft.client.MinecraftClient;

/**
 * Base interface for all utility automation modules.
 *
 * Each module is responsible for:
 *  - maintaining its own enabled flag and per-module config
 *  - implementing tick() to run its automation logic once per client tick
 *  - providing a display name for the settings UI
 *
 * Adding a new module: implement this interface, add an instance to
 * UtilityModuleManager.MODULES, and the settings tab will pick it up automatically.
 */
public interface UtilityModule {

    /** Human-readable name shown in the settings UI. */
    String getName();

    /** Short description shown as a subtitle in the settings UI. */
    String getDescription();

    /** Whether this module is currently enabled. */
    boolean isEnabled();

    /** Enable or disable this module. */
    void setEnabled(boolean enabled);

    /**
     * Called every client tick when the player is in-game.
     * The module should perform its automation logic here.
     *
     * @param mc the MinecraftClient instance (player and world are non-null)
     */
    void tick(MinecraftClient mc);

    /**
     * Called when the module manager is saving config.
     * The module should persist its own config here.
     */
    void save();

    /**
     * Called on startup to load persisted config.
     */
    void load();
}
