package org.cheetahv2.antigravity.client;

/**
 * Holds the lock state for /lockcam.
 *
 * When activated, the current yaw is snapped to the nearest cardinal
 * direction (0°, 90°, 180°, -90°) and the pitch is zeroed.
 * MouseLockMixin reads these values every frame to keep the camera pinned.
 */
public class LockCamManager {

    private static boolean locked     = false;
    private static float  lockedYaw   = 5f;
    private static float  lockedPitch = 0f;

    /**
     * Toggle the lock. When enabling, snaps the provided yaw to the nearest
     * cardinal (0, 90, 180, -90) and locks pitch to 0.
     *
     * @param currentYaw   player's current yaw  (degrees, Minecraft convention)
     * @param currentPitch player's current pitch (degrees)
     */
    public static void toggle(float currentYaw, float currentPitch) {
        if (locked) {
            locked = false;
        } else {
            lockedYaw   = snapToCardinal(currentYaw);
            lockedPitch = 0f;
            locked      = true;
        }
    }

    public static boolean isLocked()      { return locked; }
    public static float   getLockedYaw()  { return lockedYaw; }
    public static float   getLockedPitch(){ return lockedPitch; }

    /**
     * Snaps an arbitrary yaw to the nearest of {0, 90, 180, -90}.
     *
     * Minecraft yaw convention:
     *   0   = South  (+Z)
     *   90  = West   (-X)
     *   180 / -180 = North (-Z)
     *  -90  = East   (+X)
     *
     * We normalise to [-180, 180) first, then pick the nearest cardinal.
     */
    private static float snapToCardinal(float yaw) {
        // Normalise to [-180, 180)
        yaw = yaw % 360f;
        if (yaw <= -180f) yaw += 360f;
        if (yaw >   180f) yaw -= 360f;

        // Cardinals to compare against
        float[] cardinals = { 0f, 90f, 180f, -90f };

        float best     = cardinals[0];
        float bestDist = Float.MAX_VALUE;

        for (float c : cardinals) {
            // Angular distance, accounting for wrap-around
            float diff = Math.abs(yaw - c);
            if (diff > 180f) diff = 360f - diff;
            if (diff < bestDist) {
                bestDist = diff;
                best     = c;
            }
        }
        return best;
    }
}
