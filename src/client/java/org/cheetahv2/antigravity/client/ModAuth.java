package org.cheetahv2.antigravity.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * ModAuth
 *
 * Checks with your Cloudflare Worker whether the current player is allowed
 * to use this mod.  The check runs async on init so it never blocks the game.
 *
 * HOW TO CONFIGURE:
 *   1. Set WORKER_URL to your Worker's URL  (e.g. https://cgc-auth.yourname.workers.dev)
 *   2. Set AUTH_SECRET to the exact same value as SECRET_TOKEN in your Worker's
 *      environment variables.  This is the only thing that needs to stay secret.
 *
 * WHAT HAPPENS ON FAILURE:
 *   - The mod silently disables all of its HUD overlays and automation modules.
 *   - No error message is shown to the player.
 *   - The mod still loads so it doesn't produce suspicious crash reports.
 *
 * If the network request fails entirely (no internet, Worker down, timeout),
 * the mod defaults to ALLOWED so legitimate users are never locked out by a
 * temporary outage.  You can flip this to false if you'd rather be strict.
 */
public class ModAuth {

    // ── Configuration ────────────────────────────────────────────────────────
    /** Your Cloudflare Worker URL — no trailing slash. */
    private static final String WORKER_URL =
            "https://patient-pine-1a33.haydenburchfield41.workers.dev";

    /**
     * Shared secret.  Must match SECRET_TOKEN in the Worker's environment variables.
     * Keep this out of public repos. It's the only thing protecting the endpoint.
     */
    private static final String AUTH_SECRET  = "ewahbsjnOJfhiou233a@*)u214jowajelkaeawpoeawo";

    /** How long to wait for the Worker before giving up and allowing access. */
    private static final Duration TIMEOUT    = Duration.ofSeconds(5);

    /**
     * What to do when the network request fails entirely.
     * true  = allow (friendly to users, weaker security)
     * false = deny  (stricter, but locks out users if Worker is down)
     */
    private static final boolean ALLOW_ON_NETWORK_ERROR = true;
    // ─────────────────────────────────────────────────────────────────────────

    // ── State ─────────────────────────────────────────────────────────────────
    private static volatile boolean authorized = false;
    private static volatile boolean checkDone  = false;

    /**
     * Starts the async auth check.  Call this early in onInitializeClient().
     * Safe to call multiple times — subsequent calls are no-ops after the first.
     */
    public static void beginCheck() {
        if (checkDone) return;

        CompletableFuture.runAsync(() -> {
            try {
                Session session = MinecraftClient.getInstance().getSession();
                String  name    = session.getUsername();
                String  uuid    = session.getUuidOrNull() != null
                        ? session.getUuidOrNull().toString()
                        : "";

                String url = WORKER_URL
                        + "?t=" + AUTH_SECRET
                        + "&u=" + uuid
                        + "&n=" + name;

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .build();

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> resp = client.send(req,
                        HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
                    authorized = body.has("allowed") && body.get("allowed").getAsBoolean();
                } else {
                    // Non-200 status = explicitly denied
                    authorized = false;
                }

            } catch (Exception e) {
                // Network error, timeout, JSON parse failure — use fallback
                authorized = ALLOW_ON_NETWORK_ERROR;
            } finally {
                checkDone = true;
            }
        });
    }

    /**
     * Returns true if the player has been confirmed as authorized, OR if the
     * check hasn't completed yet (grace period to avoid blocking on slow connections).
     *
     * Call this wherever you want to gate behaviour:
     *   if (!ModAuth.isAllowed()) return;
     */
    public static boolean isAllowed() {
        // If check hasn't finished yet, allow temporarily (prevents flicker on slow start)
        if (!checkDone) return true;
        return authorized;
    }

    /**
     * Returns true only once the check has finished AND authorization is confirmed.
     * Useful if you want to wait until the result is definitive before enabling features.
     */
    public static boolean isConfirmedAllowed() {
        return checkDone && authorized;
    }

    /** True once the network request has completed (pass or fail). */
    public static boolean isCheckDone() {
        return checkDone;
    }
}