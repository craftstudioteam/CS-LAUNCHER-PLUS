package net.kdt.pojavlaunch.shortcuts;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Creates, updates and revokes Android home screen shortcuts for Minecraft profiles.
 *
 * <p>This replaces the earlier hand-rolled three-branch implementation. Everything
 * now goes through {@link ShortcutManagerCompat}, which internally picks
 * {@code requestPinShortcut} on API 26+, the API 25 equivalent, and the legacy
 * {@code INSTALL_SHORTCUT} broadcast below that — so the API-level branching, the
 * reflection hack and the duplicated API 25/26 methods are all gone.</p>
 *
 * <p>Beyond plain creation this class also supports:</p>
 * <ul>
 *   <li>multiple shortcuts per profile, one per {@link ShortcutType}</li>
 *   <li>a confirmation callback so the UI only celebrates on a real pin</li>
 *   <li>dynamic shortcuts, i.e. the app-icon long-press menu</li>
 *   <li>updating and removing existing shortcuts</li>
 * </ul>
 */
public final class ProfileShortcutHelper {

    private static final String TAG = "ProfileShortcutHelper";

    /** Directory under {@code filesDir} holding rendered shortcut icons. */
    private static final String SHORTCUTS_DIR = "shortcut_icons";

    /** Prefix for every shortcut id we own, so foreign ids are never touched. */
    private static final String ID_PREFIX = "cs_sc_";

    /** Action fired back to us once the launcher confirms a pin request. */
    public static final String ACTION_PIN_RESULT =
            "net.kdt.pojavlaunch.action.SHORTCUT_PINNED";

    /**
     * Android caps the app-icon long-press menu. Four leaves room for the system's
     * own entries on launchers that add them.
     */
    private static final int MAX_DYNAMIC_SHORTCUTS = 4;

    private ProfileShortcutHelper() {
        // static only
    }

    // ─── Ids ───────────────────────────────────────────────────────────

    /** Deterministic shortcut id for a profile + action pair. */
    @NonNull
    public static String buildShortcutId(@NonNull String profileKey,
                                         @NonNull ShortcutType type) {
        return ID_PREFIX + profileKey.replace("-", "_") + "_" + type.getId();
    }

    // ─── Capability checks ─────────────────────────────────────────────

    /**
     * Whether the current home screen accepts pinned shortcuts.
     * Some launchers (and most TV launchers) return false here.
     */
    public static boolean isPinningSupported(@NonNull Context context) {
        try {
            return ShortcutManagerCompat.isRequestPinShortcutSupported(context);
        } catch (Exception e) {
            Log.w(TAG, "Pin support check failed", e);
            return false;
        }
    }

    // ─── Creation ──────────────────────────────────────────────────────

    /**
     * Request that the launcher pin a shortcut.
     *
     * @param context    any context; the application context is used internally
     * @param profileKey profile UUID key
     * @param profile    the profile itself
     * @param type       what the shortcut should do
     * @param label      visible label; falls back to the profile name when blank
     * @param icon       fully rendered icon bitmap; a profile icon is derived when null
     * @param iconSource marker stored in the registry ("profile"/"skin"/"custom"/"loader")
     * @param adaptive   whether {@code icon} was rendered with adaptive insets
     * @return true when the request reached the launcher. The user may still
     * decline, which is reported through {@link ShortcutPinReceiver}.
     */
    public static boolean createShortcut(@NonNull Context context,
                                         @NonNull String profileKey,
                                         @NonNull MinecraftProfile profile,
                                         @NonNull ShortcutType type,
                                         @Nullable String label,
                                         @Nullable Bitmap icon,
                                         @Nullable String iconSource,
                                         boolean adaptive) {

        Context appContext = context.getApplicationContext();
        String shortcutLabel = resolveLabel(label, profile, type);

        Bitmap iconBitmap = icon;
        if (iconBitmap == null) {
            iconBitmap = renderDefaultIcon(appContext, profileKey, profile, type, adaptive);
        }
        if (iconBitmap == null) {
            Log.w(TAG, "Could not resolve an icon for profile " + profileKey);
            return false;
        }

        String shortcutId = buildShortcutId(profileKey, type);
        // Persist the icon so "Manage shortcuts" can show a thumbnail later.
        saveShortcutIcon(appContext, shortcutId, iconBitmap);

        ShortcutInfoCompat shortcut = buildShortcutInfo(
                appContext, shortcutId, shortcutLabel, iconBitmap, profileKey, type, adaptive);

        try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(appContext)) {
                Log.w(TAG, "Launcher does not support pinned shortcuts");
                return false;
            }

