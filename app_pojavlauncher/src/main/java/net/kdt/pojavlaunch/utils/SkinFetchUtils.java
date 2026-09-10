package net.kdt.pojavlaunch.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SkinFetchUtils {

    /** Fetch skin for a username from mc-heads.net and save it locally */
    public static void fetchAndSaveSkin(String username, File destSkinFile) {
        try {
            // mc-heads returns a valid Steve PNG for unknown names. Never cache
            // that fallback as if it were a real account skin; first require a
            // Mojang/Minecraft Services profile match.
            if (!hasOfficialProfile(username)) {
                if (destSkinFile.exists()) destSkinFile.delete();
                Log.i("SkinFetch", "No official profile for " + username + "; keeping default skin");
                return;
            }
            Log.i("SkinFetch", "Fetching verified skin for " + username);
            Tools.downloadFile("https://mc-heads.net/skin/" + username, destSkinFile.getAbsolutePath());
            Bitmap check = BitmapFactory.decodeFile(destSkinFile.getAbsolutePath());
            if (check == null || check.getWidth() < 64) {
                if (check != null) check.recycle();
                destSkinFile.delete();
                return;
            }
            check.recycle();
            Log.i("SkinFetch", "Verified skin saved to " + destSkinFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w("SkinFetch", "Failed to fetch skin for " + username, e);
        }
    }

    private static boolean hasOfficialProfile(String username) {
        String safe;
        try { safe = java.net.URLEncoder.encode(username, "UTF-8"); }
        catch (Exception e) { return false; }
        String[] urls = {
                "https://api.minecraftservices.com/minecraft/profile/lookup/name/" + safe,
                "https://api.mojang.com/users/profiles/minecraft/" + safe
        };
        for (String value : urls) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(value).openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent", "CSLauncher");
                if (connection.getResponseCode() == 200) return true;
            } catch (Throwable ignored) {}
            finally { if (connection != null) connection.disconnect(); }
        }
        return false;
    }

    /** Fetch head for a username and save it to cache */
    public static void fetchAndSaveHead(String username, File destHeadFile) {
        try {
            Log.i("SkinFetch", "Fetching head for " + username);
            Tools.downloadFile("https://mc-heads.net/head/" + username + "/100", destHeadFile.getAbsolutePath());
            Log.i("SkinFetch", "Head saved to " + destHeadFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w("SkinFetch", "Failed to fetch head for " + username, e);
        }
    }
}
