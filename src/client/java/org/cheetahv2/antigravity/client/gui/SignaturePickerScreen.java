package org.cheetahv2.antigravity.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.CodedTextRenderer;
import org.cheetahv2.antigravity.client.util.GradientUtil;
import org.cheetahv2.antigravity.client.util.SignatureManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * SignaturePickerScreen — what /sign opens.
 *
 * A card per saved preset: click to SEND it, or use the row buttons to edit,
 * set active, or delete. "+ New" jumps straight into the editor with a fresh
 * preset. Each card previews its lines with the colour codes stripped.
 */
public class SignaturePickerScreen extends Screen {

    private String status = "";
    private long   statusAt = 0;
    private int    scroll = 0;

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
            BTN_CLOSE = 0, BTN_NEW = 1,
            BTN_SEND_BASE = 100, BTN_EDIT_BASE = 200, BTN_DEL_BASE = 300,
            BTN_PLACE_BASE = 400;

    public SignaturePickerScreen() {
        super(Text.literal("Antigravity — Signatures"));
    }

    @Override public boolean shouldPause() { return false; }

    private int pw() { return Math.min(420, width - 20); }
    private int ph() { return Math.min(300, height - 20); }
    private int px() { return (width - pw()) / 2; }
    private int py() { return (height - ph()) / 2; }

    private static final int ROW_H = 34;

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        poll();
        buttons.clear();

        int x = px(), y = py(), w = pw(), h = ph();

        ctx.fill(0, 0, width, height, Theme.BG_DIM);
        Theme.pill(ctx, x, y, w, h, Theme.BG, Theme.BORDER);
        ctx.fill(x + 2, y, w - 4, 2, Theme.ACCENT);
        Theme.stars(ctx, textRenderer, x + 4, y + 24, w - 8, h - 46, 24);

        ctx.drawTextWithShadow(textRenderer, Theme.WORDMARK, x + 10, y + 9, Theme.TEXT_HI);
        ctx.drawTextWithShadow(textRenderer, "§8│ §7Pick a signature",
                x + 14 + textRenderer.getWidth(Theme.WORDMARK), y + 9, Theme.TEXT_MID);
        addBtn(ctx, new Btn(x + w - 20, y + 7, 13, 13, BTN_CLOSE, "×"));

        List<String> names = new ArrayList<>(SignatureManager.names());
        int listTop = y + 28, listBot = y + h - 40;

        ctx.enableScissor(x + 2, listTop, x + w - 2, listBot);
        int ry = listTop + 4 - scroll;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            SignatureManager.Preset p = SignatureManager.get(name);
            boolean active = name.equalsIgnoreCase(SignatureManager.getActiveName());
            boolean hov = gy >= ry && gy < ry + ROW_H - 4 && gx >= x + 10 && gx < x + w - 120
                    && gy >= listTop && gy < listBot;

            Theme.pill(ctx, x + 10, ry, w - 20, ROW_H - 4,
                    hov ? Theme.BTN_BG_HOV : (active ? 0x40103020 : 0x25160D2E),
                    active ? Theme.GOOD : (hov ? Theme.ACCENT : Theme.BORDER));

            ctx.drawTextWithShadow(textRenderer,
                    (active ? "§a✦ " : "§d✦ ") + "§f" + name
                            + " §8(" + (p == null ? 0 : p.lines.size()) + " lines)",
                    x + 16, ry + 5, Theme.TEXT_HI);

            // First line preview — rendered with its real gradient colours
            if (p == null || p.lines.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, "§8(empty)", x + 16, ry + 17, Theme.TEXT_DIM);
            } else {
                CodedTextRenderer.draw(ctx, textRenderer, p.lines.get(0),
                        x + 16, ry + 17, w - 150, Theme.TEXT_DIM);
            }

            // Row actions — evenly spaced
            addBtn(ctx, new Btn(x + w - 150, ry + 6, 44, 16, BTN_PLACE_BASE + i, "§bPlace"));
            addBtn(ctx, new Btn(x + w - 102, ry + 6, 40, 16, BTN_SEND_BASE + i,  "§dSend"));
            addBtn(ctx, new Btn(x + w -  58, ry + 6, 34, 16, BTN_EDIT_BASE + i,  "§7Edit"));
            addBtn(ctx, new Btn(x + w -  20, ry + 6, 14, 16, BTN_DEL_BASE + i,   "§c×"));

            // Clicking the card body opens the placement screen
            buttons.add(new Btn(x + 10, ry, w - 156, ROW_H - 4, BTN_PLACE_BASE + i, ""));
            ry += ROW_H;
        }
        ctx.disableScissor();

        if (names.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "§8No signatures saved yet — press + New",
                    x + w / 2, y + h / 2 - 10, Theme.TEXT_DIM);
        }

        // Footer
        int ay = y + h - 32;
        addBtn(ctx, new Btn(x + 12, ay, 90, 16, BTN_NEW, "§a+ New"));
        ctx.drawTextWithShadow(textRenderer, "§8Click a card to send  •  ESC to close",
                x + 112, ay + 5, Theme.TEXT_DIM);

        if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 4000) {
            ctx.drawTextWithShadow(textRenderer, status, x + 12, y + h - 14, Theme.TEXT_MID);
        }

        super.render(ctx, mx, my, delta);
    }

    private String trim(String s, int maxW) {
        while (textRenderer.getWidth(s) > maxW && s.length() > 3) s = s.substring(0, s.length() - 1);
        return s;
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
        scroll = Math.max(0, scroll - (int)(vAmount * 20));
        return true;
    }

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
        List<String> names = new ArrayList<>(SignatureManager.names());

        if (tag >= BTN_PLACE_BASE) {
            int i = tag - BTN_PLACE_BASE;
            if (i < names.size() && mc != null) {
                SignatureManager.setActive(names.get(i));
                mc.setScreen(new SignaturePlaceScreen(names.get(i)));
            }
            return;
        }
        if (tag >= BTN_DEL_BASE) {
            int i = tag - BTN_DEL_BASE;
            if (i < names.size()) {
                SignatureManager.delete(names.get(i));
                setStatus("§cDeleted §f" + names.get(i));
            }
            return;
        }
        if (tag >= BTN_EDIT_BASE) {
            int i = tag - BTN_EDIT_BASE;
            if (i < names.size() && mc != null) {
                SignatureManager.setActive(names.get(i));
                mc.setScreen(new SignatureSettingsScreen());
            }
            return;
        }
        if (tag >= BTN_SEND_BASE) {
            int i = tag - BTN_SEND_BASE;
            if (i < names.size() && mc != null) {
                SignatureManager.setActive(names.get(i));
                int sent = SignatureManager.send(names.get(i));
                setStatus(sent > 0 ? "§d✦ Sending " + sent + " line(s)…" : "§cPreset is empty");
                if (sent > 0) mc.setScreen(null); // close so you see it land
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
                SignatureManager.setActive(name);
                if (mc != null) mc.setScreen(new SignatureSettingsScreen());
            }
        }
    }

    private void setStatus(String s) { status = s; statusAt = System.currentTimeMillis(); }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(input);
    }

    public void onClose() { MinecraftClient.getInstance().setScreen(null); }
}
