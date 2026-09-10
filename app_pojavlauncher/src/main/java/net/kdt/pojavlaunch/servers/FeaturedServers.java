package net.kdt.pojavlaunch.servers;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The servers that ship inside the launcher.
 *
 * <p>An entry here is not user data: it is always present at the top of the Server Hub for every
 * profile, it cannot be deleted or edited by list operations, and it is drawn with its own
 * featured card (banner artwork + server mark + tagline). Everything else about it behaves like
 * a normal server — it is pinged live, its MOTD and favicon come from the real handshake, it is
 * written into {@code servers.dat} so the game's own multiplayer list shows it too, and it can be
 * joined with one tap.
 *
 * <p>This list is the single source of truth: identity, tagline and artwork all live here, and
 * {@code ServerStore} routes its per-address cache off {@link Item#address}. Artwork is bundled ({@code drawable-nodpi}) rather than fetched from a
 * URL on purpose: the hub has to look right on a plane, on a fresh install and before the first
 * ping answers, and a launcher that shows a blank grey box where a partner's banner belongs is
 * worse than one that carries 400 KB of PNG.
 */
public final class FeaturedServers {

    /** One built-in server: identity, artwork and the optional community button. */
    public static final class Item {
        public final String address;
        public final String host;
        public final int port;
        public final String name;
        public final String tagline;
        /** null when this server has no community link — the button is then not shown at all. */
        public final String discordUrl;
        public final int logoRes;
        public final int bannerRes;

        Item(String host, int port, String name, String tagline, String discordUrl,
             int logoRes, int bannerRes) {
            this.host = host;
            this.port = port;
            this.address = host + ':' + port;
            this.name = name;
            this.tagline = tagline;
            this.discordUrl = discordUrl;
            this.logoRes = logoRes;
            this.bannerRes = bannerRes;
        }

        /** A fresh, unpinged copy — what the hub shows before the first reply. */
        public ServerEntry newEntry() {
            ServerEntry e = new ServerEntry(address);
            e.name = name;
            e.pinned = true;
            e.motd = tagline;
            return e;
        }
    }

    private static final List<Item> ALL = build();

    private static List<Item> build() {
        List<Item> l = new ArrayList<>();
        // Phase 8: PlantMC removed from the built-in list (user request).
        l.add(new Item("play.indianpvp.fun", 19015, "Indian PvP & Lifesteal",
                "PvP \u2022 Grind \u2022 Steal \u2022 Dominate",
                null,
                R.drawable.featured_indianpvp_logo,
                R.drawable.featured_indianpvp_banner));
        return Collections.unmodifiableList(l);
    }

    private FeaturedServers() {}

    public static List<Item> all() { return ALL; }

    /** Built-in entries in display order, with their last known ping / MOTD / icon cache. */
    public static List<ServerEntry> entries(android.content.Context ctx) {
        List<ServerEntry> out = new ArrayList<>();
        for (Item it : ALL) out.add(ServerStore.loadBuiltIn(ctx, it));
        return out;
    }

    /** The built-in a given address belongs to, or {@code null}. */
    public static Item find(String address) {
        if (address == null) return null;
        String a = normalize(address);
        for (Item it : ALL) {
            String full = normalize(it.address);
            if (a.equals(full) || a.equals(normalize(it.host)) || a.equals(normalize(it.host + ":25565")))
                return it;
        }
        return null;
    }

    public static boolean is(String address) { return find(address) != null; }

    /** Lower-case, no scheme, no trailing slash, no default port — so any spelling matches. */
    static String normalize(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("http://")) a = a.substring(7);
        if (a.startsWith("https://")) a = a.substring(8);
        if (a.startsWith("minecraft://")) a = a.substring("minecraft://".length());
        int slash = a.indexOf('/');
        if (slash >= 0) a = a.substring(0, slash);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - ":25565".length());
        return a;
    }
}
