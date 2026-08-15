package org.cheetahv2.antigravity.client.gui;

/**
 * Theme — the unified "Antigravity" look.
 *
 * Concept: deep-void indigo backdrops, a levitation-violet primary accent,
 * gravity-well aqua as the secondary, starlight text, and the ✦ star as the
 * brand mark. Every screen and HUD pulls its colors from here so the whole
 * mod reads as one product.
 *
 * Usage: reference the constants directly (they are compile-time constants),
 * or alias them in a local palette block. Semantic colors (GOOD/BAD/WARN and
 * feature colors like the Frozen ice-blue) stay meaningful on purpose.
 */
public final class Theme {

    private Theme() {}

    // ── Brand ─────────────────────────────────────────────────────────────
    /** Star mark used across headers and lists. */
    public static final String ICON = "✦";
    /** Header wordmark, pre-colored ("✦ ANTIGRAVITY"). */
    public static final String WORDMARK = "§d✦ §fANTI§dGRAVITY";

    // ── Void backdrops ────────────────────────────────────────────────────
    public static final int BG         = 0xEE0B0718; // settings card base — deep void indigo
    public static final int BG_DIM     = 0x88050212; // full-screen backdrop dim
    public static final int GLASS_BG   = 0xD00B0718; // dialog glass card
    public static final int HUD_BG     = 0x50120A24; // in-game HUD pill background
    public static final int PANEL      = 0x50201640; // nebula inset panel
    public static final int PANEL_LIT  = 0x702A1D52; // hovered panel

    // ── Lines & glass ─────────────────────────────────────────────────────
    public static final int BORDER      = 0x508D77E8; // soft violet border
    public static final int BORDER_SOFT = 0x2A8D77E8; // faint violet border (HUD pills)
    public static final int BORDER_LIT  = 0xCCC9B8FF; // bright on hover
    public static final int SHEEN       = 0x1AFFFFFF; // top glass highlight
    public static final int SHADOW      = 0x40000000; // bottom inner shadow

    // ── Accents ───────────────────────────────────────────────────────────
    public static final int ACCENT     = 0xFFA77BFF; // levitation violet (primary)
    public static final int ACCENT_ALT = 0xFF64E0DC; // gravity-well aqua (secondary)

    // ── Text ──────────────────────────────────────────────────────────────
    public static final int TEXT_HI  = 0xFFF3EEFF; // starlight
    public static final int TEXT_MID = 0xFFB9ABDE; // dusk lavender
    public static final int TEXT_DIM = 0xFF6E5F94; // faded violet-grey

    // ── Status (semantic — kept meaningful) ───────────────────────────────
    public static final int GOOD     = 0xFF57E8A9; // aurora green (ON / healthy)
    public static final int GOOD_DIM = 0x3030A870;
    public static final int BAD      = 0xFFFF5E7A; // red-pink (OFF / danger)
    public static final int BAD_DIM  = 0x30A82238;
    public static final int WARN     = 0xFFFFC95E; // solar gold (warnings, cached, cores)

    // ── Interactive fills ─────────────────────────────────────────────────
    public static final int BTN_BG       = 0x30160D2E;
    public static final int BTN_BG_HOV   = 0x50241850;
    public static final int BTN_BG_DOWN  = 0x60321F6E;
    public static final int TAB_ACTIVE   = 0xFF231548;
    public static final int SCROLL_TRACK = 0x30160D2E;
    public static final int SCROLL_THUMB = 0x90A77BFF;

    // ── Drawing helpers ───────────────────────────────────────────────────

    /**
     * Rounded "pill" — fill with 2px-cut corners plus a matching rounded
     * border. The standard button/badge shape across all Antigravity menus.
     */
    public static void pill(net.minecraft.client.gui.DrawContext c,
                            int x, int y, int w, int h, int bg, int brd) {
        // background with clipped corners
        c.fill(x + 2, y,     x + w - 2, y + h,     bg);
        c.fill(x,     y + 2, x + 2,     y + h - 2, bg);
        c.fill(x + w - 2, y + 2, x + w, y + h - 2, bg);
        c.fill(x + 1, y + 1, x + 2, y + 2, bg);
        c.fill(x + w - 2, y + 1, x + w - 1, y + 2, bg);
        c.fill(x + 1, y + h - 2, x + 2, y + h - 1, bg);
        c.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, bg);
        // rounded border
        c.fill(x + 2, y,         x + w - 2, y + 1,     brd);
        c.fill(x + 2, y + h - 1, x + w - 2, y + h,     brd);
        c.fill(x,     y + 2,     x + 1,     y + h - 2, brd);
        c.fill(x + w - 1, y + 2, x + w,     y + h - 2, brd);
        c.fill(x + 1, y + 1, x + 2, y + 2, brd);
        c.fill(x + w - 2, y + 1, x + w - 1, y + 2, brd);
        c.fill(x + 1, y + h - 2, x + 2, y + h - 1, brd);
        c.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, brd);
        // top sheen
        c.fill(x + 3, y + 1, x + w - 3, y + 2, SHEEN);
    }

    /**
     * Twinkling deterministic starfield inside a rect — the "space" garnish
     * for menu cards. Cheap: ~count pixel stars + a few dim ✦ glyphs, with a
     * slow time-based twinkle. Positions are stable between frames.
     */
    public static void stars(net.minecraft.client.gui.DrawContext c,
                             net.minecraft.client.font.TextRenderer tr,
                             int x, int y, int w, int h, int count) {
        long time = System.currentTimeMillis();
        java.util.Random rnd = new java.util.Random(9137L); // fixed seed → stable layout
        for (int i = 0; i < count; i++) {
            int sx = x + rnd.nextInt(Math.max(1, w));
            int sy = y + rnd.nextInt(Math.max(1, h));
            // Slow twinkle, phase-shifted per star
            float tw = (float) (0.5 + 0.5 * Math.sin(time / 900.0 + i * 2.1));
            int a = 0x14 + (int) (0x22 * tw);
            boolean violet = (i % 3 == 0);
            int col = (a << 24) | (violet ? 0xA77BFF : 0xFFFFFF);
            c.fill(sx, sy, sx + 1, sy + 1, col);
            // Every 8th star is a tiny ✦ glyph
            if (i % 8 == 0) {
                c.drawText(tr, ICON, sx, sy, ((a / 2) << 24) | 0xC9B8FF, false);
            }
        }
    }
}
