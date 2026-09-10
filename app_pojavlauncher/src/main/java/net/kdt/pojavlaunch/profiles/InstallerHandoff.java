package net.kdt.pojavlaunch.profiles;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.JavaGUILauncherActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.notifications.CsNotifier;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.Map;

/**
 * Phase 11 (item 1): everything that happens AFTER a modloader installer jar has
 * been downloaded, in one place that does not depend on a Fragment being attached.
 *
 * Why this exists — the "OptiFine needs two clicks" report:
 * <ol>
 *   <li>The download can take minutes (vanilla version first). By the time it
 *       finished, the create page was often gone ({@code !isAdded()}), and the
 *       old callback silently returned: jar cached, installer never launched.
 *       The second click found the cached jar and finished instantly.</li>
 *   <li>The installer runs in the {@code :gui_installer} process and writes the
 *       profile into launcher_profiles.json itself, then calls
 *       {@code System.exit(0)}. Nothing told the launcher process about it — no
 *       result, no reload, no selection, no message. The new profile only
 *       appeared after the user wandered back to Home and pressed something.</li>
 * </ol>
 * So: the hand-off is recorded in prefs, the installer is started from the app
 * context, and a watcher on the profiles file (plus {@link #onLauncherResumed})
 * reloads profiles, applies the user's name/icon, cleans the installer's
 * {@code javaArgs}, makes the profile current and tells the user.
 */
public final class InstallerHandoff {
    private static final String TAG = "InstallerHandoff";
    private static final String PREFS = "installer_handoff";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_SINCE = "since";
    private static final String KEY_MTIME = "mtime";
    private static final String KEY_SIZE = "size";
    private static final String KEY_LABEL = "label";
    private static final String KEY_LOADER = "loader";
    /** Give up watching after this long (a stuck installer, a killed process...). */
    private static final long WATCH_LIMIT_MS = 6 * 60 * 1000L;
    private static final long POLL_MS = 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable sPoller;
    private static boolean sFinishing;
    /** mtime first seen changed by the poller — read only once it stays put. */
    private static long sSeenMtime = -1;

    private InstallerHandoff() {}

    /**
     * Record the user's name/icon for the profile the installer is about to
     * create and start the installer. Safe from any thread and without a
     * Fragment; {@code label} is what the completion toast calls the result.
     *
     * @param token the substring the installer's {@code lastVersionId} will contain
     *              (e.g. {@code "1.21.11-OptiFine"}), used to find the new profile
     */
    public static void start(Context context, Intent installerIntent, String token,
                             @Nullable String profileName, @Nullable String profileIcon,
                             String label, String loader) {
        final Context app = context.getApplicationContext();
        try {
            PendingProfileRename.record(app, token, profileName, profileIcon);
        } catch (Throwable t) {
            Log.w(TAG, "could not record pending rename", t);
        }
        File profiles = new File(Tools.DIR_GAME_NEW, "launcher_profiles.json");
        prefs(app).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_SINCE, System.currentTimeMillis())
                .putLong(KEY_MTIME, profiles.lastModified())
                .putLong(KEY_SIZE, profiles.length())
                .putString(KEY_LABEL, label == null ? "Profile" : label)
                .putString(KEY_LOADER, loader == null ? "" : loader)
                .apply();
        try {
            Activity host = ContextExecutor.peekActivity();
            if (host != null && !host.isFinishing() && !host.isDestroyed()) {
                host.startActivity(installerIntent);
            } else {
                installerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                app.startActivity(installerIntent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "installer start failed", t);
            prefs(app).edit().clear().apply();
            CsNotifier.error("Installer failed to start", t.getMessage() == null ? "Try again" : t.getMessage(), null, null);
            return;
        }
        CsNotifier.info(label + " installer running", "Hang on — the profile is created automatically");
        armWatcher(app);
    }

    /** {@code true} while an installer we started has not been reconciled yet. */
    public static boolean isPending(Context context) {
        return prefs(context).getBoolean(KEY_ACTIVE, false);
    }

