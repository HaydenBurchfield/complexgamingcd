package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.cooldown.AbilityCooldownManager;
import org.cheetahv2.antigravity.client.cooldown.MoodSwingsHud;
import org.cheetahv2.antigravity.client.cooldown.RuneAvailabilityHud;
import org.cheetahv2.antigravity.client.tracker.EventScheduleHud;
import org.cheetahv2.antigravity.client.tracker.EventScheduleManager;
import org.cheetahv2.antigravity.client.tracker.FrozenPlayerTracker;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * HudDragScreen — the unified HUD editor. Pauses the game and lets the player
 * drag + scale EVERY HUD element the mod renders:
 *
 *   Lark status, Ability Cooldowns, Rune Availability, Event Schedule,
 *   Frozen list, Mood Swings, Notifications anchor.
 *
 * Controls:
 *   • Click + drag any ghost box to reposition it.
 *   • Hover a ghost box to reveal [−] and [+] scale buttons.
 *   • ESC or [L] to close and save everything.
 *
 * Adding a new HUD element: append one HudElement adapter in buildElements().
 * Mouse handling: GLFW-polling pattern to avoid MC 1.21.5 Click-wrapper API.
 */
public class HudDragScreen extends Screen {

    // ── Palette — unified Antigravity theme (see gui/Theme) ───────────────
    private static final int
        TEXT_MID   = Theme.TEXT_MID,
        TEXT_DIM   = Theme.TEXT_DIM,
        BORDER_LIT = Theme.BORDER_LIT,
        ACCENT     = Theme.ACCENT,
        BTN_MINUS  = Theme.BAD,
        BTN_PLUS   = Theme.GOOD;

    private static final int BTN_W = 14, BTN_H = 11;

    // ── Element adapter ───────────────────────────────────────────────────
    private interface HudElement {
        String label();
        int accent();
        int x();
        int y();
        int w();
        int h();
        void setPos(int x, int y);
        float scale();
        void setScale(float s);
        default boolean scalable() { return true; }
        void save();
    }

    private final List<HudElement> elements = new ArrayList<>();

    // ── Drag state ────────────────────────────────────────────────────────
    private HudElement dragging = null;
    private int dragOffX, dragOffY;
    private boolean prevLmb = false;
    private boolean clickWasOnScaleBtn = false;

    public HudDragScreen() {
        super(Text.literal("HUD Editor"));
    }

    @Override public boolean shouldPause()       { return true; }
    @Override public boolean shouldCloseOnEsc()  { return true; }

