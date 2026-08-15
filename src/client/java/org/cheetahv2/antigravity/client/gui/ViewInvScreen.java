package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.detection.LarkManager;
import org.cheetahv2.antigravity.client.tracker.PlayerTracker;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * ViewInvScreen — shows a player's armor/hands + recent held items.
 *
 * Two sources:
 *   • LIVE:   a PlayerEntity currently in render distance (I-key raycast).
 *   • CACHED: a PlayerTracker.InvSnapshot — what the player had the last time
 *             they were seen. Used by "/invsee <name>" for players out of
 *             render distance. A "CACHED · seen Xs ago" badge is shown.
 */
public class ViewInvScreen extends Screen {
    private static final int SLOT = 18;

    private final PlayerEntity target;          // null when showing a cached snapshot
    private final String targetName;
    private final ItemStack head, chest, legs, feet, mainHand, offHand;
    private final float snapHealth, snapMaxHealth;
    private final long seenAtMs;                // 0 = live view
    private ItemStack hoveredStack = ItemStack.EMPTY;

    // Liquid Glass Palette — unified Antigravity theme (see gui/Theme)
    private static final int
            GLASS_BG       = Theme.GLASS_BG,
            GLASS_BORDER   = Theme.BORDER_SOFT,
            GLASS_SHEEN    = Theme.SHEEN,
            GLASS_SHADOW   = Theme.SHADOW,
            SLOT_BORDER    = 0x108D77E8,
            COLOR_GREEN    = Theme.GOOD,
            COLOR_GOLD     = Theme.WARN,
            COLOR_WHITE    = Theme.TEXT_HI;

    /** Live view of a player in render distance. */
    public ViewInvScreen(PlayerEntity p) {
        super(Text.literal("viewinv"));
        this.target = p;
        this.targetName = p.getName().getString();
        this.head = p.getInventory().getStack(39).copy();
        this.chest = p.getInventory().getStack(38).copy();
        this.legs = p.getInventory().getStack(37).copy();
        this.feet = p.getInventory().getStack(36).copy();
        this.mainHand = p.getMainHandStack().copy();
        this.offHand = p.getOffHandStack().copy();
        this.snapHealth = p.getHealth();
        this.snapMaxHealth = p.getMaxHealth();
        this.seenAtMs = 0;
    }

