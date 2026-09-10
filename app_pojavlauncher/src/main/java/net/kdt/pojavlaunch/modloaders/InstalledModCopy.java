package net.kdt.pojavlaunch.modloaders;

import android.content.Context;
import android.widget.Toast;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The only place that knows how a mod jar lands in another profile's mods
 * folder. Both the picker page and the "hold the copy button and drop it on a
 * profile" gesture use it, so the two paths can never drift apart.
 */
public final class InstalledModCopy {

    public interface Callback {
        void onResult(boolean ok, File dest);
    }

    /**
     * ProgressKeeper record for a copy. Notifying the shade is the whole point of
     * routing the big ones through here: a 200 MB modpack jar copied inside the
     * app looks identical to a hung app unless something outside the app says
     * how far it has got.
     */
    public static final String RECORD = "mod_copy";

    private InstalledModCopy() {}

    /**
     * A jar in {@code dir} that is already the same mod: identical bytes, or at
     * least the same name and size. Returns null when nothing matches.
     *
     * Name-only is deliberately not enough — re-downloading "OptiFine.jar" after
     * editing its config is a different file — so a same-named candidate is only
     * reported when its size matches too, and any other candidate must hash the
     * same before we claim it.
     */
    public static File findDuplicate(File src, File dir) {
        if (src == null || !src.isFile() || dir == null || !dir.isDirectory()) return null;
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        long len = src.length();
        String srcHash = null;                                  // computed only if needed
        for (File f : kids) {
            if (f == null || !f.isFile() || f.equals(src)) continue;
            String n = f.getName();
            if (n.endsWith(".part") || n.endsWith(".disabled.tmp")) continue;
            String base = n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n;
            if (base.equals(src.getName()) && f.length() == len) return f;
        }
        for (File f : kids) {                                   // second pass: content match
            if (f == null || !f.isFile() || f.length() != len || f.equals(src)) continue;
            // A half-finished ".part" is the same length as the source by
            // definition and hashes the same once written — claiming it as an
            // installed mod would turn a retry into a permanent "already there".
            String n2 = f.getName();
            if (n2.endsWith(".part") || n2.endsWith(".disabled.tmp")) continue;
            if (srcHash == null) {
                srcHash = sha1(src);
                if (srcHash == null) return null;               // no hash, no claim
            }
            String other = sha1(f);
            if (other != null && other.equals(srcHash)) return f;
        }
        return null;
    }

    private static String sha1(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * copy() plus the things only a long transfer needs: skip-if-already-there and
     * a ProgressKeeper record that feeds the notification shade.
     *
     * @param onSkipped runs on the worker thread with the file it matched, or null
     *                  when the copy really did start.
     */
    public static void copyWithProgress(File src, File dest, Callback callback,
                                        DuplicateCallback onSkipped) {
        copy(src, dest, null, -1, onSkipped, callback);
    }

    public interface DuplicateCallback {
        /** Runs on the worker thread; null means "no match, the copy is running". */
        void onDuplicate(File sameFileAlreadyThere);
    }

    /** @return the mods directory of a profile key, or null if the profile is gone. */
    public static File modsDirFor(String profileKey) {
        try {
            LauncherProfiles.load();
            if (LauncherProfiles.mainProfileJson == null
                    || LauncherProfiles.mainProfileJson.profiles == null) return null;
            MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
            if (p == null) return null;
            return new File(Tools.getGameDirPath(p), "mods");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Streams {@code src} into {@code dest} on the shared executor. Never clobbers
     * silently and never leaves a half-written jar behind.
     */
    public static void copy(File src, File dest, Callback callback) {
        copy(src, dest, null, -1, null, callback);
    }

    /**
     * @param reportTotal bytes to report against; -1 means "decide yourself", and
     *                    the rule is that only a copy big enough to be worth a
     *                    notification is reported (see below).
     */
    public static void copy(File src, File dest, byte[] buffer, long reportTotal,
                            Callback callback) {
        copy(src, dest, buffer, reportTotal, null, callback);
    }

    private static void copy(File src, File dest, byte[] buffer, long reportTotal,
                             final DuplicateCallback onSkipped, Callback callback) {
        if (src == null || !src.isFile() || dest == null) {
            if (callback != null) callback.onResult(false, dest);
            return;
        }
        final File target = dest;
        final long srcLen = src.length();
        final long report = reportTotal > 0 ? reportTotal
                : (srcLen >= 8L * 1024 * 1024 ? srcLen : -1);
        PojavApplication.sExecutorService.execute(() -> {
            // The duplicate scan hashes bytes, so it happens HERE on the worker:
            // hashing a 200 MB modpack jar on the main thread is an ANR with a
            // progress dialog frozen in front of it.
            if (onSkipped != null) {
                File dup = findDuplicate(src, target.getParentFile());
                if (dup != null) {
                    onSkipped.onDuplicate(dup);
                    if (callback != null) callback.onResult(true, dup);
                    return;
                }
                onSkipped.onDuplicate(null);
            }
            // Only the failure branch touches this, so it has to start true —
            // javac rightly refuses an otherwise unassigned local.
            boolean ok = true;
            File tmp = new File(target.getParentFile(), target.getName() + ".part");
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (!tmp.getParentFile().isDirectory() && !tmp.getParentFile().mkdirs()) {
                    throw new java.io.IOException("cannot create " + tmp.getParent());
                }
                byte[] buf = buffer != null && buffer.length >= 128 * 1024
                        ? buffer : new byte[128 * 1024];
                int n;
                long done = 0;
                long startedAt = System.nanoTime();
                long lastBeat = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (report > 0) {
                        done += n;
                        long now = System.currentTimeMillis();
                        // 250 ms is the notification heartbeat's own interval:
                        // submitting faster than that buys nothing and spams the
                        // listener list, which lives on the main thread.
                        if (now - lastBeat > 250) {
                            lastBeat = now;
                            double secs = Math.max(0.001, (System.nanoTime() - startedAt) / 1e9);
                            report(done, report, done / secs);
                        }
                    }
                }
                if (report > 0) report(done, report, 0);
                out.flush();
            } catch (Throwable t) {
                ok = false;
            } finally {
                if (report > 0) {
                    net.kdt.pojavlaunch.progresskeeper.ProgressKeeper.submitProgress(
                            RECORD, -1, -1, (Object) null);
                }
            }
            if (ok && tmp.length() != src.length()) ok = false;
            if (ok) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();           // replace semantics: the copy wins
                ok = tmp.renameTo(target);
                if (!ok && tmp.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            final boolean result = ok;
            if (callback != null) callback.onResult(result, target);
        });
    }

    /** The MB/MB/s triple every byte-payload consumer in the app already speaks. */
    private static void report(long done, long total, double bytesPerSec) {
        net.kdt.pojavlaunch.progresskeeper.ProgressKeeper.submitProgress(RECORD,
                (int) Math.min(99, done * 100 / Math.max(1, total)),
                R.string.of_dl_progress,
                (double) (done / 1048576.0), (double) (total / 1048576.0),
                bytesPerSec / 1048576.0);
    }

    /** Toast helper so both call sites report the same way. */
    public static void toast(Context ctx, String msg) {
        if (ctx == null) return;
        Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
