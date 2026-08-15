package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.ClientCommandQueue;
import org.cheetahv2.antigravity.client.util.CodedTextRenderer;
import org.cheetahv2.antigravity.client.util.GradientUtil;
import org.cheetahv2.antigravity.client.util.GuiDumper;
import org.cheetahv2.antigravity.client.util.LoreCommandConfig;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * LoreEditorScreen — /lore
 *
 * Reads the HELD item's name + lore (colours preserved as &#RRGGBB/&l), lets
 * you edit / add / delete / reorder / centre lines, then applies the changes
 * with the server's own commands (sll / rll / ssl — see LoreCommandConfig).
 *
 * What you see in the list IS the final lore: lines you delete here get an
 * /rll, lines you keep get an /sll at their new index. Nothing is sent until
 * Apply, and the exact command list is previewed first.
 *
 * Enchant lines can be locked (🔒) so they are never rewritten — toggleable,
 * because sometimes you DO want to rebuild the whole lore by hand.
 */
public class LoreEditorScreen extends Screen {

    /** Plenty of room: full enchant lore easily runs past 30 lines. */
    private static final int MAX_LINES = 40;

    private final List<TextFieldWidget> lineFields = new ArrayList<>();
    /** Original 1-based lore index each row came from (-1 = newly added row). */
    private final List<Integer> rowOrigIndex = new ArrayList<>();
    /** The item's lore exactly as read, used to diff against the edited rows. */
    private List<String> originalLines = new ArrayList<>();
    private TextFieldWidget nameField;

    private String  itemLabel     = "(no item)";
    private int     originalCount = 0;   // lore lines present on the item when read
    private int     hiddenTail    = 0;   // lines beyond MAX_LINES we did NOT load (never touched)
    private boolean renameEnabled = false;
    private boolean protectEnchants = true;

    private String status = "";
    private long   statusAt = 0;

    private int scroll = 0;
    private int contentH = 0;
    /** Rows that can't be written because lines cannot be created. */
    private int overflowLines = 0;

    private int viewTop()    { return py() + 22; }
    private int viewBottom() { return py() + ph() - 46; }

    // GLFW polling
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
            BTN_CLOSE = 0, BTN_READ = 1, BTN_APPLY = 2, BTN_ADD = 3,
            BTN_COPY_CMDS = 4, BTN_RENAME_TOGGLE = 5, BTN_GRAD = 6, BTN_CENTER = 7,
            BTN_CLEAR = 8, BTN_PROTECT_TOGGLE = 9,
            BTN_DEL_BASE = 100, BTN_UP_BASE = 200, BTN_DOWN_BASE = 300,
            BTN_PADL_BASE = 400, BTN_PADR_BASE = 500;

    public LoreEditorScreen() {
        super(Text.literal("Antigravity — Lore Editor"));
    }

    @Override public boolean shouldPause() { return false; }

    private int pw() { return Math.min(540, width - 20); }
    private int ph() { return Math.min(320, height - 20); }
    private int px() { return (width - pw()) / 2; }
    private int py() { return (height - ph()) / 2; }

