package net.kdt.pojavlaunch.shortcuts;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Persisted metadata for one home screen shortcut.
 *
 * <p>Android does not let an app read back the pinned shortcuts it created on
 * older API levels, and pinned shortcut labels can be renamed by the launcher.
 * We therefore keep our own registry so the in-app "Manage shortcuts" screen can
 * list, update and revoke what the user made.</p>
 *
 * <p>Serialised with Gson into the launcher preferences by
 * {@link ShortcutRegistry}. Field names are part of the on-disk format — renaming
 * them breaks existing installs.</p>
 */
@Keep
public class ShortcutRecord {

    /** Unique Android shortcut id, e.g. {@code cs_sc_<profile>_<action>}. */
    public String shortcutId;

    /** UUID key of the owning profile in {@code LauncherProfiles}. */
    public String profileKey;

    /** Profile name captured at creation time, for display when a profile vanishes. */
    public String profileName;

    /** Label shown on the home screen. */
    public String label;

    /** Persisted {@link ShortcutType#getId()}. */
    public String actionId;

    /** Icon source marker: {@code profile}, {@code skin}, {@code custom} or {@code loader}. */
    public String iconSource;

    /** Epoch millis when the shortcut was created. */
    public long createdAt;

    /** Epoch millis of the most recent tap, or 0 when never used. */
    public long lastUsedAt;

    /** How many times the shortcut has been tapped. */
    public int useCount;

    /** True when the icon was rendered as an adaptive (masked) icon. */
    public boolean adaptiveIcon;

    /** Whether the shortcut also appears in the app's long-press menu. */
    public boolean dynamic;

    public ShortcutRecord() {
        // Gson
    }

    public ShortcutRecord(@NonNull String shortcutId,
                          @NonNull String profileKey,
                          @Nullable String profileName,
                          @NonNull String label,
                          @NonNull ShortcutType type,
                          @Nullable String iconSource,
                          boolean adaptiveIcon,
                          boolean dynamic) {
        this.shortcutId = shortcutId;
        this.profileKey = profileKey;
        this.profileName = profileName;
        this.label = label;
        this.actionId = type.getId();
        this.iconSource = iconSource != null ? iconSource : "profile";
        this.adaptiveIcon = adaptiveIcon;
        this.dynamic = dynamic;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedAt = 0L;
        this.useCount = 0;
    }

    @NonNull
    public ShortcutType getType() {
        return ShortcutType.fromId(actionId);
    }

    /** Register a tap. Callers must persist the registry afterwards. */
    public void markUsed() {
        lastUsedAt = System.currentTimeMillis();
        useCount++;
    }

    /** Basic sanity check — guards against corrupted or partially written entries. */
    public boolean isValid() {
        return shortcutId != null && !shortcutId.isEmpty()
                && profileKey != null && !profileKey.isEmpty()
                && label != null && !label.isEmpty();
    }
}
