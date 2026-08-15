package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.cooldown.AbilityCooldownManager;
import org.cheetahv2.antigravity.client.cooldown.MoodSwingsHud;
import org.cheetahv2.antigravity.client.utility.AbilityKeybindModule;
import org.cheetahv2.antigravity.client.utility.AutoCookieModule;
import org.cheetahv2.antigravity.client.utility.AutoJackOfHeartsModule;
import org.cheetahv2.antigravity.client.utility.AutoSacredShieldModule;
import org.cheetahv2.antigravity.client.utility.AutoSellSoulModule;
import org.cheetahv2.antigravity.client.utility.AutoTotemModule;
import org.cheetahv2.antigravity.client.utility.AutoTropicalShieldModule;
import org.cheetahv2.antigravity.client.utility.BidWarModule;
import org.cheetahv2.antigravity.client.utility.UtilityModule;
import org.cheetahv2.antigravity.client.utility.UtilityModuleManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * CGC Settings Screen — /ccsettings
 *
 * Tabs: HUD | Cooldowns | Modules
 *  • HUD       — every HUD element toggleable + label scales + HUD editor button
 *  • Cooldowns — cooldown HUD layout/scale/position + per-ability toggles
 *  • Modules   — EVERY utility module, generically listed and toggleable,
 *                with per-module extra settings (incl. Sell Soul + Bid War)
 *
 * The panel scales with the window, content areas scroll (mouse wheel,
 * PgUp/PgDn, or the ▲/▼ buttons) and is clipped with a scissor so nothing
 * spills out of the card. All buttons are custom-drawn — GLFW-polled.
 */
public class AntigravityClient extends Screen {

    // ── Palette — unified Antigravity theme (see gui/Theme) ───────────────
    private static final int
        BG          = Theme.BG,
        PANEL       = Theme.PANEL,
        BORDER      = Theme.BORDER,
        BORDER_LIT  = Theme.BORDER_LIT,
        ACCENT      = Theme.ACCENT,
        ACCENT2     = Theme.ACCENT_ALT,
        TEXT_HI     = Theme.TEXT_HI,
        TEXT_MID    = Theme.TEXT_MID,
        TEXT_DIM    = Theme.TEXT_DIM,
        GREEN       = Theme.GOOD,
        GREEN_DIM   = Theme.GOOD_DIM,
        RED         = Theme.BAD,
        RED_DIM     = Theme.BAD_DIM,
        TAB_ACTIVE  = Theme.TAB_ACTIVE,
        TAB_IDLE    = 0x00000000;

    // ── Layout (responsive + GUI-scale aware) ─────────────────────────────
    private static final int TAB_H = 20;
    private static final int HDR_H = 26;
    private static final int FOOT_H = 16;

    /**
     * Auto-fit zoom so the card looks right at ANY Minecraft GUI Scale:
     * on a cramped scaled resolution (GUI scale 4-5) the whole menu shrinks
     * to fit; on a roomy one (GUI scale 1-2) it grows up to 1.25x. All layout
     * happens in "virtual" coordinates (vw × vh) which are then matrix-scaled.
     */
    private float ui = 1f;
    private int vw, vh;

    private void computeUiScale() {
        float fit = Math.min(width / 500f, height / 430f);
        ui = Math.max(0.6f, Math.min(1.25f, fit));
        vw = (int) (width  / ui);
        vh = (int) (height / ui);
    }

    private int pw() { return Math.min(480, (vw > 0 ? vw : width)  - 16); }
    private int ph() { return Math.min(400, (vh > 0 ? vh : height) - 16); }
    private int px() { return ((vw > 0 ? vw : width)  - pw()) / 2; }
    private int py() { return ((vh > 0 ? vh : height) - ph()) / 2; }
    private int contentTop()    { return py() + HDR_H + TAB_H + 2; }
    private int contentBottom() { return py() + ph() - FOOT_H - 2; }

    // ── Tabs ──────────────────────────────────────────────────────────────
    private int activeTab = 0;
    private static final String[] TABS = { "✦  HUD", "◎  Cooldowns", "⚙  Modules" };
    private final int[] scroll = new int[TABS.length];
    private final int[] contentHeight = new int[TABS.length];

    // ── GLFW mouse state ──────────────────────────────────────────────────
    private boolean prevLmb = false;
    private double  gx, gy;

    /** Non-null while waiting for the user to press a key for this ability bind. */
    private AbilityKeybindModule.Bind captureBind = null;

    // ── Custom button registry ────────────────────────────────────────────
    private static final class Btn {
        int x, y, w, h, tag;
        String label;
        Btn(int x, int y, int w, int h, int tag, String label) {
            this.x=x; this.y=y; this.w=w; this.h=h; this.tag=tag; this.label=label;
        }
    }
    private final List<Btn> buttons = new ArrayList<>();

    // ── Button tags ───────────────────────────────────────────────────────
    // Chrome (always clickable): 0-19
    private static final int
        BTN_CLOSE        = 0,
        BTN_SCROLL_UP    = 5,
        BTN_SCROLL_DOWN  = 6,
        BTN_TAB_0        = 10; // +1, +2 for the other tabs

    // HUD tab: 20-49
    private static final int
        BTN_STATUS_TOGGLE    = 20,
        BTN_CRATE_TOGGLE     = 21,
        BTN_EVENT_HUD_TOGGLE = 22,
        BTN_RUNE_HUD_TOGGLE  = 23,
        BTN_MOOD_HUD_TOGGLE  = 24,
        BTN_BEAMS_TOGGLE     = 25,
        BTN_FROZEN_TOGGLE    = 26,
        BTN_NOTIF_TOGGLE     = 27,
        BTN_LARK_ALERTS      = 28,
        BTN_OPEN_HUD_EDITOR  = 29,
        BTN_LARK_SCALE_DN    = 30,
        BTN_LARK_SCALE_UP    = 31,
        BTN_LARK_HEIGHT_DN   = 32,
        BTN_LARK_HEIGHT_UP   = 33,
        BTN_GLOW_SCALE_DN    = 34,
        BTN_GLOW_SCALE_UP    = 35,
        BTN_MOOD_DUR_DN      = 36,
        BTN_MOOD_DUR_UP      = 37;

