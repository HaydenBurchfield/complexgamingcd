package org.cheetahv2.antigravity.client.utility;

import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.cheetahv2.antigravity.client.util.ConfigHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AbilityKeybindModule — bind ANY key to an abilities-menu slot.
 *
 * Each bind maps a raw keyboard key → a menu slot (1-9). Pressing the key
 * runs the server's keybind-menu sequence:
 *
 *   double-tap swap-hands (opens the abilities menu)
 *     → select hotbar slot N (the menu reads the held-slot change)
 *     → if you were ALREADY on slot N, one extra swap-hands press instead
 *       (a slot change wouldn't register — same trick Sell Soul uses)
 *     → restore your previous hotbar slot
 *
 * Binds are managed in /ccsettings → Modules → Ability Keybinds: click
 * "+ Add Bind", press any key, pick the slot with the steppers. Keys are
 * polled raw via GLFW so anything on the keyboard works, not just keys
 * Minecraft lets you register. Sequences are silent, spam-safe (a held key
 * chains), and coordinate with the other modules via ModuleSync.
 */
public class AbilityKeybindModule implements UtilityModule {

    // ── Config ────────────────────────────────────────────────────────────
    public static class Bind {
        public int    keyCode  = -1;   // GLFW key code (-1 = unset)
        public String keyName  = "?";  // display name for the UI
        public int    menuSlot = 1;    // abilities menu slot 1-9
    }

    public static class Config {
        public boolean enabled = true;
        /** Delay between the F-taps. */
        public int actionDelayMs = 100;
        /** Extra wait after the 2nd F-tap so the menu is open before selecting. */
        public int menuConfirmDelayMs = 250;
        public List<Bind> binds = new ArrayList<>();
    }

    private Config config = new Config();
    private static final Path FILE = Paths.get("config", "antigravity", "module_ability_keys.json");

    public static final int MAX_BINDS = 9;

    // ── State machine ─────────────────────────────────────────────────────
    private enum Phase { IDLE, SWAP_HANDS_1, SWAP_HANDS_2, CONFIRM, RESTORE }
    private Phase phase        = Phase.IDLE;
    private long  lastActionMs = 0L;
    private int   targetSlot   = -1;  // hotbar index (menuSlot - 1)
    private int   prevSelected = -1;

    /** Edge detection per key code. */
    private final Map<Integer, Boolean> prevDown = new HashMap<>();

    // ── UtilityModule impl ────────────────────────────────────────────────
    @Override public String getName()           { return "Ability Keybinds"; }
    @Override public String getDescription()    { return "Any key → abilities menu slot (F-F-N)"; }
    @Override public boolean isEnabled()        { return config.enabled; }
    @Override public void setEnabled(boolean v) { config.enabled = v; save(); }

    @Override
    public void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.getWindow() == null) return;

        // ── Advance an active sequence ────────────────────────────────────
        if (phase != Phase.IDLE) {
            stepSequence(mc);
            return;
        }

        // ── Poll bound keys (raw GLFW — works for any key) ───────────────
        if (mc.currentScreen != null) { prevDown.clear(); return; }
        long win = mc.getWindow().getHandle();

        for (Bind bind : config.binds) {
            if (bind.keyCode <= 0) continue;
            boolean down = GLFW.glfwGetKey(win, bind.keyCode) == GLFW.GLFW_PRESS;
            boolean was  = prevDown.getOrDefault(bind.keyCode, false);
            prevDown.put(bind.keyCode, down);

            // Fire on press; a HELD key re-fires as soon as the previous
            // sequence finishes (spam-friendly, same as Sell Soul).
            if (down && (!was || phase == Phase.IDLE)) {
                start(mc, bind.menuSlot);
                if (phase != Phase.IDLE) break; // one sequence at a time
            }
        }
    }

    private void start(MinecraftClient mc, int menuSlot) {
        if (!config.enabled) return;
        if (menuSlot < 1 || menuSlot > 9) return;
        if (ModuleSync.inventoryLocked(mc) || ModuleSync.isBusy("abilitykeys")) return;

        ModuleSync.acquire("abilitykeys", 2000);
        targetSlot   = menuSlot - 1;
        prevSelected = -1;
        phase        = Phase.SWAP_HANDS_1;
        lastActionMs = 0L;
    }

    private void stepSequence(MinecraftClient mc) {
        long now  = System.currentTimeMillis();
        long wait = (phase == Phase.CONFIRM) ? config.menuConfirmDelayMs : config.actionDelayMs;
        if (now - lastActionMs < wait) return;

        switch (phase) {

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
                if (selected == targetSlot) {
                    // Already holding the target slot — no slot-change packet
                    // would fire, so activate the held item with a right-click
                    // instead.
                    if (mc.interactionManager != null) {
                        mc.interactionManager.interactItem(mc.player, net.minecraft.util.Hand.MAIN_HAND);
                        mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    }
                } else {
                    prevSelected = selected;
                    mc.player.getInventory().setSelectedSlot(targetSlot);
                }
                phase = Phase.RESTORE;
                lastActionMs = now;
                break;
            }

            case RESTORE: {
                if (prevSelected >= 0) {
                    mc.player.getInventory().setSelectedSlot(prevSelected);
                    prevSelected = -1;
                }
                reset();
                lastActionMs = now;
                break;
            }

            default:
                reset();
                break;
        }
    }

    private void reset() {
        phase        = Phase.IDLE;
        targetSlot   = -1;
        prevSelected = -1;
        ModuleSync.release("abilitykeys");
    }

    // ── Bind management (used by the settings UI) ─────────────────────────

    public Bind addBind() {
        if (config.binds.size() >= MAX_BINDS) return null;
        Bind b = new Bind();
        config.binds.add(b);
        save();
        return b;
    }

    public void removeBind(int index) {
        if (index >= 0 && index < config.binds.size()) {
            config.binds.remove(index);
            save();
        }
    }

    // ── Packet helper ─────────────────────────────────────────────────────

    private void sendSwapHands(MinecraftClient mc) {
        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                BlockPos.ORIGIN, Direction.DOWN));
    }

    // ── Config persistence ────────────────────────────────────────────────
    @Override public void save() { ConfigHelper.save(FILE, config); }
    @Override public void load() {
        Config c = ConfigHelper.load(FILE, Config.class);
        if (c != null) {
            config = c;
            if (config.binds == null) config.binds = new ArrayList<>();
        }
    }

    public Config getConfig() { return config; }
}
