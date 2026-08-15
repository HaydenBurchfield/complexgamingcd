package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.detection.TargetManager;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * /target — Lists all pinned targets.
 *
 * Player heads are rendered via ctx.drawItem() on a PLAYER_HEAD ItemStack
 * with CUSTOM_DATA containing {"SkullOwner": "playerName"}.
 * This avoids the ProfileComponent / RenderPipeline API entirely and works
 * across all 1.21.x versions.
 */
public class TargetListScreen extends Screen {

    // ── Palette — unified Antigravity theme (see gui/Theme) ───────────────
    private static final int
            BG          = Theme.BG,
            PANEL       = Theme.PANEL,
            PANEL_HOV   = Theme.PANEL_LIT,
            BORDER      = Theme.BORDER,
            ACCENT      = Theme.ACCENT,
            TEXT_HI     = Theme.TEXT_HI,
            TEXT_MID    = Theme.TEXT_MID,
            TEXT_DIM    = Theme.TEXT_DIM,
            RED_HOV     = Theme.BAD;

    // ── Layout ────────────────────────────────────────────────────────────
    private static final int PW = 360, PH = 320;
    private static final int HDR_H = 28;
    private static final int ROW_H = 36;
    private static final int ROW_PAD = 4;
    private static final int MARGIN = 10;
    private static final int HEAD_SIZE = 16; // drawItem always renders 16x16
    private static final int BTN_W = 22, BTN_H = 16;

    private int px() { return (width  - PW) / 2; }
    private int py() { return (height - PH) / 2; }

    // ── State ─────────────────────────────────────────────────────────────
    private int scrollOffset = 0;
    private final int MAX_VISIBLE = 7;
    private List<Map.Entry<String, Integer>> rows = new ArrayList<>();

    // Cache head stacks — rebuilt if target list changes
    private final Map<String, ItemStack> headCache = new HashMap<>();

    // GLFW polling
    private boolean prevLmb = false;
    private double gx, gy;
    private int hovRow  = -1;
    private int hovType = -1;
    private boolean hovUp = false, hovDown = false;

