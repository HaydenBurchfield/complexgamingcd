package org.cheetahv2.antigravity.client.tracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * OutpostBeamRenderer
 *
 * Draws a tall translucent waypoint beam into the sky at every active
 * DestroyTheCore core location (parsed from chat by EventScheduleManager),
 * plus a floating "⚑ CORE" label with live distance. Multiple beams render
 * simultaneously since several cores can spawn in the first minute.
 *
 * Called from the WorldRenderEvents.AFTER_ENTITIES hook in AntigravityClient.
 */
public final class OutpostBeamRenderer {

    /** Beam half-width in blocks. */
    private static final float BEAM_HALF = 0.55f;
    /** Beam vertical extent relative to the core Y. */
    private static final float BEAM_DOWN = 8f;
    private static final float BEAM_UP   = 260f;

    // Gold/orange core beam
    private static final int BEAM_R = 255, BEAM_G = 170, BEAM_B = 0;

    private OutpostBeamRenderer() {}

    public static void render(MatrixStack matrices, Camera camera,
                              MinecraftClient mc, EventScheduleManager manager) {
        if (mc == null || mc.world == null || mc.player == null) return;
        if (!manager.getConfig().showCoreBeams) return;

        var cores = manager.getActiveCores();
        if (cores.isEmpty()) return;

        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        TextRenderer tr = mc.textRenderer;

        double camX = camera.getCameraPos().x;
        double camY = camera.getCameraPos().y;
        double camZ = camera.getCameraPos().z;

        // Slow pulse so the beam reads as "alive"
        float pulse = (float)(0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 400.0));
        int alpha = 60 + (int)(50 * pulse);

        for (EventScheduleManager.ActiveCore core : cores) {
            float cx = (float)(core.x + 0.5 - camX);
            float cz = (float)(core.z + 0.5 - camZ);
            float y0 = (float)(core.y - BEAM_DOWN - camY);
            float y1 = (float)(core.y + BEAM_UP   - camY);

            matrices.push();
            Matrix4f mat = matrices.peek().getPositionMatrix();
            VertexConsumer vc = imm.getBuffer(RenderLayers.debugQuads());

            // 4 faces of a square column, each drawn double-sided
            beamFace(vc, mat, cx - BEAM_HALF, y0, cz - BEAM_HALF, cx + BEAM_HALF, y1, cz - BEAM_HALF, alpha);
            beamFace(vc, mat, cx + BEAM_HALF, y0, cz - BEAM_HALF, cx + BEAM_HALF, y1, cz + BEAM_HALF, alpha);
            beamFace(vc, mat, cx + BEAM_HALF, y0, cz + BEAM_HALF, cx - BEAM_HALF, y1, cz + BEAM_HALF, alpha);
            beamFace(vc, mat, cx - BEAM_HALF, y0, cz + BEAM_HALF, cx - BEAM_HALF, y1, cz - BEAM_HALF, alpha);

            // Bright inner core (thinner, more opaque)
            float inner = BEAM_HALF * 0.35f;
            int innerAlpha = Math.min(255, alpha + 90);
            beamFace(vc, mat, cx - inner, y0, cz - inner, cx + inner, y1, cz - inner, innerAlpha);
            beamFace(vc, mat, cx + inner, y0, cz - inner, cx + inner, y1, cz + inner, innerAlpha);
            beamFace(vc, mat, cx + inner, y0, cz + inner, cx - inner, y1, cz + inner, innerAlpha);
            beamFace(vc, mat, cx - inner, y0, cz + inner, cx - inner, y1, cz - inner, innerAlpha);

            matrices.pop();
            imm.draw();

            // ── Floating label with distance ──────────────────────────────
            double dist = Math.sqrt(mc.player.squaredDistanceTo(
                    core.x + 0.5, core.y, core.z + 0.5));
            String label = "§6⚑ CORE" + (core.name != null ? " §e" + core.name : "")
                    + " §f" + (int) dist + "m";
            int width = tr.getWidth(label);

            // Scale label up with distance so it stays readable from far away
            float ds = 0.025f * Math.max(1.5f, (float)(dist / 12.0));

            matrices.push();
            matrices.translate(core.x + 0.5 - camX, core.y + 3.0 - camY, core.z + 0.5 - camZ);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
            matrices.scale(-ds, -ds, ds);

            tr.draw(Text.literal(label), -width / 2f, 0f,
                    0xFFFFAA00, false,
                    matrices.peek().getPositionMatrix(), imm,
                    TextRenderer.TextLayerType.SEE_THROUGH,
                    0x40000000, 15728880);
            matrices.pop();
            imm.draw();
        }
    }

    /** One vertical quad from (x0,y0,z0) to (x1,y1,z1), drawn on both sides. */
    private static void beamFace(VertexConsumer vc, Matrix4f mat,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1, int alpha) {
        // front winding
        vc.vertex(mat, x0, y0, z0).color(BEAM_R, BEAM_G, BEAM_B, alpha);
        vc.vertex(mat, x1, y0, z1).color(BEAM_R, BEAM_G, BEAM_B, alpha);
        vc.vertex(mat, x1, y1, z1).color(BEAM_R, BEAM_G, BEAM_B, alpha / 3);
        vc.vertex(mat, x0, y1, z0).color(BEAM_R, BEAM_G, BEAM_B, alpha / 3);
        // back winding (visible from the other side)
        vc.vertex(mat, x0, y1, z0).color(BEAM_R, BEAM_G, BEAM_B, alpha / 3);
        vc.vertex(mat, x1, y1, z1).color(BEAM_R, BEAM_G, BEAM_B, alpha / 3);
        vc.vertex(mat, x1, y0, z1).color(BEAM_R, BEAM_G, BEAM_B, alpha);
        vc.vertex(mat, x0, y0, z0).color(BEAM_R, BEAM_G, BEAM_B, alpha);
    }
}
