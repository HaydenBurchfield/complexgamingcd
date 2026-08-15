package org.cheetahv2.antigravity.client.cooldown;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MoodSwingsHud
 *
 * Displays the current Mood Swings mood in a pill above the hotbar with a
 * colored progress bar + countdown (same style as the Lark / rune cooldown
 * tiles). The mood is parsed from chat whenever a "Mood Swings" line appears.
 *
 * Parsing is deliberately liberal because the server wording can vary:
 *   • "... Mood Swings ... swung into a(n) <MOOD> mood ..."
 *   • "... you are now in a(n) <MOOD> mood ..."
 *   • any "mood swings" line: the word right before "mood" is used.
 * A "for N seconds" phrase in the message overrides the default duration.
 *
 * Position/scale are editable in the unified HUD editor ([L] in-game) and the
 * HUD can be toggled in /ccsettings. Test with /cgcmood <mood>.
 */
public class MoodSwingsHud {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled = true;
        /** -1 = centered horizontally above the hotbar. */
        public int x = -1;
        /** -1 = default (just above hotbar). */
        public int y = -1;
        public float scale = 1.0f;
        /** Mood Swings cycles every 10 s (per the boots' lore). */
        public long defaultDurationMs = 10_000;
        /**
         * Mood names detected in ACTION BAR messages (the server announces the
         * active mood there, not in chat). Extend if the enchant gains moods.
         */
        public java.util.List<String> knownMoods =
                new java.util.ArrayList<>(java.util.List.of("Aggressive", "Playful", "Lazy"));
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "hud_mood.json");

    // ── Mood state ────────────────────────────────────────────────────────
    private String currentMood   = null;
    private long   moodStartMs   = 0;
    private long   moodDurationMs = 0;

    // ── Panel dimensions (unscaled) ───────────────────────────────────────
    public static final int PANEL_W = 130;
    public static final int PANEL_H = 26;

    // ── Mood styling ─────────────────────────────────────────────────────
    private record MoodStyle(String icon, int color) {}
    private static final Map<String, MoodStyle> MOOD_STYLES = new LinkedHashMap<>();
    static {
        // The three Mood Swings (Panda) moods
        MOOD_STYLES.put("aggressive", new MoodStyle("⚔", 0xFFE84A6A)); // +25% damage — red
        MOOD_STYLES.put("playful",    new MoodStyle("⚡", 0xFFFFD060)); // +30% speed — yellow
        MOOD_STYLES.put("lazy",       new MoodStyle("☾", 0xFF55DDAA)); // slow + regen — teal
        MOOD_STYLES.put("happy",     new MoodStyle("☺", 0xFF4AE87A)); // ☺ green
        MOOD_STYLES.put("joyful",    new MoodStyle("☺", 0xFF4AE87A));
        MOOD_STYLES.put("angry",     new MoodStyle("☠", 0xFFE84A6A)); // ☠ red
        MOOD_STYLES.put("furious",   new MoodStyle("☠", 0xFFE84A6A));
        MOOD_STYLES.put("sad",       new MoodStyle("☹", 0xFF5AB4FF)); // ☹ blue
        MOOD_STYLES.put("depressed", new MoodStyle("☹", 0xFF5AB4FF));
        MOOD_STYLES.put("excited",   new MoodStyle("⚡", 0xFFFFD060)); // ⚡ yellow
        MOOD_STYLES.put("energetic", new MoodStyle("⚡", 0xFFFF9944)); // ⚡ orange
        MOOD_STYLES.put("calm",      new MoodStyle("✿", 0xFF55DDDD)); // ✿ aqua
        MOOD_STYLES.put("relaxed",   new MoodStyle("✿", 0xFF55DDDD));
        MOOD_STYLES.put("tired",     new MoodStyle("☾", 0xFF9999AA)); // ☾ gray
        MOOD_STYLES.put("lazy",      new MoodStyle("☾", 0xFF9999AA));
        MOOD_STYLES.put("love",      new MoodStyle("❤", 0xFFFF5B9B)); // ❤ pink
        MOOD_STYLES.put("loving",    new MoodStyle("❤", 0xFFFF5B9B));
        MOOD_STYLES.put("greedy",    new MoodStyle("✪", 0xFFFFAA00)); // ✪ gold
        MOOD_STYLES.put("lucky",     new MoodStyle("✦", 0xFF3DDA6E)); // ✦
        MOOD_STYLES.put("crazy",     new MoodStyle("☘", 0xFF9F6FFF)); // purple
    }
    private static final MoodStyle DEFAULT_STYLE = new MoodStyle("✦", 0xFFBB8FFF);

    // ── Chat parsing ─────────────────────────────────────────────────────

    // "Mood Swings V has made you feel Lazy!" (the actual server wording)
    private static final Pattern MADE_YOU_FEEL = Pattern.compile(
            "(?:made\\s+you\\s+feel|you\\s+feel|feeling)\\s+([A-Za-z]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MOOD_SWUNG = Pattern.compile(
            "mood\\s*swings?(?:\\s+[ivxIVX]+)?.*?" +
            "(?:swung|changed|shifted|switched|put\\s+you|is\\s+now)\\s+(?:in)?(?:to)?\\s*(?:a|an)?\\s*([A-Za-z]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NOW_IN_MOOD = Pattern.compile(
            "(?:you(?:'re|\\s+are)?|now)\\s+(?:now\\s+)?(?:feeling\\s+|in\\s+)?(?:a|an)?\\s*([A-Za-z]+)\\s+mood",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_BEFORE_MOOD = Pattern.compile(
            "([A-Za-z]+)\\s+mood\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_SECONDS = Pattern.compile(
            "\\bfor\\s+(\\d+)\\s+seconds?\\b", Pattern.CASE_INSENSITIVE);

    // Words that can sit before "mood" but aren't the mood itself.
    private static final java.util.Set<String> MOOD_STOPWORDS = java.util.Set.of(
            "a", "an", "the", "your", "new", "current", "into", "in", "to", "of", "swings", "swing");

    /**
     * ACTION BAR entry point — this is where the server actually announces the
     * active mood ("... Aggressive ...", cycling every 10 s while wearing the
     * Mood Swings boots). Matches any configured known mood word.
     *
     * To avoid false positives from unrelated action-bar spam, a bare mood word
     * only counts while Mood Swings boots are equipped; if the message itself
     * says "mood" it always counts.
     */
    // ── Diagnostics (surfaced by /cgcmood status) ─────────────────────────
    private String lastActionBar = null;
    private String lastParse     = "no action bar seen yet";

    public void onActionBar(String stripped) {
        if (stripped == null || stripped.isEmpty()) return;
        lastActionBar = stripped;

        if (!config.enabled) { lastParse = "HUD disabled in settings"; return; }
        String lo = stripped.toLowerCase();

        boolean mentionsMood = lo.contains("mood");
        if (!mentionsMood && !wearingMoodBoots()) {
            lastParse = "skipped (no 'mood' in text and no Mood Swings boots detected)";
            return;
        }

        for (String moodWord : config.knownMoods) {
            if (moodWord == null || moodWord.isBlank()) continue;
            if (lo.contains(moodWord.toLowerCase())) {
                lastParse = "matched mood: " + moodWord;
                announceMood(moodWord, config.defaultDurationMs);
                return;
            }
        }

        // Unknown mood word but explicitly a mood message → generic parsing
        if (mentionsMood) {
            lastParse = "mood line, but no known mood word — tried generic parse";
            onChat(stripped);
        } else {
            lastParse = "no mood word matched";
        }
    }

    /** Human-readable state dump for /cgcmood status. */
    public java.util.List<String> getStatusLines(MinecraftClient mc) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("§7enabled: " + (config.enabled ? "§atrue" : "§cfalse")
                + " §7| boots detected: " + (wearingMoodBoots() ? "§atrue" : "§cfalse"));
        out.add("§7current mood: " + (hasActiveMood()
                ? "§d" + currentMood + " §7(" + (remainingMs() / 1000) + "s left)" : "§8none"));
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        out.add("§7hud pos: §f" + getX(sw) + "," + getY(sh)
                + " §7scale: §f" + String.format("%.1f", getScale())
                + " §7(screen " + sw + "x" + sh + ")");
        out.add("§7known moods: §f" + String.join(", ", config.knownMoods));
        out.add("§7last action bar: " + (lastActionBar == null ? "§8(none)" : "§f" + lastActionBar));
        out.add("§7parse result: §e" + lastParse);
        return out;
    }

    /**
     * A repeated announcement of the SAME active mood keeps the running
     * countdown; a new mood restarts the 10 s cycle.
     */
    private void announceMood(String mood, long durationMs) {
        if (currentMood != null && currentMood.equalsIgnoreCase(mood) && hasActiveMood()) {
            return; // same mood still ticking — don't reset the bar
        }
        setMood(mood, durationMs);
    }

    /** True if the equipped boots carry the Mood Swings enchant (lore check, cached per stack). */
    private static final Map<net.minecraft.item.ItemStack, Boolean> BOOTS_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static boolean wearingMoodBoots() {
        var mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;
        net.minecraft.item.ItemStack boots = mc.player.getInventory().getStack(36); // feet slot
        if (boots == null || boots.isEmpty()) return false;
        Boolean cached = BOOTS_CACHE.get(boots);
        if (cached != null) return cached;
        boolean match = stackMentionsMoodSwings(boots);
        BOOTS_CACHE.put(boots, match);
        return match;
    }

    private static boolean stackMentionsMoodSwings(net.minecraft.item.ItemStack stack) {
        try {
            if (stack.getName().getString().toLowerCase().contains("mood swings")) return true;
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                for (net.minecraft.text.Text line : lore.lines()) {
                    StringBuilder sb = new StringBuilder(line.getString());
                    for (net.minecraft.text.Text sib : line.getSiblings()) sb.append(sib.getString());
                    if (sb.toString().toLowerCase().contains("mood swings")) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** @param stripped chat line with §-codes stripped. */
    public void onChat(String stripped) {
        String lo = stripped.toLowerCase();
        if (!lo.contains("mood")) return;
        // Only react to Mood Swings enchant lines (avoid random chat containing "mood")
        if (!lo.contains("mood swing") && !lo.contains("runes")) return;

        String mood = null;
        Matcher m = MADE_YOU_FEEL.matcher(stripped);
        if (m.find() && !MOOD_STOPWORDS.contains(m.group(1).toLowerCase())) {
            mood = m.group(1);
        }
        if (mood == null) {
            m = MOOD_SWUNG.matcher(stripped);
            if (m.find() && !MOOD_STOPWORDS.contains(m.group(1).toLowerCase())) mood = m.group(1);
        }
        if (mood == null) {
            m = NOW_IN_MOOD.matcher(stripped);
            if (m.find() && !MOOD_STOPWORDS.contains(m.group(1).toLowerCase())) mood = m.group(1);
        }
        if (mood == null) {
            m = WORD_BEFORE_MOOD.matcher(stripped);
            while (m.find()) {
                String w = m.group(1).toLowerCase();
                if (!MOOD_STOPWORDS.contains(w)) { mood = m.group(1); break; }
            }
        }
        if (mood == null) return;

        long dur = config.defaultDurationMs;
        Matcher s = FOR_SECONDS.matcher(stripped);
        if (s.find()) {
            try { dur = Long.parseLong(s.group(1)) * 1000L; } catch (NumberFormatException ignored) {}
        }
        setMood(mood, dur);
    }

    /** Mood Swings always cycles every 10 seconds — hardcoded by request. */
    public static final long MOOD_DURATION_MS = 10_000L;

    /** Also used by the /cgcmood test command. Duration is fixed at 10 s. */
    public void setMood(String mood, long ignoredDurationMs) {
        this.currentMood    = mood.toUpperCase();
        this.moodStartMs    = System.currentTimeMillis();
        this.moodDurationMs = MOOD_DURATION_MS;
    }

    public boolean hasActiveMood() {
        return currentMood != null
                && System.currentTimeMillis() - moodStartMs < moodDurationMs;
    }

    public long remainingMs() {
        if (currentMood == null) return 0;
        return Math.max(0, moodStartMs + moodDurationMs - System.currentTimeMillis());
    }

    // ── Position / scale (HUD editor hooks) ──────────────────────────────

    public boolean isEnabled()          { return config.enabled; }
    public void setEnabled(boolean v)   { config.enabled = v; save(); }
    public float getScale()             { return Math.max(0.4f, Math.min(3.0f, config.scale)); }
    public void setScale(float s)       { config.scale = Math.max(0.4f, Math.min(3.0f, s)); }
    public void setPos(int x, int y)    { config.x = x; config.y = y; }

    public int getX(int sw) {
        int x = config.x >= 0 ? config.x : (sw - getScaledW()) / 2;
        // Clamp on-screen — a saved position from a bigger window/scale could
        // otherwise push the HUD entirely off-screen ("it just doesn't show").
        return Math.max(0, Math.min(sw - getScaledW(), x));
    }

    public int getY(int sh) {
        int y = config.y >= 0 ? config.y : sh - 55 - getScaledH();
        return Math.max(0, Math.min(sh - getScaledH(), y));
    }

    public int getScaledW() { return (int)(PANEL_W * getScale()); }
    public int getScaledH() { return (int)(PANEL_H * getScale()); }

    // ── Render ────────────────────────────────────────────────────────────

    public void render(DrawContext ctx, MinecraftClient mc) {
        if (!config.enabled || mc == null || mc.getWindow() == null) return;
        if (!hasActiveMood()) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        TextRenderer tr = mc.textRenderer;

        MoodStyle style = MOOD_STYLES.getOrDefault(currentMood.toLowerCase(), DEFAULT_STYLE);
        int col = style.color();

        long rem = remainingMs();
        float progress = 1f - (float) rem / Math.max(1, moodDurationMs);

        int px = getX(sw);
        int py = getY(sh);
        float scale = getScale();

        var ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate((float) px, (float) py);
        ms.scale(scale, scale);

        int pw = PANEL_W, ph = PANEL_H;

        // Fade out over the last 500ms
        int alpha = rem < 500 ? (int)(rem * 255 / 500) : 255;

        // Liquid-glass pill — Antigravity theme
        ctx.fill(0, 0, pw, ph, applyAlpha(org.cheetahv2.antigravity.client.gui.Theme.HUD_BG, alpha));
        gborder(ctx, 0, 0, pw, ph, applyAlpha(org.cheetahv2.antigravity.client.gui.Theme.BORDER_SOFT, alpha));
        ctx.fill(1, 1, pw - 1, 2, applyAlpha(0x15FFFFFF, alpha));
        ctx.fill(1, 1, 3, ph - 1, applyAlpha(col, alpha)); // colored accent bar

        // Icon + mood name
        ctx.drawTextWithShadow(tr, style.icon(), 7, 5, applyAlpha(col, alpha));
        ctx.drawTextWithShadow(tr, "MOOD: " + currentMood, 18, 5, applyAlpha(col, alpha));

        // Countdown (right-aligned)
        String ts = String.format("%.1fs", rem / 1000.0);
        int tw = tr.getWidth(ts);
        ctx.drawTextWithShadow(tr, ts, pw - tw - 5, 5, applyAlpha(0xFFDDD0F8, alpha));

        // Progress bar (drains as the mood expires)
        int bx = 6, by = 17, bw = pw - 12, bh = 4;
        ctx.fill(bx, by, bx + bw, by + bh, applyAlpha(0x40000000, alpha));
        int filled = (int)((1f - progress) * bw);
        if (filled > 0) ctx.fill(bx, by, bx + filled, by + bh, applyAlpha(col, alpha));

        ms.popMatrix();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int applyAlpha(int argb, int alpha) {
        int baseA = (argb >>> 24) & 0xFF;
        int finalA = baseA * alpha / 255;
        return (finalA << 24) | (argb & 0x00FFFFFF);
    }

    private static void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }

    // ── Config persistence ────────────────────────────────────────────────
    public void save() { ConfigHelper.save(FILE, config); }
    public void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c != null) {
            config = c;
            if (config.knownMoods == null || config.knownMoods.isEmpty()) {
                config.knownMoods = new java.util.ArrayList<>(
                        java.util.List.of("Aggressive", "Playful", "Lazy"));
            }
        }
    }
    public Config getConfig() { return config; }
}
