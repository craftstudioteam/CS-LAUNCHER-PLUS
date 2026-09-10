package net.kdt.pojavlaunch.servers;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Minimal DNS SRV resolver for {@code _minecraft._tcp.<host>}.
 *
 * <p>The vanilla client resolves this record before connecting, which is why a huge number of
 * public servers (play.example.net → node12.host.net:25577) answer in Minecraft but look
 * "offline" to a launcher that only does a plain A-record connect. The hub used to be one of
 * those launchers; now it follows the same rule as the game.
 *
 * <p>No third-party DNS library is used — a single UDP query is built and parsed by hand.
 */
public final class SrvResolver {
    private static final String TAG = "SrvResolver";
    private static final String[] DNS_SERVERS = {"1.1.1.1", "8.8.8.8", "9.9.9.9"};
    private static final int TIMEOUT_MS = 2500;

    private SrvResolver() {}

    public static final class Result {
        public final String host;
        public final int port;
        Result(String host, int port) { this.host = host; this.port = port; }
    }

    /** @return resolved target, or null when there is no SRV record / lookup failed. */
    public static Result resolve(String domain) {
        if (domain == null || domain.isEmpty()) return null;
        if (isIpLiteral(domain)) return null;
        String query = "_minecraft._tcp." + domain;
        for (String dns : DNS_SERVERS) {
            try {
                Result r = query(dns, query);
                if (r != null) return r;
            } catch (Exception e) {
                Log.d(TAG, "SRV lookup via " + dns + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    private static boolean isIpLiteral(String host) {
        if (host.contains(":")) return true; // IPv6
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        for (String p : parts) {
            try { int v = Integer.parseInt(p); if (v < 0 || v > 255) return false; }
            catch (NumberFormatException e) { return false; }
        }
        return true;
    }

    private static Result query(String dnsServer, String name) throws Exception {
        byte[] request = buildQuery(name);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            InetAddress addr = InetAddress.getByName(dnsServer);
            socket.send(new DatagramPacket(request, request.length, addr, 53));
            byte[] buf = new byte[1024];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);
            return parse(buf, response.getLength());
        }
    }

    private static byte[] buildQuery(String name) {
        ByteBuffer b = ByteBuffer.allocate(512);
        int id = new Random().nextInt(0xFFFF);
        b.putShort((short) id);
        b.putShort((short) 0x0100); // standard query, recursion desired
        b.putShort((short) 1);      // qdcount
        b.putShort((short) 0);      // ancount
        b.putShort((short) 0);      // nscount
        b.putShort((short) 0);      // arcount
        for (String label : name.split("\\.")) {
            byte[] raw = label.getBytes();
            if (raw.length == 0 || raw.length > 63) continue;
            b.put((byte) raw.length);
            b.put(raw);
        }
        b.put((byte) 0);
        b.putShort((short) 33); // SRV
        b.putShort((short) 1);  // IN
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static Result parse(byte[] data, int length) {
        ByteBuffer b = ByteBuffer.wrap(data, 0, length);
        b.getShort();                       // id
        int flags = b.getShort() & 0xFFFF;
        if ((flags & 0x000F) != 0) return null; // rcode != 0
        int qd = b.getShort() & 0xFFFF;
        int an = b.getShort() & 0xFFFF;
        b.getShort(); b.getShort();

        for (int i = 0; i < qd; i++) { skipName(b); b.getShort(); b.getShort(); }

        Result best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (int i = 0; i < an; i++) {
            skipName(b);
            int type = b.getShort() & 0xFFFF;
            b.getShort();                  // class
            b.getInt();                    // ttl
            int rdLength = b.getShort() & 0xFFFF;
            int end = b.position() + rdLength;
            if (type == 33 && rdLength >= 7) {
                int priority = b.getShort() & 0xFFFF;
                b.getShort();              // weight
                int port = b.getShort() & 0xFFFF;
                String target = readName(b, data);
                if (target != null && !target.isEmpty() && priority < bestPriority) {
                    if (target.endsWith(".")) target = target.substring(0, target.length() - 1);
                    best = new Result(target, port);
                    bestPriority = priority;
                }
            }
            b.position(Math.min(end, b.limit()));
        }
        return best;
    }

    private static void skipName(ByteBuffer b) {
        while (b.hasRemaining()) {
            int len = b.get() & 0xFF;
            if (len == 0) return;
            if ((len & 0xC0) == 0xC0) { b.get(); return; } // compression pointer
            b.position(Math.min(b.position() + len, b.limit()));
        }
    }

    private static String readName(ByteBuffer b, byte[] full) {
        StringBuilder sb = new StringBuilder();
        int guard = 0;
        while (b.hasRemaining() && guard++ < 128) {
            int len = b.get() & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) {
                int offset = ((len & 0x3F) << 8) | (b.get() & 0xFF);
                sb.append(readNameAt(full, offset, 0));
                return sb.toString();
            }
            byte[] label = new byte[len];
            b.get(label);
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(label));
        }
        return sb.toString();
    }

    private static String readNameAt(byte[] full, int offset, int depth) {
        if (depth > 8 || offset < 0 || offset >= full.length) return "";
        StringBuilder sb = new StringBuilder();
        int i = offset;
        int guard = 0;
        while (i < full.length && guard++ < 128) {
            int len = full[i] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) {
                int ptr = ((len & 0x3F) << 8) | (full[i + 1] & 0xFF);
                sb.append(readNameAt(full, ptr, depth + 1));
                return sb.toString();
            }
            i++;
            if (i + len > full.length) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(full, i, len));
            i += len;
        }
        return sb.toString();
    }
}
