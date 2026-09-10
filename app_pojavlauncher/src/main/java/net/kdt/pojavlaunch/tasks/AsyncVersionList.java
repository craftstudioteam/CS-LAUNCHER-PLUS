package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;
import static net.kdt.pojavlaunch.utils.DownloadUtils.downloadString;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/** Class getting the version list, and that's all really */
public class AsyncVersionList {

    /** How many times a forced manifest refresh is attempted before giving up. */
    private static final int FORCE_REFRESH_ATTEMPTS = 2;

    public void getVersionList(@Nullable VersionDoneListener listener, boolean secondPass){
        sExecutorService.execute(() -> {
            File versionFile = new File(Tools.DIR_CACHE + "/version_list.json");
            JMinecraftVersionList versionList = null;
            try{
                if(!versionFile.exists() || (System.currentTimeMillis() > versionFile.lastModified() + 86400000 )){
                    versionList = downloadVersionList(LauncherPreferences.PREF_VERSION_REPOS);
                }
            }catch (Exception e){
                Log.e("AsyncVersionList", "Refreshing version list failed :" + e);
                e.printStackTrace();
            }

            // Fallback when no network or not needed
            if (versionList == null) {
                try {
                    versionList = Tools.GLOBAL_GSON.fromJson(new JsonReader(new FileReader(versionFile)), JMinecraftVersionList.class);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (JsonIOException | JsonSyntaxException e) {
                    e.printStackTrace();
                    versionFile.delete();
                    if(!secondPass)
                        getVersionList(listener, true);
                }
            }

            if(listener != null)
                listener.onVersionDone(versionList);
        });
    }


    /**
     * Synchronously fetch the freshest version manifest straight from Mojang,
     * completely bypassing the 24-hour on-disk cache. Meant to be called from a
     * worker thread when the cached copy provably missed something (a version the
     * user is trying to download is not listed, or the in-memory table was never
     * populated on a cold shortcut start).
     * <p>
     * On success the parsed list is stored into {@link ExtraConstants#RELEASE_TABLE}
     * (so every UI/lookup consumer sees the new data immediately) and the disk cache
     * is rewritten. Any failure simply yields null - callers must fall back to
     * whatever data they already had.
     *
     * @return the freshly downloaded version list, or null if it could not be obtained
     */
    @Nullable
    public static JMinecraftVersionList fetchFreshVersionListBlocking() {
        for (int attempt = 1; attempt <= FORCE_REFRESH_ATTEMPTS; attempt++) {
            try {
                Log.i("AsyncVersionList", "Force-refreshing version manifest, attempt "
                        + attempt + "/" + FORCE_REFRESH_ATTEMPTS);
                String jsonString = downloadString(LauncherPreferences.PREF_VERSION_REPOS);
                JMinecraftVersionList list = Tools.GLOBAL_GSON.fromJson(jsonString, JMinecraftVersionList.class);
                if (list == null || list.versions == null || list.versions.length == 0) {
                    // A truncated/empty document parsed "successfully" - treat as a hard
                    // failure so the retry (or the caller's fallback) takes over.
                    Log.w("AsyncVersionList", "Force-refresh returned an invalid version manifest");
                    continue;
                }
                // Publish in memory first - this is the lookup table used everywhere.
                ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, list);
                // Then persist; a cache-write failure must not fail the refresh itself.
                try (FileOutputStream fos = new FileOutputStream(Tools.DIR_CACHE + "/version_list.json")) {
                    fos.write(jsonString.getBytes());
                } catch (IOException ioe) {
                    Log.w("AsyncVersionList", "Failed to persist the refreshed version list", ioe);
                }
                Log.i("AsyncVersionList", "Version manifest force-refreshed, len=" + list.versions.length);
                return list;
            } catch (IOException | JsonSyntaxException e) {
                Log.w("AsyncVersionList", "Version manifest refresh attempt " + attempt + " failed", e);
            }
        }
        return null;
    }

    @SuppressWarnings("SameParameterValue")
    private JMinecraftVersionList downloadVersionList(String mirror){
        JMinecraftVersionList list = null;
        try{
            Log.i("ExtVL", "Syncing to external: " + mirror);
            String jsonString = downloadString(mirror);
            list = Tools.GLOBAL_GSON.fromJson(jsonString, JMinecraftVersionList.class);
            Log.i("ExtVL","Downloaded the version list, len=" + list.versions.length);

            // Then save the version list
            //TODO make it not save at times ?
            FileOutputStream fos = new FileOutputStream(Tools.DIR_CACHE + "/version_list.json");
            fos.write(jsonString.getBytes());
            fos.close();



        }catch (IOException e){
            Log.e("AsyncVersionList", e.toString());
        }
        return list;
    }

    /** Basic listener, acting as a callback */
    public interface VersionDoneListener{
        void onVersionDone(JMinecraftVersionList versions);
    }

}
