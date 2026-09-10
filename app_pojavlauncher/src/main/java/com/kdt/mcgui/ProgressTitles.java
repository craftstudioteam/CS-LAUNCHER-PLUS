package com.kdt.mcgui;

/**
 * THE one place a progress row's human title is built.
 *
 * It exists because the title used to be computed twice — once for the in-app
 * Download Console row (ProgressLayout) and once for the shade notification
 * (ProgressService.resolveTitle) — and the two copies had already drifted: the row
 * said "Unpacking Runtime" while the notification said "Downloading Java Runtime",
 * the modpack row said "Downloading Shader Pack: x" while the notification said
 * "Extracting"-style wording, and the row used "..." where the notification used an
 * ellipsis. A user reading both screens for the same download should not get two
 * different stories, so both now call this.
 *
 * Titles are deliberately NOT localised, matching the rest of this launcher's
 * custom-built pages (the download console, the shade cards, the mod manager): one
 * voice across every surface matters more here than a partial translation set that
 * would leave half of a sentence in English.
 */
public final class ProgressTitles {
    // The five string resources that change the wording. They are looked up once
    // rather than compared inside this class: R.string ids are not compile-time
    // constants here (so no switch on them), and this class must stay Context-free to
    // be testable off-device. Call ProgressTitles.init(getResources()) before use —
    // both callers already run inside a Context.
    public static int FABRIC_DL, FORGE_DL, OF_DL, NEOFORGE_SEARCHING, FORGE_SEARCHING;

    public static void init(android.content.res.Resources res) {
        FABRIC_DL = net.kdt.pojavlaunch.R.string.fabric_dl_progress;
        FORGE_DL = net.kdt.pojavlaunch.R.string.forge_dl_progress;
        OF_DL = net.kdt.pojavlaunch.R.string.of_dl_progress;
        NEOFORGE_SEARCHING = net.kdt.pojavlaunch.R.string.neoforge_dl_searching;
        FORGE_SEARCHING = net.kdt.pojavlaunch.R.string.forge_dl_searching;
    }

    private ProgressTitles() {}

    /** @param record the ProgressLayout key (may be null for loader-only payloads) */
    public static String title(String record, int resid, Object[] va,
                              String contentType, String contentName) {
        // Strings must be resolved by the caller: this class has no Context, which is
        // also what makes it testable off-device.
        if (resid == FABRIC_DL) {
            return "Downloading Fabric" + first(va);
        }
        if (resid == FORGE_DL) {
            String loaderName = "Forge";
            String ver = va != null && va.length > 0 && va[0] != null ? String.valueOf(va[0]) : null;
            if (ver != null && ver.toLowerCase(java.util.Locale.ROOT).contains("neoforge")) {
                // The version string already carries the loader's name
                // ("neoforge-20.4.80"), so prefixing "Forge" would read
                // "Downloading Forge neoforge-20.4.80".
                loaderName = "";
            }
            return "Downloading " + loaderName + (ver == null ? "" : " " + ver);
        }
        if (resid == OF_DL) {
            return "Downloading OptiFine" + first(va);
        }
        if (resid == NEOFORGE_SEARCHING) return "Searching NeoForge…";
        if (resid == FORGE_SEARCHING) return "Searching Forge…";
        if (net.kdt.pojavlaunch.modloaders.InstalledModCopy.RECORD.equals(record)) {
            // A copy is not a download; telling a user to wait for a network that is
            // already idle is how they start killing the app mid-copy.
            return "Copying mod to profile";
        }
        switch (record == null ? "" : record) {
            case ProgressLayout.DOWNLOAD_MINECRAFT:
                return "Downloading Minecraft";
            case ProgressLayout.UNPACK_RUNTIME:
                return "Downloading Java Runtime";
            case ProgressLayout.DOWNLOAD_VERSION_LIST:
                return "Fetching Version List";
            case ProgressLayout.AUTHENTICATE_MICROSOFT:
                return "Signing in to Microsoft";
            case ProgressLayout.INSTALL_MODPACK:
                if (contentType != null && contentName != null) {
                    return "Downloading " + typeLabel(contentType) + ": " + contentName;
                }
                return "Installing Modpack";
            case ProgressLayout.EXTRACT_COMPONENTS:
                return "Extracting Components";
            case ProgressLayout.EXTRACT_SINGLE_FILES:
                return "Extracting Files";
            default:
                if (resid > 0) return null;   // caller resolves the string resource
                if (va != null && va.length > 0 && va[0] instanceof String) return (String) va[0];
                return "Downloading…";
        }
    }

    /** The one phrase a modpack's content type contributes, shared by both surfaces. */
    public static String typeLabel(String contentType) {
        if (contentType == null || contentType.isEmpty()) return "Modpack";
        if ("resourcepack".equals(contentType)) return "Resource Pack";
        if ("shader".equals(contentType)) return "Shader Pack";
        if ("mod".equals(contentType)) return "Mod";
        return contentType.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + contentType.substring(1);
    }

    private static String first(Object[] va) {
        return va != null && va.length > 0 && va[0] != null ? " " + va[0] : "";
    }
}
