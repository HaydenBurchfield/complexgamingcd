package org.cheetahv2.antigravity.client.detection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks "pinned" target players who should always glow a specific color.
 * Unlike CustomGlowManager (which is expiry-based), targets glow permanently
 * until explicitly removed.
 *
 * Persisted to: config/antigravity/targets.json
 */
public class TargetManager {

    // ── Preset color names ──────────────────────────────────────────────
    public static final Map<String, Integer> COLOR_NAMES = new LinkedHashMap<>();
    static {
        COLOR_NAMES.put("red",     0xFF4444);
        COLOR_NAMES.put("orange",  0xFF8800);
        COLOR_NAMES.put("yellow",  0xFFFF00);
        COLOR_NAMES.put("green",   0x44FF44);
        COLOR_NAMES.put("cyan",    0x00FFFF);
        COLOR_NAMES.put("blue",    0x4488FF);
        COLOR_NAMES.put("purple",  0xAA44FF);
        COLOR_NAMES.put("pink",    0xFF44CC);
        COLOR_NAMES.put("white",   0xFFFFFF);
        COLOR_NAMES.put("aqua",    0x00FFEE);
        COLOR_NAMES.put("lime",    0x55FF55);
        COLOR_NAMES.put("magenta", 0xFF00FF);
        COLOR_NAMES.put("gold",    0xFFD700);
    }

    public static final int DEFAULT_COLOR = 0xFF4444; // red

    // ── Internal storage ─────────────────────────────────────────────────
    // playerName -> RGB int (0x00RRGGBB)
    private static final Map<String, Integer> targets = new ConcurrentHashMap<>();

    private static final Gson   GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path   FILE = Paths.get("config", "antigravity", "targets.json");

    // ── Public API ───────────────────────────────────────────────────────

    /** Add (or update) a target with a specific RGB color. */
    public static void addTarget(String playerName, int color) {
        targets.put(playerName, color & 0x00FFFFFF);
        save();
    }

    /** Remove a target. Returns true if it existed. */
    public static boolean removeTarget(String playerName) {
        Integer removed = targets.remove(playerName);
        if (removed != null) {
            save();
            return true;
        }
        return false;
    }

    public static boolean isTarget(String playerName) {
        return targets.containsKey(playerName);
    }

    /** Returns the stored RGB color for a target, or DEFAULT_COLOR if not found. */
    public static int getColor(String playerName) {
        return targets.getOrDefault(playerName, DEFAULT_COLOR);
    }

    /** Snapshot of all current targets. Safe to iterate on the render thread. */
    public static Map<String, Integer> getAll() {
        return Collections.unmodifiableMap(targets);
    }

    public static int count() {
        return targets.size();
    }

    /** Parse a color string: named ("red"), hex ("#FF0000" / "FF0000"). */
    public static int parseColor(String input) {
        if (input == null || input.isBlank()) return DEFAULT_COLOR;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (COLOR_NAMES.containsKey(s)) return COLOR_NAMES.get(s);
        s = s.startsWith("#") ? s.substring(1) : s;
        s = s.startsWith("0x") ? s.substring(2) : s;
        try {
            return (int)(Long.parseLong(s, 16) & 0x00FFFFFF);
        } catch (NumberFormatException e) {
            return DEFAULT_COLOR;
        }
    }

    // ── Tick — called from the client tick loop ───────────────────────────
    /**
     * Re-registers every target into CustomGlowManager with a 2-second window
     * so they stay permanently lit as long as TargetManager is ticked.
     */
    public static void tick(MinecraftClient mc) {
        if (mc == null || mc.world == null) return;
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            CustomGlowManager.registerGlow(e.getKey(), 2_000L, e.getValue());
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────
    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(targets));
        } catch (IOException ignored) {}
    }

    public static void load() {
        if (!Files.exists(FILE)) return;
        try {
            String json = Files.readString(FILE);
            Type type = new TypeToken<Map<String, Integer>>(){}.getType();
            Map<String, Integer> loaded = GSON.fromJson(json, type);
            if (loaded != null) {
                targets.clear();
                loaded.forEach((k, v) -> targets.put(k, v & 0x00FFFFFF));
            }
        } catch (IOException ignored) {}
    }
}
