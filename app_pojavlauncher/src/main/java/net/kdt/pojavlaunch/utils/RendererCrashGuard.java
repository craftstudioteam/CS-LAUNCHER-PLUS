package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps MobileGlues from being used again after it has crashed the game natively.
 *
 * A native crash (SIGSEGV inside the GL driver or MobileGlues) kills the whole launcher process, so nothing
 * can be written at that moment. Instead, every game launch first records which renderer it uses. The record
 * is cleared when the JVM returns. If the process died instead, the record is still there on the next launcher
 * start. A session counts as a native crash when HotSpot wrote an hs_err_pid*.log into the game folder after
 * the session began. If that session used MobileGlues, MobileGlues is avoided until the user selects it again
 * by hand.
 */
public final class RendererCrashGuard {
    public static final String MOBILEGLUES = "opengles_mobileglues";

    private static final String TAG = "RendererCrashGuard";
    private static final String PREFS = "renderer_crash_guard";
    private static final String KEY_BLOCKED = "mobileglues_blocked";
    private static final String KEY_PENDING_RENDERER = "pending_renderer";
    private static final String KEY_PENDING_STARTED = "pending_started_ms";
    private static final String KEY_PENDING_DIR = "pending_game_dir";
    // File timestamps can be coarser than System.currentTimeMillis(), so allow a little slack.
    private static final long TIME_SLACK_MS = 2000L;
    private static final String[] FALLBACK_ORDER = {"opengles3_ltw", "opengles3_KW", "opengles2"};

    private RendererCrashGuard() {}

    /** Called right before the JVM starts. */
    public static void onSessionStart(Context ctx, String renderer, File gameDir) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit()
                    .putString(KEY_PENDING_RENDERER, renderer)
                    .putLong(KEY_PENDING_STARTED, System.currentTimeMillis())
                    .putString(KEY_PENDING_DIR, gameDir == null ? null : gameDir.getAbsolutePath())
                    .commit();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not record the game session start", e);
        }
    }

    /** Called when the JVM returned, so the launcher process survived the session. */
    public static void onSessionEnd(Context ctx) {
        resolvePendingSession(ctx);
    }

    /** Called at launcher start. A session still pending here means the previous process died during it. */
    public static void onAppStart(Context ctx) {
        resolvePendingSession(ctx);
    }

    /** True while MobileGlues must not be used because it crashed the game natively before. */
    public static boolean isMobileGluesBlocked(Context ctx) {
        if (ctx == null) return false;
        try {
            return prefs(ctx).getBoolean(KEY_BLOCKED, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Called when the user switches a profile to MobileGlues. From then on the guard no longer applies. */
    public static void onMobileGluesSelectedManually(Context ctx) {
        if (ctx == null) return;
        try {
            prefs(ctx).edit().putBoolean(KEY_BLOCKED, false).commit();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not clear the MobileGlues block", e);
        }
    }

    /** Renderer to use instead of MobileGlues, or null when no other compatible renderer exists. */
    public static String pickFallback(Context ctx) {
        List<String> ids = new ArrayList<>(Tools.getCompatibleRenderers(ctx).rendererIds);
        for (String preferred : FALLBACK_ORDER) {
            if (ids.contains(preferred)) return preferred;
        }
        for (String id : ids) {
            if (!MOBILEGLUES.equals(id)) return id;
        }
        return null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void resolvePendingSession(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences prefs = prefs(ctx);
            String renderer = prefs.getString(KEY_PENDING_RENDERER, null);
            if (renderer == null) return;
            long started = prefs.getLong(KEY_PENDING_STARTED, 0L);
            String gameDir = prefs.getString(KEY_PENDING_DIR, null);
            // Clear the record before looking at the files, so one session can never be counted twice.
            prefs.edit().remove(KEY_PENDING_RENDERER).remove(KEY_PENDING_STARTED).remove(KEY_PENDING_DIR).commit();
            if (MOBILEGLUES.equals(renderer) && hasNativeCrashSince(gameDir, started)) {
                prefs.edit().putBoolean(KEY_BLOCKED, true).commit();
                Log.e(TAG, "MobileGlues session ended in a native crash. MobileGlues stays off until selected manually.");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not evaluate the previous game session", e);
        }
    }

    private static boolean hasNativeCrashSince(String gameDir, long started) {
        if (started <= 0) return false;
        long threshold = started - TIME_SLACK_MS;
        File[] folders = {
                gameDir == null ? null : new File(gameDir),
                Tools.DIR_GAME_NEW == null ? null : new File(Tools.DIR_GAME_NEW)
        };
        for (File folder : folders) {
            if (folder == null || !folder.isDirectory()) continue;
            File[] files = folder.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith("hs_err_pid") && name.endsWith(".log") && file.lastModified() >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }
}