            // A callback intent lets us distinguish "user pinned it" from
            // "user dismissed the dialog" — the old code always claimed success.
            IntentSender sender = buildPinResultSender(appContext, shortcutId);
            boolean requested = ShortcutManagerCompat.requestPinShortcut(
                    appContext, shortcut, sender);

            if (requested) {
                // Register optimistically; the receiver confirms or the entry is
                // pruned on the next manage-screen refresh.
                ShortcutRegistry.put(appContext, new ShortcutRecord(
                        shortcutId, profileKey, profile.name, shortcutLabel,
                        type, iconSource, adaptive, false));
            }
            return requested;

        } catch (Exception e) {
            Log.e(TAG, "Failed to request pinned shortcut", e);
            return false;
        }
    }

    /**
     * Build the PendingIntent the system fires once a pin actually happens.
     * Returns null when a sender cannot be created, in which case the pin still
     * works but arrives unconfirmed.
     */
    @Nullable
    private static IntentSender buildPinResultSender(@NonNull Context context,
                                                     @NonNull String shortcutId) {
        try {
            Intent callback = new Intent(context, ShortcutPinReceiver.class);
            callback.setAction(ACTION_PIN_RESULT);
            callback.putExtra(ShortcutPinReceiver.EXTRA_SHORTCUT_ID, shortcutId);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Mutability must be explicit on API 31+; IMMUTABLE is correct here
                // because the system does not need to fill anything in.
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pending = PendingIntent.getBroadcast(
                    context, shortcutId.hashCode(), callback, flags);
            return pending.getIntentSender();
        } catch (Exception e) {
            Log.w(TAG, "Could not build pin result sender", e);
            return null;
        }
    }

    @NonNull
    private static ShortcutInfoCompat buildShortcutInfo(@NonNull Context context,
                                                        @NonNull String shortcutId,
                                                        @NonNull String label,
                                                        @NonNull Bitmap icon,
                                                        @NonNull String profileKey,
                                                        @NonNull ShortcutType type,
                                                        boolean adaptive) {
        Intent intent = new Intent(context, ShortcutActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(ShortcutActivity.EXTRA_PROFILE_KEY, profileKey);
        intent.putExtra(ShortcutActivity.EXTRA_ACTION, type.getId());
        intent.putExtra(ShortcutActivity.EXTRA_SHORTCUT_ID, shortcutId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Adaptive icons get the masked treatment on Android 8+; older launchers
        // fall back to the plain bitmap automatically.
        IconCompat iconCompat = adaptive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? IconCompat.createWithAdaptiveBitmap(icon)
                : IconCompat.createWithBitmap(icon);

        return new ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(truncate(label, 12))
                .setLongLabel(truncate(label, 25))
                .setIcon(iconCompat)
                .setIntent(intent)
                .build();
    }

    // ─── Dynamic shortcuts (app icon long-press menu) ──────────────────

    /**
     * Rebuild the app-icon long-press menu from the most-used profiles.
     *
     * <p>Unlike pinned shortcuts these need no user confirmation, so the launcher
     * gains a useful quick menu for free. Safe to call on every launcher resume.</p>
     *
     * @param profileKeys profile keys in the order they should appear
     */
    public static void syncDynamicShortcuts(@NonNull Context context,
                                            @NonNull List<String> profileKeys) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;

        Context appContext = context.getApplicationContext();

        try {
            List<ShortcutInfoCompat> shortcuts = new ArrayList<>();
            int limit = Math.min(profileKeys.size(), MAX_DYNAMIC_SHORTCUTS);
            int maxAllowed = ShortcutManagerCompat.getMaxShortcutCountPerActivity(appContext);
            if (maxAllowed > 0) limit = Math.min(limit, maxAllowed);

            for (int i = 0; i < limit; i++) {
                String profileKey = profileKeys.get(i);
                MinecraftProfile profile = lookupProfile(profileKey);
                if (profile == null || profile.name == null || profile.name.isEmpty()) continue;

                Bitmap icon = renderDefaultIcon(appContext, profileKey, profile,
                        ShortcutType.LAUNCH, true);
                if (icon == null) continue;

                String id = ID_PREFIX + "dyn_" + profileKey.replace("-", "_");
                shortcuts.add(buildShortcutInfo(appContext, id, profile.name, icon,
                        profileKey, ShortcutType.LAUNCH, true));
            }

            // setDynamicShortcuts replaces the whole set, so removed profiles vanish.
            ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts);
        } catch (Exception e) {
            // Never let a launcher quirk crash the app on resume.
            Log.w(TAG, "Failed to sync dynamic shortcuts", e);
        }
    }

    /** Remove every dynamic shortcut, leaving pinned ones untouched. */
    public static void clearDynamicShortcuts(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
        try {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context.getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "Failed to clear dynamic shortcuts", e);
        }
    }

    // ─── Update / removal ──────────────────────────────────────────────

    /**
     * Push a new label and icon to an already pinned shortcut.
     * Only works on API 25+; older launchers keep the original artwork.
     */
    public static boolean updateShortcut(@NonNull Context context,
                                         @NonNull ShortcutRecord record,
                                         @NonNull String newLabel,
                                         @Nullable Bitmap newIcon) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false;

        Context appContext = context.getApplicationContext();
        MinecraftProfile profile = lookupProfile(record.profileKey);
        if (profile == null) return false;

        Bitmap icon = newIcon != null
                ? newIcon
                : loadShortcutIcon(appContext, record.shortcutId);
        if (icon == null) {
            icon = renderDefaultIcon(appContext, record.profileKey, profile,
                    record.getType(), record.adaptiveIcon);
        }
        if (icon == null) return false;

        if (newIcon != null) saveShortcutIcon(appContext, record.shortcutId, newIcon);

        ShortcutInfoCompat updated = buildShortcutInfo(appContext, record.shortcutId,
                newLabel, icon, record.profileKey, record.getType(), record.adaptiveIcon);

        try {
            boolean ok = ShortcutManagerCompat.updateShortcuts(
                    appContext, Collections.singletonList(updated));
            if (ok) {
                record.label = newLabel;
                ShortcutRegistry.put(appContext, record);
            }
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "Failed to update shortcut " + record.shortcutId, e);
            return false;
        }
    }

    /**
     * Revoke a shortcut.
     *
     * <p>On API 26+ {@code disableShortcuts} greys it out on the home screen with a
     * message. Truly deleting a pinned shortcut is the launcher's prerogative, so
     * we additionally drop our registry entry and cached icon.</p>
     */
    public static void removeShortcut(@NonNull Context context,
                                      @NonNull String shortcutId) {
        Context appContext = context.getApplicationContext();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                ShortcutManager manager = appContext.getSystemService(ShortcutManager.class);
                if (manager != null) {
                    List<String> ids = Collections.singletonList(shortcutId);
                    manager.removeDynamicShortcuts(ids);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        manager.disableShortcuts(ids,
                                appContext.getString(R.string.shortcut_disabled_message));
                    } else {
                        manager.disableShortcuts(ids);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to disable shortcut " + shortcutId, e);
            }
        }

        ShortcutRegistry.remove(appContext, shortcutId);
        deleteShortcutIcon(appContext, shortcutId);
    }

    /** Revoke every shortcut belonging to one profile. Call when deleting a profile. */
    public static void removeShortcutsForProfile(@NonNull Context context,
                                                 @NonNull String profileKey) {
        Context appContext = context.getApplicationContext();
        for (ShortcutRecord record : ShortcutRegistry.findByProfile(appContext, profileKey)) {
            removeShortcut(appContext, record.shortcutId);
        }
        ShortcutRegistry.removeForProfile(appContext, profileKey);
    }

    /** Revoke everything this launcher created. */
    public static void removeAllShortcuts(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        for (ShortcutRecord record : ShortcutRegistry.load(appContext)) {
            removeShortcut(appContext, record.shortcutId);
        }
        clearDynamicShortcuts(appContext);
        ShortcutRegistry.clear(appContext);
    }

    /**
     * Drop registry entries whose profile has been deleted, and disable the
     * corresponding home screen icons.
     *
     * @return how many stale shortcuts were pruned
     */
    public static int pruneOrphanShortcuts(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        List<String> liveKeys = new ArrayList<>();
        if (LauncherProfiles.mainProfileJson != null
                && LauncherProfiles.mainProfileJson.profiles != null) {
            liveKeys.addAll(LauncherProfiles.mainProfileJson.profiles.keySet());
        } else {
            // Profiles not loaded yet — pruning now would delete everything.
            return 0;
        }

        List<String> pruned = ShortcutRegistry.pruneOrphans(appContext, liveKeys);
        for (String shortcutId : pruned) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                try {
                    ShortcutManager manager = appContext.getSystemService(ShortcutManager.class);
                    if (manager != null) {
                        manager.disableShortcuts(Collections.singletonList(shortcutId));
                    }
                } catch (Exception ignored) {
                    // Best effort only.
                }
            }
            deleteShortcutIcon(appContext, shortcutId);
        }
        return pruned.size();
    }

    // ─── Icon helpers ──────────────────────────────────────────────────

    /**
     * Render the standard icon for a profile: its configured icon, masked to a
     * squircle, badged with the action glyph.
     */
    @Nullable
    public static Bitmap renderDefaultIcon(@NonNull Context context,
                                           @NonNull String profileKey,
                                           @NonNull MinecraftProfile profile,
                                           @NonNull ShortcutType type,
                                           boolean adaptive) {
        Bitmap source = resolveProfileBitmap(context, profileKey, profile);
        return ShortcutIconRenderer.render(context, source,
                ShortcutIconRenderer.IconShape.SQUIRCLE, type,
                resolveAccentColor(context), adaptive);
    }

    /** The raw, unbadged artwork for a profile — its icon, or a loader fallback. */
    @Nullable
    public static Bitmap resolveProfileBitmap(@NonNull Context context,
                                              @NonNull String profileKey,
                                              @NonNull MinecraftProfile profile) {
        try {
            Drawable drawable = ProfileIconCache.fetchIcon(
                    context.getResources(), profileKey, profile.icon);
            Bitmap bitmap = ShortcutIconRenderer.drawableToBitmap(drawable);
            if (bitmap != null) return bitmap;
        } catch (Exception e) {
            Log.w(TAG, "Profile icon load failed for " + profileKey, e);
        }

        // Fall back to a mod-loader glyph inferred from the version id.
        int fallbackRes = resolveLoaderIcon(profile.lastVersionId);
        try {
            Drawable fallback = context.getDrawable(fallbackRes);
            return ShortcutIconRenderer.drawableToBitmap(fallback);
        } catch (Exception e) {
            Log.w(TAG, "Fallback icon load failed", e);
            return null;
        }
    }

    /** Pick a loader badge drawable from a version id such as "1.20.1-fabric". */
    public static int resolveLoaderIcon(@Nullable String lastVersionId) {
        if (lastVersionId == null) return R.drawable.ic_cs_logo_placeholder;
        String lower = lastVersionId.toLowerCase();
        if (lower.contains("fabric")) return R.drawable.ic_fabric;
        if (lower.contains("quilt")) return R.drawable.ic_quilt;
        if (lower.contains("neoforge") || lower.contains("forge")) return R.drawable.ic_forge;
        if (lower.contains("optifine")) return R.drawable.ic_optifine;
        return R.drawable.ic_vanilla_grass;
    }

    /** Accent colour used behind action badges. */
    @ColorInt
    public static int resolveAccentColor(@NonNull Context context) {
        try {
            return context.getResources().getColor(R.color.premium_cyan);
        } catch (Exception e) {
            return Color.parseColor("#D0D0D0");
        }
    }

    @Nullable
    public static Bitmap loadShortcutIcon(@NonNull Context context,
                                          @NonNull String shortcutId) {
        File iconFile = getIconFile(context, shortcutId);
        if (!iconFile.exists()) return null;
        return BitmapFactory.decodeFile(iconFile.getAbsolutePath());
    }

    private static void saveShortcutIcon(@NonNull Context context,
                                         @NonNull String shortcutId,
                                         @NonNull Bitmap icon) {
        File iconFile = getIconFile(context, shortcutId);
        File parent = iconFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "Could not create shortcut icon directory");
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(iconFile)) {
            icon.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            Log.w(TAG, "Failed to save shortcut icon", e);
        }
    }

    private static void deleteShortcutIcon(@NonNull Context context,
                                           @NonNull String shortcutId) {
        File iconFile = getIconFile(context, shortcutId);
        if (iconFile.exists() && !iconFile.delete()) {
            Log.w(TAG, "Could not delete cached icon for " + shortcutId);
        }
    }

    @NonNull
    private static File getIconFile(@NonNull Context context, @NonNull String shortcutId) {
        File dir = new File(context.getApplicationContext().getFilesDir(), SHORTCUTS_DIR);
        return new File(dir, shortcutId + ".png");
    }

    // ─── Misc ──────────────────────────────────────────────────────────

    @Nullable
    private static MinecraftProfile lookupProfile(@NonNull String profileKey) {
        if (LauncherProfiles.mainProfileJson == null
                || LauncherProfiles.mainProfileJson.profiles == null) {
            return null;
        }
        return LauncherProfiles.mainProfileJson.profiles.get(profileKey);
    }

    @NonNull
    private static String resolveLabel(@Nullable String label,
                                       @NonNull MinecraftProfile profile,
                                       @NonNull ShortcutType type) {
        if (label != null && !label.trim().isEmpty()) return label.trim();
        if (profile.name != null && !profile.name.trim().isEmpty()) return profile.name.trim();
        return "Minecraft";
    }

    /** Launchers silently clip long labels; trimming with an ellipsis reads better. */
    @NonNull
    private static String truncate(@NonNull String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(1, maxLength - 1)).trim() + "…";
    }
}
