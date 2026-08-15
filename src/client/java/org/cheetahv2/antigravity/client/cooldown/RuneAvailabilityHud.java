package org.cheetahv2.antigravity.client.cooldown;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.cheetahv2.antigravity.client.AntigravityClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * RuneAvailabilityHud
 *
 * Shows a side panel listing which runes are currently AVAILABLE — meaning:
 *   1. A matching item is actually IN YOUR INVENTORY right now, AND
 *   2. The rune is not currently on cooldown.
 *
 * The inventory scan is cached and refreshed every 10 client ticks (0.5 s)
 * from tick() — it is NOT recomputed per frame, so it costs nothing at
 * render time.
 *
 * INV_KEYWORDS maps enchant ID → word(s) that must appear in the item name to
 * count as "that rune being in inventory". If an ID isn't in INV_KEYWORDS, the
 * enchant's displayName (lower-case) is used.
 */
public class RuneAvailabilityHud {

    // ── Inventory keyword overrides ───────────────────────────────────────
    public static final Map<String, String> INV_KEYWORDS = new HashMap<>();
    static {
        INV_KEYWORDS.put("shuffle_deck",    "shuffle deck");
        INV_KEYWORDS.put("invocation",      "invocation");
        INV_KEYWORDS.put("swift_strike",    "swift strike");
        INV_KEYWORDS.put("circus_cannon",   "circus cannon");
        INV_KEYWORDS.put("illusion",        "illusion");
        INV_KEYWORDS.put("snake_eyes",      "snake eyes");
        INV_KEYWORDS.put("beeswax",         "beeswax");
        INV_KEYWORDS.put("lifebuoy",        "lifebuoy");
        INV_KEYWORDS.put("light_speed",     "light speed");
        INV_KEYWORDS.put("overhead_spin",   "overhead spin");
        INV_KEYWORDS.put("deep_freeze",     "deep freeze");
        INV_KEYWORDS.put("lifeforce",       "lifeforce");
        INV_KEYWORDS.put("dasher",          "dasher");
        INV_KEYWORDS.put("plaugeweaver",    "plagueweaver");
        INV_KEYWORDS.put("raging_smash",    "raging smash");
        INV_KEYWORDS.put("toxic_forcefield","toxic forcefield");
        INV_KEYWORDS.put("ascent",          "ascent");
        INV_KEYWORDS.put("power_pylon",     "power pylon");
        INV_KEYWORDS.put("rain_dance",      "rain dance");
        INV_KEYWORDS.put("squirting_flower","squirting flower");
        INV_KEYWORDS.put("love_birds",      "love birds");
        INV_KEYWORDS.put("lovestruck",      "lovestruck");
    }

    // ── Position / settings persistence ──────────────────────────────────
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = Paths.get("config", "antigravity", "hud_rune_avail.json");

    public static class SavedPos {
        public boolean enabled = true;
        public int x = -1;  // -1 = use default (right side)
        public int y = -1;
        public float scale = 1.0f;
    }

