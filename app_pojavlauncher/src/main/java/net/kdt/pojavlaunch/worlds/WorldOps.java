package net.kdt.pojavlaunch.worlds;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Background ops for the World Manager. Every method runs on
 * {@link PojavApplication#sExecutorService} and reports back on the main
 * thread through {@link OpCallback}. All zip/unzip paths guard against
 * path-traversal entries and never throw across the boundary — failures are
 * delivered as {@code onDone(false, reason)}.
 */
public final class WorldOps {

    public interface OpCallback {
        /** pct = 0..100 (or -1 for indeterminate); called on the main thread. */
        default void onProgress(int pct, @Nullable String message) {}
        /** Called exactly once, on the main thread. */
        void onDone(boolean ok, @NonNull String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SIZE_CAP = 60L * 1024 * 1024 * 1024; // 60 GB safety cap

    private WorldOps() {}

    @NonNull
    public static File backupsDirFor(@NonNull File gameDir) {
        return new File(gameDir, "csl_backups/worlds");
    }

    // ═══════════════════════════ DELETE ═══════════════════════════

    public static void deleteWorld(@NonNull WorldEntry world, @NonNull OpCallback cb) {
        run(cb, () -> {
            progress(cb, -1, "Deleting…");
            deleteRecursive(world.folder);
            done(cb, true, "Deleted \"" + world.displayName + "\"");
        });
    }

    // ═══════════════════════════ RENAME ═══════════════════════════

    public static void renameWorld(@NonNull WorldEntry world, @NonNull String newName,
                                   @NonNull OpCallback cb) {
        run(cb, () -> {
            progress(cb, -1, "Renaming…");
            NbtIO.renameLevel(world.folder, newName);
            // Also rename the folder when the new name is filesystem-safe and
            // Minecraft is not running (best effort — NBT rename already wins).
            String safe = newName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (!safe.isEmpty() && !safe.equals(world.folderName)) {
                File target = new File(world.folder.getParentFile(), safe);
                if (!target.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    world.folder.renameTo(target);
                }
            }
            done(cb, true, "Renamed to \"" + newName + "\"");
        });
    }

    // ═══════════════════════════ DUPLICATE ═══════════════════════════

    public static void duplicateWorld(@NonNull WorldEntry world, @NonNull OpCallback cb) {
        run(cb, () -> {
            File parent = world.folder.getParentFile();
            if (parent == null) { done(cb, false, "No saves folder"); return; }
            File dest = uniqueSibling(parent, world.folderName + " (Copy)");
            progress(cb, 0, "Copying…");
            copyDirectory(world.folder, dest, pct -> progress(cb, pct, "Copying…"));
            // The copy keeps the same LevelName — that's exactly what vanilla
            // Minecraft does when you use "Recreate"/import flows.
            done(cb, true, "Duplicated as " + dest.getName());
        });
    }

    // ═══════════════════════════ BACKUP ═══════════════════════════

    @NonNull
    public static String backupFileName(@NonNull WorldEntry world) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return world.folderName + "__" + stamp + ".zip";
    }

    public static void backupWorld(@NonNull WorldEntry world, @NonNull File gameDir,
                                   @NonNull OpCallback cb) {
        run(cb, () -> {
            File backups = backupsDirFor(gameDir);
            //noinspection ResultOfMethodCallIgnored
            backups.mkdirs();
            File zip = new File(backups, backupFileName(world));
            progress(cb, 0, "Packing backup…");
            try (OutputStream os = new FileOutputStream(zip)) {
                zipFolder(world.folder, os, pct -> progress(cb, pct, "Packing backup…"));
            }
            done(cb, true, "Backup saved:\n" + zip.getName());
        });
    }

    /** List backups belonging to one world (zip names start with folderName__). */
    @NonNull
    public static List<File> listBackups(@NonNull WorldEntry world, @NonNull File gameDir) {
        List<File> out = new ArrayList<>();
        File[] files = backupsDirFor(gameDir).listFiles();
        if (files == null) return out;
        String prefix = world.folderName + "__";
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(prefix) && f.getName().endsWith(".zip")) {
                out.add(f);
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    public static void restoreBackup(@NonNull File zip, @NonNull File savesDir,
                                     @NonNull OpCallback cb) {
        run(cb, () -> {
            String base = zip.getName().replaceFirst("__\\d{8}-\\d{6}\\.zip$", "");
            if (base.isEmpty()) base = zip.getName().replace(".zip", "");
            File dest = new File(savesDir, base);
            if (dest.exists()) dest = uniqueSibling(savesDir, base + " (Restored)");
            progress(cb, 0, "Restoring…");
            try (InputStream in = new FileInputStream(zip)) {
                unzipWorld(in, savesDir, dest.getName(), pct -> progress(cb, pct, "Restoring…"));
            }
            done(cb, true, "Restored as " + dest.getName());
        });
    }

    // ═══════════════════════════ EXPORT ═══════════════════════════

    public static void exportWorld(@NonNull Context ctx, @NonNull WorldEntry world,
                                   @NonNull Uri dest, @NonNull OpCallback cb) {
        run(cb, () -> {
            progress(cb, 0, "Exporting…");
            ContentResolver cr = ctx.getContentResolver();
            try (OutputStream os = cr.openOutputStream(dest)) {
                if (os == null) { done(cb, false, "Could not open destination"); return; }
                zipFolder(world.folder, os, pct -> progress(cb, pct, "Exporting…"));
            }
            done(cb, true, "Exported \"" + world.displayName + "\"");
        });
    }

    // ═══════════════════════════ IMPORT ═══════════════════════════

    public static void importWorld(@NonNull Context ctx, @NonNull Uri src,
                                   @NonNull File savesDir, @NonNull OpCallback cb) {
        run(cb, () -> {
            progress(cb, 0, "Importing…");
            ContentResolver cr = ctx.getContentResolver();
            String name = queryDisplayName(cr, src);
            if (name == null) name = "Imported World";
            name = name.replace(".zip", "").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (name.isEmpty()) name = "Imported World";
            File dest = uniqueSibling(savesDir, name);
            try (InputStream in = cr.openInputStream(src)) {
                if (in == null) { done(cb, false, "Could not read the file"); return; }
                unzipWorld(in, savesDir, dest.getName(), pct -> progress(cb, pct, "Importing…"));
            }
            done(cb, true, "Imported \"" + name + "\"");
        });
    }

    // ═══════════════════════════ COMPRESS ═══════════════════════════

    /** Zip into the backups folder, then optionally delete the original. */
    public static void compressWorld(@NonNull WorldEntry world, @NonNull File gameDir,
                                     boolean deleteSource, @NonNull OpCallback cb) {
        run(cb, () -> {
            File backups = backupsDirFor(gameDir);
            //noinspection ResultOfMethodCallIgnored
            backups.mkdirs();
            File zip = new File(backups, world.folderName + "_compressed.zip");
            //noinspection ResultOfMethodCallIgnored
            if (zip.exists()) zip.delete();
            progress(cb, 0, "Compressing…");
            try (OutputStream os = new FileOutputStream(zip)) {
                zipFolder(world.folder, os, pct -> progress(cb, pct, "Compressing…"));
            }
            if (deleteSource) {
                progress(cb, -1, "Removing original…");
                deleteRecursive(world.folder);
                done(cb, true, "Compressed to " + zip.getName() + "\nOriginal removed.");
            } else {
                done(cb, true, "Compressed to " + zip.getName());
            }
        });
    }

    // ══════════════════════ STORAGE / DATA ══════════════════════

    /** {@code {bytes used by `dir` (capped), freeBytes, totalBytes}} for StatFs(path). */
    @NonNull
    public static long[] storageStats(@NonNull File dir) {
        long used = folderSizeCapped(dir, SIZE_CAP);
        long free = 0, total = 0;
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            free = fs.getAvailableBytes();
            total = fs.getTotalBytes();
        } catch (Exception ignored) {}
        return new long[]{used, free, total};
    }

    public static long folderSizeCapped(@NonNull File root, long cap) {
        long[] sum = {0};
        sizeWalk(root, sum, cap, 0);
        return sum[0];
    }

    private static void sizeWalk(@NonNull File f, long[] sum, long cap, int depth) {
        if (sum[0] >= cap || depth > 8) return;
        if (f.isFile()) { sum[0] += f.length(); return; }
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (sum[0] >= cap) return;
            sizeWalk(k, sum, cap, depth + 1);
        }
    }

    public static int countDatapacks(@NonNull WorldEntry world) {
        File dir = world.datapacksDir();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            String n1 = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && (n1.endsWith(".zip") || n1.endsWith(".jar"))) n++;
            else if (f.isDirectory() && new File(f, "pack.mcmeta").exists()) n++;
        }
        return n;
    }

    // ══════════════════════ SCAN / FORMAT ══════════════════════

    /**
     * Enumerate valid worlds (folders containing level.dat) inside saves/.
     * Req-15 hardening: every directory is processed in isolation — a corrupt,
     * unreadable or deeply-nested level.dat must NEVER wipe the whole list
     * (it used to kill the scan silently and surface as "no worlds").
     */
    @NonNull
    public static List<WorldEntry> scanWorlds(@NonNull File savesDir) {
        List<WorldEntry> out = new ArrayList<>();
        File[] dirs;
        try {
            dirs = savesDir.listFiles();
        } catch (Throwable t) {
            android.util.Log.w("WorldOps", "saves dir not listable: " + savesDir, t);
            return out;
        }
        if (dirs == null) return out;
        for (File d : dirs) {
            try {
                if (!d.isDirectory()) continue;
                if (!new File(d, "level.dat").exists() && !new File(d, "level.dat_old").exists()) continue;
                WorldEntry w = new WorldEntry(d);
                NbtIO.LevelInfo info = null;
                try {
                    info = NbtIO.readLevelInfo(d);
                } catch (Throwable t) {
                    // Broken NBT (truncated file, exotic modded tag, OOM on
                    // absurd depth): keep the world, degrade to folder name.
                    android.util.Log.w("WorldOps", "level.dat unreadable in " + d.getName(), t);
                }
                if (info != null) {
                    if (info.levelName != null && !info.levelName.isEmpty()) w.displayName = info.levelName;
                    w.versionName = info.versionName;
                    w.lastPlayedMs = info.lastPlayedMs;
                    w.hasSeed = info.hasSeed;
                    w.seed = info.seed;
                    w.hardcore = info.hardcore;
                }
                out.add(w);
            } catch (Throwable t) {
                android.util.Log.w("WorldOps", "skipping unreadable entry " + d, t);
            }
        }
        return out;
    }

    /** Fill size + datapack count for a batch of entries (call off-UI thread).
     *  Req-15: failures degrade per-entry instead of aborting the batch. */
    public static void enrich(@NonNull List<WorldEntry> worlds) {
        for (WorldEntry w : worlds) {
            try {
                w.sizeBytes = folderSizeCapped(w.folder, SIZE_CAP);
                w.datapackCount = countDatapacks(w);
            } catch (Throwable t) {
                android.util.Log.w("WorldOps", "enrich failed for " + w.folderName, t);
            }
        }
    }

    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 0) return "—";
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.US, "%.1f %s", v, units[u]);
    }

    @NonNull
    public static String formatLastPlayed(long ms) {
        if (ms <= 0) return "Never played";
        long diff = System.currentTimeMillis() - ms;
        long min = diff / 60000;
        if (min < 1) return "Just now";
        if (min < 60) return min + "m ago";
        long h = min / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 30) return d + "d ago";
        long mo = d / 30;
        if (mo < 12) return mo + "mo ago";
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(ms));
    }

    // ══════════════════════ LOW-LEVEL HELPERS ══════════════════════

    private interface IntConsumer { void accept(int pct); }

    private static void copyDirectory(@NonNull File src, @NonNull File dest,
                                      @Nullable IntConsumer pct) throws IOException {
        List<File> files = new ArrayList<>();
        collect(src, files);
        int total = Math.max(1, files.size());
        int[] done = {0};
        byte[] buf = new byte[64 * 1024];
        for (File f : files) {
            String rel = src.toURI().relativize(f.toURI()).getPath();
            File out = new File(dest, rel);
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
            try (InputStream in = new FileInputStream(f);
                 OutputStream os = new FileOutputStream(out)) {
                int r;
                while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            }
            done[0]++;
            if (pct != null && (done[0] % 8 == 0 || done[0] == total)) {
                pct.accept(done[0] * 100 / total);
            }
        }
    }

    private static void collect(@NonNull File f, @NonNull List<File> out) {
        if (f.isFile()) { out.add(f); return; }
        File[] kids = f.listFiles();
        if (kids == null) return;
        for (File k : kids) collect(k, out);
    }

    /** Zip the whole world folder (top entry = folder name) with progress. */
    private static void zipFolder(@NonNull File src, @NonNull OutputStream os,
                                  @Nullable IntConsumer pct) throws IOException {
        List<File> files = new ArrayList<>();
        collect(src, files);
        int total = Math.max(1, files.size());
        int[] done = {0};
        byte[] buf = new byte[64 * 1024];
        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            String rootName = src.getName() + "/";
            for (File f : files) {
                String rel = src.toURI().relativize(f.toURI()).getPath();
                zos.putNextEntry(new ZipEntry(rootName + rel));
                try (InputStream in = new FileInputStream(f)) {
                    int r;
                    while ((r = in.read(buf)) != -1) zos.write(buf, 0, r);
                }
                zos.closeEntry();
                done[0]++;
                if (pct != null && (done[0] % 8 == 0 || done[0] == total)) {
                    pct.accept(done[0] * 100 / total);
                }
            }
        }
    }

    /**
     * Unzip a world into savesDir under {@code destName}. Handles both
     * folder-wrapped zips (worldFolder/…) and flat zips (level.dat at root).
     * Rejects entries trying to escape the destination (zip-slip safe).
     */
    private static void unzipWorld(@NonNull InputStream in, @NonNull File savesDir,
                                   @NonNull String destName, @Nullable IntConsumer pct)
            throws IOException {
        // First pass: detect the prefix that contains level.dat (root or one folder deep)
        File tmpList = new File(savesDir, destName);
        byte[] raw = readAll(in);
        String prefix = detectWorldPrefix(raw);
        if (prefix == null) throw new IOException("Not a Minecraft world (level.dat not found)");

        File dest = tmpList;
        String canonDest = dest.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(raw))) {
            ZipEntry e;
            java.util.Set<String> seen = new java.util.HashSet<>();
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.startsWith(prefix)) continue;
                String rel = name.substring(prefix.length());
                if (rel.isEmpty()) continue;
                File out = new File(dest, rel);
                if (!out.getCanonicalPath().startsWith(canonDest)) continue; // zip-slip guard
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                // First-file-wins keeps duplicates from corrupting restores.
                String key = out.getCanonicalPath();
                if (seen.contains(key)) continue;
                seen.add(key);
                try (OutputStream os = new FileOutputStream(out)) {
                    int r;
                    while ((r = zis.read(buf)) != -1) os.write(buf, 0, r);
                }
                //noinspection ResultOfMethodCallIgnored
                out.setLastModified(Math.max(0, e.getTime()));
            }
        }
        if (!new File(dest, "level.dat").exists()) {
            deleteRecursive(dest);
            throw new IOException("Corrupt world archive (level.dat missing after unzip)");
        }
        if (pct != null) pct.accept(100);
    }

    /** Detects whether the zip wraps the world inside a top folder or stores it flat. */
    @Nullable
    private static String detectWorldPrefix(byte[] raw) throws IOException {
        boolean rootLevel = false;
        String oneDeep = null;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(raw))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if ("level.dat".equals(n)) rootLevel = true;
                else if (n.endsWith("/level.dat") && n.indexOf('/') == n.lastIndexOf('/')) {
                    oneDeep = n.substring(0, n.length() - "level.dat".length());
                }
                if (rootLevel || oneDeep != null) break;
            }
        }
        if (rootLevel) return "";
        return oneDeep;
    }

    private static byte[] readAll(@NonNull InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static void deleteRecursive(@NonNull File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    @NonNull
    private static File uniqueSibling(@NonNull File parent, @NonNull String base) {
        File f = new File(parent, base);
        int i = 2;
        while (f.exists()) f = new File(parent, base + " " + (i++));
        return f;
    }

    @Nullable
    private static String queryDisplayName(@NonNull ContentResolver cr, @NonNull Uri uri) {
        try (android.database.Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Thread plumbing ──

    private interface Job { void run() throws Exception; }

    private static void run(@NonNull OpCallback cb, @NonNull Job job) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                job.run();
            } catch (Throwable t) {
                post(() -> cb.onDone(false, t.getMessage() != null ? t.getMessage() : "Operation failed"));
            }
        });
    }

    private static void progress(@NonNull OpCallback cb, int pct, @Nullable String msg) {
        post(() -> cb.onProgress(pct, msg));
    }

    private static void done(@NonNull OpCallback cb, boolean ok, @NonNull String msg) {
        post(() -> cb.onDone(ok, msg));
    }

    private static void post(@NonNull Runnable r) {
        MAIN.post(r);
    }
}
