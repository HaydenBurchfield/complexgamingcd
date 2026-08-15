package org.cheetahv2.antigravity.client.utility;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AutoTropicalShieldModule
 *
 * Equips the Tropical Shield to offhand when HP ≤ threshold.
 * Un-equips and returns it exactly where it came from when HP recovers.
 *
 * Detection: item name contains "tropical". No Items.SHIELD requirement.
 *
 * Tick-delay state machine (one packet per tick):
 *
 *  EQUIP path:
 *   IDLE → EQUIP_STEP1 → [if from main inv: swap mainInv↔hotbar8]
 *                       → EQUIP_STEP2 → swap hotbar8↔offhand → EQUIPPED
 *
 *  UNEQUIP path:
 *   EQUIPPED → UNEQUIP_STEP1 → swap offhand↔hotbar8
 *            → UNEQUIP_STEP2 → swap hotbar8 back to original slot → IDLE
 */
public class AutoTropicalShieldModule implements UtilityModule {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Config {
        public boolean enabled          = false;
        /** Health (half-hearts) at or below which to equip. Default = 14 (7 hearts). */
        public float   triggerHealth    = 14.0f;
        /** Minimum ms between state transitions. */
        public int     actionCooldownMs = 500;
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_tropical_shield.json");

    // ── State machine ─────────────────────────────────────────────────────
    private enum Phase { IDLE, EQUIP_STEP1, EQUIP_STEP2, EQUIPPED, UNEQUIP_STEP1, UNEQUIP_STEP2 }
    private Phase phase           = Phase.IDLE;
    private long  lastActionMs    = 0L;

    // Set during EQUIP_STEP1, used during UNEQUIP_STEP2
    private int   shieldInvSlot   = -1;   // original 0-35 slot (-1 = was in offhand)
    private int   shieldScrSlot   = -1;   // screen-handler slot for shieldInvSlot

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Auto Tropical Shield"; }
    @Override public String getDescription()    { return "Equips Tropical Shield below threshold"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (!config.enabled || mc.player == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        if (now - lastActionMs < config.actionCooldownMs) return;

        float hp    = mc.player.getHealth();
        boolean low = hp <= config.triggerHealth && hp > 0;

        switch (phase) {

            case IDLE: {
                if (low) phase = Phase.EQUIP_STEP1;
                break;
            }

            case EQUIP_STEP1: {
                if (!low) { phase = Phase.IDLE; break; }

                // SMART: Sacred outranks Tropical. Never kick a Sacred Shield
                // out of the offhand, and if the Sacred module is enabled and
                // its (more urgent) threshold is hit, stand down and let it act.
                if (offhandHasSacred(mc)) { phase = Phase.IDLE; break; }
                var mgr = UtilityModuleManager.INSTANCE;
                if (mgr.AUTO_SACRED_SHIELD.isEnabled()
                        && hp <= mgr.AUTO_SACRED_SHIELD.getConfig().triggerHealth) {
                    break; // sacred module will take this emergency
                }

                // Pause while a container is open / another module is mid-sequence
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("tropical", 1500)) break;

                // Already in offhand?
                if (isTropicalShield(mc.player.getOffHandStack())) {
                    shieldInvSlot = -1;
                    shieldScrSlot = -1;
                    phase = Phase.EQUIPPED;
                    lastActionMs = now;
                    ModuleSync.release("tropical");
                    break;
                }

                int slot = findTropicalShield(mc);
                if (slot < 0) { reset(); break; }

                shieldInvSlot = slot;
                shieldScrSlot = slot < 9 ? 36 + slot : slot;

                if (slot >= 9) {
                    // Main inventory → hotbar 8 (this tick's packet)
                    sendSwap(mc, (short) shieldScrSlot, (byte) 8);
                    phase = Phase.EQUIP_STEP2;
                } else {
                    // Already in hotbar — skip main-inv swap, go to offhand next tick
                    phase = Phase.EQUIP_STEP2;
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
                ModuleSync.release("tropical"); // equip sequence finished
                mc.player.sendMessage(Text.literal("§a✦ Tropical Shield equipped!"), true);
                break;
            }

            case EQUIPPED: {
                if (!low) phase = Phase.UNEQUIP_STEP1;
                // If shield was removed externally, reset
                if (shieldInvSlot == -1 && !isTropicalShield(mc.player.getOffHandStack())) {
                    phase = Phase.IDLE;
                }
                break;
            }

            case UNEQUIP_STEP1: {
                if (!isTropicalShield(mc.player.getOffHandStack())) {
                    reset();
                    break;
                }
                if (ModuleSync.inventoryLocked(mc)) break;
                if (!ModuleSync.acquire("tropical", 1500)) break;
                // Offhand (screen 45) → hotbar slot 8 (button 40)
                sendSwap(mc, (short) 45, (byte) 40);
                phase = Phase.UNEQUIP_STEP2;
                lastActionMs = now;
                break;
            }

            case UNEQUIP_STEP2: {
                // Shield is now in hotbar slot 8 — return it to where it came from
                if (shieldInvSlot >= 0 && shieldInvSlot < 9) {
                    // Came from hotbar — swap hotbar8 ↔ that slot
                    sendSwap(mc, (short) 44, (byte) shieldInvSlot);
                } else if (shieldInvSlot >= 9) {
                    // Came from main inventory — swap hotbar8 ↔ original main-inv screen slot
                    sendSwap(mc, (short) shieldScrSlot, (byte) 8);
                }
                // shieldInvSlot == -1: was already in offhand; nothing to restore
                mc.player.sendMessage(Text.literal("§7✦ Tropical Shield unequipped."), true);
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
        ModuleSync.release("tropical");
    }

    /** True if the offhand currently holds a Sacred Shield (never displace it). */
    private boolean offhandHasSacred(MinecraftClient mc) {
        ItemStack off = mc.player.getOffHandStack();
        return off != null && !off.isEmpty()
                && off.getName().getString().toLowerCase().contains("sacred");
    }

    // ── Item detection ────────────────────────────────────────────────────

    private int findTropicalShield(MinecraftClient mc) {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (isTropicalShield(mc.player.getInventory().getStack(i))) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (isTropicalShield(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
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
