package org.cheetahv2.antigravity.client.utility;

import java.nio.file.Paths;

/**
 * AutoSellSoulModule — Sell Soul LEGGINGS (legs armor slot).
 *
 * Keybind-triggered: equips the leggings, double-taps swap-hands to open the
 * Sell Soul menu, confirms (slot 1 / extra F-press), unequips and restores.
 * All mechanics live in {@link AbstractEquipMenuModule}.
 */
public class AutoSellSoulModule extends AbstractEquipMenuModule {

    public AutoSellSoulModule() {
        // legs armor = player screen slot 7
        super("sellsoul", 7, "sell soul",
                Paths.get("config", "antigravity", "module_sell_soul.json"));
    }

    @Override public String getName()        { return "Auto Sell Soul"; }
    @Override public String getDescription() { return "Keybind: equips Sell Soul legs, F-F-1 menu, re-equips old"; }
}
