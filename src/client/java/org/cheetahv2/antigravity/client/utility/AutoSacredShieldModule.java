package org.cheetahv2.antigravity.client.utility;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AutoSacredShieldModule
 *
 * Equips the Sacred Shield (Divine Intervention) to offhand when HP ≤ threshold.
 * Un-equips and returns it exactly where it came from when HP recovers.
 *
 * Detection: item name contains "sacred" AND lore contains "divine intervention".
 * No Items.SHIELD requirement — works with custom server items.
 *
 * Tick-delay state machine (one packet per tick, server processes each before next):
 *
 *  EQUIP path:
 *   IDLE → (trigger) → EQUIP_STEP1 → [if from main inv: swap mainInv↔hotbar8]
 *                                   → EQUIP_STEP2 → swap hotbar8↔offhand → EQUIPPED
 *
 *  UNEQUIP path:
 *   EQUIPPED → (hp recovers) → UNEQUIP_STEP1 → swap offhand↔hotbar8
 *                             → UNEQUIP_STEP2 → [if from hotbar: swap hotbar8↔origHotbar]
 *                                              → [if from main: swap mainInvScreen↔hotbar8]
 *                             → IDLE
 */
public class AutoSacredShieldModule implements UtilityModule {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled          = false;
        /** Health (half-hearts) at or below which to equip. Default = 8 (4 hearts). */
        public float   triggerHealth    = 8.0f;
        /** Minimum ms between state transitions. */
        public int     actionCooldownMs = 500;
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_sacred_shield.json");

    // ── State machine ─────────────────────────────────────────────────────
    private enum Phase { IDLE, EQUIP_STEP1, EQUIP_STEP2, EQUIPPED, UNEQUIP_STEP1, UNEQUIP_STEP2 }
    private Phase phase           = Phase.IDLE;
    private long  lastActionMs    = 0L;

    // Set during EQUIP_STEP1, used during UNEQUIP_STEP2
    private int   shieldInvSlot   = -1;   // original 0-35 slot (-1 = was in offhand)
    private int   shieldScrSlot   = -1;   // screen-handler slot for shieldInvSlot

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Auto Sacred Shield"; }
    @Override public String getDescription()    { return "Equips Sacred Shield near death"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (!config.enabled || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        // Throttle every transition
        if (now - lastActionMs < config.actionCooldownMs) return;

        float hp      = mc.player.getHealth();
        boolean low   = hp <= config.triggerHealth && hp > 0;

        switch (phase) {

            case IDLE: {
                if (low) phase = Phase.EQUIP_STEP1;
                break;
            }

            case EQUIP_STEP1: {
                if (!low) { phase = Phase.IDLE; break; }

                // SMART gates: pause while a container screen is open or
                // another module is mid-sequence (all stage via hotbar 8).
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("sacred", 1500)) break;

                // Already in offhand?
                if (isSacredShield(mc.player.getOffHandStack())) {
                    shieldInvSlot = -1;
                    shieldScrSlot = -1;
                    phase = Phase.EQUIPPED;
                    lastActionMs = now;
                    ModuleSync.release("sacred"); // nothing to move
                    break;
                }

                int slot = findSacredShield(mc);
                if (slot < 0) { reset(); break; }  // not found

                shieldInvSlot = slot;
                shieldScrSlot = slot < 9 ? 36 + slot : slot;

                if (slot >= 9) {
                    // Main inventory → hotbar 8 (this tick)
                    sendSwap(mc, (short) shieldScrSlot, (byte) 8);
                    phase = Phase.EQUIP_STEP2;  // next tick: hotbar8 → offhand
                } else {
                    // Already in hotbar — skip straight to offhand swap
                    phase = Phase.EQUIP_STEP2;
                    // Don't consume the tick delay — fall through handled next tick
                }
                lastActionMs = now;
                break;
            }

            case EQUIP_STEP2: {
                if (!low) { reset(); break; }
                // Hotbar slot 8 (screen 44) → offhand (button 40)
                sendSwap(mc, (short) 44, (byte) 40);
                phase = Phase.EQUIPPED;
                lastActionMs = now;
                ModuleSync.release("sacred"); // equip sequence finished
                mc.player.sendMessage(Text.literal("§d✦ §fSacred Shield equipped!"), true);
                break;
            }

            case EQUIPPED: {
                if (!low) phase = Phase.UNEQUIP_STEP1;
                // If shield was removed externally, reset
                if (shieldInvSlot == -1 && !isSacredShield(mc.player.getOffHandStack())) {
                    phase = Phase.IDLE;
                }
                break;
            }

            case UNEQUIP_STEP1: {
                if (!isSacredShield(mc.player.getOffHandStack())) {
                    // Gone externally
                    reset();
                    break;
                }
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("sacred", 1500)) break;
                // Offhand (screen 45) → hotbar slot 8 (button 40)
                sendSwap(mc, (short) 45, (byte) 40);
                phase = Phase.UNEQUIP_STEP2;
                lastActionMs = now;
                break;
            }

            case UNEQUIP_STEP2: {
                // Shield is now in hotbar slot 8 — move it back to where it came from
                if (shieldInvSlot >= 0 && shieldInvSlot < 9) {
                    // Came from another hotbar slot — swap hotbar8 ↔ that slot
                    sendSwap(mc, (short) 44, (byte) shieldInvSlot);
                } else if (shieldInvSlot >= 9) {
                    // Came from main inventory — swap hotbar8 ↔ original main-inv screen slot
                    sendSwap(mc, (short) shieldScrSlot, (byte) 8);
                }
                // shieldInvSlot == -1: was already in offhand; nothing to restore
                mc.player.sendMessage(Text.literal("§7✦ Sacred Shield unequipped."), true);
                reset();
                lastActionMs = now;
                break;
            }
        }
    }

    private void reset() {
        phase         = Phase.IDLE;
        shieldInvSlot = -1;
        shieldScrSlot = -1;
        ModuleSync.release("sacred");
    }

    // ── Item detection ────────────────────────────────────────────────────

    private int findSacredShield(MinecraftClient mc) {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (isSacredShield(mc.player.getInventory().getStack(i))) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (isSacredShield(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private boolean isSacredShield(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String name = stack.getName().getString().toLowerCase();
        if (!name.contains("sacred")) return false;
        return hasLoreContaining(stack, "divine intervention");
    }

    private boolean hasLoreContaining(ItemStack stack, String keyword) {
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return false;
        for (net.minecraft.text.Text line : lore.lines()) {
            if (line.getString().toLowerCase().contains(keyword)) return true;
            for (net.minecraft.text.Text sib : line.getSiblings()) {
                if (sib.getString().toLowerCase().contains(keyword)) return true;
            }
        }
        return false;
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
