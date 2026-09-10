package net.kdt.pojavlaunch.modloaders;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class FabriclikeDownloadTask implements Runnable, Tools.DownloaderFeedback{
    private final ModloaderDownloadListener mModloaderDownloadListener;
    private final FabriclikeUtils mUtils;
    private final String mGameVersion;
    private final String mLoaderVersion;
    private final boolean mCreateProfile;
    private final String mProfileName;
    private final String mProfileIcon;
    private net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper mProgressWrapper;
    public FabriclikeDownloadTask(ModloaderDownloadListener modloaderDownloadListener, FabriclikeUtils utils, String mGameVersion, String mLoaderVersion, boolean mCreateProfile) {
        this(modloaderDownloadListener, utils, mGameVersion, mLoaderVersion, mCreateProfile, null, null);
    }

    /** Creation flow variant: the user's profile NAME and ICON are carried
     *  through verbatim — never replaced by an auto-generated label. */
    public FabriclikeDownloadTask(ModloaderDownloadListener modloaderDownloadListener, FabriclikeUtils utils, String mGameVersion, String mLoaderVersion, boolean mCreateProfile, String profileName, String profileIcon) {
        this.mModloaderDownloadListener = modloaderDownloadListener;
        this.mUtils = utils;
        this.mGameVersion = mGameVersion;
        this.mLoaderVersion = mLoaderVersion;
        this.mCreateProfile = mCreateProfile;
        this.mProfileName = profileName;
        this.mProfileIcon = profileIcon;
    }

    @Override
    public void run() {
        android.util.Log.d("FabricInstall", "STEP 5: Downloading loader");
        mProgressWrapper = new net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper(R.string.fabric_dl_progress, ProgressLayout.INSTALL_MODPACK);
        mProgressWrapper.extraString = mUtils.getName();
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.fabric_dl_progress, mUtils.getName());
        try {
            if(runCatching()) mModloaderDownloadListener.onDownloadFinished(null);
            else mModloaderDownloadListener.onDataNotAvailable();
        }catch (IOException e) {
            mModloaderDownloadListener.onDownloadError(e);
        }
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
    }

    private boolean runCatching() throws IOException{
        String fabricJson = DownloadUtils.downloadString(mUtils.createJsonDownloadUrl(mGameVersion, mLoaderVersion));
        String versionId;
        try {
            JSONObject fabricJsonObject = new JSONObject(fabricJson);
            versionId = fabricJsonObject.getString("id");
        }catch (JSONException e) {
            e.printStackTrace();
            return false;
        }
        File versionJsonDir = new File(Tools.DIR_HOME_VERSION, versionId);
        File versionJsonFile = new File(versionJsonDir, versionId+".json");
        FileUtils.ensureDirectory(versionJsonDir);
        Tools.write(versionJsonFile.getAbsolutePath(), fabricJson);
        
        // A loader installed from the Create Profile hub must always become a
        // real, independent instance. Earlier code silently rewired whichever
        // profile happened to be selected, which made a successful download look
        // like no profile had been created and mixed mods into another instance.
        if (mCreateProfile) {
            LauncherProfiles.load();
            String profileKey = LauncherProfiles.getFreeProfileKey();
            MinecraftProfile installedProfile = MinecraftProfile.createTemplate();
            installedProfile.lastVersionId = versionId;
            installedProfile.name = (mProfileName != null && !mProfileName.trim().isEmpty())
                    ? mProfileName : mUtils.getName() + " " + mGameVersion;
            installedProfile.icon = (mProfileIcon != null)
                    ? mProfileIcon : mUtils.getIconName();
            installedProfile.type = "custom";
            // Each created loader receives its own game directory. The UUID is
            // safe as a directory name and prevents resource/mod collisions.
            installedProfile.gameDir = "./custom_instances/" + profileKey;
            String now = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                    .format(new java.util.Date());
            installedProfile.created = now;
            installedProfile.lastUsed = now;

            LauncherProfiles.mainProfileJson.profiles.put(profileKey, installedProfile);
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                    .apply();
            LauncherProfiles.write();
        }
        return true;
    }

    /**
     * Checks whether the given profile is associated with the specified game version.
     * Compares the resolved MC version (via inheritsFrom chain) against the target.
     */
    private boolean isProfileForGameVersion(MinecraftProfile profile, String gameVersion) {
        if (profile == null || profile.lastVersionId == null) return false;
        String pmcVer = net.kdt.pojavlaunch.utils.ProfileDetection.getMcVersion(profile);
        return pmcVer != null && pmcVer.equals(gameVersion);
    }

    @Override
    public void updateProgress(int curr, int max) {
        if (mProgressWrapper != null) mProgressWrapper.updateProgress(curr, max);
    }
}
