package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.ClientCommandQueue;
import org.cheetahv2.antigravity.client.util.CodedTextRenderer;
import org.cheetahv2.antigravity.client.util.GradientUtil;
import org.cheetahv2.antigravity.client.util.LoreCommandConfig;
import org.cheetahv2.antigravity.client.util.LoreDiff;
import org.cheetahv2.antigravity.client.util.SignatureManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * SignaturePlaceScreen — drag your signature onto the item.
 *
 * Shows the held item's real lore with the signature block inserted live, in
 * full colour. Drag the block (or use the arrows) to choose exactly where it
 * lands; the lines below shift down rather than being overwritten. Apply
 * issues the /sll + /rll script.
 */
public class SignaturePlaceScreen extends Screen {

    private final String presetName;
    private List<String> itemLore = new ArrayList<>();
    private List<String> sig      = new ArrayList<>();
    private int insertAt = 0;          // index in itemLore where the block goes
    private int minInsert = 0;         // can't go above the enchant block

    private String status = "";
    private long   statusAt = 0;

    private int scroll = 0;
    private int contentH = 0;

    // drag state
    private boolean dragging = false;
    private boolean prevLmb = false;
    private double gx, gy;

    private static final int ROW_H = 11;

    private static final class Btn {
        int x, y, w, h, tag; String label;
        Btn(int x,int y,int w,int h,int tag,String label){
            this.x=x; this.y=y; this.w=w; this.h=h; this.tag=tag; this.label=label; }
    }
    private final List<Btn> buttons = new ArrayList<>();

    private static final int BTN_CLOSE = 0, BTN_UP = 1, BTN_DOWN = 2,
            BTN_APPLY = 3, BTN_REREAD = 4, BTN_TOP = 5, BTN_BOTTOM = 6;

    public SignaturePlaceScreen(String presetName) {
        super(Text.literal("Antigravity — Place Signature"));
        this.presetName = presetName;
    }

    @Override public boolean shouldPause() { return false; }

    private int pw() { return Math.min(560, width - 20); }
    private int ph() { return Math.min(340, height - 20); }
    private int px() { return (width - pw()) / 2; }
    private int py() { return (height - ph()) / 2; }
    private int listTop()    { return py() + 46; }
    private int listBottom() { return py() + ph() - 44; }

    @Override
    protected void init() { reread(); }

    /** Loads the held item's lore and the preset's lines. */
    private void reread() {
        itemLore = LoreCommandConfig.heldLoreLines();
        minInsert = LoreCommandConfig.lastProtectedIndex(itemLore) + 1;

        sig = new ArrayList<>();
        SignatureManager.Preset p = SignatureManager.get(presetName);
        if (p != null) {
            for (String l : p.lines) if (l != null && !l.isBlank()) sig.add(l.strip());
        }

        // Drop an existing copy of this signature so the preview shows the
        // result of REPLACING it, not a second copy stacked on top.
        java.util.Set<String> sigPlain = new java.util.HashSet<>();
        for (String s : sig) sigPlain.add(GradientUtil.stripCodes(s).trim());
        for (int i = itemLore.size() - 1; i >= minInsert; i--) {
            if (sigPlain.contains(GradientUtil.stripCodes(itemLore.get(i)).trim())) {
                itemLore.remove(i);
                if (insertAt > i) insertAt--;
            }
        }

        insertAt = Math.max(minInsert, Math.min(insertAt, itemLore.size()));
        if (insertAt == 0) insertAt = Math.min(minInsert, itemLore.size());
    }

