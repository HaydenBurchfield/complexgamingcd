package org.cheetahv2.antigravity.client.utility;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.cooldown.AbilityCooldownManager;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AutoTotemModule
 *
 * TWO MODES:
 *
 *  ALWAYS  (original behaviour)
 *      Keeps a Totem of Undying in the offhand at all times.
 *      Re-equips automatically after one is consumed.
 *
 *  SMART  (new — mirrors Sacred Shield logic)
 *      Only equips a totem when:
 *        • HP ≤ triggerHealth, AND
 *        • Sacred Shield cannot be deployed:
 *            – not in inventory, OR on cooldown (jack of hearts CD is tracked separately)
 *        • Tropical Shield cannot be deployed:
 *            – not in inventory, OR on cooldown in the ability cooldown map
 *      Un-equips (and returns totem to its original slot) when HP recovers above
 *      the threshold OR a shield becomes available again.
 *
 * Tick-delay state machine (ALWAYS mode):
 *   IDLE → EQUIP_STEP1 [if from main inv: swap mainInv↔hotbar8]
 *        → EQUIP_STEP2  swap hotbar8↔offhand
 *        → IDLE
 *
 * Tick-delay state machine (SMART mode):
 *   IDLE     → (trigger) → EQUIP_STEP1 → EQUIP_STEP2 → EQUIPPED
 *   EQUIPPED → (clear)   → UNEQUIP_STEP1 → UNEQUIP_STEP2 → IDLE
 */
public class AutoTotemModule implements UtilityModule {

    public enum Mode { ALWAYS, SMART }

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled          = false;
        public Mode    mode             = Mode.ALWAYS;
        /** SMART mode: HP (half-hearts) at or below which to consider equipping. */
        public float   triggerHealth    = 8.0f;
        /** Minimum ms between equip actions to avoid spam. */
        public int     actionCooldownMs = 500;
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_totem.json");

    // ── State machine ─────────────────────────────────────────────────────
    private enum Phase { IDLE, EQUIP_STEP1, EQUIP_STEP2, EQUIPPED, UNEQUIP_STEP1, UNEQUIP_STEP2 }
    private Phase phase        = Phase.IDLE;
    private long  lastActionMs = 0L;
    private int   totemInvSlot = -1;
    private int   totemScrSlot = -1;

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Auto Totem"; }
    @Override public String getDescription()    { return "Keeps Totem of Undying in offhand"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (!config.enabled || mc.player == null || mc.world == null) return;

        if (config.mode == Mode.ALWAYS) {
            tickAlways(mc);
        } else {
            tickSmart(mc);
        }
    }

    // ── ALWAYS mode ───────────────────────────────────────────────────────

    private void tickAlways(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now - lastActionMs < config.actionCooldownMs) return;

