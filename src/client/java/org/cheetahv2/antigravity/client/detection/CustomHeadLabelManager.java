package org.cheetahv2.antigravity.client.detection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks and renders custom above-head labels over specific players.
 * Labels expire automatically after the specified duration.
 * Used by custom enchants like Love Birds to render "❤ STUNNED ❤" above target players.
 */
public class CustomHeadLabelManager {

    // Maps playerName -> expiry timestamp
    private static final Map<String, Long>   labelExpiry = new ConcurrentHashMap<>();
    // Maps playerName -> label text
    private static final Map<String, String> labelText   = new ConcurrentHashMap<>();

    public static void registerLabel(String playerName, String text, long durationMs) {
        labelExpiry.put(playerName, System.currentTimeMillis() + durationMs);
        labelText.put(playerName, text);
    }

    public static boolean hasLabel(String playerName) {
        Long expiry = labelExpiry.get(playerName);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public static String getLabel(String playerName) {
        return labelText.getOrDefault(playerName, "");
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        labelExpiry.entrySet().removeIf(e -> {
            if (e.getValue() <= now) {
                labelText.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Renders above-head labels in the world for all active target effects.
     * Called from WorldRenderEvents.AFTER_ENTITIES.
     */
    public static void renderHeadLabels(
            net.minecraft.client.util.math.MatrixStack matrices,
            net.minecraft.client.render.Camera camera,
            MinecraftClient mc) {

        if (mc == null || mc.world == null || labelExpiry.isEmpty()) return;
        if (matrices == null || camera == null) return;

        TextRenderer tr = mc.textRenderer;
        long now = System.currentTimeMillis();

        float scale  = Math.max(0.5f, Math.min(4.0f, AntigravityClient.HUD_SETTINGS.labelScale));
        float ds     = 0.025f * scale;
        float height = AntigravityClient.HUD_SETTINGS.labelHeightOffset + 0.8f;

        net.minecraft.client.render.VertexConsumerProvider.Immediate imm =
                mc.getBufferBuilders().getEntityVertexConsumers();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;

            String name = p.getName().getString();
            Long expiry = labelExpiry.get(name);
            if (expiry == null || expiry <= now) {
                labelExpiry.remove(name);
                labelText.remove(name);
                continue;
            }

            String text = labelText.getOrDefault(name, "");
            if (text.isEmpty()) continue;

            // Pulse alpha for last 500ms
            long rem = expiry - now;
            float alpha = (rem < 500L) ? (rem / 500f) : 1f;
            int alphaByte = (int)(alpha * 255);
            int col = (alphaByte << 24) | 0xFFAA00;

            int width = tr.getWidth(text);

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
                    -width / 2f,
                    0f,
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
