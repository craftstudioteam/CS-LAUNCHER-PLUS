package net.kdt.pojavlaunch.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.Map;

/**
 * Keeps the handful of settings that <b>both</b> processes can change in sync.
 *
 * <p>The launcher ({@code :launcher}) and the game ({@code :game}) each hold their own in-memory
 * copy of {@code cslauncher_settings}. Whichever one commits last rewrites the entire file from
 * its own stale copy, so a value changed on the other side is silently reverted — that is why the
 * in-game FPS toggle (and friends) came back off after every relaunch, and why the launcher's
 * "Save" button could undo what the player had just changed in game.
 *
 * <p>The keys listed in {@link #SHARED_KEYS} are therefore also stored in
 * {@link CrossProcessState}, which is never cached. Rules:
 * <ul>
 *   <li>write through {@link #putBoolean}/{@link #putInt}/… — the value lands in the normal
 *       preferences (so nothing else in the app has to change) <i>and</i> in the shared file;</li>
 *   <li>call {@link #syncIntoPreferences} whenever a process is about to read or rewrite the
 *       preferences — it pulls whatever the other process wrote back in first.</li>
 * </ul>
 *
 * <p>Defaults are never written here: a key only exists in the shared file once the user has
 * actually chosen a value, so {@code getBoolean(key, default)} still behaves exactly as before
 * for anything the user has not touched.
 */
public final class SharedSettings {

    private static final String TAG = "SharedSettings";

    /** Settings reachable from both the launcher UI and the in-game quick settings. */
    public static final String[] SHARED_KEYS = {
            // diagnostics HUD — the reported "FPS setting keeps resetting"
            "showFpsCounter",
            "showMemoryChip",
            "fpsChipTx", "fpsChipTy",
            "memChipTx", "memChipTy",
            // in-game quick settings
            "enableGyro",
            "gyroInvertX",
            "gyroInvertY",
            "gyroSensitivity",
            "disableGestures",
            "always_grab_mouse",
            "mousespeed",
            "timeLongPressTrigger",
            "resolutionRatio",
            // written by the game process during launch
            "allocation",
    };

    private SharedSettings() {}

    private static boolean isShared(String key) {
        for (String k : SHARED_KEYS) if (k.equals(key)) return true;
        return false;
    }

    // ───────────────────────────── writes ─────────────────────────────

    public static void putBoolean(Context ctx, @NonNull String key, boolean value) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(key, value).apply();
        CrossProcessState.put(ctx, key, value);
    }

    public static void putInt(Context ctx, @NonNull String key, int value) {
        if (ctx == null) return;
        prefs(ctx).edit().putInt(key, value).apply();
        CrossProcessState.put(ctx, key, value);
    }

    public static void putFloat(Context ctx, @NonNull String key, float value) {
        if (ctx == null) return;
        prefs(ctx).edit().putFloat(key, value).apply();
        CrossProcessState.put(ctx, key, (double) value);
    }

    public static void putString(Context ctx, @NonNull String key, String value) {
        if (ctx == null) return;
        prefs(ctx).edit().putString(key, value).apply();
        CrossProcessState.put(ctx, key, value);
    }

    /** Mirrors an already-committed preference value into the shared file. */
    public static void mirror(Context ctx, @NonNull String key, Object value) {
        if (ctx == null || !isShared(key)) return;
        CrossProcessState.put(ctx, key, value);
    }

    /** Mirrors every shared key found in the given map (used after a bulk settings save). */
    public static void mirrorAll(Context ctx, @NonNull Map<String, ?> values) {
        if (ctx == null) return;
        JSONObject changes = new JSONObject();
        for (String key : SHARED_KEYS) {
            if (!values.containsKey(key)) continue;
            Object value = values.get(key);
            if (value == null) continue;
            try {
                changes.put(key, value instanceof Float ? (double) (Float) value : value);
            } catch (Exception ignored) {}
        }
        if (changes.length() > 0) CrossProcessState.merge(ctx, changes);
    }

    // ───────────────────────────── reads / sync ─────────────────────────────

    /**
     * Copies every shared value from the cross-process file into this process's preferences.
     *
     * <p>Call this before reading settings and before rewriting them in bulk. Without it, a
     * process that has been alive since before the other one made a change would write its stale
     * copy back over the file.
     *
     * @return number of values that were actually stale in this process
     */
    public static int syncIntoPreferences(Context ctx) {
        if (ctx == null) return 0;
        JSONObject state = CrossProcessState.readAll(ctx);
        if (state.length() == 0) {
            // First run after the update: seed the shared file from whatever the user already has,
            // so nothing is lost and later syncs have something to restore.
            seedFromPreferences(ctx);
            return 0;
        }
        SharedPreferences prefs = prefs(ctx);
        SharedPreferences.Editor editor = prefs.edit();
        int changed = 0;
        for (String key : SHARED_KEYS) {
            if (!state.has(key)) continue;
            Object shared = state.opt(key);
            if (shared == null || shared == JSONObject.NULL) continue;
            Object mine = prefs.getAll().get(key);
            try {
                if (shared instanceof Boolean) {
                    if (!(mine instanceof Boolean) || (Boolean) mine != (Boolean) shared) {
                        editor.putBoolean(key, (Boolean) shared);
                        changed++;
                    }
                } else if (shared instanceof Integer) {
                    if (!(mine instanceof Integer) || ((Integer) mine).intValue() != (Integer) shared) {
                        editor.putInt(key, (Integer) shared);
                        changed++;
                    }
                } else if (shared instanceof Number) {
                    // JSON gives back Double/Long for numbers written as float/long
                    if (mine instanceof Float) {
                        float value = (float) ((Number) shared).doubleValue();
                        if ((Float) mine != value) { editor.putFloat(key, value); changed++; }
                    } else {
                        int value = ((Number) shared).intValue();
                        if (!(mine instanceof Integer) || ((Integer) mine) != value) {
                            editor.putInt(key, value);
                            changed++;
                        }
                    }
                } else if (shared instanceof String) {
                    if (!shared.equals(mine)) { editor.putString(key, (String) shared); changed++; }
                }
            } catch (Exception e) {
                Log.w(TAG, "could not sync " + key, e);
            }
        }
        if (changed > 0) {
            editor.commit(); // commit, not apply: callers read the values immediately after
            Log.i(TAG, "restored " + changed + " shared setting(s) written by the other process");
        }
        return changed;
    }

    /** Writes the user's current values into the shared file (one-time bootstrap). */
    private static void seedFromPreferences(@NonNull Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        Map<String, ?> all = prefs.getAll();
        JSONObject changes = new JSONObject();
        for (String key : SHARED_KEYS) {
            Object value = all.get(key);
            if (value == null) continue; // never seed a default the user has not chosen
            try {
                changes.put(key, value instanceof Float ? (double) (Float) value : value);
            } catch (Exception ignored) {}
        }
        if (changes.length() > 0) CrossProcessState.merge(ctx, changes);
    }

    private static SharedPreferences prefs(@NonNull Context ctx) {
        if (LauncherPreferences.DEFAULT_PREF != null) return LauncherPreferences.DEFAULT_PREF;
        return ctx.getApplicationContext()
                .getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
    }
}
