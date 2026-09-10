package net.kdt.pojavlaunch.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks settings metadata for the Phase-3 Settings experience:
 * ⭐ Favorites and 🕘 Recently-Changed keys.
 *
 * Kept in its own prefs file so it never leaks into the draft/save pipeline
 * (draft init only copies primitive scalars from cslauncher_settings).
 */
public final class SettingsMetaStore {

    private static final String FILE = "csl_settings_meta";
    private static final String FAVORITES = "favorite_keys";
    private static final String RECENT_KEYS = "recent_keys_ordered";
    private static final int RECENT_CAP = 24;
    private static final String SEP = "␟"; // unit-separator join (keys never contain it)

    private SettingsMetaStore() {}

    private static SharedPreferences prefs(@NonNull Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ── Favorites ──

    public static boolean isFavorite(@NonNull Context ctx, @NonNull String key) {
        return prefs(ctx).getStringSet(FAVORITES, new LinkedHashSet<>()).contains(key);
    }

    public static void toggleFavorite(@NonNull Context ctx, @NonNull String key) {
        SharedPreferences p = prefs(ctx);
        Set<String> favs = new LinkedHashSet<>(p.getStringSet(FAVORITES, new LinkedHashSet<>()));
        if (!favs.remove(key)) favs.add(key);
        p.edit().putStringSet(FAVORITES, favs).apply();
    }

    @NonNull
    public static List<String> getFavorites(@NonNull Context ctx) {
        // LinkedHashSet keeps insertion order; newest last → show newest first.
        List<String> out = new ArrayList<>(
                prefs(ctx).getStringSet(FAVORITES, new LinkedHashSet<>()));
        java.util.Collections.reverse(out);
        return out;
    }

    // ── Recently changed ──

    public static void recordChange(@NonNull Context ctx, @NonNull String key) {
        SharedPreferences p = prefs(ctx);
        List<String> keys = decode(p.getString(RECENT_KEYS, ""));
        keys.remove(key);
        keys.add(0, key);
        while (keys.size() > RECENT_CAP) keys.remove(keys.size() - 1);
        p.edit().putString(RECENT_KEYS, encode(keys)).apply();
    }

    @NonNull
    public static List<String> getRecent(@NonNull Context ctx) {
        return decode(prefs(ctx).getString(RECENT_KEYS, ""));
    }

    public static void clearRecent(@NonNull Context ctx) {
        prefs(ctx).edit().remove(RECENT_KEYS).apply();
    }

    private static List<String> decode(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String k : raw.split(SEP)) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private static String encode(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (k.contains(SEP)) continue;
            if (sb.length() > 0) sb.append(SEP);
            sb.append(k);
        }
        return sb.toString();
    }
}