    public TargetListScreen() {
        super(Text.literal("Target List"));
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Render ────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        rows = new ArrayList<>(TargetManager.getAll().entrySet());
        rows.sort(Map.Entry.comparingByKey());

        int px = px(), py = py();

        // Cursor
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        double[] cx = new double[1], cy = new double[1];
        GLFW.glfwGetCursorPos(win, cx, cy);
        double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
        gx = cx[0] / scale;
        gy = cy[0] / scale;
        boolean lmb = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean click = lmb && !prevLmb;

        // Background
        ctx.fill(px - 2, py - 2, px + PW + 2, py + PH + 2, BORDER);
        ctx.fill(px, py, px + PW, py + PH, BG);
        ctx.fill(px, py, px + PW, py + HDR_H, 0xCC1A0F36);
        ctx.fill(px, py + HDR_H - 1, px + PW, py + HDR_H, BORDER);

        // Title
        ctx.drawText(textRenderer, Text.literal("\u25CE  TARGET LIST"), px + 10, py + 9, ACCENT, false);
        String badge = rows.size() + " target" + (rows.size() == 1 ? "" : "s");
        ctx.drawText(textRenderer, Text.literal(badge),
                px + PW - textRenderer.getWidth(badge) - 10, py + 9, TEXT_DIM, false);

        // Scroll arrows
        int contentAreaH = PH - HDR_H - 6;
        int maxRows = rows.size();
        boolean canScrollUp   = scrollOffset > 0;
        boolean canScrollDown = (scrollOffset + MAX_VISIBLE) < maxRows;

        int arrowX = px + PW - 16;
        int upArY  = py + HDR_H + 4;
        int dnArY  = py + PH - 14;

        hovUp   = gx >= arrowX && gx <= arrowX + 12 && gy >= upArY && gy <= upArY + 10;
        hovDown = gx >= arrowX && gx <= arrowX + 12 && gy >= dnArY && gy <= dnArY + 10;

        if (canScrollUp) {
            ctx.fill(arrowX, upArY, arrowX + 12, upArY + 10, hovUp ? PANEL_HOV : PANEL);
            ctx.drawText(textRenderer, Text.literal("\u25B2"), arrowX + 2, upArY + 1,
                    hovUp ? ACCENT : TEXT_MID, false);
            if (click && hovUp) scrollOffset--;
        }
        if (canScrollDown) {
            ctx.fill(arrowX, dnArY, arrowX + 12, dnArY + 10, hovDown ? PANEL_HOV : PANEL);
            ctx.drawText(textRenderer, Text.literal("\u25BC"), arrowX + 2, dnArY + 1,
                    hovDown ? ACCENT : TEXT_MID, false);
            if (click && hovDown) scrollOffset++;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, maxRows - MAX_VISIBLE)));

        // Empty state
        if (rows.isEmpty()) {
            String msg1 = "No targets set.";
            String msg2 = "Use: /target add <player> [color]";
            ctx.drawText(textRenderer, Text.literal(msg1),
                    px + (PW - textRenderer.getWidth(msg1)) / 2,
                    py + HDR_H + contentAreaH / 2 - 10, TEXT_MID, false);
            ctx.drawText(textRenderer, Text.literal(msg2),
                    px + (PW - textRenderer.getWidth(msg2)) / 2,
                    py + HDR_H + contentAreaH / 2 + 4, TEXT_DIM, false);
            prevLmb = lmb;
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        // Rows
        hovRow  = -1;
        hovType = -1;

        int rowY = py + HDR_H + ROW_PAD;
        int visCount = Math.min(MAX_VISIBLE, rows.size() - scrollOffset);

        for (int i = 0; i < visCount; i++) {
            int idx = scrollOffset + i;
            Map.Entry<String, Integer> entry = rows.get(idx);
            String name  = entry.getKey();
            int    color = entry.getValue();

            int rx = px + MARGIN;
            int rw = PW - MARGIN * 2 - 18;
            int ry = rowY + i * (ROW_H + 2);

            int btnX = rx + rw - BTN_W - 2;
            boolean overRow = gx >= rx && gx <= rx + rw && gy >= ry && gy <= ry + ROW_H;
            boolean overBtn = gx >= btnX && gx <= btnX + BTN_W
                    && gy >= ry + (ROW_H - BTN_H) / 2
                    && gy <= ry + (ROW_H - BTN_H) / 2 + BTN_H;

            if (overRow) { hovRow = idx; hovType = overBtn ? 1 : 0; }

            boolean rowHov = (hovRow == idx && hovType == 0);
            ctx.fill(rx, ry, rx + rw, ry + ROW_H, rowHov ? PANEL_HOV : PANEL);
            ctx.fill(rx, ry, rx + 1, ry + ROW_H, color | 0xFF000000);

            // Player head — centered vertically in the row
            int headX = rx + 4;
            int headY = ry + (ROW_H - HEAD_SIZE) / 2;
            ctx.drawItem(getHeadStack(name), headX, headY);

            // Name
            ctx.drawText(textRenderer, Text.literal(name),
                    headX + HEAD_SIZE + 6, ry + 6, TEXT_HI, false);

            // Color swatch + label
            String colorLabel = colorLabel(color);
            int swatchX = headX + HEAD_SIZE + 6;
            int swatchY = ry + ROW_H - 12;
            ctx.fill(swatchX, swatchY, swatchX + 8, swatchY + 7, color | 0xFF000000);
            ctx.fill(swatchX, swatchY, swatchX + 8, swatchY + 1, 0x40FFFFFF);
            ctx.drawText(textRenderer, Text.literal(colorLabel),
                    swatchX + 10, swatchY, TEXT_MID, false);

            // Remove [×] button
            int btnY = ry + (ROW_H - BTN_H) / 2;
            boolean btnHov = (hovRow == idx && hovType == 1);
            ctx.fill(btnX, btnY, btnX + BTN_W, btnY + BTN_H,
                    btnHov ? RED_HOV : 0x55DD4444);
            ctx.fill(btnX, btnY, btnX + BTN_W, btnY + 1, 0x30FFFFFF);
            String xLabel = "\u00D7";
            ctx.drawText(textRenderer, Text.literal(xLabel),
                    btnX + (BTN_W - textRenderer.getWidth(xLabel)) / 2,
                    btnY + (BTN_H - 7) / 2,
                    btnHov ? 0xFFFFFFFF : 0xFFDD4444, false);

            if (click && overBtn) {
                TargetManager.removeTarget(name);
                headCache.remove(name);
            }
        }

        // Footer
        String hint = "ESC to close  •  /target add <player> [color]";
        ctx.drawText(textRenderer, Text.literal(hint),
                px + (PW - textRenderer.getWidth(hint)) / 2,
                py + PH - 11, TEXT_DIM, false);

        prevLmb = lmb;
        super.render(ctx, mouseX, mouseY, delta);
    }

    // ── Player head ItemStack ─────────────────────────────────────────────
    /**
     * Builds a PLAYER_HEAD ItemStack using CUSTOM_DATA with a SkullOwner NBT tag.
     * This is the most version-stable way to get a named player head without
     * touching ProfileComponent or any drawTexture API.
     *
     * The SkullOwner tag is recognized by Minecraft's skull item renderer and
     * will trigger a skin lookup for the named player automatically.
     */
    private ItemStack getHeadStack(String playerName) {
        return headCache.computeIfAbsent(playerName, name -> {
            ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
            NbtCompound nbt = new NbtCompound();
            nbt.putString("SkullOwner", name);
            stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
            return stack;
        });
    }

    // ── Color label ──────────────────────────────────────────────────────
    private static String colorLabel(int rgb) {
        for (Map.Entry<String, Integer> e : TargetManager.COLOR_NAMES.entrySet()) {
            if (e.getValue() == rgb) return e.getKey();
        }
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    // ── Input ─────────────────────────────────────────────────────────────
    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            MinecraftClient.getInstance().setScreen(null);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount < 0 && (scrollOffset + MAX_VISIBLE) < rows.size()) scrollOffset++;
        if (verticalAmount > 0 && scrollOffset > 0) scrollOffset--;
        return true;
    }
}