package net.kdt.pojavlaunch.modloaders.modpacks;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Tracks which store projects (mods / packs / shaders) are installed into each
 * profile, so the browse cards can render real states:
 *   NONE              → show the download button
 *   INSTALLED         → green ✓ "Installed in this Profile" (download hidden)
 *   UPDATE_AVAILABLE  → amber pill, download stays available
 *   INSTALLED_NEWER   → green pill, installed version is newer than store latest
 *
 * Data source of truth: entries are written by ModInstallFragment when a
 * download into a profile completes, and the "latest known store version" is
 * recorded by ModVersionPickerFragment whenever a version list is fetched.
 * The jar file itself is re-checked on query, so manual deletions from the
 * Manage Mods screen auto-heal the index.
 */
public final class InstalledContentTracker {

    public static final int STATE_NONE = 0;
    public static final int STATE_INSTALLED = 1;
    public static final int STATE_UPDATE_AVAILABLE = 2;
    public static final int STATE_INSTALLED_NEWER = 3;

    private static final String PREFS = "installed_content_index";

    private InstalledContentTracker() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * A list rebind asks this class the same question once per visible card, and
     * each answer costs two preference reads plus a file stat. That is what makes a
     * browse page stutter after an install rather than what makes it correct.
     *
     * The window is 400 ms on purpose: long enough to cover one notify + layout
     * pass, far shorter than the time between two screens, so refreshOnResume can
     * never be answered from a stale entry. Anything that writes the index
     * invalidates the entry directly, so the cache is a fast path, not a second
     * source of truth.
     */
    private static final long QUERY_TTL_MS = 400L;
    private static final java.util.HashMap<String, Object[]> sQueryCache =
            new java.util.HashMap<>();          // key -> { expiresAt, InstallInfo }

    private static InstallInfo cached(String key) {
        synchronized (sQueryCache) {
            Object[] hit = sQueryCache.get(key);
            if (hit == null) return null;
            if (((Long) hit[0]) < System.currentTimeMillis()) {
                sQueryCache.remove(key);
                return null;
            }
            return (InstallInfo) hit[1];
        }
    }

    private static void cache(String key, InstallInfo info) {
        synchronized (sQueryCache) {
            sQueryCache.put(key, new Object[]{System.currentTimeMillis() + QUERY_TTL_MS, info});
        }
    }

    /** Drop any memoised answer for this (profile, type, project). */
    public static void invalidate(Context ctx, @Nullable String profileKey,
                                  String contentType, String modId) {
        if (modId == null || contentType == null) return;
        synchronized (sQueryCache) {
            sQueryCache.remove(key(profileKey, contentType, modId));
        }
    }

    /** Called by the screens that delete jars straight off disk (Manage Mods). */
    public static void invalidateAll() {
        synchronized (sQueryCache) {
            sQueryCache.clear();
        }
    }

    private static String key(@Nullable String profileKey, String contentType, String modId) {
        return (profileKey == null ? "default" : profileKey) + "|" + contentType + "|" + modId;
    }

    private static String latestKey(String contentType, String modId) {
        return "latest|" + contentType + "|" + modId;
    }

    /** Called when a store download into a profile finishes successfully. */
    public static void markInstalled(Context ctx, @Nullable String profileKey,
                                     String contentType, String modId,
                                     @Nullable String versionName, @Nullable String fileName) {
        if (modId == null) return;
        prefs(ctx).edit()
                .putString(key(profileKey, contentType, modId),
                        (versionName == null ? "" : versionName) + "|"
                                + (fileName == null ? "" : fileName) + "|"
                                + System.currentTimeMillis())
                .apply();
        invalidate(ctx, profileKey, contentType, modId);
    }

    /** Called when the jar is removed from the profile, by any route. */
    public static void markUninstalled(Context ctx, @Nullable String profileKey,
                                       String contentType, String modId) {
        if (modId == null) return;
        try {
            prefs(ctx).edit().remove(key(profileKey, contentType, modId)).apply();
        } catch (Throwable ignored) {}
        invalidate(ctx, profileKey, contentType, modId);
    }

    /** Called when a versions list is fetched for a project (latest = first sorted entry). */
    public static void recordLatestKnown(Context ctx, String contentType,
                                         String modId, @Nullable String versionName) {
        if (modId == null || versionName == null || versionName.isEmpty()) return;
        prefs(ctx).edit().putString(latestKey(contentType, modId), versionName).apply();
    }

