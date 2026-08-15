package org.cheetahv2.antigravity.client.detection;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.cheetahv2.antigravity.client.AntigravityClient;
import org.cheetahv2.antigravity.client.util.ConfigHelper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lark ability tracker.
 *
 * YOUR OWN state machine (triggered by equipping a Lark weapon in main hand):
 *
 *   [equip]
 *      │
 *      ▼
 *   BUFF (+50%)  ──5 s──►  NORMAL  ──5 s──►  COOLDOWN (-50%)  ──30 s──►  IDLE
 *                                                  ▲
 *                                           [equip again]  ← resets the 30 s timer
 *
 * ENEMY tracking state machine (per enemy player):
 *
 *   [enemy equips lark]
 *      │
 *      ├─ if was IDLE/BUFF/NORMAL ──► ENEMY_BUFF  (+50%, RED label)  ──5s──► ENEMY_NORMAL ──5s──► ENEMY_COOLDOWN ──30s──► ENEMY_IDLE
 *      │
 *      └─ if was ENEMY_COOLDOWN   ──► ENEMY_COOLDOWN reset timer (GRAY label, still on cd)
 *
 * PERFORMANCE NOTES (added):
 *   • Lark-level detection now reads the LORE component directly instead of
 *     building a full item tooltip, and results are cached per ItemStack in a
 *     weak identity map. The old per-call tooltip build caused visible frame
 *     drops when several players were holding Lark weapons.
 *   • The threat map used by the above-head label renderer is built ONCE per
 *     tick instead of being rescanned every frame.
 */
public class LarkManager {
    public static final Path FILE = Paths.get("config", "antigravity", "lark.json");

    // =========================================================================
    //  ENEMY LARK STATE TRACKING
    // =========================================================================

    public enum EnemyLarkState { IDLE, BUFF, NORMAL, COOLDOWN }

    public static class EnemyLarkData {
        public EnemyLarkState state = EnemyLarkState.IDLE;
        public long stateStartMs    = 0;
        public int  level           = 0;
        public boolean wasHoldingLark = false;

        // Phase durations — mirror the global config at the time of equip
        public long buffMs;
        public long normalMs;
        public long cooldownMs;

        public EnemyLarkData(long buffMs, long normalMs, long cooldownMs) {
            this.buffMs     = buffMs;
            this.normalMs   = normalMs;
            this.cooldownMs = cooldownMs;
        }

        public long remaining() {
            if (state == EnemyLarkState.IDLE) return 0;
            long elapsed = System.currentTimeMillis() - stateStartMs;
            long total = switch (state) {
                case BUFF     -> buffMs;
                case NORMAL   -> normalMs;
                case COOLDOWN -> cooldownMs;
                default       -> 0;
            };
            return Math.max(0, total - elapsed);
        }

        /**
         * Called every tick when we detect the enemy is holding a Lark weapon.
         * Returns true if the state changed (equip event fired).
         */
        public boolean onEquipDetected(int larkLevel, long buffMs, long normalMs, long cooldownMs) {
            this.level = larkLevel;
            if (!wasHoldingLark) {
                // Rising edge — player just pulled out their lark
                wasHoldingLark = true;
                if (state == EnemyLarkState.COOLDOWN) {
                    // Re-equip during cooldown: reset timer, stay in COOLDOWN (gray label)
                    this.buffMs     = buffMs;
                    this.normalMs   = normalMs;
                    this.cooldownMs = cooldownMs;
                    stateStartMs    = System.currentTimeMillis();
                } else {
                    // Fresh equip: start BUFF (red label, dangerous)
                    this.buffMs     = buffMs;
                    this.normalMs   = normalMs;
                    this.cooldownMs = cooldownMs;
                    state        = EnemyLarkState.BUFF;
                    stateStartMs = System.currentTimeMillis();
                }
                return true;
            }
            return false;
        }

        public void onUnequip() {
            wasHoldingLark = false;
            // Do NOT reset state — the timer keeps running even if they swap weapons
        }

