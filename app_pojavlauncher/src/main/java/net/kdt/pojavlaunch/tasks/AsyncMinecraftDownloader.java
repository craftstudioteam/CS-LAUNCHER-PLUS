package net.kdt.pojavlaunch.tasks;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

public class AsyncMinecraftDownloader {
    public static String normalizeVersionId(String versionString) {
        JMinecraftVersionList versionList = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if(versionList == null || versionList.versions == null) return versionString;
        if(MinecraftProfile.LATEST_RELEASE.equals(versionString)) {
            // Phase 10: "latest release" is the launcher's pinned default
            // (1.21.11 vanilla); only if the manifest does not list it at all
            // do we fall back to Mojang's current latest.
            versionString = getListedVersion(versionList, MinecraftProfile.DEFAULT_VERSION) != null
                    ? MinecraftProfile.DEFAULT_VERSION : versionList.latest.get("release");
        }
        if(MinecraftProfile.LATEST_SNAPSHOT.equals(versionString)) versionString = versionList.latest.get("snapshot");
        return versionString;
    }

    public static JMinecraftVersionList.Version getListedVersion(String normalizedVersionString) {
        JMinecraftVersionList versionList = (JMinecraftVersionList) ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        return getListedVersion(versionList, normalizedVersionString);
    }

    /**
     * Look a version id up in an explicitly supplied version list instead of the
     * in-memory release table. Used by the downloader's self-healing path, which
     * may have just fetched a fresher manifest than the one currently published.
     * @param versionList the list to search, may be null
     * @param normalizedVersionString exact version id to find
     * @return the matching manifest entry, or null when absent / no list available
     */
    public static JMinecraftVersionList.Version getListedVersion(JMinecraftVersionList versionList, String normalizedVersionString) {
        if(versionList == null || versionList.versions == null) return null; // can't have listed versions if there's no list
        for(JMinecraftVersionList.Version version : versionList.versions) {
            if(version.id.equals(normalizedVersionString)) return version;
        }
        return null;
    }

    public interface DoneListener{
        void onDownloadDone();
        void onDownloadFailed(Throwable throwable);
    }
}
