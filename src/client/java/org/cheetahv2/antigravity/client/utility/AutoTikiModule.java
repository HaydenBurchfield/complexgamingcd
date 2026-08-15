package org.cheetahv2.antigravity.client.utility;

import java.nio.file.Paths;

/**
 * AutoTikiModule — Tiki / Rain Dance CHESTPLATE (chest armor slot).
 *
 * Keybind-triggered, same mechanic as Sell Soul: equips the chestplate,
 * double-taps swap-hands to open its keybind menu, confirms (slot 1 / extra
 * F-press), unequips and restores everything.
 *
 * Matches "👺 Rain Dance IV" and the max-level gradient name — detection is
 * on "rain dance" in the name/lore with all §/&/&#RRGGBB codes stripped.
 * All mechanics live in {@link AbstractEquipMenuModule}.
 */
public class AutoTikiModule extends AbstractEquipMenuModule {

    public AutoTikiModule() {
        // chest armor = player screen slot 6
        super("tiki", 6, "rain dance",
                Paths.get("config", "antigravity", "module_tiki.json"));
    }

    @Override public String getName()        { return "Auto Tiki (Rain Dance)"; }
    @Override public String getDescription() { return "Keybind: equips Tiki chest, F-F-1 menu, re-equips old"; }
}
