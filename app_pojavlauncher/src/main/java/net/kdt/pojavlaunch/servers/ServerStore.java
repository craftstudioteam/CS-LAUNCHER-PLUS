package net.kdt.pojavlaunch.servers;

import android.content.Context;
import android.content.SharedPreferences;
import net.kdt.pojavlaunch.prefs.CrossProcessState;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Server storage.
 *
 * <p>Two rules shape this class:
 * <ul>
 *   <li><b>Per profile.</b> Every launcher profile owns its own server list, keyed by the profile
 *       key, so a server added while editing "1.21 Fabric" never shows up under "1.8.9 PvP".</li>
 *   <li><b>The built-in server is global.</b> It lives in its own slot, is returned first for
 *       every profile, and cannot be removed, edited or duplicated by list operations.</li>
 * </ul>
 *
 * <p><b>Storage.</b> This is deliberately NOT backed by SharedPreferences. The Server Hub is
 * edited in the launcher process while the launch path (queued join, servers.dat sync) runs in
 * the game process, and MODE_PRIVATE preferences keep a per-process copy that is rewritten whole
 * on every commit — so the game process was overwriting servers the user had just added, which is
 * why a saved server IP disappeared after relaunching. {@link CrossProcessState} is read from
 * disk every time and merged under a file lock, so both processes always see the same list.
 * Data written by older builds is migrated on first read.
 */
public class ServerStore {
    private static final String PREFS = "cs_servers_v31";
    /** Legacy single global list (pre per-profile builds); migrated on first read. */
    private static final String KEY_LIST = "servers";
    private static final String KEY_LIST_PROFILE = "servers_profile_"; // + profileKey
    private static final String KEY_MIGRATED = "servers_migrated_"; // + profileKey
    /**
     * Per-address cache for the built-in servers (ping, MOTD, favicon). A map, because there is
     * more than one built-in now and each one needs its own slot.
     */
    private static final String KEY_BUILTIN = "builtin_cache";
    private static final String KEY_BUILTIN_MAP = "builtin_cache_by_address";
    private static final String KEY_PENDING = "pending_server_"; // + profileKey
    private static final String KEY_PENDING_GLOBAL = "pending_server_global";
    private static final String KEY_PENDING_TIME = "pending_server_time";
    private static final Gson GSON = new Gson();

    private static String listKey(String profileKey) {
        return KEY_LIST_PROFILE + (profileKey == null || profileKey.isEmpty() ? "default" : profileKey);
    }

    // ── storage primitives: cross-process file, never a per-process cache ──

    private static String readValue(Context ctx, String key) {
        String value = CrossProcessState.getString(ctx, key, null);
        if (value != null) return value;
        // Migration: pull the value written by older builds out of SharedPreferences once.
        SharedPreferences legacy = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = legacy.getString(key, null);
        if (old != null && !old.isEmpty()) {
            CrossProcessState.put(ctx, key, old);
            return old;
        }
        return null;
    }

    private static void writeValue(Context ctx, String key, String value) {
        CrossProcessState.put(ctx, key, value);
    }

    private static void removeValue(Context ctx, String key) {
        CrossProcessState.remove(ctx, key);
        // keep the legacy copy from resurrecting the entry on the next migration read
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply();
    }

    /**
     * Built-in servers first, then this profile's own servers.
     */
    public static List<ServerEntry> load(Context ctx, String profileKey) {
        List<ServerEntry> out = FeaturedServers.entries(ctx);
        out.addAll(loadUserServers(ctx, profileKey));
        return out;
    }

