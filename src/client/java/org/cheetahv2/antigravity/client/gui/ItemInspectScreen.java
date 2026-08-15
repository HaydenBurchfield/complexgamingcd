package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.cheetahv2.antigravity.client.AntigravityClient;

import java.util.ArrayList;
import java.util.List;

public class ItemInspectScreen extends Screen {

    // Unified Antigravity theme (see gui/Theme)
    private static final int
            BG          = Theme.GLASS_BG,
            GLASS_PANEL = 0x40160D2E,
            BORDER      = Theme.BORDER_SOFT,
            SHEEN       = Theme.SHEEN,
            ACCENT_GOLD = Theme.ACCENT,
            ACCENT_LIME = Theme.ACCENT_ALT,
            TXT_DIM     = Theme.TEXT_DIM,
            TXT_WHITE   = Theme.TEXT_HI,
            TAB_ACT     = 0x60A77BFF,
            TAB_HOV     = 0x30A77BFF,
            BTN_COPY    = 0xFF1C3020,
            BTN_COPY_B  = Theme.GOOD,
            ROW_EVEN    = 0x18FFFFFF,
            ROW_ODD     = 0x08FFFFFF;

    private static final int PW = 420, PH = 300;
    private static final int TAB_H = 18, HEADER_H = 26;
    private static final int COPY_BTN_W = 38, ROW_H = 11, SCROLL_BAR_W = 4;
    private static final String[] TAB_LABELS = { "\u00A7eLore", "\u00A7bStats", "\u00A77NBT" };

    private final ItemStack stack;
    private int activeTab = 0;
    private int scrollLore = 0, scrollStats = 0, scrollNbt = 0;
    private boolean draggingScroll = false;
    private double dragStartY = 0;
    private int dragStartScroll = 0;
    private List<String> loreLines;
    private List<Text>   loreTexts;      // NEW — for accurate hex color display
    private List<String> loreCopyLines;  // NEW — &#RRGGBB format for clipboard
    private List<String> statsLines;
    private List<String> nbtLines;
    private String styledName;           // &#RRGGBB copy string for item name
    private long copiedUntil = 0;
    private long copiedNameUntil = 0;

    // GLFW polling state — tracked frame-to-frame inside render()
    private boolean prevLeftDown = false;

