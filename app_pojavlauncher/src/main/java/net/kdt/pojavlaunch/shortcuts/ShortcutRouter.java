package net.kdt.pojavlaunch.shortcuts;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.ManageModsFragment;
import net.kdt.pojavlaunch.fragments.ModsSearchFragment;
import net.kdt.pojavlaunch.fragments.ProfileEditorFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Executes the action a shortcut asked for, once the launcher UI is ready.
 *
 * <p>Kept separate from {@code LauncherActivity} so the routing table lives in
 * one place and the activity keeps only a two-line hook.</p>
 */
public final class ShortcutRouter {

    private static final String TAG = "ShortcutRouter";

    /** How many profiles appear in the app-icon long-press menu. */
    private static final int DYNAMIC_SHORTCUT_LIMIT = 4;

    private ShortcutRouter() {
        // static only
    }

    /**
     * Perform a shortcut action inside the running launcher.
     *
     * @param activity   the live launcher activity
     * @param profileKey profile the shortcut points at, already made current
     * @param action     what to do
     */
    public static void route(@NonNull FragmentActivity activity,
                             @NonNull String profileKey,
                             @NonNull ShortcutType action) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        // Make sure the rest of the launcher agrees on which profile is active.
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .apply();

        switch (action) {
            case LAUNCH: {
                // Premium boot continuity: ShortcutActivity already showed the
                // "Opening Game…" screen; the launcher keeps an identical overlay
                // while its own listener owns download/auth/validation.
                if (activity instanceof net.kdt.pojavlaunch.LauncherActivity) {
                    MinecraftProfile profile = null;
                    if (LauncherProfiles.mainProfileJson != null
                            && LauncherProfiles.mainProfileJson.profiles != null) {
                        profile = LauncherProfiles.mainProfileJson.profiles.get(profileKey);
                    }
                    // Ask the game activity to open on the launch log screen.
                    net.kdt.pojavlaunch.MainActivity.sAutoShowLogsOnce = true;
                    ((net.kdt.pojavlaunch.LauncherActivity) activity).showLaunchBoot(
                            profile != null && profile.name != null ? profile.name : profileKey,
                            profile != null ? profile.lastVersionId : null);
                }
                // The launcher's own listener owns download/auth/validation, so
                // just raise the flag it already listens for.
                ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                break;
            }

            case MODS:
                openMods(activity, profileKey);
                break;

            case EDIT:
                openEditor(activity);
                break;

            case OPEN_PROFILE:
            case FOLDER:
            default:
                // OPEN_PROFILE only needs the profile selected, which happened
                // above. FOLDER is handled entirely inside ShortcutActivity and
                // only reaches here when the directory could not be resolved.
                break;
        }
    }

    private static void openMods(@NonNull FragmentActivity activity,
                                 @NonNull String profileKey) {
        Bundle args = new Bundle();
        args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, profileKey);
        openFragment(activity, ModsSearchFragment.class, ModsSearchFragment.TAG, args);
    }

    private static void openEditor(@NonNull FragmentActivity activity) {
        // ProfileEditorFragment reads the current profile from preferences when
        // it is given no arguments; passing a Bundle makes it create a new one.
        openFragment(activity, ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
    }

    /**
     * Show a fragment, preferring the two-pane right side when it is available
     * so the shortcut lands the user in the same place the in-app navigation would.
     */
    private static void openFragment(@NonNull FragmentActivity activity,
                                     @NonNull Class<? extends Fragment> fragmentClass,
                                     @NonNull String tag,
                                     @Nullable Bundle args) {
        try {
            Fragment root = activity.getSupportFragmentManager().findFragmentByTag("ROOT");
            if (root instanceof MainMenuFragment && root.isAdded()) {
                ((MainMenuFragment) root).openChildPane(fragmentClass, tag, args);
            } else {
                Tools.swapFragment(activity, fragmentClass, tag, args);
            }
        } catch (Exception e) {
            Log.w(TAG, "Shortcut navigation failed for " + tag, e);
        }
    }

    // ─── Dynamic shortcut sync ─────────────────────────────────────────

    /**
     * Rebuild the app-icon long-press menu from the most recently played profiles.
     *
     * <p>Cheap and idempotent, so calling it on every launcher resume is fine.
     * Requires {@code LauncherProfiles} to already be loaded.</p>
     */
    public static void syncDynamicShortcuts(@NonNull FragmentActivity activity) {
        if (!LauncherPreferences.DEFAULT_PREF.getBoolean(
                ShortcutPreferences.KEY_DYNAMIC_ENABLED, true)) {
            ProfileShortcutHelper.clearDynamicShortcuts(activity);
            return;
        }

        try {
            List<String> recentKeys = collectRecentProfileKeys();
            if (recentKeys.isEmpty()) return;
            ProfileShortcutHelper.syncDynamicShortcuts(activity, recentKeys);
        } catch (Exception e) {
            Log.w(TAG, "Dynamic shortcut sync failed", e);
        }
    }

    /** Profile keys ordered by {@code lastUsed}, most recent first. */
    @NonNull
    private static List<String> collectRecentProfileKeys() {
        List<String> keys = new ArrayList<>();

        if (LauncherProfiles.mainProfileJson == null
                || LauncherProfiles.mainProfileJson.profiles == null) {
            return keys;
        }

        List<Map.Entry<String, MinecraftProfile>> entries =
                new ArrayList<>(LauncherProfiles.mainProfileJson.profiles.entrySet());

        Collections.sort(entries, new Comparator<Map.Entry<String, MinecraftProfile>>() {
            @Override
            public int compare(Map.Entry<String, MinecraftProfile> a,
                               Map.Entry<String, MinecraftProfile> b) {
                String ua = a.getValue() != null && a.getValue().lastUsed != null
                        ? a.getValue().lastUsed : "";
                String ub = b.getValue() != null && b.getValue().lastUsed != null
                        ? b.getValue().lastUsed : "";
                // ISO-8601 timestamps sort correctly as plain strings.
                return ub.compareTo(ua);
            }
        });

        for (Map.Entry<String, MinecraftProfile> entry : entries) {
            MinecraftProfile profile = entry.getValue();
            if (entry.getKey() == null || entry.getKey().isEmpty()) continue;
            if (profile == null || profile.name == null || profile.name.trim().isEmpty()) continue;
            keys.add(entry.getKey());
            if (keys.size() >= DYNAMIC_SHORTCUT_LIMIT) break;
        }

        return keys;
    }
}