    private int fieldX() { return px() + 32; }
    private int fieldW() { return pw() - 132; } // room for ▲▼× and the pad nudges

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        List<String> existing = collectLinesSafe();
        List<Integer> idx = new ArrayList<>(rowOrigIndex);
        String existingName = nameField != null ? nameField.getText() : "";
        buildWidgets(existingName, existing, idx);
    }

    /** (Re)creates the name field + one text field per lore line. */
    private void buildWidgets(String name, List<String> lines, List<Integer> origIdx) {
        clearChildren();
        lineFields.clear();
        rowOrigIndex.clear();

        int y = py();
        nameField = new TextFieldWidget(textRenderer, px() + 58, y + 30, pw() - 140, 14,
                Text.literal("name"));
        nameField.setMaxLength(256);
        nameField.setText(name == null ? "" : name);
        addDrawableChild(nameField);

        int ly = y + 62;
        for (int i = 0; i < Math.min(MAX_LINES, lines.size()); i++) {
            TextFieldWidget f = new TextFieldWidget(textRenderer, fieldX(), ly, fieldW(), 14,
                    Text.literal("lore " + (i + 1)));
            f.setMaxLength(512);
            f.setText(lines.get(i) == null ? "" : lines.get(i));
            addDrawableChild(f);
            lineFields.add(f);
            rowOrigIndex.add(origIdx != null && i < origIdx.size() ? origIdx.get(i) : -1);
            ly += 17;
        }
    }

    private List<String> collectLinesSafe() {
        List<String> out = new ArrayList<>();
        for (TextFieldWidget f : lineFields) out.add(f.getText());
        return out;
    }

    /** Loads the held item's name + lore into the editor. */
    private void readHeldItem() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        ItemStack stack = mc.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) {
            setStatus("§cHold an item in your main hand first");
            return;
        }

        itemLabel = GradientUtil.stripCodes(stack.getName().getString());

        List<String> lines = new ArrayList<>();
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) lines.add(GuiDumper.toCodes(line));
        }
        originalCount = lines.size();
        // Anything past MAX_LINES is left completely alone — it is neither
        // shown nor deleted (silently truncating used to wipe the tail).
        hiddenTail = Math.max(0, originalCount - MAX_LINES);
        if (hiddenTail > 0) lines = new ArrayList<>(lines.subList(0, MAX_LINES));

        originalLines = new ArrayList<>(lines);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) idx.add(i + 1); // 1-based lore index

        buildWidgets(GuiDumper.toCodes(stack.getName()), lines, idx);
        scroll = 0;
        setStatus("§aLoaded §f" + lines.size() + "§a line(s) from §f" + itemLabel
                + (hiddenTail > 0 ? " §8(+" + hiddenTail + " beyond the editor, untouched)" : ""));
    }

    /**
     * Rows at or above the last enchant line are locked.
     *
     * The enchant block occupies the TOP of the lore — the ◆ lines plus their
     * ability boxes, tag rows and blank separators — so the boundary is the
     * LAST matching line, not each matching line individually.
     */
    private int lockBoundary() {
        if (!protectEnchants) return -1;
        return LoreCommandConfig.lastProtectedIndex(collectLinesSafe());
    }

    private boolean isLocked(int rowIndex, int boundary) {
        return protectEnchants && rowIndex <= boundary;
    }

    /**
     * Builds a MINIMAL edit script.
     *
     * /rll re-indexes: removing line 5 makes the old line 6 become line 5. The
     * old approach rewrote every row by position while SKIPPING locked enchant
     * lines — so after a deletion the untouched locked line stayed put while
     * everything around it shifted, corrupting the lore.
     *
     * Instead:
     *   1. delete the removed original indices, highest first (lower indices
     *      are unaffected by a higher removal, so no shifting mid-script)
     *   2. work out what the item looks like after those deletions
     *   3. only rewrite the rows whose content differs from that
     *
     * A locked line that didn't move produces no command at all; one that DID
     * move is rewritten with its own identical text, which is the only way to
     * keep the lore consistent.
     */
    private List<String> buildCommands() {
        List<String> cmds = new ArrayList<>();
        if (renameEnabled && nameField != null && !nameField.getText().isBlank()) {
            cmds.add(LoreCommandConfig.rename(nameField.getText().trim()));
        }

        List<String> rows = collectLinesSafe();
        int lastManaged = originalCount - hiddenTail;

        // Line numbers COUNT the enchant lines — they are real lore lines, so
        // indices run over the whole lore, not just the custom part. Rows
        // inside the enchant block are simply never targeted.
        int offset = LoreCommandConfig.lastProtectedIndex(originalLines) + 1;

        // ── 1. deletions, highest index first ────────────────────────────
        // /rll re-indexes (removing 3 makes the old 4 become 3). Working from
        // the highest index down means every remaining target keeps its index.
        java.util.Set<Integer> kept = new java.util.HashSet<>();
        for (int oi : rowOrigIndex) if (oi > 0) kept.add(oi);
        for (int i = lastManaged; i > offset; i--) {
            if (!kept.contains(i)) cmds.add(LoreCommandConfig.removeLine(i));
        }

        // ── 2. simulate the lore left after those deletions ──────────────
        List<String> sim = new ArrayList<>();
        for (int i = 1; i <= lastManaged; i++) {
            if (kept.contains(i) && i - 1 < originalLines.size()) {
                sim.add(originalLines.get(i - 1));
            }
        }

        // ── 3. turn `sim` into the edited rows ───────────────────────────
        // Only /sll is available (no insert, no append command), so rows are
        // walked in order: an existing line is replaced, and a brand-new line
        // is written at exactly size+1 — never further, because writing past
        // the end makes the server clamp to the last line and each write would
        // overwrite the previous one.
        boolean canAppend = LoreCommandConfig.get().canAppend;
        overflowLines = 0;
        for (int r = offset; r < rows.size(); r++) {
            String want = rows.get(r) == null ? "" : rows.get(r);
            if (r < sim.size()) {
                if (sim.get(r).equals(want)) continue;    // already correct
                cmds.add(LoreCommandConfig.setLine(r + 1, want));
                sim.set(r, want);
            } else if (canAppend) {
                cmds.add(LoreCommandConfig.addLine(want));
                sim.add(want);
            } else {
                // /sll only edits an existing line and the add commands are
                // permission-denied, so extra rows simply cannot be created.
                overflowLines++;
            }
        }
        return cmds;
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
        Theme.stars(ctx, textRenderer, x + 4, y + 24, w - 8, h - 46, 26);

        ctx.drawTextWithShadow(textRenderer, Theme.WORDMARK, x + 10, y + 9, Theme.TEXT_HI);
        ctx.drawTextWithShadow(textRenderer, "§8│ §7Lore §8— §7" + trim(itemLabel, 150),
                x + 14 + textRenderer.getWidth(Theme.WORDMARK), y + 9, Theme.TEXT_MID);
        addBtn(ctx, new Btn(x + w - 20, y + 7, 13, 13, BTN_CLOSE, "×"));

        int vTop = viewTop(), vBot = viewBottom();
        int oy = -scroll;

        // Reposition fields for the scroll and hide off-view ones
        if (nameField != null) {
            nameField.setY(y + 30 + oy);
            nameField.visible = nameField.getY() >= vTop - 2 && nameField.getY() + 14 <= vBot + 2;
        }
        for (int i = 0; i < lineFields.size(); i++) {
            TextFieldWidget f = lineFields.get(i);
            f.setY(y + 62 + i * 17 + oy);
            f.visible = f.getY() >= vTop - 2 && f.getY() + 14 <= vBot + 2;
        }

        ctx.enableScissor(x + 4, vTop, x + w - 4, vBot);

        // Name row
        drawClipped(ctx, "§7Name", x + 12, y + 34 + oy, vTop, vBot, Theme.TEXT_MID);
        addBtnClipped(ctx, new Btn(x + w - 78, y + 29 + oy, 66, 15, BTN_RENAME_TOGGLE,
                renameEnabled ? "§aRename ON" : "§8Rename OFF"), vTop, vBot);

        // Lore rows (everything at/above the last enchant line is locked)
        int boundary = lockBoundary();
        int lockedCount = 0;
        for (int i = 0; i < lineFields.size(); i++) {
            TextFieldWidget f = lineFields.get(i);
            int fy = f.getY();
            boolean locked = isLocked(i, boundary);
            if (locked) lockedCount++;
            f.setEditable(!locked);

            drawClipped(ctx, locked ? "§6L" : "§8" + (i + 1), x + 14, fy + 4, vTop, vBot,
                    locked ? Theme.WARN : Theme.TEXT_DIM);

            int bx = f.getX() + f.getWidth() + 3;
            if (!locked) {
                addBtnClipped(ctx, new Btn(bx,      fy, 13, 14, BTN_UP_BASE + i,   "§7▲"), vTop, vBot);
                addBtnClipped(ctx, new Btn(bx + 15, fy, 13, 14, BTN_DOWN_BASE + i, "§7▼"), vTop, vBot);
                addBtnClipped(ctx, new Btn(bx + 30, fy, 13, 14, BTN_DEL_BASE + i,  "§c×"), vTop, vBot);
                // Manual centring nudge (±1 "&f " pad unit)
                addBtnClipped(ctx, new Btn(bx + 45, fy, 13, 14, BTN_PADL_BASE + i, "§8◄"), vTop, vBot);
                addBtnClipped(ctx, new Btn(bx + 59, fy, 13, 14, BTN_PADR_BASE + i, "§8►"), vTop, vBot);
            }
            // Locked rows get NO actions at all: deleting part of the enchant
            // block shifts the rest and corrupts the item's enchant display.
        }

        int by = y + 62 + lineFields.size() * 17 + 6 + oy;
        if (lineFields.size() < MAX_LINES) {
            addBtnClipped(ctx, new Btn(x + 32, by, 56, 15, BTN_ADD, "§a+ Line"), vTop, vBot);
        }
        addBtnClipped(ctx, new Btn(x + 92,  by, 86, 15, BTN_GRAD,   "§b✦ Grad ← Item"), vTop, vBot);
        addBtnClipped(ctx, new Btn(x + 182, by, 78, 15, BTN_CENTER, "§b⇔ Center All"), vTop, vBot);
        addBtnClipped(ctx, new Btn(x + 264, by, 62, 15, BTN_CLEAR,  "§cClear All"), vTop, vBot);
        addBtnClipped(ctx, new Btn(x + 330, by, 96, 15, BTN_PROTECT_TOGGLE,
                protectEnchants ? "§6L Enchants ON" : "§8L Enchants OFF"), vTop, vBot);

        // ── Colour preview of the finished lore ──────────────────────────
        int pvY = by + 22;
        drawClipped(ctx, "§7Item preview:", x + 12, pvY, vTop, vBot, Theme.TEXT_MID);
        pvY += 11;
        if (nameField != null && pvY >= vTop - 8 && pvY <= vBot) {
            CodedTextRenderer.draw(ctx, textRenderer, nameField.getText(), x + 16, pvY, w - 40, Theme.TEXT_HI);
        }
        pvY += 11;
        for (TextFieldWidget f : lineFields) {
            if (pvY >= vTop - 8 && pvY <= vBot) {
                CodedTextRenderer.draw(ctx, textRenderer, f.getText(), x + 20, pvY, w - 44, 0xFFAAAAAA);
            }
            pvY += 10;
        }

        // ── Command preview ──────────────────────────────────────────────
        List<String> cmds = buildCommands();
        int py2 = pvY + 8;
        drawClipped(ctx, "§7Will send §f" + cmds.size() + " §7command(s)"
                        + (lockedCount > 0 ? " §8(§6" + lockedCount + " locked§8)" : "")
                        + (hiddenTail > 0 ? " §8(+" + hiddenTail + " untouched)" : "") + "§7:",
                x + 12, py2, vTop, vBot, Theme.TEXT_MID);
        py2 += 11;

        int tooLong = 0;
        for (String c : cmds) {
            boolean over = c.length() > GradientUtil.MAX_COMMAND_LENGTH;
            if (over) tooLong++;
            drawClipped(ctx, (over ? "§c[" + c.length() + "] " : "§8") + "/"
                            + trim(GradientUtil.stripCodes(c), w - 60),
                    x + 16, py2, vTop, vBot, Theme.TEXT_DIM);
            py2 += 9;
        }
        if (tooLong > 0) {
            drawClipped(ctx,
                    "§c⚠ " + tooLong + " command(s) exceed the 256-char limit — they will be skipped",
                    x + 16, py2, vTop, vBot, 0xFFFF5E7A);
            py2 += 9;
        }
        if (overflowLines > 0) {
            drawClipped(ctx, "§c⚠ " + overflowLines + " extra row(s) can't be written — new lore"
                            + " lines can't be created (no add permission)",
                    x + 16, py2, vTop, vBot, 0xFFFF5E7A);
            py2 += 9;
        }
        ctx.disableScissor();

        contentH = (py2 + 12) - (y + 30 + oy);

        // Scrollbar
        int viewH = vBot - vTop;
        if (contentH > viewH) {
            int trackX = x + w - 6;
            ctx.fill(trackX, vTop, trackX + 3, vBot, Theme.SCROLL_TRACK);
            int thumbH = Math.max(14, viewH * viewH / contentH);
            int maxS = contentH - viewH;
            int thumbY = vTop + (viewH - thumbH) * Math.min(scroll, maxS) / Math.max(1, maxS);
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, Theme.SCROLL_THUMB);
        }

        // Actions
        int ay = y + h - 40;
        addBtn(ctx, new Btn(x + 12,  ay, 84, 16, BTN_READ,      "§b⟳ Read Item"));
        addBtn(ctx, new Btn(x + 100, ay, 96, 16, BTN_COPY_CMDS, "§b⎘ Copy Commands"));
        addBtn(ctx, new Btn(x + w - 100, ay, 88, 16, BTN_APPLY, "§a✦ Apply"));

        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 5000) {
            ctx.drawTextWithShadow(textRenderer, status, x + 12, y + h - 20, Theme.TEXT_MID);
        }

        super.render(ctx, mx, my, delta);
    }

    private String trim(String s, int maxW) {
        while (textRenderer.getWidth(s) > maxW && s.length() > 3) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void drawClipped(DrawContext ctx, String text, int x, int y, int top, int bot, int color) {
        if (y < top - 8 || y > bot) return;
        ctx.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    private void addBtnClipped(DrawContext ctx, Btn b, int top, int bot) {
        if (b.y + b.h < top || b.y > bot) return;
        addBtn(ctx, b);
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        int maxS = Math.max(0, contentH - (viewBottom() - viewTop()));
        scroll = Math.max(0, Math.min(maxS, scroll - (int)(vAmount * 18)));
        return true;
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

        if (tag >= BTN_PADR_BASE) { pad(tag - BTN_PADR_BASE, +1); return; }
        if (tag >= BTN_PADL_BASE) { pad(tag - BTN_PADL_BASE, -1); return; }
        if (tag >= BTN_DOWN_BASE) { move(tag - BTN_DOWN_BASE, +1); return; }
        if (tag >= BTN_UP_BASE)   { move(tag - BTN_UP_BASE,  -1); return; }
        if (tag >= BTN_DEL_BASE) {
            int i = tag - BTN_DEL_BASE;
            List<String> cur = collectLinesSafe();
            List<Integer> idx = new ArrayList<>(rowOrigIndex);
            if (i < cur.size()) {
                cur.remove(i);                 // may empty the list entirely
                if (i < idx.size()) idx.remove(i);
                buildWidgets(nameField.getText(), cur, idx);
                setStatus("§7Removed line " + (i + 1) + " §8(applies on Apply)");
            }
            return;
        }

        switch (tag) {
            case BTN_CLOSE -> onClose();
            case BTN_READ  -> readHeldItem();
            case BTN_ADD   -> {
                List<String> cur = collectLinesSafe();
                cur.add("");
                rebuild(cur);
            }
            case BTN_CLEAR -> {
                rebuild(new ArrayList<>());
                setStatus("§cAll lines cleared §8— Apply will remove them from the item");
            }
            case BTN_RENAME_TOGGLE  -> renameEnabled = !renameEnabled;
            case BTN_PROTECT_TOGGLE -> {
                protectEnchants = !protectEnchants;
                setStatus(protectEnchants
                        ? "§6Enchant block locked §7(everything above the last enchant line)"
                        : "§eEnchant block unlocked §7— every line is editable");
            }
            case BTN_CENTER -> {
                // Centre editable lines against the widest line in the item
                List<String> cur = collectLinesSafe();
                List<String> centered = CodedTextRenderer.centerAll(textRenderer, cur);
                int bnd = lockBoundary();
                int n = 0, pads = 0;
                for (int k = 0; k < lineFields.size() && k < centered.size(); k++) {
                    if (isLocked(k, bnd)) continue;
                    lineFields.get(k).setText(centered.get(k));
                    // count "&f " units added, so it's obvious it did something
                    pads += (centered.get(k).length() - CodedTextRenderer.stripLeadingPad(centered.get(k)).length()) / 3;
                    n++;
                }
                setStatus("§bCentered " + n + " line(s) §8(+" + pads
                        + " pad units — use §7◄ ►§8 to fine-tune)");
            }
            case BTN_GRAD -> {
                if (mc != null && mc.player != null && !lineFields.isEmpty()) {
                    var stops = GradientUtil.sampleItemName(mc.player.getMainHandStack());
                    if (stops.isEmpty()) { setStatus("§cHeld item has no coloured name"); return; }
                    TextFieldWidget f = lineFields.get(0);
                    if (GradientUtil.stripCodes(f.getText()).isBlank()) { setStatus("§cLine 1 is empty"); return; }
                    // pad-preserving so a centred line stays centred
                    f.setText(GradientUtil.applyPreservingPad(f.getText(), stops, false,
                            GradientUtil.MAX_COMMAND_LENGTH - 8));
                    setStatus("§bLine 1 re-coloured to match the item");
                }
            }
            case BTN_COPY_CMDS -> {
                if (mc != null) {
                    mc.keyboard.setClipboard(String.join("\n", buildCommands()));
                    setStatus("§bCommands copied to clipboard");
                }
            }
            case BTN_APPLY -> {
                List<String> cmds = buildCommands();
                if (cmds.isEmpty()) { setStatus("§7Nothing to apply"); return; }
                ClientCommandQueue.submit(cmds, LoreCommandConfig.get().spacingMs);
                originalCount = lineFields.size() + hiddenTail;
                setStatus("§a✦ Sending " + cmds.size() + " command(s)…");
            }
        }
    }

    private void move(int i, int dir) {
        List<String> cur = collectLinesSafe();
        List<Integer> idx = new ArrayList<>(rowOrigIndex);
        int j = i + dir;
        if (i < 0 || i >= cur.size() || j < 0 || j >= cur.size()) return;
        java.util.Collections.swap(cur, i, j);
        if (i < idx.size() && j < idx.size()) java.util.Collections.swap(idx, i, j);
        buildWidgets(nameField != null ? nameField.getText() : "", cur, idx);
    }

    /**
     * Manual centring nudge: adds/removes one "&f " pad unit on a line.
     * Auto-centre uses the client's font metrics, which can be off when the
     * server ships custom glyph widths in a resource pack — this lets you
     * correct by eye without hand-editing the codes.
     */
    private void pad(int i, int delta) {
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
        setStatus("§bLine " + (i + 1) + " pad " + (delta > 0 ? "+1" : "-1"));
    }

    /** Rebuilds the text fields from a line list, preserving the name field. */
    private void rebuild(List<String> lines) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            idx.add(i < rowOrigIndex.size() ? rowOrigIndex.get(i) : -1);
        }
        buildWidgets(nameField != null ? nameField.getText() : "", lines, idx);
    }

    private void setStatus(String s) { status = s; statusAt = System.currentTimeMillis(); }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(input);
    }

    public void onClose() {
        MinecraftClient.getInstance().setScreen(null);
    }
}
