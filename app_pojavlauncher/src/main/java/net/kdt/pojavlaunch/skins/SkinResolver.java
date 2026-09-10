package net.kdt.pojavlaunch.skins;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Phase 8 — one place that answers "where is this account's full skin PNG?"
 *
 * <p>The home 3D character used to go through the Mojang UUID → session server
 * → textures pipeline only. That works for Microsoft accounts and for nothing
 * else: an ely.by account has an ely.by UUID the Mojang session server does not
 * know, and a local ("PNE"/offline) account has no UUID at all. On top of that
 * the ely.by fetch used {@code http://}, which Android blocks outright on
 * targetSdk 28+, so ely.by skins silently never arrived. Both showed Steve.
 *
 * <p>Resolution order, per account kind (first hit wins, result cached as
 * {@code <DIR_DATA>/skins/<username>_skin.png} — the file every other skin
 * surface already reads):
 * <ol>
 *   <li><b>Microsoft</b>: Mojang by UUID (unchanged pipeline).</li>
 *   <li><b>ely.by</b>: {@code https://skinsystem.ely.by/skins/<name>.png}, then
 *       ely.by's textures API by name, then Mojang by name (many ely users
 *       mirror a premium name).</li>
 *   <li><b>Local</b>: Mojang by name (premium-name skins), then ely.by by
 *       name, then {@code mc-heads.net/skin/<name>} as the last resort.</li>
 * </ol>
 * Anything shorter than a real 64×32 skin is rejected so a 404 HTML body can
 * never be cached as a "skin".
 */
public final class SkinResolver {

    private static final String TAG = "SkinResolver";
    private static final int TIMEOUT_MS = 9000;
    private static final long MAX_BYTES = 4L * 1024L * 1024L;

    private SkinResolver() {}

    /** Full-skin cache file for a username (created by whichever source wins). */
    @NonNull
    public static File skinFile(@NonNull String username) {
        return new File(Tools.DIR_DATA + "/skins/" + username + "_skin.png");
    }

    /**
     * Resolve + cache the full skin for {@code account}. Blocking; run off the
     * UI thread. Returns the cache file when a usable skin exists (fresh or
     * previously cached), or null.
     */
    @Nullable
    public static File resolve(@Nullable MinecraftAccount account) {
        if (account == null || account.username == null || account.username.trim().isEmpty()) return null;
        final String name = account.username.trim();
        File out = skinFile(name);
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();

        if (account.isMicrosoft) {
            // Phase 10 root cause of "premium head shows the default/mouse skin
            // in the account list": ANY 64x32 file at skins/<name>_skin.png was
            // accepted as the cached skin — including one a local/ely.by account
            // of the same name (or the default character) wrote earlier — so
            // the real Mojang skin was never fetched. A premium account now
            // requires the cache to be MARKED as the exact Mojang skin for its
            // UUID (sidecar); anything else is re-fetched and overwritten.
            if (account.isSkinSlotManaged() && isUsableSkin(out)) return out; // user-equipped slot wins
            if (isUsableSkin(out) && isPremiumCache(out, account)) return out;
            File fresh = new File(out.getAbsolutePath() + ".premium.tmp");
            fresh.delete();
            File viaMojang = mojangByUuid(account, fresh);
            if (viaMojang != null && isUsableSkin(viaMojang)) {
                out.delete();
                if (!viaMojang.renameTo(out)) {
                    try { org.apache.commons.io.FileUtils.copyFile(viaMojang, out); } catch (Throwable ignored) {}
                    viaMojang.delete();
                }
                if (isUsableSkin(out)) {
                    markPremiumCache(out, account);
                    // the flat face cache was cut from the stale sheet — drop it
                    try { new File(Tools.DIR_DATA + "/skins/" + name + "_face.png").delete(); } catch (Throwable ignored) {}
                    return out;
                }
            }
            fresh.delete();
            // Offline / rate-limited: keep whatever we have rather than nothing.
            return isUsableSkin(out) ? out : null;
        }
        if (isUsableSkin(out)) return out;

        String[] candidates;
        if (account.isMicrosoft) {
            candidates = new String[]{
                    "https://mc-heads.net/skin/" + name };
        } else if (account.isElyByAccount()) {
            // Phase 11 root cause of "ely.by skin never shows on the home 3D
            // body/head": skinsystem.ely.by/skins/<name>.png answers with a
            // 301 to http://ely.by/storage/skins/<hash>.png. HttpURLConnection
            // never follows an https→http hop and cleartext is blocked by the
            // manifest, so the download silently failed every time and the
            // default character stayed. The textures JSON gives us the real
            // storage URL (and tells us "no skin" with a 204), and the
            // downloader now follows redirects by hand, upgrading them to https.
            candidates = new String[]{
                    "ely-textures:" + name,
                    "https://skinsystem.ely.by/skins/" + name + ".png",
                    mojangByNameUrl(name),
                    "https://mc-heads.net/skin/" + name };
        } else {
            candidates = new String[]{
                    mojangByNameUrl(name),
                    "ely-textures:" + name,
                    "https://skinsystem.ely.by/skins/" + name + ".png",
                    "https://mc-heads.net/skin/" + name };
        }
        for (String url : candidates) {
            if (url == null) continue;
            if (download(url, out)) {
                Log.i(TAG, "skin for " + name + " from " + url.replaceAll("https?://", "").split("/")[0]);
                return out;
            }
        }
        return null;
    }

    // ── premium cache marker ─────────────────────────────────────────────────

    /** Sidecar next to the skin: "mojang:<uuid>:<length>:<lastModified>". */
    @NonNull
    private static File premiumMarker(@NonNull File skin) {
        return new File(skin.getAbsolutePath() + ".src");
    }

    /** True when the cached sheet is the one fetched from Mojang for THIS account's UUID. */
    public static boolean isPremiumCache(@Nullable File skin, @Nullable MinecraftAccount account) {
        if (skin == null || account == null || !skin.isFile()) return false;
        try {
            File m = premiumMarker(skin);
            if (!m.isFile()) return false;
            String v = Tools.read(m.getAbsolutePath()).trim();
            String expect = "mojang:" + String.valueOf(account.profileId) + ":" + skin.length() + ":" + skin.lastModified();
            return expect.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markPremiumCache(@NonNull File skin, @NonNull MinecraftAccount account) {
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(premiumMarker(skin))) {
            String v = "mojang:" + String.valueOf(account.profileId) + ":" + skin.length() + ":" + skin.lastModified();
            fo.write(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    /** Called by the skin manager when the user equips/uploads a skin: the cache is no longer "Mojang's". */
    public static void forgetPremiumMark(@Nullable String username) {
        if (username == null) return;
        try { premiumMarker(skinFile(username)).delete(); } catch (Throwable ignored) {}
    }

    /** True when the PNG on disk decodes to a real skin sheet (≥ 64×32). */
    public static boolean isUsableSkin(@Nullable File f) {
        if (f == null || !f.isFile() || f.length() < 200) return false;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            return o.outWidth >= 64 && o.outHeight >= 32;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── sources ──────────────────────────────────────────────────────────────

    @Nullable
    private static File mojangByUuid(@NonNull MinecraftAccount account, @NonNull File out) {
        try {
            return net.kdt.pojavlaunch.shortcuts.ShortcutSkinHeadHelper
                    .getFullSkinFile(account.profileId, account.username, out);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Mojang skin by <i>name</i>: the name → UUID lookup, then the session
     * server textures. Returned as a pseudo-URL handled by {@link #download}
     * so the candidate list stays a flat ordered array.
     */
    @Nullable
    private static String mojangByNameUrl(@NonNull String name) {
        return "mojang-name:" + name;
    }

    /**
     * ely.by textures endpoint: {@code {"SKIN":{"url":...},"CAPE":{"url":...}}}
     * (HTTP 204 = the account has no ely.by skin). The skin URL is downloaded
     * through the redirect-safe path; a cape, when present, is cached next to
     * the other capes so the home 3D view shows it too.
     */
    @Nullable
    public static String elyTexturesSkinUrl(@NonNull String name, @Nullable File capeOut) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://skinsystem.ely.by/textures/" + name).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "CS-Launcher-Plus");
            int code = c.getResponseCode();
            if (code != 200) return null;
            String body;
            try (InputStream in = c.getInputStream()) {
                body = new String(org.apache.commons.io.IOUtils.toByteArray(in), java.nio.charset.StandardCharsets.UTF_8);
            }
            org.json.JSONObject json = new org.json.JSONObject(body);
            String skinUrl = null;
            org.json.JSONObject skin = json.optJSONObject("SKIN");
            if (skin != null) skinUrl = skin.optString("url", null);
            org.json.JSONObject cape = json.optJSONObject("CAPE");
            if (cape != null && capeOut != null) {
                String capeUrl = cape.optString("url", null);
                if (capeUrl != null && !capeUrl.isEmpty()) {
                    try {
                        File parent = capeOut.getParentFile();
                        if (parent != null && !parent.isDirectory()) parent.mkdirs();
                        downloadImage(capeUrl, capeOut, 64, 32);
                    } catch (Throwable ignored) {}
                }
            }
            return skinUrl == null || skinUrl.isEmpty() ? null : skinUrl;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Open {@code url} following up to 4 redirects by hand. Every hop is
     * forced to https (ely.by redirects to a cleartext storage host, which
     * Android refuses and HttpURLConnection would not follow anyway).
     */
    @Nullable
    public static HttpURLConnection openImage(@NonNull String url) throws java.io.IOException {
        String current = url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "CS-Launcher-Plus");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) return null;
                if (loc.startsWith("/")) {
                    URL u = new URL(current);
                    loc = "https://" + u.getHost() + loc;
                } else if (loc.startsWith("http://")) {
                    loc = "https://" + loc.substring("http://".length());
                }
                current = loc;
                continue;
            }
            if (code < 200 || code >= 300) { c.disconnect(); return null; }
            String type = c.getContentType();
            if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) { c.disconnect(); return null; }
            return c;
        }
        return null;
    }

    /** Download a PNG (redirect-safe) and keep it only when it decodes to at least minW×minH. */
    private static boolean downloadImage(@NonNull String url, @NonNull File out, int minW, int minH) {
        HttpURLConnection c = null;
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try {
            c = openImage(url);
            if (c == null) return false;
            long total = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return false;
                    fo.write(buf, 0, n);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            if (o.outWidth < minW || o.outHeight < minH) { tmp.delete(); return false; }
            out.delete();
            if (!tmp.renameTo(out)) {
                org.apache.commons.io.FileUtils.copyFile(tmp, out);
                tmp.delete();
            }
            return out.isFile();
        } catch (Throwable t) {
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean download(@NonNull String url, @NonNull File out) {
        if (url.startsWith("ely-textures:")) {
            String name = url.substring("ely-textures:".length());
            File capeOut = new File(Tools.DIR_DATA + "/capes/" + name + "_cape.png");
            String skinUrl = elyTexturesSkinUrl(name, capeOut);
            if (skinUrl == null) return false;
            return downloadImage(skinUrl, out, 64, 32) && isUsableSkin(out);
        }
        if (url.startsWith("mojang-name:")) {
            String name = url.substring("mojang-name:".length());
            try {
                File tmp = new File(out.getAbsolutePath() + ".mojang.tmp");
                tmp.delete();
                File r = net.kdt.pojavlaunch.shortcuts.ShortcutSkinHeadHelper.getFullSkinFile(name, tmp);
                if (r != null && isUsableSkin(r)) {
                    out.delete();
                    if (r.renameTo(out)) return true;
                    org.apache.commons.io.FileUtils.copyFile(r, out);
                    r.delete();
                    return isUsableSkin(out);
                }
                tmp.delete();
            } catch (Throwable ignored) {}
            return false;
        }
        HttpURLConnection c = null;
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try {
            c = openImage(url);
            if (c == null) return false;
            long total = 0;
            try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return false;
                    fo.write(buf, 0, n);
                }
            }
            if (!isUsableSkin(tmp)) { tmp.delete(); return false; }
            out.delete();
            if (!tmp.renameTo(out)) {
                org.apache.commons.io.FileUtils.copyFile(tmp, out);
                tmp.delete();
            }
            return isUsableSkin(out);
        } catch (Throwable t) {
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Decode a cached skin, or null. */
    @Nullable
    public static Bitmap decode(@Nullable File f) {
        if (!isUsableSkin(f)) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }
}
