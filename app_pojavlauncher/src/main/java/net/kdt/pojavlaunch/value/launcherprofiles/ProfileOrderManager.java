package net.kdt.pojavlaunch.value.launcherprofiles;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure ordering operations for launcher profiles. Never creates/copies profiles. */
public final class ProfileOrderManager {
    public static final int SCHEMA_VERSION = 1;
    private ProfileOrderManager() {}

    /** Migrate legacy profiles in the same last-used order the old Home UI displayed. */
    public static boolean ensure(@Nullable MinecraftLauncherProfiles data) {
        if (data == null || data.profiles == null) return false;
        boolean changed = false;
        if (data.profileOrderVersion < SCHEMA_VERSION) {
            List<Map.Entry<String, MinecraftProfile>> legacy = validEntries(data.profiles);
            legacy.sort((a, b) -> {
                String au = safe(a.getValue().lastUsed);
                String bu = safe(b.getValue().lastUsed);
                int used = bu.compareTo(au);
                return used != 0 ? used : safe(a.getKey()).compareTo(safe(b.getKey()));
            });
            long normal = 0, favorite = 0;
            for (Map.Entry<String, MinecraftProfile> e : legacy) {
                MinecraftProfile p = e.getValue();
                if (p.favorite) p.favoriteOrder = favorite++;
                p.normalOrder = normal++;
            }
            data.profileOrderVersion = SCHEMA_VERSION;
            changed = true;
        }

        long nextFavorite = nextOrder(data.profiles, true);
        long nextNormal = nextOrder(data.profiles, false);
        for (MinecraftProfile p : data.profiles.values()) {
            if (p == null) continue;
            if (isUnset(p.normalOrder)) { p.normalOrder = nextNormal++; changed = true; }
            if (isUnset(p.favoriteOrder)) { p.favoriteOrder = nextFavorite++; changed = true; }
        }
        return changed;
    }

    @NonNull
    public static List<Map.Entry<String, MinecraftProfile>> orderedEntries(
            @Nullable MinecraftLauncherProfiles data) {
        if (data == null || data.profiles == null) return new ArrayList<>();
        ensure(data);
        List<Map.Entry<String, MinecraftProfile>> entries = validEntries(data.profiles);
        entries.sort((a, b) -> {
            MinecraftProfile pa = a.getValue(), pb = b.getValue();
            if (pa.favorite != pb.favorite) return pa.favorite ? -1 : 1;
            long oa = pa.favorite ? pa.favoriteOrder : pa.normalOrder;
            long ob = pb.favorite ? pb.favoriteOrder : pb.normalOrder;
            int order = Long.compare(oa, ob);
            return order != 0 ? order : safe(a.getKey()).compareTo(safe(b.getKey()));
        });
        return entries;
    }

    public static boolean setFavorite(@Nullable MinecraftLauncherProfiles data,
                                      @NonNull String key, boolean favorite) {
        if (data == null || data.profiles == null) return false;
        ensure(data);
        MinecraftProfile profile = data.profiles.get(key);
        if (profile == null || profile.favorite == favorite) return false;
        profile.favorite = favorite;
        if (favorite) {
            profile.favoriteOrder = nextOrder(data.profiles, true);
        } else if (isUnset(profile.normalOrder)) {
            profile.normalOrder = nextOrder(data.profiles, false);
        }
        normalizeGroup(data.profiles, favorite);
        return true;
    }

    /** Persist the exact visible order, while keeping favorite and normal groups independent. */
    public static boolean applyVisibleOrder(@Nullable MinecraftLauncherProfiles data,
                                            @NonNull List<String> orderedKeys) {
        if (data == null || data.profiles == null) return false;
        ensure(data);
        long fav = 0, normal = 0;
        Set<String> seen = new HashSet<>();
        boolean changed = false;
        for (String key : orderedKeys) {
            if (key == null || !seen.add(key)) continue;
            MinecraftProfile p = data.profiles.get(key);
            if (p == null) continue;
            if (p.favorite) {
                if (p.favoriteOrder != fav) { p.favoriteOrder = fav; changed = true; }
                fav++;
            } else {
                if (p.normalOrder != normal) { p.normalOrder = normal; changed = true; }
                normal++;
            }
        }
        // Profiles omitted by a stale UI snapshot are appended, never deleted.
        for (Map.Entry<String, MinecraftProfile> e : orderedEntries(data)) {
            if (seen.contains(e.getKey())) continue;
            MinecraftProfile p = e.getValue();
            if (p.favorite) {
                if (p.favoriteOrder != fav) { p.favoriteOrder = fav; changed = true; }
                fav++;
            } else {
                if (p.normalOrder != normal) { p.normalOrder = normal; changed = true; }
                normal++;
            }
        }
        return changed;
    }

    private static void normalizeGroup(Map<String, MinecraftProfile> profiles, boolean favorite) {
        List<Map.Entry<String, MinecraftProfile>> group = validEntries(profiles);
        group.removeIf(e -> e.getValue().favorite != favorite);
        group.sort(Comparator.comparingLong((Map.Entry<String, MinecraftProfile> e) -> favorite
                ? e.getValue().favoriteOrder : e.getValue().normalOrder));
        long i = 0;
        for (Map.Entry<String, MinecraftProfile> e : group) {
            if (favorite) e.getValue().favoriteOrder = i++;
            else e.getValue().normalOrder = i++;
        }
    }

    private static long nextOrder(Map<String, MinecraftProfile> profiles, boolean favorite) {
        long max = -1;
        for (MinecraftProfile p : profiles.values()) {
            if (p == null || p.favorite != favorite) continue;
            long order = favorite ? p.favoriteOrder : p.normalOrder;
            if (!isUnset(order)) max = Math.max(max, order);
        }
        return max + 1;
    }

    private static boolean isUnset(long order) { return order < 0 || order == Long.MAX_VALUE; }
    private static String safe(String s) { return s == null ? "" : s; }

    private static List<Map.Entry<String, MinecraftProfile>> validEntries(
            Map<String, MinecraftProfile> profiles) {
        List<Map.Entry<String, MinecraftProfile>> out = new ArrayList<>();
        for (Map.Entry<String, MinecraftProfile> e : profiles.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (e.getValue().name == null || e.getValue().name.trim().isEmpty()) continue;
            out.add(e);
        }
        return out;
    }
}
