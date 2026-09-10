package net.kdt.pojavlaunch.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.Map;

/**
 * Carries the user's chosen PROFILE NAME + ICON across installer-based
 * creations (Forge / NeoForge / OptiFine), where the installer agent writes
 * the profile itself with an auto-generated name.
 *
 * Right before the installer is handed off, the creation flow records a
 * pending rename keyed by a build token. Whenever profiles are reloaded
 * (e.g. returning to the Home screen), {@link #apply(Context)} renames the
 * NEWEST matching profile to the user's name/icon, makes it current, and
 * clears the pending record — so "My Survival" stays "My Survival"
 * everywhere: home, instance list, editor and launch.
 */
public final class PendingProfileRename {

    private static final String PREFS = "pending_profile_rename";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_NAME = "name";
    private static final String KEY_ICON = "icon";

    private PendingProfileRename() {}

    /** Records the intent to rename the profile the installer is about to create. */
    public static void record(Context ctx, String buildToken, String profileName, String profileIcon) {
        if (buildToken == null || profileName == null || profileName.trim().isEmpty()) return;
        prefs(ctx).edit()
                .putString(KEY_TOKEN, buildToken)
                .putString(KEY_NAME, profileName.trim())
                .putString(KEY_ICON, profileIcon)
                .apply();
    }

    /**
     * Applies the pending rename against the currently loaded profiles
     * (newest match wins). Returns true when a profile was renamed and
     * written — callers may refresh dependent UI.
     */
    public static boolean apply(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String token = p.getString(KEY_TOKEN, null);
        String name = p.getString(KEY_NAME, null);
        if (token == null || name == null) return false;
        if (LauncherProfiles.mainProfileJson == null
                || LauncherProfiles.mainProfileJson.profiles == null) return false;

        String bestKey = null;
        String bestCreated = null;
        for (Map.Entry<String, MinecraftProfile> e
                : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
            MinecraftProfile prof = e.getValue();
            if (prof == null || prof.lastVersionId == null) continue;
            // Phase 11: case-insensitive so "1.21.11-OptiFine" matches the
            // installer's "1.21.11-OptiFine_HD_U_J9" while a plain "1.21.11"
            // vanilla profile can no longer be mistaken for it.
            if (!prof.lastVersionId.toLowerCase().contains(token.toLowerCase())) continue;
            if (prof.name != null && prof.name.equals(name)) continue; // already applied
            // Newest matching profile = the one the installer just wrote.
            if (bestCreated == null || (prof.created != null && prof.created.compareTo(bestCreated) > 0)) {
                bestCreated = prof.created;
                bestKey = e.getKey();
            }
        }
        if (bestKey == null) return false;

        MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(bestKey);
        prof.name = name;
        String icon = p.getString(KEY_ICON, null);
        if (icon != null && !icon.isEmpty()) prof.icon = icon;
        LauncherProfiles.mainProfileJson.profiles.put(bestKey, prof);
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, bestKey)
                .apply();
        LauncherProfiles.write();
        clear(ctx);
        return true;
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
