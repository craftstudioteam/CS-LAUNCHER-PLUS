package net.kdt.pojavlaunch.utils;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProfileDetection {

    /** Regex to match a Minecraft release version token like 1.21, 1.21.10, 1.20.6, 1.21.4-pre1. */
    private static final Pattern MC_VERSION_PATTERN =
            Pattern.compile("1\\.[0-9]+(?:\\.[0-9]+)?(?:-(?:pre|rc)[0-9]+)?");

    /** Regex to match snapshot version ids like 24w14a / 25w06a / 24w14potato. */
    private static final Pattern SNAPSHOT_PATTERN =
            Pattern.compile("(\\d{2}w\\d{2}[a-z]*)");

    /** Loader-related keywords used to determine if a version is a loader version */
    private static final Pattern LOADER_PATTERN =
            Pattern.compile("(?i)(fabric|forge|neoforge|quilt|optifine|liteloader)");

    /**
     * Extract the base Minecraft version from a profile.
     * Uses the version JSON inheritsFrom chain first, then falls back to
     * extracting from the versionId string.
     */
    public static String getMcVersion(MinecraftProfile profile) {
        if (profile == null || profile.lastVersionId == null) return "";
        try {
            JMinecraftVersionList.Version v = Tools.getVersionInfo(profile.lastVersionId);
            if (v != null) {
                if (v.inheritsFrom != null && !v.inheritsFrom.isEmpty()) {
                    return v.inheritsFrom;
                }
                return v.id; // Vanilla version
            }
        } catch (Exception e) {}

        // Fallback: extract MC version from lastVersionId
        String result = extractMcFromVersionId(profile.lastVersionId);
        if (!result.isEmpty()) return result;

        // Try profile name as well
        if (profile.name != null) {
            result = extractMcFromVersionId(profile.name);
            if (!result.isEmpty()) return result;
        }
        return "";
    }

    /**
     * Extract the base Minecraft version from a loader version ID string.
     * E.g. "fabric-loader-0.19.3-1.21.10" → "1.21.10"
     *      "forge-1.21.10-52.0.12" → "1.21.10"
     *      "1.21.10" → "1.21.10"
     */
    public static String extractMcFromVersionId(String versionId) {
        if (versionId == null || versionId.isEmpty()) return "";

        // Token-based: split on dash/underscore/space and keep the LAST token
        // that is a pure MC version. Loaders append the MC version at the end
        // ("fabric-loader-0.19.3-1.21.10"), and this avoids false matches like
        // the "1.0" hidden inside a Forge build number ("forge-1.20.1-47.1.0").
        String[] tokens = versionId.split("[-_ ]+");
        String lastMatch = "";
        String prev = "";
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            // Re-attach pre/rc suffixes ("pre1", "rc2") to the version token
            // before them so "1.21.4-pre1" is extracted whole.
            if (isPreReleaseSuffix(token) && MC_VERSION_PATTERN.matcher(prev).matches()) {
                token = prev + "-" + token;
            }
            if (MC_VERSION_PATTERN.matcher(token).matches()) {
                lastMatch = token;
            } else if (SNAPSHOT_PATTERN.matcher(token).matches()) {
                lastMatch = token;
            }
            prev = token;
        }
        return lastMatch;
    }

    private static boolean isPreReleaseSuffix(String token) {
        if (token.startsWith("pre") && token.length() > 3 && token.substring(3).matches("[0-9]+")) {
            return true;
        }
        return token.startsWith("rc") && token.length() > 2 && token.substring(2).matches("[0-9]+");
    }

    /**
     * Check whether a profile uses the specified mod loader.
     * Checks multiple sources:
     * 1. lastVersionId string contains the loader name
     * 2. Version JSON id/inheritsFrom/mainClass contain the loader name
     * 3. Profile name contains the loader name
     * 4. The actual mods directory contains the loader's API mod (e.g. fabric-api)
     */
    public static boolean hasLoader(MinecraftProfile profile, String loader) {
        if (profile == null || profile.lastVersionId == null || loader == null) return false;
        String vId = profile.lastVersionId.toLowerCase();
        String targetLoader = loader.toLowerCase();

        // 1. Check lastVersionId
        if (vId.contains(targetLoader)) return true;

        // 2. Check version JSON chain
        try {
            JMinecraftVersionList.Version v = Tools.getVersionInfo(profile.lastVersionId);
            if (v != null) {
                if (v.id != null && v.id.toLowerCase().contains(targetLoader)) return true;
                if (v.inheritsFrom != null && v.inheritsFrom.toLowerCase().contains(targetLoader)) return true;
                if (v.mainClass != null && v.mainClass.toLowerCase().contains(targetLoader)) return true;
            }
        } catch (Exception e) {}

        // 3. Check profile name
        if (profile.name != null && profile.name.toLowerCase().contains(targetLoader)) return true;

        // 4. Check actual mods directory for known loader API files
        //    This catches cases where a loader was installed but the version ID doesn't contain its name
        try {
            File gameDir = net.kdt.pojavlaunch.Tools.getGameDirPath(profile);
            if (gameDir != null) {
                File modsDir = new File(gameDir, "mods");
                if (modsDir.exists() && modsDir.isDirectory()) {
                    File[] mods = modsDir.listFiles((dir, name) ->
                            name.toLowerCase().endsWith(".jar") || name.toLowerCase().endsWith(".jar.disabled"));
                    if (mods != null) {
                        for (File mod : mods) {
                            String modName = mod.getName().toLowerCase();
                            // Check for loader-specific API mods
                            if (targetLoader.equals("fabric") && modName.contains("fabric-api")) return true;
                            if (targetLoader.equals("fabric") && modName.contains("fabric-loader")) return true;
                            if (targetLoader.equals("forge") && modName.contains("forge")) return true;
                            if (targetLoader.equals("neoforge") && modName.contains("neoforge")) return true;
                            if (targetLoader.equals("quilt") && modName.contains("quilt")) return true;
                            if (targetLoader.equals("liteloader") && modName.contains("liteloader")) return true;
                            if (targetLoader.equals("optifine") && (modName.contains("optifine") || modName.contains("optifabric"))) return true;
                        }
                    }
                }
            }
        } catch (Exception e) {}

        return false;
    }

    /**
     * Loader tags that do not name a mod loader at all. Modrinth files a
     * resource pack under {@code ["minecraft"]}, a shader under
     * {@code ["iris", "optifine", "canvas", "vanilla"]}, a data pack under
     * {@code ["datapack"]}. None of these constrain the profile — treating
     * them as loaders is what produced the false "NOT COMPATIBLE" verdicts.
     */
    private static final java.util.Set<String> UNIVERSAL_LOADER_TAGS = new java.util.HashSet<>(
            java.util.Arrays.asList("minecraft", "vanilla", "iris", "optifine", "canvas",
                    "datapack", "resourcepack", "shader", "any", "all", "*"));

    /** Content kinds whose files never depend on the profile's mod loader. */
    public static boolean isLoaderAgnosticContent(@androidx.annotation.Nullable String contentType) {
        if (contentType == null) return false;
        switch (contentType.toLowerCase()) {
            case "resourcepack": case "resource_pack": case "texturepack":
            case "shader": case "shaderpack":
            case "world": case "datapack": case "data_pack": case "map":
                return true;
            default:
                return false;
        }
    }

    /**
     * Single source of truth for the loader half of a compatibility verdict.
     *
     * <ul>
     *   <li>resource packs / shaders / worlds / data packs → always OK</li>
     *   <li>no loader list, or only universal tags ("minecraft", "iris"…) → OK</li>
     *   <li>otherwise OK when the profile runs any listed loader; Quilt profiles
     *       also accept Fabric files, NeoForge profiles accept Forge files only
     *       for legacy builds (1.20.1) where the two were interchangeable</li>
     * </ul>
     */
    public static boolean isLoaderCompatible(@androidx.annotation.Nullable MinecraftProfile profile,
                                             @androidx.annotation.Nullable String contentType,
                                             @androidx.annotation.Nullable String[] loaders,
                                             @androidx.annotation.Nullable String mcVersion) {
        if (profile == null) return true;
        if (isLoaderAgnosticContent(contentType)) return true;
        if (loaders == null || loaders.length == 0) return true;
        boolean sawRealLoader = false;
        for (String raw : loaders) {
            if (raw == null) continue;
            String l = raw.trim().toLowerCase();
            if (l.isEmpty() || UNIVERSAL_LOADER_TAGS.contains(l)) continue;
            sawRealLoader = true;
            if (hasLoader(profile, l)) return true;
            if ("fabric".equals(l) && hasLoader(profile, "quilt")) return true;
            if ("forge".equals(l) && hasLoader(profile, "neoforge")
                    && mcVersion != null && mcVersion.startsWith("1.20.1")) return true;
        }
        // Only universal tags were listed → the file runs on anything.
        return !sawRealLoader;
    }

    /**
     * Check if a profile's MC version is compatible with a mod's required MC version.
     * Supports exact match, prefix match, and wildcards.
     */
    public static boolean isVersionCompatible(String pmcVer, String modMcVer) {
        if (pmcVer == null || modMcVer == null) return false;
        pmcVer = pmcVer.trim().toLowerCase();
        modMcVer = modMcVer.trim().toLowerCase();
        if (pmcVer.isEmpty() || modMcVer.isEmpty()) return false;

        // Handle version ranges like ">=1.21" or "1.21-1.22"
        if (modMcVer.startsWith(">=") || modMcVer.startsWith("<=") || modMcVer.startsWith(">") || modMcVer.startsWith("<")) {
            return isVersionInRange(pmcVer, modMcVer);
        }
        if (modMcVer.contains("-") && !modMcVer.startsWith("-")) {
            String[] parts = modMcVer.split("-", 2);
            if (parts.length == 2) {
                return isVersionInRange(pmcVer, parts[0], parts[1]);
            }
        }

        // Exact or numerically-equal ("1.21" == "1.21.0")
        if (versionsEqual(pmcVer, modMcVer)) return true;

        // Same release line at a patch boundary: a base tag ("1.21") covers its
        // numeric patches ("1.21.1"), but never a later minor ("1.21.10").
        if (sameReleaseFamily(pmcVer, modMcVer)) return true;

        return false;
    }

    /** Numeric equality with zero-padding ("1.21" ≡ "1.21.0", "1.20" ≡ "1.20.1"? No). */
    private static boolean versionsEqual(String a, String b) {
        if (a.equals(b)) return true;
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseNum(pa[i]) : 0;
            int nb = i < pb.length ? parseNum(pb[i]) : 0;
            // Non-numeric tokens (snapshots/pre-releases) are only equal when
            // the whole string already matched above.
            if (na != nb || na < 0 || nb < 0) return false;
        }
        return true;
    }

    /**
     * Release-line family check at a strict patch boundary:
     * "1.21" covers "1.21.1", "1.20.1" covers "1.20.6", but "1.21.1" never covers
     * "1.21.10" and "1.21" never covers "1.22".
     */
    private static boolean sameReleaseFamily(String profile, String mod) {
        if (profile.equals(mod)) return true;
        String longer, shorter;
        if (profile.startsWith(mod + ".")) {
            longer = profile;
            shorter = mod;
        } else if (mod.startsWith(profile + ".")) {
            longer = mod;
            shorter = profile;
        } else {
            return false;
        }
        String rest = longer.substring(shorter.length() + 1);
        return !rest.isEmpty() && rest.matches("[0-9]+");
    }

    private static int parseNum(String token) {
        try { return Integer.parseInt(token); } catch (Exception e) { return -1; }
    }

    private static boolean isVersionInRange(String version, String rangeExpr) {
        if (rangeExpr.startsWith(">=")) {
            return compareVersions(version, rangeExpr.substring(2)) >= 0;
        } else if (rangeExpr.startsWith("<=")) {
            return compareVersions(version, rangeExpr.substring(2)) <= 0;
        } else if (rangeExpr.startsWith(">")) {
            return compareVersions(version, rangeExpr.substring(1)) > 0;
        } else if (rangeExpr.startsWith("<")) {
            return compareVersions(version, rangeExpr.substring(1)) < 0;
        }
        return false;
    }

    private static boolean isVersionInRange(String version, String min, String max) {
        return compareVersions(version, min) >= 0 && compareVersions(version, max) <= 0;
    }

    /**
     * Compare two version strings numerically.
     * Returns negative if v1 < v2, positive if v1 > v2, 0 if equal.
     */
    private static int compareVersions(String v1, String v2) {
        String clean1 = v1.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
        String clean2 = v2.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
        String[] parts1 = clean1.isEmpty() ? new String[]{"0"} : clean1.split("\\.");
        String[] parts2 = clean2.isEmpty() ? new String[]{"0"} : clean2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = 0, n2 = 0;
            try { n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0; } catch (NumberFormatException ignored) {}
            try { n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0; } catch (NumberFormatException ignored) {}
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }
}