    /**
     * LauncherActivity.onResume: the installer process has exited (or the user
     * came back). Reconcile immediately instead of waiting for the next poll.
     */
    public static void onLauncherResumed(Context context) {
        final Context app = context.getApplicationContext();
        if (!isPending(app)) return;
        if (System.currentTimeMillis() - prefs(app).getLong(KEY_SINCE, 0L) > WATCH_LIMIT_MS) {
            prefs(app).edit().clear().apply();
            stopWatcher();
            return;
        }
        armWatcher(app);
        check(app, true);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static void armWatcher(final Context app) {
        stopWatcher();
        sPoller = new Runnable() {
            @Override
            public void run() {
                if (!isPending(app)) { sPoller = null; return; }
                if (System.currentTimeMillis() - prefs(app).getLong(KEY_SINCE, 0L) > WATCH_LIMIT_MS) {
                    prefs(app).edit().clear().apply();
                    sPoller = null;
                    return;
                }
                check(app, false);
                if (sPoller == this) MAIN.postDelayed(this, POLL_MS);
            }
        };
        MAIN.postDelayed(sPoller, POLL_MS);
    }

    private static void stopWatcher() {
        if (sPoller != null) MAIN.removeCallbacks(sPoller);
        sPoller = null;
    }

    /**
     * The installer has finished when launcher_profiles.json changed since the
     * hand-off (mtime or size). While the installer process is still alive the
     * file must have been quiet for one poll before it is read: the agent
     * rewrites it once more right after the success dialog. A resume of the
     * launcher means the installer window is gone, so a changed file is read at
     * once — and an unchanged one after a grace period means the user closed
     * the installer without installing: the hand-off is dropped (the phase-10
     * rename fallback in Home stays armed and is harmless).
     */
    private static void check(final Context app, boolean fromResume) {
        if (sFinishing) return;
        SharedPreferences p = prefs(app);
        File profiles = new File(Tools.DIR_GAME_NEW, "launcher_profiles.json");
        long mtime = profiles.lastModified();
        long size = profiles.length();
        boolean changed = mtime > 0
                && (mtime != p.getLong(KEY_MTIME, 0L) || size != p.getLong(KEY_SIZE, -1L));
        if (!changed) {
            sSeenMtime = -1;
            if (fromResume) scheduleCancelCheck(app);
            return;
        }
        if (!fromResume && mtime != sSeenMtime) {
            sSeenMtime = mtime;           // first sighting: wait one more poll
            return;
        }
        sFinishing = true;
        MAIN.postDelayed(() -> finish(app), fromResume ? 250L : 300L);
    }

    /** Launcher resumed but nothing was written: give a slow final write 5 s, then drop the hand-off. */
    private static void scheduleCancelCheck(final Context app) {
        if (System.currentTimeMillis() - prefs(app).getLong(KEY_SINCE, 0L) < 8000L) return;
        MAIN.postDelayed(() -> {
            if (!isPending(app) || sFinishing) return;
            SharedPreferences p = prefs(app);
            File f = new File(Tools.DIR_GAME_NEW, "launcher_profiles.json");
            boolean changed = f.lastModified() != p.getLong(KEY_MTIME, 0L)
                    || f.length() != p.getLong(KEY_SIZE, -1L);
            if (changed) { check(app, true); return; }
            p.edit().clear().apply();
            stopWatcher();
            Log.i(TAG, "installer closed without writing a profile — hand-off dropped");
        }, 5000L);
    }

    private static void finish(final Context app) {
        final String label = prefs(app).getString(KEY_LABEL, "Profile");
        final String loader = prefs(app).getString(KEY_LOADER, "");
        prefs(app).edit().clear().apply();
        stopWatcher();
        sSeenMtime = -1;
        LauncherProfiles.loadAsync(() -> {
            sFinishing = false;
            String selectedKey = null;
            try {
                boolean renamed = PendingProfileRename.apply(app);
                selectedKey = LauncherPreferences.DEFAULT_PREF.getString(
                        LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
                if (!renamed) {
                    // Name already matched (or no rename recorded): still select the
                    // newest profile of this loader so the user lands on it.
                    String newest = newestProfileKey(loader);
                    if (newest != null) {
                        selectedKey = newest;
                        LauncherPreferences.DEFAULT_PREF.edit()
                                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, newest).apply();
                    }
                }
                boolean dirty = scrubInstallerArgs(selectedKey);
                if (dirty) LauncherProfiles.write();
                // Home's stage launches the FIRST profile in order — bring the new
                // one to the front so "press Play" really plays it.
                if (selectedKey != null) promoteToFront(selectedKey);
            } catch (Throwable t) {
                Log.w(TAG, "post-install reconcile failed", t);
            }
            if (selectedKey != null) {
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, selectedKey);
            }
            // (load()/write() above already notified LauncherProfiles' update
            // listeners — Home rebinds its stage + library from them.)
            CsNotifier.success(label + " ready", "Profile created and selected — press Play");
        });
    }

    /** Same move as Home's "Set as Primary": key first among its group, then persisted. */
    private static void promoteToFront(String key) {
        java.util.List<String> order = new java.util.ArrayList<>();
        MinecraftProfile target = null;
        int favCount = 0;
        for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.getOrderedEntries()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (e.getKey().equals(key)) { target = e.getValue(); continue; }
            if (e.getValue().favorite) favCount++;
            order.add(e.getKey());
        }
        if (target == null) return;
        order.add(target.favorite ? 0 : Math.min(favCount, order.size()), key);
        LauncherProfiles.applyProfileOrder(order);
    }

    /** Newest profile whose version id mentions the loader (e.g. "OptiFine"). */
    @Nullable
    private static String newestProfileKey(String loader) {
        if (loader == null || loader.isEmpty()) return null;
        if (LauncherProfiles.mainProfileJson == null || LauncherProfiles.mainProfileJson.profiles == null) return null;
        String bestKey = null, bestCreated = null;
        String needle = loader.toLowerCase();
        for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile prof = e.getValue();
            if (prof == null || prof.lastVersionId == null) continue;
            if (!prof.lastVersionId.toLowerCase().contains(needle)) continue;
            if (bestCreated == null || (prof.created != null && prof.created.compareTo(bestCreated) > 0)) {
                bestCreated = prof.created == null ? "" : prof.created;
                bestKey = e.getKey();
            }
        }
        return bestKey;
    }

    /**
     * Installers seed desktop JVM flags ({@code -Xmx2G -XX:+UnlockExperimentalVMOptions
     * -XX:+UseG1GC ...}) into the profile. On Android those override the RAM slider
     * and the launcher's own GC tuning, so they are dropped from the profile we just
     * created. Returns true when something was removed.
     */
    private static boolean scrubInstallerArgs(@Nullable String key) {
        if (key == null || LauncherProfiles.mainProfileJson == null
                || LauncherProfiles.mainProfileJson.profiles == null) return false;
        MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(key);
        if (prof == null || prof.javaArgs == null) return false;
        String args = prof.javaArgs.trim();
        if (args.isEmpty()) { prof.javaArgs = null; return true; }
        if (args.contains("-Xmx") || args.contains("UseG1GC") || args.contains("UnlockExperimentalVMOptions")) {
            prof.javaArgs = null;
            return true;
        }
        return false;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
