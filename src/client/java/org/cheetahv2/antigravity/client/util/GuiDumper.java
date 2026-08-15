package org.cheetahv2.antigravity.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * GuiDumper — copies the entire open GUI (or your own inventory) as text.
 *
 * For every non-empty slot it emits: slot index, count, item id, display
 * name, and all lore lines. Formatting is serialized back into the
 * &#RRGGBB / &l-style codes servers use, so a dumped name like
 * "&#9FFB08👺 Rain Dance IV" can be pasted straight into module configs.
 *
 * Output goes to the CLIPBOARD and to config/antigravity/gui_dumps/ as a
 * timestamped .txt (clipboards get overwritten; files don't).
 */
public final class GuiDumper {

    private GuiDumper() {}

    private static final Path DUMP_DIR = Paths.get("config", "antigravity", "gui_dumps");

    /** Dump the current screen handler. Returns a short status for the action bar. */
    public static String dump(MinecraftClient mc) {
        if (mc == null || mc.player == null) return null;

        var handler = mc.player.currentScreenHandler;
        String title = mc.currentScreen != null
                ? mc.currentScreen.getTitle().getString()
                : "Player Inventory";

        StringBuilder out = new StringBuilder(4096);
        out.append("=== GUI DUMP: ").append(title).append(" ===\n");
        out.append("time: ").append(LocalDateTime.now()).append('\n');
        out.append("slots: ").append(handler.slots.size()).append("\n\n");

        int containerItems = 0, playerItems = 0;
        StringBuilder containerSec = new StringBuilder();
        StringBuilder playerSec    = new StringBuilder();

        for (Slot slot : handler.slots) {
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;

            boolean playerSlot = slot.inventory instanceof PlayerInventory;
            StringBuilder sec = playerSlot ? playerSec : containerSec;
            if (playerSlot) playerItems++; else containerItems++;

            sec.append("[slot ").append(slot.id).append("] x").append(stack.getCount())
               .append("  ").append(Registries.ITEM.getId(stack.getItem())).append('\n');
            sec.append("  Name: ").append(toCodes(stack.getName())).append('\n');

            var lore = stack.get(DataComponentTypes.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                sec.append("  Lore:\n");
                for (Text line : lore.lines()) {
                    sec.append("    ").append(toCodes(line)).append('\n');
                }
            }
            sec.append('\n');
        }

        if (containerItems > 0) {
            out.append("── Container (").append(containerItems).append(" items) ──\n\n");
            out.append(containerSec);
        }
        out.append("── Player Inventory (").append(playerItems).append(" items) ──\n\n");
        out.append(playerSec);

        String text = out.toString();

        // Clipboard
        mc.keyboard.setClipboard(text);

        // File (clipboard survives one paste; files survive the session)
        String fileName = "gui_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".txt";
        try {
            Files.createDirectories(DUMP_DIR);
            Files.writeString(DUMP_DIR.resolve(fileName), text);
        } catch (IOException ignored) {}

        return "§d✦ §fCopied GUI §7(" + (containerItems + playerItems)
                + " items) §fto clipboard §8+ gui_dumps/" + fileName;
    }

    // ── Text → &-code serialization ───────────────────────────────────────

    /**
     * Flattens a Text tree back into a string with &#RRGGBB / &l-style codes,
     * matching the ampersand format used in server configs.
     */
    public static String toCodes(Text text) {
        StringBuilder sb = new StringBuilder();
        final Style[] last = { null };
        text.visit((style, str) -> {
            if (!str.isEmpty()) {
                if (!style.equals(last[0])) {
                    sb.append(codesFor(style));
                    last[0] = style;
                }
                sb.append(str);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    private static String codesFor(Style s) {
        StringBuilder c = new StringBuilder();
        TextColor col = s.getColor();
        if (col != null) {
            // Prefer the legacy &x code for exact named colors, hex otherwise
            Formatting named = namedFormatting(col);
            if (named != null) c.append('&').append(named.getCode());
            else               c.append(String.format("&#%06X", col.getRgb()));
        }
        if (s.isBold())          c.append("&l");
        if (s.isItalic())        c.append("&o");
        if (s.isUnderlined())    c.append("&n");
        if (s.isStrikethrough()) c.append("&m");
        if (s.isObfuscated())    c.append("&k");
        return c.toString();
    }

    private static Formatting namedFormatting(TextColor col) {
        for (Formatting f : Formatting.values()) {
            if (f.isColor() && f.getColorValue() != null && f.getColorValue() == col.getRgb()) {
                return f;
            }
        }
        return null;
    }
}
