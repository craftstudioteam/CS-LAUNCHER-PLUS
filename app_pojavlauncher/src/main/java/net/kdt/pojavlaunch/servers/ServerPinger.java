package net.kdt.pojavlaunch.servers;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Server List Ping (SLP) implementation matching the vanilla handshake.
 *
 * <p>Key behaviours copied from the game itself:
 * <ul>
 *   <li>protocol number -1 is sent, which every server understands as "status request from an
 *       unknown client" — sending a fixed number (760) made some servers answer with a
 *       version-mismatch MOTD instead of their real one.</li>
 *   <li>{@code _minecraft._tcp} SRV records are resolved before connecting, exactly like the
 *       client, so SRV-only servers stop showing up as "offline".</li>
 *   <li>the raw description is preserved verbatim in {@link ServerEntry#motdRaw} so
 *       {@link MotdRenderer} can paint the real colours; only a plain copy is stripped.</li>
 * </ul>
 */
public class ServerPinger {
    private static final String TAG = "ServerPinger";
    private static final int TIMEOUT_MS = 5000;
    /** -1 == "I don't know my protocol yet", the value vanilla uses for status pings. */
    private static final int PROTOCOL_STATUS = -1;

    public interface Callback {
        void onResult(ServerEntry entry);
        void onError(ServerEntry entry, Exception e);
    }

    public static void pingAsync(ServerEntry entry, Callback cb) {
        new Thread(() -> {
            try {
                ping(entry);
                if (cb != null) cb.onResult(entry);
            } catch (Exception e) {
                Log.w(TAG, "ping failed " + entry.address + ": " + e);
                entry.online = false;
                entry.pingMs = -1;
                entry.playersOnline = -1;
                entry.playersMax = -1;
                entry.lastPingEpoch = System.currentTimeMillis();
                if (cb != null) cb.onError(entry, e);
            }
        }, "ServerPinger-" + entry.address).start();
    }

    public static void ping(ServerEntry entry) throws Exception {
        String host = entry.getHost();
        String connectHost = host;
        if (connectHost.startsWith("[") && connectHost.endsWith("]"))
            connectHost = connectHost.substring(1, connectHost.length() - 1);
        int port = entry.getPort();

        // Vanilla resolves SRV only when the user did not type an explicit port.
        if (!entry.hasExplicitPort()) {
            SrvResolver.Result srv = SrvResolver.resolve(connectHost);
            if (srv != null) {
                Log.i(TAG, "SRV " + connectHost + " -> " + srv.host + ":" + srv.port);
                entry.resolvedHost = srv.host;
                entry.resolvedPort = srv.port;
                connectHost = srv.host;
                port = srv.port;
            } else {
                entry.resolvedHost = null;
                entry.resolvedPort = -1;
            }
        }

        // The hostname sent inside the handshake must stay the one the user typed,
        // otherwise virtual-host (BungeeCord / Velocity) servers answer with the wrong MOTD.
        String handshakeHost = host.replace("[", "").replace("]", "");
        int handshakePort = entry.getPort();

        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(connectHost, port), TIMEOUT_MS);
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {

                ByteArrayOutputStream hsBuf = new ByteArrayOutputStream();
                DataOutputStream hs = new DataOutputStream(hsBuf);
                writeVarInt(hs, 0x00);
                writeVarInt(hs, PROTOCOL_STATUS);
                writeString(hs, handshakeHost);
                hs.writeShort(handshakePort);
                writeVarInt(hs, 1); // next state: status
                byte[] hsBytes = hsBuf.toByteArray();
                writeVarInt(out, hsBytes.length);
                out.write(hsBytes);
                writeVarInt(out, 1);
                writeVarInt(out, 0x00); // status request
                out.flush();

                readVarInt(in); // packet length
                int packetId = readVarInt(in);
                if (packetId != 0x00) throw new RuntimeException("Invalid status packet " + packetId);
                int jsonLen = readVarInt(in);
                if (jsonLen <= 0 || jsonLen > 4 * 1024 * 1024) throw new RuntimeException("Bad status length " + jsonLen);
                byte[] jsonBytes = new byte[jsonLen];
                in.readFully(jsonBytes);
                String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
                long handshakePing = System.currentTimeMillis() - start;
                parseJson(entry, jsonStr, handshakePing);

                // Ping/pong gives the accurate latency, exactly like the vanilla server list.
                try {
                    long now = System.currentTimeMillis();
                    writeVarInt(out, 9);
                    writeVarInt(out, 0x01);
                    out.writeLong(now);
                    out.flush();
                    readVarInt(in);
                    int pongId = readVarInt(in);
                    if (pongId == 0x01) {
                        long sent = in.readLong();
                        entry.pingMs = Math.max(0, System.currentTimeMillis() - sent);
                    }
                } catch (Exception ignored) {
                    if (entry.pingMs < 0) entry.pingMs = handshakePing;
                }
            }
        }
        entry.lastPingEpoch = System.currentTimeMillis();
        if (entry.pingMs < 0) entry.pingMs = System.currentTimeMillis() - start;
    }

    private static void parseJson(ServerEntry e, String json, long fallbackPing) {
        try {
            JSONObject root = new JSONObject(json);

            JSONObject version = root.optJSONObject("version");
            if (version != null) {
                e.versionName = MotdRenderer.stripLegacy(version.optString("name", ""));
                e.protocol = version.optInt("protocol", -1);
            }
            JSONObject players = root.optJSONObject("players");
            if (players != null) {
                e.playersOnline = players.optInt("online", -1);
                e.playersMax = players.optInt("max", -1);
            }

            Object desc = root.opt("description");
            // RAW is what makes colours possible — never strip it.
            String raw;
            if (desc instanceof JSONObject || desc instanceof JSONArray) raw = desc.toString();
            else if (desc != null) raw = String.valueOf(desc);
            else raw = "";
            e.motdRaw = raw;

            String plain = MotdRenderer.toPlain(raw).trim();
            if (plain.isEmpty()) plain = "A Minecraft Server";
            String[] lines = plain.split("\n");
            if (lines.length > 2) plain = lines[0].trim() + "\n" + lines[1].trim();
            e.motd = plain;

            if (e.name == null || e.name.isEmpty() || e.name.equals(e.address)) {
                String first = lines.length > 0 ? lines[0].trim() : "";
                if (!first.isEmpty() && first.length() < 48) e.name = first;
                else if (e.versionName != null && !e.versionName.isEmpty()) e.name = e.versionName;
                else e.name = e.address;
            }

            String favicon = root.optString("favicon", null);
            if (favicon != null && favicon.startsWith("data:image")) e.iconBase64 = favicon;

            e.online = true;
            if (e.pingMs < 0) e.pingMs = fallbackPing;
        } catch (Exception ex) {
            Log.w(TAG, "status parse failed", ex);
            e.online = true;
            if (e.motd == null || e.motd.isEmpty()) e.motd = "A Minecraft Server";
            e.pingMs = fallbackPing;
        }
        if (e.motd == null || e.motd.isEmpty()) e.motd = "A Minecraft Server";
        if (e.pingMs < 0) e.pingMs = fallbackPing;
    }

    private static void writeVarInt(DataOutputStream out, int v) throws Exception {
        while ((v & 0xFFFFFF80) != 0) { out.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
        out.writeByte(v);
    }

    private static int readVarInt(DataInputStream in) throws Exception {
        int numRead = 0, result = 0; byte read;
        do {
            read = in.readByte();
            result |= (read & 0x7F) << (7 * numRead);
            numRead++;
            if (numRead > 5) throw new RuntimeException("VarInt too big");
        } while ((read & 0x80) != 0);
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b);
    }
}
