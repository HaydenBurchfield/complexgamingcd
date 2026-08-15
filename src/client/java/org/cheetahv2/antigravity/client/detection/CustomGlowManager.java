package org.cheetahv2.antigravity.client.detection;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Tracks which player names should be rendered with a custom glow effect.
 * Glow entries expire automatically after the specified duration.
 * Each entry stores its own RGB colour so different enchants can glow differently.
 */
public class CustomGlowManager {

    // Maps playerName -> expiry timestamp (ms)
    private static final Map<String, Long>    glowExpiry = new ConcurrentHashMap<>();
    // Maps playerName -> RGB colour (no alpha — renderer adds it)
    private static final Map<String, Integer> glowColor  = new ConcurrentHashMap<>();

    // Cached set of entity IDs actively glowing — rebuilt each tick
    public static final Set<Integer> activeGlowIds = ConcurrentHashMap.newKeySet();

    /**
     * Register a glow with a specific RGB colour (e.g. 0xFF4AE87A for lime-green).
     * The alpha byte is stripped; the outline renderer supplies full opacity.
     */
    public static void registerGlow(String playerName, long durationMs, int color) {
        glowExpiry.put(playerName, System.currentTimeMillis() + durationMs);
        glowColor.put(playerName,  color & 0x00FFFFFF);
    }

    /** Convenience overload — white glow (vanilla default). */
    public static void registerGlow(String playerName, long durationMs) {
        registerGlow(playerName, durationMs, 0xFFFFFF);
    }

    public static boolean isGlowing(String playerName) {
        Long expiry = glowExpiry.get(playerName);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    /**
     * Returns the stored RGB colour for this player's glow outline.
     * Returns white (0xFFFFFF) if no custom colour is registered.
     */
    public static int getGlowColor(String playerName) {
        return glowColor.getOrDefault(playerName, 0xFFFFFF);
    }

    public static void tick(net.minecraft.client.MinecraftClient mc) {
        long now = System.currentTimeMillis();
        // removeIf also cleans up the colour map for expired entries
        glowExpiry.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                glowColor.remove(e.getKey());
                return true;
            }
            return false;
        });

        activeGlowIds.clear();
        if (mc == null || mc.world == null) return;

        for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
            if (isGlowing(p.getName().getString())) {
                activeGlowIds.add(p.getId());
            }
        }
    }
}