    /** The lore as it will look after Apply. */
    private List<String> preview() {
        List<String> out = new ArrayList<>(itemLore);
        out.addAll(Math.max(0, Math.min(insertAt, out.size())), sig);
        return out;
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
        Theme.stars(ctx, textRenderer, x + 4, y + 26, w - 8, h - 70, 22);

        // Header
        ctx.drawTextWithShadow(textRenderer, Theme.WORDMARK, x + 12, y + 10, Theme.TEXT_HI);
        ctx.drawTextWithShadow(textRenderer, "§8│ §7Place signature §8— §f" + presetName,
                x + 16 + textRenderer.getWidth(Theme.WORDMARK), y + 10, Theme.TEXT_MID);
        addBtn(ctx, new Btn(x + w - 22, y + 8, 14, 14, BTN_CLOSE, "×"));

        ctx.drawTextWithShadow(textRenderer,
                "§8Drag the highlighted block, or use the arrows. Lines below shift down.",
                x + 12, y + 30, Theme.TEXT_DIM);

        // ── Live preview list ────────────────────────────────────────────
        List<String> lines = preview();
        int vTop = listTop(), vBot = listBottom();
        ctx.enableScissor(x + 8, vTop, x + w - 8, vBot);

        int ly = vTop + 4 - scroll;
        for (int i = 0; i < lines.size(); i++) {
            boolean isSig  = i >= insertAt && i < insertAt + sig.size();
            boolean locked = i < minInsert;

            if (ly >= vTop - ROW_H && ly <= vBot) {
                if (isSig) {
                    // Highlighted, draggable block
                    Theme.pill(ctx, x + 12, ly - 1, w - 40, ROW_H,
                            dragging ? 0x70321F6E : 0x40241850, Theme.ACCENT);
                    ctx.drawTextWithShadow(textRenderer, "§d⣿", x + 15, ly, Theme.ACCENT);
                } else if (locked) {
                    ctx.drawTextWithShadow(textRenderer, "§6L", x + 15, ly, Theme.WARN);
                } else {
                    ctx.drawTextWithShadow(textRenderer, "§8" + (i + 1), x + 15, ly, Theme.TEXT_DIM);
                }
                CodedTextRenderer.draw(ctx, textRenderer, lines.get(i),
                        x + 30, ly, w - 60, locked ? 0xFF8A7BB8 : Theme.TEXT_HI);
            }
            ly += ROW_H;
        }
        ctx.disableScissor();
        contentH = lines.size() * ROW_H + 8;

        // Scrollbar
        int viewH = vBot - vTop;
        if (contentH > viewH) {
            int trackX = x + w - 12;
            ctx.fill(trackX, vTop, trackX + 3, vBot, Theme.SCROLL_TRACK);
            int thumbH = Math.max(14, viewH * viewH / contentH);
            int maxS = contentH - viewH;
            int thumbY = vTop + (viewH - thumbH) * Math.min(scroll, maxS) / Math.max(1, maxS);
            ctx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, Theme.SCROLL_THUMB);
        }

        // ── Footer: evenly spaced controls ───────────────────────────────
        int ay = y + h - 32;
        int bw = 62, gap = 8, bx = x + 14;
        addBtn(ctx, new Btn(bx,                    ay, bw, 17, BTN_TOP,    "§7⇈ Top"));
        addBtn(ctx, new Btn(bx + (bw + gap),       ay, bw, 17, BTN_UP,     "§7▲ Up"));
        addBtn(ctx, new Btn(bx + (bw + gap) * 2,   ay, bw, 17, BTN_DOWN,   "§7▼ Down"));
        addBtn(ctx, new Btn(bx + (bw + gap) * 3,   ay, bw, 17, BTN_BOTTOM, "§7⇊ End"));
        addBtn(ctx, new Btn(bx + (bw + gap) * 4,   ay, bw + 14, 17, BTN_REREAD, "§b⟳ Reload"));

        List<String> cmds = LoreDiff.commands(LoreCommandConfig.heldLoreLines(), preview());
        addBtn(ctx, new Btn(x + w - 106, ay, 92, 17, BTN_APPLY,
                "§a✦ Apply §8(" + cmds.size() + ")"));

