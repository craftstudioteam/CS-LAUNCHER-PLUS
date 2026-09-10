package net.kdt.pojavlaunch.modloaders;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Real update check for an installed mod jar.
 *
 * The jar's SHA-1 hash is sent to Modrinth's version_files endpoint, which
 * identifies the exact published file. The owning project's versions are then
 * filtered by the profile's Minecraft version and loader; if a file was
 * published after the installed one, the caller gets its download URL.
 *
 * No guessing from file names and no fake data — if Modrinth does not know
 * the file, {@link Result#state} is {@link State#NOT_FOUND}.
 */
public final class ModUpdateChecker {

    private static final String TAG = "ModUpdateChecker";
    private static final String API = "https://api.modrinth.com/v2";
    private static final int TIMEOUT_MS = 15_000;

    public enum State { UPDATE_AVAILABLE, UP_TO_DATE, NOT_FOUND, ERROR }

    public static final class Result {
        public final State state;
        public final String installedVersion;
        public final String latestVersion;
        public final String downloadUrl;
        public final String fileName;
        public final String projectId;

        Result(State state, String installedVersion, String latestVersion,
               String downloadUrl, String fileName, String projectId) {
            this.state = state;
            this.installedVersion = installedVersion;
            this.latestVersion = latestVersion;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.projectId = projectId;
        }

        static Result of(State s) { return new Result(s, null, null, null, null, null); }
    }

    public interface Callback {
        void onResult(Result result);
    }

    private ModUpdateChecker() {}

    /**
     * @param modFile   the installed jar (".jar" or ".jar.disabled")
     * @param mcVersion the profile's Minecraft version, e.g. "1.20.1"
     * @param loader    one of fabric / forge / quilt / neoforge, may be null
     */
    public static void check(File modFile, String mcVersion, String loader, Callback cb) {
        try {
            String sha1 = sha1Of(modFile);
            if (sha1 == null) { cb.onResult(Result.of(State.ERROR)); return; }

            // ── 1. Identify the exact published file by its hash
            String body = "{\"hashes\":[\"" + sha1 + "\"],\"algorithm\":\"sha1\"}";
            String raw = post(API + "/version_files", body);
            if (raw == null) { cb.onResult(Result.of(State.NOT_FOUND)); return; }

            JsonObject map = JsonParser.parseString(raw).getAsJsonObject();
            JsonElement hit = map.get(sha1);
            if (hit == null || hit.isJsonNull() || !hit.isJsonObject()) {
                // Modrinth does not know this exact file → not a Modrinth mod.
                cb.onResult(Result.of(State.NOT_FOUND));
                return;
            }
            JsonObject current = hit.getAsJsonObject();
            String projectId = str(current, "project_id");
            String currentVersion = firstNonEmpty(str(current, "version_number"),
                    str(current, "name"), modFile.getName());
            String currentDate = str(current, "date_published");
            if (projectId == null) { cb.onResult(Result.of(State.NOT_FOUND)); return; }

            // ── 2. List that project's files for this MC version / loader
            StringBuilder url = new StringBuilder(API)
                    .append("/project/").append(projectId).append("/version");
            boolean first = true;
            if (mcVersion != null && !mcVersion.isEmpty()) {
                url.append(first ? "?" : "&").append("game_versions=[\"")
                        .append(esc(mcVersion)).append("\"]");
                first = false;
            }
            String ld = normaliseLoader(loader);
            if (ld != null) {
                url.append(first ? "?" : "&").append("loaders=[\"").append(esc(ld)).append("\"]");
            }
            String listRaw = get(url.toString());
            if (listRaw == null) { cb.onResult(Result.of(State.UP_TO_DATE)); return; }

            JsonArray versions = JsonParser.parseString(listRaw).getAsJsonArray();
            JsonObject newest = null;
            String newestDate = null;
            for (JsonElement e : versions) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                String date = str(v, "date_published");
                if (date == null) continue;
                if (newestDate == null || date.compareTo(newestDate) > 0) {
                    newestDate = date;
                    newest = v;
                }
            }
            if (newest == null) { cb.onResult(Result.of(State.UP_TO_DATE)); return; }

            boolean newer = currentDate == null || newestDate.compareTo(currentDate) > 0;
            String primary = primaryFile(newest);
            String primaryName = primaryFileName(newest);
            if (!newer || primary == null) {
                cb.onResult(new Result(State.UP_TO_DATE, currentVersion,
                        firstNonEmpty(str(newest, "version_number"), str(newest, "name"), ""),
                        null, null, projectId));
                return;
            }
            cb.onResult(new Result(State.UPDATE_AVAILABLE, currentVersion,
                    firstNonEmpty(str(newest, "version_number"), str(newest, "name"), ""),
                    primary, primaryName, projectId));

        } catch (Throwable t) {
            Log.w(TAG, "Update check failed", t);
            cb.onResult(Result.of(State.ERROR));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String primaryFile(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null) return null;
        JsonObject fallback = null;
        for (JsonElement e : files) {
            if (!e.isJsonObject()) continue;
            JsonObject f = e.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) return str(f, "url");
            if (fallback == null) fallback = f;
        }
        return fallback == null ? null : str(fallback, "url");
    }

    private static String primaryFileName(JsonObject version) {
        JsonArray files = version.getAsJsonArray("files");
        if (files == null) return null;
        JsonObject fallback = null;
        for (JsonElement e : files) {
            if (!e.isJsonObject()) continue;
            JsonObject f = e.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) return str(f, "filename");
            if (fallback == null) fallback = f;
        }
        return fallback == null ? null : str(fallback, "filename");
    }

    private static String normaliseLoader(String loader) {
        if (loader == null) return null;
        String l = loader.toLowerCase(java.util.Locale.US);
        if (l.contains("fabric")) return "fabric";
        if (l.contains("quilt")) return "quilt";
        if (l.contains("neoforge")) return "neoforge";
        if (l.contains("forge")) return "forge";
        return null;
    }

    private static String sha1Of(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "Could not hash " + f, t);
            return null;
        }
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "CSLauncherPlus/1.0");
            if (c.getResponseCode() != 200) return null;
            return read(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String post(String url, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "CSLauncherPlus/1.0");
            try (OutputStream os = c.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) return null;
            return read(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static String firstNonEmpty(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.trim().isEmpty()) return s;
        }
        return "";
    }

    private static String esc(String s) {
        return s.replace("\"", "").replace("\\", "");
    }
}
