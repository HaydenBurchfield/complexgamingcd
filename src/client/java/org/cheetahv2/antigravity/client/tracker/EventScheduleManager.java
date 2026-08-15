package org.cheetahv2.antigravity.client.tracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EventScheduleManager
 *
 * Tracks the Outpost / Ponds event rotation and the "DestroyTheCore" location.
 *
 * Schedule rules (server time = UTC, events fire every 3 h starting at 02:00):
 *   02:00 → Outpost   (then again 08:00, 14:00, 20:00)
 *   05:00 → Ponds     (then again 11:00, 17:00, 23:00)
 *
 * Each runs 6 h apart within its own track, alternating with the other track
 * so there is an event every 3 h.
 *
 * Core spawns are parsed from chat:
 *   "(/destroythecore) A core has spawned in the warzone at X: 2 Y: -11 Z: 100! ..."
 *
 * Known spawn locations are stored in config so the user can add more later.
 */
public class EventScheduleManager {

    // ── Config ──────────────────────────────────────────────────────────────
    public static class Config {
        public boolean showEventHud = true;

        /** Render sky-beam waypoints at active core spawns. */
        public boolean showCoreBeams = true;
        /** How long a core beam stays up before auto-expiring (seconds). */
        public int coreBeamDurationSec = 600;

        /** Event HUD position/scale — editable in the HUD editor. -1 = default bottom-left. */
        public int hudX = -1;
        public int hudY = -1;
        public float hudScale = 1.0f;

        /** Known core spawn locations — add more entries in the JSON to expand. */
        public List<CoreLocation> coreLocations = defaultLocations();

        private static List<CoreLocation> defaultLocations() {
            List<CoreLocation> list = new ArrayList<>();
            list.add(new CoreLocation("Warzone A",  -79, -15, 230));
            list.add(new CoreLocation("Warzone B",  -91, -13, 258));
            list.add(new CoreLocation("Warzone C",  -61, -11, 116));
            list.add(new CoreLocation("Warzone D",    2, -11, 100));
            list.add(new CoreLocation("Warzone E",   34, -17, 105));
            list.add(new CoreLocation("Warzone F",   70, -17, 136));
            // Slot G intentionally empty — fill in coords when discovered
            // list.add(new CoreLocation("Warzone G", 0, 0, 0));
            return list;
        }
    }

    public static class CoreLocation {
        public String name;
        public int x, y, z;

        public CoreLocation() {}

        public CoreLocation(String name, int x, int y, int z) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        /** Returns true if (cx, cy, cz) matches this location within tolerance. */
        public boolean matches(int cx, int cy, int cz) {
            return Math.abs(cx - x) <= 2 && Math.abs(cy - y) <= 2 && Math.abs(cz - z) <= 2;
        }

        @Override
        public String toString() {
            return name + " (" + x + ", " + y + ", " + z + ")";
        }
    }

    public enum EventType { OUTPOST, PONDS }

    public static class ScheduledEvent {
        public final EventType type;
        /** Epoch-millis of this event's start time (UTC). */
        public final long startMs;

        public ScheduledEvent(EventType type, long startMs) {
            this.type = type;
            this.startMs = startMs;
        }
    }

    // ── Singleton state ─────────────────────────────────────────────────────
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CFG_FILE =
            Paths.get("config", "antigravity", "event_schedule.json");

    private Config config = new Config();

    /**
     * All currently-active core spawns. Several cores can spawn within the
     * first minute of an outpost, so this is a list rather than a single slot.
     * Entries expire after {@link Config#coreBeamDurationSec}.
     */
    public static class ActiveCore {
        public final int x, y, z;
        /** Matched known location name, or null if coords were unknown. */
        public final String name;
        public final long spawnMs;

        public ActiveCore(int x, int y, int z, String name) {
            this.x = x; this.y = y; this.z = z;
            this.name = name;
            this.spawnMs = System.currentTimeMillis();
        }
    }

    private final java.util.concurrent.CopyOnWriteArrayList<ActiveCore> activeCores =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Chat pattern for DestroyTheCore spawn message. */
    private static final Pattern CORE_PATTERN = Pattern.compile(
            "\\(/destroythecore\\).*?at X:\\s*(-?\\d+)\\s*Y:\\s*(-?\\d+)\\s*Z:\\s*(-?\\d+)",
            Pattern.CASE_INSENSITIVE
    );

    /** Core destroyed / event over — used to clear beams early. */
    private static final Pattern CORE_DESTROYED_PATTERN = Pattern.compile(
            "\\(/destroythecore\\).*?(?:has been destroyed|was destroyed|destroyed the core)",
            Pattern.CASE_INSENSITIVE
    );

    // ── Config persistence ─────────────────────────────────────────────────

    public void load() {
        if (!Files.exists(CFG_FILE)) {
            save();
            return;
        }
        try {
            Config loaded = GSON.fromJson(Files.readString(CFG_FILE), Config.class);
            if (loaded != null) {
                config = loaded;
                // Ensure list is never null after deserialization
                if (config.coreLocations == null) config.coreLocations = Config.defaultLocations();
            }
        } catch (IOException ignored) {}
    }

