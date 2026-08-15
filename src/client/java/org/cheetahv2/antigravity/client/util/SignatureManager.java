package org.cheetahv2.antigravity.client.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
// GradientUtil powers the auto-gradient source resolution below

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SignatureManager — named, multi-line signature presets for /sign.
 *
 * A preset is a list of lines. Each line is sent as its own chat command,
 * spaced out by lineDelayMs so the server doesn't drop them for spam.
 * If a line does not already start with a command word, it is wrapped with
 * prefixFormat ("sll {n} " by default, {n} = 1-based line number), so you
 * can paste raw gradient art straight in and it still sends correctly.
 *
 * Stored in config/antigravity/signature.json.
 */
public class SignatureManager {

    private static final Path FILE = Paths.get("config", "antigravity", "signature.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The original hardcoded one-liner, kept as the built-in default preset. */
    private static final String DEFAULT_SIGNATURE =
            "sll 1 &#FFBCE2 ☆ &#FEC7DDᴄ&#FDCCDAʜ&#FDD2D8ᴇ&#FCD7D5ᴇ&#FCDDD3ᴛ&#FBE2D0ᴀ&#FBE8CEʜ&#FAEDCBᴠ&#F7E9CF² &#F0E2D7&lX&#ECDFDB&lX &7(&#E2D4E8+&#DED1EC3&7)";

    // ── Model ─────────────────────────────────────────────────────────────

    public static class Preset {
        public String name = "default";
        public List<String> lines = new ArrayList<>();
        /** Wrap bare lines with prefixFormat when sending. */
        public boolean autoPrefix = true;
        /** {n} is replaced with the target lore line number. */
        public String prefixFormat = "sll {n} ";
        /**
         * First lore line to write to. An item's enchant block occupies the
         * top of the lore, so writing at line 1 lands inside it — bump this
         * to the first line BELOW the enchants.
         */
        public int startLine = 1;
        /**
         * Work out the start line from the HELD item every send (first line
         * after its enchant block). A fixed startLine is wrong the moment you
         * sign a different item — a shield with 2 lore lines can't take the
         * index that fitted a fully-enchanted chestplate.
         */
        public boolean autoStart = true;
        /** Put the signature ABOVE the "Crate Exclusive" line instead of below it. */
        public boolean aboveCrateLine = false;
        /** Where the last send placed the block (0-based), for replacing it. */
        public int lastStart = -1;
        /**
         * Trim leftover lines when a new signature is SHORTER than the last.
         *
         * The set-line command REPLACES the line at that index, so the lines
         * being rewritten must NOT be deleted first — doing that removed the
         * line, shifted everything up, and then wrote into the shifted list,
         * which is what mangled/duplicated the lore. Only the surplus tail is
         * removed, and only after the writes.
         */
        public boolean clearFirst = true;
        /** How many lines the last send wrote (used by clearFirst). */
        public int lastWritten = 0;
        /** Delay between lines (ms) so the server doesn't rate-limit us. */
        public int lineDelayMs = 500;

        /**
         * Gradient source for auto-colouring: a hex stop list
         * ("#10B1FF,#91FFFF"), "rainbow", or "item" to sample the held item's
         * name. Empty = no auto gradient.
         */
        public String gradientStops = "";
        /** Re-colour every line from gradientStops whenever the preset is saved/sent. */
        public boolean autoGradient = false;
        /** Bold (&l) every character of the generated gradient. */
        public boolean gradientBold = true;

        public Preset() {}
        public Preset(String name, List<String> lines) {
            this.name = name;
            this.lines = lines;
        }
    }

    private static class Data {
        String active = "default";
        Map<String, Preset> presets = new LinkedHashMap<>();
    }

    private static Data data = new Data();

    /**
     * Kept for compatibility — sending now goes through ClientCommandQueue so
     * signatures and lore edits share one throttle (and one spam back-off).
     */
    public static void tickSend(MinecraftClient mc) { /* handled by ClientCommandQueue */ }

    /**
     * Resolves a preset's gradient source into colour stops.
     * "item" samples the held item's name; "rainbow" sweeps hues; anything
     * else is parsed as a hex list. Returns an empty list when unavailable.
     */
    public static List<Integer> resolveStops(Preset p) {
        if (p == null || p.gradientStops == null || p.gradientStops.isBlank()) return List.of();
        String spec = p.gradientStops.trim();
        if (spec.equalsIgnoreCase("item")) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return List.of();
            return GradientUtil.sampleItemName(mc.player.getMainHandStack());
        }
        if (spec.equalsIgnoreCase("rainbow")) return GradientUtil.rainbow(12);
        List<Integer> stops = GradientUtil.parseStops(spec);
        // A single pair reads best mirrored (dark -> bright -> dark)
        return stops.size() == 2 ? GradientUtil.mirror(stops) : stops;
    }