        /** Advance phase transitions each tick. */
        public void tick() {
            if (remaining() == 0) {
                switch (state) {
                    case BUFF     -> { state = EnemyLarkState.NORMAL;   stateStartMs = System.currentTimeMillis(); }
                    case NORMAL   -> { state = EnemyLarkState.COOLDOWN; stateStartMs = System.currentTimeMillis(); }
                    case COOLDOWN -> state = EnemyLarkState.IDLE;
                    default       -> {}
                }
            }
        }

        /** True if this enemy is actively buffed (+50%) — show RED label. */
        public boolean isBuffed() { return state == EnemyLarkState.BUFF; }

        /** True if this enemy is on cooldown (-50%) — show GRAY label. */
        public boolean isOnCooldown() { return state == EnemyLarkState.COOLDOWN; }

        /** True if label should be shown at all (any non-idle state). */
        public boolean shouldShowLabel() { return state != EnemyLarkState.IDLE; }
    }

    // Maps playerName -> their lark tracking data
    private final Map<String, EnemyLarkData> enemyData = new ConcurrentHashMap<>();

    // =========================================================================
    //  YOUR OWN STATE
    // =========================================================================

    // ---------- config (persisted) ----------
    public boolean alertEnabled = true;
    public long buffMs     = 5_000;
    public long normalMs   = 5_000;
    public long cooldownMs = 30_000;
    public String headLabel = "⚔ LARK {roman}";

    // ---------- runtime state ----------
    public enum State { IDLE, BUFF, NORMAL, COOLDOWN }
    public State state = State.IDLE;
    public long stateStartMs = 0;

    /** Tracks the previous main-hand item so we can detect the moment the player equips a Lark weapon. */
    private boolean wasHoldingLark = false;

    // =========================================================================
    //  PERSISTENCE
    // =========================================================================

    public static class SaveData {
        public long buffMs, normalMs, cooldownMs;
        public String headLabel;
        public boolean alertEnabled;
    }

    public void save() {
        SaveData d = new SaveData();
        d.buffMs       = buffMs;
        d.normalMs     = normalMs;
        d.cooldownMs   = cooldownMs;
        d.headLabel    = headLabel;
        d.alertEnabled = alertEnabled;
        ConfigHelper.save(FILE, d);
    }

    public void load() {
        SaveData d = ConfigHelper.load(FILE, SaveData.class);
        if (d != null) {
            if (d.buffMs     > 0) buffMs     = d.buffMs;
            if (d.normalMs   > 0) normalMs   = d.normalMs;
            if (d.cooldownMs > 0) cooldownMs = d.cooldownMs;
            if (d.headLabel  != null) headLabel = d.headLabel;
            alertEnabled = d.alertEnabled;
        }
    }

    // =========================================================================
    //  CHAT-TRIGGER SHIMS (called by AntigravityClient)
    // =========================================================================

    /** Manually force the BUFF phase — used by chat-based trigger in AntigravityClient. */
    public void onChatBuff() {
        state        = State.BUFF;
        stateStartMs = System.currentTimeMillis();
    }

    /** Manually force the NORMAL phase (neutral window between buff and cooldown). */
    public void onChatDebuff() {
        state        = State.NORMAL;
        stateStartMs = System.currentTimeMillis();
    }

    /** Manually force the COOLDOWN phase. */
    public void onChatCooldown() {
        state        = State.COOLDOWN;
        stateStartMs = System.currentTimeMillis();
    }

    // =========================================================================
    //  OWN STATE HELPERS
    // =========================================================================

