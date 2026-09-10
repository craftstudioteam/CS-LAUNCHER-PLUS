package net.kdt.pojavlaunch.modloaders.modpacks.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.ZipUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.zip.ZipFile;

public class ModpackInstaller {

    public static ModLoader installModpack(ModDetail modDetail, int selectedVersion, InstallFunction installFunction) throws IOException {
        String versionUrl = modDetail.versionUrls[selectedVersion];
        String versionHash = modDetail.versionHashes[selectedVersion];
        String modpackName = (modDetail.title.toLowerCase(Locale.ROOT) + " " + modDetail.versionNames[selectedVersion])
                .trim().replaceAll("[\\\\/:*?\"<>| \\t\\n]", "_" );
        if (versionHash != null) {
            modpackName += "_" + versionHash;
        }
        if (modpackName.length() > 255){
            modpackName = modpackName.substring(0,255);
        }

        // Build a new minecraft instance, folder first

        // Get the modpack file
        File modpackFile = new File(Tools.DIR_CACHE, modpackName + ".cf"); // Cache File
        ModLoader modLoaderInfo;
        try {
            byte[] downloadBuffer = new byte[8192];
            DownloadUtils.ensureSha1(modpackFile, versionHash, (Callable<Void>) () -> {
                DownloadUtils.downloadFileMonitored(versionUrl, modpackFile, downloadBuffer,
                        new DownloaderProgressWrapper(R.string.modpack_download_downloading_metadata,
                                ProgressLayout.INSTALL_MODPACK));
                return null;
            });

            // Install the modpack
            modLoaderInfo = installFunction.installModpack(modpackFile, new File(Tools.DIR_GAME_HOME, "custom_instances/"+modpackName));

        } finally {
            modpackFile.delete();
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
        if(modLoaderInfo == null) {
            return null;
        }

        // Create the instance
        MinecraftProfile profile = new MinecraftProfile();
        profile.gameDir = "./custom_instances/" + modpackName;
        profile.name = modDetail.title;
        profile.lastVersionId = modLoaderInfo.getVersionId();
        profile.icon = ModIconCache.getBase64Image(modDetail.getIconCacheTag());


        LauncherProfiles.mainProfileJson.profiles.put(modpackName, profile);
        LauncherProfiles.write();
        
        net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.edit()
                .putString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, modpackName).apply();
        LauncherProfiles.load();

        return modLoaderInfo;
    }

    public static ModLoader importModpack(File modpackFile, int apiSource, InstallFunction installFunction) throws IOException, NoSuchAlgorithmException {
        // O3 (CS integration): the caller (LauncherActivity) already streamed the
        // DocumentsUI content into a local cache file, so everything below is
        // plain fast file I/O — no repeated content-provider opens, and the
        // hash pass now reports real progress.
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 1, R.string.import_modpack_start);
        if (modpackFile == null) throw new IOException("Can't open modpack file, try again?");
        String manifestFileName;
        switch (apiSource) {
            case Constants.SOURCE_CURSEFORGE:
                manifestFileName = "manifest.json";
                break;
            case Constants.SOURCE_MODRINTH:
                manifestFileName = "modrinth.index.json";
                break;
            default:
                throw new UnsupportedOperationException("Unknown API source: " + apiSource);
        }
        // Read Manifest JSON (ZipFile random access — instant vs streaming the
        // whole archive through a ZipInputStream looking for one entry)
        JsonObject manifestFile = JsonParser.parseString(Tools.read(ZipUtils.getEntryStream(
                    new ZipFile(modpackFile), manifestFileName))).getAsJsonObject();

        // Parse the JSON to prepare for instance creation
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 1, R.string.import_modpack_json);
        String modpackName = "";
        String modpackVersion = "";
        String modpackMcVersion = "";

        switch (apiSource) {
            case Constants.SOURCE_CURSEFORGE:
                try {
                    modpackName = manifestFile.get("name").getAsString();
                    modpackVersion = manifestFile.get("version").getAsString();
                    modpackMcVersion = manifestFile.get("minecraft").getAsJsonObject().get("version").getAsString();
                } catch (RuntimeException ignored) {}
                break;
            case Constants.SOURCE_MODRINTH:
                try {
                    modpackName = manifestFile.get("name").getAsString();
                    modpackVersion = manifestFile.get("versionId").getAsString();
                    modpackMcVersion = manifestFile.get("dependencies").getAsJsonObject().get("minecraft").getAsString();
                } catch (RuntimeException ignored) {}
                break;
            default:
                throw new UnsupportedOperationException("Unknown API source: " + apiSource);
        }
        if(modpackName.isBlank() || modpackVersion.isBlank() || modpackMcVersion.isBlank()) throw new IOException("Corrupt Modpack manifest file.");

        // Hash the ZIP File, can't use getSha1 cause progress bar
        MessageDigest algorithm = MessageDigest.getInstance("SHA-1");
        DigestInputStream hashingStream = new DigestInputStream(new FileInputStream(modpackFile), algorithm);
        long fileSize = modpackFile.length();
        long readSize = 0;
        byte[] buffer = new byte[262144];
        while (true) {
            int n = hashingStream.read(buffer);
            if (n == -1) break;
            readSize += n;
            String readMB = fileSize > 0 ? String.format(Locale.US, "%.2f", readSize / (1024.0 * 1024.0)) : "unknown";
            String totalMB = fileSize > 0 ? String.format(Locale.US, "%.2f",fileSize / (1024.0 * 1024.0)) : "unknown";
            int progress = fileSize > 0 ? (int) ((readSize * 100L) / fileSize) : 0;
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, progress, R.string.import_modpack_hash, readMB, totalMB);
        }
        hashingStream.close();
        byte[] digest = algorithm.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        String hash = sb.toString();
        // Keep CSL's profile-folder naming convention (name+version+"for"+mc+hash)
        String profileFolderName = (modpackName.toLowerCase(Locale.ROOT) +
                modpackVersion + "for" +
                modpackMcVersion);
        profileFolderName = profileFolderName.trim().replaceAll("[\\\\/:*?\"<>| \t\n]", "_");
        profileFolderName = profileFolderName + hash;

        // Install the actual pack into custom_instances
        ModLoader modLoaderInfo = installFunction.installModpack(modpackFile, new File(Tools.DIR_GAME_HOME, "custom_instances/"+profileFolderName));
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);

        if(modLoaderInfo == null) {
            return null;
        }

        // Create the instance (We don't have a picture guys)
        MinecraftProfile profile = new MinecraftProfile();
        profile.gameDir = "./custom_instances/" + profileFolderName;
        profile.name = modpackName;
        profile.lastVersionId = modLoaderInfo.getVersionId();
        LauncherProfiles.mainProfileJson.profiles.put(profileFolderName, profile);
        LauncherProfiles.write();

        net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.edit()
                .putString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileFolderName).apply();
        LauncherProfiles.load();

        return modLoaderInfo;
    }

interface InstallFunction {
        ModLoader installModpack(File modpackFile, File instanceDestination) throws IOException;
    }

    /**
     * Profile-based download installation: saves directly to the profile's
     * gameDir subfolder (mods/, resourcepacks/, shaderpacks/) instead of
     * creating a new custom_instances/ profile.
     */
    public static void installToProfileFolder(File file, File profileGameDir, String targetFolder) throws IOException {
        File destDir = new File(profileGameDir, targetFolder);
        if (!destDir.exists()) destDir.mkdirs();
        File destFile = new File(destDir, file.getName());
        try (java.io.InputStream is = new java.io.FileInputStream(file);
             java.io.FileOutputStream os = new java.io.FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
        }
    }
}
