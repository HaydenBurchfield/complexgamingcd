package org.cheetahv2.antigravity.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * ClientCommandQueue — paces batches of chat commands so the server neither
 * rate-limits nor spam-kicks you.
 *
 * Protections:
 *   • fixed spacing between commands, plus small random jitter so the timing
 *     isn't perfectly robotic
 *   • an EXTRA pause every few commands (long lore edits are the worst case:
 *     a dozen /sll in a row is exactly what spam filters look for)
 *   • automatic back-off when the server complains about speed — the queue
 *     pauses, widens its spacing, and resumes instead of ploughing on
 *   • hard 256-char guard, since oversized packets disconnect the client
 */
public final class ClientCommandQueue {

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static long nextAt = 0L;
    private static int  delayMs = 500;
    private static int  sentSinceBreath = 0;
    private static int  total = 0;

    /** Commands sent before taking a longer breather. */
    private static final int BURST_SIZE   = 4;
    private static final int BREATHER_MS  = 1200;

    private ClientCommandQueue() {}

    public static void submit(List<String> commands, int spacingMs) {
        QUEUE.clear();
        QUEUE.addAll(commands);
        delayMs = Math.max(250, spacingMs);   // never faster than 4/sec
        sentSinceBreath = 0;
        total = commands.size();
        nextAt = 0L;
    }

    public static int pending() { return QUEUE.size(); }

    public static void clear() {
        QUEUE.clear();
        notifyPlayer("§7✦ Queued commands cancelled.");
    }

    /**
     * Feed server chat here — if it looks like a rate-limit/spam warning the
     * queue backs off rather than getting you kicked.
     */
    public static void onServerMessage(String stripped) {
        if (QUEUE.isEmpty() || stripped == null) return;
        // Ignore our OWN output: printing a message re-enters the chat event,
        // and our warning text used to match the detector below -> infinite
        // recursion -> StackOverflow crash.
        if (ModChat.isSending()) return;

        String lo = stripped.toLowerCase();
        boolean rateLimited =
                lo.contains("slow down") || lo.contains("too quickly") || lo.contains("too fast")
                || lo.contains("please wait") || lo.contains("wait before")
                || lo.contains("spamming") || lo.contains("stop spamming")
                || lo.contains("command cooldown");
        if (!rateLimited) return;

        delayMs = Math.min(3000, (int) (delayMs * 1.8));
        nextAt = System.currentTimeMillis() + 3000;
        sentSinceBreath = 0;
        // Wording deliberately avoids the trigger phrases above.
        notifyPlayer("§e✦ Throttling: pausing 3s, now " + delayMs
                + "ms per command (" + QUEUE.size() + " left).");
    }

    /** Call once per client tick. */
    public static void tick(MinecraftClient mc) {
        if (QUEUE.isEmpty() || mc == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now < nextAt) return;

        String cmd = QUEUE.poll();
        if (cmd != null && !cmd.isBlank()) {
            String out = cmd.startsWith("/") ? cmd.substring(1) : cmd;
            // The server kicks the client for command packets over 256 chars,
            // so drop it with a warning instead of disconnecting.
            if (out.length() > GradientUtil.MAX_COMMAND_LENGTH) {
                notifyPlayer("§c✦ Skipped a command: " + out.length() + " chars (limit "
                        + GradientUtil.MAX_COMMAND_LENGTH + "). Shorten the line or"
                        + " use a coarser gradient.");
            } else {
                mc.player.networkHandler.sendChatCommand(out);
                sentSinceBreath++;
            }
        }

        // Jittered spacing, with a longer pause after each burst
        int jitter = (int) (Math.random() * 120) - 40;
        int wait = delayMs + jitter;
        if (sentSinceBreath >= BURST_SIZE) {
            sentSinceBreath = 0;
            wait += BREATHER_MS;
        }
        nextAt = now + Math.max(200, wait);

        if (QUEUE.isEmpty() && total > 1) {
            notifyPlayer("§a✦ Finished sending " + total + " commands.");
            total = 0;
        }
    }

    private static void notifyPlayer(String msg) {
        ModChat.send(msg); // re-entrancy safe
    }
}
