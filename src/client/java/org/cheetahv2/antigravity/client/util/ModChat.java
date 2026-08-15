package org.cheetahv2.antigravity.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * ModChat — sends the mod's own chat messages behind a re-entrancy flag.
 *
 * Client-side sendMessage() re-enters the chat event, so a message the mod
 * prints is parsed again by the mod. If that text happens to match one of our
 * own detectors, it prints another message, which is parsed again... which is
 * exactly how the queue's "slow down" warning stack-overflowed the client.
 *
 * Everything the mod prints goes through here, and chat handlers skip input
 * while {@link #isSending()} is true.
 */
public final class ModChat {

    private static boolean sending = false;

    private ModChat() {}

    /** True while the mod is printing its own message — chat handlers must no-op. */
    public static boolean isSending() { return sending; }

    public static void send(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        if (sending) return;               // never nest
        sending = true;
        try {
            mc.player.sendMessage(Text.literal(msg), false);
        } finally {
            sending = false;
        }
    }

    /** Action-bar variant (also re-entrancy safe). */
    public static void actionBar(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        if (sending) return;
        sending = true;
        try {
            mc.player.sendMessage(Text.literal(msg), true);
        } finally {
            sending = false;
        }
    }
}
