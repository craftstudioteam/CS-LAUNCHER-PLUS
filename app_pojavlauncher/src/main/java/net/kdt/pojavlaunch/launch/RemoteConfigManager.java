package net.kdt.pojavlaunch.launch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * RemoteConfigManager — GitHub Remote Config for CS Launcher Plus.
 *
 * Fetches and caches remote/config.json straight from the launcher repo via
 * GitHub Raw:
 *   https://raw.githubusercontent.com/PAPA20000/CSL/main/remote/config.json
 * (a jsDelivr mirror of the same file is kept as a resilience fallback).
 *
 * Guarantees:
 *  - network strictly off the UI thread (executor), never on the launch path
 *  - parsing is fully tolerant: a missing/malformed section falls back to
 *    defaults (loadingVideo disabled) and can never break startup
 *  - last known good JSON is persisted in its own SharedPreferences file
 *    ("csl_remote_config") so the settings save flow can never wipe it
 *  - zero shipped assets: the config (and any media it points at) lives
 *    entirely on the remote — APK size is unaffected
 */
public final class RemoteConfigManager {

    private static final String TAG = "RemoteConfig";
    /** Primary: GitHub Raw serves the freshest content (CDN cache ~minutes). */
    public static final String CONFIG_URL =
            "https://raw.githubusercontent.com/PAPA20000/CSL/main/remote/config.json";
    /** Resilience fallback: jsDelivr mirror of the same file. */
    private static final String FALLBACK_CONFIG_URL =
            "https://cdn.jsdelivr.net/gh/PAPA20000/CSL@main/remote/config.json";

    private static final String PREFS = "csl_remote_config";
    private static final String KEY_JSON = "cached_json";
    private static final String KEY_FETCHED_AT = "fetched_at_ms";

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_BYTES = 64 * 1024;

    /** How often the config auto-refreshes while the launcher is alive. */
    public static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;

    private static volatile Config sConfig;
    private static volatile long sFetchedAtMs;
    private static volatile boolean sFetchInFlight;

    private RemoteConfigManager() {}

    // ══════════════════ Public read API (UI-thread safe, instant) ══════════════════

    /** Current config: memory → persisted cache → defaults. Never null. */
    @NonNull
    public static Config get(@NonNull Context ctx) {
        Config c = sConfig;
        if (c != null) return c;
        String json = prefs(ctx).getString(KEY_JSON, null);
        sFetchedAtMs = prefs(ctx).getLong(KEY_FETCHED_AT, 0L);
        c = parse(json);
        sConfig = c;
        return c;
    }

    @Nullable
    public static String getLoadingVideoUrl(@NonNull Context ctx) {
        Config c = get(ctx);
        if (c.loadingVideo == null) return null;
        return c.loadingVideo.enabled ? c.loadingVideo.url : null;
    }

    public static long getFetchedAtMs() {
        return sFetchedAtMs;
    }

    // ══════════════════ Fetch (req 1 + 9) ══════════════════

    /** Callback fired on the MAIN thread after a fresh config was fetched + saved. */
    public interface RefreshListener { void onRefreshed(); }

    /** Kick an async refresh. Safe to call repeatedly; concurrent fetches collapse. */
    public static void refreshAsync(@NonNull final Context ctx) {
        refreshAsync(ctx, null);
    }

    /** Async refresh; {@code listener} fires on the main thread after a successful save. */
    public static void refreshAsync(@NonNull final Context ctx, @Nullable final RefreshListener listener) {
        if (sFetchInFlight) return;
        sFetchInFlight = true;
        final Context app = ctx.getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String json = download(CONFIG_URL);
                if (json == null) json = download(FALLBACK_CONFIG_URL);
                Config parsed = parse(json);
                if (json != null && parsed != null) {
                    sConfig = parsed;
                    sFetchedAtMs = System.currentTimeMillis();
                    prefs(app).edit()
                            .putString(KEY_JSON, json)
                            .putLong(KEY_FETCHED_AT, sFetchedAtMs)
                            .apply();
                    Log.i(TAG, "config refreshed: loadingVideo="
                            + (parsed.loadingVideo != null && parsed.loadingVideo.enabled
                            ? parsed.loadingVideo.url : "disabled"));
                    // Freshness sync: re-fetch the temp-cached video when the
                    // remote file/URL changed (GitHub video replace → next
                    // launcher start picks it up automatically).
                    if (parsed.loadingVideo != null && parsed.loadingVideo.enabled) {
                        LoadingVideoCache.syncAsync(app, parsed.loadingVideo.url);
                    }
                    if (listener != null) Tools.MAIN_HANDLER.post(listener::onRefreshed);
                }
            } catch (Throwable t) {
                Log.w(TAG, "config fetch failed, keeping cache", t);
            } finally {
                sFetchInFlight = false;
            }
        });
    }

    // ══════════════════ Model ══════════════════

    public static final class Config {
        public LoadingVideo loadingVideo;
    }

    public static final class LoadingVideo {
        public boolean enabled;
        public String url;
    }

    // ══════════════════ Internals ══════════════════

    @Nullable
    private static Config parse(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) return new Config();
        try {
            Config c = Tools.GLOBAL_GSON.fromJson(json, Config.class);
            return c != null ? c : new Config();
        } catch (Throwable t) {
            Log.w(TAG, "config parse failed", t);
            return new Config(); // defaults: everything disabled
        }
    }

    @Nullable
    private static String download(@NonNull String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "CS-Launcher-V3");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int total = 0, n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) return null; // config abuse guard
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
