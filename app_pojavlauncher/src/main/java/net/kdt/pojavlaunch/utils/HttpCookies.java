package net.kdt.pojavlaunch.utils;

import android.util.Log;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny cookie jar for the plain {@link HttpURLConnection} stack.
 *
 * This is not a nicety: optifine.net hands out a {@code JSESSIONID} on the
 * interstitial page and then refuses to serve the actual file ("File not
 * found.", 16 bytes) to a request that does not carry that session back. With
 * no cookie store, the OptiFine download could never finish — and the launcher
 * used to save the 16-byte error page as the installer jar, which then showed
 * up as a broken Java launch several screens later.
 *
 * Cookies are kept per host for the life of the process and never persisted:
 * these sessions are short-lived and single-purpose.
 */
public final class HttpCookies {
    private static final String TAG = "HttpCookies";
    private static final Map<String, Map<String, String>> sByHost = new ConcurrentHashMap<>();

    private HttpCookies() {}

    public static String hostOf(String url) {
        int p = url.indexOf("://");
        if (p < 0) return url;
        int s = p + 3, e = s;
        while (e < url.length() && url.charAt(e) != '/' && url.charAt(e) != '?') e++;
        return url.substring(s, e).toLowerCase(java.util.Locale.US);
    }

    /** Header value for the request, or null when nothing is known for this host. */
    public static String headerFor(String url) {
        Map<String, String> jar = sByHost.get(hostOf(url));
        if (jar == null || jar.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Absorb every Set-Cookie header of a response into the jar for that host. */
    public static void store(String url, HttpURLConnection conn) {
        if (conn == null) return;
        try {
            List<String> headers = conn.getHeaderFields() == null
                    ? null : conn.getHeaderFields().get("Set-Cookie");
            if (headers == null) {
                // The field name is case-sensitive in some implementations.
                Map<String, List<String>> all = conn.getHeaderFields();
                if (all != null) {
                    headers = new ArrayList<>();
                    for (Map.Entry<String, List<String>> e : all.entrySet()) {
                        if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                            headers.addAll(e.getValue());
                        }
                    }
                }
            }
            if (headers == null || headers.isEmpty()) return;
            String host = hostOf(url);
            Map<String, String> jar = sByHost.computeIfAbsent(host,
                    k -> new HashMap<String, String>());
            synchronized (jar) {
                for (String h : headers) {
                    if (h == null || h.isEmpty()) continue;
                    String pair = h.split(";", 2)[0].trim();
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (value.isEmpty() || "null".equalsIgnoreCase(value)
                            || "deleted".equalsIgnoreCase(value)) {
                        jar.remove(name);
                    } else {
                        jar.put(name, value);
                    }
                }
            }
        } catch (Throwable t) {
            // A cookie store must never be the reason a download fails.
            Log.d(TAG, "Could not store cookies for " + url, t);
        }
    }

    /** Attach our stored cookies (without clobbering a caller-supplied header). */
    public static void apply(String url, HttpURLConnection conn) {
        if (conn == null) return;
        if (conn.getRequestProperty("Cookie") != null) return;
        String header = headerFor(url);
        if (header != null) conn.setRequestProperty("Cookie", header);
    }

    public static void clear(String url) {
        sByHost.remove(hostOf(url));
    }
}