    /** Called when the local player pulls out a Lark weapon. */
    private void onWeaponEquipped() {
        if (state == State.COOLDOWN) {
            // Re-equip during cooldown: just reset the timer, stay in COOLDOWN
            stateStartMs = System.currentTimeMillis();
            // ── MIS-TIMED LARK ──────────────────────────────────────────────
            // Player equipped Lark while the cooldown was still active.
            // Flash them with a blue glow and show a blue warning notification.
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                CustomGlowManager.registerGlow(mc.player.getName().getString(), 3000L, 0x3399FF);
                AntigravityClient.NOTIF_MANAGER.push(
                        "§b⚠ Mis-timed Lark! (still on CD)", 0xFF3399FF, 3000);
            }
        } else {
            // Fresh equip: start BUFF phase
            state        = State.BUFF;
            stateStartMs = System.currentTimeMillis();
        }
    }

    public long remaining() {
        if (state == State.IDLE) return 0;
        long elapsed = System.currentTimeMillis() - stateStartMs;
        long total = switch (state) {
            case BUFF     -> buffMs;
            case NORMAL   -> normalMs;
            case COOLDOWN -> cooldownMs;
            default       -> 0;
        };
        return Math.max(0, total - elapsed);
    }

    /** 0 → just started, 1 → phase complete */
    public float progress() {
        if (state == State.IDLE) return 1f;
        long elapsed = System.currentTimeMillis() - stateStartMs;
        long total = switch (state) {
            case BUFF     -> buffMs;
            case NORMAL   -> normalMs;
            case COOLDOWN -> cooldownMs;
            default       -> 1;
        };
        return Math.min(1f, (float) elapsed / Math.max(1, total));
    }

    // =========================================================================
    //  TICK — own state + all enemies
    // =========================================================================

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null) return;

        // --- own equip detection ---
        boolean holdingLark = isLarkWeapon(mc.player.getMainHandStack());
        if (holdingLark && !wasHoldingLark) {
            onWeaponEquipped();
        }
        wasHoldingLark = holdingLark;

        // --- own phase transitions ---
        if (remaining() == 0) {
            switch (state) {
                case BUFF     -> { state = State.NORMAL;   stateStartMs = System.currentTimeMillis(); }
                case NORMAL   -> { state = State.COOLDOWN; stateStartMs = System.currentTimeMillis(); }
                case COOLDOWN -> state = State.IDLE;
                default       -> {}
            }
        }

        // --- enemy lark tracking ---
        cachedThreats.clear();
        if (mc.world == null) return;

        Set<String> seenThisTick = new HashSet<>();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;

            String name = p.getName().getString();
            seenThisTick.add(name);

            int larkLevel = getLarkLevel(p.getMainHandStack());
            boolean holdingLarkNow = larkLevel > 0;

            if (holdingLarkNow) cachedThreats.put(p, larkLevel);

            EnemyLarkData data = enemyData.computeIfAbsent(
                    name, k -> new EnemyLarkData(buffMs, normalMs, cooldownMs));

            if (holdingLarkNow) {
                boolean justEquipped = data.onEquipDetected(larkLevel, buffMs, normalMs, cooldownMs);
                if (justEquipped && alertEnabled) {
                    if (data.isBuffed()) {
                        AntigravityClient.NOTIF_MANAGER.push(
                                "§c⚔ " + name + " pulled out Lark " + toRoman(larkLevel) + " §f[§c+50%§f]",
                                0xFFE84A6A);
                    } else {
                        // was on cooldown, re-equipped — still dangerous but timer reset
                        AntigravityClient.NOTIF_MANAGER.push(
                                "§7⚔ " + name + " re-equipped Lark (still on CD)",
                                0xFF888888);
                    }
                }
            } else {
                if (data.wasHoldingLark) {
                    data.onUnequip();
                }
            }

            data.tick();

            // Register red glow while enemy is actively buffed (+50%) — refreshed each tick
            if (data.isBuffed()) {
                CustomGlowManager.registerGlow(name, 600, 0xFF2020);
            }
        }

        // Clean up players who left the world
        enemyData.keySet().removeIf(name -> !seenThisTick.contains(name));
    }

    // =========================================================================
    //  ITEM INSPECTION  (cached — see class javadoc performance notes)
    // =========================================================================

    /**
     * Per-ItemStack lark-level cache. ItemStack does not override equals/hashCode,
     * so WeakHashMap keys behave identity-based: a remote player's equipment
     * stack stays the same object until the server replaces it, and stale
     * entries are garbage-collected automatically.
     */
    private static final Map<ItemStack, Integer> LEVEL_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static int getLarkLevel(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        Integer cached = LEVEL_CACHE.get(stack);
        if (cached != null) return cached;
        int lvl = computeLarkLevel(stack);
        LEVEL_CACHE.put(stack, lvl);
        return lvl;
    }

    /** Reads the lore component directly instead of building a full tooltip. */
    private static int computeLarkLevel(ItemStack stack) {
        try {
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                for (Text line : lore.lines()) {
                    StringBuilder sb = new StringBuilder(line.getString());
                    for (Text sib : line.getSiblings()) sb.append(sib.getString());
                    int lvl = larkLevelFromLine(sb.toString());
                    if (lvl > 0) return lvl;
                }
            }
            // Fallback: display name (in case the enchant line lives in the name)
            return larkLevelFromLine(stack.getName().getString());
        } catch (Exception ignored) {}
        return 0;
    }

    private static int larkLevelFromLine(String raw) {
        String plain = raw.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        if (!plain.contains("◆")) return 0; // enchant lines are marked with a diamond
        String lo = plain.toLowerCase();
        int idx = lo.indexOf("lark");
        if (idx < 0) return 0;
        String after = plain.substring(idx + 4).trim();
        if (after.startsWith("V"))   return 5;
        if (after.startsWith("IV"))  return 4;
        if (after.startsWith("III")) return 3;
        if (after.startsWith("II"))  return 2;
        if (after.startsWith("I"))   return 1;
        return 1;
    }

    public static boolean isLarkWeapon(ItemStack stack) { return getLarkLevel(stack) > 0; }

    /**
     * Threat map rebuilt ONCE per tick in tick() — the render callback used to
     * rescan every player's held item every frame. Renderers read this snapshot.
     */
    private final Map<PlayerEntity, Integer> cachedThreats = new LinkedHashMap<>();

    public Map<PlayerEntity, Integer> getLarkThreats(MinecraftClient mc) {
        if (!alertEnabled) return Collections.emptyMap();
        return cachedThreats;
    }

    public String buildHeadLabel(int lvl) {
        String[] ROMAN = {"", "I", "II", "III", "IV", "V"};
        String r = (lvl >= 1 && lvl <= 5) ? ROMAN[lvl] : String.valueOf(lvl);
        return AntigravityClient.colorize(headLabel)
                .replace("{roman}", r)
                .replace("{level}", String.valueOf(lvl));
    }

    // =========================================================================
    //  ABOVE-HEAD LABEL RENDERING FOR ENEMIES
    // =========================================================================

    /**
     * Renders Lark state labels above enemy players' heads.
     * Called from WorldRenderEvents.AFTER_ENTITIES.
     *
     * RED   = BUFF  (+50%, dangerous — they just equipped it)
     * GRAY  = COOLDOWN (they re-equipped on cd or the buff has expired, -50%)
     * No label shown during NORMAL or IDLE.
     */
    public void renderEnemyHeadLabels(
            net.minecraft.client.util.math.MatrixStack matrices,
            net.minecraft.client.render.Camera camera,
            MinecraftClient mc) {

        if (mc == null || mc.world == null || enemyData.isEmpty()) return;
        if (matrices == null || camera == null) return;

        TextRenderer tr = mc.textRenderer;

        float scale  = Math.max(0.5f, Math.min(8.0f, AntigravityClient.HUD_SETTINGS.larkLabelScale));
        float ds     = 0.025f * scale;
        // Render slightly above the CustomHeadLabelManager layer so they don't overlap
        float height = AntigravityClient.HUD_SETTINGS.labelHeightOffset + 1.4f;

        net.minecraft.client.render.VertexConsumerProvider.Immediate imm =
                mc.getBufferBuilders().getEntityVertexConsumers();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;

            String name = p.getName().getString();
            EnemyLarkData data = enemyData.get(name);
            if (data == null || !data.shouldShowLabel()) continue;

            // Only show labels for BUFF (red) and COOLDOWN (gray)
            // NORMAL phase intentionally has no label — it's a brief neutral window
            if (data.state == EnemyLarkState.NORMAL) continue;

            // Build label text and color
            String labelText;
            int labelColor;
            long rem = data.remaining();
            String timeStr = String.format("%.1fs", rem / 1000.0);
            String roman   = toRoman(data.level);

            if (data.isBuffed()) {
                // RED — they are at +50%, dangerous
                labelText  = "§c⚔ LARK " + roman + " +50% §f[" + timeStr + "]";
                labelColor = 0xFFE84A6A; // red
            } else {
                // GRAY — they are on cooldown, -50%
                labelText  = "§7⚔ LARK " + roman + " -50% §f[" + timeStr + "]";
                labelColor = 0xFF888888; // gray
            }

            // Pulse alpha for the last 500ms of each phase
            float alpha = (rem < 500L) ? (rem / 500f) : 1f;
            int alphaByte = (int)(alpha * 255);
            int col = (alphaByte << 24) | (labelColor & 0x00FFFFFF);

            int width = tr.getWidth(labelText);

            matrices.push();
            matrices.translate(
                    p.getX() - camera.getCameraPos().x,
                    p.getY() + p.getHeight() + height - camera.getCameraPos().y,
                    p.getZ() - camera.getCameraPos().z);
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
            matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
            matrices.scale(-ds, -ds, ds);

            tr.draw(
                    Text.literal(labelText),
                    -width / 2f,
                    0f,
                    col,
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

    // =========================================================================
    //  HUD — own state panel
    // =========================================================================

    public void renderHud(DrawContext ctx, TextRenderer tr, MinecraftClient mc, int sw, int sh) {
        int sc = switch (state) {
            case BUFF     -> org.cheetahv2.antigravity.client.gui.Theme.GOOD;   // +50%
            case NORMAL   -> 0xFFDDD0F8;                                        // pale lavender – neutral
            case COOLDOWN -> org.cheetahv2.antigravity.client.gui.Theme.BAD;    // -50%
            default       -> org.cheetahv2.antigravity.client.gui.Theme.ACCENT; // idle — brand violet
        };
        String sl = switch (state) {
            case BUFF     -> "LARK +50%";
            case NORMAL   -> "LARK";
            case COOLDOWN -> "LARK -50%";
            default       -> "LARK";
        };

        int px = (AntigravityClient.HUD_SETTINGS.larkHudX >= 0)
                ? AntigravityClient.HUD_SETTINGS.larkHudX : 10;
        int py = (AntigravityClient.HUD_SETTINGS.larkHudY >= 0)
                ? AntigravityClient.HUD_SETTINGS.larkHudY : (sh - 40);

        float larkScale = Math.max(0.4f, AntigravityClient.HUD_SETTINGS.larkScale);
        var larkMs = ctx.getMatrices();
        larkMs.pushMatrix();
        larkMs.translate((float) px, (float) py);
        larkMs.scale(larkScale, larkScale);

        // Draw relative to (0, 0) after translate
        int pw = 120, ph = 30;

        // Liquid-glass panel — Antigravity theme
        ctx.fill(0, 0, pw, ph, org.cheetahv2.antigravity.client.gui.Theme.HUD_BG);
        gborder(ctx, 0, 0, pw, ph, org.cheetahv2.antigravity.client.gui.Theme.BORDER_SOFT);
        ctx.fill(1, 1, pw - 1, 2, 0x15FFFFFF);
        ctx.fill(1, 1, 3, ph - 1, sc);

        ctx.drawTextWithShadow(tr, sl, 7, 5, sc);

        if (state != State.IDLE) {
            long rem = remaining();
            String ts = String.format("%.1fs", rem / 1000.0);
            int tw = tr.getWidth(ts);
            ctx.drawTextWithShadow(tr, ts, pw - tw - 7, 5, 0xFFDDD0F8);
        }

        // Progress bar
        int bx = 6, by = 18, bw = pw - 12, bh = 4;
        ctx.fill(bx, by, bx + bw, by + bh, 0x40000000);

        float prog = progress();
        int filled = (int)(prog * bw);
        if (filled > 0) ctx.fill(bx, by, bx + filled, by + bh, sc);

        if (AntigravityClient.larkHudDragging) {
            gborder(ctx, 0, 0, pw, ph, 0xFFFFD060);
        } else {
            ctx.drawTextWithShadow(tr, "⠿", pw - 8, ph - 8, 0x887860A8);
        }

        larkMs.popMatrix();
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private static String toRoman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    private void gborder(DrawContext c, int x, int y, int w, int h, int col) {
        c.fill(x, y, x + w, y + 1, col);
        c.fill(x, y + h - 1, x + w, y + h, col);
        c.fill(x, y, x + 1, y + h, col);
        c.fill(x + w - 1, y, x + w, y + h, col);
    }
}
