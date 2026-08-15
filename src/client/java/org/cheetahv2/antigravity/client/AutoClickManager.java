package org.cheetahv2.antigravity.client;

import java.util.Random;

/**
 * Manages /toggleclick auto-click state.
 *
 * While active, signals that LMB should be held.
 * Every 3–10 seconds it randomly lets go for 80–300 ms.
 */
public class AutoClickManager {

    private static boolean active      = false;
    private static boolean inPause     = false;
    private static long    nextEventMs = 0L;

    private static final Random RNG = new Random();

    /** Gap between random pauses: 3 – 10 s */
    private static final long MIN_CLICK_MS = 3_000L;
    private static final long MAX_CLICK_MS = 10_000L;

    /** Duration of each random release: 80 – 300 ms */
    private static final long MIN_PAUSE_MS = 80L;
    private static final long MAX_PAUSE_MS = 300L;

    public static boolean isActive()    { return active; }

    /** True when we should be pressing LMB right now. */
    public static boolean shouldClick() { return active && !inPause; }

    /**
     * Toggle on/off.
     * @return new state — true = ON
     */
    public static boolean toggle() {
        active = !active;
        if (active) {
            inPause = false;
            scheduleNextPause();
        }
        return active;
    }

    public static void disable() {
        active  = false;
        inPause = false;
    }

    /** Must be called every client tick to drive the random pause timer. */
    public static void tick() {
        if (!active) return;
        long now = System.currentTimeMillis();
        if (!inPause && now >= nextEventMs) {
            // Start a random release
            inPause     = true;
            nextEventMs = now + MIN_PAUSE_MS
                    + (long)(RNG.nextDouble() * (MAX_PAUSE_MS - MIN_PAUSE_MS));
        } else if (inPause && now >= nextEventMs) {
            // End the release, schedule next one
            inPause = false;
            scheduleNextPause();
        }
    }

    private static void scheduleNextPause() {
        nextEventMs = System.currentTimeMillis()
                + MIN_CLICK_MS
                + (long)(RNG.nextDouble() * (MAX_CLICK_MS - MIN_CLICK_MS));
    }
}