    /**
     * Raw recorded file name for this (profile, type, project) — used by the
     * safe update-replace step to locate the superseded jar after a new
     * version of the same store project lands in the same profile directory.
     * Pref-only lookup: performs no file-system validation and never mutates
     * the index, so it is safe to call mid-transaction.
     */
    @Nullable
    public static String getRecordedFileName(Context ctx, @Nullable String profileKey,
                                             String contentType, String modId) {
        if (modId == null || contentType == null) return null;
        try {
            String record = prefs(ctx).getString(key(profileKey, contentType, modId), null);
            if (record == null) return null;
            String[] parts = record.split("\\|", -1);
            return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve the card state for a project inside a profile.
     *
     * @param contentDir directory where this content type lives for the profile
     *                   (mods / resourcepacks / shaderpacks / saves); used to
     *                   verify the installed file still exists. May be null.
     */
    public static final class InstallInfo {
        public final int state;
        @Nullable public final String version;
        public final long installedAt;

        InstallInfo(int state, @Nullable String version, long installedAt) {
            this.state = state;
            this.version = version;
            this.installedAt = installedAt;
        }
    }

    public static int queryState(Context ctx, @Nullable String profileKey,
                                 String contentType, String modId,
                                 @Nullable File contentDir) {
        return queryInfo(ctx, profileKey, contentType, modId, contentDir).state;
    }

    /** State plus the version/date metadata rendered by the Modrinth-style card. */
    public static InstallInfo queryInfo(Context ctx, @Nullable String profileKey,
                                        String contentType, String modId,
                                        @Nullable File contentDir) {
        if (modId == null || contentType == null) return new InstallInfo(STATE_NONE, null, 0L);
        final String cacheKey = key(profileKey, contentType, modId);
        InstallInfo cached = cached(cacheKey);
        if (cached != null) return cached;
        try {
            SharedPreferences p = prefs(ctx);
            String record = p.getString(key(profileKey, contentType, modId), null);
            if (record == null) return new InstallInfo(STATE_NONE, null, 0L);

            String[] parts = record.split("\\|", -1);
            String installedVersion = parts.length > 0 ? parts[0] : "";
            String fileName = parts.length > 1 ? parts[1] : "";
            long installedAt = 0L;
            if (parts.length > 2 && !parts[2].isEmpty()) {
                try { installedAt = Long.parseLong(parts[2]); } catch (NumberFormatException ignored) {}
            }

            // Auto-heal: file vanished (deleted from Manage Mods) → drop index.
            if (!fileName.isEmpty() && contentDir != null) {
                File f = new File(contentDir, fileName);
                if (!f.exists()) {
                    p.edit().remove(key(profileKey, contentType, modId)).apply();
                    return new InstallInfo(STATE_NONE, null, 0L);
                }
                // Legacy records did not store a timestamp. File mtime is the
                // closest truthful install date and avoids showing fake data.
                if (installedAt <= 0L) installedAt = f.lastModified();
            }

            String latest = p.getString(latestKey(contentType, modId), null);
            int state;
            if (latest == null || latest.isEmpty() || installedVersion.isEmpty()
                    || latest.equals(installedVersion)) {
                state = STATE_INSTALLED;
            } else {
                int cmp = compareVersionStrings(installedVersion, latest);
                state = cmp >= 0 ? STATE_INSTALLED_NEWER : STATE_UPDATE_AVAILABLE;
            }
            InstallInfo out = new InstallInfo(state,
                    installedVersion.isEmpty() ? null : installedVersion, installedAt);
            // Only a verified-on-disk answer is memoised; the "file vanished"
            // branch below must keep re-checking until the index self-heals.
            cache(cacheKey, out);
            return out;
        } catch (Exception e) {
            return new InstallInfo(STATE_NONE, null, 0L);
        }
    }

    /** Dotted numeric version compare ("1.20.4" vs "1.21"); non-numeric → text order. */
    static int compareVersionStrings(String v1, String v2) {
        String c1 = v1.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
        String c2 = v2.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
        if (c1.isEmpty() || c2.isEmpty()) return v1.compareTo(v2);
        String[] p1 = c1.split("\\.");
        String[] p2 = c2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = 0, n2 = 0;
            try { n1 = i < p1.length ? Integer.parseInt(p1[i]) : 0; } catch (NumberFormatException ignored) {}
            try { n2 = i < p2.length ? Integer.parseInt(p2[i]) : 0; } catch (NumberFormatException ignored) {}
            if (n1 != n2) return n1 < n2 ? -1 : 1;
        }
        return 0;
    }
}
