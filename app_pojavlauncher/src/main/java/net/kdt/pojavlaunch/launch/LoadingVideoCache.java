package net.kdt.pojavlaunch.launch;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * LoadingVideoCache — throwaway local copy of the remote loading video.
 *
 * Lives in cacheDir/loading_video/ : the OS may evict it at any time, nothing
 * is permanent, and nothing ships inside the APK.
 *
 * Freshness rules:
 *  - same URL plays from cache instantly, zero re-download
 *  - URL changed in config.json → the old copy is ignored and the new video
 *    is fetched in the background
 *  - video replaced at the SAME URL on GitHub → the next launcher start runs
 *    a cheap HEAD probe (ETag / Last-Modified / Content-Length) during the
 *    config refresh and re-downloads only when the remote object changed
 *
 * Every failure is silent: worst case the launcher streams the live URL or
 * shows the Classic Black stage. It can never crash the launch.
 */
public final class LoadingVideoCache {

    private static final String TAG = "LoadingVideoCache";
    private static final String PREFS = "csl_loading_video_cache";
    private static final String KEY_URL = "cached_url";
    private static final String KEY_SIZE = "cached_size";
    private static final String KEY_SAVED_AT = "cached_at_ms";
    private static final String KEY_ETAG = "cached_etag";
    private static final String KEY_LASTMOD = "cached_lastmod";

    private static final String DIR_NAME = "loading_video";
    private static final String FILE_NAME = "cached.mp4";
    /** Temp files are unique per process: launcher (:ui) and game (:game) are separate processes. */
    private static final String TMP_PREFIX = "download-";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int HEAD_TIMEOUT_MS = 5000;
    private static final long MAX_BYTES = 64L * 1024L * 1024L; // 64 MB abuse guard

    /** The URL currently being downloaded (collapses duplicate fetches). */
    private static volatile String sDownloadingUrl;

    private LoadingVideoCache() {}

    // ─────────────────────── read API (instant, UI-safe) ───────────────────────

