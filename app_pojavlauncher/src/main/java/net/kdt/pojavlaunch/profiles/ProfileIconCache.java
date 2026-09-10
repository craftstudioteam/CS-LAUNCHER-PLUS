package net.kdt.pojavlaunch.profiles;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import java.util.concurrent.ConcurrentHashMap;

public class ProfileIconCache {
    // Data header format: data:<mime>;<encoding>,<data>
    private static final String DATA_HEADER = "data:";
    private static final String FALLBACK_ICON_NAME = "default";

    /**
     * Sentinel stored in {@code profile.background} to request the built-in
     * "Default Background" — a plain dark-grey solid colour (no image/GIF).
     */
    public static final String DEFAULT_SOLID_BACKGROUND = "cs_default_solid";
    /** The dark-grey shade the Default Background option paints. */
    public static final int DEFAULT_SOLID_BACKGROUND_COLOR = 0xFF1B1B20;

    // Bounded LRU cache for decoded data icons — prevents unbounded heap growth.
    // 32 entries is generous: most users have <10 profiles.
    private static final int MAX_ICON_CACHE_SIZE = 32;
    // NOTE: We intentionally do NOT recycle BitmapDrawables on eviction.
    // A recycled bitmap will crash any ImageView that is still drawing it
    // (e.g. a RecyclerView item that hasn't been recycled yet).
    // Android's GC reclaims native memory once all Java references are dropped.
    private static final LruCache<String, Drawable> sIconCache = new LruCache<String, Drawable>(MAX_ICON_CACHE_SIZE) {
        @Override
        protected void entryRemoved(boolean evicted, String key, Drawable oldValue, Drawable newValue) {
            // No-op: bitmap must remain usable by any ImageView still referencing it.
        }
    };
    // Static icons (built-in resource drawables) are lightweight references; ConcurrentHashMap avoids
    // ConcurrentModificationException when accessed from multiple threads.
    private static final ConcurrentHashMap<String, Drawable> sStaticIconCache = new ConcurrentHashMap<>();

    /**
     * Fetch an icon from the cache, or load it if it's not cached.
     * @param resources the Resources object, used for creating drawables
     * @param key the profile key
     * @param icon the profile icon data (stored in the icon field of MinecraftProfile)
     * @return an icon drawable
     */
    public static @NonNull Drawable fetchIcon(Resources resources, @Nullable String key, @Nullable String icon) {
        if (key == null) {
            Log.w("ProfileIconCache", "fetchIcon called with null key, returning fallback");
            return fetchFallbackIcon(resources);
        }
        Drawable cachedIcon = sIconCache.get(key);
        if(cachedIcon != null) return cachedIcon;
        if(icon != null && icon.startsWith(DATA_HEADER)) return fetchDataIcon(resources, key, icon);
        else return fetchStaticIcon(resources, key, icon);
    }

    /**
     * Drop an icon from the icon cache. When dropped, its Drawable will be re-read from the
     * data string (or re-fetched from the static cache) on the next fetchIcon call.
     * The underlying Bitmap is NOT recycled here — it may still be referenced by an ImageView
     * (e.g. a RecyclerView item that hasn't been recycled yet).
     * @param key the profile key
     */
    public static void dropIcon(@Nullable String key) {
        if (key == null) return;
        sIconCache.remove(key);
        sIconCache.remove(key + "_bg");
        sIconCache.remove(key + "_bg_remote");
    }

