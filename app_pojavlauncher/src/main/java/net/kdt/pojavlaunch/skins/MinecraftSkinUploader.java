package net.kdt.pojavlaunch.skins;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Authenticated Minecraft Services skin upload for premium Microsoft accounts. */
public final class MinecraftSkinUploader {
    private MinecraftSkinUploader() {}

    public static void upload(@NonNull MinecraftAccount account, @NonNull File skin,
                              boolean slim) throws Exception {
        if (!account.isMicrosoft || account.accessToken == null || "0".equals(account.accessToken)) {
            throw new IllegalStateException("A valid Microsoft Minecraft session is required");
        }
        String boundary = "----CSLauncherSkin" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://api.minecraftservices.com/minecraft/profile/skins").openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        c.setRequestProperty("Authorization", "Bearer " + account.accessToken);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "CSLauncher/" + BuildConfig.VERSION_NAME);
        try (OutputStream out = c.getOutputStream()) {
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                    + (slim ? "slim" : "classic") + "\r\n");
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n");
            try (java.io.FileInputStream in = new java.io.FileInputStream(skin)) {
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            write(out, "\r\n--" + boundary + "--\r\n");
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            StringBuilder response = new StringBuilder();
            if (c.getErrorStream() != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) response.append(line);
                }
            }
            throw new Exception("Minecraft skin sync failed (HTTP " + code + ") " + response);
        }
        c.disconnect();
    }

    private static void write(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
