package net.kdt.pojavlaunch.shortcuts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

/**
 * Invisible trampoline activity opened by a home screen shortcut.
 *
 * <p>It resolves the profile, marks it current, then hands a routing instruction
 * to {@link LauncherActivity} through intent extras.</p>
 *
 * <p>The previous implementation posted the launch signal on a 500 ms delayed
 * handler and hoped the launcher had finished initialising. That is a race: on a
 * cold start the listener was often not registered yet and the tap silently did
 * nothing, while on a warm start the delay was pure lag. Routing through the
 * intent removes the guesswork — the launcher reads the extra whenever it is
 * genuinely ready.</p>
 */
public class ShortcutActivity extends Activity {

    private static final String TAG = "ShortcutActivity";

    /** Profile UUID key the shortcut points at. */
    public static final String EXTRA_PROFILE_KEY = "cs_shortcut_profile_key";

    /** Persisted {@link ShortcutType#getId()} describing the action to run. */
    public static final String EXTRA_ACTION = "cs_shortcut_action";

    /** Registry id, used for usage statistics. */
    public static final String EXTRA_SHORTCUT_ID = "cs_shortcut_id";

    /**
     * Legacy flag from the original implementation. Shortcuts created by older
     * builds carry this instead of {@link #EXTRA_ACTION}; honouring it keeps them
     * working after an update.
     */
    public static final String EXTRA_AUTO_LAUNCH = "cs_shortcut_auto_launch";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            openLauncher(null, null);
            return;
        }

        final String profileKey = intent.getStringExtra(EXTRA_PROFILE_KEY);
        final String shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID);
        final ShortcutType action = resolveAction(intent);

        if (profileKey == null || profileKey.isEmpty()) {
            openLauncher(null, null);
            return;
        }

        // Record the tap so "Manage shortcuts" can sort by popularity.
        if (shortcutId != null) {
            try {
                ShortcutRegistry.recordUsage(getApplicationContext(), shortcutId);
            } catch (Exception e) {
                Log.w(TAG, "Could not record shortcut usage", e);
            }
        }

        // Premium boot interstitial — only real game launches get the visible
        // "Opening Game…" screen; every other action stays a fast invisible hop.
        final boolean bootScreen = action == ShortcutType.LAUNCH;
        if (bootScreen) showBootScreen();

        // Profiles live in a JSON file, so loading is off the main thread.
        LauncherProfiles.loadAsync(() -> {
            MinecraftProfile profile = lookupProfile(profileKey);

            if (profile == null) {
                // The profile was deleted after the shortcut was made. Tell the
                // user instead of dumping them on a random screen, and clean up.
                Toast.makeText(getApplicationContext(),
                        R.string.shortcut_profile_missing, Toast.LENGTH_LONG).show();
                try {
                    ProfileShortcutHelper.removeShortcutsForProfile(
                            getApplicationContext(), profileKey);
                } catch (Exception ignored) {
                    // Cleanup is best effort.
                }
                openLauncher(null, null);
                return;
            }

            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                    .apply();

            if (bootScreen) bindBootProfile(profileKey, profile);

            // Opening a folder needs no launcher UI at all.
            if (action == ShortcutType.FOLDER) {
                if (openGameFolder(profile)) {
                    finish();
                    return;
                }
                // Could not resolve the directory — fall through to the launcher.
            }

            openLauncher(profileKey, action);
        });
    }

    /**
     * Put the shared boot screen on stage with a short staggered entrance.
     * LauncherActivity shows the very same layout above its home UI, so the
     * user never sees a flash between the shortcut and the launch itself.
     */
    private void showBootScreen() {
        setContentView(R.layout.screen_opening_game);

        android.view.View tile = findViewById(R.id.sg_mark_tile);
        android.view.View eyebrow = findViewById(R.id.sg_eyebrow);
        android.view.View title = findViewById(R.id.sg_title);
        android.view.View shimmer = findViewById(R.id.sg_indeterminate);

        animateIn(tile, 0);
        animateIn(eyebrow, 90);
        animateIn(title, 170);
        animateIn(shimmer, 320);
    }

    private void animateIn(@Nullable android.view.View v, long delayMs) {
        if (v == null) return;
        float rise = 16f * getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(rise);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(430)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    /** Fill the identity of the game being opened once profiles are resolved. */
    private void bindBootProfile(@NonNull String profileKey, @NonNull MinecraftProfile profile) {
        android.widget.TextView name = findViewById(R.id.sg_profile_name);
        android.widget.TextView chip = findViewById(R.id.sg_version_chip);

        if (name != null) {
            name.setText(profile.name != null && !profile.name.trim().isEmpty()
                    ? profile.name : profileKey);
            animateIn(name, 0);
        }
        String version = profile.lastVersionId;
        if (chip != null && version != null && !version.isEmpty() && !"Unknown".equals(version)) {
            chip.setText(version);
            chip.setVisibility(android.view.View.VISIBLE);
            animateIn(chip, 60);
        }
    }

    /**
     * Work out which action to run, tolerating shortcuts from older builds.
     */
    @NonNull
    private ShortcutType resolveAction(@NonNull Intent intent) {
        String actionId = intent.getStringExtra(EXTRA_ACTION);
        if (actionId != null) return ShortcutType.fromId(actionId);

        // Pre-multi-action shortcut: the boolean decided launch vs just open.
        boolean autoLaunch = intent.getBooleanExtra(EXTRA_AUTO_LAUNCH, true);
        return autoLaunch ? ShortcutType.LAUNCH : ShortcutType.OPEN_PROFILE;
    }

    @Nullable
    private MinecraftProfile lookupProfile(@NonNull String profileKey) {
        if (LauncherProfiles.mainProfileJson == null
                || LauncherProfiles.mainProfileJson.profiles == null) {
            return null;
        }
        return LauncherProfiles.mainProfileJson.profiles.get(profileKey);
    }

    /**
     * Hand the instruction to the launcher and finish.
     *
     * @param profileKey profile to select, or null to just open the launcher
     * @param action     what the launcher should do once it is ready
     */
    private void openLauncher(@Nullable String profileKey, @Nullable ShortcutType action) {
        Intent launcherIntent = new Intent(this, LauncherActivity.class);
        launcherIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        if (profileKey != null && action != null) {
            launcherIntent.putExtra(EXTRA_PROFILE_KEY, profileKey);
            launcherIntent.putExtra(EXTRA_ACTION, action.getId());
        }

        startActivity(launcherIntent);
        if (action == ShortcutType.LAUNCH) {
            // The launcher's identical boot overlay fades in over this screen.
            overridePendingTransition(android.R.anim.fade_in, 0);
        }
        finish();
    }

    /**
     * Open the profile's game directory through the app's documents provider.
     *
     * @return true when a file manager was launched
     */
    private boolean openGameFolder(@NonNull MinecraftProfile profile) {
        try {
            File gameDir = profile.resolveGameDir();
            if (gameDir == null || !gameDir.exists()) {
                Toast.makeText(getApplicationContext(),
                        R.string.shortcut_folder_missing, Toast.LENGTH_SHORT).show();
                return false;
            }
            Tools.openPath(this, gameDir, false);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to open game folder", e);
            Toast.makeText(getApplicationContext(),
                    R.string.shortcut_folder_missing, Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
