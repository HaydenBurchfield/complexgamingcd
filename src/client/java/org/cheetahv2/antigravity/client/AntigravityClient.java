package org.cheetahv2.antigravity.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.cheetahv2.antigravity.client.cooldown.AbilityCooldownManager;
import org.cheetahv2.antigravity.client.cooldown.CustomCooldownSystem;
import org.cheetahv2.antigravity.client.cooldown.RuneAvailabilityHud;
import org.cheetahv2.antigravity.client.detection.RunicObstructionManager;
import org.cheetahv2.antigravity.client.gui.ItemInspectScreen;
import org.cheetahv2.antigravity.client.gui.SignatureSettingsScreen;
import org.cheetahv2.antigravity.client.mixin.HandledScreenAccessor;
import org.cheetahv2.antigravity.client.tracker.EventScheduleManager;
import org.cheetahv2.antigravity.client.tracker.EventScheduleHud;
import org.cheetahv2.antigravity.client.tracker.FrozenPlayerTracker;
import org.cheetahv2.antigravity.client.tracker.PlayerTracker;
import org.cheetahv2.antigravity.client.detection.LarkManager;
import org.cheetahv2.antigravity.client.detection.CustomGlowManager;
import org.cheetahv2.antigravity.client.detection.CustomHeadLabelManager;
import org.cheetahv2.antigravity.client.gui.ViewInvScreen;
import org.cheetahv2.antigravity.client.util.SignatureManager;
import org.cheetahv2.antigravity.client.detection.TargetManager;
import org.cheetahv2.antigravity.client.gui.TargetListScreen;
import org.cheetahv2.antigravity.client.utility.UtilityModuleManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class AntigravityClient implements ClientModInitializer {


    public static String USER = "CheetahV2";


    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("antigravity", "main"));

    // Keybindings
    public static KeyBinding invseeKey;
    public static KeyBinding toggleHudKey;
    public static KeyBinding inspectItemKey;
    public static KeyBinding toggleCratePullerKey;   // show/hide crate puller on hover tooltip

    private static boolean prevInspectKeyDown = false;

    // Singletons
    public static final PlayerTracker PLAYER_TRACKER = new PlayerTracker();
    public static final LarkManager LARK_MANAGER = new LarkManager();
    public static final FrozenPlayerTracker FROZEN_TRACKER = new FrozenPlayerTracker();
    public static final AbilityCooldownManager ABILITY_COOLDOWN = new AbilityCooldownManager();
    public static final NotifManager NOTIF_MANAGER = new NotifManager();
    public static final HudSettings HUD_SETTINGS = new HudSettings();
    public static final EventScheduleManager EVENT_SCHEDULE = new EventScheduleManager();
    public static final UtilityModuleManager UTILITY_MODULES = UtilityModuleManager.INSTANCE;
    public static final RuneAvailabilityHud  RUNE_AVAIL_HUD  = new RuneAvailabilityHud();
    public static final org.cheetahv2.antigravity.client.cooldown.MoodSwingsHud MOOD_HUD =
            new org.cheetahv2.antigravity.client.cooldown.MoodSwingsHud();

    // Drag states
    public static boolean larkHudDragging = false;
    private static int larkDragOffX = 0, larkDragOffY = 0;

    /** Toggle key: press once to enter Lark drag mode, press again to exit. */
    public static KeyBinding larkDragModeKey;
    public static KeyBinding autoClickKey;
    public static KeyBinding sellSoulKey;
    public static KeyBinding tikiKey;
    public static KeyBinding copyGuiKey;
    private static boolean prevCopyGuiKeyDown = false;
    /** True when the player has pressed the drag-mode key and we show the ghost box. */
    public static boolean larkDragModeActive = false;
    private static boolean prevLarkDragKeyDown = false;

    // Auto-fix state
    /** Set true when combat-tag-expired is detected in chat; consumed next tick. */
    private boolean pendingFixCommand = false;
    /** Set true on death so we send /fix the moment the player respawns. */
    private boolean waitingRespawnFix = false;
    /** Set true when a kill is detected in chat; consumed next tick. */
    private boolean pendingKillFix = false;

    // ============================================================
    //  HudSettings
    // ============================================================
    public static class HudSettings {
        public boolean showStatusBar = true;
        public boolean showCratePuller = true;   // toggle: show who pulled from crate on hover
        /** Toast notifications (lark alerts, rune procs, ...). Off by default. */
        public boolean notificationsEnabled = false;
        public int larkHudX = 10;
        public int larkHudY = 220;
        public float larkScale = 1.0f;
        public float labelScale = 3f;
        public float larkLabelScale = 5.0f;
        public float labelHeightOffset = 1f;

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path FILE = Paths.get("config", "antigravity", "hud.json");

        public void save() {
            try {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, GSON.toJson(this));
            } catch (IOException ignored) {}
        }

        public void load() {
            if (!Files.exists(FILE)) return;
            try {
                HudSettings s = GSON.fromJson(Files.readString(FILE), HudSettings.class);
                if (s == null) return;
                showStatusBar = s.showStatusBar;
                showCratePuller = s.showCratePuller;
                notificationsEnabled = s.notificationsEnabled;
                larkHudX = s.larkHudX;
                larkHudY = s.larkHudY;
                if (s.larkScale > 0) larkScale = s.larkScale;
                if (s.labelScale > 0) labelScale = s.labelScale;
                if (s.larkLabelScale > 0) larkLabelScale = s.larkLabelScale;
                labelHeightOffset = s.labelHeightOffset;
            } catch (IOException ignored) {}
        }
    }




    // ============================================================
    //  NotifManager
    // ============================================================
    // ============================================================
    //  NotifManager  — drop-in replacement for the inner class in
    //  AntigravityClient.java
    //
    //  Changes vs original:
    //   • anchorX / anchorY stored and persisted (default -1 = top-right corner)
    //   • saveAnchor() / loadAnchor()
    //   • getQueue() accessor so AntigravityClient can list active toasts
    //   • tickDrag() — Shift+LMB drag in-game (no screen needed)
    //   • render() now uses anchorX/anchorY if set, otherwise falls back to
    //     top-right corner behaviour identical to before
    // ============================================================
    // ============================================================
    //  NotifManager  — drop-in replacement for the inner class in
    //  AntigravityClient.java
    //
    //  Changes vs original:
    //   • anchorX / anchorY stored and persisted (default -1 = top-right corner)
    //   • saveAnchor() / loadAnchor()
    //   • getQueue() accessor so AntigravityClient can list active toasts
    //   • tickDrag() — Shift+LMB drag in-game (no screen needed)
    //   • render() now uses anchorX/anchorY if set, otherwise falls back to
    //     top-right corner behaviour identical to before
    // ============================================================
    public static class NotifManager {
        public static class Notif {
            public String text;
            public int color;
            public long expiresAt;
            public Notif(String t, int col, long dur) {
                text = t; color = col;
                expiresAt = System.currentTimeMillis() + dur;
            }
        }

        private final java.util.concurrent.CopyOnWriteArrayList<Notif> queue =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Drag anchor. -1 = use default top-right corner. */
        public int anchorX = -1;
        public int anchorY = -1;

        // drag state
        private boolean dragging = false;
        private int dragOffX, dragOffY;
        private static final int TOAST_W = 200; // approximate max width

        // persistence
        private static final java.nio.file.Path ANCHOR_FILE =
                java.nio.file.Paths.get("config", "antigravity", "notif_anchor.json");

        public void saveAnchor() {
            try {
                var obj = new com.google.gson.JsonObject();
                obj.addProperty("anchorX", anchorX);
                obj.addProperty("anchorY", anchorY);
                java.nio.file.Files.createDirectories(ANCHOR_FILE.getParent());
                java.nio.file.Files.writeString(ANCHOR_FILE,
                        new com.google.gson.GsonBuilder().create().toJson(obj));
            } catch (Exception ignored) {}
        }

        public void loadAnchor() {
            if (!java.nio.file.Files.exists(ANCHOR_FILE)) return;
            try {
                var obj = new com.google.gson.JsonParser().parse(
                        java.nio.file.Files.readString(ANCHOR_FILE)).getAsJsonObject();
                anchorX = obj.get("anchorX").getAsInt();
                anchorY = obj.get("anchorY").getAsInt();
            } catch (Exception ignored) {}
        }

        public java.util.List<Notif> getQueue() {
            return java.util.Collections.unmodifiableList(queue);
        }

        public void push(String text, int color, long durationMs) {
            // Respect the global toggle (/ccsettings → HUD → Toast notifications)
            if (!HUD_SETTINGS.notificationsEnabled) return;
            queue.add(new Notif(text, color, durationMs));
            while (queue.size() > 8) queue.remove(0);
        }

        public void push(String text, int color) { push(text, color, 4000); }

        public void tick() {
            queue.removeIf(n -> n.expiresAt <= System.currentTimeMillis());
        }

        /**
         * Shift+LMB drag in-game (no screen).
         * Call from ClientTickEvents.END_CLIENT_TICK.
         */
        public void tickDrag(net.minecraft.client.MinecraftClient mc) {
            if (mc == null || mc.currentScreen != null || mc.getWindow() == null) return;

            long win    = mc.getWindow().getHandle();
            double[] mx = new double[1], my = new double[1];
            org.lwjgl.glfw.GLFW.glfwGetCursorPos(win, mx, my);
            double gx = mx[0] / mc.getWindow().getScaleFactor();
            double gy = my[0] / mc.getWindow().getScaleFactor();

            boolean lmb   = org.lwjgl.glfw.GLFW.glfwGetMouseButton(win, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            boolean shift  = org.lwjgl.glfw.GLFW.glfwGetKey(win, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT)  == org.lwjgl.glfw.GLFW.GLFW_PRESS
                    || org.lwjgl.glfw.GLFW.glfwGetKey(win, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;

            int sw = mc.getWindow().getScaledWidth();
            int bx = (anchorX >= 0) ? anchorX : (sw - TOAST_W - 10);
            int by = (anchorY >= 0) ? anchorY : 10;
            int bh = Math.max(12, queue.size() * 14);

            if (lmb && shift && !dragging && gx >= bx - 4 && gx <= bx + TOAST_W + 4
                    && gy >= by && gy <= by + bh) {
                dragging  = true;
                dragOffX  = (int)(gx - bx);
                dragOffY  = (int)(gy - by);
            }

            if (dragging) {
                if (lmb) {
                    anchorX = (int)(gx - dragOffX);
                    anchorY = (int)(gy - dragOffY);
                } else {
                    dragging = false;
                    saveAnchor();
                }
            }
        }

        public void render(net.minecraft.client.gui.DrawContext ctx, net.minecraft.client.MinecraftClient mc) {
            if (queue.isEmpty()) return;
            net.minecraft.client.font.TextRenderer tr = mc.textRenderer;
            int sh = mc.getWindow().getScaledHeight();
            int sw = mc.getWindow().getScaledWidth();
            long now = System.currentTimeMillis();

            // Determine anchor: custom if set, else top-right corner
            boolean customAnchor = anchorX >= 0 && anchorY >= 0;
            int baseY = customAnchor ? anchorY : 10;
            int y = baseY;

            int i = 0;
            for (Notif n : queue) {
                if (n.expiresAt <= now) continue;
                long rem   = n.expiresAt - now;
                int alpha  = (rem < 500) ? (int)(rem * 255 / 500) : 255;
                String text = colorize(n.text);
                int tw2 = tr.getWidth(text);
                // Anchor x: custom or snap to right edge
                int x = customAnchor ? anchorX : (sw - tw2 - 18);

                ctx.fill(x - 4, y - 2, x + tw2 + 6, y + 10, (alpha << 24) | 0x0B0718);
                gborder(ctx, x - 4, y - 2, tw2 + 10, 12, ((alpha / 3) << 24) | 0x008D77E8);
                // Dragging indicator
                if (dragging && i == 0) {
                    gborder(ctx, x - 4, y - 2, tw2 + 10, 12, 0xCCFFD060);
                }
                ctx.drawTextWithShadow(tr, text, x, y, (n.color & 0x00FFFFFF) | (alpha << 24));
                y += 14;
                if (y > sh - 20) break;
                i++;
            }
        }

        private void gborder(net.minecraft.client.gui.DrawContext c, int x, int y, int w, int h, int col) {
            c.fill(x, y, x + w, y + 1, col);
            c.fill(x, y + h - 1, x + w, y + h, col);
            c.fill(x, y, x + 1, y + h, col);
            c.fill(x + w - 1, y, x + w, y + h, col);
        }
    }

    private static boolean checkedUser = false;
    private static final String ALLOWED_USER = "CheetahV2";

    @Override
    public void onInitializeClient() {

        ModAuth.beginCheck();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            String username = client.player.getGameProfile().name();

            if (!username.equals(ALLOWED_USER)) {
                client.scheduleStop();
            }
        });


        invseeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.invsee", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY
        ));
        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.toggle_hud", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY
        ));
        inspectItemKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.inspect_item",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,          // default: Z — change freely in Controls screen
                CATEGORY
        ));
        toggleCratePullerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.toggle_crate_puller",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,          // default: G — rebind freely in Controls screen
                CATEGORY
        ));
        larkDragModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.lark_drag_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_L,          // default: L — toggle Lark HUD drag mode
                CATEGORY
        ));
        autoClickKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.auto_click",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,         // default: F8 — toggle AutoClick
                CATEGORY
        ));
        sellSoulKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.sell_soul",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,    // unbound by default — set in Controls screen
                CATEGORY
        ));
        tikiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.tiki",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,    // unbound by default — set in Controls screen
                CATEGORY
        ));
        copyGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.copy_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,         // default: F6 — dump open GUI to clipboard
                CATEGORY
        ));


        KeyBinding cookieToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.antigravity.cookie_toggle",
                GLFW.GLFW_KEY_F9,
                CATEGORY
        ));
        // Load configs
        HUD_SETTINGS.load();
        LARK_MANAGER.load();
        FROZEN_TRACKER.load();
        ABILITY_COOLDOWN.load();
        EVENT_SCHEDULE.load();
        RUNE_AVAIL_HUD.load();
        MOOD_HUD.load();
        SignatureManager.load();
        org.cheetahv2.antigravity.client.util.LoreCommandConfig.load();
        NOTIF_MANAGER.loadAnchor();
        TargetManager.load();
        UTILITY_MODULES.load();



        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // /sign [name]            — send the active (or named) preset
            // /sign list              — list saved presets
            // /sign use <name>        — set the active preset
            // /sign delete <name>     — delete a preset
            // /sign gui               — open the editor
            dispatcher.register(ClientCommandManager.literal("sign")
                    // Bare /sign opens the picker GUI (choose / edit / new)
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null) return 0;
                        mc.send(() -> mc.setScreen(
                                new org.cheetahv2.antigravity.client.gui.SignaturePickerScreen()));
                        return 1;
                    })
                    .then(ClientCommandManager.literal("send")
                            .executes(ctx -> sendSignature(SignatureManager.getActiveName())))
                    .then(ClientCommandManager.literal("list").executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null || mc.player == null) return 0;
                        mc.player.sendMessage(Text.literal("§d✦ §fSignature presets:"), false);
                        for (String n : SignatureManager.names()) {
                            var p = SignatureManager.get(n);
                            boolean active = n.equalsIgnoreCase(SignatureManager.getActiveName());
                            mc.player.sendMessage(Text.literal(
                                    (active ? "  §a▸ §f" : "  §8▸ §7") + n
                                            + " §8(" + (p == null ? 0 : p.lines.size()) + " lines)"), false);
                        }
                        return 1;
                    }))
                    // /sign preview — print the exact commands (with lengths)
                    // without sending anything
                    .then(ClientCommandManager.literal("preview").executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null || mc.player == null) return 0;
                        var cmds = SignatureManager.previewCommands(SignatureManager.getActiveName());
                        if (cmds.isEmpty()) {
                            mc.player.sendMessage(Text.literal("§7✦ Nothing to send."), false);
                            return 0;
                        }
                        var p = SignatureManager.get(SignatureManager.getActiveName());
                        mc.player.sendMessage(Text.literal("§d✦ §f" + SignatureManager.getActiveName()
                                + " §7— startLine=§f" + (p == null ? 1 : p.startLine)
                                + " §7clearFirst=§f" + (p != null && p.clearFirst)
                                + " §7lastWritten=§f" + (p == null ? 0 : p.lastWritten)), false);
                        for (String c : cmds) {
                            boolean over = c.length() > org.cheetahv2.antigravity.client.util
                                    .GradientUtil.MAX_COMMAND_LENGTH;
                            mc.player.sendMessage(Text.literal(
                                    (over ? "§c[" + c.length() + " TOO LONG] " : "§8[" + c.length() + "] ")
                                            + "§7/" + c), false);
                        }
                        return 1;
                    }))
                    // /sign place — drag the signature onto the held item
                    .then(ClientCommandManager.literal("place").executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null) return 0;
                        mc.send(() -> mc.setScreen(
                                new org.cheetahv2.antigravity.client.gui.SignaturePlaceScreen(
                                        SignatureManager.getActiveName())));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("gui").executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null) return 0;
                        mc.send(() -> mc.setScreen(new SignatureSettingsScreen()));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("use")
                            .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        MinecraftClient mc = MinecraftClient.getInstance();
                                        if (mc == null || mc.player == null) return 0;
                                        String n = StringArgumentType.getString(ctx, "name").trim();
                                        boolean ok = SignatureManager.setActive(n);
                                        mc.player.sendMessage(Text.literal(ok
                                                ? "§a✦ §fActive signature: §d" + n
                                                : "§c✦ §fNo preset named §f" + n), false);
                                        return ok ? 1 : 0;
                                    })))
                    .then(ClientCommandManager.literal("delete")
                            .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        MinecraftClient mc = MinecraftClient.getInstance();
                                        if (mc == null || mc.player == null) return 0;
                                        String n = StringArgumentType.getString(ctx, "name").trim();
                                        boolean ok = SignatureManager.delete(n);
                                        mc.player.sendMessage(Text.literal(ok
                                                ? "§c✦ §fDeleted preset §f" + n
                                                : "§7✦ No preset named " + n), false);
                                        return ok ? 1 : 0;
                                    })))
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                            .executes(ctx -> sendSignature(
                                    StringArgumentType.getString(ctx, "name").trim()))));

            // /siggrad <#hexA> <#hexB[,#hexC...]> <text>  — build a gradient
            // /siggrad item <text>                        — match held item's gradient
            // /siggrad rainbow <text>
            // Result is printed AND copied to the clipboard.
            dispatcher.register(ClientCommandManager.literal("siggrad")
                    .then(ClientCommandManager.literal("item")
                            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        MinecraftClient mc = MinecraftClient.getInstance();
                                        if (mc == null || mc.player == null) return 0;
                                        var held = mc.player.getMainHandStack();
                                        var stops = org.cheetahv2.antigravity.client.util.GradientUtil
                                                .sampleItemName(held);
                                        if (stops.isEmpty()) {
                                            mc.player.sendMessage(Text.literal(
                                                    "§c✦ §fHold an item with a coloured name first."), false);
                                            return 0;
                                        }
                                        return emitGradient(StringArgumentType.getString(ctx, "text"), stops,
                                                "matched §f" + held.getName().getString());
                                    })))
                    .then(ClientCommandManager.literal("rainbow")
                            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                    .executes(ctx -> emitGradient(
                                            StringArgumentType.getString(ctx, "text"),
                                            org.cheetahv2.antigravity.client.util.GradientUtil.rainbow(12),
                                            "rainbow"))))
                    .then(ClientCommandManager.argument("stops", StringArgumentType.word())
                            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        MinecraftClient mc = MinecraftClient.getInstance();
                                        if (mc == null || mc.player == null) return 0;
                                        String spec = StringArgumentType.getString(ctx, "stops");
                                        var stops = org.cheetahv2.antigravity.client.util.GradientUtil
                                                .parseStops(spec);
                                        if (stops.size() < 1) {
                                            mc.player.sendMessage(Text.literal(
                                                    "§c✦ §fUse hex stops like §f#10B1FF,#91FFFF"), false);
                                            return 0;
                                        }
                                        // A single pair reads best mirrored (dark→bright→dark)
                                        if (stops.size() == 2) {
                                            stops = org.cheetahv2.antigravity.client.util.GradientUtil.mirror(stops);
                                        }
                                        return emitGradient(StringArgumentType.getString(ctx, "text"),
                                                stops, spec);
                                    }))));

            // /signsettings — opens the signature editor GUI
            dispatcher.register(ClientCommandManager.literal("signsettings")
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null) return 0;
                        mc.send(() -> mc.setScreen(new SignatureSettingsScreen()));
                        return 1;
                    }));

            // /ccsettings — opens the main CGC settings GUI
            dispatcher.register(ClientCommandManager.literal("ccsettings")
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null) return 0;
                        mc.send(() -> mc.setScreen(new org.cheetahv2.antigravity.client.gui.AntigravityClient()));
                        return 1;
                    }));

            // /invsee <player> — view a player's gear; uses the live entity when
            // in render distance, otherwise the last cached snapshot of them.
            dispatcher.register(ClientCommandManager.literal("invsee")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                            .executes(ctx -> {
                                MinecraftClient mc = MinecraftClient.getInstance();
                                if (mc == null || mc.player == null || mc.world == null) return 0;
                                String name = StringArgumentType.getString(ctx, "player");

                                // Live player in render distance?
                                for (PlayerEntity p : mc.world.getPlayers()) {
                                    if (p.getName().getString().equalsIgnoreCase(name) && p != mc.player) {
                                        final PlayerEntity live = p;
                                        mc.send(() -> mc.setScreen(new ViewInvScreen(live)));
                                        return 1;
                                    }
                                }

                                // Fall back to the snapshot cache
                                var snap = PLAYER_TRACKER.getSnapshot(name);
                                if (snap != null) {
                                    mc.send(() -> mc.setScreen(new ViewInvScreen(snap)));
                                    return 1;
                                }

                                mc.player.sendMessage(Text.literal(
                                        "§c[CGC] §fHaven't seen §e" + name + "§f this session — no cached inventory."), false);
                                return 0;
                            })));

            // /bidwatch — manage the Bid War watch list
            dispatcher.register(ClientCommandManager.literal("bidwatch")
                    .executes(ctx -> bidwatchList())
                    .then(ClientCommandManager.literal("list").executes(ctx -> bidwatchList()))
                    .then(ClientCommandManager.literal("add")
                            .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        MinecraftClient mc = MinecraftClient.getInstance();
                                        if (mc == null || mc.player == null) return 0;
                                        String item = StringArgumentType.getString(ctx, "item");
                                        boolean added = UTILITY_MODULES.BID_WAR.addWatch(item);
                                        mc.player.sendMessage(Text.literal(added
                                                ? "§a[CGC] §fNow watching bid wars for: §e" + item.trim()
                                                : "§7[CGC] Already watching: " + item.trim()), false);
                                        return 1;
                                    })))
                    .then(ClientCommandManager.literal("remove")
                            .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        MinecraftClient mc = MinecraftClient.getInstance();
                                        if (mc == null || mc.player == null) return 0;
                                        String item = StringArgumentType.getString(ctx, "item");
                                        boolean removed = UTILITY_MODULES.BID_WAR.removeWatch(item);
                                        mc.player.sendMessage(Text.literal(removed
                                                ? "§c[CGC] §fRemoved bid war watch: §e" + item.trim()
                                                : "§7[CGC] Wasn't watching: " + item.trim()), false);
                                        return 1;
                                    }))));

            // /cgcmood — status dump; /cgcmood <mood> — test the HUD directly
            dispatcher.register(ClientCommandManager.literal("cgcmood")
                    .executes(ctx -> cgcMoodStatus())
                    .then(ClientCommandManager.literal("status").executes(ctx -> cgcMoodStatus()))
                    .then(ClientCommandManager.argument("mood", StringArgumentType.word())
                            .executes(ctx -> {
                                MOOD_HUD.setMood(StringArgumentType.getString(ctx, "mood"),
                                        MOOD_HUD.getConfig().defaultDurationMs);
                                return 1;
                            })));

            // /lore — open the lore editor for the held item
            dispatcher.register(ClientCommandManager.literal("lore")
                    .executes(ctx -> {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc == null) return 0;
                        mc.send(() -> mc.setScreen(
                                new org.cheetahv2.antigravity.client.gui.LoreEditorScreen()));
                        return 1;
                    }));

            // /target — open target list GUI
            dispatcher.register(ClientCommandManager.literal("target")
                .executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc == null) return 0;
                    mc.send(() -> mc.setScreen(new TargetListScreen()));
                    return 1;
                })
                // /target add <player> [color]
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            TargetManager.addTarget(player, TargetManager.DEFAULT_COLOR);
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc != null && mc.player != null)
                                mc.player.sendMessage(Text.literal("\u00A7aTarget added: \u00A7f" + player + " \u00A77(glow: \u00A7cred\u00A77)"), false);
                            return 1;
                        })
                        .then(ClientCommandManager.argument("color", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                String player   = StringArgumentType.getString(ctx, "player");
                                String colorStr = StringArgumentType.getString(ctx, "color");
                                int color = TargetManager.parseColor(colorStr);
                                TargetManager.addTarget(player, color);
                                MinecraftClient mc = MinecraftClient.getInstance();
                                if (mc != null && mc.player != null) {
                                    String hex = String.format("#%06X", color & 0xFFFFFF);
                                    mc.player.sendMessage(Text.literal("\u00A7aTarget added: \u00A7f" + player + " \u00A77(glow: \u00A7f" + hex + "\u00A77)"), false);
                                }
                                return 1;
                            })
                        )
                    )
                )
                // /target remove <player>
                .then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .executes(ctx -> {
                            String player = StringArgumentType.getString(ctx, "player");
                            boolean removed = TargetManager.removeTarget(player);
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc != null && mc.player != null)
                                mc.player.sendMessage(removed
                                    ? Text.literal("\u00A7cTarget removed: \u00A7f" + player)
                                    : Text.literal("\u00A77" + player + " was not a target."), false);
                            return 1;
                        })
                    )
                )
            );

            // /toggleclick — hold LMB with random releases; releases cursor without pause screen
            dispatcher.register(ClientCommandManager.literal("toggleclick")
                .executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc == null || mc.player == null) return 0;
                    boolean on = AutoClickManager.toggle();
                    if (on && mc.mouse != null) {
                        mc.mouse.unlockCursor();
                    }
                    mc.player.sendMessage(Text.literal(
                        on  ? "\u00A7a[CGC] AutoClick \u00A7lON \u00A7r\u00A77(cursor released \u2014 /toggleclick or F8 to stop)"
                            : "\u00A7c[CGC] AutoClick \u00A7lOFF"), false);
                    return 1;
                }));

            // /lockcam — toggle camera lock snapped to nearest cardinal yaw (0/90/180/-90), pitch 0
            dispatcher.register(ClientCommandManager.literal("lockcam")
                .executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc == null || mc.player == null) return 0;
                    LockCamManager.toggle(mc.player.getYaw(), mc.player.getPitch());
                    boolean now = LockCamManager.isLocked();
                    String msg = now
                        ? String.format("\u00A7bCamera locked \u00A77(yaw: \u00A7f%.0f\u00A77, pitch: \u00A7f0\u00A77)", LockCamManager.getLockedYaw())
                        : "\u00A77Camera unlocked.";
                    mc.player.sendMessage(Text.literal(msg), false);
                    return 1;
                })
            );

        });




        // Listen to Chat messages. The GAME event's second parameter is the
        // "overlay" flag — true for ACTION BAR messages, which is where the
        // server announces the active Mood Swings mood.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String plain = message.getString();
            if (overlay) {
                MOOD_HUD.onActionBar(plain.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim());
            }
            handleChatMessage(plain);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            handleChatMessage(message.getString());
        });

        // Tick loop
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) return;

            // ── AutoClick ────────────────────────────────────────────────────
            AutoClickManager.tick(); // drives random-pause timer
            // LMB injection is handled by MinecraftClientAutoClickMixin (fires
            // inside tick() before handleInputEvents, works focused + background)
            // ─────────────────────────────────────────────────────────────────

            // ── Auto /fix ────────────────────────────────────────────────────
            // Trigger 1: combat-tag expiry detected in chat (pendingFixCommand)
            if (pendingFixCommand) {
                client.player.networkHandler.sendChatCommand("fix all");
                pendingFixCommand = false;
            }
            // Trigger 2: player death → wait for respawn so the server receives it
            boolean isDead = client.player.isDead() || client.player.getHealth() <= 0f;
            if (isDead) {
                waitingRespawnFix = true;
            } else if (waitingRespawnFix) {
                // Player has respawned — fire /fix
                client.player.networkHandler.sendChatCommand("fix all");
                waitingRespawnFix = false;
            }
            // Trigger 3: kill detected in chat
            if (pendingKillFix) {
                client.player.networkHandler.sendChatCommand("fix all");
                pendingKillFix = false;
            }
            // ─────────────────────────────────────────────────────────────────

            PLAYER_TRACKER.tick(client);
            LARK_MANAGER.tick(client);
            FROZEN_TRACKER.tick(client);
            FROZEN_TRACKER.tickDrag(client);
            NOTIF_MANAGER.tick();
            NOTIF_MANAGER.tickDrag(client);
            RUNE_AVAIL_HUD.tick(client);
            SignatureManager.tickSend(client); // drains multi-line /sign queue
            org.cheetahv2.antigravity.client.util.ClientCommandQueue.tick(client); // lore editor batches
            ABILITY_COOLDOWN.tickDrag(client);
            // NOTE: tickKeys removed — ability cooldowns are chat-triggered only.
            CustomGlowManager.tick(client);
            TargetManager.tick(client);
            CustomHeadLabelManager.tick();
            RunicObstructionManager.tick(client);
            UTILITY_MODULES.tick(client);

            // Sell Soul: fire on press AND auto-repeat while held — trigger()
            // no-ops mid-sequence and starts the next sale as soon as the
            // previous one finishes, so holding the key spams sells.
            while (sellSoulKey.wasPressed()) {
                if (client.currentScreen == null) UTILITY_MODULES.AUTO_SELL_SOUL.trigger(client);
            }
            if (sellSoulKey.isPressed() && client.currentScreen == null) {
                UTILITY_MODULES.AUTO_SELL_SOUL.trigger(client);
            }

            // Tiki (Rain Dance chestplate): same press/hold-to-spam behaviour
            while (tikiKey.wasPressed()) {
                if (client.currentScreen == null) UTILITY_MODULES.AUTO_TIKI.trigger(client);
            }
            if (tikiKey.isPressed() && client.currentScreen == null) {
                UTILITY_MODULES.AUTO_TIKI.trigger(client);
            }


            // Raycast target player inspector hotkey (I key)
            if (invseeKey.wasPressed() && client.currentScreen == null) {
                PlayerEntity target = raycastPlayer(client, 50.0);
                if (target != null) {
                    client.setScreen(new ViewInvScreen(target));
                } else {

                }
            }

            while (inspectItemKey.wasPressed()) {
                ItemStack toInspect = resolveHoveredItem(client);
                if (toInspect != null && !toInspect.isEmpty()) {
                    client.setScreen(new ItemInspectScreen(toInspect));
                }
            }

            if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>) {
                long win = client.getWindow().getHandle();
                int keyCode = InputUtil.fromTranslationKey(inspectItemKey.getBoundKeyTranslationKey()).getCode();
                boolean down = GLFW.glfwGetKey(win, keyCode) == GLFW.GLFW_PRESS;
                if (down && !prevInspectKeyDown) {
                    ItemStack toInspect = resolveHoveredItem(client);
                    if (toInspect != null && !toInspect.isEmpty()) {
                        client.setScreen(new ItemInspectScreen(toInspect));
                    }
                }
                prevInspectKeyDown = down;

                // Copy-GUI key (works while any container screen is open)
                int copyCode = InputUtil.fromTranslationKey(copyGuiKey.getBoundKeyTranslationKey()).getCode();
                boolean copyDown = copyCode > 0 && GLFW.glfwGetKey(win, copyCode) == GLFW.GLFW_PRESS;
                if (copyDown && !prevCopyGuiKeyDown) {
                    String status = org.cheetahv2.antigravity.client.util.GuiDumper.dump(client);
                    if (status != null) client.player.sendMessage(Text.literal(status), false);
                }
                prevCopyGuiKeyDown = copyDown;
            } else {
                prevInspectKeyDown = false;
                prevCopyGuiKeyDown = false;

                // Copy-GUI key with no screen open → dumps your own inventory
                while (copyGuiKey.wasPressed()) {
                    String status = org.cheetahv2.antigravity.client.util.GuiDumper.dump(client);
                    if (status != null) client.player.sendMessage(Text.literal(status), false);
                }
            }


            // Toggle HUD view
            if (toggleHudKey.wasPressed()) {
                HUD_SETTINGS.showStatusBar ^= true;
                HUD_SETTINGS.save();
                client.player.sendMessage(Text.literal("§7[CGC] HUD layout visibility toggled."), false);
            }

            // Toggle crate puller overlay
            if (toggleCratePullerKey.wasPressed()) {
                HUD_SETTINGS.showCratePuller ^= true;
                HUD_SETTINGS.save();
                client.player.sendMessage(Text.literal(
                        "§7[CGC] Crate puller overlay: " + (HUD_SETTINGS.showCratePuller ? "§aON" : "§cOFF")), false);
            }

            // AutoClick toggle (F8 keybind)
            if (autoClickKey.wasPressed()) {
                boolean on = AutoClickManager.toggle();
                if (on && client.mouse != null) {
                    client.mouse.unlockCursor();
                }
                client.player.sendMessage(Text.literal(
                    on  ? "\u00A7a[CGC] AutoClick \u00A7lON \u00A7r\u00A77(cursor released)"
                        : "\u00A7c[CGC] AutoClick \u00A7lOFF"), false);
            }

            // Toggle Lark HUD drag mode — opens pause screen
            if (larkDragModeKey.wasPressed()) {
                if (client.currentScreen == null) {
                    larkDragModeActive = true;
                    client.setScreen(new org.cheetahv2.antigravity.client.gui.HudDragScreen());
                }
            }

            // Dragging Lark HUD in-game (only when Lark is active, no drag mode screen open)
            // Drag mode with screen open is handled by HudDragScreen
            boolean canDragLark = !larkDragModeActive && LARK_MANAGER.state != LarkManager.State.IDLE;
            if (client.currentScreen == null && client.getWindow() != null && canDragLark) {
                long win = client.getWindow().getHandle();
                double[] mx = new double[1], my = new double[1];
                GLFW.glfwGetCursorPos(win, mx, my);
                double gx = mx[0] / client.getWindow().getScaleFactor();
                double gy = my[0] / client.getWindow().getScaleFactor();
                boolean lmb = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean shift = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                int pw = 160, ph = 34;
                int px2 = (HUD_SETTINGS.larkHudX >= 0) ? HUD_SETTINGS.larkHudX : 10;
                int py2 = (HUD_SETTINGS.larkHudY >= 0) ? HUD_SETTINGS.larkHudY : (client.getWindow().getScaledHeight() - 40);

                if (lmb && shift && !larkHudDragging && gx >= px2 && gx <= px2 + pw && gy >= py2 && gy <= py2 + ph) {
                    larkHudDragging = true;
                    larkDragOffX = (int) (gx - px2);
                    larkDragOffY = (int) (gy - py2);
                }
                if (larkHudDragging) {
                    if (lmb) {
                        HUD_SETTINGS.larkHudX = (int) (gx - larkDragOffX);
                        HUD_SETTINGS.larkHudY = (int) (gy - larkDragOffY);
                    } else {
                        larkHudDragging = false;
                        HUD_SETTINGS.save();
                    }
                }
            } else if (larkHudDragging) {
                larkHudDragging = false;
                HUD_SETTINGS.save();
            }
        });



        // 2D HUD Overlays
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getWindow() == null) return;
            TextRenderer tr = mc.textRenderer;
            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();

            // Render Event Schedule HUD (independent — has its own toggle in /ccsettings)
            EventScheduleHud.render(ctx, mc, EVENT_SCHEDULE);

            // Mood Swings HUD (independent toggle)
            MOOD_HUD.render(ctx, mc);

            // Toast notifications (independent toggle)
            if (HUD_SETTINGS.notificationsEnabled) {
                NOTIF_MANAGER.render(ctx, mc);
            }

            if (!HUD_SETTINGS.showStatusBar) return;

            // Render Ability Cooldowns HUD
            ABILITY_COOLDOWN.renderHud(ctx, mc);

            // Render Rune Availability sidebar
            RUNE_AVAIL_HUD.render(ctx, mc);

            // Render Frozen player HUD
            FROZEN_TRACKER.renderHud(ctx, mc);


            // Render Lark Status HUD
            if (LARK_MANAGER.state != LarkManager.State.IDLE) {
                LARK_MANAGER.renderHud(ctx, tr, mc, sw, sh);
            }

            // Drag hint (only when in-game Shift+LMB drag is active for Lark)
            if (larkHudDragging) {
                String hint = "§eDragging Lark HUD";
                int tw = tr.getWidth(hint);
                ctx.drawTextWithShadow(tr, hint, sw / 2 - tw / 2, 8, 0xFFFFD060);
            }

            // Notifications are disabled — no render
        });

        // ── Crate Puller Tooltip Injection ─────────────────────────────────
        net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register(
                (stack, tooltipContext, tooltipType, lines) -> {
                    if (!HUD_SETTINGS.showCratePuller) return;
                    String puller = extractCratePuller(stack);
                    if (puller == null) return;
                    long ts = extractPullTimestamp(stack);
                    String datePart = ts > 0 ? " §8(§7" + formatPullDate(ts) + "§8)" : "";
                    lines.add(Text.empty());
                    lines.add(Text.literal(
                            "§8[§6Crate Pull§8] §7Pulled by: §e" + puller + datePart));
                });

        // 3D above-head entity labels
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.world == null || mc.player == null) return;

            net.minecraft.client.util.math.MatrixStack matrices = context.matrices();
            net.minecraft.client.render.Camera camera = context.gameRenderer().getCamera();
            if (matrices == null || camera == null) return;

            long now = System.currentTimeMillis();
            TextRenderer tr = mc.textRenderer;

            // 0. Outpost core waypoint beams (multiple can be active at once)
            org.cheetahv2.antigravity.client.tracker.OutpostBeamRenderer.render(
                    matrices, camera, mc, EVENT_SCHEDULE);

            // 1. Scan for active Lark Threat above-head labels
            Map<PlayerEntity, Integer> larkThreats = LARK_MANAGER.getLarkThreats(mc);
            if (!larkThreats.isEmpty()) {
                float scale = Math.max(0.5f, Math.min(8.0f, HUD_SETTINGS.larkLabelScale));
                float height = HUD_SETTINGS.labelHeightOffset;
                float ds = 0.025f * scale;
                net.minecraft.client.render.VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();

                for (Map.Entry<PlayerEntity, Integer> entry : larkThreats.entrySet()) {
                    PlayerEntity player = entry.getKey();
                    int lvl = entry.getValue();
                    boolean pulse = (now / 500) % 2 == 0;
                    String text = (pulse ? "§c" : "§4") + LARK_MANAGER.buildHeadLabel(lvl);
                    int col = pulse ? 0xFF3333 : 0xFF7777;
                    int width = tr.getWidth(text);

                    matrices.push();
                    matrices.translate(player.getX() - camera.getCameraPos().x,
                            player.getY() + player.getHeight() + height - camera.getCameraPos().y,
                            player.getZ() - camera.getCameraPos().z);
                    matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                    matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                    matrices.scale(-ds, -ds, ds);

                    tr.draw(Text.literal(text),
                            -width / 2f,
                            0f,
                            col | 0xFF000000,
                            false,
                            matrices.peek().getPositionMatrix(),
                            imm,
                            TextRenderer.TextLayerType.SEE_THROUGH,
                            0x20000000,
                            15728880);
                    matrices.pop();
                }
                imm.draw();
            }

            // 2. Render Frozen Player above-head tags
            FROZEN_TRACKER.renderHeadLabels(matrices, camera, mc);

            // 3. Render custom enchant head labels (Love Birds stun, etc.)
            CustomHeadLabelManager.renderHeadLabels(matrices, camera, mc);
            RunicObstructionManager.renderHeadLabels(matrices, camera, mc);

            // 4. Render custom glow outlines for affected players
            if (!CustomGlowManager.activeGlowIds.isEmpty()) {
                for (net.minecraft.entity.player.PlayerEntity p : mc.world.getPlayers()) {
                    if (!CustomGlowManager.activeGlowIds.contains(p.getId())) continue;
                    if (p == mc.player) continue;

                    // Pulse between two pinks to create a heartbeat glow effect
                    boolean pulse = (System.currentTimeMillis() / 400) % 2 == 0;
                    String glowText = pulse ? "\u00A7d\u2764" : "\u00A7c\u2764";
                    int glowCol = pulse ? 0xFFE84A6A : 0xFFFF69B4;
                    int width = tr.getWidth(glowText);
                    float scale = Math.max(0.5f, Math.min(4.0f, HUD_SETTINGS.labelScale));
                    float ds = 0.025f * scale;
                    float ht = HUD_SETTINGS.labelHeightOffset + 1.2f;

                    net.minecraft.client.render.VertexConsumerProvider.Immediate gImm =
                            mc.getBufferBuilders().getEntityVertexConsumers();

                    matrices.push();
                    matrices.translate(
                            p.getX() - camera.getCameraPos().x,
                            p.getY() + p.getHeight() + ht - camera.getCameraPos().y,
                            p.getZ() - camera.getCameraPos().z);
                    matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
                    matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
                    matrices.scale(-ds, -ds, ds);
                    tr.draw(Text.literal(glowText), -width / 2f, 0f,
                            glowCol | 0xFF000000, false,
                            matrices.peek().getPositionMatrix(), gImm,
                            TextRenderer.TextLayerType.SEE_THROUGH,
                            0x20000000, 15728880);
                    matrices.pop();
                    gImm.draw();
                }
            }
        });
    }

    private static ItemStack resolveHoveredItem(MinecraftClient mc) {
        if (mc == null || mc.player == null) return null;

        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> hs) {
            ItemStack cursor = mc.player.currentScreenHandler.getCursorStack();
            if (!cursor.isEmpty()) return cursor;

            net.minecraft.screen.slot.Slot focused = ((HandledScreenAccessor) hs).getFocusedSlot();
            if (focused != null && focused.hasStack()) return focused.getStack();

            return null; // screen open but nothing hovered — don't fall back to held item
        }

        ItemStack held = mc.player.getMainHandStack();
        if (!held.isEmpty()) return held;
        ItemStack offHand = mc.player.getOffHandStack();
        if (!offHand.isEmpty()) return offHand;
        return null;
    }

    /** Queues a signature preset's lines for sending. Used by /sign [name]. */
    private static int sendSignature(String presetName) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        int lines = SignatureManager.send(presetName);
        if (lines == 0) {
            mc.player.sendMessage(Text.literal(
                    "§c✦ §fNo signature preset named §f" + presetName
                            + " §7— see §f/sign list"), false);
            return 0;
        }
        return 1;
    }

    /** Builds a gradient string, prints it and copies it to the clipboard. */
    private static int emitGradient(String text, java.util.List<Integer> stops, String label) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;

        boolean bold = text.startsWith("!");           // "!TEXT" → bold every char
        String body  = bold ? text.substring(1) : text;
        // fit() instead of apply(): keeps the result usable as a chat command
        // (Minecraft kicks on anything over 256 characters)
        String out   = org.cheetahv2.antigravity.client.util.GradientUtil.fit(body, stops, bold,
                org.cheetahv2.antigravity.client.util.GradientUtil.MAX_COMMAND_LENGTH - 8);

        mc.keyboard.setClipboard(out);
        mc.player.sendMessage(Text.literal("§d✦ §fGradient §8(" + label + ", " + out.length()
                + " chars)§f — copied to clipboard:"), false);
        mc.player.sendMessage(Text.literal("§7" + out), false);
        return 1;
    }

    /** Prints Mood Swings HUD diagnostics to chat. Used by /cgcmood [status]. */
    private static int cgcMoodStatus() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        mc.player.sendMessage(Text.literal("§d✦ §fMood Swings status:"), false);
        for (String line : MOOD_HUD.getStatusLines(mc)) {
            mc.player.sendMessage(Text.literal("  " + line), false);
        }
        return 1;
    }

    /** Prints the Bid War watch list to chat. Used by /bidwatch [list]. */
    private static int bidwatchList() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return 0;
        var watched = UTILITY_MODULES.BID_WAR.getWatched();
        if (watched.isEmpty()) {
            mc.player.sendMessage(Text.literal(
                    "§7[CGC] No bid war items watched. Add with §f/bidwatch add <name>"), false);
        } else {
            mc.player.sendMessage(Text.literal("§6[CGC] §fWatched bid war items:"), false);
            for (String w : watched) {
                mc.player.sendMessage(Text.literal("  §e⚑ §f" + w), false);
            }
        }
        return 1;
    }

    private void handleChatMessage(String plain) {
        // Never parse our OWN chat output. Client-side sendMessage re-enters
        // this event, so without this a mod message that happens to match one
        // of our detectors recurses until the client stack-overflows.
        if (org.cheetahv2.antigravity.client.util.ModChat.isSending()) return;
        String stripped = plain.replaceAll("\u00A7[0-9a-fk-orA-FK-OR]", "").trim();
        String lo = stripped.toLowerCase();

        // Pass to ability cooldowns
        ABILITY_COOLDOWN.onChat(stripped);

        // Pass to frozen player tracker
        FROZEN_TRACKER.onChat(stripped);

        // Pass to custom enchant system (Shuffle Deck, Squirting Flower, Love Birds, etc.)
        CustomCooldownSystem.onChat(stripped);

        // Pass to event schedule manager (DestroyTheCore spawn detection)
        EVENT_SCHEDULE.onChat(stripped);

        // NOTE: Mood Swings is deliberately NOT parsed here — the server only
        // announces moods in the ACTION BAR (see the GAME event handler).

        // Pass to Bid War alerts
        UTILITY_MODULES.BID_WAR.onChat(stripped);

        // Let the command queue back off if the server says we're too fast
        org.cheetahv2.antigravity.client.util.ClientCommandQueue.onServerMessage(stripped);

        // ── Combat tag expiry ─────────────────────────────────────────────
        // Auto-send /fix after the fight ends (no notification shown).
        if (lo.contains("combat tag has expired")) {
            pendingFixCommand = true;
        }

        // ── Kill detection ────────────────────────────────────────────────
        // Server typically sends "You killed <player>" or "<player> was killed by <you>"
        String localName = MinecraftClient.getInstance() != null &&
                MinecraftClient.getInstance().player != null
                ? MinecraftClient.getInstance().player.getName().getString().toLowerCase()
                : "";
        if (!localName.isEmpty()) {
            boolean youKilledSomeone = lo.startsWith("you killed ") ||
                    (lo.contains("killed") && lo.contains("you") && lo.contains("killed by you")) ||
                    (lo.contains("✗") && lo.contains(localName) && lo.contains("kill")) ||
                    (lo.contains("was killed by " + localName));
            if (youKilledSomeone) {
                pendingKillFix = true;
            }
        }

        // ── Jack of Hearts — start 1:30 cooldown HUD tile on use ─────────
        // Detects server confirmation messages when the card is played.
        // The AutoJackOfHeartsModule also calls triggerJackOfHearts() when
        // it auto-uses the card, so manual use is covered here too.
        if ((lo.contains("jack") && lo.contains("heart"))
                && (lo.contains("used") || lo.contains("activated") || lo.contains("played"))) {
            ABILITY_COOLDOWN.triggerJackOfHearts();
        }

        // Lark status transitions parser
        if (lo.contains("lark") && lo.contains("activated") && lo.contains("50% more")) {
            LARK_MANAGER.onChatBuff();
        } else if (lo.contains("lark") && lo.contains("50% less")) {
            LARK_MANAGER.onChatDebuff();
        } else if (lo.contains("lark") && lo.contains("cooldown")) {
            LARK_MANAGER.onChatCooldown();
        }
    }

    private PlayerEntity raycastPlayer(MinecraftClient mc, double distance) {
        Entity cameraEntity = mc.getCameraEntity();
        if (cameraEntity == null || mc.world == null) return null;

        Vec3d eyePos = cameraEntity.getCameraPosVec(1.0F);
        Vec3d lookVec = cameraEntity.getRotationVec(1.0F);
        Vec3d endPos = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);
        Box box = cameraEntity.getBoundingBox().expand(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance).expand(1.0D, 1.0D, 1.0D);

        EntityHitResult hit = ProjectileUtil.raycast(cameraEntity, eyePos, endPos, box,
                entity -> entity instanceof PlayerEntity && entity != mc.player && !entity.isSpectator() && entity.canHit(),
                distance * distance);

        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            return (PlayerEntity) hit.getEntity();
        }
        return null;
    }

    public static String colorize(String s) {
        if (s == null) return "";
        return s.replace("&0", "§0").replace("&1", "§1").replace("&2", "§2").replace("&3", "§3")
                .replace("&4", "§4").replace("&5", "§5").replace("&6", "§6").replace("&7", "§7")
                .replace("&8", "§8").replace("&9", "§9").replace("&a", "§a").replace("&b", "§b")
                .replace("&c", "§c").replace("&d", "§d").replace("&e", "§e").replace("&f", "§f")
                .replace("&k", "§k").replace("&l", "§l").replace("&m", "§m").replace("&n", "§n")
                .replace("&o", "§o").replace("&r", "§r");
    }

    // ── Crate Puller NBT Helpers ────────────────────────────────────────

    /**
     * Extracts the player name who pulled this item from a crate.
     *
     * Reads {@code PublicBukkitValues.scrptr:dupe_uuid} whose value is formatted as
     * {@code "playerName-unixTimestampSec:itemUUID"}.  Also checks several other
     * common puller-key names in case the server changes format.
     *
     * @return the player name string, or {@code null} if not found.
     */
    public static String extractCratePuller(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        var customData = stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return null;

        net.minecraft.nbt.NbtCompound tag = customData.copyNbt();

        net.minecraft.nbt.NbtCompound pbv =
                tag.getCompound("PublicBukkitValues").orElse(null);

        if (pbv == null) return null;

        String raw = pbv.getString("scrptr:dupe_uuid").orElse(null);

        if (raw == null || raw.isEmpty()) {
            return null;
        }

        // Remove anything after :
        int colon = raw.indexOf(':');
        String firstPart = colon == -1 ? raw : raw.substring(0, colon);

        // Return everything before the first dash
        int dash = firstPart.indexOf('-');

        if (dash > 0) {
            return firstPart.substring(0, dash);
        }

        return firstPart;
    }

    /**
     * Returns the Unix epoch-second timestamp embedded in {@code scrptr:dupe_uuid},
     * or {@code 0} if it cannot be parsed.
     */
    public static long extractPullTimestamp(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        net.minecraft.component.type.NbtComponent customData =
                stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if (customData == null) return 0;
        net.minecraft.nbt.NbtCompound tag = customData.copyNbt();
        net.minecraft.nbt.NbtCompound pbv = tag.getCompound("PublicBukkitValues").orElse(null);
        if (pbv == null) return 0;

        String raw = pbv.getString("scrptr:dupe_uuid").orElse(null);
        if (raw == null || raw.isEmpty()) return 0;
        int colonIdx = raw.indexOf(':');
        if (colonIdx <= 0) return 0;
        String playerPart = raw.substring(0, colonIdx);
        int lastDash = playerPart.lastIndexOf('-');
        if (lastDash < 0) return 0;
        String possibleTs = playerPart.substring(lastDash + 1);
        try {
            long ts = Long.parseLong(possibleTs);
            return (possibleTs.length() >= 8) ? ts : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Formats an epoch-second timestamp as {@code "MMM d, yyyy"} in the system locale. */
    public static String formatPullDate(long epochSeconds) {
        java.time.LocalDate date = java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate();
        return date.getMonth().getDisplayName(
                java.time.format.TextStyle.SHORT, java.util.Locale.US)
                + " " + date.getDayOfMonth() + ", " + date.getYear();
    }
}