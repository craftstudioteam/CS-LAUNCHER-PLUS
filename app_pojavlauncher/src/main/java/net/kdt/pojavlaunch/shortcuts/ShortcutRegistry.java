package net.kdt.pojavlaunch.shortcuts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * On-device registry of every shortcut this launcher has created.
 *
 * <p>Android's {@code ShortcutManager} can only enumerate dynamic shortcuts, and
 * only from API 25. Pinned shortcuts are owned by the home screen app and are
 * effectively write-only from our side. To offer a "Manage shortcuts" screen we
 * therefore keep an independent list, stored as JSON in a dedicated
 * {@link SharedPreferences} file.</p>
 *
 * <p>All methods are synchronised on the class monitor. Reads are cheap (the list
 * is cached in memory after the first load) so calling from the UI thread is fine.</p>
 */
public final class ShortcutRegistry {

    private static final String TAG = "ShortcutRegistry";
    private static final String PREFS_NAME = "cs_shortcuts";
    private static final String KEY_RECORDS = "records";

    private static final Gson GSON = new Gson();

    /** In-memory cache. Null until the first {@link #load(Context)}. */
    private static List<ShortcutRecord> sCache;

    private ShortcutRegistry() {
        // static only
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Load every registered shortcut, newest first.
     *
     * @return a defensive copy — mutating it does not affect the registry.
     */
    @NonNull
    public static synchronized List<ShortcutRecord> load(@NonNull Context context) {
        if (sCache == null) {
            sCache = readFromDisk(context);
        }
        return new ArrayList<>(sCache);
    }

    @NonNull
    private static List<ShortcutRecord> readFromDisk(@NonNull Context context) {
        String json = prefs(context).getString(KEY_RECORDS, null);
        if (json == null || json.isEmpty()) return new ArrayList<>();

        try {
            List<ShortcutRecord> parsed = GSON.fromJson(json,
                    new TypeToken<List<ShortcutRecord>>() {}.getType());
            if (parsed == null) return new ArrayList<>();

            // Drop anything corrupted so the UI never renders a broken row.
            List<ShortcutRecord> clean = new ArrayList<>(parsed.size());
            for (ShortcutRecord record : parsed) {
                if (record != null && record.isValid()) clean.add(record);
            }
            return clean;
        } catch (JsonSyntaxException e) {
            Log.w(TAG, "Corrupted shortcut registry, resetting", e);
            return new ArrayList<>();
        }
    }

    private static synchronized void persist(@NonNull Context context) {
        if (sCache == null) return;
        prefs(context).edit()
                .putString(KEY_RECORDS, GSON.toJson(sCache))
                .apply();
    }

    /**
     * Insert a record, replacing any existing entry with the same shortcut id.
     */
    public static synchronized void put(@NonNull Context context,
                                        @NonNull ShortcutRecord record) {
        if (!record.isValid()) {
            Log.w(TAG, "Refusing to register an invalid shortcut record");
            return;
        }
        if (sCache == null) sCache = readFromDisk(context);

        for (int i = 0; i < sCache.size(); i++) {
            if (record.shortcutId.equals(sCache.get(i).shortcutId)) {
                sCache.set(i, record);
                persist(context);
                return;
            }
        }
        sCache.add(record);
        persist(context);
    }

    /** Remove one shortcut by its Android id. */
    public static synchronized void remove(@NonNull Context context,
                                           @NonNull String shortcutId) {
        if (sCache == null) sCache = readFromDisk(context);
        boolean changed = false;
        for (int i = sCache.size() - 1; i >= 0; i--) {
            if (shortcutId.equals(sCache.get(i).shortcutId)) {
                sCache.remove(i);
                changed = true;
            }
        }
        if (changed) persist(context);
    }

    /** Remove every shortcut belonging to a profile (used when a profile is deleted). */
    public static synchronized void removeForProfile(@NonNull Context context,
                                                     @NonNull String profileKey) {
        if (sCache == null) sCache = readFromDisk(context);
        boolean changed = false;
        for (int i = sCache.size() - 1; i >= 0; i--) {
            if (profileKey.equals(sCache.get(i).profileKey)) {
                sCache.remove(i);
                changed = true;
            }
        }
        if (changed) persist(context);
    }

    @Nullable
    public static synchronized ShortcutRecord find(@NonNull Context context,
                                                   @NonNull String shortcutId) {
        if (sCache == null) sCache = readFromDisk(context);
        for (ShortcutRecord record : sCache) {
            if (shortcutId.equals(record.shortcutId)) return record;
        }
        return null;
    }

    /** Every shortcut registered for one profile. */
    @NonNull
    public static synchronized List<ShortcutRecord> findByProfile(@NonNull Context context,
                                                                  @NonNull String profileKey) {
        if (sCache == null) sCache = readFromDisk(context);
        List<ShortcutRecord> result = new ArrayList<>();
        for (ShortcutRecord record : sCache) {
            if (profileKey.equals(record.profileKey)) result.add(record);
        }
        return result;
    }

    /** True when this exact profile + action combination already has a shortcut. */
    public static synchronized boolean exists(@NonNull Context context,
                                              @NonNull String profileKey,
                                              @NonNull ShortcutType type) {
        if (sCache == null) sCache = readFromDisk(context);
        for (ShortcutRecord record : sCache) {
            if (profileKey.equals(record.profileKey)
                    && type.getId().equals(record.actionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record a tap on a shortcut. Silently ignores unknown ids so that shortcuts
     * created before the registry existed do not spam the log.
     */
    public static synchronized void recordUsage(@NonNull Context context,
                                                @NonNull String shortcutId) {
        ShortcutRecord record = find(context, shortcutId);
        if (record == null) return;
        record.markUsed();
        persist(context);
    }

    /** Total number of registered shortcuts. */
    public static synchronized int count(@NonNull Context context) {
        if (sCache == null) sCache = readFromDisk(context);
        return sCache.size();
    }

    /**
     * Shortcuts sorted by how often they are used, most used first.
     * Ties fall back to the most recently used.
     */
    @NonNull
    public static synchronized List<ShortcutRecord> loadByUsage(@NonNull Context context) {
        List<ShortcutRecord> records = load(context);
        Collections.sort(records, new Comparator<ShortcutRecord>() {
            @Override
            public int compare(ShortcutRecord a, ShortcutRecord b) {
                if (a.useCount != b.useCount) {
                    return Integer.compare(b.useCount, a.useCount);
                }
                return Long.compare(b.lastUsedAt, a.lastUsedAt);
            }
        });
        return records;
    }

    /**
     * Drop registry entries whose profile no longer exists.
     *
     * @param liveProfileKeys keys currently present in {@code LauncherProfiles}
     * @return the shortcut ids that were pruned, so the caller can also disable
     * them through {@code ShortcutManager}
     */
    @NonNull
    public static synchronized List<String> pruneOrphans(@NonNull Context context,
                                                         @NonNull List<String> liveProfileKeys) {
        if (sCache == null) sCache = readFromDisk(context);
        List<String> pruned = new ArrayList<>();
        for (int i = sCache.size() - 1; i >= 0; i--) {
            ShortcutRecord record = sCache.get(i);
            if (!liveProfileKeys.contains(record.profileKey)) {
                pruned.add(record.shortcutId);
                sCache.remove(i);
            }
        }
        if (!pruned.isEmpty()) persist(context);
        return pruned;
    }

    /** Wipe everything. Used by the "remove all shortcuts" action. */
    public static synchronized void clear(@NonNull Context context) {
        sCache = new ArrayList<>();
        persist(context);
    }

    /** Drop the in-memory cache so the next read hits disk. Mainly for tests. */
    public static synchronized void invalidate() {
        sCache = null;
    }
}
