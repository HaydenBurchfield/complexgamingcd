package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.CodedTextRenderer;
import org.cheetahv2.antigravity.client.util.GradientUtil;
import org.cheetahv2.antigravity.client.util.SignatureManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Signature editor — /signsettings (or /sign gui).
 *
 * Left column  : saved presets (click to load, × to delete, + to create)
 * Right column : the selected preset's lines, one text field each
 * Bottom       : gradient helpers + send/save
 *
 * Lines are stored raw (with &#RRGGBB codes). Sending wraps each bare line
 * with "sll <n> " automatically — see SignatureManager.
 */
public class SignatureSettingsScreen extends Screen {

    private static final int MAX_LINES = 6;

    private String editingName = SignatureManager.getActiveName();
    private final List<TextFieldWidget> lineFields = new ArrayList<>();
    private TextFieldWidget nameField;
    private TextFieldWidget stopsField;   // gradient source: hex list / "item" / "rainbow"

    private String status = "";
    private long   statusAt = 0;

    // Scrolling (content column + preset list)
    private int scroll = 0;
    private int contentH = 0;
    private int listScroll = 0;
    private int listH = 0;

    // GLFW polling (matches the rest of the mod's screens)
    private boolean prevLmb = false;
    private double gx, gy;

    private static final class Btn {
        int x, y, w, h, tag; String label;
        Btn(int x, int y, int w, int h, int tag, String label) {
            this.x=x; this.y=y; this.w=w; this.h=h; this.tag=tag; this.label=label;
        }
    }
    private final List<Btn> buttons = new ArrayList<>();

    private static final int
            BTN_CLOSE = 0, BTN_SAVE = 1, BTN_SEND = 2, BTN_NEW = 3,
            BTN_ADD_LINE = 4, BTN_GRAD_ITEM = 5, BTN_GRAD_COPY = 6,
            BTN_AUTO_TOGGLE = 7, BTN_APPLY_ALL = 8, BTN_RAINBOW = 9, BTN_BOLD_TOGGLE = 10,
            BTN_CENTER = 11, BTN_START_DN = 12, BTN_START_UP = 13, BTN_CLEAR_TOGGLE = 14,
            BTN_START_AUTO = 15, BTN_CRATE_SIDE = 16,
            BTN_PRESET_BASE = 100,   // +i select
            BTN_DELETE_BASE = 200,   // +i delete
            BTN_LINE_DEL_BASE = 300, // +i remove line
            BTN_PADL_BASE = 400,     // +i one pad unit off
            BTN_PADR_BASE = 500;     // +i one pad unit on

    public SignatureSettingsScreen() {
        super(Text.literal("Antigravity — Signatures"));
    }

    @Override public boolean shouldPause() { return false; }

    private int pw() { return Math.min(520, width - 20); }
    private int ph() { return Math.min(320, height - 20); }
    private int px() { return (width - pw()) / 2; }
    private int py() { return (height - ph()) / 2; }
    private int listW() { return 130; }

    /** Scrollable content viewport (between the header and the action bar). */
    private int viewTop()    { return py() + 22; }
    private int viewBottom() { return py() + ph() - 46; }

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        lineFields.clear();
        clearChildren();

        int x = px(), y = py();
        int fx = x + listW() + 16;
        int fw = pw() - listW() - 32;

        nameField = new TextFieldWidget(textRenderer, fx, y + 34, fw, 14, Text.literal("name"));
        nameField.setMaxLength(32);
        nameField.setText(editingName);
        addDrawableChild(nameField);

        SignatureManager.Preset p = SignatureManager.get(editingName);

        // Gradient source field ("#10B1FF,#91FFFF" / "item" / "rainbow")
        stopsField = new TextFieldWidget(textRenderer, fx + 42, y + 52, Math.max(40, fw - 190), 14,
                Text.literal("gradient"));
        stopsField.setMaxLength(160);
        stopsField.setText(p == null ? "" : p.gradientStops);
        addDrawableChild(stopsField);

        List<String> lines = (p == null) ? new ArrayList<>() : new ArrayList<>(p.lines);
        if (lines.isEmpty()) lines.add("");

        int ly = y + 80;
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            TextFieldWidget f = new TextFieldWidget(textRenderer, fx, ly, fw - 56, 14,
                    Text.literal("line " + (i + 1)));
            f.setMaxLength(512);          // gradient art is long
            f.setText(lines.get(i));
            addDrawableChild(f);
            lineFields.add(f);
            ly += 18;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        poll();
        buttons.clear();

        int x = px(), y = py(), w = pw(), h = ph();

        ctx.fill(0, 0, width, height, Theme.BG_DIM);
        Theme.pill(ctx, x, y, w, h, Theme.BG, Theme.BORDER);
        ctx.fill(x + 2, y, w - 4, 2, Theme.ACCENT);
        Theme.stars(ctx, textRenderer, x + 4, y + 24, w - 8, h - 44, 30);

        // Header
        ctx.drawTextWithShadow(textRenderer, Theme.WORDMARK, x + 10, y + 9, Theme.TEXT_HI);
        ctx.drawTextWithShadow(textRenderer, "§8│ §7Signatures",
                x + 14 + textRenderer.getWidth(Theme.WORDMARK), y + 9, Theme.TEXT_MID);
        addBtn(ctx, new Btn(x + w - 20, y + 7, 13, 13, BTN_CLOSE, "×"));

        int vTop = viewTop(), vBot = viewBottom();

        // ── Preset list (own scroll) ─────────────────────────────────────
        ctx.drawTextWithShadow(textRenderer, "§7Presets", x + 12, y + 26, Theme.TEXT_MID);
        ctx.enableScissor(x + 4, y + 38, x + listW(), vBot);
        int ly = y + 40 - listScroll;
        int i = 0;
        for (String name : SignatureManager.names()) {
            boolean active = name.equalsIgnoreCase(SignatureManager.getActiveName());
            boolean editing = name.equalsIgnoreCase(editingName);
            if (ly > y + 24 && ly < vBot) {
                Theme.pill(ctx, x + 10, ly, listW() - 18, 15,
                        editing ? 0x50241850 : 0x25160D2E,
                        editing ? Theme.ACCENT : Theme.BORDER);
                ctx.drawTextWithShadow(textRenderer,
                        (active ? "§a✦ " : "§8✧ ") + "§f" + trim(name, listW() - 46),
                        x + 15, ly + 4, Theme.TEXT_HI);
                buttons.add(new Btn(x + 10, ly, listW() - 40, 15, BTN_PRESET_BASE + i, ""));
                addBtn(ctx, new Btn(x + listW() - 28, ly, 12, 15, BTN_DELETE_BASE + i, "§c×"));
            }
            ly += 18;
            i++;
        }
        if (ly + 2 < vBot) addBtn(ctx, new Btn(x + 10, ly + 2, listW() - 18, 15, BTN_NEW, "§a+ New Preset"));
        ctx.disableScissor();
        listH = (ly + 20) - (y + 40 - listScroll);

        // ── Editor column (scrolls as one) ───────────────────────────────
        int fx = x + listW() + 16;
        int fw = pw() - listW() - 32;
        SignatureManager.Preset cur = SignatureManager.get(editingName);
        int oy = -scroll;

        // Reposition the text fields to follow the scroll, hiding off-view ones
        if (nameField != null) {
            nameField.setY(y + 34 + oy);
            nameField.visible = nameField.getY() >= vTop - 2 && nameField.getY() + 14 <= vBot + 2;
        }
        if (stopsField != null) {
            stopsField.setY(y + 52 + oy);
            stopsField.visible = stopsField.getY() >= vTop - 2 && stopsField.getY() + 14 <= vBot + 2;
        }
        for (int k = 0; k < lineFields.size(); k++) {
            TextFieldWidget f = lineFields.get(k);
            f.setY(y + 80 + k * 18 + oy);
            f.visible = f.getY() >= vTop - 2 && f.getY() + 14 <= vBot + 2;
        }

        ctx.enableScissor(x + listW() + 6, vTop, x + w - 4, vBot);

        drawClipped(ctx, "§7Name", fx, y + 24 + oy, vTop, vBot, Theme.TEXT_MID);

        // Gradient row
        drawClipped(ctx, "§7Grad", fx, y + 56 + oy, vTop, vBot, Theme.TEXT_MID);
        int gbx = fx + fw - 144;
        addBtnClipped(ctx, new Btn(gbx,      y + 51 + oy, 44, 15, BTN_GRAD_ITEM, "§b← Item"), vTop, vBot);
        addBtnClipped(ctx, new Btn(gbx + 46, y + 51 + oy, 44, 15, BTN_RAINBOW,   "§dRainbow"), vTop, vBot);
        boolean auto = cur != null && cur.autoGradient;
        addBtnClipped(ctx, new Btn(gbx + 92, y + 51 + oy, 50, 15, BTN_AUTO_TOGGLE,
                auto ? "§aAuto ON" : "§8Auto OFF"), vTop, vBot);

        drawClipped(ctx, "§7Lines §8(auto-prefixed \"sll n\")", fx, y + 70 + oy, vTop, vBot, Theme.TEXT_MID);
        boolean gbold = cur == null || cur.gradientBold;
        addBtnClipped(ctx, new Btn(fx + fw - 98, y + 68 + oy, 46, 13, BTN_BOLD_TOGGLE,
                gbold ? "§fBold ✔" : "§8Bold ✘"), vTop, vBot);
        addBtnClipped(ctx, new Btn(fx + fw - 50, y + 68 + oy, 50, 13, BTN_APPLY_ALL, "§bApply All"), vTop, vBot);

        for (int k = 0; k < lineFields.size(); k++) {
            TextFieldWidget f = lineFields.get(k);
            int fy = f.getY();
            // Live length vs the 256-char command limit (prefix included)
            int len = f.getText().length() + 6; // ~"sll n "
            boolean over = len > GradientUtil.MAX_COMMAND_LENGTH;
            drawClipped(ctx, (over ? "§c" : len > 200 ? "§e" : "§8") + len,
                    fx - 22, fy + 4, vTop, vBot, Theme.TEXT_DIM);
            int bx = fx + f.getWidth() + 3;
            addBtnClipped(ctx, new Btn(bx, fy, 14, 14, BTN_LINE_DEL_BASE + k, "§c×"), vTop, vBot);
            // Manual centring nudge (±1 "&f " pad unit)
            addBtnClipped(ctx, new Btn(bx + 16, fy, 12, 14, BTN_PADL_BASE + k, "§8◄"), vTop, vBot);
            addBtnClipped(ctx, new Btn(bx + 29, fy, 12, 14, BTN_PADR_BASE + k, "§8►"), vTop, vBot);
        }

        int by = y + 80 + lineFields.size() * 18 + 4 + oy;
        if (lineFields.size() < MAX_LINES) {
            addBtnClipped(ctx, new Btn(fx, by, 60, 15, BTN_ADD_LINE, "§a+ Line"), vTop, vBot);
        }
        addBtnClipped(ctx, new Btn(fx + 64, by, 76, 15, BTN_CENTER, "§b⇔ Center All"), vTop, vBot);

        // Where the signature is written, and whether the previous one is
        // removed first (an item's enchant block sits above your lore).
        boolean autoStart = cur == null || cur.autoStart;
        int startLine = cur == null ? 1 : cur.startLine;
        drawClipped(ctx, "§7Start line", fx + 146, by + 4, vTop, vBot, Theme.TEXT_MID);
        drawClipped(ctx, autoStart ? "§aauto" : "§f" + startLine,
                fx + 200, by + 4, vTop, vBot, Theme.ACCENT);
        addBtnClipped(ctx, new Btn(fx + 212, by, 14, 15, BTN_START_DN, "§c−"), vTop, vBot);
        addBtnClipped(ctx, new Btn(fx + 228, by, 14, 15, BTN_START_UP, "§a+"), vTop, vBot);
        addBtnClipped(ctx, new Btn(fx + 246, by, 40, 15, BTN_START_AUTO, "§bAuto"), vTop, vBot);
        boolean clr = cur == null || cur.clearFirst;
        addBtnClipped(ctx, new Btn(fx + 290, by, 66, 15, BTN_CLEAR_TOGGLE,
                clr ? "§aReplace" : "§8Keep old"), vTop, vBot);

        // Above / below the "Crate Exclusive" line
        boolean above = cur != null && cur.aboveCrateLine;
        addBtnClipped(ctx, new Btn(fx, by + 18, 150, 15, BTN_CRATE_SIDE,
                above ? "§b↑ Above Crate Exclusive" : "§b↓ Below Crate Exclusive"), vTop, vBot);

        // ── Live colour preview (renders &#RRGGBB for real) ──────────────
        int prevY = by + 22;
        drawClipped(ctx, "§7Preview:", fx, prevY, vTop, vBot, Theme.TEXT_MID);
        prevY += 11;
        for (TextFieldWidget f : lineFields) {
            if (prevY >= vTop - 8 && prevY <= vBot) {
                CodedTextRenderer.draw(ctx, textRenderer, f.getText(),
                        fx, prevY, fw, Theme.TEXT_HI);
            }
            prevY += 10;
        }
        ctx.disableScissor();

        contentH = (prevY + 12) - (y + 24 + oy);

        // Scrollbar for the editor column
        int viewH = vBot - vTop;
        if (contentH > viewH) {
            int trackX = x + w - 6;
            ctx.fill(trackX, vTop, trackX + 3, vBot, Theme.SCROLL_TRACK);
            int thumbH = Math.max(14, viewH * viewH / contentH);
            int maxS = contentH - viewH;
            int thumbY = vTop + (viewH - thumbH) * Math.min(scroll, maxS) / Math.max(1, maxS);
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, Theme.SCROLL_THUMB);
        }

        // ── Actions ──────────────────────────────────────────────────────
        int ay = y + h - 40;
        addBtn(ctx, new Btn(fx,       ay, 96, 16, BTN_GRAD_ITEM, "§b✦ Grad ← Item"));
        addBtn(ctx, new Btn(fx + 102, ay, 96, 16, BTN_GRAD_COPY, "§b⎘ Copy Line 1"));
        addBtn(ctx, new Btn(x + w - 190, ay, 84, 16, BTN_SAVE, "§aSave"));
        addBtn(ctx, new Btn(x + w - 100, ay, 84, 16, BTN_SEND, "§d✦ Send"));

        // Status line
        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 4000) {
            ctx.drawTextWithShadow(textRenderer, status, fx, y + h - 20, Theme.TEXT_MID);
        }

        super.render(ctx, mx, my, delta);
    }

    private String trim(String s, int maxW) {
        while (textRenderer.getWidth(s) > maxW && s.length() > 3) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Draws a label only when it falls inside the scroll viewport. */
    private void drawClipped(DrawContext ctx, String text, int x, int y, int top, int bot, int color) {
        if (y < top - 8 || y > bot) return;
        ctx.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    /** Registers/draws a button only when it falls inside the scroll viewport. */
    private void addBtnClipped(DrawContext ctx, Btn b, int top, int bot) {
        if (b.y + b.h < top || b.y > bot) return; // fully outside: not clickable either
        addBtn(ctx, b);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        // Cursor over the preset list scrolls the list, otherwise the editor
        boolean overList = mouseX < px() + listW();
        if (overList) {
            int maxS = Math.max(0, listH - (viewBottom() - viewTop()));
            listScroll = Math.max(0, Math.min(maxS, listScroll - (int)(vAmount * 18)));
        } else {
            int maxS = Math.max(0, contentH - (viewBottom() - viewTop()));
            scroll = Math.max(0, Math.min(maxS, scroll - (int)(vAmount * 18)));
        }
        return true;
    }

    private void addBtn(DrawContext ctx, Btn b) {
        boolean hov = gx >= b.x && gx < b.x + b.w && gy >= b.y && gy < b.y + b.h;
        Theme.pill(ctx, b.x, b.y, b.w, b.h,
                hov ? Theme.BTN_BG_HOV : Theme.BTN_BG,
                hov ? Theme.ACCENT : Theme.BORDER);
        if (!b.label.isEmpty()) {
            int tw = textRenderer.getWidth(b.label);
            ctx.drawTextWithShadow(textRenderer, b.label,
                    b.x + (b.w - tw) / 2, b.y + (b.h - 7) / 2,
                    hov ? Theme.TEXT_HI : Theme.TEXT_MID);
        }
        buttons.add(b);
    }

    // ─────────────────────────────────────────────────────────────────────
    private void poll() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return;
        long win = mc.getWindow().getHandle();
        double sf = mc.getWindow().getScaleFactor();
        double[] rx = new double[1], ry = new double[1];
        GLFW.glfwGetCursorPos(win, rx, ry);
        gx = rx[0] / sf; gy = ry[0] / sf;
        boolean lmb = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (lmb && !prevLmb) {
            for (Btn b : buttons) {
                if (gx >= b.x && gx < b.x + b.w && gy >= b.y && gy < b.y + b.h) { click(b.tag); break; }
            }
        }
        prevLmb = lmb;
    }

    private void click(int tag) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (tag >= BTN_PADR_BASE) { padLine(tag - BTN_PADR_BASE, +1); return; }
        if (tag >= BTN_PADL_BASE) { padLine(tag - BTN_PADL_BASE, -1); return; }
        if (tag >= BTN_LINE_DEL_BASE) {
            int i = tag - BTN_LINE_DEL_BASE;
            if (i < lineFields.size() && lineFields.size() > 1) {
                List<String> cur = collectLines();
                cur.remove(i);
                SignatureManager.put(editingName, cur);
                init();
            }
            return;
        }
        if (tag >= BTN_DELETE_BASE) {
            List<String> names = new ArrayList<>(SignatureManager.names());
            int i = tag - BTN_DELETE_BASE;
            if (i < names.size()) {
                SignatureManager.delete(names.get(i));
                editingName = SignatureManager.getActiveName();
                init();
            }
            return;
        }
        if (tag >= BTN_PRESET_BASE) {
            List<String> names = new ArrayList<>(SignatureManager.names());
            int i = tag - BTN_PRESET_BASE;
            if (i < names.size()) {
                editingName = names.get(i);
                SignatureManager.setActive(editingName);
                init();
            }
            return;
        }

        switch (tag) {
            case BTN_CLOSE -> onClose();
            case BTN_NEW -> {
                String base = "preset";
                int n = 2;
                String name = base;
                while (SignatureManager.get(name) != null) name = base + n++;
                SignatureManager.put(name, List.of(""));
                editingName = name;
                SignatureManager.setActive(name);
                init();
            }
            case BTN_ADD_LINE -> {
                List<String> cur = collectLines();
                cur.add("");
                SignatureManager.put(editingName, cur);
                init();
            }
            case BTN_SAVE -> {
                String newName = nameField.getText().trim();
                if (newName.isEmpty()) newName = editingName;
                List<String> cur = collectLines();
                if (!newName.equalsIgnoreCase(editingName)) {
                    SignatureManager.delete(editingName);
                    editingName = newName;
                }
                SignatureManager.put(editingName, cur);
                persistGradientSettings();
                // Auto-gradient regenerates colours on save
                SignatureManager.applyAutoGradient(SignatureManager.get(editingName));
                SignatureManager.setActive(editingName);
                init(); // reload fields so regenerated colours are visible
                setStatus("§aSaved §f" + editingName + " §7(" + cur.size() + " lines)");
            }
            case BTN_SEND -> {
                List<String> cur = collectLines();
                SignatureManager.put(editingName, cur);
                persistGradientSettings();
                int sent = SignatureManager.send(editingName); // applies auto-gradient first
                init();
                setStatus(sent > 0 ? "§d✦ Sending " + sent + " line(s)…" : "§cNothing to send");
            }
            case BTN_GRAD_ITEM -> {
                // Sample the held item's colours, store them as the source and
                // immediately re-colour every line.
                if (mc != null && mc.player != null) {
                    var stops = GradientUtil.sampleItemName(mc.player.getMainHandStack());
                    if (stops.isEmpty()) { setStatus("§cHold an item with a coloured name"); return; }
                    stopsField.setText("item");
                    persistGradientSettings();
                    int n = applyGradientToAll(stops);
                    setStatus("§bMatched held item — re-coloured " + n + " line(s)");
                }
            }
            case BTN_RAINBOW -> {
                stopsField.setText("rainbow");
                persistGradientSettings();
                int n = applyGradientToAll(GradientUtil.rainbow(12));
                setStatus("§dRainbow applied to " + n + " line(s)");
            }
            case BTN_START_DN, BTN_START_UP -> {
                SignatureManager.Preset ps = SignatureManager.get(editingName);
                if (ps != null) {
                    ps.autoStart = false; // manual override
                    ps.startLine = Math.max(1, ps.startLine + (tag == BTN_START_UP ? 1 : -1));
                    SignatureManager.saveNow();
                    setStatus("§7Fixed start line §f" + ps.startLine + " §8(Auto to go back)");
                }
            }
            case BTN_START_AUTO -> {
                // Read the held item: put the signature on the first line
                // BELOW the enchant block.
                var cfg = org.cheetahv2.antigravity.client.util.LoreCommandConfig.class;
                var lore = org.cheetahv2.antigravity.client.util.LoreCommandConfig.heldLoreLines();
                if (lore.isEmpty()) { setStatus("§cHold the item you sign, then press Auto"); return; }
                int custom = org.cheetahv2.antigravity.client.util.LoreCommandConfig.heldCustomLoreCount();
                int ench   = org.cheetahv2.antigravity.client.util.LoreCommandConfig.heldEnchantOffset();
                SignatureManager.Preset ps = SignatureManager.get(editingName);
                if (ps != null) {
                    // Back to per-item detection: line numbers count the
                    // enchant lines, so writing starts right after the block.
                    ps.autoStart = true;
                    ps.startLine = ench + 1;
                    SignatureManager.saveNow();
                    setStatus("§bAuto start §7— this item: §f" + ps.startLine
                            + " §8(" + ench + " enchant, " + custom + " custom, "
                            + lore.size() + " total)");
                }
            }
            case BTN_CRATE_SIDE -> {
                SignatureManager.Preset ps = SignatureManager.get(editingName);
                if (ps != null) {
                    ps.aboveCrateLine = !ps.aboveCrateLine;
                    SignatureManager.saveNow();
                    setStatus(ps.aboveCrateLine
                            ? "§bSignature goes ABOVE the Crate Exclusive line"
                            : "§bSignature goes BELOW the Crate Exclusive line");
                }
            }
            case BTN_CLEAR_TOGGLE -> {
                SignatureManager.Preset ps = SignatureManager.get(editingName);
                if (ps != null) {
                    ps.clearFirst = !ps.clearFirst;
                    SignatureManager.saveNow();
                    setStatus(ps.clearFirst
                            ? "§aLeftover lines from a longer signature get removed"
                            : "§7Leftover lines are left in place");
                }
            }
            case BTN_CENTER -> {
                // Pad every line so they centre against the widest one
                List<String> centered = CodedTextRenderer.centerAll(textRenderer, collectLines());
                for (int k = 0; k < lineFields.size() && k < centered.size(); k++) {
                    lineFields.get(k).setText(centered.get(k));
                }
                SignatureManager.put(editingName, collectLines());
                setStatus("§bCentered " + centered.size() + " line(s)");
            }
            case BTN_APPLY_ALL -> {
                persistGradientSettings();
                var stops = SignatureManager.resolveStops(SignatureManager.get(editingName));
                if (stops.isEmpty()) { setStatus("§cSet a gradient first (hex list, item, or rainbow)"); return; }
                int n = applyGradientToAll(stops);
                setStatus("§bRe-coloured " + n + " line(s)");
            }
            case BTN_AUTO_TOGGLE -> {
                SignatureManager.Preset p2 = SignatureManager.get(editingName);
                if (p2 != null) {
                    p2.autoGradient = !p2.autoGradient;
                    persistGradientSettings();
                    setStatus(p2.autoGradient
                            ? "§aAuto-gradient ON §7— regenerates on every save/send"
                            : "§7Auto-gradient off");
                }
            }
            case BTN_BOLD_TOGGLE -> {
                SignatureManager.Preset p3 = SignatureManager.get(editingName);
                if (p3 != null) {
                    p3.gradientBold = !p3.gradientBold;
                    persistGradientSettings();
                    setStatus(p3.gradientBold ? "§fGradient bold on" : "§7Gradient bold off");
                }
            }
            case BTN_GRAD_COPY -> {
                if (mc != null && !lineFields.isEmpty()) {
                    mc.keyboard.setClipboard(lineFields.get(0).getText());
                    setStatus("§bLine 1 copied to clipboard");
                }
            }
        }
    }

    private List<String> collectLines() {
        List<String> out = new ArrayList<>();
        for (TextFieldWidget f : lineFields) out.add(f.getText());
        return out;
    }

    /**
     * Manual centring nudge: adds/removes one "&f " pad unit on a line.
     * Auto-centre measures with the client font, which can disagree with a
     * server resource pack's custom glyph widths — this corrects by eye.
     */
    private void padLine(int i, int delta) {
        if (i < 0 || i >= lineFields.size()) return;
        TextFieldWidget f = lineFields.get(i);
        String text = f.getText();
        if (delta > 0) {
            f.setText("&f " + text);
        } else {
            String stripped = CodedTextRenderer.stripOnePad(text);
            if (stripped.equals(text)) { setStatus("§7No padding left on line " + (i + 1)); return; }
            f.setText(stripped);
        }
        SignatureManager.put(editingName, collectLines());
        setStatus("§bLine " + (i + 1) + " pad " + (delta > 0 ? "+1" : "-1"));
    }

    /** Writes the gradient source/flags from the UI back onto the preset. */
    private void persistGradientSettings() {
        SignatureManager.Preset p = SignatureManager.get(editingName);
        if (p == null) return;
        if (stopsField != null) p.gradientStops = stopsField.getText().trim();
        SignatureManager.saveNow();
    }

    /**
     * Re-colours every line in the editor from the given stops, automatically
     * coarsening each gradient so the resulting command still fits Minecraft's
     * 256-character limit.
     */
    private int applyGradientToAll(List<Integer> stops) {
        SignatureManager.Preset p = SignatureManager.get(editingName);
        boolean bold = p == null || p.gradientBold;
        int budget = GradientUtil.MAX_COMMAND_LENGTH - 8; // room for "sll n "
        int n = 0;
        for (TextFieldWidget f : lineFields) {
            if (GradientUtil.stripCodes(f.getText()).isBlank()) continue;
            // Pad-preserving: re-colouring must not wipe "&f &f " centring
            f.setText(GradientUtil.applyPreservingPad(f.getText(), stops, bold, budget));
            n++;
        }
        if (n > 0) SignatureManager.put(editingName, collectLines());
        return n;
    }

    private void setStatus(String s) { status = s; statusAt = System.currentTimeMillis(); }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(input);
    }

    public void onClose() {
        SignatureManager.put(editingName, collectLines());
        MinecraftClient.getInstance().setScreen(null);
    }
}
