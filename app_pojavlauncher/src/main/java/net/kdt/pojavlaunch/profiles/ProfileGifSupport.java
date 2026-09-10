package net.kdt.pojavlaunch.profiles;

import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import pl.droidsonroids.gif.GifDrawable;
import pl.droidsonroids.gif.GifDrawableBuilder;

/**
 * Animated GIF support for profile backgrounds.
 *
 * - Detects GIF payloads (data URIs and remote URLs)
 * - Builds memory-efficient {@link GifDrawable}s (subsampled when oversized)
 * - Disk-caches remote GIFs under the app cache dir and downloads them
 *   asynchronously, notifying UI listeners when a fresh asset lands
 */
public final class ProfileGifSupport {

    private static final String TAG = "ProfileGifSupport";

    /**
     * Official default animated profile background for CS Launcher Plus.
     * Phase 8: bundled in the APK ({@code res/raw/cs_default_profile_bg.gif}) so it
     * plays instantly, offline, on a fresh install — no download, no blank card.
     * The value is a resource key understood by {@link #isBundledKey(String)}.
     */
    public static final String DEFAULT_PROFILE_BG_URL = "res:cs_default_profile_bg";

    /** The previous remote default; profiles saved by older builds still carry it. */
    public static final String LEGACY_REMOTE_DEFAULT_BG_URL =
            "https://i.ibb.co/k60k0LcH/356021c99fded9d442b02d0b48891338.gif";

    private static final String BUNDLED_PREFIX = "res:";

    private static final String GIF_DATA_PREFIX = "data:image/gif";
    private static final String CACHE_DIR_NAME = "profile_bg_cache";
    // Keep per-frame decode cheap so playback stays smooth on mobile GPUs — a
    // banner never needs more than ~512px, and lighter frames = no mid-loop
    // stutter / lag while the profile card is on screen.
    private static final int MAX_GIF_DIMENSION = 512; // subsample beyond this for smooth playback

    public interface OnAssetReadyListener {
        void onAssetReady(@NonNull String assetKey);
    }

    /** Weak refs so adapters/fragments never leak. */
    private static final Set<WeakReference<OnAssetReadyListener>> sListeners = ConcurrentHashMap.newKeySet();

    /** In-flight download guard — never fetch the same URL twice concurrently. */
    private static final Set<String> sActiveDownloads = ConcurrentHashMap.newKeySet();

    private ProfileGifSupport() { /* no instances */ }

    // ── Detection ────────────────────────────────────────────────────────────

    public static boolean isGifDataUri(@Nullable String value) {
        return value != null && value.startsWith(GIF_DATA_PREFIX);
    }

