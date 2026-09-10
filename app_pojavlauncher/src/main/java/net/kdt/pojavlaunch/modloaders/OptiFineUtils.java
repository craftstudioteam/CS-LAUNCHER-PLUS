package net.kdt.pojavlaunch.modloaders;

import android.content.Intent;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class OptiFineUtils {

    public static OptiFineVersions downloadOptiFineVersions() throws IOException {
        try {
            OptiFineVersions v = DownloadUtils.downloadStringCached("https://optifine.net/downloads",
                    "of_downloads_page", new OptiFineScraper());
            if (v == null) return null;
            // Phase 10: the list's adloadx links are http:// — Android blocks
            // cleartext on targetSdk 34, so the interstitial fetch failed before
            // it began. Normalise every link once here.
            for (java.util.List<OptiFineVersion> group : v.optifineVersions) {
                if (group == null) continue;
                for (OptiFineVersion ov : group) {
                    if (ov != null && ov.downloadUrl != null && ov.downloadUrl.startsWith("http://"))
                        ov.downloadUrl = "https://" + ov.downloadUrl.substring("http://".length());
                }
            }
            return v;
        }catch (DownloadUtils.ParseException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void addAutoInstallArgs(Intent intent, File modInstallerJar) {
        intent.putExtra("javaArgs", "-javaagent:"+ Tools.DIR_DATA+"/forge_installer/forge_installer.jar"
                + "=OFNPS" +// No Profile Suppression
                " -jar "+modInstallerJar.getAbsolutePath());
    }

    public static class OptiFineVersions {
        public List<String> minecraftVersions;
        public List<List<OptiFineVersion>> optifineVersions;
    }
    public static class OptiFineVersion {
        public String minecraftVersion;
        public String versionName;
        public String downloadUrl;
    }
}
