package net.kdt.pojavlaunch.performance;

/**
 * The facts about the Minecraft session a launch is about to perform, gathered by the one scan
 * {@link LaunchSnapshot} already does — never by re-walking directories.
 *
 * <p>Mod pack and version matter for a performance decision only where they change what the JVM is
 * doing: a modded client allocates far more short-lived objects (so GC choice and heap headroom
 * move), while an unmod 1.8 client spends its time in the network and render path. This class
 * exposes just enough to make that judgement without pretending to model the game.
 */
public final class McContext {

    /** Version numeric tail used for the coarse "old vs modern" split (1.8 vs 1.21). */
    public final int majorVersion;
    public final int minorVersion;
    public final String versionId;
    public final String runtimeName;
    public final String rendererId;
    /** Profile directory — read-only source of Minecraft's own vsync / maxFps choices. */
    public final java.io.File gameDirectory;
    public final int modCount;
    public final boolean hasSodium;
    public final boolean hasOptifine;
    public final boolean hasIrisOrShaders;
    public final boolean modded;

    public McContext(String versionId, String runtimeName, String rendererId,
                     int modCount, boolean hasSodium, boolean hasOptifine, boolean hasShaders) {
        this(versionId, runtimeName, rendererId, null, modCount, hasSodium, hasOptifine, hasShaders);
    }

    public McContext(String versionId, String runtimeName, String rendererId,
                     java.io.File gameDirectory, int modCount, boolean hasSodium,
                     boolean hasOptifine, boolean hasShaders) {
        this.versionId = versionId == null ? "" : versionId;
        this.runtimeName = runtimeName == null ? "" : runtimeName;
        this.rendererId = rendererId == null ? "" : rendererId;
        this.gameDirectory = gameDirectory;
        this.modCount = Math.max(0, modCount);
        this.hasSodium = hasSodium;
        this.hasOptifine = hasOptifine;
        this.hasIrisOrShaders = hasShaders;
        this.modded = this.modCount > 0;
        int major = 0, minor = 0;
        // "1.20.4", "1.8.9", or a loader string with the version inside it
        String[] parts = this.versionId.split("[^0-9]+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("1") && !parts[i + 1].isEmpty()) {
                try {
                    minor = Integer.parseInt(parts[i + 1]);
                    major = 1;
                    break;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        this.majorVersion = major;
        this.minorVersion = minor;
    }

    public static McContext from(LaunchSnapshot snapshot) {
        if (snapshot == null) return new McContext(null, null, null, 0, false, false, false);
        java.util.Set<String> mods = snapshot.modFileNames;
        int count = mods == null ? 0 : mods.size();
        boolean sodium = false, optifine = false, shaders = false;
        if (mods != null) {
            for (String name : mods) {
                if (name == null) continue;
                String n = name.toLowerCase(java.util.Locale.ROOT);
                if (n.contains("sodium") || n.contains("embeddium") || n.contains("rubidium")) sodium = true;
                if (n.contains("optifine") || n.contains("otifine")) optifine = true;
                if (n.contains("iris") || n.contains("voxelmap") || n.contains("shader")) shaders = true;
            }
        }
        return new McContext(snapshot.versionId, snapshot.runtimeName, snapshot.rendererId,
                snapshot.gameDirectory, count, sodium, optifine, shaders);
    }

    /** Modern client (1.17+) where the big-heap, high-allocation behaviour applies. */
    public boolean isModern() {
        return majorVersion == 1 && minorVersion >= 17;
    }

    public boolean isOldClient() {
        return majorVersion == 1 && minorVersion > 0 && minorVersion <= 12;
    }

    /** How much heap churn this session is likely to create: 0 = plain, 2 = heavy mod stack. */
    public int allocationPressureClass() {
        int score = 0;
        if (modded) score += modCount > 40 ? 2 : 1;
        if (hasOptifine) score += 1;              // extra caches
        if (hasIrisOrShaders) score += 1;          // per-frame shader work
        if (isModern()) score += 1;                // 1.17+ registries + larger world data
        return score;
    }

    /**
     * Minecraft's own pacing choices, straight from the profile's {@code options.txt}. Read-only and
     * side-effect free on purpose: {@code MCOptionUtils.load()} creates an empty options file when one
     * is missing, and the launcher has no business writing into a player's profile to answer a question.
     *
     * @return {@code null} when the file or the key cannot be read — callers must treat "unknown" as
     * "the player asked for nothing", never as permission to add a cap.
     */
    public String mcOption(String key) {
        if (gameDirectory == null || key == null) return null;
        java.io.File f = new java.io.File(gameDirectory, "options.txt");
        if (!f.isFile()) return null;
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            String prefix = key + ":";
            while ((line = r.readLine()) != null) {
                if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** True only when the player's own options say vsync is on. Unknown counts as "not on". */
    public boolean mcVsyncEnabled() {
        return "true".equalsIgnoreCase(mcOption("vsync"));
    }

    /** Minecraft's FPS slider; 260 means Unlimited, and an unreadable value means no cap known. */
    public int mcMaxFps() {
        String v = mcOption("maxFps");
        if (v == null) return -1;
        try {
            int i = Integer.parseInt(v.trim());
            return i >= 260 ? 0 : Math.max(0, i);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** A readable tag for logs, so a measurement can be traced back to the session it came from. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        if (!versionId.isEmpty()) b.append(versionId);
        if (!runtimeName.isEmpty()) b.append(" · ").append(runtimeName);
        if (!rendererId.isEmpty()) b.append(" · ").append(rendererId);
        if (modCount > 0) b.append(" · ").append(modCount).append(" mods");
        return b.toString();
    }

}