    public static boolean isRemoteUrl(@Nullable String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    /** {@code res:<raw_name>} — a GIF shipped inside the APK. */
    public static boolean isBundledKey(@Nullable String value) {
        return value != null && value.startsWith(BUNDLED_PREFIX);
    }

    /** True for every spelling of "the launcher default" (bundled, legacy remote, CS alias). */
    public static boolean isDefaultBackground(@Nullable String value) {
        return value == null
                || DEFAULT_PROFILE_BG_URL.equals(value)
                || LEGACY_REMOTE_DEFAULT_BG_URL.equals(value)
                || "cs_client_artwork".equals(value);
    }

    /**
     * Decode a bundled raw GIF into a looping drawable. Synchronous (it is a
     * local resource read); falls back to a static first frame if the GIF
     * decoder rejects the payload.
     */
    @Nullable
    public static Drawable loadBundled(@NonNull Resources res, @NonNull String key) {
        if (!isBundledKey(key)) return null;
        String name = key.substring(BUNDLED_PREFIX.length());
        try {
            int id = "cs_default_profile_bg".equals(name)
                    ? net.kdt.pojavlaunch.R.raw.cs_default_profile_bg : 0;
            if (id == 0) id = res.getIdentifier(name, "raw", net.kdt.pojavlaunch.BuildConfig.APPLICATION_ID);
            if (id == 0) id = res.getIdentifier(name, "raw", "net.kdt.pojavlaunch");
            if (id == 0) return null;
            byte[] data;
            try (java.io.InputStream in = res.openRawResource(id);
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.max(64 * 1024, in.available()))) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                data = bos.toByteArray();
            }
            Drawable gif = buildGifDrawable(data);
            if (gif == null) gif = buildStaticFallback(res, data);
            return gif;
        } catch (Throwable t) {
            Log.w(TAG, "bundled background failed: " + key, t);
            return null;
        }
    }

    public static boolean looksLikeGif(@Nullable byte[] data) {
        return data != null && data.length > 5
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F';
    }

    // ── Drawable building ────────────────────────────────────────────────────

    /**
     * Build a looping {@link GifDrawable} from raw GIF bytes, subsampling when the
     * source exceeds {@link #MAX_GIF_DIMENSION} so animation stays smooth + light.
     */
    @Nullable
    public static GifDrawable buildGifDrawable(@NonNull byte[] data) {
        if (!looksLikeGif(data)) return null;
        try {
            GifDrawableBuilder builder = new GifDrawableBuilder().from(data);
            builder.sampleSize(computeGifSampleSize(data));
            GifDrawable drawable = builder.build();
            drawable.setLoopCount(0); // loop forever while the profile is on screen
            return drawable;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "GifDrawable build failed (" + data.length + " bytes)", e);
            return null;
        }
    }

    /** GifDrawable sampling mirrors BitmapFactory's power-of-two strategy. */
    private static int computeGifSampleSize(@NonNull byte[] data) {
        try {
            // GIF header: bytes 6-7 width LE, 8-9 height LE
            if (data.length > 10 && looksLikeGif(data)) {
                int width = (data[7] & 0xFF) << 8 | (data[6] & 0xFF);
                int height = (data[9] & 0xFF) << 8 | (data[8] & 0xFF);
                int sample = 1;
                while (width / (sample * 2) >= MAX_GIF_DIMENSION
                        || height / (sample * 2) >= MAX_GIF_DIMENSION) {
                    if (sample >= 8) break;
                    sample *= 2;
                }
                return Math.max(1, sample);
            }
        } catch (Throwable ignored) { }
        return 1;
    }

    /** Static fallback for corrupt/non-animatable payloads. */
    @Nullable
    public static Drawable buildStaticFallback(@NonNull Resources res, @NonNull byte[] data) {
        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
        return bmp != null ? new BitmapDrawable(res, bmp) : null;
    }

    // ── Remote caching ───────────────────────────────────────────────────────

    @Nullable
    public static File cacheFileForUrl(@NonNull String url) {
        try {
            if (Tools.DIR_CACHE == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("bg_");
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", digest[i]));
            File dir = new File(Tools.DIR_CACHE, CACHE_DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) return null;
            return new File(dir, sb.toString() + (url.contains(".gif") ? ".gif" : ".img"));
        } catch (Exception e) {
            Log.w(TAG, "cacheFileForUrl failed", e);
            return null;
        }
    }

    /**
     * Load a remote image from the disk cache. Triggers an async download when absent.
     *
     * @return cached drawable, or null while the download is still in flight
     */
    @Nullable
    public static Drawable loadRemoteSync(@NonNull Resources res, @NonNull String url) {
        File cacheFile = cacheFileForUrl(url);
        if (cacheFile == null) return null;
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                byte[] data = readAllBytes(cacheFile);
                if (looksLikeGif(data)) return buildGifDrawable(data);
                return buildStaticFallback(res, data);
            } catch (Exception e) {
                Log.w(TAG, "cached asset unreadable, refetching: " + url, e);
                //noinspection ResultOfMethodCallIgnored
                cacheFile.delete();
            }
        }
        kickDownload(url);
        return null;
    }

    private static void kickDownload(@NonNull final String url) {
        if (!sActiveDownloads.add(url)) return; // already downloading
        PojavApplication.sExecutorService.execute(() -> {
            HttpURLConnection conn = null;
            try {
                File cacheFile = cacheFileForUrl(url);
                if (cacheFile == null) return;
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "CSLauncherPlus/1.0");
                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new FileOutputStream(cacheFile)) {
                    byte[] buf = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                        total += read;
                        if (total > 15L * 1024 * 1024) { // 15 MB safety cap
                            //noinspection ResultOfMethodCallIgnored
                            cacheFile.delete();
                            return;
                        }
                    }
                }
                notifyAssetReady(url);
            } catch (Exception e) {
                Log.w(TAG, "remote asset download failed: " + url, e);
            } finally {
                if (conn != null) conn.disconnect();
                sActiveDownloads.remove(url);
            }
        });
    }

    // ── Listeners ────────────────────────────────────────────────────────────

    public static void addAssetReadyListener(@NonNull OnAssetReadyListener listener) {
        sListeners.add(new WeakReference<>(listener));
    }

    public static void removeAssetReadyListener(@NonNull OnAssetReadyListener listener) {
        for (Iterator<WeakReference<OnAssetReadyListener>> it = sListeners.iterator(); it.hasNext(); ) {
            OnAssetReadyListener ref = it.next().get();
            if (ref == null || ref == listener) it.remove();
        }
    }

    private static void notifyAssetReady(@NonNull final String assetKey) {
        Tools.runOnUiThread(() -> {
            for (WeakReference<OnAssetReadyListener> ref : sListeners) {
                OnAssetReadyListener listener = ref.get();
                if (listener != null) listener.onAssetReady(assetKey);
            }
        });
    }

    /** Stop any GifDrawable bound to recycled views (frees the render thread). */
    public static void stopDrawable(@Nullable Drawable drawable) {
        if (drawable instanceof GifDrawable) {
            GifDrawable gif = (GifDrawable) drawable;
            if (gif.isRunning()) gif.stop();
        }
    }

    /**
     * Resume a cached GifDrawable that was paused by {@link #stopDrawable} when its
     * view got recycled. Orchestrates Req-4 behaviour:
     * - keeps playing after a profile refresh (cache-hit instance is restarted),
     * - never restarts unexpectedly (start() is only issued when NOT running),
     * - never starts a recycled drawable.
     * Call right after binding a drawable to an ImageView.
     */
    public static void resumeDrawable(@Nullable Drawable drawable) {
        if (drawable instanceof GifDrawable) {
            GifDrawable gif = (GifDrawable) drawable;
            if (!gif.isRecycled() && !gif.isRunning()) gif.start();
        }
    }

    private static byte[] readAllBytes(@NonNull File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (InputStream in = new java.io.FileInputStream(file)) {
            int offset = 0, read;
            while (offset < data.length && (read = in.read(data, offset, data.length - offset)) != -1) {
                offset += read;
            }
        }
        return data;
    }
}
