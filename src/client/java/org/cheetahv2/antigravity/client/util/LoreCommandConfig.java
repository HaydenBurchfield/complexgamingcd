package org.cheetahv2.antigravity.client.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * LoreCommandConfig — the server command templates the lore editor issues.
 *
 * Defaults follow the syntax already in use on this server:
 *   set lore line   : "sll {n} {text}"   (same command the signature uses)
 *   remove lore line: "rll {n}"
 *   rename item     : "ssl {text}"
 *
 * Placeholders: {n} = 1-based line number, {text} = the line content
 * (with &#RRGGBB / &l codes preserved).
 *
 * Edit config/antigravity/lore_commands.json if your server differs — the
 * editor previews every command before sending, so a mismatch is obvious.
 */
public class LoreCommandConfig {

    public static class Config {
        public String setLine    = "sll {n} {text}";
        public String removeLine = "rll {n}";
        public String rename     = "ssl {text}";
        /**
         * APPEND a new lore line to the end. Set-line only works on a line
         * that already exists — writing past the end makes the server clamp to
         * the last line, so every extra line would overwrite the previous one.
         */
        public String addLine    = "addll {text}";
        /**
         * INSERT a new line BEFORE {n}, pushing the rest down. Used when a row
         * is added in the middle, so the lines below keep their content
         * instead of being rewritten one by one.
         */
        public String insertLine = "ill {n} {text}";
        /**
         * Whether this account may CREATE lore lines (addll / ill).
         *
         * Default false: those commands come back "you don't have permission",
         * and /sll only edits a line that already exists. So the editor works
         * strictly within the lines an item already has — it can rewrite and
         * delete them, but not add new ones. Flip to true if you get the perm.
         */
        public boolean canAppend = false;

        /**
         * Lore lines matching any of these (case-insensitive, on the
         * colour-stripped text) are SERVER-OWNED — enchant lines, ability
         * boxes, crate tags. The editor shows them locked and never rewrites
         * them, because re-sending an enchant line through /sll duplicates or
         * mangles it. Add your own patterns here if needed.
         */
        public java.util.List<String> protectedPatterns = new java.util.ArrayList<>(java.util.List.of(
                // Every enchant line is "<icon> <Name> <ROMAN>" — and the icon
                // is per-rune (◆ ⟁ 🗡 👺 🂡 💘 ⛏ …), so keying on ◆ alone missed
                // lines like "⟁ Prism IV" and put the block boundary in the
                // wrong place. Match the SHAPE instead: ends in a roman numeral.
                "^\\W*[a-z][a-z' ]*\\s+[ivxlcdm]+$",
                "◆",                       // classic enchant marker
                "tags:",
                "max level:",
                "crate exclusive",
                "click.*view all levels"
                // NOTE: the ability description box (┌ │ └ rows) is deliberately
                // NOT protected — those are ordinary lines you may want to edit,
                // delete, or centre. Add "^\\s*[\\u250C\\u2502\\u2514]" here to
                // lock them too.
        ));
        /**
         * ms between commands. Long lore edits fire a dozen commands in a row,
         * which is exactly what spam filters watch for — the queue also adds
         * jitter, a pause every few commands, and backs off automatically if
         * the server complains.
         */
        public int    spacingMs  = 500;
    }

    private static final Path FILE = Paths.get("config", "antigravity", "lore_commands.json");
    private static Config config = new Config();

    public static Config get() { return config; }

    public static void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c == null) { save(); return; } // write defaults so they're discoverable

        config = c;
        Config defaults = new Config();
        if (config.protectedPatterns == null) {
            config.protectedPatterns = defaults.protectedPatterns;
        } else {
            // Merge in patterns added by newer versions, keeping user edits
            for (String def : defaults.protectedPatterns) {
                if (!config.protectedPatterns.contains(def)) config.protectedPatterns.add(def);
            }
        }
        // Repair command templates from earlier builds (e.g. the invented "all")
        if (config.addLine == null || config.addLine.startsWith("all ")) {
            config.addLine = defaults.addLine;
        }
        if (config.insertLine == null) config.insertLine = defaults.insertLine;
        save();
    }

    /**
     * Index of the LAST server-owned line in a lore list, or -1 if none.
     *
     * The enchant block sits at the TOP of an item's lore: the ◆ enchant
     * lines, their ability boxes, tag rows and separators all belong to it.
     * So everything from line 0 down to this index is server-owned, and only
     * the lines BELOW it are the player's own lore. Matching line-by-line
     * left the description rows between enchants unprotected.
     */
    public static int lastProtectedIndex(java.util.List<String> lines) {
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isProtected(lines.get(i))) last = i;
        }
        return last;
    }

    /**
     * True if this lore line belongs to the server (enchant text, ability box,
     * crate tag) and must not be rewritten by the editor.
     */
    public static boolean isProtected(String loreLine) {
        if (loreLine == null || loreLine.isBlank()) return false;
        String plain = GradientUtil.stripCodes(loreLine)
                .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                .trim()
                .toLowerCase();
        if (plain.isEmpty()) return false;
        for (String pat : config.protectedPatterns) {
            if (pat == null || pat.isBlank()) continue;
            try {
                if (java.util.regex.Pattern.compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(plain).find()) return true;
            } catch (Exception ignored) {
                if (plain.contains(pat.toLowerCase())) return true; // not a regex, plain contains
            }
        }
        return false;
    }

    public static void save() { ConfigHelper.save(FILE, config); }

    // ── Template expansion ────────────────────────────────────────────────

    public static String setLine(int n, String text) {
        return config.setLine.replace("{n}", String.valueOf(n)).replace("{text}", text);
    }

    public static String removeLine(int n) {
        return config.removeLine.replace("{n}", String.valueOf(n));
    }

    public static String rename(String text) {
        return config.rename.replace("{text}", text);
    }

    public static String addLine(String text) {
        return config.addLine.replace("{text}", text);
    }

    public static String insertLine(int beforeLine, String text) {
        return config.insertLine.replace("{n}", String.valueOf(beforeLine)).replace("{text}", text);
    }

    /**
     * How many lore lines the enchant block occupies on the held item.
     *
     * The server's line commands address the player's CUSTOM lore only — the
     * enchant block is generated and not addressable, so "/sll 1" writes the
     * first custom line, which renders just below the enchants. Every index we
     * send must therefore be offset by this many lines.
     */
    public static int heldEnchantOffset() {
        java.util.List<String> lines = heldLoreLines();
        if (lines.isEmpty()) return 0;
        return lastProtectedIndex(lines) + 1;   // 0-based index -> count
    }

    /** Number of player-editable (custom) lore lines on the held item. */
    public static int heldCustomLoreCount() {
        return Math.max(0, heldLoreCount() - heldEnchantOffset());
    }

    /** Lore line count of the item currently in the main hand (0 if none). */
    public static int heldLoreCount() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        var stack = mc.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return 0;
        var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
        return lore == null ? 0 : lore.lines().size();
    }

    /** Lore lines of the held item, serialized back to &-codes. */
    public static java.util.List<String> heldLoreLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return out;
        var stack = mc.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return out;
        var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
        if (lore == null) return out;
        for (net.minecraft.text.Text line : lore.lines()) out.add(GuiDumper.toCodes(line));
        return out;
    }
}