    public static void openForItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        MinecraftClient.getInstance().setScreen(new ItemInspectScreen(stack));
    }

    public ItemInspectScreen(ItemStack stack) {
        super(Text.literal("ItemInspect"));
        this.stack = stack.copy();
        buildCache();
    }

    // ----------------------------------------------------------------
    //  Keyboard — KeyInput is the correct API in this MC version
    // ----------------------------------------------------------------
    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { MinecraftClient.getInstance().setScreen(null); return true; }
        if (input.key() == GLFW.GLFW_KEY_1) { activeTab = 0; return true; }
        if (input.key() == GLFW.GLFW_KEY_2) { activeTab = 1; return true; }
        if (input.key() == GLFW.GLFW_KEY_3) { activeTab = 2; return true; }
        return super.keyPressed(input);
    }

    // ----------------------------------------------------------------
    //  Scroll wheel — signature unchanged, no issue
    // ----------------------------------------------------------------
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        setActiveScroll(activeScroll() - (int) verticalAmount);
        return true;
    }

    @Override
    public boolean shouldPause() { return false; }

    // ----------------------------------------------------------------
    //  Render — also drives all mouse click/drag logic via GLFW polling.
    //  We never override mouseClicked / mouseDragged / mouseReleased,
    //  sidestepping the new Click-wrapper API entirely.
    // ----------------------------------------------------------------
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {

        // --- poll GLFW for left-button state ---
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        boolean leftDown = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (leftDown && !prevLeftDown) {
            // fresh click this frame
            onMouseClick(mx, my);
        } else if (leftDown && draggingScroll) {
            // held down and dragging scrollbar
            int total = activeLines().size(), visible = visibleRows();
            if (total > visible) {
                double ratio = (double)(total - visible) / Math.max(1, contentH() - 4);
                setActiveScroll(dragStartScroll + (int)((my - dragStartY) * ratio));
            }
        } else if (!leftDown) {
            draggingScroll = false;
        }
        prevLeftDown = leftDown;

        // --- drawing ---
        int px = px(), py = py();
        TextRenderer tr = textRenderer;

        ctx.fill(0, 0, width, height, 0x70010108);
        ctx.fill(px, py, px + PW, py + PH, BG);
        gborder(ctx, px, py, PW, PH, BORDER);
        ctx.fill(px + 1, py + 1, px + PW - 1, py + 2, SHEEN);

        ctx.fill(px, py, px + PW, py + HEADER_H, GLASS_PANEL);
        gborder(ctx, px, py, PW, HEADER_H, BORDER);
        ctx.fill(px + 1, py + 1, px + 3, py + HEADER_H - 1, ACCENT_GOLD);
        ctx.drawItem(stack, px + 5, py + 4);

        ctx.drawTextWithShadow(tr, "\u2726 \u00A77Item Inspect \u00A78| ", px + 24, py + 9, TXT_WHITE);
        int prefixW = tr.getWidth("\u2726 \u00A77Item Inspect \u00A78| ");
        ctx.drawTextWithShadow(tr, stack.getName(), px + 24 + prefixW, py + 9, TXT_WHITE);

// "Copy Name" button — right side of header, left of ESC
        String cnLabel = System.currentTimeMillis() < copiedNameUntil ? "\u00A7aCopied!" : "\u00A7bName";
        int cnW = tr.getWidth(cnLabel) + 8, cnH = 12;
        int cnX = px + PW - cnW - 22, cnY = py + 7;
        boolean cnHov = mx >= cnX && mx < cnX + cnW && my >= cnY && my < cnY + cnH;
        ctx.fill(cnX, cnY, cnX + cnW, cnY + cnH, cnHov ? 0x605AB4FF : 0x201C3A);
        gborder(ctx, cnX, cnY, cnW, cnH, cnHov ? 0xFF5AB4FF : 0x30FFFFFF);
        ctx.drawCenteredTextWithShadow(tr, cnLabel, cnX + cnW / 2, cnY + 2, TXT_WHITE);

        String esc = "\u00A78ESC";
        ctx.drawTextWithShadow(tr, esc, px + PW - tr.getWidth(esc) - 6, py + 9, TXT_DIM);

        int tabW = PW / TAB_LABELS.length;
        int tabY = py + HEADER_H;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            int tx = px + i * tabW;
            boolean active = i == activeTab;
            boolean hov    = mx >= tx && mx < tx + tabW && my >= tabY && my < tabY + TAB_H;
            ctx.fill(tx, tabY, tx + tabW, tabY + TAB_H, active ? TAB_ACT : hov ? TAB_HOV : 0x10FFFFFF);
            gborder(ctx, tx, tabY, tabW, TAB_H, active ? 0x60FFFFFF : 0x15FFFFFF);
            if (active) ctx.fill(tx, tabY + TAB_H - 1, tx + tabW, tabY + TAB_H, ACCENT_GOLD);
            int lw = tr.getWidth(TAB_LABELS[i]);
            ctx.drawTextWithShadow(tr, TAB_LABELS[i], tx + (tabW - lw) / 2, tabY + 5, active ? ACCENT_GOLD : TXT_DIM);
        }

        int cy = contentY(), cw = PW - 12, ch = contentH();
        int cx = px + 6;
        ctx.fill(cx, cy, cx + cw, cy + ch, 0x18000000);
        gborder(ctx, cx, cy, cw, ch, 0x15FFFFFF);

        ctx.enableScissor(cx + 1, cy + 1, cx + cw - 1, cy + ch - 1);
        List<String> lines = activeLines();
        int scroll = activeScroll();
        int displayCw = cw - SCROLL_BAR_W - 6;
        int ry = cy + 3;
        for (int i = scroll; i < lines.size() && (ry - cy) < ch - 2; i++) {
            String raw = lines.get(i);
            ctx.fill(cx + 1, ry - 1, cx + displayCw + 1, ry + ROW_H - 1, (i % 2 == 0) ? ROW_EVEN : ROW_ODD);
            if (activeTab == 0 && i < loreTexts.size()) {
                ctx.drawTextWithShadow(tr, loreTexts.get(i), cx + 3, ry, TXT_WHITE);
            } else {
                ctx.drawTextWithShadow(tr, raw, cx + 3, ry, TXT_WHITE);
            }
            if (activeTab == 0) {
                int bx = cx + displayCw - COPY_BTN_W + 2;
                boolean bhov = mx >= bx && mx < bx + COPY_BTN_W && my >= ry - 1 && my < ry + ROW_H;
                ctx.fill(bx, ry - 1, bx + COPY_BTN_W, ry + ROW_H - 1, bhov ? 0x604AE87A : BTN_COPY);
                gborder(ctx, bx, ry - 1, COPY_BTN_W, ROW_H, bhov ? BTN_COPY_B : 0x30FFFFFF);
                ctx.drawTextWithShadow(tr, "\u00A7aCopy", bx + 3, ry, bhov ? ACCENT_LIME : TXT_DIM);
            }
            ry += ROW_H;
        }
        ctx.disableScissor();

        int total = lines.size(), visible = visibleRows();
        if (total > visible) {
            int sbX = cx + cw - SCROLL_BAR_W - 1, sbH = ch - 4;
            int thumbH = Math.max(10, sbH * visible / total);
            int thumbY = cy + 2 + (sbH - thumbH) * scroll / (total - visible);
            ctx.fill(sbX, cy + 2, sbX + SCROLL_BAR_W, cy + ch - 2, 0x20FFFFFF);
            ctx.fill(sbX, thumbY, sbX + SCROLL_BAR_W, thumbY + thumbH, 0x90FFFFFF);
        }

        if (activeTab == 0) {
            int bw2 = 90, bh2 = 14, bx2 = px + PW - bw2 - 6, by2 = py + PH - bh2 - 4;
            boolean bhov2 = mx >= bx2 && mx < bx2 + bw2 && my >= by2 && my < by2 + bh2;
            ctx.fill(bx2, by2, bx2 + bw2, by2 + bh2, bhov2 ? 0x604AE87A : 0x201C3020);
            gborder(ctx, bx2, by2, bw2, bh2, bhov2 ? BTN_COPY_B : 0x30FFFFFF);
            ctx.drawTextWithShadow(tr, "\u00A7aCopy All Lore", bx2 + 4, by2 + 3, bhov2 ? ACCENT_LIME : TXT_DIM);
        }

        if (System.currentTimeMillis() < copiedUntil) {
            long rem = copiedUntil - System.currentTimeMillis();
            int alpha = (rem < 400) ? (int)(rem * 255 / 400) : 255;
            String msg = "\u00A7a\u2714 Copied!";
            int mw = tr.getWidth(msg);
            ctx.drawTextWithShadow(tr, msg, px + PW / 2 - mw / 2, py + PH - 18, (alpha << 24) | 0x4AE87A);
        }

        super.render(ctx, mx, my, delta);
    }

    // ----------------------------------------------------------------
    //  Click logic (called from render on fresh left-click)
    // ----------------------------------------------------------------
    private void onMouseClick(int mx, int my) {
        int px = px(), py = py();

        // Copy Name button hit test
        String cnLabel2 = "\u00A7bName";
        int cnW2 = textRenderer.getWidth(cnLabel2) + 8, cnH2 = 12;
        int cnX2 = px() + PW - cnW2 - 22, cnY2 = py() + 7;
        if (mx >= cnX2 && mx < cnX2 + cnW2 && my >= cnY2 && my < cnY2 + cnH2) {
            copyToClipboard(styledName);
            copiedNameUntil = System.currentTimeMillis() + 1200;
            return;
        }

        // Tab bar
        int tabW = PW / TAB_LABELS.length;
        int tabY = py + HEADER_H;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            int tx = px + i * tabW;
            if (mx >= tx && mx < tx + tabW && my >= tabY && my < tabY + TAB_H) {
                activeTab = i;
                return;
            }
        }

        // Per-row copy buttons (Lore tab only)
        if (activeTab == 0) {
            int cx = px + 6, cy = contentY(), cw = PW - 12;
            int displayCw = cw - SCROLL_BAR_W - 6;
            int ry = cy + 3;
            for (int i = scrollLore; i < loreLines.size() && (ry - cy) < contentH() - 2; i++) {
                int bx = cx + displayCw - COPY_BTN_W + 2;
                if (mx >= bx && mx < bx + COPY_BTN_W && my >= ry - 1 && my < ry + ROW_H) {
                    copyToClipboard(loreCopyLines.get(i));
                    return;
                }
                ry += ROW_H;
            }
            // Copy All Lore button
            int bw2 = 90, bh2 = 14, bx2 = px + PW - bw2 - 6, by2 = py + PH - bh2 - 4;
            if (mx >= bx2 && mx < bx2 + bw2 && my >= by2 && my < by2 + bh2) {
                StringBuilder sb = new StringBuilder();
                for (String l : loreCopyLines) sb.append(l).append('\n');
                copyToClipboard(sb.toString().trim());
                return;
            }
        }

        // Scrollbar drag start
        int cx = px + 6, cy = contentY(), cw = PW - 12, ch = contentH();
        int sbX = cx + cw - SCROLL_BAR_W - 1;
        if (mx >= sbX && mx < sbX + SCROLL_BAR_W + 2 && my >= cy && my < cy + ch) {
            draggingScroll = true;
            dragStartY = my;
            dragStartScroll = activeScroll();
        }
    }

    // ----------------------------------------------------------------
    //  Data builders
    // ----------------------------------------------------------------
    private void buildCache() {
        loreLines = new ArrayList<>();
        styledName = toCopyString(stack.getName());

        loreLines     = new ArrayList<>();
        loreTexts     = new ArrayList<>();
        loreCopyLines = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                loreTexts.add(line);
                loreLines.add(line.getString());        // plain — used for row count / widths
                loreCopyLines.add(toCopyString(line));  // &#RRGGBB — used for clipboard
            }
        }
        if (loreLines.isEmpty()) {
            loreLines.add("\u00A78(no lore)");
            loreTexts.add(Text.literal("\u00A78(no lore)"));
            loreCopyLines.add("(no lore)");
        }

        // ── Crate Puller injection (top of Lore tab) ─────────────────────
        if (AntigravityClient.HUD_SETTINGS.showCratePuller) {
            String puller = AntigravityClient.extractCratePuller(stack);
            if (puller != null) {
                long ts = AntigravityClient.extractPullTimestamp(stack);
                String datePart = ts > 0
                        ? " \u00A78(\u00A77" + AntigravityClient.formatPullDate(ts) + "\u00A78)" : "";
                String pullerLine = "\u00A78[\u00A76Crate Pull\u00A78] \u00A77Pulled by: \u00A7e" + puller + datePart;
                String plainPuller = "[Crate Pull] Pulled by: " + puller
                        + (ts > 0 ? " (" + AntigravityClient.formatPullDate(ts) + ")" : "");
                // Separator between injected info and real lore (only if there is real lore below)
                boolean hasRealLore = lore != null && !lore.lines().isEmpty();
                if (hasRealLore) {
                    loreLines.add(0, "\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
                    loreTexts.add(0, Text.literal("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"));
                    loreCopyLines.add(0, "---------");
                }
                loreLines.add(0, pullerLine);
                loreTexts.add(0, Text.literal(pullerLine));
                loreCopyLines.add(0, plainPuller);
            }
        }

        statsLines = new ArrayList<>();
        statsLines.add("\u00A78\u25B8 \u00A77Item ID: \u00A7f" + stack.getItem().getTranslationKey());
        statsLines.add("\u00A78\u25B8 \u00A77Count: \u00A7e" + stack.getCount());

        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants != null && !enchants.isEmpty()) {
            statsLines.add("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            statsLines.add("\u00A76\u26A1 \u00A7eEnchantments");
            for (var entry : enchants.getEnchantmentEntries()) {
                RegistryEntry<Enchantment> e = entry.getKey();
                statsLines.add("  \u00A78\u2022 \u00A7b" + e.value().description().getString() + " \u00A77" + toRoman(entry.getIntValue()));
            }
        }

        ItemEnchantmentsComponent stored = stack.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored != null && !stored.isEmpty()) {
            statsLines.add("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            statsLines.add("\u00A7d\u26A1 \u00A7dStored Enchants");
            for (var entry : stored.getEnchantmentEntries()) {
                RegistryEntry<Enchantment> e = entry.getKey();
                statsLines.add("  \u00A78\u2022 \u00A75" + e.value().description().getString() + " \u00A77" + toRoman(entry.getIntValue()));
            }
        }

        if (stack.isDamageable()) {
            statsLines.add("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            int dur = stack.getMaxDamage() - stack.getDamage();
            statsLines.add("\u00A78\u25B8 \u00A77Durability: \u00A7a" + dur + " \u00A77/ \u00A7a" + stack.getMaxDamage());
        }

        if (stack.get(DataComponentTypes.UNBREAKABLE) != null) {
            statsLines.add("\u00A78\u25B8 \u00A7cUnbreakable");
        }

        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) {
            statsLines.add("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            statsLines.add("\u00A78\u25B8 \u00A77Custom Name: " + customName.getString());
        }

        var cmd = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd != null) {
            statsLines.add("\u00A78\u25B8 \u00A77Custom Model: \u00A7e" + cmd);
        }

        var attrs = stack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        if (attrs != null && !attrs.modifiers().isEmpty()) {
            statsLines.add("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            statsLines.add("\u00A7c\u2694 \u00A7cAttribute Modifiers");
            attrs.modifiers().forEach(entry -> {
                EntityAttributeModifier mod = entry.modifier();
                String op = switch (mod.operation()) {
                    case ADD_VALUE -> "+";
                    case ADD_MULTIPLIED_BASE -> "xBase ";
                    case ADD_MULTIPLIED_TOTAL -> "xTotal ";
                };
                statsLines.add("  \u00A78\u2022 \u00A7f" + entry.attribute().value().getTranslationKey() + ": \u00A7a" + op + String.format("%.2f", mod.value()));
            });
        }

        nbtLines = new ArrayList<>();
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        if (customData != null) {
            NbtCompound tag = customData.copyNbt();
            if (tag.isEmpty()) nbtLines.add("\u00A78(custom_data is empty)");
            else flattenNbt(tag, nbtLines, 0);
        } else {
            nbtLines.add("\u00A78(no custom_data on this item)");
        }
        nbtLines.add("\u00A78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
        nbtLines.add("\u00A77Components Present:");
        stack.getComponents().forEach(entry -> nbtLines.add("  \u00A78\u2022 \u00A79" + entry.type().toString()));
    }

    private void flattenNbt(NbtCompound tag, List<String> out, int depth) {
        String indent = "  ".repeat(depth);
        for (String key : tag.getKeys()) {
            NbtElement val = tag.get(key);
            if (val instanceof NbtCompound sub) {
                out.add(indent + "\u00A7b" + key + "\u00A77: {");
                flattenNbt(sub, out, depth + 1);
                out.add(indent + "\u00A77}");
            } else {
                String vs = val.toString();
                if (vs.length() > 60) vs = vs.substring(0, 57) + "\u00A78...";
                out.add(indent + "\u00A7b" + key + "\u00A77: \u00A7f" + vs);
            }
        }
    }

    private static String toCopyString(Text text) {
        StringBuilder sb = new StringBuilder();
        text.visit((style, str) -> {
            if (str.isEmpty()) return java.util.Optional.empty();

            net.minecraft.text.TextColor tc = style.getColor();
            if (tc != null) {
                boolean matched = false;
                for (net.minecraft.util.Formatting f : net.minecraft.util.Formatting.values()) {
                    if (f.isColor() && f.getColorValue() != null
                            && f.getColorValue().equals(tc.getRgb())) {
                        sb.append('&').append(f.getCode());
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    // Hex color — &#RRGGBB format
                    sb.append(String.format("&#%06X", tc.getRgb()));
                }
            }
            if (style.isBold())          sb.append("&l");
            if (style.isItalic())        sb.append("&o");
            if (style.isUnderlined())    sb.append("&n");
            if (style.isStrikethrough()) sb.append("&m");
            if (style.isObfuscated())    sb.append("&k");
            sb.append(str);
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return sb.toString();
    }

    private static String toRoman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV";
            case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII";
            case 9 -> "IX"; case 10 -> "X"; default -> String.valueOf(n);
        };
    }

    private List<String> activeLines() {
        return switch (activeTab) { case 0 -> loreLines; case 1 -> statsLines; default -> nbtLines; };
    }

    private int activeScroll() {
        return switch (activeTab) { case 0 -> scrollLore; case 1 -> scrollStats; default -> scrollNbt; };
    }

    private void setActiveScroll(int v) {
        int max = Math.max(0, activeLines().size() - visibleRows());
        v = Math.max(0, Math.min(v, max));
        switch (activeTab) { case 0 -> scrollLore = v; case 1 -> scrollStats = v; default -> scrollNbt = v; }
    }

    private int px()          { return (width  - PW) / 2; }
    private int py()          { return (height - PH) / 2; }
    private int contentY()    { return py() + HEADER_H + TAB_H + 2; }
    private int contentH()    { return PH - HEADER_H - TAB_H - 2 - 6; }
    private int visibleRows() { return Math.max(1, (contentH() - 4) / ROW_H); }

    private void copyToClipboard(String text) {
        MinecraftClient.getInstance().keyboard.setClipboard(text);
        copiedUntil = System.currentTimeMillis() + 1200;
    }

    private static void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }
}