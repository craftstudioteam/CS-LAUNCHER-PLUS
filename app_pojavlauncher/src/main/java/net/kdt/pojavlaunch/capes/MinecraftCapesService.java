package net.kdt.pojavlaunch.capes;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service client communicating with Minecraft capes gallery and texture endpoints.
 * Supports configurable endpoints, pagination, search queries, and robust error handling.
 */
public class MinecraftCapesService {
    private static final String TAG = "MinecraftCapesService";

    public static final String DEFAULT_GALLERY_ENDPOINT = "https://api.minecraftcapes.net/gallery";
    public static final String USER_AGENT = "CS-Launcher-Plus/3.1 (Android; Minecraft Java Edition)";

    private static final int TIMEOUT_CONNECT_MS = 8000;
    private static final int TIMEOUT_READ_MS = 12000;

    private static MinecraftCapesService sInstance;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(4);

    public interface GalleryCallback {
        void onSuccess(List<CapeItem> capes, int totalPages);
        void onError(Exception error);
    }

    public interface TextureCallback {
        void onSuccess(Bitmap texture, byte[] rawBytes);
        void onError(Exception error);
    }

    public static synchronized MinecraftCapesService getInstance() {
        if (sInstance == null) {
            sInstance = new MinecraftCapesService();
        }
        return sInstance;
    }

    private MinecraftCapesService() {}

    /**
     * Search the MinecraftCapes gallery dynamically.
     */
    public void searchGalleryAsync(@Nullable String query, @Nullable String category, int page, @NonNull GalleryCallback callback) {
        mExecutor.execute(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder(DEFAULT_GALLERY_ENDPOINT);
                urlBuilder.append("?page=").append(Math.max(1, page));
                urlBuilder.append("&limit=18");

                if (category != null && !category.isEmpty() && !CapeItem.CATEGORY_ALL.equalsIgnoreCase(category)) {
                    urlBuilder.append("&category=").append(URLEncoder.encode(category, "UTF-8"));
                }
                if (query != null && !query.trim().isEmpty()) {
                    urlBuilder.append("&search=").append(URLEncoder.encode(query.trim(), "UTF-8"));
                }

                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlBuilder.toString());
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
                    conn.setReadTimeout(TIMEOUT_READ_MS);
                    conn.setInstanceFollowRedirects(true);

                    int responseCode = conn.getResponseCode();
                    if (responseCode >= 200 && responseCode < 300) {
                        String jsonStr = readStream(conn.getInputStream());
                        List<CapeItem> items = parseGalleryJson(jsonStr);
                        callback.onSuccess(items, items.size() >= 18 ? page + 1 : page);
                        return;
                    }
                } catch (Exception httpEx) {
                    Log.w(TAG, "Online gallery query fell back: " + httpEx.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }

                // If online gallery fails/offline, filter curated items as fallback
                List<CapeItem> fallback = CapeRepository.getInstance().getCuratedCapes();
                List<CapeItem> filtered = new ArrayList<>();
                String q = query != null ? query.toLowerCase().trim() : "";
                for (CapeItem item : fallback) {
                    if (q.isEmpty() || item.getName().toLowerCase().contains(q) || item.getCategory().toLowerCase().contains(q)) {
                        filtered.add(item);
                    }
                }
                callback.onSuccess(filtered, 1);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    /**
     * Download a cape texture bitmap and raw bytes.
     */
    public void downloadTexture(@NonNull String textureUrl, @NonNull TextureCallback callback) {
        mExecutor.execute(() -> {
            try {
                byte[] bytes = downloadBytes(textureUrl);
                if (bytes == null || bytes.length == 0) {
                    callback.onError(new Exception("Empty texture data received"));
                    return;
                }

                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) {
                    callback.onError(new Exception("Failed to decode cape PNG bitmap"));
                    return;
                }

                Bitmap normalized = normalizeCapeDimensions(bitmap);
                byte[] finalBytes = bytes;
                if (normalized != bitmap) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    normalized.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    finalBytes = bos.toByteArray();
                }

                callback.onSuccess(normalized, finalBytes);
            } catch (Exception e) {
                callback.onError(e);
            }
        });
    }

    public static Bitmap normalizeCapeDimensions(Bitmap src) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();

        // Standard 64x32 or 64x64 or HD multiples
        if ((w == 64 && (h == 32 || h == 64)) || (w == 128 && (h == 64 || h == 128))) {
            return src;
        }

        return Bitmap.createScaledBitmap(src, 64, 32, false);
    }

    private byte[] downloadBytes(String urlStr) throws Exception {
        if (urlStr.startsWith("data:image")) {
            int comma = urlStr.indexOf(",");
            if (comma != -1) {
                String b64 = urlStr.substring(comma + 1);
                return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            }
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
        conn.setReadTimeout(TIMEOUT_READ_MS);
        conn.setInstanceFollowRedirects(true);

        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        return out.toString("UTF-8");
    }

    private List<CapeItem> parseGalleryJson(String jsonStr) {
        List<CapeItem> list = new ArrayList<>();
        try {
            JSONArray arr;
            if (jsonStr.trim().startsWith("[")) {
                arr = new JSONArray(jsonStr);
            } else {
                JSONObject obj = new JSONObject(jsonStr);
                arr = obj.optJSONArray("capes");
                if (arr == null) arr = obj.optJSONArray("data");
                if (arr == null) arr = obj.optJSONArray("results");
            }

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item == null) continue;
                    CapeItem parsed = CapeItem.fromJsonObject(item);
                    if (parsed != null && !parsed.getId().isEmpty()) {
                        list.add(parsed);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing gallery JSON: " + e.getMessage());
        }
        return list;
    }
}
