package org.cheetahv2.antigravity.client.tracker;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.util.ConfigHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FrozenPlayerTracker {
    public static final Path FILE = Paths.get("config", "antigravity", "frozen_tracker.json");

    public static class SavedConfig {
        public boolean enabled = true;
        public boolean showOutline = true;
        public boolean showHeadTag = true;
        public boolean showHudList = true;
        /** HUD widget screen position — draggable via Shift+LMB in-game */
        public int hudX = 10;
        public int hudY = 160;
        /** HUD scale — editable in the unified HUD editor. */
        public float hudScale = 1.0f;
    }

    public float getScale() {
        return Math.max(0.4f, Math.min(3.0f, config.hudScale <= 0 ? 1.0f : config.hudScale));
    }

    public void setScale(float s) { config.hudScale = Math.max(0.4f, Math.min(3.0f, s)); }

    /** Unscaled panel width — used by the HUD editor. */
    public static int getBoxW() { return BOX_W; }

    /** Current unscaled panel height. */
    public int getBoxH() { return 16 + Math.max(1, activeFrozenIds.size()) * 11; }

    private SavedConfig config = new SavedConfig();
    private final Map<String, Long> abilityFrozenPlayers = new ConcurrentHashMap<>();
    public final Set<Integer> activeFrozenIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── drag state (no screen needed — same GLFW pattern as AbilityCooldownManager) ──
    public boolean dragging = false;
    private int dragOffX, dragOffY;
    private static final int BOX_W = 120;

    private static final Pattern[] CHAT_FREEZE_PATTERNS = {
            Pattern.compile("(?i)(?:.+)?\\b([a-z0-9_]{3,16})\\b has been frozen solid"),
            Pattern.compile("(?i)freezing \\b([a-z0-9_]{3,16})\\b solid"),
            Pattern.compile("(?i)stunned \\b([a-z0-9_]{3,16})\\b for"),
            Pattern.compile("(?i)(?:.+)?\\b([a-z0-9_]{3,16})\\b was stunned by deep freeze"),
            Pattern.compile("(?i)(?:.+)?\\b([a-z0-9_]{3,16})\\b is frozen solid")
    };

    public void load() {
        SavedConfig c = ConfigHelper.load(FILE, SavedConfig.class);
        if (c != null) this.config = c;
    }

    public void save() {
        ConfigHelper.save(FILE, this.config);
    }

    public SavedConfig getConfig() {
        return config;
    }

    public void onChat(String plain) {
        if (!config.enabled) return;
        String clean = plain.replaceAll("\u00A7[0-9a-fk-orA-FK-OR]", "").trim();
        for (Pattern p : CHAT_FREEZE_PATTERNS) {
            Matcher m = p.matcher(clean);
            if (m.find()) {
                String playerName = m.group(1);
                abilityFrozenPlayers.put(playerName, System.currentTimeMillis() + 2500);
                AntigravityClient.NOTIF_MANAGER.push("\u2744 §b" + playerName + " §fIS FROZEN!", 0x33CCFF, 2500);
                break;
            }
        }
    }

    public boolean isPlayerFrozen(PlayerEntity p) {
        if (!config.enabled || p == null) return false;

        String name = p.getName().getString();
        Long expiry = abilityFrozenPlayers.get(name);
        if (expiry != null && expiry > System.currentTimeMillis()) return true;
        if (p.getFrozenTicks() > 0) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.world != null) {
            if (p.hasStatusEffect(StatusEffects.SLOWNESS)) {
                BlockPos pos = p.getBlockPos();
                net.minecraft.block.Block block = mc.world.getBlockState(pos).getBlock();
                net.minecraft.block.Block below = mc.world.getBlockState(pos.down()).getBlock();

                boolean standsOnIce = block == net.minecraft.block.Blocks.ICE ||
                        block == net.minecraft.block.Blocks.PACKED_ICE ||
                        block == net.minecraft.block.Blocks.BLUE_ICE ||
                        block == net.minecraft.block.Blocks.FROSTED_ICE ||
                        below == net.minecraft.block.Blocks.ICE ||
                        below == net.minecraft.block.Blocks.PACKED_ICE ||
                        below == net.minecraft.block.Blocks.BLUE_ICE ||
                        below == net.minecraft.block.Blocks.FROSTED_ICE ||
                        block == net.minecraft.block.Blocks.SNOW ||
                        block == net.minecraft.block.Blocks.POWDER_SNOW;
                if (standsOnIce) return true;
            }
        }
        return false;
    }

    public void tick(MinecraftClient mc) {
        activeFrozenIds.clear();
        if (!config.enabled || mc == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        abilityFrozenPlayers.entrySet().removeIf(e -> e.getValue() <= now);

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (isPlayerFrozen(p)) activeFrozenIds.add(p.getId());
        }
    }

    /**
     * Tick drag — call from ClientTickEvents.END_CLIENT_TICK (same as Lark / Cooldown drags).
     * Shift + LMB on the frozen HUD widget while no screen is open.
     */
    public void tickDrag(MinecraftClient mc) {
        if (mc == null || mc.currentScreen != null || mc.getWindow() == null) return;
        if (!config.enabled || !config.showHudList) return;

        long win = mc.getWindow().getHandle();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(win, mx, my);
        double gx = mx[0] / mc.getWindow().getScaleFactor();
        double gy = my[0] / mc.getWindow().getScaleFactor();

        boolean lmb   = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean shift  = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        // Current dynamic box height (matches renderHud, scaled)
        int frozenCount = activeFrozenIds.size();
        float scale = getScale();
        int boxH = (int)((16 + Math.max(1, frozenCount) * 11) * scale);
        int boxW = (int)(BOX_W * scale);

        int bx = config.hudX, by = config.hudY;

        if (lmb && shift && !dragging && gx >= bx && gx <= bx + boxW && gy >= by && gy <= by + boxH) {
            dragging  = true;
            dragOffX  = (int)(gx - bx);
            dragOffY  = (int)(gy - by);
        }

        if (dragging) {
            if (lmb) {
                config.hudX = (int)(gx - dragOffX);
                config.hudY = (int)(gy - dragOffY);
            } else {
                dragging = false;
                save();
            }
        }
    }

    public void renderHud(DrawContext ctx, MinecraftClient mc) {
        if (!config.enabled || !config.showHudList || mc == null || mc.world == null) return;

        List<String> frozenList = new ArrayList<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (activeFrozenIds.contains(p.getId())) frozenList.add(p.getName().getString());
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : abilityFrozenPlayers.entrySet()) {
            if (entry.getValue() > now && !frozenList.contains(entry.getKey())) {
                frozenList.add(entry.getKey());
            }
        }

        if (frozenList.isEmpty() && mc.currentScreen == null && !dragging) return;

        TextRenderer tr = mc.textRenderer;
        int boxH = 16 + Math.max(1, frozenList.size()) * 11;
        float scale = getScale();

        var ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate((float) config.hudX, (float) config.hudY);
        ms.scale(scale, scale);

        ctx.fill(0, 0, BOX_W, boxH, org.cheetahv2.antigravity.client.gui.Theme.HUD_BG);
        gborder(ctx, 0, 0, BOX_W, boxH, dragging ? 0xCCFFD060
                : org.cheetahv2.antigravity.client.gui.Theme.BORDER_SOFT);
        ctx.fill(1, 1, BOX_W - 1, 2, 0x15FFFFFF);
        ctx.fill(1, 1, 3, boxH - 1, 0xFF33CCFF);

        ctx.drawTextWithShadow(tr, "\u2744 FROZEN", 7, 4, 0xFF33CCFF);
        int listY = 15;

        if (frozenList.isEmpty()) {
            ctx.drawTextWithShadow(tr, "\u00A78None", 7, listY, 0xFF888888);
        } else {
            for (String name : frozenList) {
                ctx.drawTextWithShadow(tr, "\u2744 " + name, 7, listY, 0xFF33CCFF);
                listY += 11;
            }
        }

        ms.popMatrix();
    }

    public void renderHeadLabels(net.minecraft.client.util.math.MatrixStack matrices,
                                 net.minecraft.client.render.Camera camera,
                                 MinecraftClient mc) {
        if (!config.enabled || !config.showHeadTag || mc == null || mc.world == null) return;

        TextRenderer tr = mc.textRenderer;
        float scale  = Math.max(0.5f, Math.min(4.0f, AntigravityClient.HUD_SETTINGS.labelScale));
        float ds     = 0.025f * scale;
        float height = AntigravityClient.HUD_SETTINGS.labelHeightOffset + 0.6f;
        net.minecraft.client.render.VertexConsumerProvider.Immediate imm =
                mc.getBufferBuilders().getEntityVertexConsumers();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (!activeFrozenIds.contains(p.getId())) continue;

            String text = "\u00A7b\u2744 [FROZEN]";
            int width   = tr.getWidth(text);

            matrices.push();
            matrices.translate(p.getX() - camera.getCameraPos().x,
                    p.getY() + p.getHeight() + height - camera.getCameraPos().y,
                    p.getZ() - camera.getCameraPos().z);
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
            matrices.scale(-ds, -ds, ds);

            tr.draw(Text.literal(text), -width / 2f, 0f,
                    0x33CCFF | 0xFF000000, false,
                    matrices.peek().getPositionMatrix(), imm,
                    TextRenderer.TextLayerType.SEE_THROUGH, 0x20000000, 15728880);
            matrices.pop();
        }
        imm.draw();
    }

    private void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }
}