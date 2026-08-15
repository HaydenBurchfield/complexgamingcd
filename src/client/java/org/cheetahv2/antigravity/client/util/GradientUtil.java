package org.cheetahv2.antigravity.client.util;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GradientUtil — builds &#RRGGBB gradient strings for signatures.
 *
 * Three sources of colour:
 *   • two or more explicit hex stops  → smooth interpolation
 *   • "rainbow"                        → full hue sweep
 *   • an ItemStack's display name      → SAMPLES the item's own per-character
 *     colours and re-maps them across your text, so a signature matches the
 *     exact gradient of a named item.
 *
 * Output uses the same &#RRGGBB / &l format the server accepts, so results
 * can be pasted straight into a signature preset.
 */
public final class GradientUtil {

    private GradientUtil() {}

    // ── Colour list builders ──────────────────────────────────────────────

    /** Parses "#10B1FF,#91FFFF" / "10B1FF 91FFFF" into ARGB-less RGB ints. */
    public static List<Integer> parseStops(String spec) {
        List<Integer> out = new ArrayList<>();
        for (String tok : spec.split("[,\\s]+")) {
            String h = tok.trim();
            if (h.startsWith("&")) h = h.substring(1);
            if (h.startsWith("#"))  h = h.substring(1);
            if (h.length() != 6) continue;
            try { out.add((int) Long.parseLong(h, 16)); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Evenly spaced hue sweep. */
    public static List<Integer> rainbow(int steps) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.max(2, steps); i++) {
            float h = (float) i / Math.max(1, steps - 1);
            out.add(java.awt.Color.HSBtoRGB(h, 0.85f, 1.0f) & 0xFFFFFF);
        }
        return out;
    }

    /**
     * Extracts the ordered list of colours actually used across an item's
     * display name, one entry per visible character. This is what lets a
     * signature "match the gradient" of an item.
     */
    public static List<Integer> sampleItemName(ItemStack stack) {
        List<Integer> colors = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return colors;
        Text name = stack.getName();
        name.visit((style, str) -> {
            TextColor col = style.getColor();
            int rgb = col != null ? (col.getRgb() & 0xFFFFFF) : 0xFFFFFF;
            for (int i = 0; i < str.length(); i++) {
                if (Character.isWhitespace(str.charAt(i))) continue;
                colors.add(rgb);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return colors;
    }

    // ── Core gradient application ─────────────────────────────────────────

    /**
     * Applies a colour ramp across the text, emitting "&#RRGGBB" before each
     * visible character (whitespace is left uncoloured so it stays compact).
     *
     * @param stops two or more colours; the ramp is interpolated between them
     * @param bold  append &l to every character
     */
    public static String apply(String text, List<Integer> stops, boolean bold) {
        return apply(text, stops, bold, 1);
    }

    /** Minecraft drops any chat/command packet longer than this. */
    public static final int MAX_COMMAND_LENGTH = 256;

    /**
     * Gradient with a colour block size.
     *
     * Two size optimisations matter, because each hex code costs 7-9 chars and
     * Minecraft hard-limits commands to 256 characters:
     *   - a colour code is only emitted when the colour actually CHANGES
     *   - step > 1 colours in blocks instead of per character
     * A hex code resets formatting, so &l is re-emitted after each code, but
     * only then.
     *
     * @param step visible characters per colour block (1 = smoothest)
     */
    public static String apply(String text, List<Integer> stops, boolean bold, int step) {
        if (text == null || text.isEmpty() || stops == null || stops.isEmpty()) return text;
        if (step < 1) step = 1;
        if (stops.size() == 1) {
            return String.format("&#%06X", stops.get(0)) + (bold ? "&l" : "") + text;
        }

        int visible = 0;
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) visible++;
        if (visible == 0) return text;

        int groups = Math.max(1, (visible + step - 1) / step);
        StringBuilder sb = new StringBuilder(text.length() * 4);
        int idx = 0;
        int lastColor = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { sb.append(c); continue; }
            int group = idx / step;
            float t = groups == 1 ? 0f : (float) group / (groups - 1);
            int col = rampAt(stops, t);
            if (col != lastColor) {
                sb.append(String.format("&#%06X", col));
                if (bold) sb.append("&l");
                lastColor = col;
            }
            sb.append(c);
            idx++;
        }
        return sb.toString();
    }

    /**
     * Re-colours a line WITHOUT destroying its centring.
     *
     * Leading padding is written as "&f &f &f " because servers trim plain
     * leading whitespace. Running a gradient over the raw text turned that
     * padding back into bare spaces (stripCodes removes the &f, apply() emits
     * whitespace uncoloured), so every gradient/auto-gradient pass silently
     * un-centred the line. The pad is split off, the body re-coloured, and the
     * pad re-attached in its original form.
     */
    public static String applyPreservingPad(String coded, List<Integer> stops,
                                            boolean bold, int maxLen) {
        int pad = CodedTextRenderer.countPad(coded);
        String body = CodedTextRenderer.stripLeadingPad(coded);
        String plain = stripCodes(body);
        if (plain.isBlank()) return coded;
        String budgetted = fit(plain, stops, bold, Math.max(16, maxLen - pad * 3));
        return CodedTextRenderer.padUnits(pad) + budgetted;
    }

    /**
     * Builds the smoothest gradient that still fits in maxLen characters,
     * coarsening the colour blocks until it does. Falls back to a single
     * mid-ramp colour, and only then trims text (never mid-code).
     */
    public static String fit(String text, List<Integer> stops, boolean bold, int maxLen) {
        if (text == null) return "";
        if (stops == null || stops.isEmpty()) return text;
        for (int step = 1; step <= 24; step++) {
            String s = apply(text, stops, bold, step);
            if (s.length() <= maxLen) return s;
        }
        String single = String.format("&#%06X", rampAt(stops, 0.5f)) + (bold ? "&l" : "") + text;
        if (single.length() <= maxLen) return single;
        int overflow = single.length() - maxLen;
        String trimmed = text.length() > overflow ? text.substring(0, text.length() - overflow) : "";
        return String.format("&#%06X", rampAt(stops, 0.5f)) + (bold ? "&l" : "") + trimmed;
    }

    /** Colour at position t (0..1) along a multi-stop ramp. */
    public static int rampAt(List<Integer> stops, float t) {
        if (stops.size() == 1) return stops.get(0);
        t = Math.max(0f, Math.min(1f, t));
        float scaled = t * (stops.size() - 1);
        int i = (int) Math.floor(scaled);
        if (i >= stops.size() - 1) return stops.get(stops.size() - 1);
        return lerp(stops.get(i), stops.get(i + 1), scaled - i);
    }

    /** Linear blend between two RGB ints. */
    public static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Symmetric ramp: runs the stops forward then back, which is what the
     * "♱⫘⫘…⫘♱" border art does (dark → bright in the middle → dark again).
     */
    public static List<Integer> mirror(List<Integer> stops) {
        List<Integer> out = new ArrayList<>(stops);
        for (int i = stops.size() - 2; i >= 0; i--) out.add(stops.get(i));
        return out;
    }

    /** Strips &-codes so a preview can be measured/re-coloured. */
    public static String stripCodes(String s) {
        if (s == null) return "";
        return s.replaceAll("&#[0-9A-Fa-f]{6}", "").replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    /** Converts &-codes to §-codes so the result renders in-game as a preview. */
    public static String toSectionCodes(String s) {
        if (s == null) return "";
        // Vanilla can't render &#RRGGBB, so hex codes are dropped for preview
        return s.replaceAll("&#[0-9A-Fa-f]{6}", "").replace('&', '§');
    }
}
