package net.kdt.pojavlaunch.servers;

import android.content.Context;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.worlds.NbtIO;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps {@code <gameDir>/servers.dat} in sync with the launcher's Server Hub, so anything added
 * in the hub also shows up in the in-game Multiplayer list.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Hub and active servers are prioritized at the top of {@code servers.dat}, so after a
 *       verification plugin kicks or transfers the player, the server is immediately accessible
 *       at index 0 in the in-game multiplayer screen;</li>
 *   <li>Existing user servers in {@code servers.dat} are preserved without data loss or duplication;</li>
 *   <li>No invalid base64 string favicon tags that break 1.20.5+ Mojang NBT codecs;</li>
 *   <li>Pre-launch sync ensures game directories created on first run get the server list.</li>
 * </ul>
 */
public class ServerListSync {
    private static final String TAG = "ServerListSync";

    public static void syncToGameDir(Context ctx, String profileKey) {
        syncToGameDir(ctx, profileKey, resolveGameDir(profileKey));
    }

    public static void syncToGameDir(Context ctx, String profileKey, File gameDir) {
        try {
            if (gameDir == null) gameDir = new File(Tools.DIR_GAME_NEW);
            if (!gameDir.exists() && !gameDir.mkdirs()) {
                Log.w(TAG, "cannot create game dir " + gameDir);
                return;
            }
            List<ServerEntry> hubServers = ServerStore.load(ctx, profileKey);
            File dat = new File(gameDir, "servers.dat");

            // ── 1. Read existing servers from servers.dat so nothing is lost ──
            List<Map<String, Object>> existingEntries = new ArrayList<>();
            Set<String> known = new LinkedHashSet<>();
            if (dat.isFile()) {
                try {
                    Map<String, Object> existing = NbtIO.readPossiblyGzipped(dat);
                    Object serversTag = existing.get("servers");
                    if (serversTag instanceof NbtIO.NbtList) {
                        for (Object item : ((NbtIO.NbtList) serversTag).items) {
                            if (!(item instanceof Map)) continue;
                            @SuppressWarnings("unchecked")
                            Map<String, Object> entry = (Map<String, Object>) item;
                            Object ip = entry.get("ip");
                            if (ip instanceof String) {
                                known.add(((String) ip).toLowerCase());
                            }
                            existingEntries.add(entry);
                        }
                    }
                } catch (Exception readFail) {
                    Log.w(TAG, "existing servers.dat unreadable, rebuilding: " + readFail);
                    known.clear();
                }
            }

            // ── 2. Prioritize Hub and active servers at top, then append other servers ──
            NbtIO.NbtList finalList = new NbtIO.NbtList(10 /* TAG_Compound */);
            Set<String> inserted = new LinkedHashSet<>();

            for (ServerEntry e : hubServers) {
                if (e == null || e.address == null || e.address.isEmpty()) continue;
                String key = e.address.toLowerCase();
                if (inserted.contains(key)) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("ip", e.address);
                String name = e.name != null && !e.name.isEmpty() ? e.name : e.address;
                name = MotdRenderer.stripLegacy(name).trim();
                if (name.isEmpty()) name = e.address;
                if (name.length() > 64) name = name.substring(0, 64);
                entry.put("name", name);
                entry.put("acceptTextures", (byte) 1);
                finalList.items.add(entry);
                inserted.add(key);
            }

            for (Map<String, Object> existing : existingEntries) {
                Object ip = existing.get("ip");
                if (ip instanceof String) {
                    String key = ((String) ip).toLowerCase();
                    if (!inserted.contains(key)) {
                        finalList.items.add(existing);
                        inserted.add(key);
                    }
                }
            }

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("servers", finalList);
            NbtIO.writeGzipped(dat, root);
            Log.i(TAG, "servers.dat synced (" + finalList.items.size() + " entries) -> " + dat);
        } catch (Exception ex) {
            Log.w(TAG, "sync failed", ex);
        }
    }

    /** Pulls servers the player added in-game back into the hub, so both lists match. */
    public static void importFromGameDir(Context ctx, String profileKey) {
        try {
            File gameDir = resolveGameDir(profileKey);
            if (gameDir == null) return;
            File dat = new File(gameDir, "servers.dat");
            if (!dat.isFile()) return;
            Map<String, Object> root = NbtIO.readPossiblyGzipped(dat);
            Object serversTag = root.get("servers");
            if (!(serversTag instanceof NbtIO.NbtList)) return;

            List<ServerEntry> current = ServerStore.load(ctx, profileKey);
            Set<String> known = new LinkedHashSet<>();
            for (ServerEntry e : current) if (e.address != null) known.add(e.address.toLowerCase());

            List<ServerEntry> imported = new ArrayList<>();
            for (Object item : ((NbtIO.NbtList) serversTag).items) {
                if (!(item instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) item;
                Object ip = entry.get("ip");
                if (!(ip instanceof String)) continue;
                String address = ((String) ip).trim();
                if (address.isEmpty() || known.contains(address.toLowerCase())) continue;
                ServerEntry se = new ServerEntry(address);
                Object name = entry.get("name");
                if (name instanceof String && !((String) name).isEmpty()) se.name = (String) name;
                imported.add(se);
                known.add(address.toLowerCase());
            }
            if (imported.isEmpty()) return;
            current.addAll(imported);
            ServerStore.save(ctx, profileKey, current);
            Log.i(TAG, "imported " + imported.size() + " server(s) from servers.dat");
        } catch (Exception ex) {
            Log.w(TAG, "import failed", ex);
        }
    }

    public static void syncAllProfiles(Context ctx) {
        try {
            LauncherProfiles.load();
            for (String key : LauncherProfiles.mainProfileJson.profiles.keySet()) {
                syncToGameDir(ctx, key);
            }
        } catch (Exception ignored) {}
    }

    private static File resolveGameDir(String profileKey) {
        File gameDir = null;
        if (profileKey != null) {
            try {
                LauncherProfiles.load();
                MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
                if (p != null) gameDir = Tools.getGameDirPath(p);
            } catch (Exception ignored) {}
        }
        if (gameDir == null) gameDir = new File(Tools.DIR_GAME_NEW);
        return gameDir;
    }
}