    // Cooldowns tab: 50-99, ability toggles 100-999
    private static final int
        BTN_SCALE_DN     = 50,
        BTN_SCALE_UP     = 51,
        BTN_LAYOUT       = 52,
        BTN_RESET_POS    = 53,
        BTN_NUDGE_L      = 54,
        BTN_NUDGE_R      = 55,
        BTN_NUDGE_U      = 56,
        BTN_NUDGE_D      = 57,
        BTN_ABILITY_BASE = 100;

    // Modules tab: 1000 + moduleIndex*50 + sub
    private static final int BTN_MODULE_BASE   = 1000;
    private static final int MODULE_TAG_STRIDE = 50;

    public AntigravityClient() {
        super(Text.literal("CGC Settings"));
    }

    @Override public boolean shouldPause() { return false; }
    @Override protected void init() {}

    // ─────────────────────────────────────────────────────────────────────
    // DRAW HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private static void fill(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + h, col);
    }

    private static void border(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x,     y,       x+w, y+1,   col);
        c.fill(x,     y+h-1,   x+w, y+h,   col);
        c.fill(x,     y+1,     x+1, y+h-1, col);
        c.fill(x+w-1, y+1,     x+w, y+h-1, col);
    }

    private static void sheen(DrawContext c, int x, int y, int w) {
        c.fill(x+2, y+1, x+w-2, y+2, 0x18FFFFFF);
    }

    private boolean drawBtn(DrawContext ctx, Btn b, boolean pressed) {
        boolean hov = gx >= b.x && gx < b.x+b.w && gy >= b.y && gy < b.y+b.h;
        int bg  = pressed ? Theme.BTN_BG_DOWN : hov ? Theme.BTN_BG_HOV : Theme.BTN_BG;
        int brd = pressed ? BORDER_LIT  : hov ? ACCENT      : BORDER;
        Theme.pill(ctx, b.x, b.y, b.w, b.h, bg, brd);
        if (!b.label.isEmpty()) {
            int tw = textRenderer.getWidth(b.label);
            ctx.drawTextWithShadow(textRenderer, b.label,
                    b.x + (b.w - tw) / 2, b.y + (b.h - 7) / 2, hov ? TEXT_HI : TEXT_MID);
        }
        return hov;
    }

    private void drawToggleBtn(DrawContext ctx, Btn b, boolean on) {
        boolean hov = gx >= b.x && gx < b.x+b.w && gy >= b.y && gy < b.y+b.h;
        int bg  = on  ? (hov ? 0x80206030 : GREEN_DIM)
                      : (hov ? 0x80602020 : RED_DIM);
        int brd = on  ? (hov ? 0xFF55EE88 : GREEN) : (hov ? 0xFFEE5555 : RED);
        Theme.pill(ctx, b.x, b.y, b.w, b.h, bg, brd);
        String lbl = on ? "§a✦ ON" : "§cOFF";
        int tw = textRenderer.getWidth(lbl);
        ctx.drawTextWithShadow(textRenderer, lbl, b.x + (b.w - tw)/2, b.y + (b.h - 7)/2, 0xFFFFFFFF);
    }

    private void drawStepBtn(DrawContext ctx, Btn b, boolean isPlus) {
        boolean hov = gx >= b.x && gx < b.x+b.w && gy >= b.y && gy < b.y+b.h;
        int col = isPlus ? (hov ? 0xFF55EE88 : 0xFF33AA55) : (hov ? 0xFFEE6655 : 0xFFAA3333);
        Theme.pill(ctx, b.x, b.y, b.w, b.h, hov ? 0x40FFFFFF : 0x20FFFFFF, col);
        String sym = isPlus ? "+" : "−";
        int tw = textRenderer.getWidth(sym);
        ctx.drawTextWithShadow(textRenderer, sym, b.x+(b.w-tw)/2, b.y+(b.h-7)/2, col);
    }

    private void sectionLabel(DrawContext ctx, String text, int x, int y) {
        fill(ctx, x, y, pw() - 20, 1, 0x30AABBDD);
        ctx.drawTextWithShadow(textRenderer, text, x + 2, y - 8, ACCENT);
    }

    /** Label + toggle button on one row. */
    private void drawRow(DrawContext ctx, int x, int y, String label, int tag, boolean on) {
        ctx.drawTextWithShadow(textRenderer, label, x, y + 4, TEXT_MID);
        Btn btn = new Btn(px() + pw() - 80, y, 62, 16, tag, "");
        drawToggleBtn(ctx, btn, on);
        buttons.add(btn);
    }

    /** Label + value badge + two stepper buttons on one row. */
    private void drawStepper(DrawContext ctx, int x, int y,
                              String label, String value, int tagDn, int tagUp) {
        ctx.drawTextWithShadow(textRenderer, label, x, y + 3, TEXT_MID);
        int vx = x + 148;
        int badgeW = textRenderer.getWidth(value) + 10;
        Theme.pill(ctx, vx, y - 1, badgeW, 15, PANEL, BORDER);
        ctx.drawTextWithShadow(textRenderer, value, vx + 5, y + 3, ACCENT);

        Btn dn = new Btn(vx + Math.max(badgeW + 6, 66),      y, 18, 14, tagDn, "");
        Btn up = new Btn(vx + Math.max(badgeW + 6, 66) + 22, y, 18, 14, tagUp, "");
        drawStepBtn(ctx, dn, false);
        drawStepBtn(ctx, up, true);
        buttons.add(dn);
        buttons.add(up);
    }

    // ─────────────────────────────────────────────────────────────────────
    // RENDER
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        computeUiScale();
        pollGlfw();
        buttons.clear();

        int x = px(), y = py(), w = pw(), h = ph();

        var ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.scale(ui, ui);

        // ── Backdrop + rounded card ───────────────────────────────────────
        fill(ctx, 0, 0, vw, vh, Theme.BG_DIM);
        Theme.pill(ctx, x, y, w, h, BG, BORDER);
        fill(ctx, x+2, y, w-4, 2, ACCENT);          // top accent stripe
        fill(ctx, x+2, y+2, w-4, 1, 0x30C9B8FF);
        fill(ctx, x+2, y+h-3, w-4, 2, Theme.SHADOW);

        // ── Starfield garnish (behind all content) ────────────────────────
        Theme.stars(ctx, textRenderer, x + 4, y + HDR_H, w - 8, h - HDR_H - FOOT_H, 46);

        // ── Header ────────────────────────────────────────────────────────
        fill(ctx, x+2, y+3, w-4, HDR_H-3, 0x28160D2E);
        ctx.drawTextWithShadow(textRenderer, Theme.WORDMARK, x+10, y+9, TEXT_HI);
        ctx.drawTextWithShadow(textRenderer, "§8│ §7Settings",
                x + 14 + textRenderer.getWidth(Theme.WORDMARK), y+9, TEXT_MID);
        Btn close = new Btn(x+w-20, y+7, 13, 13, BTN_CLOSE, "×");
        drawBtn(ctx, close, false);
        buttons.add(close);

        // ── Tabs ──────────────────────────────────────────────────────────
        int tabY = y + HDR_H;
        int tabW = w / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            boolean active = i == activeTab;
            int tx = x + i * tabW;
            fill(ctx, tx, tabY, tabW, TAB_H, active ? TAB_ACTIVE : TAB_IDLE);
            if (active) {
                fill(ctx, tx, tabY, tabW, 1, ACCENT);
                fill(ctx, tx, tabY+TAB_H-1, tabW, 1, BG);
            } else {
                fill(ctx, tx, tabY+TAB_H-1, tabW, 1, BORDER);
            }
            boolean hovTab = gx >= tx && gx < tx+tabW && gy >= tabY && gy < tabY+TAB_H;
            int tw = textRenderer.getWidth(TABS[i]);
            ctx.drawTextWithShadow(textRenderer, TABS[i],
                    tx + (tabW - tw)/2, tabY + (TAB_H - 7)/2,
                    active ? ACCENT : (hovTab ? TEXT_MID : TEXT_DIM));
            buttons.add(new Btn(tx, tabY, tabW, TAB_H, BTN_TAB_0 + i, ""));
        }
        border(ctx, x, tabY, w, TAB_H, 0x20AABBDD);

        // ── Scrollable content (scissored) ────────────────────────────────
        int viewTop = contentTop(), viewBottom = contentBottom();
        int cy = viewTop + 8 - scroll[activeTab];

        // Scissor rect is transformed by the current pose (which already has
        // the ui zoom applied), so pass plain virtual coordinates here.
        ctx.enableScissor(x + 1, viewTop, x + w - 1, viewBottom);
        int endY;
        if (activeTab == 0)      endY = renderHudTab(ctx, x, cy);
        else if (activeTab == 1) endY = renderCooldownsTab(ctx, x, cy);
        else                     endY = renderModulesTab(ctx, x, cy);
        ctx.disableScissor();

        contentHeight[activeTab] = (endY - cy) + 12;
        clampScroll();

        // ── Scrollbar + scroll buttons (only when needed) ─────────────────
        int viewH = viewBottom - viewTop;
        if (contentHeight[activeTab] > viewH) {
            int trackX = x + w - 8;
            fill(ctx, trackX, viewTop, 3, viewH, Theme.SCROLL_TRACK);
            int thumbH = Math.max(14, viewH * viewH / contentHeight[activeTab]);
            int maxScroll = contentHeight[activeTab] - viewH;
            int thumbY = viewTop + (viewH - thumbH) * scroll[activeTab] / Math.max(1, maxScroll);
            fill(ctx, trackX, thumbY, 3, thumbH, Theme.SCROLL_THUMB);

            Btn up = new Btn(x + w - 22, viewTop + 2, 12, 12, BTN_SCROLL_UP, "▲");
            Btn dn = new Btn(x + w - 22, viewBottom - 14, 12, 12, BTN_SCROLL_DOWN, "▼");
            drawBtn(ctx, up, false); buttons.add(up);
            drawBtn(ctx, dn, false); buttons.add(dn);
        }

        // ── Footer ────────────────────────────────────────────────────────
        fill(ctx, x+2, y+h-FOOT_H, w-4, FOOT_H-2, 0x20000000);
        fill(ctx, x+2, y+h-FOOT_H, w-4, 1, 0x208D77E8);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "§8✦ ESC to close  •  Scroll to see more  •  Changes save automatically",
                x + w/2, y + h - 11, TEXT_DIM);

        ms.popMatrix();
    }

    // ─────────────────────────────────────────────────────────────────────
    // TAB 0 — HUD
    // ─────────────────────────────────────────────────────────────────────
    private int renderHudTab(DrawContext ctx, int x, int cy) {
        org.cheetahv2.antigravity.client.AntigravityClient.HudSettings hs =
                org.cheetahv2.antigravity.client.AntigravityClient.HUD_SETTINGS;
        var esm  = org.cheetahv2.antigravity.client.AntigravityClient.EVENT_SCHEDULE;
        var rah  = org.cheetahv2.antigravity.client.AntigravityClient.RUNE_AVAIL_HUD;
        var mood = org.cheetahv2.antigravity.client.AntigravityClient.MOOD_HUD;
        var fpt  = org.cheetahv2.antigravity.client.AntigravityClient.FROZEN_TRACKER;
        var lark = org.cheetahv2.antigravity.client.AntigravityClient.LARK_MANAGER;

        // ── HUD editor shortcut ───────────────────────────────────────────
        Btn editor = new Btn(x + 10, cy, pw() - 20, 20, BTN_OPEN_HUD_EDITOR,
                "§b✥ Open HUD Editor §8— drag & scale every element");
        drawBtn(ctx, editor, false);
        buttons.add(editor);
        cy += 30;

        // ── Visibility toggles ────────────────────────────────────────────
        sectionLabel(ctx, "VISIBILITY", x + 10, cy);
        cy += 10;

        drawRow(ctx, x + 10, cy, "Status Bar HUD (cooldowns, runes, lark)", BTN_STATUS_TOGGLE, hs.showStatusBar);           cy += 20;
        drawRow(ctx, x + 10, cy, "Rune Availability panel",                 BTN_RUNE_HUD_TOGGLE, rah.isEnabled());          cy += 20;
        drawRow(ctx, x + 10, cy, "Mood Swings HUD (above hotbar)",          BTN_MOOD_HUD_TOGGLE, mood.isEnabled());         cy += 20;
        drawRow(ctx, x + 10, cy, "Event Schedule HUD",                      BTN_EVENT_HUD_TOGGLE, esm.isHudEnabled());      cy += 20;
        drawRow(ctx, x + 10, cy, "Outpost Core sky beams",                  BTN_BEAMS_TOGGLE, esm.getConfig().showCoreBeams); cy += 20;
        drawRow(ctx, x + 10, cy, "Frozen player list",                      BTN_FROZEN_TOGGLE, fpt.getConfig().showHudList); cy += 20;
        drawRow(ctx, x + 10, cy, "Crate Puller tooltip overlay",            BTN_CRATE_TOGGLE, hs.showCratePuller);          cy += 20;
        drawRow(ctx, x + 10, cy, "Toast notifications",                     BTN_NOTIF_TOGGLE, hs.notificationsEnabled);     cy += 20;
        drawRow(ctx, x + 10, cy, "Lark alerts + head labels",               BTN_LARK_ALERTS, lark.alertEnabled);            cy += 26;

        // ── Scales ────────────────────────────────────────────────────────
        sectionLabel(ctx, "LABEL SCALES", x + 10, cy);
        cy += 12;

        drawStepper(ctx, x + 10, cy, "Lark Label Scale",
                String.format("%.1f", hs.larkLabelScale), BTN_LARK_SCALE_DN, BTN_LARK_SCALE_UP);
        cy += 20;
        drawStepper(ctx, x + 10, cy, "Lark Height Offset",
                String.format("%.2f", hs.labelHeightOffset), BTN_LARK_HEIGHT_DN, BTN_LARK_HEIGHT_UP);
        cy += 20;
        drawStepper(ctx, x + 10, cy, "Glow Label Scale",
                String.format("%.1f", hs.labelScale), BTN_GLOW_SCALE_DN, BTN_GLOW_SCALE_UP);
        cy += 26;

        // ── Mood Swings ───────────────────────────────────────────────────
        sectionLabel(ctx, "MOOD SWINGS", x + 10, cy);
        cy += 12;
        ctx.drawTextWithShadow(textRenderer,
                "§8↳ Moods cycle every §f10s §8(fixed). Test the HUD: §f/cgcmood happy",
                x + 10, cy, TEXT_DIM);
        cy += 16;

        ctx.drawTextWithShadow(textRenderer,
                "§8✦ Positions & sizes of all HUD elements live in the HUD Editor ([L] in-game).",
                x + 10, cy, TEXT_DIM);
        cy += 14;
        return cy;
    }

    // ─────────────────────────────────────────────────────────────────────
    // TAB 1 — COOLDOWNS
    // ─────────────────────────────────────────────────────────────────────
    private int renderCooldownsTab(DrawContext ctx, int x, int cy) {
        AbilityCooldownManager acm =
                org.cheetahv2.antigravity.client.AntigravityClient.ABILITY_COOLDOWN;
        AbilityCooldownManager.SavedConfig cfg = acm.getConfig();

        sectionLabel(ctx, "HUD DISPLAY", x + 10, cy);
        cy += 12;

        drawStepper(ctx, x + 10, cy, "HUD Scale",
                String.format("%.1f", cfg.scale), BTN_SCALE_DN, BTN_SCALE_UP);

        String layoutLabel = cfg.layoutMode == AbilityCooldownManager.LayoutMode.CONSOLIDATED
                ? "§bConsolidated" : "§eIndividual";
        Btn btnLayout = new Btn(x + pw() - 120, cy - 3, 102, 16, BTN_LAYOUT, layoutLabel);
        drawBtn(ctx, btnLayout, false);
        buttons.add(btnLayout);
        cy += 24;

        sectionLabel(ctx, "HUD POSITION", x + 10, cy);
        cy += 12;

        Theme.pill(ctx, x + 10, cy - 2, 130, 16, PANEL, BORDER);
        ctx.drawTextWithShadow(textRenderer,
                "§8X=§f" + cfg.consolidatedX + "  §8Y=§f" + cfg.consolidatedY,
                x + 16, cy + 2, TEXT_MID);

        Btn btnReset = new Btn(x + 148, cy - 2, 60, 16, BTN_RESET_POS, "Reset");
        drawBtn(ctx, btnReset, false);
        buttons.add(btnReset);

        int dp = x + 216;
        Btn l = new Btn(dp,    cy - 2, 20, 16, BTN_NUDGE_L, "◄");
        Btn r = new Btn(dp+22, cy - 2, 20, 16, BTN_NUDGE_R, "►");
        Btn u = new Btn(dp+44, cy - 2, 20, 16, BTN_NUDGE_U, "▲");
        Btn d = new Btn(dp+66, cy - 2, 20, 16, BTN_NUDGE_D, "▼");
        drawBtn(ctx, l, false); buttons.add(l);
        drawBtn(ctx, r, false); buttons.add(r);
        drawBtn(ctx, u, false); buttons.add(u);
        drawBtn(ctx, d, false); buttons.add(d);
        cy += 24;

        ctx.drawTextWithShadow(textRenderer,
                "§8✦ Or drag the box in the HUD Editor / Shift+LMB in-game.",
                x + 10, cy, TEXT_DIM);
        cy += 18;

        // ── Per-ability toggles ──────────────────────────────────────────
        sectionLabel(ctx, "TRACKED ABILITIES", x + 10, cy);
        cy += 12;

        List<AbilityCooldownManager.AbilityDef> defs = AbilityCooldownManager.getDefs();
        if (defs.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, "§8No custom abilities defined.", x + 10, cy, TEXT_DIM);
            cy += 14;
        }
        for (int i = 0; i < defs.size(); i++) {
            AbilityCooldownManager.AbilityDef def = defs.get(i);
            AbilityCooldownManager.AbilitySetting s =
                    cfg.settings.computeIfAbsent(def.id, k -> new AbilityCooldownManager.AbilitySetting());

            Theme.pill(ctx, x + 8, cy - 2, pw() - 16, 18,
                    s.enabled ? 0x30103020 : 0x20160D2E,
                    s.enabled ? 0x6057E8A9 : BORDER);
            ctx.drawTextWithShadow(textRenderer, def.iconChar, x + 14, cy + 2, def.iconColor);
            ctx.drawTextWithShadow(textRenderer,
                    (s.enabled ? "§f" : "§8") + def.name
                            + " §8(" + AbilityCooldownManager.fmt(def.defaultDurationMs) + ")",
                    x + 26, cy + 2, TEXT_HI);

            Btn toggle = new Btn(x + pw() - 80, cy - 1, 62, 16, BTN_ABILITY_BASE + i, "");
            drawToggleBtn(ctx, toggle, s.enabled);
            buttons.add(toggle);
            cy += 22;
        }
        return cy;
    }

    // ─────────────────────────────────────────────────────────────────────
    // TAB 2 — MODULES (generic — every module gets a row automatically)
    // ─────────────────────────────────────────────────────────────────────
    private int renderModulesTab(DrawContext ctx, int x, int cy) {
        UtilityModuleManager mgr = org.cheetahv2.antigravity.client.AntigravityClient.UTILITY_MODULES;

        ctx.drawTextWithShadow(textRenderer,
                "§8✔ Automation & alert modules. Toggle each independently.",
                x + 10, cy, TEXT_DIM);
        cy += 14;

        for (int i = 0; i < mgr.modules.size(); i++) {
            UtilityModule m = mgr.modules.get(i);
            cy = renderModuleHeader(ctx, x, cy, i, m);
            if (m.isEnabled()) {
                cy = renderModuleExtras(ctx, x, cy, i, m);
            }
            cy += 4;
        }
        return cy;
    }

    /** Module header row: pill, name, description, ON/OFF toggle. */
    private int renderModuleHeader(DrawContext ctx, int x, int cy, int moduleIndex, UtilityModule module) {
        boolean on = module.isEnabled();

        int pillBg  = on ? 0x40103020 : 0x20160D2E;
        int pillBrd = on ? GREEN      : BORDER;
        Theme.pill(ctx, x + 8, cy - 2, pw() - 16, 18, pillBg, pillBrd);

        ctx.drawTextWithShadow(textRenderer,
                (on ? "§a✦ " : "§8✧ ") + (on ? "§a" : "§7") + module.getName(),
                x + 14, cy + 2, TEXT_HI);

        int descX = x + 14 + textRenderer.getWidth(module.getName()) + 10;
        String desc = module.getDescription();
        int maxDescW = pw() - 110 - (descX - x);
        while (textRenderer.getWidth(desc) > maxDescW && desc.length() > 4)
            desc = desc.substring(0, desc.length() - 1);
        ctx.drawTextWithShadow(textRenderer, "§8— " + desc, descX, cy + 2, TEXT_DIM);

        Btn toggleBtn = new Btn(x + pw() - 80, cy - 1, 62, 16,
                BTN_MODULE_BASE + moduleIndex * MODULE_TAG_STRIDE, "");
        drawToggleBtn(ctx, toggleBtn, on);
        buttons.add(toggleBtn);

        return cy + 20;
    }

    /** Module-specific extra settings shown while the module is enabled. */
    private int renderModuleExtras(DrawContext ctx, int x, int cy, int idx, UtilityModule m) {
        int base = BTN_MODULE_BASE + idx * MODULE_TAG_STRIDE;

        if (m instanceof AutoCookieModule cookie) {
            AutoCookieModule.Config cc = cookie.getConfig();
            drawStepper(ctx, x + 18, cy, "Trigger Delay", cc.triggerDelayMs + " ms", base + 1, base + 2);
            cy += 20;
            drawStepper(ctx, x + 18, cy, "Cookie Cooldown", (cc.cookieCooldownMs / 1000.0f) + "s", base + 3, base + 4);
            cy += 20;
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Searches inventory for Santa's Cookie → slot 8", x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof AutoJackOfHeartsModule jack) {
            AutoJackOfHeartsModule.Config jc = jack.getConfig();
            drawStepper(ctx, x + 18, cy, "Trigger Delay", jc.triggerDelayMs + " ms", base + 1, base + 2);
            cy += 20;
            drawStepper(ctx, x + 18, cy, "Use Cooldown", (jc.useCooldownMs / 1000.0f) + "s", base + 3, base + 4);
            cy += 20;
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Uses Jack of Hearts (paper) when runiced", x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof AutoSacredShieldModule sacred) {
            AutoSacredShieldModule.Config sc = sacred.getConfig();
            drawStepper(ctx, x + 18, cy, "Equip Below",
                    String.format("%.0f HP (%.1f❤)", sc.triggerHealth, sc.triggerHealth / 2f),
                    base + 1, base + 2);
            cy += 20;
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Needs: item named \"Sacred\" with Divine Intervention lore", x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof AutoTropicalShieldModule tropical) {
            AutoTropicalShieldModule.Config tc = tropical.getConfig();
            drawStepper(ctx, x + 18, cy, "Equip Below",
                    String.format("%.0f HP (%.1f❤)", tc.triggerHealth, tc.triggerHealth / 2f),
                    base + 1, base + 2);
            cy += 20;
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Needs: item named \"Tropical\" anywhere in inventory", x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof AutoTotemModule totem) {
            AutoTotemModule.Config tc = totem.getConfig();
            ctx.drawTextWithShadow(textRenderer, "Mode", x + 18, cy + 3, TEXT_MID);
            Btn mode = new Btn(x + 166, cy - 1, 96, 15, base + 1,
                    tc.mode == AutoTotemModule.Mode.ALWAYS ? "§bAlways" : "§eSmart");
            drawBtn(ctx, mode, false);
            buttons.add(mode);
            cy += 20;
            if (tc.mode == AutoTotemModule.Mode.SMART) {
                drawStepper(ctx, x + 18, cy, "Equip Below",
                        String.format("%.0f HP (%.1f❤)", tc.triggerHealth, tc.triggerHealth / 2f),
                        base + 2, base + 3);
                cy += 20;
            }
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Keeps Totem of Undying in offhand. Re-equips after use.", x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof AbilityKeybindModule keys) {
            AbilityKeybindModule.Config kc = keys.getConfig();
            drawStepper(ctx, x + 18, cy, "Step Delay", kc.actionDelayMs + " ms", base + 2, base + 3);
            cy += 20;
            drawStepper(ctx, x + 18, cy, "Menu Confirm Delay", kc.menuConfirmDelayMs + " ms", base + 4, base + 5);
            cy += 20;

            ctx.drawTextWithShadow(textRenderer,
                    "§7Binds §8(key press → open abilities menu → pick slot):", x + 18, cy, TEXT_MID);
            cy += 13;

            for (int k = 0; k < kc.binds.size(); k++) {
                AbilityKeybindModule.Bind b = kc.binds.get(k);
                boolean capturing = captureBind == b;

                Btn keyBtn = new Btn(x + 24, cy - 2, 100, 15, base + 10 + k * 4,
                        capturing ? "§e⌨ Press a key…" : "§f⌨ " + b.keyName);
                drawBtn(ctx, keyBtn, capturing);
                buttons.add(keyBtn);

                ctx.drawTextWithShadow(textRenderer, "§7→ Slot §d" + b.menuSlot, x + 132, cy + 1, TEXT_MID);
                Btn dn = new Btn(x + 186, cy - 2, 16, 14, base + 11 + k * 4, "");
                Btn up = new Btn(x + 206, cy - 2, 16, 14, base + 12 + k * 4, "");
                drawStepBtn(ctx, dn, false);
                drawStepBtn(ctx, up, true);
                buttons.add(dn);
                buttons.add(up);

                Btn rm = new Btn(x + pw() - 50, cy - 2, 16, 14, base + 13 + k * 4, "§c×");
                drawBtn(ctx, rm, false);
                buttons.add(rm);
                cy += 18;
            }

            if (kc.binds.size() < AbilityKeybindModule.MAX_BINDS) {
                Btn add = new Btn(x + 24, cy - 1, 110, 15, base + 1, "§a+ Add Bind");
                drawBtn(ctx, add, false);
                buttons.add(add);
                cy += 19;
            }
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Click a key button to rebind — ANY keyboard key works. ESC cancels.",
                    x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof org.cheetahv2.antigravity.client.utility.AbstractEquipMenuModule equip) {
            // Shared UI for all equip-menu modules (Sell Soul legs, Tiki chest, ...)
            var sc = equip.getConfig();
            drawStepper(ctx, x + 18, cy, "Min Level", "L" + sc.minLevel, base + 1, base + 2);
            cy += 20;
            drawStepper(ctx, x + 18, cy, "Max Level", "L" + sc.maxLevel, base + 3, base + 4);
            cy += 20;
            drawStepper(ctx, x + 18, cy, "Step Delay", sc.actionDelayMs + " ms", base + 5, base + 6);
            cy += 20;
            drawStepper(ctx, x + 18, cy, "Menu Confirm Delay", sc.menuConfirmDelayMs + " ms", base + 7, base + 8);
            cy += 20;
            ctx.drawTextWithShadow(textRenderer,
                    "§8⤷ Bind its key in Controls. Equips piece, F-F-1 menu, restores slots.",
                    x + 18, cy, TEXT_DIM);
            cy += 14;

        } else if (m instanceof BidWarModule bidwar) {
            BidWarModule.Config bc = bidwar.getConfig();
            drawRow(ctx, x + 18, cy, "Alert on EVERY bid war", base + 1, bc.notifyAll);  cy += 20;
            drawRow(ctx, x + 18, cy, "Play sound",             base + 2, bc.playSound);  cy += 20;
            drawRow(ctx, x + 18, cy, "Show title banner",      base + 3, bc.showTitle);  cy += 20;

            ctx.drawTextWithShadow(textRenderer,
                    "§7Watched items §8(add with §f/bidwatch add <name>§8):", x + 18, cy, TEXT_MID);
            cy += 12;
            if (bc.watchedItems.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, "§8(none — watching nothing"
                        + (bc.notifyAll ? ", but Alert-on-every is ON)" : ")"), x + 24, cy, TEXT_DIM);
                cy += 14;
            }
            for (int k = 0; k < bc.watchedItems.size(); k++) {
                String item = bc.watchedItems.get(k);
                Theme.pill(ctx, x + 24, cy - 2, pw() - 60, 14, 0x25160D2E, BORDER);
                ctx.drawTextWithShadow(textRenderer, "§e⚑ §f" + item, x + 30, cy + 1, TEXT_HI);
                Btn rm = new Btn(x + pw() - 50, cy - 2, 16, 14, base + 10 + k, "§c×");
                drawBtn(ctx, rm, false);
                buttons.add(rm);
                cy += 17;
            }
        }
        return cy;
    }