        // Position readout
        ctx.drawTextWithShadow(textRenderer,
                "§7Insert at line §f" + (insertAt + 1) + " §8of " + (itemLore.size() + sig.size())
                        + "  §8•  " + sig.size() + " signature line(s)",
                x + 14, y + h - 46, Theme.TEXT_MID);

        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 5000) {
            ctx.drawTextWithShadow(textRenderer, status, x + 14, y + h - 12, Theme.TEXT_MID);
        }

        super.render(ctx, mx, my, delta);
    }

    private void addBtn(DrawContext ctx, Btn b) {
        boolean hov = gx >= b.x && gx < b.x + b.w && gy >= b.y && gy < b.y + b.h;
        Theme.pill(ctx, b.x, b.y, b.w, b.h,
                hov ? Theme.BTN_BG_HOV : Theme.BTN_BG,
                hov ? Theme.ACCENT : Theme.BORDER);
        int tw = textRenderer.getWidth(b.label);
        ctx.drawTextWithShadow(textRenderer, b.label,
                b.x + (b.w - tw) / 2, b.y + (b.h - 7) / 2,
                hov ? Theme.TEXT_HI : Theme.TEXT_MID);
        buttons.add(b);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        int maxS = Math.max(0, contentH - (listBottom() - listTop()));
        scroll = Math.max(0, Math.min(maxS, scroll - (int)(v * 18)));
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
            // Grab the block?
            int rowUnder = rowAt(gy);
            if (rowUnder >= insertAt && rowUnder < insertAt + sig.size()
                    && gx > px() + 8 && gx < px() + pw() - 16) {
                dragging = true;
            } else {
                for (Btn b : buttons) {
                    if (gx >= b.x && gx < b.x + b.w && gy >= b.y && gy < b.y + b.h) {
                        click(b.tag); break;
                    }
                }
            }
        }

        if (dragging) {
            if (lmb) {
                int row = rowAt(gy);
                if (row >= 0) setInsert(Math.min(row, itemLore.size()));
            } else {
                dragging = false;
                setStatus("§bPlaced at line §f" + (insertAt + 1));
            }
        }
        prevLmb = lmb;
    }

    /** Preview row index under a screen Y, or -1. */
    private int rowAt(double screenY) {
        if (screenY < listTop() || screenY > listBottom()) return -1;
        return (int) ((screenY - (listTop() + 4 - scroll)) / ROW_H);
    }

    private void setInsert(int v) {
        insertAt = Math.max(minInsert, Math.min(v, itemLore.size()));
    }

    private void click(int tag) {
        switch (tag) {
            case BTN_CLOSE  -> onClose();
            case BTN_UP     -> setInsert(insertAt - 1);
            case BTN_DOWN   -> setInsert(insertAt + 1);
            case BTN_TOP    -> setInsert(minInsert);
            case BTN_BOTTOM -> setInsert(itemLore.size());
            case BTN_REREAD -> { reread(); setStatus("§bReloaded the held item"); }
            case BTN_APPLY  -> {
                List<String> current = LoreCommandConfig.heldLoreLines();
                List<String> cmds = LoreDiff.commands(current, preview());
                if (cmds.isEmpty()) { setStatus("§7Already matches — nothing to send"); return; }
                ClientCommandQueue.submit(cmds, LoreCommandConfig.get().spacingMs);
                SignatureManager.Preset p = SignatureManager.get(presetName);
                if (p != null) {
                    p.lastWritten = sig.size();
                    p.lastStart   = insertAt;
                    SignatureManager.saveNow();
                }
                setStatus("§a✦ Sending " + cmds.size() + " command(s)…");
            }
        }
    }

    private void setStatus(String s) { status = s; statusAt = System.currentTimeMillis(); }

    @Override
    public boolean keyPressed(KeyInput input) {
        int k = input.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (k == GLFW.GLFW_KEY_UP)   { setInsert(insertAt - 1); return true; }
        if (k == GLFW.GLFW_KEY_DOWN) { setInsert(insertAt + 1); return true; }
        return super.keyPressed(input);
    }

    public void onClose() { MinecraftClient.getInstance().setScreen(null); }
}
