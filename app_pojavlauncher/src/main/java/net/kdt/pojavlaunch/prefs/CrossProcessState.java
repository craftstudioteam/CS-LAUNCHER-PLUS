package net.kdt.pojavlaunch.prefs;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/**
 * A tiny state store that is actually safe to share between the launcher process and the game
 * process.
 *
 * <p><b>Why this exists.</b> The launcher runs in {@code :launcher} and the game activity runs in
 * {@code :game} (see AndroidManifest). Both of them read and write the same
 * {@code cslauncher_settings} SharedPreferences, but SharedPreferences opened with
 * {@code MODE_PRIVATE} keeps a <i>per-process in-memory copy</i> of the whole file and rewrites
 * that whole copy on every commit. So whatever process writes last silently throws away every
 * change the other process made since it started — which is exactly how the saved server IP and
 * the FPS toggle kept coming back reset after a relaunch. {@code MODE_MULTI_PROCESS} is
 * deprecated and unreliable, so it is not a fix either.
 *
 * <p><b>How this is different.</b> Nothing is cached: every read hits the file and every write
 * re-reads, merges, and replaces it atomically (temp file + rename) under an inter-process
 * {@link FileLock}. Two processes can therefore interleave writes without losing each other's
 * keys, and a value written by the game is visible to the launcher the instant it asks for it.
 *
 * <p>Only genuinely shared state belongs here — see {@link SharedSettings}. Everything else keeps
 * using the existing SharedPreferences exactly as before.
 */
public final class CrossProcessState {

    private static final String TAG = "CrossProcessState";
    private static final String FILE_NAME = "cs_shared_state.json";
    private static final Object IO_GUARD = new Object();

    private CrossProcessState() {}

    private static File file(@NonNull Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** Every entry point is null-tolerant: callers may be detached fragments or dead dialogs. */
    private static boolean unusable(Context ctx) {
        return ctx == null || ctx.getApplicationContext() == null;
    }

    private static File lockFile(@NonNull Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME + ".lock");
    }

    private static File backupFile(@NonNull Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME + ".bak");
    }

    // ───────────────────────────── raw access ─────────────────────────────

    /** @return the whole state; never null, empty when the file is missing or damaged. */
    @NonNull
    public static JSONObject readAll(Context ctx) {
        if (unusable(ctx)) return new JSONObject();
        synchronized (IO_GUARD) {
            File f = file(ctx);
            if (!f.isFile() || f.length() == 0) return new JSONObject();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buffer = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
                int read = in.read(buffer);
                if (read <= 0) return new JSONObject();
                return new JSONObject(new String(buffer, 0, read, StandardCharsets.UTF_8));
            } catch (Exception e) {
                // Never start empty on a damaged file: that would look exactly like "all my
                // servers vanished". Fall back to the last known-good copy instead.
                Log.w(TAG, "unreadable state file, falling back to the backup", e);
                return readFileOrEmpty(backupFile(ctx));
            }
        }
    }

    private static JSONObject readFileOrEmpty(File f) {
        if (!f.isFile() || f.length() == 0) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            int read = in.read(buffer);
            if (read <= 0) return new JSONObject();
            return new JSONObject(new String(buffer, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Merges {@code changes} into the stored state under an inter-process lock.
     * A key mapped to {@link JSONObject#NULL} is removed.
     */
    public static void merge(Context ctx, @NonNull JSONObject changes) {
        if (unusable(ctx)) return;
        synchronized (IO_GUARD) {
            File f = file(ctx);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "cannot create " + parent);
                return;
            }
            FileLock lock = null;
            RandomAccessFile lockHandle = null;
            try {
                lockHandle = new RandomAccessFile(lockFile(ctx), "rw");
                // Blocking lock: the other process may be mid-write. This is a few bytes of JSON,
                // so the wait is microseconds, and it guarantees no lost update.
                lock = lockHandle.getChannel().lock();

                JSONObject current = readAllLocked(f);
                if (current.length() == 0) {
                    // main file missing or damaged — recover whatever the backup still holds
                    JSONObject backup = readFileOrEmpty(backupFile(ctx));
                    for (java.util.Iterator<String> it = backup.keys(); it.hasNext(); ) {
                        String key = it.next();
                        current.put(key, backup.get(key));
                    }
                }
                for (java.util.Iterator<String> it = changes.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Object value = changes.opt(key);
                    if (value == null || value == JSONObject.NULL) current.remove(key);
                    else current.put(key, value);
                }

                File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(current.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.getFD().sync(); // survive a kill right after the game starts
                }
                // Refresh the backup from the copy that is about to be replaced, so there is
                // always one complete file on disk even if the device dies mid-rename.
                if (f.isFile() && f.length() > 0) copy(f, backupFile(ctx));

                if (!tmp.renameTo(f)) {
                    // renameTo can fail if the target exists on some filesystems
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    if (!tmp.renameTo(f)) {
                        Log.w(TAG, "could not replace " + f);
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "state write failed", e);
            } finally {
                try { if (lock != null) lock.release(); } catch (IOException ignored) {}
                try { if (lockHandle != null) lockHandle.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void copy(File from, File to) {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "backup copy failed", e);
        }
    }

    private static JSONObject readAllLocked(File f) {
        if (!f.isFile() || f.length() == 0) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[(int) Math.min(f.length(), 4L * 1024 * 1024)];
            int read = in.read(buffer);
            if (read <= 0) return new JSONObject();
            return new JSONObject(new String(buffer, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "unreadable state file during write, replacing", e);
            return new JSONObject();
        }
    }

    // ───────────────────────────── typed helpers ─────────────────────────────

    public static boolean contains(Context ctx, @NonNull String key) {
        return readAll(ctx).has(key);
    }

    @Nullable
    public static String getString(Context ctx, @NonNull String key, @Nullable String fallback) {
        JSONObject root = readAll(ctx);
        return root.has(key) ? root.optString(key, fallback) : fallback;
    }

    public static boolean getBoolean(Context ctx, @NonNull String key, boolean fallback) {
        JSONObject root = readAll(ctx);
        return root.has(key) ? root.optBoolean(key, fallback) : fallback;
    }

    public static int getInt(Context ctx, @NonNull String key, int fallback) {
        JSONObject root = readAll(ctx);
        return root.has(key) ? root.optInt(key, fallback) : fallback;
    }

    public static long getLong(Context ctx, @NonNull String key, long fallback) {
        JSONObject root = readAll(ctx);
        return root.has(key) ? root.optLong(key, fallback) : fallback;
    }

    public static float getFloat(Context ctx, @NonNull String key, float fallback) {
        JSONObject root = readAll(ctx);
        return root.has(key) ? (float) root.optDouble(key, fallback) : fallback;
    }

    public static void put(Context ctx, @NonNull String key, @Nullable Object value) {
        try {
            JSONObject changes = new JSONObject();
            changes.put(key, value == null ? JSONObject.NULL : value);
            merge(ctx, changes);
        } catch (Exception e) {
            Log.w(TAG, "put failed for " + key, e);
        }
    }

    public static void remove(Context ctx, @NonNull String key) {
        put(ctx, key, null);
    }
}