/    // ─────────────────────────────────────────────────────────────────────
    // INPUT — GLFW polling + scroll
    // ─────────────────────────────────────────────────────────────────────
    private void pollGlfw() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return;

        long win = mc.getWindow().getHandle();
        double sf = mc.getWindow().getScaleFactor();
        double[] rx = new double[1], ry = new double[1];
        GLFW.glfwGetCursorPos(win, rx, ry);
        // Cursor into virtual (ui-zoomed) coordinates — matches button layout
        gx = rx[0] / sf / ui;
        gy = ry[0] / sf / ui;
        boolean lmb = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (lmb && !prevLmb) {
            for (Btn b : buttons) {
                if (gx >= b.x && gx < b.x+b.w && gy >= b.y && gy < b.y+b.h) {
                    // Content buttons only count when the cursor is inside the
                    // scissored content area (chrome tags < 20 are always live).
                    boolean chrome = b.tag < 20;
                    if (!chrome && (gy < contentTop() || gy >= contentBottom())) continue;
                    handleClick(b.tag);
                    break;
                }
            }
        }

        prevLmb = lmb;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll[activeTab] -= (int)(verticalAmount * 18);
        clampScroll();
        return true;
    }

    private void clampScroll() {
        int viewH = contentBottom() - contentTop();
        int max = Math.max(0, contentHeight[activeTab] - viewH);
        scroll[activeTab] = Math.max(0, Math.min(max, scroll[activeTab]));
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLICK HANDLING
    // ─────────────────────────────────────────────────────────────────────
    private void handleClick(int tag) {
        org.cheetahv2.antigravity.client.AntigravityClient.HudSettings hs =
                org.cheetahv2.antigravity.client.AntigravityClient.HUD_SETTINGS;
        AbilityCooldownManager acm =
                org.cheetahv2.antigravity.client.AntigravityClient.ABILITY_COOLDOWN;
        AbilityCooldownManager.SavedConfig cfg = acm.getConfig();
        var esm  = org.cheetahv2.antigravity.client.AntigravityClient.EVENT_SCHEDULE;
        var rah  = org.cheetahv2.antigravity.client.AntigravityClient.RUNE_AVAIL_HUD;
        MoodSwingsHud mood = org.cheetahv2.antigravity.client.AntigravityClient.MOOD_HUD;
        var fpt  = org.cheetahv2.antigravity.client.AntigravityClient.FROZEN_TRACKER;
        var lark = org.cheetahv2.antigravity.client.AntigravityClient.LARK_MANAGER;

        // ── Modules tab (dynamic tags) ────────────────────────────────────
        if (tag >= BTN_MODULE_BASE) {
            handleModuleClick(tag);
            return;
        }

        // ── Ability toggles ───────────────────────────────────────────────
        if (tag >= BTN_ABILITY_BASE) {
            int idx = tag - BTN_ABILITY_BASE;
            List<AbilityCooldownManager.AbilityDef> defs = AbilityCooldownManager.getDefs();
            if (idx < defs.size()) {
                AbilityCooldownManager.AbilitySetting s =
                        cfg.settings.computeIfAbsent(defs.get(idx).id,
                                k -> new AbilityCooldownManager.AbilitySetting());
                s.enabled ^= true;
                acm.save();
            }
            return;
        }

        switch (tag) {
            case BTN_CLOSE       -> onClose();
            case BTN_SCROLL_UP   -> { scroll[activeTab] -= 40; clampScroll(); }
            case BTN_SCROLL_DOWN -> { scroll[activeTab] += 40; clampScroll(); }
            case BTN_TAB_0       -> activeTab = 0;
            case BTN_TAB_0 + 1   -> activeTab = 1;
            case BTN_TAB_0 + 2   -> activeTab = 2;

            // HUD tab
            case BTN_STATUS_TOGGLE    -> { hs.showStatusBar ^= true; hs.save(); }
            case BTN_CRATE_TOGGLE     -> { hs.showCratePuller ^= true; hs.save(); }
            case BTN_NOTIF_TOGGLE     -> { hs.notificationsEnabled ^= true; hs.save(); }
            case BTN_EVENT_HUD_TOGGLE -> esm.setHudEnabled(!esm.isHudEnabled());
            case BTN_RUNE_HUD_TOGGLE  -> rah.setEnabled(!rah.isEnabled());
            case BTN_MOOD_HUD_TOGGLE  -> mood.setEnabled(!mood.isEnabled());
            case BTN_BEAMS_TOGGLE     -> { esm.getConfig().showCoreBeams ^= true; esm.save(); }
            case BTN_FROZEN_TOGGLE    -> { fpt.getConfig().showHudList ^= true; fpt.save(); }
            case BTN_LARK_ALERTS      -> { lark.alertEnabled ^= true; lark.save(); }
            case BTN_OPEN_HUD_EDITOR  -> {
                org.cheetahv2.antigravity.client.AntigravityClient.larkDragModeActive = true;
                MinecraftClient.getInstance().setScreen(new HudDragScreen());
            }
            case BTN_LARK_SCALE_DN  -> { hs.larkLabelScale    = Math.max(0.5f, hs.larkLabelScale    - 0.5f);  hs.save(); }
            case BTN_LARK_SCALE_UP  -> { hs.larkLabelScale    = Math.min(8.0f, hs.larkLabelScale    + 0.5f);  hs.save(); }
            case BTN_LARK_HEIGHT_DN -> { hs.labelHeightOffset = Math.max(-2f,  hs.labelHeightOffset - 0.25f); hs.save(); }
            case BTN_LARK_HEIGHT_UP -> { hs.labelHeightOffset = Math.min(4f,   hs.labelHeightOffset + 0.25f); hs.save(); }
            case BTN_GLOW_SCALE_DN  -> { hs.labelScale        = Math.max(0.5f, hs.labelScale        - 0.5f);  hs.save(); }
            case BTN_GLOW_SCALE_UP  -> { hs.labelScale        = Math.min(8.0f, hs.labelScale        + 0.5f);  hs.save(); }
            case BTN_MOOD_DUR_DN -> {
                mood.getConfig().defaultDurationMs = Math.max(5_000, mood.getConfig().defaultDurationMs - 5_000);
                mood.save();
            }
            case BTN_MOOD_DUR_UP -> {
                mood.getConfig().defaultDurationMs = Math.min(300_000, mood.getConfig().defaultDurationMs + 5_000);
                mood.save();
            }

            // Cooldowns tab
            case BTN_SCALE_DN  -> { cfg.scale = Math.max(0.4f, cfg.scale - 0.1f); acm.save(); }
            case BTN_SCALE_UP  -> { cfg.scale = Math.min(3.0f, cfg.scale + 0.1f); acm.save(); }
            case BTN_LAYOUT    -> {
                cfg.layoutMode = cfg.layoutMode == AbilityCooldownManager.LayoutMode.CONSOLIDATED
                        ? AbilityCooldownManager.LayoutMode.INDIVIDUAL
                        : AbilityCooldownManager.LayoutMode.CONSOLIDATED;
                acm.save();
            }
            case BTN_RESET_POS -> { cfg.consolidatedX = 10; cfg.consolidatedY = 50; acm.save(); }
            case BTN_NUDGE_L   -> { cfg.consolidatedX -= 5; acm.save(); }
            case BTN_NUDGE_R   -> { cfg.consolidatedX += 5; acm.save(); }
            case BTN_NUDGE_U   -> { cfg.consolidatedY -= 5; acm.save(); }
            case BTN_NUDGE_D   -> { cfg.consolidatedY += 5; acm.save(); }
            default -> {}
        }
    }

    /** Decode BTN_MODULE_BASE + idx*STRIDE + sub. */
    private void handleModuleClick(int tag) {
        UtilityModuleManager mgr = org.cheetahv2.antigravity.client.AntigravityClient.UTILITY_MODULES;
        int rel = tag - BTN_MODULE_BASE;
        int idx = rel / MODULE_TAG_STRIDE;
        int sub = rel % MODULE_TAG_STRIDE;
        if (idx >= mgr.modules.size()) return;
        UtilityModule m = mgr.modules.get(idx);

        if (sub == 0) {
            m.setEnabled(!m.isEnabled());
            return;
        }

        if (m instanceof AutoCookieModule cookie) {
            AutoCookieModule.Config cc = cookie.getConfig();
            switch (sub) {
                case 1 -> cc.triggerDelayMs   = Math.max(0, cc.triggerDelayMs - 50);
                case 2 -> cc.triggerDelayMs   = Math.min(5000, cc.triggerDelayMs + 50);
                case 3 -> cc.cookieCooldownMs = Math.max(0, cc.cookieCooldownMs - 250);
                case 4 -> cc.cookieCooldownMs = Math.min(5000, cc.cookieCooldownMs + 250);
            }
            cookie.save();

        } else if (m instanceof AutoJackOfHeartsModule jack) {
            AutoJackOfHeartsModule.Config jc = jack.getConfig();
            switch (sub) {
                case 1 -> jc.triggerDelayMs = Math.max(0, jc.triggerDelayMs - 50);
                case 2 -> jc.triggerDelayMs = Math.min(5000, jc.triggerDelayMs + 50);
                case 3 -> jc.useCooldownMs  = Math.max(0, jc.useCooldownMs - 250);
                case 4 -> jc.useCooldownMs  = Math.min(10000, jc.useCooldownMs + 250);
            }
            jack.save();

        } else if (m instanceof AutoSacredShieldModule sacred) {
            AutoSacredShieldModule.Config sc = sacred.getConfig();
            switch (sub) {
                case 1 -> sc.triggerHealth = Math.max(2f, sc.triggerHealth - 1f);
                case 2 -> sc.triggerHealth = Math.min(20f, sc.triggerHealth + 1f);
            }
            sacred.save();

        } else if (m instanceof AutoTropicalShieldModule tropical) {
            AutoTropicalShieldModule.Config tc = tropical.getConfig();
            switch (sub) {
                case 1 -> tc.triggerHealth = Math.max(2f, tc.triggerHealth - 1f);
                case 2 -> tc.triggerHealth = Math.min(20f, tc.triggerHealth + 1f);
            }
            tropical.save();

        } else if (m instanceof AutoTotemModule totem) {
            AutoTotemModule.Config tc = totem.getConfig();
            switch (sub) {
                case 1 -> tc.mode = tc.mode == AutoTotemModule.Mode.ALWAYS
                        ? AutoTotemModule.Mode.SMART : AutoTotemModule.Mode.ALWAYS;
                case 2 -> tc.triggerHealth = Math.max(2f, tc.triggerHealth - 1f);
                case 3 -> tc.triggerHealth = Math.min(20f, tc.triggerHealth + 1f);
            }
            totem.save();

        } else if (m instanceof AbilityKeybindModule keys) {
            AbilityKeybindModule.Config kc = keys.getConfig();
            if (sub == 1) {
                AbilityKeybindModule.Bind b = keys.addBind();
                if (b != null) captureBind = b; // immediately listen for the key
            }
            else if (sub == 2) { kc.actionDelayMs      = Math.max(50,  kc.actionDelayMs - 50);      keys.save(); }
            else if (sub == 3) { kc.actionDelayMs      = Math.min(1000, kc.actionDelayMs + 50);     keys.save(); }
            else if (sub == 4) { kc.menuConfirmDelayMs = Math.max(50,  kc.menuConfirmDelayMs - 50); keys.save(); }
            else if (sub == 5) { kc.menuConfirmDelayMs = Math.min(2000, kc.menuConfirmDelayMs + 50); keys.save(); }
            else if (sub >= 10) {
                int k  = (sub - 10) / 4;
                int op = (sub - 10) % 4;
                if (k < kc.binds.size()) {
                    AbilityKeybindModule.Bind b = kc.binds.get(k);
                    switch (op) {
                        case 0 -> captureBind = b; // rebind: listen for next key
                        case 1 -> { b.menuSlot = Math.max(1, b.menuSlot - 1); keys.save(); }
                        case 2 -> { b.menuSlot = Math.min(9, b.menuSlot + 1); keys.save(); }
                        case 3 -> { if (captureBind == b) captureBind = null; keys.removeBind(k); }
                    }
                }
            }

        } else if (m instanceof org.cheetahv2.antigravity.client.utility.AbstractEquipMenuModule equip) {
            var sc = equip.getConfig();
            switch (sub) {
                case 1 -> sc.minLevel = Math.max(1, sc.minLevel - 1);
                case 2 -> sc.minLevel = Math.min(sc.maxLevel, sc.minLevel + 1);
                case 3 -> sc.maxLevel = Math.max(sc.minLevel, sc.maxLevel - 1);
                case 4 -> sc.maxLevel = Math.min(5, sc.maxLevel + 1);
                case 5 -> sc.actionDelayMs = Math.max(50, sc.actionDelayMs - 50);
                case 6 -> sc.actionDelayMs = Math.min(1000, sc.actionDelayMs + 50);
                case 7 -> sc.menuConfirmDelayMs = Math.max(50, sc.menuConfirmDelayMs - 50);
                case 8 -> sc.menuConfirmDelayMs = Math.min(2000, sc.menuConfirmDelayMs + 50);
            }
            equip.save();

        } else if (m instanceof BidWarModule bidwar) {
            BidWarModule.Config bc = bidwar.getConfig();
            if (sub == 1)      bc.notifyAll ^= true;
            else if (sub == 2) bc.playSound ^= true;
            else if (sub == 3) bc.showTitle ^= true;
            else if (sub >= 10) {
                int k = sub - 10;
                if (k < bc.watchedItems.size()) bc.watchedItems.remove(k);
            }
            bidwar.save();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // KEYBOARD
    // ─────────────────────────────────────────────────────────────────────
    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();

        // ── Ability bind key capture ──────────────────────────────────────
        if (captureBind != null) {
            if (key != GLFW.GLFW_KEY_ESCAPE) {
                captureBind.keyCode = key;
                captureBind.keyName = keyDisplayName(key);
                org.cheetahv2.antigravity.client.AntigravityClient.UTILITY_MODULES.ABILITY_KEYS.save();
            }
            captureBind = null; // ESC just cancels the capture
            return true;
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP)   { scroll[activeTab] -= 60; clampScroll(); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll[activeTab] += 60; clampScroll(); return true; }
        return super.keyPressed(input);
    }

    private static String keyDisplayName(int code) {
        try {
            return net.minecraft.client.util.InputUtil.Type.KEYSYM
                    .createFromCode(code).getLocalizedText().getString();
        } catch (Exception e) {
            return "KEY_" + code;
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    public void onClose() {
        org.cheetahv2.antigravity.client.AntigravityClient.HUD_SETTINGS.save();
        org.cheetahv2.antigravity.client.AntigravityClient.ABILITY_COOLDOWN.save();
        MinecraftClient.getInstance().setScreen(null);
    }
}