    private SavedPos pos = new SavedPos();

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(pos));
        } catch (IOException ignored) {}
    }

    public void load() {
        if (!Files.exists(FILE)) return;
        try {
            SavedPos p = GSON.fromJson(Files.readString(FILE), SavedPos.class);
            if (p != null) pos = p;
        } catch (IOException ignored) {}
    }

    public boolean isEnabled()        { return pos.enabled; }
    public void setEnabled(boolean v) { pos.enabled = v; save(); }

    public int getX(int screenWidth) {
        if (pos.x >= 0) return pos.x;
        return screenWidth - PANEL_W - 4;
    }

    public int getY(int screenHeight) {
        if (pos.y >= 0) return pos.y;
        return screenHeight / 2 - 40;
    }

    public float getScale() { return Math.max(0.4f, Math.min(3.0f, pos.scale)); }
    public void setScale(float s) { pos.scale = Math.max(0.4f, Math.min(3.0f, s)); }

    public void setPos(int x, int y) {
        pos.x = x;
        pos.y = y;
    }

    // ── Panel dimensions (unscaled) ──────────────────────────────────────
    public static final int PANEL_W  = 115;
    private static final int ENTRY_H  = 13;
    private static final int HEADER_H = 13;

    // ── Drag state (used from HudDragScreen) ─────────────────────────────
    public boolean dragging = false;
    public int dragOffX, dragOffY;

    // ── Cached availability (recomputed every 10 ticks, not per frame) ───
    private final List<CustomCooldownSystem.CustomEnchant> cachedAvailable = new ArrayList<>();
    private int tickCounter = 0;

    /**
     * Called once per client tick from AntigravityClient. Refreshes the
     * "which runes are in my inventory" cache every 10 ticks.
     */
    public void tick(MinecraftClient mc) {
        if (!pos.enabled) return;
        if (mc == null || mc.player == null) return;
        if (++tickCounter % 10 != 0) return;
        recompute(mc);
    }

    private void recompute(MinecraftClient mc) {
        // Single pass: collect every item's NAME + LORE lines into one haystack,
        // then match keywords. Runes are custom enchants written into the LORE
        // of gear (e.g. "◆ Shuffle Deck V" on boots) — matching names alone
        // finds nothing, which is why the panel used to need ALWAYS_SHOW hacks.
        StringBuilder hay = new StringBuilder(768);
        var inv = mc.player.getInventory();
        for (int i = 0; i < 40; i++) { // 0-35 main inv + hotbar, 36-39 worn armor
            appendItemText(hay, inv.getStack(i));
        }
        appendItemText(hay, mc.player.getOffHandStack());
        String haystack = hay.toString();

        Map<String, AbilityCooldownManager.CooldownInstance> active =
                AntigravityClient.ABILITY_COOLDOWN.getActive();

        cachedAvailable.clear();
        for (CustomCooldownSystem.CustomEnchant e : CustomCooldownSystem.REGISTRY) {
            if (active.containsKey(e.id)) continue; // on cooldown — skip

            String keyword = INV_KEYWORDS.getOrDefault(e.id, e.displayName.toLowerCase());
            if (haystack.contains(keyword)) {
                cachedAvailable.add(e);
            }
        }
    }

    /** Appends an item's display name and all lore lines, lower-cased, newline-separated. */
    private static void appendItemText(StringBuilder sb, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        sb.append(stack.getName().getString().toLowerCase()).append('\n');
        var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
        if (lore != null) {
            for (net.minecraft.text.Text line : lore.lines()) {
                sb.append(line.getString().toLowerCase());
                for (net.minecraft.text.Text sib : line.getSiblings()) {
                    sb.append(sib.getString().toLowerCase());
                }
                sb.append('\n');
            }
        }
    }

    // ── Alpha / gradient helpers ─────────────────────────────────────────

    /** Vertical gradient: colorTop at y, colorBottom at y+h. */
    public static void fillGradientV(DrawContext ctx, int x, int y, int w, int h,
                                      int colorTop, int colorBottom) {
        int at = (colorTop   >>> 24) & 0xFF, rt = (colorTop   >> 16) & 0xFF;
        int gt = (colorTop   >>  8) & 0xFF,  bt = (colorTop        ) & 0xFF;
        int ab = (colorBottom >>> 24) & 0xFF, rb = (colorBottom >> 16) & 0xFF;
        int gb = (colorBottom >>  8) & 0xFF,  bb = (colorBottom      ) & 0xFF;
        for (int row = 0; row < h; row++) {
            float t = (h <= 1) ? 0f : (float) row / (h - 1);
            int a = at + (int)((ab - at) * t);
            int r = rt + (int)((rb - rt) * t);
            int g = gt + (int)((gb - gt) * t);
            int b = bt + (int)((bb - bt) * t);
            ctx.fill(x, y + row, x + w, y + row + 1, (a << 24) | (r << 16) | (g << 8) | b);
        }
    }

    // ── Render ────────────────────────────────────────────────────────────

    public void render(DrawContext ctx, MinecraftClient mc) {
        if (!pos.enabled || mc == null || mc.getWindow() == null) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        TextRenderer tr = mc.textRenderer;
        float scale = getScale();

        // On-cooldown runes may expire between cache refreshes; filter cheaply here.
        Map<String, AbilityCooldownManager.CooldownInstance> active =
                AntigravityClient.ABILITY_COOLDOWN.getActive();
        List<CustomCooldownSystem.CustomEnchant> available = new ArrayList<>(cachedAvailable.size());
        for (CustomCooldownSystem.CustomEnchant e : cachedAvailable) {
            if (!active.containsKey(e.id)) available.add(e);
        }

        if (available.isEmpty()) return;

        int px = getX(sw);
        int py = getY(sh);
        int panelH = HEADER_H + available.size() * ENTRY_H + 3;

        var ms = ctx.getMatrices();
        ms.pushMatrix();
        ms.translate((float) px, (float) py);
        ms.scale(scale, scale);

        int pw = PANEL_W;

        // ── Background — Antigravity glass ────────────────────────────────
        ctx.fill(0, 0, pw, panelH, 0xBB0B0718);
        gborder(ctx, 0, 0, pw, panelH, org.cheetahv2.antigravity.client.gui.Theme.BORDER);
        ctx.fill(1, 1, pw - 1, 2, 0x12FFFFFF); // top glass highlight

        // ── Header ────────────────────────────────────────────────────────
        ctx.drawTextWithShadow(tr, "§d✦ §fAvailable", 5, 3,
                org.cheetahv2.antigravity.client.gui.Theme.ACCENT);
        ctx.fill(1, HEADER_H - 1, pw - 1, HEADER_H, 0x448D77E8); // separator

        // ── Entries ───────────────────────────────────────────────────────
        int ey = HEADER_H + 1;
        for (CustomCooldownSystem.CustomEnchant e : available) {
            int runeColor = e.color | 0xFF000000;

            // Left gradient bar
            fillGradientV(ctx, 1, ey, 2, ENTRY_H - 1, runeColor, 0xFF000000);

            // Checkmark
            ctx.drawTextWithShadow(tr, "§a✔", 5, ey + 2, 0xFF55FF55);

            // Rune icon
            ctx.drawTextWithShadow(tr, e.iconChar, 16, ey + 2, e.color);

            // Name (truncated)
            String name = e.displayName;
            int maxW = pw - 30;
            while (tr.getWidth(name) > maxW && name.length() > 3)
                name = name.substring(0, name.length() - 1);
            if (!name.equals(e.displayName)) name += "…";
            ctx.drawTextWithShadow(tr, name, 27, ey + 2,
                    org.cheetahv2.antigravity.client.gui.Theme.TEXT_HI);

            ey += ENTRY_H;
        }

        ms.popMatrix();
    }

    private void gborder(DrawContext ctx, int x, int y, int w, int h, int col) {
        ctx.fill(x, y, x + w, y + 1, col);
        ctx.fill(x, y + h - 1, x + w, y + h, col);
        ctx.fill(x, y, x + 1, y + h, col);
        ctx.fill(x + w - 1, y, x + w, y + h, col);
    }

    /** Estimated panel height for ghost box sizing in HudDragScreen. */
    public int estimatePanelH() {
        int estimate = Math.max(cachedAvailable.size(), 4);
        return (int)((HEADER_H + estimate * ENTRY_H + 3) * getScale());
    }

    public int getScaledWidth() { return (int)(PANEL_W * getScale()); }
}
