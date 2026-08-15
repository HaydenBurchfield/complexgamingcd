package org.cheetahv2.antigravity.client.utility;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.cheetahv2.antigravity.client.util.ConfigHelper;
import org.cheetahv2.antigravity.client.util.RomanHelper;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AbstractEquipMenuModule — the shared "equip armor piece → double-tap
 * swap-hands → confirm → unequip → restore" state machine used by keybind
 * items whose enchant opens a server-side keybind menu (Sell Soul leggings,
 * Tiki / Rain Dance chestplate, ...).
 *
 * On trigger:
 *   1. Find the matching armor piece (display name OR lore contains the
 *      keyword, §/&/&#RRGGBB codes stripped; level filter minLevel-maxLevel,
 *      highest level preferred).
 *   2. Stage it in a hotbar slot (if it's in the main inventory).
 *   3. EQUIP it into the armor slot (previous armor parks in the stage slot).
 *   4. Double-tap swap-hands — opens the menu.
 *   5. Confirm: switch to hotbar slot 1, or ONE more swap-hands press if
 *      already on slot 1.
 *   6. UNEQUIP (old armor re-equips automatically) and restore all slots.
 *
 * Silent by design. Safe to trigger every tick while the key is held —
 * sequences chain back-to-back. Coordinates with other modules via ModuleSync.
 */
public abstract class AbstractEquipMenuModule implements UtilityModule {

    // ── Config (shared shape for all equip-menu modules) ──────────────────
    public static class Config {
        public boolean enabled           = true;
        /** Only act on pieces whose enchant level is within [minLevel, maxLevel]. */
        public int     minLevel          = 1;
        public int     maxLevel          = 5;
        /** Delay between the swap / F-tap steps. */
        public int     actionDelayMs     = 100;
        /** Extra wait after the 2nd F-tap so the server menu is open before confirming. */
        public int     menuConfirmDelayMs = 250;
    }

    protected Config config = new Config();

    private final String  lockKey;          // ModuleSync owner id
    private final int     armorScreenSlot;  // player screen handler: 5=head 6=chest 7=legs 8=feet
    private final String  keyword;          // lower-case match keyword ("sell soul", "rain dance")
    private final Path    file;
    private final Pattern levelPattern;

    protected AbstractEquipMenuModule(String lockKey, int armorScreenSlot, String keyword, Path file) {
        this.lockKey         = lockKey;
        this.armorScreenSlot = armorScreenSlot;
        this.keyword         = keyword.toLowerCase();
        this.file            = file;
        this.levelPattern    = Pattern.compile(
                Pattern.quote(this.keyword) + "\\s+([IVXivx]{1,4})\\b");
    }

    // ── State machine ─────────────────────────────────────────────────────
    private enum Phase { IDLE, MOVE_TO_HOTBAR, EQUIP, SWAP_HANDS_1, SWAP_HANDS_2,
                         CONFIRM, UNEQUIP, RESTORE }
    private Phase phase        = Phase.IDLE;
    private long  lastActionMs = 0L;

    private int itemInvSlot      = -1;  // original 0-35 inventory slot of the piece
    private int itemScrSlot      = -1;  // screen-handler slot for itemInvSlot
    private int workingSlot      = -1;  // hotbar slot used to stage the piece (0-8)
    private int prevSelectedSlot = -1;  // hotbar slot selected before "pressing 1"

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    /**
     * Keybind entry point — call every tick while the key is held; it no-ops
     * mid-sequence and starts the next run the moment the previous finishes.
     * Deliberately SILENT — no chat/action-bar output at all.
     */
    public void trigger(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (phase != Phase.IDLE) return;
        if (!config.enabled) return;
        if (ModuleSync.inventoryLocked(mc) || ModuleSync.isBusy(lockKey)) return;

        int slot = findMatchingItem(mc);
        if (slot < 0) return;

        ModuleSync.acquire(lockKey, 2500);
        itemInvSlot      = slot;
        itemScrSlot      = slot < 9 ? 36 + slot : slot;
        prevSelectedSlot = -1;
        phase            = Phase.MOVE_TO_HOTBAR;
        lastActionMs     = 0L;
    }

    @Override
    public void tick(MinecraftClient mc) {
        if (phase == Phase.IDLE) return;
        if (mc.player == null || mc.world == null) { reset(); return; }

        long now  = System.currentTimeMillis();
        long wait = (phase == Phase.CONFIRM) ? config.menuConfirmDelayMs : config.actionDelayMs;
        if (now - lastActionMs < wait) return;

        switch (phase) {

            case MOVE_TO_HOTBAR: {
                if (itemInvSlot < 9) {
                    workingSlot = itemInvSlot; // already on the hotbar
                } else {
                    workingSlot = mc.player.getInventory().getSelectedSlot();
                    sendSwap(mc, itemScrSlot, workingSlot);
                }
                phase = Phase.EQUIP;
                lastActionMs = now;
                break;
            }

            case EQUIP: {
                sendSwap(mc, armorScreenSlot, workingSlot);
                phase = Phase.SWAP_HANDS_1;
                lastActionMs = now;
                break;
            }

            case SWAP_HANDS_1: {
                sendSwapHands(mc);
                phase = Phase.SWAP_HANDS_2;
                lastActionMs = now;
                break;
            }

            case SWAP_HANDS_2: {
                sendSwapHands(mc);
                phase = Phase.CONFIRM;
                lastActionMs = now;
                break;
            }

            case CONFIRM: {
                int selected = mc.player.getInventory().getSelectedSlot();
                if (selected == 0) {
                    // Already on hotbar slot 1 → ONE more swap-hands press.
                    sendSwapHands(mc);
                } else {
                    // "Press 1": select hotbar slot 0 (packet auto-sent next tick).
                    prevSelectedSlot = selected;
                    mc.player.getInventory().setSelectedSlot(0);
                }
                phase = Phase.UNEQUIP;
                lastActionMs = now;
                break;
            }

            case UNEQUIP: {
                sendSwap(mc, armorScreenSlot, workingSlot);
                phase = Phase.RESTORE;
                lastActionMs = now;
                break;
            }

            case RESTORE: {
                if (prevSelectedSlot >= 0) {
                    mc.player.getInventory().setSelectedSlot(prevSelectedSlot);
                    prevSelectedSlot = -1;
                }
                // Unconditional: the action can rename/relevel the item, so no
                // "is it still the same?" check (that used to strand items).
                if (itemInvSlot >= 9 && workingSlot >= 0) {
                    sendSwap(mc, itemScrSlot, workingSlot);
                }
                reset(); // silent
                lastActionMs = now;
                break;
            }

            default:
                reset();
                break;
        }
    }

    private void reset() {
        phase            = Phase.IDLE;
        itemInvSlot      = -1;
        itemScrSlot      = -1;
        workingSlot      = -1;
        prevSelectedSlot = -1;
        ModuleSync.release(lockKey);
    }

    // ── Item detection ────────────────────────────────────────────────────

    /** Search the whole inventory; prefer the highest level inside the configured range. */
    private int findMatchingItem(MinecraftClient mc) {
        int bestSlot = -1, bestLevel = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!matches(stack)) continue;
            int lvl = getLevel(stack);
            if (lvl < 1) lvl = 1;
            if (lvl < config.minLevel || lvl > config.maxLevel) continue;
            if (lvl > bestLevel) { bestLevel = lvl; bestSlot = i; }
        }
        return bestSlot;
    }

    /** True if display name OR any lore line contains the keyword (formatting stripped). */
    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (cleaned(stack.getName().getString()).contains(keyword)) return true;
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (Text line : lore.lines()) {
            if (cleaned(fullLineText(line)).contains(keyword)) return true;
        }
        return false;
    }

    /** Level from the roman numeral after the keyword in name or lore; -1 if not found. */
    public int getLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        int lvl = levelFrom(cleaned(stack.getName().getString()));
        if (lvl > 0) return lvl;
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                lvl = levelFrom(cleaned(fullLineText(line)));
                if (lvl > 0) return lvl;
            }
        }
        return -1;
    }

    private int levelFrom(String cleanedLower) {
        Matcher m = levelPattern.matcher(cleanedLower);
        if (m.find()) {
            int lvl = RomanHelper.fromRoman(m.group(1).toUpperCase());
            if (lvl > 0) return lvl;
        }
        return -1;
    }

    private static String fullLineText(Text line) {
        StringBuilder sb = new StringBuilder(line.getString());
        for (Text sib : line.getSiblings()) sb.append(sib.getString());
        return sb.toString();
    }

    /** Strips §-codes, &-codes and &#RRGGBB hex codes, lowercases. */
    private static String cleaned(String s) {
        if (s == null) return "";
        return s.replaceAll("&#[0-9A-Fa-f]{6}", "")
                .replaceAll("[§&][0-9a-fk-orA-FK-OR]", "")
                .toLowerCase();
    }

    // ── Packet helpers ────────────────────────────────────────────────────

    /** Exactly what the vanilla [F] key sends. */
    private void sendSwapHands(MinecraftClient mc) {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ORIGIN, Direction.DOWN));
    }

    /** SWAP click via the interaction manager — same path as a real click. */
    private void sendSwap(MinecraftClient mc, int screenSlot, int hotbarButton) {
        if (mc.interactionManager == null) return;
        mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                screenSlot,
                hotbarButton,
                SlotActionType.SWAP,
                mc.player);
    }

    // ── Config persistence ────────────────────────────────────────────────
    @Override public void save() { ConfigHelper.save(file, config); }
    @Override public void load() {
        Config c = ConfigHelper.load(file, Config.class);
        if (c != null) config = c;
    }

    public Config getConfig() { return config; }
}