    /** Cached view built from the last snapshot of a player we've seen. */
    public ViewInvScreen(PlayerTracker.InvSnapshot snap) {
        super(Text.literal("viewinv"));
        this.target = null;
        this.targetName = snap.name;
        this.head = snap.head;
        this.chest = snap.chest;
        this.legs = snap.legs;
        this.feet = snap.feet;
        this.mainHand = snap.mainHand;
        this.offHand = snap.offHand;
        this.snapHealth = snap.health;
        this.snapMaxHealth = snap.maxHealth;
        this.seenAtMs = snap.seenAtMs;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int pw() { return 340; }
    private int ph() { return 250; }
    private int px() { return (width - pw()) / 2; }
    private int py() { return (height - ph()) / 2; }

    private void drawGlassPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, GLASS_BG);
        gborder(ctx, x, y, w, h, GLASS_BORDER);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, GLASS_SHEEN);
        ctx.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, GLASS_SHADOW);
    }

    private void drawGlassSlot(DrawContext ctx, int x, int y) {
        ctx.fill(x, y, x + SLOT, y + SLOT, 0x40000000);
        gborder(ctx, x, y, SLOT, SLOT, SLOT_BORDER);
        ctx.fill(x + 1, y + 1, x + SLOT - 1, y + 2, 0x10FFFFFF);
        ctx.fill(x + 1, y + 1, x + 2, y + SLOT - 1, 0x10FFFFFF);
    }

    private static void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }

    /** "12s ago" / "3m ago" / "1h 4m ago" */
    private static String agoStr(long sinceMs) {
        long s = sinceMs / 1000;
        if (s < 60) return s + "s ago";
        long m = s / 60;
        if (m < 60) return m + "m " + (s % 60) + "s ago";
        return (m / 60) + "h " + (m % 60) + "m ago";
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        int px = px(), py = py(), pw = pw(), ph = ph();
        ctx.fill(0, 0, width, height, 0x70020104); // subtle background dim

        drawGlassPanel(ctx, px, py, pw, ph);

        // Header
        ctx.fill(px + 6, py + 6, px + pw - 6, py + 24, 0x30000000);
        gborder(ctx, px + 6, py + 6, pw - 12, 18, 0x10FFFFFF);
        ctx.drawTextWithShadow(textRenderer, Theme.WORDMARK + " §8| §fViewing: §d" + targetName, px + 12, py + 11, COLOR_WHITE);

        boolean cached = target == null;

        // Badge (top right): Lark level for live views, CACHED for snapshots
        if (cached) {
            String badge = "⌛ CACHED · " + agoStr(System.currentTimeMillis() - seenAtMs);
            int bw = textRenderer.getWidth(badge) + 10;
            ctx.fill(px + pw - bw - 12, py + 8, px + pw - 12, py + 22, 0x60201800);
            gborder(ctx, px + pw - bw - 12, py + 8, bw, 14, COLOR_GOLD);
            ctx.drawTextWithShadow(textRenderer, badge, px + pw - bw - 7, py + 11, COLOR_GOLD);
        } else {
            int larkLvl = LarkManager.getLarkLevel(mainHand);
            if (larkLvl > 0) {
                String[] R = {"", "I", "II", "III", "IV", "V"};
                String badge = "⚔ Lark " + R[Math.min(larkLvl, 5)];
                int bw = textRenderer.getWidth(badge) + 10;
                ctx.fill(px + pw - bw - 12, py + 8, px + pw - 12, py + 22, 0x60002000);
                gborder(ctx, px + pw - bw - 12, py + 8, bw, 14, COLOR_GREEN);
                ctx.drawTextWithShadow(textRenderer, badge, px + pw - bw - 7, py + 11, 0xFF4AE87A);
            }
        }

        int ly = py + 34;

        // Target stats info slot (Health & state)
        ctx.fill(px + 8, ly, px + pw - 8, ly + 18, 0x20FFFFFF);
        gborder(ctx, px + 8, ly, pw - 16, 18, 0x10FFFFFF);

        float hp    = target != null ? target.getHealth()    : snapHealth;
        float maxHp = target != null ? target.getMaxHealth() : snapMaxHealth;
        String hpStr = String.format("§c❤ §fHealth: §e%.1f/%.1f%s", hp, maxHp,
                cached ? " §8(last seen)" : "");
        ctx.drawTextWithShadow(textRenderer, hpStr, px + 14, ly + 5, COLOR_WHITE);

        String stateStr = cached
                ? "§6⌛ §fState: §eOut of range"
                : "§9⏳ §fState: " + (target.isDead() ? "§cDead" : "§aAlive");
        int tw = textRenderer.getWidth(stateStr);
        ctx.drawTextWithShadow(textRenderer, stateStr, px + pw - tw - 14, ly + 5, COLOR_WHITE);

        ly += 24;

        // Section Label
        ctx.drawTextWithShadow(textRenderer, "§8Target Equipment Slots:", px + 8, ly, Theme.ACCENT);
        ly += 11;

        ItemStack[] equip = {head, chest, legs, feet, mainHand, offHand};
        String[] labels = {"Head", "Chest", "Legs", "Feet", "Hand", "Off"};
        int slotY = ly, slotX = px + 8;
        for (int i = 0; i < 6; i++) {
            int sx = slotX + i * (SLOT + 6);
            drawSlot(ctx, textRenderer, mx, my, sx, slotY, equip[i], labels[i]);
            if (!equip[i].isEmpty() && equip[i].isDamageable()) {
                int dur = equip[i].getMaxDamage() - equip[i].getDamage();
                int maxDur = equip[i].getMaxDamage();
                float pct = (float) dur / maxDur;
                int col = pct > 0.6f ? 0xFF4AE87A : pct > 0.3f ? 0xFFFFD060 : 0xFFE84A6A;
                String durStr = dur + "/" + maxDur;
                ctx.drawCenteredTextWithShadow(textRenderer, durStr, sx + SLOT / 2, slotY + SLOT + 10, col);
            }
        }
        ly += SLOT + 22;

        // Visual divider
        ctx.fill(px + 8, ly, px + pw - 8, ly + 1, 0x20FFFFFF);
        ly += 6;

        ctx.drawTextWithShadow(textRenderer, "§8Recent Held Items Logs:", px + 8, ly, Theme.ACCENT);
        ly += 11;

        List<ItemStack> hist = AntigravityClient.PLAYER_TRACKER.getHistory(targetName);
        int histX = px + 8;
        for (int i = 0; i < Math.min(10, hist.size()); i++) {
            int sx = histX + i * (SLOT + 6);
            if (sx + SLOT > px + pw - 6) break;
            drawSlot(ctx, textRenderer, mx, my, sx, ly, hist.get(i), null);
        }
        if (hist.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, "§8No logs recorded yet.", px + 8, ly + 4, 0xFF7F7F7F);
        }

        // Close instruction footer
        ctx.fill(px + 6, py + ph - 16, px + pw - 6, py + ph - 6, 0x30000000);
        gborder(ctx, px + 6, py + ph - 16, pw - 12, 10, 0x10FFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "§8Press ESC key to exit screen", px + pw / 2, py + ph - 15, 0xFF7F7F7F);

        super.render(ctx, mx, my, delta);

        // Tooltip rendering over everything else
        for (int i = 0; i < 6; i++) {
            int sx = slotX + i * (SLOT + 6);
            if (mx >= sx && mx < sx + SLOT && my >= slotY && my < slotY + SLOT && !equip[i].isEmpty()) {
                ctx.drawItemTooltip(textRenderer, equip[i], mx, my);
            }
        }
        int hy2 = py + 34 + 24 + 11 + SLOT + 22 + 6 + 11;
        for (int i = 0; i < Math.min(10, hist.size()); i++) {
            int sx = histX + i * (SLOT + 6);
            if (sx + SLOT > px + pw - 6) break;
            if (mx >= sx && mx < sx + SLOT && my >= hy2 && my < hy2 + SLOT && !hist.get(i).isEmpty()) {
                ctx.drawItemTooltip(textRenderer, hist.get(i), mx, my);
            }
        }

        // Track which item the mouse is over (for Z key inspection)
        hoveredStack = ItemStack.EMPTY;
        for (int i = 0; i < 6; i++) {
            int sx = slotX + i * (SLOT + 6);
            if (mx >= sx && mx < sx + SLOT && my >= slotY && my < slotY + SLOT && !equip[i].isEmpty()) {
                hoveredStack = equip[i];
                break;
            }
        }
        if (hoveredStack.isEmpty()) {
            for (int i = 0; i < Math.min(10, hist.size()); i++) {
                int sx = histX + i * (SLOT + 6);
                if (sx + SLOT > px + pw - 6) break;
                if (mx >= sx && mx < sx + SLOT && my >= hy2 && my < hy2 + SLOT && !hist.get(i).isEmpty()) {
                    hoveredStack = hist.get(i);
                    break;
                }
            }
        }
    }

    private void drawSlot(DrawContext ctx, TextRenderer tr, int mx, int my, int sx, int sy, ItemStack stack, String label) {
        boolean hov = mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT;

        drawGlassSlot(ctx, sx, sy);

        if (hov) {
            ctx.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x20FFFFFF);
        }

        if (!stack.isEmpty()) {
            ctx.drawItem(stack, sx + 1, sy + 1);
            ctx.drawStackOverlay(tr, stack, sx + 1, sy + 1, null);
        }
        if (label != null) {
            ctx.drawCenteredTextWithShadow(tr, "§8" + label, sx + SLOT / 2, sy + SLOT + 2, 0xFF888888);
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            MinecraftClient.getInstance().setScreen(null);
            return true;
        }
        int inspectKey = net.minecraft.client.util.InputUtil
                .fromTranslationKey(AntigravityClient.inspectItemKey.getBoundKeyTranslationKey())
                .getCode();
        if (input.key() == inspectKey && !hoveredStack.isEmpty()) {
            MinecraftClient.getInstance().setScreen(new ItemInspectScreen(hoveredStack));
            return true;
        }
        return super.keyPressed(input);
    }
}