    /**
     * Re-colours every line of the preset from its gradient source, keeping
     * the plain characters and replacing the colour codes. No-op unless
     * autoGradient is on and the source resolves.
     */
    public static int applyAutoGradient(Preset p) {
        if (p == null || !p.autoGradient) return 0;
        List<Integer> stops = resolveStops(p);
        if (stops.isEmpty()) return 0;
        int budget = GradientUtil.MAX_COMMAND_LENGTH - 8; // room for the "sll n " prefix
        int changed = 0;
        for (int i = 0; i < p.lines.size(); i++) {
            String line = p.lines.get(i);
            if (GradientUtil.stripCodes(line).isBlank()) continue;
            // Pad-preserving: keeps "&f &f " centring intact through recolouring
            p.lines.set(i, GradientUtil.applyPreservingPad(line, stops, p.gradientBold, budget));
            changed++;
        }
        if (changed > 0) save();
        return changed;
    }

    /** Queues every line of a preset for sending. Returns the command count. */
    public static int send(String presetName) {
        Preset p = get(presetName);
        if (p == null || p.lines.isEmpty()) return 0;

        // Auto-gradient regenerates colours right before sending, so an
        // "item" source always matches whatever you're currently holding.
        applyAutoGradient(p);

        List<String> out = buildCommands(p, true);
        ClientCommandQueue.submit(out, Math.max(300, p.lineDelayMs));
        return out.size();
    }

    /** The exact command list a send would issue — used by /sign preview. */
    public static List<String> previewCommands(String presetName) {
        Preset p = get(presetName);
        if (p == null) return new ArrayList<>();
        return buildCommands(p, false);
    }

    /**
     * Builds the command list for a preset.
     *
     * Set-line only works on a line that ALREADY EXISTS: writing past the end
     * makes the server clamp to the last line, so line 2 overwrites line 1,
     * line 3 overwrites that... which looked like the signature "appearing for
     * a second then overwriting itself". So each line is either
     *   • replaced   (index <= the held item's current lore length), or
     *   • appended   (beyond it) via the add-line command.
     *
     * @param commit true when actually sending (updates lastWritten / warns)
     */
    private static List<String> buildCommands(Preset p, boolean commit) {
        List<String> out = new ArrayList<>();
        List<Integer> stops = resolveStops(p);

        // Work against the item's REAL lore: build the lore we want, then diff.
        // /sll cannot create a line ("that line number does not exist"), so new
        // lines must be appended with the add command; /rll re-indexes, so
        // removals run highest-first.
        List<String> current = LoreCommandConfig.heldLoreLines();
        int offset = LoreCommandConfig.lastProtectedIndex(current) + 1;

        // ── the signature lines, length-fitted ───────────────────────────
        List<String> sig = new ArrayList<>();
        for (String raw : p.lines) {
            if (raw == null || raw.isBlank()) continue;   // blank rows aren't lore lines
            String line = raw.strip();
            int budget = GradientUtil.MAX_COMMAND_LENGTH - 12; // room for "sll NN "
            if (line.length() > budget) {
                String plain = GradientUtil.stripCodes(line);
                line = stops.isEmpty()
                        ? (plain.length() <= budget ? plain : plain.substring(0, Math.max(0, budget)))
                        : GradientUtil.applyPreservingPad(line, stops, p.gradientBold, budget);
                if (commit) {
                    warn("§e✦ A line exceeded " + GradientUtil.MAX_COMMAND_LENGTH
                            + " chars — compressed to " + line.length() + ".");
                }
            }
            sig.add(line);
        }
        if (sig.isEmpty()) return out;

        // ── where the block goes ─────────────────────────────────────────
        int crateIdx = indexOfCrateLine(current);
        int start;
        if (!p.autoStart) {
            start = Math.max(0, p.startLine - 1);
        } else if (p.aboveCrateLine && crateIdx >= 0) {
            start = crateIdx;                 // push the crate line down
        } else {
            start = offset;                   // straight after the enchant block
        }

        // ── desired lore = current, minus the old signature, plus the new ─
        List<String> desired = new ArrayList<>(current);
        if (p.clearFirst) {
            if (p.lastWritten > 0 && p.lastStart >= 0) {
                int from = Math.min(p.lastStart, desired.size());
                for (int k = 0; k < p.lastWritten && from < desired.size(); k++) desired.remove(from);
                if (start > from) start = Math.max(from, start - p.lastWritten);
            }
            // Also drop lines that ARE this signature's art already on the item
            // (same characters, different colours) — that catches signatures
            // written before the mod started tracking them, so re-signing
            // replaces the old block instead of stacking a second copy.
            java.util.Set<String> sigPlain = new java.util.HashSet<>();
            for (String s : sig) sigPlain.add(GradientUtil.stripCodes(s).trim());
            for (int i = desired.size() - 1; i >= offset; i--) {
                if (sigPlain.contains(GradientUtil.stripCodes(desired.get(i)).trim())) {
                    desired.remove(i);
                    if (start > i) start--;
                }
            }
        }
        start = Math.max(0, Math.min(start, desired.size()));
        desired.addAll(start, sig);

        // Inserting shifts the rest DOWN rather than overwriting: the diff
        // rewrites everything below the insertion point, and appends land via
        // /sll at size+1 (which the server accepts as a new bottom line).
        out.addAll(LoreDiff.commands(current, desired));

        if (commit) {
            p.lastWritten = sig.size();
            p.lastStart   = start;
            save();
        }
        return out;
    }

