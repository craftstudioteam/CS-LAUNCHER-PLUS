package net.kdt.pojavlaunch.shortcuts;

/**
 * Preference keys owned by the shortcut system.
 *
 * <p>Stored in {@code LauncherPreferences.DEFAULT_PREF} alongside the rest of the
 * launcher settings, so they survive the same backup/restore path.</p>
 */
public final class ShortcutPreferences {

    /**
     * Whether the app-icon long-press menu is populated with recent profiles.
     * Defaults to true.
     */
    public static final String KEY_DYNAMIC_ENABLED = "shortcut_dynamic_enabled";

    /** Last icon shape chosen in the picker, as {@code IconShape#getId()}. */
    public static final String KEY_LAST_SHAPE = "shortcut_last_shape";

    /** Whether the picker's adaptive-icon toggle was last left on. */
    public static final String KEY_LAST_ADAPTIVE = "shortcut_last_adaptive";

    private ShortcutPreferences() {
        // constants only
    }
}
