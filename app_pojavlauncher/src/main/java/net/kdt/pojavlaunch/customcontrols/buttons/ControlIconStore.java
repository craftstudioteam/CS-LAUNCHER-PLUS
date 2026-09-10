package net.kdt.pojavlaunch.customcontrols.buttons;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;

import com.caverock.androidsvg.SVG;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.kdt.pojavlaunch.customcontrols.ControlData;

/**
 * ═══════════════════════════════════════════════════════════════════════
 * DYNAMIC BUTTON ASSET STORE — shared by every ControlButton.
 *
 * • Async decode on the shared executor (zero main-thread IO during play).
 * • LRU bitmap cache (1/8 of heap) keyed by source + target box + rect.
 * • PNG: inSampleSize downscale to the button's pixel bounding box (OOM guard).
 * • SVG: vector-rendered straight to the target box via AndroidSVG.
 * • MINECRAFT_ATLAS: sprites cropped from vanilla GUI sheets that are
 *   extracted once from an installed client jar (icons/widgets/bars.png),
 *   scaled with nearest-neighbour so pixels stay crisp.
 * • Legacy data-URI icons (customIcon) decode through the same cache.
 * ═══════════════════════════════════════════════════════════════════════
 */
public final class ControlIconStore {
    private ControlIconStore() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> PENDING = new HashSet<>();

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** Where extracted vanilla GUI sheets are kept. */
    private static File atlasCacheDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "mc_gui_atlas");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /* ── Public API ──────────────────────────────────────────────────── */

    /**
     * Returns the icon bitmap for this button if it is already cached,
     * otherwise kicks off an async decode and returns null; onReady runs on
     * the main thread once the bitmap has landed in the cache.
     */
    public static Bitmap getIcon(Context ctx, ControlData props, int targetW, int targetH, Runnable onReady) {
        if (props == null || targetW <= 0 || targetH <= 0) return null;
        final String key = cacheKey(props, targetW, targetH);
        if (key == null) return null;
        Bitmap cached = CACHE.get(key);
        if (cached != null) return cached;

        synchronized (PENDING) {
            if (PENDING.contains(key)) return null;
            PENDING.add(key);
        }
        final Context app = ctx.getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            Bitmap decoded = null;
            try {
                decoded = decode(app, props, targetW, targetH);
            } catch (Throwable ignored) {}
            if (decoded != null) CACHE.put(key, decoded);
            synchronized (PENDING) { PENDING.remove(key); }
            if (decoded != null && onReady != null) MAIN.post(onReady);
        });
        return null;
    }

    /** True when the external icon path is an animated GIF. */
    public static boolean isGif(ControlData props) {
        return props != null && props.iconType == ControlData.ICON_EXTERNAL_FILE
                && props.iconPath != null
                && props.iconPath.toLowerCase().endsWith(".gif");
    }

    /* ── Cache keys ──────────────────────────────────────────────────── */

    private static String cacheKey(ControlData props, int w, int h) {
        // Bucket the target box to 32px steps so tiny resizes reuse the cache.
        int bw = Math.max(32, (w / 32) * 32), bh = Math.max(32, (h / 32) * 32);
        if (props.iconType == ControlData.ICON_EXTERNAL_FILE && props.iconPath != null)
            return "file|" + props.iconPath + "|" + bw + "x" + bh;
        if (props.iconType == ControlData.ICON_MINECRAFT_ATLAS
                && props.atlasSource != null && props.atlasRect != null && props.atlasRect.length == 4)
            return "atlas|" + props.atlasSource + "|" + props.atlasRect[0] + "," + props.atlasRect[1]
                    + "," + props.atlasRect[2] + "," + props.atlasRect[3] + "|" + bw + "x" + bh;
        if (props.customIcon != null && props.customIcon.startsWith("data:image"))
            return "datauri|" + props.customIcon.hashCode() + "|" + bw + "x" + bh;
        return null;
    }

    /* ── Decoders ────────────────────────────────────────────────────── */

    private static Bitmap decode(Context ctx, ControlData props, int targetW, int targetH) throws Exception {
        if (props.iconType == ControlData.ICON_EXTERNAL_FILE && props.iconPath != null) {
            String lower = props.iconPath.toLowerCase();
            if (lower.endsWith(".svg")) return decodeSvg(props.iconPath, targetW, targetH);
            if (lower.endsWith(".gif")) return decodeGifFirstFrame(props.iconPath, targetW, targetH);
            return decodeRaster(props.iconPath, targetW, targetH);
        }
        if (props.iconType == ControlData.ICON_MINECRAFT_ATLAS) {
            return decodeAtlasSprite(ctx, props.atlasSource, props.atlasRect, targetW, targetH);
        }
        if (props.customIcon != null && props.customIcon.startsWith("data:image")) {
            int comma = props.customIcon.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(props.customIcon.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        return null;
    }

    /** Raster (.png/.webp/.jpg) with inSampleSize + final fit — the OOM guard. */
    private static Bitmap decodeRaster(String path, int targetW, int targetH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= targetW && opts.outHeight / (sample * 2) >= targetH)
            sample *= 2;
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(path, real);
        return bmp == null ? null : fitInto(bmp, targetW, targetH, true);
    }

    /** First GIF frame for static previews (live playback uses GifDrawable). */
    private static Bitmap decodeGifFirstFrame(String path, int targetW, int targetH) {
        Bitmap bmp = BitmapFactory.decodeFile(path);
        return bmp == null ? null : fitInto(bmp, targetW, targetH, true);
    }

    /** Vector render at exactly the needed pixel size — always crisp. */
    private static Bitmap decodeSvg(String path, int targetW, int targetH) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            SVG svg = SVG.getFromInputStream(in);
            float docW = svg.getDocumentWidth(), docH = svg.getDocumentHeight();
            if (docW <= 0 || docH <= 0) {
                android.graphics.RectF box = svg.getDocumentViewBox();
                if (box != null && box.width() > 0) { docW = box.width(); docH = box.height(); }
                else { docW = targetW; docH = targetH; }
            }
            float scale = Math.min(targetW / docW, targetH / docH);
            int outW = Math.max(1, Math.round(docW * scale));
            int outH = Math.max(1, Math.round(docH * scale));
            Bitmap bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            svg.setDocumentWidth(outW);
            svg.setDocumentHeight(outH);
            svg.renderToCanvas(canvas);
            return bmp;
        }
    }

    /** Crop [u,v,w,h] out of a vanilla sheet, nearest-neighbour upscale. */
    private static Bitmap decodeAtlasSprite(Context ctx, String source, int[] rect, int targetW, int targetH) {
        if (source == null || rect == null || rect.length != 4) return null;
        File sheet = resolveAtlas(ctx, source);
        if (sheet == null) return null;
        Bitmap atlas = BitmapFactory.decodeFile(sheet.getAbsolutePath());
        if (atlas == null) return null;
        int u = clamp(rect[0], 0, atlas.getWidth() - 1);
        int v = clamp(rect[1], 0, atlas.getHeight() - 1);
        int w = clamp(rect[2], 1, atlas.getWidth() - u);
        int h = clamp(rect[3], 1, atlas.getHeight() - v);
        Bitmap sprite = Bitmap.createBitmap(atlas, u, v, w, h);
        // Nearest-neighbour (filter=false) keeps the Minecraft pixel look.
        return fitInto(sprite, targetW, targetH, false);
    }

    /* ── Vanilla atlas extraction ────────────────────────────────────── */

    /**
     * Returns the extracted vanilla GUI sheet (icons.png / widgets.png /
     * bars.png), pulling it out of any installed client jar on first use.
     * Null when no installed version contains the sheet.
     */
    public static File resolveAtlas(Context ctx, String source) {
        File out = new File(atlasCacheDir(ctx), source);
        if (out.isFile() && out.length() > 0) return out;
        try {
            File versionsDir = new File(Tools.DIR_HOME_VERSION);
            File[] versions = versionsDir.listFiles();
            if (versions == null) return null;
            for (File dir : versions) {
                if (!dir.isDirectory()) continue;
                File jar = new File(dir, dir.getName() + ".jar");
                if (!jar.isFile()) continue;
                try (ZipFile zip = new ZipFile(jar)) {
                    ZipEntry entry = zip.getEntry("assets/minecraft/textures/gui/" + source);
                    if (entry == null) continue;
                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    return out;
                } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ── Helpers ─────────────────────────────────────────────────────── */

    private static Bitmap fitInto(Bitmap src, int maxW, int maxH, boolean filter) {
        if (src.getWidth() <= maxW && src.getHeight() <= maxH
                && (src.getWidth() >= maxW / 2 || src.getHeight() >= maxH / 2)) return src;
        float scale = Math.min(maxW / (float) src.getWidth(), maxH / (float) src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, filter);
        if (scaled != src && !src.isRecycled() && src != scaled) src.recycle();
        return scaled;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