    public static List<ServerEntry> loadUserServers(Context ctx, String profileKey) {
        String key = listKey(profileKey);
        String json = readValue(ctx, key);

        // One-time migration: the old builds kept a single shared list. Copy it into the first
        // profile that asks for it so nobody loses the servers they already added.
        if (json == null) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String legacy = p.getString(KEY_LIST, null);
            boolean migrated = CrossProcessState.getBoolean(ctx, KEY_MIGRATED + key, false);
            if (legacy != null && !legacy.isEmpty() && !migrated) {
                writeValue(ctx, key, legacy);
                CrossProcessState.put(ctx, KEY_MIGRATED + key, true);
                json = legacy;
            }
        }
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Type t = new TypeToken<List<ServerEntry>>(){}.getType();
            List<ServerEntry> l = GSON.fromJson(json, t);
            if (l == null) return new ArrayList<>();
            // Built-in servers come from FeaturedServers, never from this list: storing them per
            // profile is how one user's copy would shadow a launcher-owned entry.
            l.removeIf(s -> s == null || s.address == null || FeaturedServers.is(s.address));
            return l;
        } catch (Exception e) { return new ArrayList<>(); }
    }

    /**
     * A built-in entry plus its last known ping / MOTD / favicon cache. Identity always comes from
     * code, never from the cache — that is what keeps a hand-edited preferences file from being
     * able to point a pinned card at some other server.
     */
    public static ServerEntry loadBuiltIn(Context ctx, FeaturedServers.Item item) {
        ServerEntry e = null;
        java.util.Map<String, ServerEntry> cache = readBuiltInMap(ctx);
        if (cache != null) e = cache.get(FeaturedServers.normalize(item.address));
        if (e == null) {
            // One-time read of the single-slot cache older builds used, so a returning user keeps
            // the ping/icon data instead of starting blank.
            String legacy = readValue(ctx, KEY_BUILTIN);
            if (legacy != null && !legacy.isEmpty()) {
                try {
                    ServerEntry l = GSON.fromJson(legacy, ServerEntry.class);
                    if (l != null && FeaturedServers.is(l.address)
                            && FeaturedServers.normalize(l.address)
                            .equals(FeaturedServers.normalize(item.address))) e = l;
                } catch (Exception ignored) {}
            }
        }
        if (e == null) e = item.newEntry();
        e.address = item.address;
        e.name = item.name;
        e.pinned = true;
        if (e.motd == null || e.motd.isEmpty()) e.motd = item.tagline;
        if (e.iconBase64 == null || e.iconBase64.isEmpty()) e.iconBase64 = bundledIcon(ctx, item);
        return e;
    }

    /**
     * The bundled server mark, as the {@code data:image/png;base64,...} string servers.dat
     * expects. Minecraft's multiplayer list therefore shows the real logo even on a server
     * that never answered a ping - and it is cached, so this runs once per install.
     */
    private static String bundledIcon(Context ctx, FeaturedServers.Item item) {
        if (item == null || item.logoRes == 0) return null;
        android.graphics.Bitmap src = null;
        android.graphics.Bitmap small = null;
        try {
            src = android.graphics.BitmapFactory.decodeResource(ctx.getResources(), item.logoRes);
            if (src == null) return null; // e.g. a vector mark: the card still draws it directly
            small = android.graphics.Bitmap.createScaledBitmap(src, 64, 64, true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + android.util.Base64.encodeToString(
                    out.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return null; // an icon is never a reason to fail a list load
        } finally {
            if (src != null) src.recycle();
            if (small != null && small != src) small.recycle();
        }
    }

    private static java.util.Map<String, ServerEntry> readBuiltInMap(Context ctx) {
        String json = readValue(ctx, KEY_BUILTIN_MAP);
        if (json == null || json.isEmpty()) return new java.util.LinkedHashMap<>();
        try {
            Type t = new TypeToken<java.util.Map<String, ServerEntry>>() {}.getType();
            java.util.Map<String, ServerEntry> m = GSON.fromJson(json, t);
            return m == null ? new java.util.LinkedHashMap<>() : m;
        } catch (Exception e) { return new java.util.LinkedHashMap<>(); }
    }

    private static void saveBuiltIn(Context ctx, ServerEntry e) {
        if (e == null || e.address == null) return;
        java.util.Map<String, ServerEntry> m = readBuiltInMap(ctx);
        m.put(FeaturedServers.normalize(e.address), e);
        writeValue(ctx, KEY_BUILTIN_MAP, GSON.toJson(m));
    }

    /** Persists this profile's own servers; the built-in entry is routed to its own slot. */
    public static void save(Context ctx, String profileKey, List<ServerEntry> list) {
        save(ctx, profileKey, list, false);
    }

    /**
     * @param allowEmpty must be true to store an empty list. Only an explicit removal may clear
     *                   the list; anything else writing "no servers" is a bug we refuse to
     *                   persist, because that is what wiping a user's whole list looks like.
     */
    private static void save(Context ctx, String profileKey, List<ServerEntry> list, boolean allowEmpty) {
        List<ServerEntry> userOnly = new ArrayList<>();
        for (ServerEntry s : list) {
            if (s == null || s.address == null) continue;
            if (s.pinned || FeaturedServers.is(s.address)) { saveBuiltIn(ctx, s); continue; }
            userOnly.add(s);
        }
        if (userOnly.isEmpty() && !allowEmpty && !loadUserServers(ctx, profileKey).isEmpty()) {
            android.util.Log.w("ServerStore",
                    "refusing to blank the saved server list for profile " + profileKey);
            return;
        }
        writeValue(ctx, listKey(profileKey), GSON.toJson(userOnly));
    }

    public static void add(Context ctx, String profileKey, ServerEntry e) {
        if (e == null || e.address == null) return;
        if (FeaturedServers.is(e.address)) { saveBuiltIn(ctx, e); return; } // already built in
        List<ServerEntry> l = loadUserServers(ctx, profileKey);
        for (ServerEntry s : l) if (s.address.equalsIgnoreCase(e.address)) return;
        l.add(0, e);
        save(ctx, profileKey, l);
    }

    /** @return false when the address is a built-in server, which cannot be removed. */
    public static boolean remove(Context ctx, String profileKey, String address) {
        if (FeaturedServers.is(address)) return false;
        List<ServerEntry> l = loadUserServers(ctx, profileKey);
        l.removeIf(s -> s.address.equalsIgnoreCase(address));
        save(ctx, profileKey, l, true); // an explicit delete may leave the list empty
        return true;
    }

    public static void update(Context ctx, String profileKey, ServerEntry updated) {
        if (updated == null || updated.address == null) return;
        if (updated.pinned || FeaturedServers.is(updated.address)) { saveBuiltIn(ctx, updated); return; }
        List<ServerEntry> l = loadUserServers(ctx, profileKey);
        if (l.isEmpty()) return; // nothing to update — never rewrite the list with an empty one
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).address.equalsIgnoreCase(updated.address)) { l.set(i, updated); found = true; break; }
        }
        if (!found) return;
        save(ctx, profileKey, l);
    }

    /**
     * Pending 1-click launch.
     *
     * <p>A global copy is always written next to the per-profile one. The launch path only knows
     * the "current profile" preference, and that key can differ from the profile the hub was
     * opened from (shortcuts, quick-launch, profile switch after queueing) — which is exactly
     * why auto-join used to silently do nothing. The global key is the fallback that makes the
     * hand-off reliable, and it is cleared as soon as the game is launched.
     */
    public static void setPending(Context ctx, String profileKey, String address) {
        try {
            org.json.JSONObject changes = new org.json.JSONObject();
            if (profileKey != null) changes.put(KEY_PENDING + profileKey, address);
            changes.put(KEY_PENDING_GLOBAL, address);
            changes.put(KEY_PENDING_TIME, System.currentTimeMillis());
            CrossProcessState.merge(ctx, changes);
        } catch (Exception ignored) {}
    }

    public static String getPending(Context ctx, String profileKey) {
        String perProfile = profileKey != null ? readValue(ctx, KEY_PENDING + profileKey) : null;
        if (perProfile != null && !perProfile.isEmpty()) return perProfile;
        return readValue(ctx, KEY_PENDING_GLOBAL);
    }

    public static void clearPending(Context ctx, String profileKey) {
        if (profileKey != null) removeValue(ctx, KEY_PENDING + profileKey);
        removeValue(ctx, KEY_PENDING_GLOBAL);
        removeValue(ctx, KEY_PENDING_TIME);
    }

    /**
     * Clears the queued join for every profile — used once the game has actually been started.
     * Only the pending keys are touched: the saved server lists must survive a launch.
     */
    public static void clearAllPending(Context ctx) {
        org.json.JSONObject state = CrossProcessState.readAll(ctx);
        org.json.JSONObject changes = new org.json.JSONObject();
        try {
            for (java.util.Iterator<String> it = state.keys(); it.hasNext(); ) {
                String key = it.next();
                if (key.startsWith(KEY_PENDING)) changes.put(key, org.json.JSONObject.NULL);
            }
            changes.put(KEY_PENDING_GLOBAL, org.json.JSONObject.NULL);
            changes.put(KEY_PENDING_TIME, org.json.JSONObject.NULL);
        } catch (Exception ignored) {}
        if (changes.length() > 0) CrossProcessState.merge(ctx, changes);

        // legacy copies, so an old pending join cannot be migrated back in
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit();
        for (String key : p.getAll().keySet()) if (key.startsWith(KEY_PENDING)) ed.remove(key);
        ed.apply();
    }
}