        switch (phase) {
            case IDLE: {
                if (!isTotem(mc.player.getOffHandStack())) phase = Phase.EQUIP_STEP1;
                break;
            }
            case EQUIP_STEP1: {
                if (isTotem(mc.player.getOffHandStack())) { phase = Phase.IDLE; break; }

                // SMART gates: wait out container screens and other modules'
                // inventory sequences (everything stages via hotbar 8).
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("totem", 1200)) break;

                int slot = findTotem(mc);
                if (slot < 0) { phase = Phase.IDLE; ModuleSync.release("totem"); break; }

                totemInvSlot = slot;
                totemScrSlot = slot < 9 ? 36 + slot : slot;

                if (slot >= 9) {
                    sendSwap(mc, (short) totemScrSlot, (byte) 8);
                    phase = Phase.EQUIP_STEP2;
                } else {
                    phase = Phase.EQUIP_STEP2;
                }
                lastActionMs = now;
                break;
            }
            case EQUIP_STEP2: {
                sendSwap(mc, (short) 44, (byte) 40);
                totemInvSlot = -1;
                totemScrSlot = -1;
                phase = Phase.IDLE;
                lastActionMs = now;
                ModuleSync.release("totem");
                break;
            }
            default:
                phase = Phase.IDLE;
                break;
        }
    }

    // ── SMART mode ────────────────────────────────────────────────────────

    private void tickSmart(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now - lastActionMs < config.actionCooldownMs) return;

        float hp  = mc.player.getHealth();
        boolean low = hp <= config.triggerHealth && hp > 0;

        // Trigger condition: low HP AND shields can't save us
        boolean shouldEquip = low && !shieldCanDeploy(mc);

        switch (phase) {

            case IDLE: {
                if (shouldEquip) phase = Phase.EQUIP_STEP1;
                break;
            }

            case EQUIP_STEP1: {
                if (!shouldEquip) { phase = Phase.IDLE; break; }

                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("totem", 1200)) break;

                if (isTotem(mc.player.getOffHandStack())) {
                    totemInvSlot = -1;
                    totemScrSlot = -1;
                    phase = Phase.EQUIPPED;
                    lastActionMs = now;
                    ModuleSync.release("totem"); // nothing to move
                    break;
                }

                int slot = findTotem(mc);
                if (slot < 0) { phase = Phase.IDLE; break; }

                totemInvSlot = slot;
                totemScrSlot = slot < 9 ? 36 + slot : slot;

                if (slot >= 9) {
                    sendSwap(mc, (short) totemScrSlot, (byte) 8);
                    phase = Phase.EQUIP_STEP2;
                } else {
                    phase = Phase.EQUIP_STEP2;
                }
                lastActionMs = now;
                break;
            }

            case EQUIP_STEP2: {
                if (!shouldEquip) { smartReset(mc); break; }
                sendSwap(mc, (short) 44, (byte) 40);
                phase = Phase.EQUIPPED;
                lastActionMs = now;
                ModuleSync.release("totem"); // equip sequence finished
                break;
            }

            case EQUIPPED: {
                // Un-equip when HP recovers OR a shield becomes available
                boolean clear = !shouldEquip;
                // Also reset if totem was consumed externally
                if (totemInvSlot == -1 && !isTotem(mc.player.getOffHandStack())) {
                    phase = Phase.IDLE;
                    break;
                }
                if (clear) phase = Phase.UNEQUIP_STEP1;
                break;
            }

            case UNEQUIP_STEP1: {
                if (!isTotem(mc.player.getOffHandStack())) { smartReset(mc); break; }
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("totem", 1200)) break;
                // Offhand (45) → hotbar slot 8 (button 40)
                sendSwap(mc, (short) 45, (byte) 40);
                phase = Phase.UNEQUIP_STEP2;
                lastActionMs = now;
                break;
            }

            case UNEQUIP_STEP2: {
                if (totemInvSlot >= 0 && totemInvSlot < 9) {
                    sendSwap(mc, (short) 44, (byte) totemInvSlot);
                } else if (totemInvSlot >= 9) {
                    sendSwap(mc, (short) totemScrSlot, (byte) 8);
                }
                smartReset(mc);
                lastActionMs = now;
                break;
            }
        }
    }

    private void smartReset(MinecraftClient mc) {
        phase        = Phase.IDLE;
        totemInvSlot = -1;
        totemScrSlot = -1;
        ModuleSync.release("totem");
    }

    /**
     * Returns true if at least one shield (Sacred or Tropical) can be deployed:
     *   • Its item is present in inventory, AND
     *   • Its cooldown is NOT currently active in the ability cooldown map.
     *
     * "On cooldown" here means the AbilityCooldownManager has an active entry with
     * the shield's known cooldown ID. We key on partial name matches since the
     * sacred/tropical shields are handled by their own modules, not the rune system.
     */
    private boolean shieldCanDeploy(MinecraftClient mc) {
        if (mc.player == null) return false;

        var active = AntigravityClient.ABILITY_COOLDOWN.getActive();

        // Sacred Shield
        boolean sacredInInv = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (isSacredShield(s)) { sacredInInv = true; break; }
        }
        if (!sacredInInv) {
            ItemStack off = mc.player.getOffHandStack();
            if (isSacredShield(off)) sacredInInv = true;
        }
        // Sacred shield is "deployable" if in inv and not on cooldown.
        // Sacred shield doesn't have a rune-system cooldown; it's always ready if in inv.
        if (sacredInInv) return true;

        // Tropical Shield — check if in inv
        boolean tropicalInInv = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (isTropicalShield(s)) { tropicalInInv = true; break; }
        }
        if (!tropicalInInv) {
            ItemStack off = mc.player.getOffHandStack();
            if (isTropicalShield(off)) tropicalInInv = true;
        }
        // Tropical shield has no rune-system cooldown tracked; available if in inv
        if (tropicalInInv) return true;

        return false;
    }

    // ── Item detection ────────────────────────────────────────────────────

    private int findTotem(MinecraftClient mc) {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (isTotem(mc.player.getInventory().getStack(i))) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (isTotem(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private boolean isTotem(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isOf(Items.TOTEM_OF_UNDYING);
    }

    private boolean isSacredShield(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getName().getString().toLowerCase();
        if (!name.contains("sacred")) return false;
        // Also check for divine intervention lore to be sure
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (net.minecraft.text.Text line : lore.lines()) {
                if (line.getString().toLowerCase().contains("divine intervention")) return true;
                for (net.minecraft.text.Text sib : line.getSiblings()) {
                    if (sib.getString().toLowerCase().contains("divine intervention")) return true;
                }
            }
        }
        // Fallback: just name check
        return name.contains("sacred shield");
    }

    private boolean isTropicalShield(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getName().getString().toLowerCase().contains("tropical");
    }

    // ── Packet helper ─────────────────────────────────────────────────────

    private void sendSwap(MinecraftClient mc, short screenSlot, byte button) {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(new ClickSlotC2SPacket(
                mc.player.currentScreenHandler.syncId,
                mc.player.currentScreenHandler.getRevision(),
                screenSlot,
                button,
                SlotActionType.SWAP,
                new Int2ObjectOpenHashMap<>(),
                ItemStackHash.EMPTY
        ));
    }

    // ── Config persistence ────────────────────────────────────────────────
    @Override public void save() { ConfigHelper.save(FILE, config); }
    @Override public void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c != null) config = c;
    }

    public Config getConfig() { return config; }
}