    /** Index of the "... Crate Exclusive" line, or -1. */
    private static int indexOfCrateLine(List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            String plain = GradientUtil.stripCodes(lore.get(i))
                    .replaceAll("§[0-9a-fk-orA-FK-OR]", "").toLowerCase();
            if (plain.contains("crate exclusive")) return i;
        }
        return -1;
    }

    private static void warn(String msg) {
        ModChat.send(msg); // re-entrancy safe (see ModChat)
    }

    /** True if the line already begins with a command word (no auto prefix). */
    private static boolean looksLikeCommand(String line) {
        String lo = line.toLowerCase();
        return lo.startsWith("sll") || lo.startsWith("/") || lo.startsWith("sign");
    }

    // ── Preset API ────────────────────────────────────────────────────────

    public static Preset get(String name) {
        if (name == null) return null;
        return data.presets.get(name.toLowerCase());
    }

    public static Preset getActive() {
        Preset p = get(data.active);
        if (p == null && !data.presets.isEmpty()) p = data.presets.values().iterator().next();
        return p;
    }

    public static String getActiveName() { return data.active; }

    public static boolean setActive(String name) {
        if (name == null || !data.presets.containsKey(name.toLowerCase())) return false;
        data.active = name.toLowerCase();
        save();
        return true;
    }

    public static Collection<String> names() {
        List<String> out = new ArrayList<>();
        for (Preset p : data.presets.values()) out.add(p.name);
        return out;
    }

    /** Creates or replaces a preset's lines, preserving its gradient settings. */
    public static Preset put(String name, List<String> lines) {
        Preset existing = get(name);
        Preset p = new Preset(name, new ArrayList<>(lines));
        if (existing != null) {
            p.gradientStops = existing.gradientStops;
            p.autoGradient  = existing.autoGradient;
            p.gradientBold  = existing.gradientBold;
            p.autoPrefix    = existing.autoPrefix;
            p.prefixFormat  = existing.prefixFormat;
            p.lineDelayMs   = existing.lineDelayMs;
        }
        data.presets.put(name.toLowerCase(), p);
        save();
        return p;
    }

    public static boolean delete(String name) {
        if (name == null) return false;
        boolean removed = data.presets.remove(name.toLowerCase()) != null;
        if (removed) {
            if (data.active.equalsIgnoreCase(name)) {
                data.active = data.presets.isEmpty() ? "default"
                        : data.presets.values().iterator().next().name.toLowerCase();
            }
            save();
        }
        return removed;
    }

    public static void saveNow() { save(); }

    // ── Legacy single-string API (kept so old call sites still work) ──────

    public static String getSignature() {
        Preset p = getActive();
        return (p == null || p.lines.isEmpty()) ? DEFAULT_SIGNATURE : p.lines.get(0);
    }

    public static void setSignature(String value) {
        String v = (value == null || value.isBlank()) ? DEFAULT_SIGNATURE : value.strip();
        Preset p = getActive();
        if (p == null) {
            put("default", List.of(v));
        } else {
            if (p.lines.isEmpty()) p.lines.add(v);
            else p.lines.set(0, v);
            save();
        }
    }

    public static String getDefaultSignature() { return DEFAULT_SIGNATURE; }

    // ── Persistence ───────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(FILE)) { seedDefaults(); return; }
        try {
            String json = Files.readString(FILE);
            Data d = GSON.fromJson(json, Data.class);
            if (d != null && d.presets != null && !d.presets.isEmpty()) {
                data = d;
                if (data.active == null) data.active = data.presets.keySet().iterator().next();
                return;
            }
            // Old format: { "value": "sll 1 ..." } → migrate into a preset
            var legacy = GSON.fromJson(json, LegacySignature.class);
            if (legacy != null && legacy.value != null && !legacy.value.isBlank()) {
                seedDefaults();
                put("default", List.of(legacy.value));
                return;
            }
            seedDefaults();
        } catch (Exception e) {
            System.err.println("[AG] Failed to load signatures: " + e.getMessage());
            seedDefaults();
        }
    }

    private static class LegacySignature { public String value; }

    private static void seedDefaults() {
        data = new Data();
        put("default", List.of(DEFAULT_SIGNATURE));
        data.active = "default";
        save();
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data));
        } catch (IOException e) {
            System.err.println("[AG] Failed to save signatures: " + e.getMessage());
        }
    }
}