    public void save() {
        try {
            Files.createDirectories(CFG_FILE.getParent());
            Files.writeString(CFG_FILE, GSON.toJson(config));
        } catch (IOException ignored) {}
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public Config getConfig() { return config; }

    public boolean isHudEnabled() { return config.showEventHud; }

    public void setHudEnabled(boolean v) {
        config.showEventHud = v;
        save();
    }

    /** All non-expired core spawns (newest last). Prunes expired entries. */
    public java.util.List<ActiveCore> getActiveCores() {
        long cutoff = System.currentTimeMillis() - config.coreBeamDurationSec * 1000L;
        activeCores.removeIf(c -> c.spawnMs < cutoff);
        return activeCores;
    }

    /** The most recent core spawn, or null (kept for the HUD's compact line). */
    public ActiveCore getLatestCore() {
        var cores = getActiveCores();
        return cores.isEmpty() ? null : cores.get(cores.size() - 1);
    }

    // ── Chat parsing ───────────────────────────────────────────────────────

    /**
     * Called from AntigravityClient.handleChatMessage().
     * Returns true if this message contained a core-spawn notification.
     */
    public boolean onChat(String stripped) {
        // Core destroyed → drop the oldest active beam
        if (CORE_DESTROYED_PATTERN.matcher(stripped).find()) {
            if (!activeCores.isEmpty()) activeCores.remove(0);
            return true;
        }

        Matcher m = CORE_PATTERN.matcher(stripped);
        if (!m.find()) return false;

        try {
            int cx = Integer.parseInt(m.group(1).trim());
            int cy = Integer.parseInt(m.group(2).trim());
            int cz = Integer.parseInt(m.group(3).trim());

            String name = null;
            for (CoreLocation loc : config.coreLocations) {
                if (loc.matches(cx, cy, cz)) {
                    name = loc.name;
                    break;
                }
            }

            // Dedupe: same spot announced twice just refreshes the beam
            final String fName = name;
            activeCores.removeIf(c ->
                    Math.abs(c.x - cx) <= 2 && Math.abs(c.y - cy) <= 2 && Math.abs(c.z - cz) <= 2);
            activeCores.add(new ActiveCore(cx, cy, cz, fName));
            while (activeCores.size() > 8) activeCores.remove(0);
        } catch (NumberFormatException ignored) {}

        return true;
    }

    /** Clear all active cores (e.g. after outpost ends). */
    public void clearCore() {
        activeCores.clear();
    }

    // ── Schedule computation ───────────────────────────────────────────────

    /**
     * Returns the next two scheduled events from now (wall-clock UTC).
     *
     * Outpost fires at UTC 02:00, 08:00, 14:00, 20:00  (every 6 h, phase 0)
     * Ponds   fires at UTC 05:00, 11:00, 17:00, 23:00  (every 6 h, phase 3)
     */
    public List<ScheduledEvent> getUpcomingEvents(int count) {
        long nowMs = System.currentTimeMillis();
        List<ScheduledEvent> upcoming = new ArrayList<>();

        // Build candidates for the next ~24 h
        ZonedDateTime nowUtc = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC);
        // Go back 12 h to ensure we cover the current event window
        ZonedDateTime scanFrom = nowUtc.minusHours(12);

        // Outpost hours (UTC): 2, 8, 14, 20
        int[] pondsHours = {3, 9, 15, 22};
        // Ponds hours (UTC): 5, 11, 17, 23
        int[] outpostHours   = {0, 6, 12, 18};

        // Scan 3 days worth of events, collect the soonest `count`
        for (int day = 0; day < 3; day++) {
            LocalDate date = scanFrom.toLocalDate().plusDays(day);

            for (int h : outpostHours) {
                ZonedDateTime t = ZonedDateTime.of(date, LocalTime.of(h, 0), ZoneOffset.UTC);
                long ms = t.toInstant().toEpochMilli();
                if (ms > nowMs) upcoming.add(new ScheduledEvent(EventType.OUTPOST, ms));
            }
            for (int h : pondsHours) {
                ZonedDateTime t = ZonedDateTime.of(date, LocalTime.of(h, 0), ZoneOffset.UTC);
                long ms = t.toInstant().toEpochMilli();
                if (ms > nowMs) upcoming.add(new ScheduledEvent(EventType.PONDS, ms));
            }
        }

        // Sort by time
        upcoming.sort((a, b) -> Long.compare(a.startMs, b.startMs));

        return upcoming.subList(0, Math.min(count, upcoming.size()));
    }

    /**
     * Returns the currently-active event, or null if we are between events.
     * An event is considered "active" for 10 minutes after its start.
     */
    public ScheduledEvent getCurrentEvent() {
        long nowMs = System.currentTimeMillis();
        long windowMs = 10 * 60 * 1000L; // 10 min active window

        ZonedDateTime nowUtc = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC);

        int[] outpostHours = {2, 8, 14, 20};
        int[] pondsHours   = {5, 11, 17, 23};

        for (int day = -1; day <= 1; day++) {
            LocalDate date = nowUtc.toLocalDate().plusDays(day);

            for (int h : outpostHours) {
                ZonedDateTime t = ZonedDateTime.of(date, LocalTime.of(h, 0), ZoneOffset.UTC);
                long ms = t.toInstant().toEpochMilli();
                if (nowMs >= ms && nowMs < ms + windowMs)
                    return new ScheduledEvent(EventType.OUTPOST, ms);
            }
            for (int h : pondsHours) {
                ZonedDateTime t = ZonedDateTime.of(date, LocalTime.of(h, 0), ZoneOffset.UTC);
                long ms = t.toInstant().toEpochMilli();
                if (nowMs >= ms && nowMs < ms + windowMs)
                    return new ScheduledEvent(EventType.PONDS, ms);
            }
        }
        return null;
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    /** Formats milliseconds-until as "Xh Ym" or "Ys" depending on magnitude. */
    public static String formatCountdown(long ms) {
        if (ms <= 0) return "NOW";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    /** Returns a display name for the event type. */
    public static String eventName(EventType type) {
        return switch (type) {
            case OUTPOST -> "§6Outpost";
            case PONDS   -> "§bPonds";
        };
    }

    /** Returns the event icon character. */
    public static String eventIcon(EventType type) {
        return switch (type) {
            case OUTPOST -> "§6⚑";
            case PONDS   -> "§b◈";
        };
    }
}
