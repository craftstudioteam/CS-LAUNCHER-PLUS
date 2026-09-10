package net.kdt.pojavlaunch.servers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

/**
 * Server list entry — address, cached ping data, MOTD and icon.
 * Persisted in servers.json via ServerStore.
 */
public class ServerEntry {
    public String address; // host:port or host
    public String name; // display name (MOTD trimmed or custom)
    public String motd;
    public String motdRaw;
    public int playersOnline = -1;
    public int playersMax = -1;
    public long pingMs = -1;
    public boolean online = false;
    public String iconBase64; // data:image/png;base64,...
    public long lastPingEpoch = 0;
    public String versionName = "";
    public int protocol = -1;
    /** Filled by the SRV lookup (null when the domain has no _minecraft._tcp record). */
    public String resolvedHost;
    public int resolvedPort = -1;
    /** Built-in partner entry: always shown first, never removable, drawn as a featured card. */
    public boolean pinned = false;

    public ServerEntry() {}
    public ServerEntry(String address) {
        this.address = address.trim();
        this.name = address;
    }

    public String getHost() {
        if (address == null) return "";
        int colon = address.lastIndexOf(':');
        // handle IPv6? simplest: if contains ']' then ipv6
        if (address.contains("]")) {
            int close = address.lastIndexOf(']');
            if (close != -1 && close + 1 < address.length() && address.charAt(close + 1) == ':') {
                return address.substring(0, close + 1);
            }
            return address;
        }
        if (colon > 0 && address.indexOf(':') == colon) {
            String after = address.substring(colon + 1);
            try { Integer.parseInt(after); return address.substring(0, colon); } catch (NumberFormatException ignored) {}
        }
        return address;
    }

    public int getPort() {
        if (address == null) return 25565;
        int colon = address.lastIndexOf(':');
        if (address.contains("]")) {
            int close = address.lastIndexOf(']');
            if (close != -1 && close + 1 < address.length() && address.charAt(close + 1) == ':') {
                String after = address.substring(close + 2);
                try { return Integer.parseInt(after); } catch (NumberFormatException ignored) {}
            }
            return 25565;
        }
        if (colon > 0 && address.indexOf(':') == colon) {
            String after = address.substring(colon + 1);
            try { return Integer.parseInt(after); } catch (NumberFormatException ignored) {}
        }
        return 25565;
    }

    /**
     * True when the user typed "host:port" themselves. Vanilla skips the SRV lookup in that
     * case, and so do we — otherwise a custom port would silently be replaced by the SRV one.
     */
    public boolean hasExplicitPort() {
        if (address == null) return false;
        if (address.contains("]")) {
            int close = address.lastIndexOf(']');
            return close != -1 && close + 1 < address.length() && address.charAt(close + 1) == ':';
        }
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || address.indexOf(':') != colon) return false;
        try { Integer.parseInt(address.substring(colon + 1)); return true; }
        catch (NumberFormatException e) { return false; }
    }

    /** Address string handed to Minecraft (--server / --quickPlayMultiplayer). */
    public String getJoinAddress() {
        String host = getHost().replace("[", "").replace("]", "");
        int port = getPort();
        return port == 25565 ? host : host + ":" + port;
    }

    public Bitmap decodeIcon() {
        if (iconBase64 == null || iconBase64.isEmpty()) return null;
        try {
            String b64 = iconBase64;
            if (b64.startsWith("data:image")) {
                int comma = b64.indexOf(',');
                if (comma != -1) b64 = b64.substring(comma + 1);
            }
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) { return null; }
    }
}
