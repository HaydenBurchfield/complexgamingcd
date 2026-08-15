package org.cheetahv2.antigravity.client.utility;

import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BidWarModule
 *
 * Watches chat for Bid War announcements, e.g.:
 *   "(/bidwar) ✔ A Bid War for Voter Pickaxe is starting at $1,000!"
 *
 * If the item name matches one of the configured watch entries (case-insensitive
 * substring match either direction), a title banner, sound, and chat alert are
 * shown. With an empty watch list + notifyAll, every bid war triggers an alert.
 *
 * Manage the watch list with:
 *   /bidwatch add <item name>
 *   /bidwatch remove <item name>
 *   /bidwatch list
 * or from /ccsettings → Modules → Bid War Alerts.
 */
public class BidWarModule implements UtilityModule {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled    = true;
        /** Alert on EVERY bid war, even items not on the watch list. */
        public boolean notifyAll  = false;
        public boolean playSound  = true;
        public boolean showTitle  = true;
        public List<String> watchedItems = new ArrayList<>();
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_bidwar.json");

    // "(/bidwar) ... Bid War for <ITEM> is starting[ at <PRICE>]!"
    // <PRICE> keeps its currency intact: "16 GC", "2,500 IGM", "$1,000", ...
    private static final Pattern BIDWAR_START = Pattern.compile(
            "\\(/bidwar\\).*?bid\\s*war\\s+for\\s+(.+?)\\s+is\\s+starting(?:\\s+at\\s+([^!]+?))?\\s*!?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Bid War Alerts"; }
    @Override public String getDescription()    { return "Alerts when a watched bid war item goes up"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }
    @Override public void tick(MinecraftClient mc) { /* chat-driven, nothing to tick */ }

    // ── Chat entry point (called even while "disabled-tick" — wired directly) ──

    /** @param stripped chat line with §-codes already stripped */
    public void onChat(String stripped) {
        if (!config.enabled) return;

        Matcher m = BIDWAR_START.matcher(stripped);
        if (!m.find()) return;

        String item  = m.group(1).trim();
        String price = m.group(2) != null ? m.group(2).trim() : null;

        String matched = matchWatched(item);
        if (matched == null && !config.notifyAll) return;

        alert(item, price);
    }

    /** Returns the watch entry that matches this item name, or null. */
    private String matchWatched(String item) {
        String lo = item.toLowerCase();
        for (String w : config.watchedItems) {
            if (w == null || w.isBlank()) continue;
            String wl = w.toLowerCase().trim();
            if (lo.contains(wl) || wl.contains(lo)) return w;
        }
        return null;
    }

    private void alert(String item, String price) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        // Price already includes its currency ("16 GC", "$1,000", "2,500 IGM")
        String priceStr = price != null ? " §7at §a" + price : "";

        // Chat line
        mc.player.sendMessage(Text.literal(
                "§6§l[CGC] §e⚑ BID WAR: §f" + item + priceStr + " §7— /bidwar"), false);

        // Title banner
        if (config.showTitle) {
            mc.send(() -> {
                mc.inGameHud.setTitle(Text.literal("§6⚑ BID WAR"));
                mc.inGameHud.setSubtitle(Text.literal("§e" + item + priceStr));
                mc.inGameHud.setTitleTicks(5, 60, 15);
            });
        }

        // Sound ping
        if (config.playSound) {
            mc.player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        }
    }

    // ── Watch list management (used by /bidwatch and the settings UI) ─────

    public boolean addWatch(String name) {
        if (name == null || name.isBlank()) return false;
        String trimmed = name.trim();
        for (String w : config.watchedItems) {
            if (w.equalsIgnoreCase(trimmed)) return false;
        }
        config.watchedItems.add(trimmed);
        save();
        return true;
    }

    public boolean removeWatch(String name) {
        boolean removed = config.watchedItems.removeIf(w -> w.equalsIgnoreCase(name.trim()));
        if (removed) save();
        return removed;
    }

    public List<String> getWatched() { return config.watchedItems; }

    // ── Config persistence ────────────────────────────────────────────────
    @Override public void save() { ConfigHelper.save(FILE, config); }
    @Override public void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c != null) {
            config = c;
            if (config.watchedItems == null) config.watchedItems = new ArrayList<>();
        }
    }

    public Config getConfig() { return config; }
}
