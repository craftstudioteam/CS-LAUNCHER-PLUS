package net.kdt.pojavlaunch.shortcuts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads and caches player skin head icons from Mojang's API.
 *
 * Flow:
 * 1. Resolve username → UUID via {@code api.mojang.com}
 * 2. Get profile texture via {@code sessionserver.mojang.com}
 * 3. Decode Base64 texture data, extract skin URL
 * 4. Download skin PNG, crop head portion (8×8 area, top-left)
 * 5. Cache the head bitmap to internal storage
 */
public class ShortcutSkinHeadHelper {

    private static final String TAG = "ShortcutSkinHeadHelper";
    private static final String CACHE_DIR = "skin_heads";
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    /** Connect and read timeout for skin texture downloads. */
    private static final int NETWORK_TIMEOUT_MS = 10_000;

    private static final String MOJANG_API_PROFILE =
            "https://api.mojang.com/users/profiles/minecraft/";
    private static final String SESSION_SERVER_PROFILE =
            "https://sessionserver.mojang.com/session/minecraft/profile/";

    /**
     * Get the skin head bitmap for a Minecraft username.
     * Checks local cache first, downloads if needed.
     *
     * @param context  Application context
     * @param username Minecraft username (case-insensitive)
     * @return The 64×64 head bitmap, or null on failure
     */
    @Nullable
    public static Bitmap getSkinHead(@NonNull Context context,
                                      @NonNull String username) {
        // Check cache first
        Bitmap cached = loadCached(context, username);
        if (cached != null) return cached;

        // Download skin head
        try {
            // Step 1: Resolve username → UUID
            String uuid = resolveUuid(username);
            if (uuid == null) {
                Log.w(TAG, "Failed to resolve UUID for: " + username);
                return null;
            }

            // Step 2: Get texture data from session server
            String skinUrl = getSkinUrl(uuid);
            if (skinUrl == null) {
                Log.w(TAG, "Failed to get skin URL for: " + uuid);
                return null;
            }

            // Step 3: Download skin and crop head
            Bitmap head = downloadAndCropHead(skinUrl);
            if (head != null) {
                cacheSkinHead(context, username, head);
            }
            return head;

        } catch (Exception e) {
            Log.e(TAG, "Failed to get skin head for: " + username, e);
            return null;
        }
    }

    /**
     * Full player skin (64x64/64x32) for a username — used by the home 3D
     * player so PREMIUM accounts show their real skin even before the user
     * ever opens Skin Management. Downloads via the same Mojang pipeline
     * (username -> UUID -> textures -> PNG) and caches to {@code cacheFile}.
     *
     * @return the cache file once it holds a valid skin, or null on failure
     */
    @Nullable
    public static File getFullSkinFile(@NonNull String username, @NonNull File cacheFile) {
        return getFullSkinFile(null, username, cacheFile);
    }

    /**
     * Premium-skin fetch that prefers the account's own profile UUID — no
     * username lookup (that API is heavily rate-limited and was the usual
     * reason the home player fell back to Steve).
     */
    @Nullable
    public static File getFullSkinFile(@Nullable String profileId, @NonNull String username,
                                       @NonNull File cacheFile) {
        if (cacheFile.isFile() && cacheFile.length() > 0) return cacheFile;
        try {
            String uuid = (profileId != null && profileId.length() == 36
                    && !profileId.startsWith("00000000-0000"))
                    ? profileId : resolveUuid(username);
            if (uuid == null) { Log.w(TAG, "no uuid for skin: " + username); return null; }
            String skinUrl = getSkinUrl(uuid);
            if (skinUrl == null) return null;
            File tmp = new File(cacheFile.getAbsolutePath() + ".tmp");
            downloadTexture(skinUrl, tmp);
            // Only a real skin sheet may become the cached file (never an error page).
            if (tmp.isFile() && tmp.length() > 0 && isSkinSheet(tmp)) {
                cacheFile.delete();
                if (tmp.renameTo(cacheFile)) return cacheFile;
                org.apache.commons.io.FileUtils.copyFile(tmp, cacheFile);
                tmp.delete();
                return cacheFile;
            }
            tmp.delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch full skin for: " + username, e);
        }
        return null;
    }

    /**
     * Delete cached skin head for a username.
     */
    public static void clearCache(@NonNull Context context,
                                   @NonNull String username) {
        File cacheFile = getCacheFile(context, username);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
    }

