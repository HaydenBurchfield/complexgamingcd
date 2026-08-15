package org.cheetahv2.antigravity.client.cooldown;

import com.google.gson.reflect.TypeToken;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.util.ConfigHelper;
import org.cheetahv2.antigravity.client.util.RomanHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AbilityCooldownManager {
    public static final Path FILE = Paths.get("config", "antigravity", "ability_cooldowns.json");

    public enum LayoutMode {
        CONSOLIDATED,
        INDIVIDUAL
    }

    public static class AbilityDef {
        public String id;
        public String name;
        public String category;
        public long defaultDurationMs;
        public String triggerKeyword;
        public String iconChar;
        public int iconColor;

        public AbilityDef() {}

        public AbilityDef(String id, String name, String category, long defaultDurationMs,
                          String triggerKeyword, String iconChar, int iconColor) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.defaultDurationMs = defaultDurationMs;
            this.triggerKeyword = triggerKeyword;
            this.iconChar = iconChar;
            this.iconColor = iconColor;
        }
    }

    public static class AbilitySetting {
        public boolean enabled = true;
        public int customX = -1;
        public int customY = -1;
        public int customDurationSec = -1;
        public String customTriggerKeyword = "";
        public int customColor = -1;
    }

    public static class CooldownInstance {
        public final String id;
        public final String name;
        public final long startTime;
        public final long durationMs;
        public final String iconChar;
        public final int iconColor;

        public CooldownInstance(String id, String name, long durationMs, String iconChar, int iconColor) {
            this.id = id;
            this.name = name;
            this.startTime = System.currentTimeMillis();
            this.durationMs = durationMs;
            this.iconChar = iconChar;
            this.iconColor = iconColor;
        }

        public long remaining() {
            return Math.max(0, startTime + durationMs - System.currentTimeMillis());
        }

        public float progress() {
            return durationMs <= 0 ? 1f : Math.min(1f, (float) (System.currentTimeMillis() - startTime) / durationMs);
        }

        public boolean isExpired() {
            return remaining() <= 0;
        }

        // ── Fade in/out alpha (0-255) ─────────────────────────────────────
        private static final long FADE_IN_MS  = 300L;
        private static final long FADE_OUT_MS = 600L;

        public int alpha() {
            long elapsed   = System.currentTimeMillis() - startTime;
            long remaining = remaining();
            if (elapsed < FADE_IN_MS) {
                // Fading in — ramp from 0 → 255
                return (int)(255L * elapsed / FADE_IN_MS);
            }
            if (remaining < FADE_OUT_MS) {
                // Fading out — ramp from 255 → 0
                return (int)(255L * remaining / FADE_OUT_MS);
            }
            return 255;
        }
    }

    public static class SavedConfig {
        public LayoutMode layoutMode = LayoutMode.CONSOLIDATED;
        public int consolidatedX = 10;
        public int consolidatedY = 50;
        public float scale = 1.0f;
        public Map<String, AbilitySetting> settings = new HashMap<>();
        public List<AbilityDef> userDefs = new ArrayList<>();
    }

    private static final List<AbilityDef> ABILITY_DEFS = new ArrayList<>();
    private static final Map<String, AbilityDef> DEFS_BY_ID = new HashMap<>();

    public static List<AbilityDef> getDefs() {
        return Collections.unmodifiableList(ABILITY_DEFS);
    }

    public static AbilityDef getDef(String id) {
        return DEFS_BY_ID.get(id);
    }

    public String addDef(String name, String triggerKeyword, long durationMs) {
        name = name.trim();
        if (name.isEmpty()) return null;

        String baseId = name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String id = baseId;
        int suffix = 2;
        while (DEFS_BY_ID.containsKey(id)) {
            id = baseId + "_" + suffix++;
        }

        String safeIcon = "\u25C6";
        int safeColor = 0xFFBB8FFF;
        AbilityDef def = new AbilityDef(id, name, "Custom", durationMs, triggerKeyword, safeIcon, safeColor);

        ABILITY_DEFS.add(def);
        DEFS_BY_ID.put(id, def);
        config.settings.computeIfAbsent(id, k -> new AbilitySetting());

        syncUserDefs();
        save();
        return id;
    }

    public boolean removeDef(String id) {
        AbilityDef def = DEFS_BY_ID.remove(id);
        if (def == null) return false;
        ABILITY_DEFS.remove(def);
        config.settings.remove(id);
        active.remove(id);
        syncUserDefs();
        save();
        return true;
    }

    private void syncUserDefs() {
        config.userDefs = new ArrayList<>(ABILITY_DEFS);
    }

    private void rebuildRuntimeLists() {
        ABILITY_DEFS.clear();
        DEFS_BY_ID.clear();
        if (config.userDefs != null) {
            for (AbilityDef def : config.userDefs) {
                if (def == null || def.id == null || def.id.isEmpty()) continue;
                if (def.iconChar == null || def.iconChar.isEmpty()) def.iconChar = "\u25C6";
                if ((def.iconColor & 0xFF000000) == 0) def.iconColor |= 0xFF000000;
                ABILITY_DEFS.add(def);
                DEFS_BY_ID.put(def.id, def);
            }
        }
    }

    private final Map<String, CooldownInstance> active = new ConcurrentHashMap<>();
    private SavedConfig config = new SavedConfig();

    public boolean draggingConsolidated = false;
    private int dragOffX, dragOffY;

    public AbilityCooldownManager() {}

    public void initDefaults() {
        config.userDefs.clear();

        // 1. Power Pylon III: 20s or 30s
        config.userDefs.add(new AbilityDef(
                "power_pylon_iii", "Power Pylon III", "Survival", 30000L,
                "activated your Power Pylon III", "\u25C6", 0xFFFFB300
        ));

        // 2. Toxic Forcefield III: 2m 3s (123s)
        config.userDefs.add(new AbilityDef(
                "toxic_forcefield_iii", "Toxic Forcefield III", "Survival", 123000L,
                "deployed Toxic Forcefield III", "\u2620", 0xFFFFA000
        ));

        // 3. Kamikaze III: 14s
        config.userDefs.add(new AbilityDef(
                "kamikaze_iii", "Kamikaze III", "Survival", 14000L,
                "activated Kamikaze III", "\u2605", 0xFFE53935
        ));

        rebuildRuntimeLists();

        for (AbilityDef def : ABILITY_DEFS) {
            config.settings.put(def.id, new AbilitySetting());
        }
    }

    public void load() {
        SavedConfig c = ConfigHelper.load(FILE, SavedConfig.class);
        if (c != null) {
            this.config = c;
            rebuildRuntimeLists();
            for (AbilityDef def : ABILITY_DEFS) {
                this.config.settings.computeIfAbsent(def.id, k -> new AbilitySetting());
            }
        } else {
            initDefaults();
            save();
        }
    }

    public void save() {
        syncUserDefs();
        ConfigHelper.save(FILE, this.config);
    }

    public SavedConfig getConfig() {
        return config;
    }

    public Map<String, CooldownInstance> getActive() {
        active.entrySet().removeIf(e -> e.getValue().isExpired());
        return active;
    }

    public void trigger(String id) {
        AbilityDef def = getDef(id);
        if (def == null) return;
        AbilitySetting setting = config.settings.get(id);
        if (setting == null || !setting.enabled) return;

        long durMs = (setting.customDurationSec > 0) ? (setting.customDurationSec * 1000L) : def.defaultDurationMs;
        int color = (setting.customColor != -1) ? setting.customColor : def.iconColor;

        CooldownInstance inst = new CooldownInstance(def.id, def.name, durMs, def.iconChar, color);
        active.put(def.id, inst);
        // Notifications suppressed — cooldowns are shown in the HUD only
    }

    public void triggerByName(String name) {
        String lowerName = name.toLowerCase().replace(" ", "_");
        for (AbilityDef def : ABILITY_DEFS) {
            if (def.id.equals(lowerName) || def.name.equalsIgnoreCase(name)) {
                trigger(def.id);
                return;
            }
        }
    }

    public static String fmt(long ms) {
        long secTotal = ms / 1000L;
        if (secTotal >= 60) {
            long min = secTotal / 60;
            long sec = secTotal % 60;
            if (sec == 0) return min + "m";
            return min + "m " + sec + "s";
        }
        return secTotal + "s";
    }

    private static final java.util.regex.Pattern RUNES_YOUR_PATTERN = java.util.regex.Pattern.compile(
            "RUNES\\s*[\\u2714\\u2718]?\\s*Your\\s+([A-Za-z ]+?)\\s+([IVXLCDM]+)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    // Current server rune format: "<icon> <Rune Name> <ROMAN> | check/warn <message>"
    // Captures the rune name + level from the header and which marker followed.
    private static final java.util.regex.Pattern RUNE_HEADER_PATTERN =
            java.util.regex.Pattern.compile(
                    "^\\W*([A-Za-z][A-Za-z' ]+?)\\s+([IVXLCDM]+)\\s*\u2503\\s*(\u2714|\u26A0)");

    public void onChat(String plain) {
        String stripped = plain.replaceAll("\u00A7[0-9a-fk-orA-FK-OR]", "").trim();
        String lo = stripped.toLowerCase();

        // -- 0. Modern header format: only YOUR runes (check) start a cooldown --
        java.util.regex.Matcher hdr = RUNE_HEADER_PATTERN.matcher(stripped);
        if (hdr.find()) {
            // Phrase-based on purpose: a bare "restor" test also matched
            // Lifeforce's "Restored 1.92 health!", which IS a proc.
            boolean expiry = lo.contains("effects have been restored")
                    || lo.contains("effects restored") || lo.contains("expired")
                    || lo.contains("wore off") || lo.contains("worn off")
                    || lo.contains("no longer") || lo.contains("deplet");
            if (!expiry && "\u2714".equals(hdr.group(3))) {
                triggerByName(hdr.group(1).trim());
            }
            return;
        }

        // ── 1. RUNES server messages ──
        if (lo.startsWith("runes") || lo.contains("[runes]")) {
            java.util.regex.Matcher m = RUNES_YOUR_PATTERN.matcher(stripped);
            if (m.find()) {
                if (!lo.contains("deplet") && !lo.contains("expir")) {
                    triggerByName(m.group(1).trim());
                }
            }
            return;
        }

        // ── 2. Trigger if message contains keyword ──
        for (AbilityDef def : ABILITY_DEFS) {
            AbilitySetting setting = config.settings.get(def.id);
            String keyword = def.triggerKeyword;

            if (setting != null &&
                    setting.customTriggerKeyword != null &&
                    !setting.customTriggerKeyword.isEmpty()) {
                keyword = setting.customTriggerKeyword;
            }

            if (keyword != null && !keyword.isEmpty() && lo.contains(keyword.toLowerCase())) {
                trigger(def.id);
            }
        }
    }


    private final Map<KeyBinding, Boolean> keyStates = new HashMap<>();


    public void tickDrag(MinecraftClient mc) {
        if (mc == null || mc.currentScreen != null || mc.getWindow() == null) return;
        if (config.layoutMode != LayoutMode.CONSOLIDATED) return;

        long win = mc.getWindow().getHandle();
        boolean lmb = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean shift = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(win, mx, my);
        double gx = mx[0] / mc.getWindow().getScaleFactor();
        double gy = my[0] / mc.getWindow().getScaleFactor();

        float sc   = config.scale;
        int   bx   = config.consolidatedX;
        int   by   = config.consolidatedY;
        int   boxW = (int)(150 * sc);
        int   boxH = (int)(Math.max(20, 16 + getActive().size() * 26) * sc);

        if (lmb && shift && !draggingConsolidated
                && gx >= bx && gx <= bx + boxW && gy >= by && gy <= by + boxH) {
            draggingConsolidated = true;
            dragOffX = (int)(gx - bx);
            dragOffY = (int)(gy - by);
        }

        if (draggingConsolidated) {
            if (lmb) {
                config.consolidatedX = (int)(gx - dragOffX);
                config.consolidatedY = (int)(gy - dragOffY);
            } else {
                draggingConsolidated = false;
                save();
            }
        }
    }

    public void onScroll(double dy, double mouseX, double mouseY) {
        float sc   = config.scale;
        int   boxW = (int)(150 * sc);
        int   boxH = (int)(Math.max(20, 16 + active.size() * 26) * sc);
        int   bx   = config.consolidatedX;
        int   by   = config.consolidatedY;
        if (mouseX >= bx && mouseX <= bx + boxW && mouseY >= by && mouseY <= by + boxH) {
            config.scale = Math.max(0.4f, Math.min(3.0f, sc + (float)(dy * 0.1)));
            save();
        }
    }

    // ── Jack of Hearts special cooldown ──────────────────────────────────
    public static final String JACK_OF_HEARTS_ID   = "__jack_of_hearts__";
    public static final long   JACK_OF_HEARTS_MS   = 90_000L; // 1:30

    /**
     * Call this whenever the Jack of Hearts card is used.
     * Registers a 1:30 cooldown tile in the HUD (it shows up alongside runes
     * even though it is not a rune itself).
     */
    public void triggerJackOfHearts() {
        CooldownInstance inst = new CooldownInstance(
                JACK_OF_HEARTS_ID,
                "Jack of Hearts",
                JACK_OF_HEARTS_MS,
                "\uD83C\uDCCB",        // 🃋 playing card jack
                0xFFFF6699             // pink-red
        );
        active.put(JACK_OF_HEARTS_ID, inst);
    }

    /**
     * Blend the alpha channel of an ARGB color with a 0-255 alpha factor.
     * Existing alpha in the color is multiplied by the factor.
     */
    private static int applyAlpha(int argb, int alpha) {
        int baseA = (argb >>> 24) & 0xFF;
        int finalA = baseA * alpha / 255;
        return (finalA << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Draw a vertical gradient rectangle (top → bottom color transition).
     * Used for the left-side accent bar on each cooldown tile.
     */
    private static void fillGradientV(DrawContext ctx,
                                      int x, int y, int w, int h,
                                      int colorTop, int colorBottom) {
        int at = (colorTop    >>> 24) & 0xFF, rt = (colorTop    >> 16) & 0xFF;
        int gt = (colorTop    >>  8) & 0xFF,  bt = (colorTop         ) & 0xFF;
        int ab = (colorBottom >>> 24) & 0xFF, rb = (colorBottom >> 16) & 0xFF;
        int gb = (colorBottom >>  8) & 0xFF,  bb = (colorBottom       ) & 0xFF;
        for (int row = 0; row < h; row++) {
            float t = (h <= 1) ? 0f : (float) row / (h - 1);
            int a = at + (int)((ab - at) * t);
            int r = rt + (int)((rb - rt) * t);
            int g = gt + (int)((gb - gt) * t);
            int b = bt + (int)((bb - bt) * t);
            ctx.fill(x, y + row, x + w, y + row + 1, (a << 24) | (r << 16) | (g << 8) | b);
        }
    }

    public void renderHud(DrawContext ctx, MinecraftClient mc) {

        if (mc == null || mc.getWindow() == null) return;

        Map<String, CooldownInstance> activeMap = getActive();

        if (activeMap.isEmpty() && !draggingConsolidated) return;

        TextRenderer tr = mc.textRenderer;
        float scale = config.scale;

        if (config.layoutMode == LayoutMode.CONSOLIDATED) {

            org.joml.Matrix3x2fStack ms = ctx.getMatrices();

            ms.pushMatrix();
            ms.translate(
                    (float) config.consolidatedX,
                    (float) config.consolidatedY
            );

            ms.scale(scale, scale);

            int rx = 0;
            int ry = 0;

            int boxW = 155;

            if (activeMap.isEmpty()) {

                if (draggingConsolidated) {

                    ctx.fill(
                            rx,
                            ry,
                            rx + boxW,
                            ry + 16,
                            0x33000000
                    );

                    gborder(
                            ctx,
                            rx,
                            ry,
                            boxW,
                            16,
                            0x55FFFFFF
                    );

                    ctx.drawTextWithShadow(
                            tr,
                            "(Drag HUD)",
                            rx + 5,
                            ry + 4,
                            0xFF888888
                    );
                }

                ms.popMatrix();
                return;
            }

            for (CooldownInstance e : activeMap.values()) {

                long rem  = e.remaining();
                int  alpha = e.alpha();   // 0-255 fade in/out
                int  boxH = 16;

                // =====================================================
                // Background
                // =====================================================
                ctx.fill(
                        rx,
                        ry,
                        rx + boxW,
                        ry + boxH,
                        applyAlpha(org.cheetahv2.antigravity.client.gui.Theme.HUD_BG, alpha)
                );

                // =====================================================
                // Thin outline
                // =====================================================
                gborder(
                        ctx,
                        rx,
                        ry,
                        boxW,
                        boxH,
                        applyAlpha(0x408D77E8, alpha)
                );

                // =====================================================
                // Top glass highlight
                // =====================================================
                ctx.fill(
                        rx + 1,
                        ry + 1,
                        rx + boxW - 1,
                        ry + 2,
                        applyAlpha(0x12FFFFFF, alpha)
                );

                // =====================================================
                // Left accent bar — gradient from rune color → black
                // =====================================================
                {
                    int barColorTop = applyAlpha(e.iconColor | 0xFF000000, alpha);
                    int barColorBot = applyAlpha(0xFF000000, alpha);
                    fillGradientV(ctx, rx + 1, ry + 1, 2, boxH - 2, barColorTop, barColorBot);
                }

                // =====================================================
                // Icon
                // =====================================================
                ctx.drawTextWithShadow(
                        tr,
                        e.iconChar,
                        rx + 6,
                        ry + 4,
                        applyAlpha(e.iconColor, alpha)
                );

                // =====================================================
                // Name
                // =====================================================
                ctx.drawTextWithShadow(
                        tr,
                        e.name,
                        rx + 18,
                        ry + 4,
                        applyAlpha(e.iconColor, alpha)
                );

                // =====================================================
                // Time
                // =====================================================
                String timeStr = fmt(rem);
                int twStr = tr.getWidth(timeStr);
                ctx.drawTextWithShadow(
                        tr,
                        timeStr,
                        rx + boxW - twStr - 5,
                        ry + 4,
                        applyAlpha(org.cheetahv2.antigravity.client.gui.Theme.GOOD, alpha)
                );

                // =====================================================
                // Animated bottom progress line
                // =====================================================
                int lineY = ry + boxH - 2;

                ctx.fill(
                        rx + 1,
                        lineY,
                        rx + boxW - 1,
                        lineY + 1,
                        applyAlpha(0x22000000, alpha)
                );

                int progressWidth = (int)((1f - e.progress()) * (boxW - 2));
                if (progressWidth > 0) {
                    ctx.fill(
                            rx + 1,
                            lineY,
                            rx + 1 + progressWidth,
                            lineY + 1,
                            applyAlpha(e.iconColor, alpha)
                    );
                }

                ry += boxH + 3;
            }

            if (draggingConsolidated) {

                gborder(
                        ctx,
                        -2,
                        -2,
                        boxW + 4,
                        ry + 4,
                        0xFFFFFF00
                );
            }

            ms.popMatrix();


        } else {

            for (CooldownInstance e : activeMap.values()) {

                AbilitySetting setting = config.settings.get(e.id);
                if (setting == null || !setting.enabled) continue;

                int ix = setting.customX >= 0 ? setting.customX : 4;
                int iy = setting.customY >= 0 ? setting.customY : 100;

                int boxW = 140;
                int boxH = 16;
                int alpha = e.alpha();   // 0-255 fade in/out

                String timeStr = fmt(e.remaining());

                // =====================================================
                // Background
                // =====================================================
                ctx.fill(ix, iy, ix + boxW, iy + boxH,
                        applyAlpha(org.cheetahv2.antigravity.client.gui.Theme.HUD_BG, alpha));

                // =====================================================
                // Border
                // =====================================================
                gborder(ctx, ix, iy, boxW, boxH,
                        applyAlpha(0x408D77E8, alpha));

                // =====================================================
                // Top highlight
                // =====================================================
                ctx.fill(ix + 1, iy + 1, ix + boxW - 1, iy + 2,
                        applyAlpha(0x12FFFFFF, alpha));

                // =====================================================
                // Left accent bar — gradient from rune color → black
                // =====================================================
                {
                    int barColorTop = applyAlpha(e.iconColor | 0xFF000000, alpha);
                    int barColorBot = applyAlpha(0xFF000000, alpha);
                    fillGradientV(ctx, ix + 1, iy + 1, 2, boxH - 2, barColorTop, barColorBot);
                }

                // =====================================================
                // Icon
                // =====================================================
                ctx.drawTextWithShadow(tr, e.iconChar,
                        ix + 6, iy + 4, applyAlpha(e.iconColor, alpha));

                // =====================================================
                // Name
                // =====================================================
                ctx.drawTextWithShadow(tr, e.name,
                        ix + 18, iy + 4, applyAlpha(e.iconColor, alpha));

                // =====================================================
                // Timer
                // =====================================================
                int tw = tr.getWidth(timeStr);
                ctx.drawTextWithShadow(tr, timeStr,
                        ix + boxW - tw - 5, iy + 4,
                        applyAlpha(org.cheetahv2.antigravity.client.gui.Theme.GOOD, alpha));

                // =====================================================
                // Progress line
                // =====================================================
                int progressWidth = (int)((1f - e.progress()) * (boxW - 2));
                ctx.fill(ix + 1, iy + boxH - 2,
                        ix + 1 + progressWidth, iy + boxH - 1,
                        applyAlpha(e.iconColor, alpha));
            }
        }
    }

    private void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }
}