    /** A validated cached file for EXACTLY this URL, or null. Never blocks. */
    @Nullable
    public static synchronized File getValidCache(@NonNull Context ctx, @NonNull String url) {
        try {
            SharedPreferences p = prefs(ctx);
            String stored = p.getString(KEY_URL, null);
            if (stored == null || !stored.equals(url)) return null;
            File f = cacheFile(ctx);
            if (!f.isFile() || f.length() <= 0) return null;
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    // ─────────────────────── background fetch / sync ───────────────────────

    /** Best-effort background download so the NEXT launch can play instantly. */
    public static void downloadAsync(@NonNull Context ctx, @Nullable String url) {
        if (url == null || url.trim().isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        final String u = url.trim();
        PojavApplication.sExecutorService.execute(() -> downloadBlocking(app, u, false));
    }

    /**
     * Freshness sync — called on every successful config refresh. If a usable
     * copy exists, a cheap HEAD probe re-downloads ONLY when the remote object
     * changed (video replaced on GitHub at the same URL). If nothing usable is
     * cached, the video is downloaded immediately.
     */
    public static void syncAsync(@NonNull Context ctx, @Nullable String url) {
        if (url == null || url.trim().isEmpty()) return;
        final Context app = ctx.getApplicationContext();
        final String u = url.trim();
        PojavApplication.sExecutorService.execute(() -> syncBlocking(app, u));
    }

    /** Drop the cached copy + all metadata (corrupt file, changed URL, …). */
    public static synchronized void invalidate(@NonNull Context ctx) {
        try {
            prefs(ctx).edit().clear().apply();
            File dir = cacheDir(ctx);
            deleteQuietly(new File(dir, FILE_NAME));
            File[] leftovers = dir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    if (f.getName().startsWith(TMP_PREFIX)) deleteQuietly(f);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ─────────────────────── internals ───────────────────────

    private static void syncBlocking(@NonNull Context ctx, @NonNull String url) {
        if (getValidCache(ctx, url) == null) {
            downloadBlocking(ctx, url, false);        // nothing usable → fetch now
            return;
        }
        String[] v = probeRemote(url);                // [etag, lastmod, length]
        if (v == null) return;                        // HEAD failed → retry next launch
        SharedPreferences p = prefs(ctx);
        String oldEtag = p.getString(KEY_ETAG, null);
        String oldLastMod = p.getString(KEY_LASTMOD, null);
        long oldSize = p.getLong(KEY_SIZE, -1L);
        long newLen = parseLongSafe(v[2]);
        boolean fresh =
                (v[0] != null && v[0].equals(oldEtag))
                || (v[1] != null && v[1].equals(oldLastMod))
                || (v[0] == null && v[1] == null && newLen > 0 && newLen == oldSize);
        if (fresh) {
            Log.i(TAG, "cached loading video is fresh");
        } else {
            Log.i(TAG, "remote video changed → refreshing cache");
            downloadBlocking(ctx, url, true);
        }
    }

    private static void downloadBlocking(@NonNull Context ctx, @NonNull String url, boolean force) {
        synchronized (LoadingVideoCache.class) {
            if (url.equals(sDownloadingUrl)) return;          // already in flight
            if (!force && getValidCache(ctx, url) != null) return;
            sDownloadingUrl = url;
        }
        HttpURLConnection conn = null;
        File tmp = null;
        try {
            File dir = cacheDir(ctx);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // Unique per process — launcher and game may download concurrently;
            // unique temp + atomic rename keeps concurrent downloads crash-safe.
            tmp = new File(dir, TMP_PREFIX + android.os.Process.myPid() + ".tmp");
            deleteQuietly(tmp);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CS-Launcher-V3");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return;
            long declared = conn.getContentLength();
            if (declared > MAX_BYTES) return;
            String etag = conn.getHeaderField("ETag");
            String lastMod = conn.getHeaderField("Last-Modified");

            long total = 0L;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) return;            // abuse guard
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            if (total <= 0) return;
            if (!isPlayableVideo(tmp)) {
                Log.w(TAG, "downloaded video failed validation — discarded");
                return;
            }

            synchronized (LoadingVideoCache.class) {
                deleteQuietly(cacheFile(ctx));
                if (!tmp.renameTo(cacheFile(ctx))) return;
                prefs(ctx).edit()
                        .putString(KEY_URL, url)
                        .putLong(KEY_SIZE, total)
                        .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                        .putString(KEY_ETAG, etag)
                        .putString(KEY_LASTMOD, lastMod)
                        .apply();
                Log.i(TAG, "loading video cached: " + total + " bytes");
            }
            tmp = null; // renamed away — do not delete below
        } catch (Throwable t) {
            Log.w(TAG, "video cache download failed (silent)", t);
        } finally {
            if (conn != null) conn.disconnect();
            deleteQuietly(tmp);
            synchronized (LoadingVideoCache.class) {
                if (url.equals(sDownloadingUrl)) sDownloadingUrl = null;
            }
        }
    }

    /** HEAD probe → [ETag, Last-Modified, Content-Length], or null on any failure. */
    @Nullable
    private static String[] probeRemote(@NonNull String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(HEAD_TIMEOUT_MS);
            conn.setReadTimeout(HEAD_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CS-Launcher-V3");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            return new String[]{
                    conn.getHeaderField("ETag"),
                    conn.getHeaderField("Last-Modified"),
                    conn.getHeaderField("Content-Length")};
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Cheap local sanity probe: a decodable MP4 must expose a duration. */
    private static boolean isPlayableVideo(@NonNull File f) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(f.getAbsolutePath());
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return dur != null && parseLongSafe(dur) > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    private static long parseLongSafe(@Nullable String s) {
        if (s == null) return -1L;
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return -1L; }
    }

    @NonNull
    private static File cacheDir(@NonNull Context ctx) {
        return new File(ctx.getApplicationContext().getCacheDir(), DIR_NAME);
    }

    @NonNull
    private static File cacheFile(@NonNull Context ctx) {
        return new File(cacheDir(ctx), FILE_NAME);
    }

    private static void deleteQuietly(@Nullable File f) {
        if (f == null) return;
        try { if (f.exists() && !f.delete()) f.deleteOnExit(); } catch (Throwable ignored) {}
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