    /** Texture download with an explicit UA + timeouts (textures.minecraft.net rejects blank agents). */
    private static void downloadTexture(@NonNull String url, @NonNull File out) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "CS-Launcher-Plus");
        conn.setConnectTimeout(NETWORK_TIMEOUT_MS);
        conn.setReadTimeout(NETWORK_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for skin texture");
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** True when the file decodes to a Minecraft skin sheet (64×32 or 64×64+). */
    private static boolean isSkinSheet(@NonNull File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            return o.outWidth >= 64 && o.outHeight >= 32;
        } catch (Throwable t) {
            return false;
        }
    }

    // ─── UUID Resolution ──────────────────────────────────────────────

    @Nullable
    private static String resolveUuid(@NonNull String username)
            throws IOException {
        String url = MOJANG_API_PROFILE + username.toLowerCase();
        // Must be an HTTP fetch. Tools.read(String) opens a FileInputStream, so
        // the previous call here always threw FileNotFoundException and the skin
        // head option could never succeed.
        String json = DownloadUtils.downloadString(url);

        if (json == null || json.isEmpty()) return null;

        // Parse UUID from the minimal JSON response: {"name":"...","id":"..."}
        int idIdx = json.indexOf("\"id\":\"");
        if (idIdx == -1) return null;
        idIdx += 6;
        int endIdx = json.indexOf("\"", idIdx);
        if (endIdx == -1) return null;

        String rawUuid = json.substring(idIdx, endIdx);

        // Format as standard UUID: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        return rawUuid.replaceAll(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5");
    }

    // ─── Skin URL Extraction ──────────────────────────────────────────

    @Nullable
    private static String getSkinUrl(@NonNull String uuid)
            throws IOException {
        String url = SESSION_SERVER_PROFILE + uuid;
        // Same fix as resolveUuid: this is a network call, not a file read.
        String json = DownloadUtils.downloadString(url);

        if (json == null || json.isEmpty()) return null;

        // Extract the "value" field from the JSON response
        int valueIdx = json.indexOf("\"value\":\"");
        if (valueIdx == -1) return null;
        valueIdx += 9;
        int endIdx = json.indexOf("\"", valueIdx);
        if (endIdx == -1) return null;

        String base64Value = json.substring(valueIdx, endIdx);

        // Decode Base64
        byte[] decoded;
        try {
            decoded = Base64.decode(base64Value, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Failed to decode texture value", e);
            return null;
        }

        String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);

        // Extract skin URL from the decoded JSON
        // Looking for: "url":"http://textures.minecraft.net/texture/..."
        int urlIdx = decodedStr.indexOf("\"url\":\"");
        if (urlIdx == -1) return null;
        urlIdx += 7;
        int urlEnd = decodedStr.indexOf("\"", urlIdx);
        if (urlEnd == -1) return null;

        String skinUrl = decodedStr.substring(urlIdx, urlEnd);
        // Phase 9 root cause of "premium skin never shows on home": the session
        // server hands out an http:// texture URL, and this app (targetSdk 34,
        // no cleartext flag) refuses plain http — the download threw, the
        // resolver fell back to Steve. textures.minecraft.net serves https.
        if (skinUrl.startsWith("http://")) skinUrl = "https://" + skinUrl.substring("http://".length());
        return skinUrl;
    }

    // ─── Skin Download + Head Crop ─────────────────────────────────────

    @Nullable
    private static Bitmap downloadAndCropHead(@NonNull String skinUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(skinUrl).openConnection();
            conn.setRequestProperty("User-Agent", Tools.APP_NAME);
            // Without timeouts a stalled texture server keeps the worker thread
            // blocked indefinitely and the picker appears frozen.
            conn.setConnectTimeout(NETWORK_TIMEOUT_MS);
            conn.setReadTimeout(NETWORK_TIMEOUT_MS);

            Bitmap skin = BitmapFactory.decodeStream(conn.getInputStream());
            conn.disconnect();

            if (skin == null) return null;

            // The head is the 8×8 pixel area starting at (8, 8) in a 64×64 skin.
            // We use the helmet/hat layer at (40, 8) for overlay.
            int skinWidth = skin.getWidth();
            int skinHeight = skin.getHeight();

            // For modern 64×64 skins: head is at (8, 8), size 8×8
            // Scale to 64×64 output for crisp shortcut icons
            int headSize; // in skin pixels
            int headX, headY;

            if (skinWidth == 64 && skinHeight == 64) {
                // Modern skin format
                headX = 8;
                headY = 8;
                headSize = 8;
            } else if (skinWidth == 64 && skinHeight == 32) {
                // Old skin format
                headX = 8;
                headY = 8;
                headSize = 8;
            } else {
                // Unknown format — use full image scaled down
                headX = 0;
                headY = 0;
                headSize = Math.min(skinWidth, skinHeight);
            }

            // Crop the head area
            Bitmap head = Bitmap.createBitmap(skin, headX, headY,
                    headSize, headSize);

            // Composite the hat/helmet overlay layer so hats are not lost.
            // On 64x64 skins it lives at (40, 8); 64x32 skins have no overlay.
            if (skinWidth == 64 && skinHeight == 64 && headSize == 8) {
                try {
                    Bitmap overlay = Bitmap.createBitmap(skin, 40, 8, 8, 8);
                    Bitmap merged = head.copy(Bitmap.Config.ARGB_8888, true);
                    new android.graphics.Canvas(merged).drawBitmap(overlay, 0, 0, null);
                    overlay.recycle();
                    head.recycle();
                    head = merged;
                } catch (Exception ignored) {
                    // Malformed skin — keep the base head layer.
                }
            }

            // Nearest-neighbour upscale: bilinear filtering turns 8x8 pixel art
            // into a blurry smear, which looked poor as a launcher icon.
            Bitmap scaled = ShortcutIconRenderer.upscalePixelArt(
                    head, ShortcutIconRenderer.ICON_SIZE);

            if (head != scaled) {
                head.recycle();
            }
            skin.recycle();

            return scaled;

        } catch (Exception e) {
            Log.e(TAG, "Failed to download/crop skin head", e);
            return null;
        }
    }

    // ─── Caching ──────────────────────────────────────────────────────

    @Nullable
    private static Bitmap loadCached(@NonNull Context context,
                                      @NonNull String username) {
        File cacheFile = getCacheFile(context, username);
        if (!cacheFile.exists()) return null;

        // Check TTL
        long age = System.currentTimeMillis() - cacheFile.lastModified();
        if (age > CACHE_TTL_MS) {
            cacheFile.delete();
            return null;
        }

        return BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
    }

    private static void cacheSkinHead(@NonNull Context context,
                                       @NonNull String username,
                                       @NonNull Bitmap head) {
        File cacheFile = getCacheFile(context, username);
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            head.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            Log.w(TAG, "Failed to cache skin head", e);
        }
    }

    private static File getCacheFile(@NonNull Context context,
                                      @NonNull String username) {
        File dir = new File(context.getFilesDir(), CACHE_DIR);
        return new File(dir, username.toLowerCase() + ".png");
    }
}
