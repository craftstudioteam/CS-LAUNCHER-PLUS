package net.kdt.pojavlaunch.shortcuts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

/**
 * The action a home screen shortcut performs when tapped.
 *
 * <p>Historically a shortcut could only do one thing: select the profile and
 * immediately launch the game. This enum lets a single profile own several
 * shortcuts that each jump to a different place in the launcher.</p>
 */
public enum ShortcutType {

    /** Select the profile and start Minecraft straight away. */
    LAUNCH("launch", R.string.shortcut_action_launch,
            R.string.shortcut_action_launch_desc, R.drawable.ic_play_arrow),

    /** Open the launcher with the profile selected, but do not start the game. */
    OPEN_PROFILE("open_profile", R.string.shortcut_action_open_profile,
            R.string.shortcut_action_open_profile_desc, R.drawable.ic_home),

    /** Jump directly into the mod browser scoped to this profile. */
    MODS("mods", R.string.shortcut_action_mods,
            R.string.shortcut_action_mods_desc, R.drawable.ic_browse_resources),

    /** Jump directly into the profile editor. */
    EDIT("edit", R.string.shortcut_action_edit,
            R.string.shortcut_action_edit_desc, R.drawable.ic_edit_profile),

    /** Open the profile's game directory in a file manager. */
    FOLDER("folder", R.string.shortcut_action_folder,
            R.string.shortcut_action_folder_desc, R.drawable.ic_folder);

    private final String mId;
    private final int mLabelRes;
    private final int mDescriptionRes;
    private final int mIconRes;

    ShortcutType(@NonNull String id, int labelRes, int descriptionRes, int iconRes) {
        mId = id;
        mLabelRes = labelRes;
        mDescriptionRes = descriptionRes;
        mIconRes = iconRes;
    }

    /** Stable string id persisted in intents and preferences. Never localise this. */
    @NonNull
    public String getId() {
        return mId;
    }

    public int getLabelRes() {
        return mLabelRes;
    }

    public int getDescriptionRes() {
        return mDescriptionRes;
    }

    /** Small glyph used for the corner badge and for the action picker chips. */
    public int getIconRes() {
        return mIconRes;
    }

    /**
     * True when this action needs the launcher UI to settle before it can run.
     * Used by {@link ShortcutActivity} to decide between a direct intent hand-off
     * and a deferred request.
     */
    public boolean requiresLauncherUi() {
        return this != FOLDER;
    }

    /**
     * Resolve a persisted id back into an enum constant.
     *
     * @return the matching type, or {@link #LAUNCH} when the id is unknown or null.
     * Falling back to LAUNCH keeps shortcuts created by older builds working.
     */
    @NonNull
    public static ShortcutType fromId(@Nullable String id) {
        if (id == null) return LAUNCH;
        for (ShortcutType type : values()) {
            if (type.mId.equals(id)) return type;
        }
        return LAUNCH;
    }
}
