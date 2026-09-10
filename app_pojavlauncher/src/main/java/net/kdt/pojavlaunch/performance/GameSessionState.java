package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import net.kdt.pojavlaunch.Tools;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cross-process marker for "a game session owns this phone right now", plus the in-process cache of it.
 *
 * <p>The marker has to be a file: the game runs in {@code :game} and the launcher UI in
 * {@code :launcher}, so nothing in-memory can bridge them. What used to be wrong was <i>how often</i> it
 * was read — every call did a disk read, a JSON parse and a {@code /proc/<pid>/cmdline} read, including
 * from Firebase message handling and from application startup. Now:
 * <ul>
 *   <li>the owning process writes the marker once on transition and deletes it once on teardown;</li>
 *   <li>every other reader gets a volatile cached boolean ({@link #isGameActiveCached()});</li>
 *   <li>the cache is refreshed from disk at most once every two seconds, and only ever on a background
 *   looper ({@link #refreshAsync()}), never from a UI callback.</li>
 * </ul>
 *
 * <p>The refresh also feeds {@link LauncherQuietPolicy}, which is how a profile's "launcher does less
 * while you play" reaches the launcher process.
 */
public final class GameSessionState {

    private static final String DIR = "performance";
    private static final String FILE = "game_session.json";
    private static final long CACHE_TTL_MS = 2_000L;
    private static final long MAX_SESSION_AGE_MS = 12L * 60L * 60L * 1000L;

    private static volatile boolean sActive;
    private static volatile long sLastRefresh;
    private static Handler sBackground;

    private GameSessionState() {}

    private static File marker() {
        return new File(new File(Tools.DIR_DATA, DIR), FILE);
    }

    // ── game process ──

    public static synchronized void markActive(Context context) {
        sActive = true;
        sLastRefresh = System.currentTimeMillis();
        applyQuiet();
        if (context == null) return;
        final Context app = context.getApplicationContext();
        try {
            background().post(new Runnable() {
                @Override public void run() {
                    writeMarker(app);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * The disk write is posted, not done inline: {@code markActive} runs on the game activity's
     * {@code onCreate}, i.e. on the launch path, and a synchronous file write there is exactly the kind
     * of main-thread I/O the launcher is supposed to stop doing. A process that dies before the write
     * lands is harmless — a stale marker is rejected by the pid liveness check in {@link #readMarker}.
     */
    private static synchronized void writeMarker(Context app) {
        try {
            File f = marker();
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            JSONObject o = new JSONObject();
            o.put("pid", Process.myPid());
            o.put("started", System.currentTimeMillis());
            o.put("process", app.getPackageName() + ":game");
            File tmp = new File(parent, "game_session.tmp");
            Tools.write(tmp.getAbsolutePath(), o.toString());
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) {
                org.apache.commons.io.FileUtils.copyFile(tmp, f);
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void clear() {
        sActive = false;
        sLastRefresh = System.currentTimeMillis();
        applyQuiet();
        try {
            File f = marker();
            if (!f.isFile()) return;
            JSONObject o = new JSONObject(Tools.read(f.getAbsolutePath()));
            if (o.optInt("pid", -1) == Process.myPid()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    // ── any other process / thread ──

    /** Volatile read only: what the last refresh concluded. Use this on message and startup paths. */
    public static boolean isGameActiveCached() {
        return sActive;
    }

    /** Cached value, with a background refresh scheduled if it may be stale. */
    public static boolean isGameActive(Context ctx) {
        maybeRefresh(ctx);
        return sActive;
    }

    /** Compatibility form for callers that have no Context to hand over. Never touches disk. */
    public static boolean isGameActive() {
        return sActive;
    }

    /** Background refresh for UI callers; returns immediately with the previous value. */
    public static void refreshAsync(final Context ctx) {
        sLastRefresh = System.currentTimeMillis();
        try {
            background().post(new Runnable() {
                @Override public void run() {
                    readMarker(ctx == null ? null : ctx.getApplicationContext());
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Refresh that may block the caller — only for threads already off the main one. */
    public static void refreshNow(Context ctx) {
        sLastRefresh = System.currentTimeMillis();
        readMarker(ctx == null ? null : ctx.getApplicationContext());
    }

    private static void maybeRefresh(Context ctx) {
        if (System.currentTimeMillis() - sLastRefresh < CACHE_TTL_MS) return;
        refreshAsync(ctx);
    }

    private static synchronized Handler background() {
        if (sBackground == null) {
            HandlerThread t = new HandlerThread("CS-SessionState", Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sBackground = new Handler(t.getLooper());
        }
        return sBackground;
    }

    private static void readMarker(Context app) {
        boolean active = false;
        try {
            File f = marker();
            if (f.isFile()) {
                JSONObject o = new JSONObject(Tools.read(f.getAbsolutePath()));
                int pid = o.optInt("pid", -1);
                long age = System.currentTimeMillis() - o.optLong("started", 0);
                if (pid > 0 && age >= 0 && age <= MAX_SESSION_AGE_MS && processIsGame(pid)) {
                    active = true;
                } else if (pid == Process.myPid()) {
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
            active = false;
        }
        sActive = active;
        applyQuiet();
    }

    /**
     * Liveness check for the marker's owner. Reads only {@code /proc/<pid>/cmdline}, and only from
     * {@link #readMarker}, which is rate limited to one call per two seconds per process.
     */
    private static boolean processIsGame(int pid) {
        File cmd = new File("/proc/" + pid + "/cmdline");
        if (!cmd.isFile()) return false;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FileInputStream in = null;
        try {
            in = new FileInputStream(cmd);
            byte[] b = new byte[128];
            int n;
            while ((n = in.read(b)) > 0) bytes.write(b, 0, n);
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
        String process = new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                .replace("\u0000", "");
        return process.endsWith(":game") || process.contains(":game");
    }

    /**
     * Bridges the profile's launcher-quiet request into whichever process is asking. Called from the
     * game process on session transitions and from the UI process on each marker refresh.
     */
    private static void applyQuiet() {
        if (sActive) {
            PerformancePolicy policy = PerformancePolicy.current();
            if (policy != null) {
                LauncherQuietPolicy.apply(policy.quietLevel);
                return;
            }
            // Another process froze the policy; re-derive the level from the persisted mode.
            LauncherQuietPolicy.applyForPersistedMode();
        } else {
            LauncherQuietPolicy.apply(PerformancePolicy.QuietLevel.OFF);
        }
    }
}
