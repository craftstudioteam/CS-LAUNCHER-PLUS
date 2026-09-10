package net.kdt.pojavlaunch.worlds;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/** One Minecraft world (= one folder under saves/) for the World Manager list. */
public final class WorldEntry {

    @NonNull public final File folder;
    @NonNull public final String folderName;

    // Display data (filled from level.dat + filesystem scan)
    public String displayName;      // LevelName, falls back to the folder name
    public String versionName;      // e.g. "1.20.1" (nullable)
    public long lastPlayedMs;       // 0 → unknown
    public boolean hasSeed;
    public long seed;
    public boolean hardcore;
    public long sizeBytes = -1;     // -1 → not computed yet
    public int datapackCount = -1;  // -1 → not computed yet

    public WorldEntry(@NonNull File folder) {
        this.folder = folder;
        this.folderName = folder.getName();
        this.displayName = folder.getName();
    }

    @Nullable
    public File iconFile() {
        File f = new File(folder, "icon.png");
        return f.exists() ? f : null;
    }

    @NonNull
    public File datapacksDir() {
        return new File(folder, "datapacks");
    }

    /**
     * Stable identity used by icon caches / trackers.
     * Req-15: absolute path, not folderName — two worlds living in different
     * saves/ dirs can share a folder name (fallback sweep), and RecyclerView
     * stable IDs must never collide.
     */
    @NonNull
    public String stableKey() {
        return folder.getAbsolutePath();
    }
}