    /**
     * Fetch a profile background banner drawable from a data URI (separate from the profile icon).
     * Returns null when no background is set, so callers can hide the banner view.
     * @param resources the Resources object
     * @param key the profile key (used for caching)
     * @param background the background data URI stored in MinecraftProfile.background
     * @return a decoded drawable, or null if none is set / decode failed
     */
    public static @Nullable Drawable fetchBackground(Resources resources, @Nullable String key, @Nullable String background) {
        if (background == null) return null;

        // ── Built-in "Default Background": a plain dark-grey solid colour ──
        if (DEFAULT_SOLID_BACKGROUND.equals(background)) {
            return new android.graphics.drawable.ColorDrawable(DEFAULT_SOLID_BACKGROUND_COLOR);
        }

        String cacheKey = (key != null ? key : "bg") + "_bg";

        // One-time compatibility alias for CS Client profiles created by the
        // previous build. Render them with the original animated background
        // without changing the profile-card layout or dimensions.
        if ("cs_client_artwork".equals(background)
                || ProfileGifSupport.LEGACY_REMOTE_DEFAULT_BG_URL.equals(background)) {
            background = ProfileGifSupport.DEFAULT_PROFILE_BG_URL;
        }

        // ── Bundled default (Phase 8): decoded once, shared by every card ──
        if (ProfileGifSupport.isBundledKey(background)) {
            Drawable bundled = sStaticIconCache.get(background);
            if (bundled == null) {
                bundled = ProfileGifSupport.loadBundled(resources, background);
                if (bundled != null) {
                    Drawable existing = sStaticIconCache.putIfAbsent(background, bundled);
                    if (existing != null) bundled = existing;
                }
            }
            return bundled;
        }

        // ── Remote URL backgrounds (e.g. the default animated GIF) ──
        if (ProfileGifSupport.isRemoteUrl(background)) {
            String remoteKey = cacheKey + "_remote";
            Drawable cachedRemote = sIconCache.get(remoteKey);
            if (cachedRemote != null) return cachedRemote;
            Drawable remote = ProfileGifSupport.loadRemoteSync(resources, background);
            if (remote != null) sIconCache.put(remoteKey, remote);
            return remote; // null while the async download is still in flight
        }

        if (!background.startsWith(DATA_HEADER)) return null;
        Drawable cached = sIconCache.get(cacheKey);
        if (cached != null) return cached;

        // ── Animated GIF data URIs keep their frames (no flattening) ──
        if (ProfileGifSupport.isGifDataUri(background)) {
            byte[] gifData = extractIconData(background);
            if (gifData == null) return null;
            Drawable gif = ProfileGifSupport.buildGifDrawable(gifData);
            if (gif == null) gif = ProfileGifSupport.buildStaticFallback(resources, gifData);
            if (gif != null) sIconCache.put(cacheKey, gif);
            return gif;
        }

        byte[] data = extractIconData(background);
        if (data == null) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        // Banner images span the full card width on large phones — decode at a
        // high resolution and in full colour so they stay crisp (no more blur/
        // banding from the old 512px RGB_565 path).
        opts.inSampleSize = computeInSampleSize(opts, 1280, 1280);
        opts.inJustDecodeBounds = false;
        opts.inMutable = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bitmap == null) return null;
        Drawable drawable = new BitmapDrawable(resources, bitmap);
        sIconCache.put(cacheKey, drawable);
        return drawable;
    }

    private static Drawable fetchDataIcon(Resources resources, @Nullable String key, @NonNull String icon) {
        Drawable dataIcon = readDataIcon(resources, icon);
        if(dataIcon == null) {
            Log.w("ProfileIconCache", "fetchDataIcon decode failed for " + key + " (icon length=" + icon.length() + ")");
            dataIcon = fetchFallbackIcon(resources);
        }
        if (key != null) sIconCache.put(key, dataIcon);
        return dataIcon;
    }

    private static Drawable fetchStaticIcon(Resources resources, @Nullable String key, @Nullable String icon) {
        if (icon == null) {
            Drawable fallback = fetchFallbackIcon(resources);
            if (key != null) sIconCache.put(key, fallback);
            return fallback;
        }

        Drawable staticIcon = sStaticIconCache.get(icon);
        if(staticIcon == null) {
            staticIcon = getStaticIcon(resources, icon);
            if(staticIcon == null) staticIcon = fetchFallbackIcon(resources);
            Drawable existing = sStaticIconCache.putIfAbsent(icon, staticIcon);
            if (existing != null) staticIcon = existing; // Another thread won the race, reuse.
        }
        if (key != null) sIconCache.put(key, staticIcon);
        return staticIcon;
    }

    private static @NonNull Drawable fetchFallbackIcon(Resources resources) {
        Drawable fallbackIcon = sStaticIconCache.get(FALLBACK_ICON_NAME);
        if(fallbackIcon == null) {
            fallbackIcon = androidx.core.util.ObjectsCompat.requireNonNull(getStaticIcon(resources, FALLBACK_ICON_NAME));
            Drawable existing = sStaticIconCache.putIfAbsent(FALLBACK_ICON_NAME, fallbackIcon);
            if (existing != null) fallbackIcon = existing;
        }
        return fallbackIcon;
    }

    private static Drawable getStaticIcon(Resources resources, @NonNull String icon) {
        int staticIconResource = getStaticIconResource(icon);
        if(staticIconResource == -1) return null;
        return ResourcesCompat.getDrawable(resources, staticIconResource, null);
    }

    private static int getStaticIconResource(String icon) {
        switch (icon) {
            case "default": return R.drawable.ic_vanilla_grass; // vanilla / normal instance = grass block
            case "fabric": return R.drawable.ic_fabric;
            case "quilt": return R.drawable.ic_quilt;
            default: return -1;
        }
    }

    private static Drawable readDataIcon(Resources resources, String icon) {
        byte[] iconData = extractIconData(icon);
        if(iconData == null) {
            Log.w("ProfileIconCache", "readDataIcon: extractIconData returned null (icon prefix=" +
                    (icon.length() > 50 ? icon.substring(0, 50) : icon) + ")");
            return null;
        }

        // Decode bounds first to compute inSampleSize for memory-efficient loading
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(iconData, 0, iconData.length, opts);

        int targetSize = 128; // Profile icons displayed at ~48dp; 128px is more than sufficient
        opts.inSampleSize = computeInSampleSize(opts, targetSize, targetSize);
        opts.inJustDecodeBounds = false;
        opts.inMutable = false; // Immutable bitmaps are more memory-efficient on some platforms
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888; // Preserve round/transparent icon corners

        Bitmap iconBitmap = BitmapFactory.decodeByteArray(iconData, 0, iconData.length, opts);
        if(iconBitmap == null) {
            Log.w("ProfileIconCache", "readDataIcon: BitmapFactory.decodeByteArray returned null (bytes=" + iconData.length + ", inSampleSize=" + opts.inSampleSize + ")");
            return null;
        }
        return new BitmapDrawable(resources, iconBitmap);
    }

    /** Detect transparent source corners, the normal signature of a round PNG/WebP icon. */
    public static boolean hasTransparentCorners(@Nullable Drawable drawable) {
        if (!(drawable instanceof BitmapDrawable)) return false;
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        if (bitmap == null || bitmap.getWidth() < 2 || bitmap.getHeight() < 2) return false;
        int w = bitmap.getWidth() - 1, h = bitmap.getHeight() - 1;
        int transparent = 0;
        if (Color.alpha(bitmap.getPixel(0, 0)) < 40) transparent++;
        if (Color.alpha(bitmap.getPixel(w, 0)) < 40) transparent++;
        if (Color.alpha(bitmap.getPixel(0, h)) < 40) transparent++;
        if (Color.alpha(bitmap.getPixel(w, h)) < 40) transparent++;
        return transparent >= 3;
    }

    /**
     * Compute a power-of-two inSampleSize that scales the image down to at most target dimensions.
     */
    private static int computeInSampleSize(BitmapFactory.Options options, int targetWidth, int targetHeight) {
        int rawWidth = options.outWidth;
        int rawHeight = options.outHeight;
        int inSampleSize = 1;
        if (rawHeight > targetHeight || rawWidth > targetWidth) {
            int halfHeight = rawHeight / 2;
            int halfWidth = rawWidth / 2;
            while ((halfHeight / inSampleSize) >= targetHeight
                    && (halfWidth / inSampleSize) >= targetWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static byte[] extractIconData(String inputString) {
        int firstSemicolon = inputString.indexOf(';');
        int commaAfterSemicolon = inputString.indexOf(',');
        if(firstSemicolon == -1 || commaAfterSemicolon == -1) return null;
        String dataEncoding = inputString.substring(firstSemicolon+1, commaAfterSemicolon);
        if(!dataEncoding.equals("base64")) return null;
        return Base64.decode(inputString.substring(commaAfterSemicolon+1), 0);
    }
}
