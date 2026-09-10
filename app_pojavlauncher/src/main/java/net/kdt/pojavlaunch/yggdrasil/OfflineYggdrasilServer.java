package net.kdt.pojavlaunch.yggdrasil;

import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class OfflineYggdrasilServer {
    private static final String TAG = "OfflineSkinServer";
    
    private final String serverName;
    private final String implName;
    private final String implVersion;

    private final Map<String, Character> byUuid = new ConcurrentHashMap<>();
    private final Map<String, Character> byName = new ConcurrentHashMap<>();
    private final Map<String, byte[]> textureStore = new ConcurrentHashMap<>();

    private KeyPair keyPair;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private int port = 0;
    private volatile boolean running = false;

    public OfflineYggdrasilServer() {
        this("CS Launcher", "drasl", "1.4");
    }

    public OfflineYggdrasilServer(String serverName, String implName, String implVersion) {
        this.serverName = serverName;
        this.implName = implName;
        this.implVersion = implVersion;
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            this.keyPair = kpg.generateKeyPair();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate RSA keypair", e);
        }
    }

    public void addCharacter(String username, String profileId, PlayerSkin skin, PlayerCape cape) {
        String uuid = profileId.replace("-", "").toLowerCase();
        Character character = new Character(uuid, username, skin, cape);
        byUuid.put(uuid, character);
        byName.put(username.toLowerCase(), character);
        if (skin != null) {
            textureStore.put(skin.getHash(), skin.getBytes());
            Log.i(TAG, "Stored skin texture: hash=" + skin.getHash() + " size=" + skin.getBytes().length + " model=" + skin.getModel());
        }
        if (cape != null) {
            textureStore.put(cape.getHash(), cape.getBytes());
            Log.i(TAG, "Stored cape texture: hash=" + cape.getHash() + " size=" + cape.getBytes().length);
        }
        Log.i(TAG, "=== CHARACTER ADDED ===");
        Log.i(TAG, "Character Registered");
        Log.i(TAG, "Username: " + username);
        Log.i(TAG, "UUID: " + uuid);
        Log.i(TAG, "Skin Hash: " + (skin != null ? skin.getHash() : "null"));
        Log.i(TAG, "Cape Hash: " + (cape != null ? cape.getHash() : "null"));
    }

    public synchronized int start() {
        if (running) return port;
        try {
            serverSocket = new ServerSocket(0); // Binds to any free port
            port = serverSocket.getLocalPort();
            running = true;
            serverThread = new Thread(this::runServerLoop, "OfflineYggdrasilServerThread");
            serverThread.start();
            Log.i(TAG, "Server Started");
            Log.i(TAG, "Port: " + port);
            return port;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start local Yggdrasil ServerSocket", e);
            return 0;
        }
    }

    public synchronized void stop() {
        if (running) {
            running = false;
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to close ServerSocket", e);
            }
            if (serverThread != null) {
                serverThread.interrupt();
            }
            port = 0;
            Log.i(TAG, "OfflineYggdrasilServer stopped");
        }
    }

    public int getPort() {
        return port;
    }

    private void runServerLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleConnection(socket)).start();
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                Log.e(TAG, "Error accepting connection", e);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (InputStream input = socket.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             OutputStream output = socket.getOutputStream();
             BufferedOutputStream bos = new BufferedOutputStream(output)) {

            String line = reader.readLine();
            if (line == null || line.isEmpty()) return;

            String[] parts = line.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String pathAndQuery = parts[1];
            String path = pathAndQuery;
            int qIdx = pathAndQuery.indexOf('?');
            if (qIdx != -1) {
                path = pathAndQuery.substring(0, qIdx);
            }

            Log.i(TAG, ">>> " + method + " " + path);

            // Consume rest of headers and read content-length
            String headerLine;
            int contentLength = 0;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            // Read request body if present (for POST endpoints)
            String requestBody = null;
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(buf, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                requestBody = new String(buf, 0, totalRead);
            }

            byte[] body = null;
            String contentType = "text/plain; charset=utf-8";
            int statusCode = 404;
            String statusText = "Not Found";

            if (path.equals("/")) {
                body = buildRoot().getBytes(StandardCharsets.UTF_8);
                contentType = "application/json; charset=utf-8";
                statusCode = 200;
                statusText = "OK";
                Log.i(TAG, "<<< 200 / (root metadata)");
            } else if (path.startsWith("/sessionserver/session/minecraft/profile/") || path.startsWith("/session/minecraft/profile/")) {
                String uuid = path.startsWith("/sessionserver/") ? path.substring("/sessionserver/session/minecraft/profile/".length())
                        : path.substring("/session/minecraft/profile/".length());
                uuid = uuid.replace("-", "").toLowerCase();
                Log.i(TAG, "Profile lookup for UUID: " + uuid);
                Log.i(TAG, "Known UUIDs: " + byUuid.keySet());
                Character character = byUuid.get(uuid);
                if (character != null) {
                    String responseJson = character.toProfileResponse(localBase(), this::signRsa);
                    body = responseJson.getBytes(StandardCharsets.UTF_8);
                    contentType = "application/json; charset=utf-8";
                    statusCode = 200;
                    statusText = "OK";
                    Log.i(TAG, "<<< 200 profile found: " + character.name + " / " + character.uuid);
                    Log.i(TAG, "<<< Response: " + responseJson);
                } else {
                    // Unknown player -> proxy to Mojang for premium / plugin skins.
                    String mojangJson = proxyToMojang(uuid);
                    if (mojangJson != null && !mojangJson.isEmpty()) {
                        body = mojangJson.getBytes(StandardCharsets.UTF_8);
                        contentType = "application/json; charset=utf-8";
                        statusCode = 200;
                        statusText = "OK";
                        Log.i(TAG, "<<< 200 proxied Mojang profile for: " + uuid);
                    } else {
                        statusCode = 204;
                        statusText = "No Content";
                        Log.w(TAG, "<<< 204 No profile for UUID: " + uuid);
                    }
                }
            } else if (path.equals("/api/profiles/minecraft")) {
                // POST endpoint: username → profile lookup (authlib-injector uses this)
                if (requestBody != null) {
                    Log.i(TAG, "Profiles lookup request body: " + requestBody);
                    try {
                        JSONArray names = new JSONArray(requestBody);
                        JSONArray result = new JSONArray();
                        for (int i = 0; i < names.length(); i++) {
                            String name = names.getString(i);
                            Character ch = byName.get(name.toLowerCase());
                            if (ch != null) {
                                JSONObject profile = new JSONObject();
                                profile.put("id", ch.uuid);
                                profile.put("name", ch.name);
                                result.put(profile);
                            } else {
                                JSONObject online = resolveOnlineName(name);
                                if (online != null) result.put(online);
                            }
                        }
                        body = result.toString().getBytes(StandardCharsets.UTF_8);
                        contentType = "application/json; charset=utf-8";
                        statusCode = 200;
                        statusText = "OK";
                        Log.i(TAG, "<<< 200 profiles found: " + result.length());
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse profiles request", e);
                        body = "[]".getBytes(StandardCharsets.UTF_8);
                        contentType = "application/json; charset=utf-8";
                        statusCode = 200;
                        statusText = "OK";
                    }
                } else {
                    body = "[]".getBytes(StandardCharsets.UTF_8);
                    contentType = "application/json; charset=utf-8";
                    statusCode = 200;
                    statusText = "OK";
                }
            } else if (path.startsWith("/textures/")) {
                String hash = path.substring("/textures/".length());
                Log.i(TAG, "textureStore.containsKey(" + hash + "): " + textureStore.containsKey(hash));
                byte[] texture = textureStore.get(hash);
                if (texture != null) {
                    body = texture;
                    contentType = "image/png";
                    statusCode = 200;
                    statusText = "OK";
                    Log.i(TAG, "<<< 200 texture: " + hash + " (" + texture.length + " bytes)");
                } else {
                    statusCode = 404;
                    statusText = "Not Found";
                    Log.w(TAG, "<<< 404 texture not found: " + hash);
                    Log.w(TAG, "Known texture hashes: " + textureStore.keySet());
                }
            } else if (path.equals("/sessionserver/session/minecraft/join") || path.equals("/session/minecraft/join")) {
                // Join server endpoint — always accept for offline mode
                statusCode = 204;
                statusText = "No Content";
                body = null;
                Log.i(TAG, "<<< 204 join (accepted)");
            } else if (path.equals("/sessionserver/session/minecraft/hasJoined") || path.equals("/session/minecraft/hasJoined")) {
                // Has-joined endpoint — look up by username from query
                String queryUsername = null;
                if (qIdx != -1) {
                    String query = pathAndQuery.substring(qIdx + 1);
                    for (String param : query.split("&")) {
                        if (param.startsWith("username=")) {
                            queryUsername = param.substring(9);
                        }
                    }
                }
                if (queryUsername != null) {
                    Character ch = byName.get(queryUsername.toLowerCase());
                    if (ch != null) {
                        body = ch.toProfileResponse(localBase(), this::signRsa).getBytes(StandardCharsets.UTF_8);
                        contentType = "application/json; charset=utf-8";
                        statusCode = 200;
                        statusText = "OK";
                        Log.i(TAG, "<<< 200 hasJoined: " + queryUsername);
                    }
                }
                if (body == null) {
                    statusCode = 204;
                    statusText = "No Content";
                    Log.w(TAG, "<<< 204 hasJoined not found: " + queryUsername);
                }
            } else {
                Log.w(TAG, "<<< 404 unknown path: " + path);
            }

            // Write HTTP headers
            String statusLine = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n";
            bos.write(statusLine.getBytes(StandardCharsets.UTF_8));
            bos.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
            bos.write("Connection: close\r\n".getBytes(StandardCharsets.UTF_8));
            if (body != null) {
                bos.write(("Content-Length: " + body.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            } else {
                bos.write("Content-Length: 0\r\n".getBytes(StandardCharsets.UTF_8));
            }
            bos.write("\r\n".getBytes(StandardCharsets.UTF_8));

            // Write body
            if (body != null) {
                bos.write(body);
            }
            bos.flush();

        } catch (Exception e) {
            Log.e(TAG, "Error handling socket request", e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    /**
     * For players we don't recognise (other online players) we proxy the request
     * to Mojang's real session server so premium and server-side skin-plugin
     * textures resolve correctly. Returns null if offline or not found so the
     * caller can fall back to the default Steve/Alex skin.
     */
    private String proxyToMojang(String uuid) {
        String raw = fetchProfileUrl("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
        if (raw == null) raw = fetchProfileUrl("https://authserver.ely.by/session/minecraft/profile/" + uuid + "?unsigned=false");
        if (raw == null) return null;
        String localized = localizeRemoteProfile(raw, uuid);
        return localized != null ? localized : raw;
    }

    /** Download remote textures through Android's network stack, serve them on
     * localhost and sign the rewritten property with this bridge's RSA key. */
    private String localizeRemoteProfile(String raw, String fallbackUuid) {
        try {
            JSONObject profile = new JSONObject(raw);
            String uuid = profile.optString("id", fallbackUuid).replace("-", "").toLowerCase();
            String name = profile.optString("name", "Player");
            JSONArray props = profile.optJSONArray("properties");
            if (props == null) return null;
            String encoded = null;
            for (int i = 0; i < props.length(); i++) {
                JSONObject p = props.optJSONObject(i);
                if (p != null && "textures".equals(p.optString("name"))) { encoded = p.optString("value", null); break; }
            }
            if (encoded == null) return null;
            JSONObject textureRoot = new JSONObject(new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8));
            JSONObject textures = textureRoot.optJSONObject("textures");
            PlayerSkin skin = null; PlayerCape cape = null;
            if (textures != null) {
                JSONObject s = textures.optJSONObject("SKIN");
                if (s != null) {
                    byte[] bytes = downloadTexture(s.optString("url", ""));
                    if (bytes != null) {
                        String hash = textureHash(s.optString("url", ""), bytes);
                        JSONObject meta = s.optJSONObject("metadata");
                        boolean slim = meta != null && "slim".equalsIgnoreCase(meta.optString("model"));
                        skin = new PlayerSkin(bytes, hash, slim ? SkinModelType.ALEX : SkinModelType.STEVE);
                        textureStore.put(hash, bytes);
                    }
                }
                JSONObject c = textures.optJSONObject("CAPE");
                if (c != null) {
                    byte[] bytes = downloadTexture(c.optString("url", ""));
                    if (bytes != null) {
                        String hash = textureHash(c.optString("url", ""), bytes);
                        cape = new PlayerCape(bytes, hash); textureStore.put(hash, bytes);
                    }
                }
            }
            if (skin == null && cape == null) return null;
            return new Character(uuid, name, skin, cape).toProfileResponse(localBase(), this::signRsa);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to localize remote skin profile", t);
            return null;
        }
    }

    private byte[] downloadTexture(String url) {
        if (url == null || !url.startsWith("http")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL(url.replace("http://textures.minecraft.net/", "https://textures.minecraft.net/")).openConnection();
            c.setConnectTimeout(7000); c.setReadTimeout(10000); c.setRequestProperty("User-Agent", "CSLauncher-SkinBridge");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n; int total = 0;
                while ((n = in.read(buf)) != -1) { total += n; if (total > 4 * 1024 * 1024) return null; out.write(buf, 0, n); }
                return out.toByteArray();
            }
        } catch (Throwable t) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    private String textureHash(String url, byte[] bytes) {
        try {
            String path = new URL(url).getPath(); String last = path.substring(path.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return last;
        } catch (Throwable ignored) {}
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes); StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format(java.util.Locale.US, "%02x", b));
            return s.toString();
        } catch (Throwable t) { return Integer.toHexString(java.util.Arrays.hashCode(bytes)); }
    }

    private JSONObject resolveOnlineName(String name) {
        try {
            String safe = java.net.URLEncoder.encode(name, "UTF-8");
            String raw = fetchProfileUrl("https://api.mojang.com/users/profiles/minecraft/" + safe);
            if (raw == null) return null;
            JSONObject source = new JSONObject(raw);
            String id = source.optString("id", "");
            if (id.isEmpty()) return null;
            JSONObject out = new JSONObject(); out.put("id", id); out.put("name", source.optString("name", name));
            return out;
        } catch (Throwable t) { return null; }
    }

    private String fetchProfileUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            conn.setRequestProperty("User-Agent", "CSLauncher");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String signRsa(String data) {
        try {
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(keyPair.getPrivate());
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(sig.sign(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "RSA sign failed", e);
            return "";
        }
    }

    private String localBase() {
        return "http://127.0.0.1:" + port;
    }

    private String buildRoot() {
        try {
            JSONObject root = new JSONObject();
            JSONArray skinDomains = new JSONArray();
            skinDomains.put("127.0.0.1");
            skinDomains.put("localhost");
            root.put("skinDomains", skinDomains);

            JSONObject meta = new JSONObject();
            meta.put("serverName", serverName);
            meta.put("implementationName", implName);
            meta.put("implementationVersion", implVersion);
            meta.put("feature.non_email_login", true);
            meta.put("feature.legacy_skin_api", true);
            root.put("meta", meta);

            String publicKeyBase64 = Base64.encodeToString(keyPair.getPublic().getEncoded(), Base64.DEFAULT).trim();
            root.put("signaturePublickey", "-----BEGIN PUBLIC KEY-----\n" + publicKeyBase64 + "\n-----END PUBLIC KEY-----");

            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static class Character {
        final String uuid;
        final String name;
        final PlayerSkin skin;
        final PlayerCape cape;

        Character(String uuid, String name, PlayerSkin skin, PlayerCape cape) {
            this.uuid = uuid;
            this.name = name;
            this.skin = skin;
            this.cape = cape;
        }

        String toProfileResponse(String baseUrl, Signer signer) {
            try {
                JSONObject texturesObj = new JSONObject();
                texturesObj.put("timestamp", System.currentTimeMillis());
                texturesObj.put("profileId", uuid);
                texturesObj.put("profileName", name);
                texturesObj.put("signatureRequired", true);
                
                JSONObject textures = new JSONObject();
                if (skin != null) {
                    JSONObject skinObj = new JSONObject();
                    skinObj.put("url", baseUrl + "/textures/" + skin.getHash());
                    if (skin.getModel() == SkinModelType.ALEX) {
                        JSONObject metadata = new JSONObject();
                        metadata.put("model", "slim");
                        skinObj.put("metadata", metadata);
                    }
                    textures.put("SKIN", skinObj);
                }
                if (cape != null) {
                    JSONObject capeObj = new JSONObject();
                    capeObj.put("url", baseUrl + "/textures/" + cape.getHash());
                    textures.put("CAPE", capeObj);
                }
                texturesObj.put("textures", textures);

                String texturesJson = texturesObj.toString();
                Log.i(TAG, "Decoded textures property: " + texturesJson);
                String encoded = Base64.encodeToString(texturesJson.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String signature = signer.sign(encoded);

                JSONObject response = new JSONObject();
                response.put("id", uuid);
                response.put("name", name);
                
                JSONArray properties = new JSONArray();
                JSONObject texturesProp = new JSONObject();
                texturesProp.put("name", "textures");
                texturesProp.put("value", encoded);
                texturesProp.put("signature", signature);
                properties.put(texturesProp);
                
                response.put("properties", properties);
                return response.toString();
            } catch (Exception e) {
                Log.e(TAG, "Failed to build profile response", e);
                return "{}";
            }
        }
    }

    private interface Signer {
        String sign(String data);
    }
}