    @Override
    protected void init() {
        elements.clear();
        buildElements();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ELEMENT REGISTRY
    // ─────────────────────────────────────────────────────────────────────
    private void buildElements() {
        AntigravityClient.HudSettings hs      = AntigravityClient.HUD_SETTINGS;
        AbilityCooldownManager acm            = AntigravityClient.ABILITY_COOLDOWN;
        AbilityCooldownManager.SavedConfig cf = acm.getConfig();
        RuneAvailabilityHud rah               = AntigravityClient.RUNE_AVAIL_HUD;
        EventScheduleManager esm              = AntigravityClient.EVENT_SCHEDULE;
        FrozenPlayerTracker fpt               = AntigravityClient.FROZEN_TRACKER;
        MoodSwingsHud mood                    = AntigravityClient.MOOD_HUD;
        AntigravityClient.NotifManager notif  = AntigravityClient.NOTIF_MANAGER;

        // 1 — Lark status
        elements.add(new HudElement() {
            public String label()  { return "✦ Lark"; }
            public int accent()    { return 0xFF5BAAFF; }
            public int x()         { return hs.larkHudX >= 0 ? hs.larkHudX : 10; }
            public int y()         { return hs.larkHudY >= 0 ? hs.larkHudY : height - 50; }
            public int w()         { return (int)(120 * hs.larkScale); }
            public int h()         { return (int)(30 * hs.larkScale); }
            public void setPos(int x, int y) { hs.larkHudX = x; hs.larkHudY = y; }
            public float scale()   { return hs.larkScale; }
            public void setScale(float s) { hs.larkScale = s; }
            public void save()     { hs.save(); }
        });

        // 2 — Ability cooldowns
        elements.add(new HudElement() {
            public String label()  { return "◆ Cooldowns"; }
            public int accent()    { return 0xFF9F6FFF; }
            public int x()         { return cf.consolidatedX; }
            public int y()         { return cf.consolidatedY; }
            public int w()         { return (int)(155 * cf.scale); }
            public int h()         { return Math.max(30, (int)((Math.max(1, acm.getActive().size()) * 19) * cf.scale)); }
            public void setPos(int x, int y) { cf.consolidatedX = x; cf.consolidatedY = y; }
            public float scale()   { return cf.scale; }
            public void setScale(float s) { cf.scale = s; }
            public void save()     { acm.save(); }
        });

        // 3 — Rune availability
        elements.add(new HudElement() {
            public String label()  { return "✔ Rune Avail"; }
            public int accent()    { return 0xFF55DDAA; }
            public int x()         { return rah.getX(width); }
            public int y()         { return rah.getY(height); }
            public int w()         { return rah.getScaledWidth(); }
            public int h()         { return Math.max(30, rah.estimatePanelH()); }
            public void setPos(int x, int y) { rah.setPos(x, y); }
            public float scale()   { return rah.getScale(); }
            public void setScale(float s) { rah.setScale(s); }
            public void save()     { rah.save(); }
        });

        // 4 — Event schedule
        elements.add(new HudElement() {
            public String label()  { return "⬡ Events"; }
            public int accent()    { return 0xFFFFCC44; }
            public int x()         { return EventScheduleHud.getX(esm, width, height); }
            public int y()         { return EventScheduleHud.getY(esm, width, height); }
            public int w()         { return (int)(EventScheduleHud.PANEL_W * EventScheduleHud.getScale(esm)); }
            public int h()         { return (int)(EventScheduleHud.getPanelH() * EventScheduleHud.getScale(esm)); }
            public void setPos(int x, int y) { esm.getConfig().hudX = x; esm.getConfig().hudY = y; }
            public float scale()   { return esm.getConfig().hudScale; }
            public void setScale(float s) { esm.getConfig().hudScale = s; }
            public void save()     { esm.save(); }
        });

        // 5 — Frozen list
        elements.add(new HudElement() {
            public String label()  { return "❄ Frozen"; }
            public int accent()    { return 0xFF33CCFF; }
            public int x()         { return fpt.getConfig().hudX; }
            public int y()         { return fpt.getConfig().hudY; }
            public int w()         { return (int)(FrozenPlayerTracker.getBoxW() * fpt.getScale()); }
            public int h()         { return (int)(fpt.getBoxH() * fpt.getScale()); }
            public void setPos(int x, int y) { fpt.getConfig().hudX = x; fpt.getConfig().hudY = y; }
            public float scale()   { return fpt.getScale(); }
            public void setScale(float s) { fpt.setScale(s); }
            public void save()     { fpt.save(); }
        });

        // 6 — Mood Swings
        elements.add(new HudElement() {
            public String label()  { return "☺ Mood"; }
            public int accent()    { return 0xFFFF5B9B; }
            public int x()         { return mood.getX(width); }
            public int y()         { return mood.getY(height); }
            public int w()         { return mood.getScaledW(); }
            public int h()         { return mood.getScaledH(); }
            public void setPos(int x, int y) { mood.setPos(x, y); }
            public float scale()   { return mood.getScale(); }
            public void setScale(float s) { mood.setScale(s); }
            public void save()     { mood.save(); }
        });

        // 7 — Notifications anchor (position only)
        elements.add(new HudElement() {
            public String label()  { return "🔔 Notifications"; }
            public int accent()    { return 0xFFFFD060; }
            public int x()         { return notif.anchorX >= 0 ? notif.anchorX : width - 210; }
            public int y()         { return notif.anchorY >= 0 ? notif.anchorY : 10; }
            public int w()         { return 200; }
            public int h()         { return 40; }
            public void setPos(int x, int y) { notif.anchorX = x; notif.anchorY = y; }
            public float scale()   { return 1f; }
            public void setScale(float s) {}
            public boolean scalable() { return false; }
            public void save()     { notif.saveAnchor(); }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // RENDER
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {

        long win = MinecraftClient.getInstance().getWindow().getHandle();
        boolean lmb = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        // ── Fresh click → scale buttons take priority, then drag ──────────
        if (lmb && !prevLmb) {
            clickWasOnScaleBtn = false;

            for (HudElement el : elements) {
                if (!el.scalable()) continue;
                int bMX = el.x() + el.w() - 2 * BTN_W - 6;
                int bPX = el.x() + el.w() - BTN_W - 3;
                int bY  = el.y() + el.h() - BTN_H - 3;
                if (isIn(mx, my, bMX, bY, BTN_W, BTN_H)) {
                    el.setScale(clampScale(el.scale() - 0.1f));
                    el.save();
                    clickWasOnScaleBtn = true;
                    break;
                }
                if (isIn(mx, my, bPX, bY, BTN_W, BTN_H)) {
                    el.setScale(clampScale(el.scale() + 0.1f));
                    el.save();
                    clickWasOnScaleBtn = true;
                    break;
                }
            }

            if (!clickWasOnScaleBtn) {
                // topmost (last drawn) wins — iterate in reverse
                for (int i = elements.size() - 1; i >= 0; i--) {
                    HudElement el = elements.get(i);
                    if (isIn(mx, my, el.x(), el.y(), el.w(), el.h())) {
                        dragging = el;
                        dragOffX = mx - el.x();
                        dragOffY = my - el.y();
                        break;
                    }
                }
            }
        }

        // ── Held → move ───────────────────────────────────────────────────
        if (lmb && !clickWasOnScaleBtn && dragging != null) {
            dragging.setPos(
                    clamp(mx - dragOffX, 0, width  - dragging.w()),
                    clamp(my - dragOffY, 0, height - dragging.h()));
        } else if (!lmb) {
            if (dragging != null) {
                dragging.save();
                dragging = null;
            }
            clickWasOnScaleBtn = false;
        }

        prevLmb = lmb;

        // ── Dimmed backdrop ───────────────────────────────────────────────
        ctx.fill(0, 0, width, height, Theme.BG_DIM);

        // ── Ghost boxes ───────────────────────────────────────────────────
        for (HudElement el : elements) {
            boolean hov  = isIn(mx, my, el.x(), el.y(), el.w(), el.h());
            boolean drag = dragging == el;

            String label = "§f" + el.label();
            if (el.scalable()) label += "  §8" + String.format("%.1fx", el.scale());

            drawGhostBox(ctx, el.x(), el.y(), el.w(), el.h(),
                    drag ? 0x90A060FF : (hov ? 0x70204060 : 0x50182838),
                    drag ? 0xFFCC88FF : (hov ? BORDER_LIT : el.accent()),
                    label, drag);

            if (el.scalable() && (hov || drag)) {
                int bMX = el.x() + el.w() - 2 * BTN_W - 6;
                int bY  = el.y() + el.h() - BTN_H - 3;
                drawScaleButtons(ctx, bMX, bY, mx, my, String.format("%.1f", el.scale()));
            }
        }

        // ── Instructions banner ───────────────────────────────────────────
        int bw = 440, bh = 28;
        int bx = (width - bw) / 2, by = height - 36;
        ctx.fill(bx, by, bx + bw, by + bh, Theme.GLASS_BG);
        gborder(ctx, bx, by, bw, bh, ACCENT);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "§d[L]§8/§dESC §7exit  §8•  §7Drag any box  §8•  §7Hover → §f[−] [+] §7to scale",
                width / 2, by + (bh - 7) / 2, TEXT_MID);

        // ── Hovered coord readout ─────────────────────────────────────────
        HudElement hovered = dragging;
        if (hovered == null) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                HudElement el = elements.get(i);
                if (isIn(mx, my, el.x(), el.y(), el.w(), el.h())) { hovered = el; break; }
            }
        }
        String coords = hovered != null
                ? "§7" + hovered.label() + "  §8X=§f" + hovered.x() + " §8Y=§f" + hovered.y()
                : "§8Hover an element to see its position";
        int ctw = textRenderer.getWidth(coords);
        ctx.fill(width / 2 - ctw / 2 - 6, 6, width / 2 + ctw / 2 + 6, 19, 0xAA07050F);
        ctx.drawTextWithShadow(textRenderer, coords, width / 2 - ctw / 2, 10, TEXT_DIM);

        super.render(ctx, mx, my, delta);
    }

    // ─────────────────────────────────────────────────────────────────────
    // KEYBOARD
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_L) {
            onClose(); return true;
        }
        return super.keyPressed(input);
    }

    public void onClose() {
        for (HudElement el : elements) el.save();
        AntigravityClient.larkDragModeActive = false;
        MinecraftClient.getInstance().setScreen(null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private boolean isIn(int px, int py, int bx, int by, int bw, int bh) {
        return px >= bx && px < bx + bw && py >= by && py < by + bh;
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private float clampScale(float s) { return Math.max(0.4f, Math.min(3.0f, Math.round(s * 10) / 10.0f)); }

    /** Draw a ghost box with corner dots and centred label. */
    private void drawGhostBox(DrawContext ctx, int x, int y, int w, int h,
                               int bg, int brd, String label, boolean dragging) {
        ctx.fill(x, y, x + w, y + h, bg);
        gborder(ctx, x, y, w, h, brd);
        ctx.fill(x + 2, y + 1, x + w - 2, y + 2, 0x18FFFFFF);

        int lw = textRenderer.getWidth(label);
        // Keep the label readable even when the box is small
        int ly = h >= 12 ? y + (h - 7) / 2 : y + 2;
        ctx.drawTextWithShadow(textRenderer, label,
                x + Math.max(2, (w - lw) / 2), ly, 0xFFFFFFFF);

        int ds = 4;
        int dot = dragging ? 0xFFCC88FF : (brd | 0xCC000000);
        ctx.fill(x + 2,     y + 2,     x + 2 + ds, y + 2 + ds, dot);
        ctx.fill(x + w - 6, y + 2,     x + w - 2,  y + 2 + ds, dot);
        ctx.fill(x + 2,     y + h - 6, x + 2 + ds, y + h - 2,  dot);
        ctx.fill(x + w - 6, y + h - 6, x + w - 2,  y + h - 2,  dot);
    }

    /** Draw [−] [+] scale buttons at (btnMX, btnY). */
    private void drawScaleButtons(DrawContext ctx, int btnMX, int btnY, int mx, int my, String scaleLabel) {
        int btnPX = btnMX + BTN_W + 2;

        boolean hovM = isIn(mx, my, btnMX, btnY, BTN_W, BTN_H);
        boolean hovP = isIn(mx, my, btnPX, btnY, BTN_W, BTN_H);

        ctx.fill(btnMX, btnY, btnMX + BTN_W, btnY + BTN_H,
                hovM ? 0xDDFF3333 : 0xBB882222);
        gborder(ctx, btnMX, btnY, BTN_W, BTN_H, hovM ? BTN_MINUS : 0xBBFF6666);
        int mw = textRenderer.getWidth("−");
        ctx.drawTextWithShadow(textRenderer, "−",
                btnMX + (BTN_W - mw) / 2, btnY + (BTN_H - 7) / 2, 0xFFFFDDDD);

        ctx.fill(btnPX, btnY, btnPX + BTN_W, btnY + BTN_H,
                hovP ? 0xDD33FF66 : 0xBB228833);
        gborder(ctx, btnPX, btnY, BTN_W, BTN_H, hovP ? BTN_PLUS : 0xBB66FF99);
        int pw = textRenderer.getWidth("+");
        ctx.drawTextWithShadow(textRenderer, "+",
                btnPX + (BTN_W - pw) / 2, btnY + (BTN_H - 7) / 2, 0xFFDDFFDD);

        int lw = textRenderer.getWidth(scaleLabel);
        ctx.drawTextWithShadow(textRenderer, "§7" + scaleLabel,
                btnMX + (2 * BTN_W + 2 - lw) / 2, btnY - 9, 0xFFCCCCCC);
    }

    private void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x,         y,         x + w, y + 1,     col);
        c.fill(x,         y + h - 1, x + w, y + h,     col);
        c.fill(x,         y,         x + 1, y + h,     col);
        c.fill(x + w - 1, y,         x + w, y + h,     col);
    }
}
