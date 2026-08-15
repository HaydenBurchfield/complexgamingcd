package org.cheetahv2.antigravity.client.detection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runic Obstruction tracking.
 *
 * ── SELF debuffed (enemy's Runic Obstruction hit you) ──────────────────────
 *  • Plays a sound (totem_of_undying use, slightly lower pitch — feels "muffled")
 *  • Displays a large title: "§c⛔ RUNICED!" with subtitle showing seconds left
 *  • Adds a notification toast
 *  • Blocks all custom enchant cooldowns from ticking while active
 *    (checked via RunicObstructionManager.isSelfRuniced())
 *
 * ── OTHER PLAYER runiced (your Runic Obstruction hit them) ─────────────────
 *  • Registers a bright-yellow glow via CustomGlowManager
 *  • Registers "⛔ RUNICED" above their head via CustomHeadLabelManager
 *  • Shows a title "§e⛔ RUNIC HIT!" so you know your enchant landed
 *  • Each level = 1 second of duration (passed in from the chat trigger)
 */
public class RunicObstructionManager {

    // ── Self-debuff state ─────────────────────────────────────────────────────
    private static long selfRunicedExpiry = 0L;

    // ── Other-player debuff state ─────────────────────────────────────────────
    // Maps playerName → expiry timestamp (ms)
    private static final Map<String, Long> otherRunicedExpiry = new ConcurrentHashMap<>();

    // Cached entity IDs of currently-runiced other players (rebuilt each tick)
    public static final Set<Integer> activeRunicedIds = ConcurrentHashMap.newKeySet();

    // ── Title display state (one title shown at a time for self) ─────────────
    private static long lastTitleShownAt = 0L;
    private static int  titleFadeInTicks  = 5;
    private static int  titleStayTicks    = 40;  // 2 s
    private static int  titleFadeOutTicks = 10;

    // Yellow — 0xFFD700 in hex
    public static final int RUNIC_YELLOW = 0xFFD700;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when YOUR custom enchants are suppressed by an enemy's Runic Obstruction.
     *
     * @param attackerName  Player who used Runic Obstruction on you
     * @param level         Enchant level (1–6)
     * @param durationMs    How long the suppression lasts (level × 1000)
     */
    public static void applySelfDebuff(String attackerName, int level, long durationMs) {
        selfRunicedExpiry = System.currentTimeMillis() + durationMs;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        // ── Sound ─────────────────────────────────────────────────────────────
        mc.getSoundManager().play(
                PositionedSoundInstance.ambient(
                        SoundEvents.ITEM_TOTEM_USE,
                        0.55f,   // pitch: lower = heavier / muffled
                        0.8f     // volume
                )
        );

        // ── Title ─────────────────────────────────────────────────────────────
        int seconds = (int)(durationMs / 1000);
        mc.send(() -> {
            mc.inGameHud.setTitle(Text.literal("§c⛔ RUNICED!"));
            mc.inGameHud.setSubtitle(Text.literal(
                    "§7Your runes are disabled for §c" + seconds + "s §7by §e"
                            + (attackerName != null ? attackerName : "someone")
            ));
            mc.inGameHud.setTitleTicks(titleFadeInTicks, titleStayTicks, titleFadeOutTicks);
            lastTitleShownAt = System.currentTimeMillis();
        });

        // ── Toast notification ────────────────────────────────────────────────
        AntigravityClient.NOTIF_MANAGER.push(
                "§c⛔ §e" + (attackerName != null ? attackerName : "Someone")
                        + " §cruiniced you for §e" + seconds + "s§c!",
                0xFFE84A4A,
                durationMs
        );
    }

    /**
     * Called when YOUR Runic Obstruction fires and suppresses another player.
     * Now shows a full title so you get clear confirmation the enchant landed.
     *
     * @param targetName  Player who was runiced
     * @param level       Enchant level (1–6)
     * @param durationMs  Duration of suppression (level × 1000)
     */
    public static void applyTargetDebuff(String targetName, int level, long durationMs) {
        if (targetName == null || targetName.isEmpty()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        otherRunicedExpiry.put(targetName, System.currentTimeMillis() + durationMs);

        // Yellow glow (bypasses team colour via EntityGlowMixin)
        CustomGlowManager.registerGlow(targetName, durationMs, RUNIC_YELLOW);

        // Head label — pulsing "⛔ RUNICED"
        CustomHeadLabelManager.registerLabel(targetName, "§e⛔ RUNICED", durationMs);

        int seconds = (int)(durationMs / 1000);

        // ── Title: show BOTH title + subtitle so it's impossible to miss ──────
        mc.send(() -> {
            //mc.inGameHud.setTitle(Text.literal("§e⛔ RUNIC HIT!"));
            mc.inGameHud.setSubtitle(Text.literal(
                    "§b" + targetName + " §fis runiced for §e" + seconds + "s§f!"
            ));
            mc.inGameHud.setTitleTicks(titleFadeInTicks, titleStayTicks, titleFadeOutTicks);
            lastTitleShownAt = System.currentTimeMillis();
        });

        // Notification
        AntigravityClient.NOTIF_MANAGER.push(
                "§e⛔ §fDisabled §b" + targetName + "§f's runes for §e" + seconds + "s!",
                RUNIC_YELLOW,
                durationMs
        );
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** True while this client's runes are suppressed. */
    public static boolean isSelfRuniced() {
        return selfRunicedExpiry > System.currentTimeMillis();
    }

    /** Remaining suppression time in milliseconds (0 if not runiced). */
    public static long selfRemainingMs() {
        return Math.max(0L, selfRunicedExpiry - System.currentTimeMillis());
    }

    /** True if the named player is currently under Runic Obstruction. */
    public static boolean isPlayerRuniced(String playerName) {
        Long exp = otherRunicedExpiry.get(playerName);
        return exp != null && exp > System.currentTimeMillis();
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    /**
     * Call once per client tick.
     * Cleans up expired entries and rebuilds the active-glow entity-ID set.
     */
    public static void tick(MinecraftClient mc) {
        long now = System.currentTimeMillis();

        otherRunicedExpiry.entrySet().removeIf(e -> e.getValue() <= now);

        activeRunicedIds.clear();
        if (mc == null || mc.world == null) return;

        for (PlayerEntity p : mc.world.getPlayers()) {
            String name = p.getName().getString();
            Long exp = otherRunicedExpiry.get(name);
            if (exp != null && exp > now) {
                activeRunicedIds.add(p.getId());
            }
        }

        // Refresh subtitle each second while self is runiced
        if (isSelfRuniced() && (now - lastTitleShownAt) >= 1000L) {
            mc.send(() -> {
                long rem = selfRemainingMs();
                if (rem <= 0) return;
                int secs = (int)Math.ceil(rem / 1000.0);
                mc.inGameHud.setTitle(Text.literal("§c⛔ RUNICED!"));
                mc.inGameHud.setSubtitle(Text.literal("§7Runes suppressed: §c" + secs + "s §7remaining"));
                mc.inGameHud.setTitleTicks(0, 25, 5);
            });
            lastTitleShownAt = now;
        }
    }

    // ── Above-head label rendering ────────────────────────────────────────────
    // FIX: switched from labelScale (default 3) to larkLabelScale (default 5)
    //      and widened the clamp range so the label is noticeably bigger.

    public static void renderHeadLabels(
            net.minecraft.client.util.math.MatrixStack matrices,
            net.minecraft.client.render.Camera camera,
            MinecraftClient mc) {

        if (mc == null || mc.world == null || otherRunicedExpiry.isEmpty()) return;
        if (matrices == null || camera == null) return;

        TextRenderer tr = mc.textRenderer;
        long now = System.currentTimeMillis();

        // Use larkLabelScale (default 5.0) — larger than the generic labelScale (3.0).
        // Cap raised to 10 so users can go even bigger via the settings screen.
        float scale = Math.max(1.0f, Math.min(10.0f,
                AntigravityClient.HUD_SETTINGS.larkLabelScale));
        float ds     = 0.025f * scale;
        float height = AntigravityClient.HUD_SETTINGS.labelHeightOffset + 0.8f;

        net.minecraft.client.render.VertexConsumerProvider.Immediate imm =
                mc.getBufferBuilders().getEntityVertexConsumers();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;

            String name = p.getName().getString();
            Long expiry = otherRunicedExpiry.get(name);
            if (expiry == null || expiry <= now) continue;

            // Pulse between gold and orange
            boolean pulse = (now / 400) % 2 == 0;
            String text = pulse ? "§e⛔ RUNICED" : "§6⛔ RUNICED";
            int col = pulse ? (0xFF000000 | RUNIC_YELLOW) : 0xFFFF8C00;

            // Fade out in last 500ms
            long rem = expiry - now;
            if (rem < 500L) {
                int alpha = (int)(rem / 500f * 255);
                col = (alpha << 24) | (col & 0x00FFFFFF);
            }

            int w = tr.getWidth(text);

            matrices.push();
            matrices.translate(
                    p.getX() - camera.getCameraPos().x,
                    p.getY() + p.getHeight() + height - camera.getCameraPos().y,
                    p.getZ() - camera.getCameraPos().z);
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
            matrices.scale(-ds, -ds, ds);

            tr.draw(
                    Text.literal(text),
                    -w / 2f, 0f,
                    col,
                    false,
                    matrices.peek().getPositionMatrix(),
                    imm,
                    TextRenderer.TextLayerType.SEE_THROUGH,
                    0x20000000,
                    15728880);

            matrices.pop();
        }

        imm.draw();
    }
}
