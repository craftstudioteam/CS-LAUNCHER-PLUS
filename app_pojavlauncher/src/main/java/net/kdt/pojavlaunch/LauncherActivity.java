package net.kdt.pojavlaunch;

import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;

import android.Manifest;
import android.app.NotificationManager;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.fragments.ModsSearchFragment;
import net.kdt.pojavlaunch.fragments.AboutFragment;
import net.kdt.pojavlaunch.fragments.CursorCustomizationFragment;
import net.kdt.pojavlaunch.fragments.SkinManagerFragment;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.graphics.Typeface;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;
import com.kdt.mcgui.mcAccountSpinner;

import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.MicrosoftLoginFragment;
import net.kdt.pojavlaunch.fragments.SelectAuthFragment;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.modloaders.modpacks.ModloaderInstallTracker;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModLoader;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackInstaller;
import net.kdt.pojavlaunch.modloaders.modpacks.api.NotificationDownloadListener;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.IconCacheJanitor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.AsyncVersionList;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.DateUtils;
import net.kdt.pojavlaunch.utils.NotificationUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;

import android.view.Window;
import android.view.WindowManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class LauncherActivity extends BaseActivity {
    public static final String SETTING_FRAGMENT_TAG = "SETTINGS_FRAGMENT";

    public final ActivityResultLauncher<Object> modInstallerLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("jar"), (data)->{
                if(data != null) Tools.launchModInstaller(this, data);
            });
    public final ActivityResultLauncher<Object> modpackImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension(new String[]{"zip", "mrpack"}), (data)->{
                if(data != null) {
                    PojavApplication.sExecutorService.execute(() -> {
                        // O3 (Copper port): stream the DocumentsUI content into a
                        // cache file ONCE (with copy progress), then run the whole
                        // import against the local file. The old path re-opened the
                        // content:// URI 3+ times, which is very slow on SAF providers.
                        try {
                            long fileSize = -1;
                            try (Cursor returnCursor = getContentResolver().query(data, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                                if (returnCursor != null && returnCursor.moveToFirst()) {
                                    fileSize = returnCursor.getLong(0);
                                }
                            }
                            File modpackFile = new File(Tools.DIR_CACHE, "import_modpack_placeholdername.cf");
                            try (InputStream inputStream = getContentResolver().openInputStream(data)) {
                                FileOutputStream output = new FileOutputStream(modpackFile);
                                byte[] b = new byte[262144];
                                int read;
                                int readTotal = 0;
                                while ((read = inputStream.read(b)) != -1) {
                                    output.write(b, 0, read);
                                    readTotal += read;
                                    String readMB = fileSize > 0 ? String.format(Locale.US, "%.2f", readTotal / (1024.0 * 1024.0)) : "unknown";
                                    String totalMB = fileSize > 0 ? String.format(Locale.US, "%.2f", fileSize / (1024.0 * 1024.0)) : "unknown";
                                    int progress = fileSize > 0 ? (int) ((readTotal * 100L) / fileSize) : 0;
                                    ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, progress, R.string.import_modpack_copy, readMB, totalMB);
                                }
                                output.flush();
                                output.close();
                            }
                            ModLoader loaderInfo = new CommonApi(getString(R.string.curseforge_api_key)).importModpack(modpackFile);
                            modpackFile.delete();
                            if (loaderInfo == null) return;
                            loaderInfo.getDownloadTask(new NotificationDownloadListener(this, loaderInfo)).run();
                        } catch (IOException e) {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                            Tools.showErrorRemote(this, R.string.modpack_install_download_failed, e);
                        } catch (IllegalArgumentException e) {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                            Tools.showError(this, R.string.not_modpack_file, e);
                        } catch (NoSuchAlgorithmException e) {
                            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                            // Should literally never happen because SHA-1 is required Java spec
                            throw new RuntimeException(e);
                        }
                    });
                }
            });

    private mcAccountSpinner mAccountSpinner;
    private FragmentContainerView mFragmentView;
    private ImageButton mSettingsButton;
    private ProgressLayout mProgressLayout;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private ModloaderInstallTracker mInstallTracker;
    private NotificationManager mNotificationManager;

    // ── Shortcut boot overlay ("Opening Game…") ─────────────────────────
    private View mBootOverlay;
    private android.widget.TextView mBootProfileName;
    private android.widget.TextView mBootVersionChip;
    private android.widget.TextView mBootStatus;
    private android.widget.TextView mBootPercent;
    private android.widget.ProgressBar mBootBeam;
    private android.widget.ProgressBar mBootIndeterminate;
    private View mBootProgressBlock;
    private boolean mBootActive = false;
    private boolean mBootSawTasks = false;
    private final Runnable mBootFailsafe = this::onBootFailsafe;
    private final Runnable mBootPostTaskHide = () -> hideBootOverlay(false);
    private final TaskCountListener mBootTaskListener = this::onBootTaskCount;
    private final ProgressListener mBootProgressListener = new ProgressListener() {
        @Override public void onProgressStarted() { }
        @Override public void onProgressUpdated(int progress, int resid, Object... va) {
            runOnUiThread(() -> onBootProgress(progress, resid));
        }
        @Override public void onProgressEnded() { }
    };

    // ── Launch sequence overlay (premium LAUNCH motion — never a download) ──
    private net.kdt.pojavlaunch.launch.LaunchOverlayView mLaunchOverlay;
    private final net.kdt.pojavlaunch.launch.LaunchTracker.PhaseListener mLaunchPhaseListener =
            this::onLaunchPhase;

    private void onLaunchPhase(@NonNull net.kdt.pojavlaunch.launch.LaunchTracker.Phase phase) {
        if (phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.IDLE
                || phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.FAILED) {
            hideLaunchOverlay();
            return;
        }
        if (mBootActive) {
            // Shortcut flow: the boot overlay stays authoritative; feed it phases.
            if (mBootStatus != null) {
                mBootStatus.setText(
                        phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.STARTING
                                ? R.string.sg_launching
                                : phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.RUNTIME
                                ? R.string.sg_runtime
                                : phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.DOWNLOADING
                                ? R.string.sg_downloading_files
                                : R.string.sg_verifying);
            }
            // Real phases are flowing — the no-task failsafe can stand down.
            if (mBootOverlay != null) mBootOverlay.removeCallbacks(mBootFailsafe);
            return;
        }
        if (phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.RUNTIME
                || phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.DOWNLOADING) {
            // Real installs/downloads → the Download Console owns the chrome.
            hideLaunchOverlay();
            return;
        }
        showLaunchOverlay(phase);
    }

    private void showLaunchOverlay(@NonNull net.kdt.pojavlaunch.launch.LaunchTracker.Phase phase) {
        if (isFinishing() || isDestroyed()) return;
        if (mLaunchOverlay == null) {
            mLaunchOverlay = new net.kdt.pojavlaunch.launch.LaunchOverlayView(this);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            addContentView(mLaunchOverlay, lp);
        }
        mLaunchOverlay.bind(net.kdt.pojavlaunch.launch.LaunchTracker.getProfileName(),
                net.kdt.pojavlaunch.launch.LaunchTracker.getVersionId());
        if (!mLaunchOverlay.isShowing()) mLaunchOverlay.startLaunch();
        mLaunchOverlay.setPhase(phase);
    }

    private void hideLaunchOverlay() {
        if (mLaunchOverlay != null && mLaunchOverlay.isShowing()) {
            mLaunchOverlay.finishAndHide(null);
        }
    }

    /* Allows to switch from one button "type" to another */
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbackListener = new FragmentManager.FragmentLifecycleCallbacks() {
        @Override
        public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
            mSettingsButton.setImageDrawable(ContextCompat.getDrawable(getBaseContext(), f instanceof MainMenuFragment
                    ? R.drawable.ic_menu_settings : R.drawable.ic_menu_home));
        }
    };

    /* Listener for the back button in settings */
    private final ExtraListener<String> mBackPreferenceListener = (key, value) -> {
        if(value.equals("true")) onBackPressed();
        return false;
    };

    /* Listener for the auth method selection screen */
    private final ExtraListener<Boolean> mSelectAuthMethod = (key, value) -> {
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        // Allow starting the add account only from the main menu, should it be moved to fragment itself ?
        if(!(fragment instanceof MainMenuFragment)) return false;

        // In landscape two-pane mode, load into right pane; otherwise full-screen swap
        MainMenuFragment mmf = (MainMenuFragment) fragment;
        if (!mmf.tryOpenInRightPane(SelectAuthFragment.class, SelectAuthFragment.TAG, null)) {
            Tools.swapFragment(this, SelectAuthFragment.class, SelectAuthFragment.TAG, null);
        }
        return false;
    };

    /* Listener for the settings fragment */
    private final View.OnClickListener mSettingButtonListener = v -> {
        navTap(v, R.string.cs_navtip_settings);
        Fragment fragment = getSupportFragmentManager().findFragmentById(mFragmentView.getId());
        if (fragment instanceof MainMenuFragment) {
            MainMenuFragment mmf = (MainMenuFragment) fragment;
            // In two-pane landscape: if right pane already has content, pressing the
            // gear/home button pops back to home. If pane is at home, open settings.
            // Always open settings full-screen to match the new UI transformation
            Tools.swapFragment(this, LauncherPreferenceFragment.class, SETTING_FRAGMENT_TAG, null);
        } else {
            // Portrait: the settings button doubles as a home button when not on main menu
            Tools.backToMainMenu(this);
        }
    };

    private final ExtraListener<Boolean> mLaunchGameListener = (key, value) -> {
        if(mProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }
        // Relaunch guard (item-1/6 companion fix): the launch overlay no longer
        // eats taps, so PLAY stays reachable mid-launch — a second tap must be
        // absorbed here semantically. Any non-IDLE/non-FAILED phase means a
        // launch sequence is ACTIVE; refuse quiet double-starts.
        net.kdt.pojavlaunch.launch.LaunchTracker.Phase csPhase =
                net.kdt.pojavlaunch.launch.LaunchTracker.getPhase();
        if (csPhase != net.kdt.pojavlaunch.launch.LaunchTracker.Phase.IDLE
                && csPhase != net.kdt.pojavlaunch.launch.LaunchTracker.Phase.FAILED) {
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }

        String selectedProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE,"");
        if (LauncherProfiles.mainProfileJson == null || !LauncherProfiles.mainProfileJson.profiles.containsKey(selectedProfile)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }
        MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(selectedProfile);
        if (prof == null || prof.lastVersionId == null || "Unknown".equals(prof.lastVersionId)){
            Toast.makeText(this, R.string.error_no_version, Toast.LENGTH_LONG).show();
            return false;
        }

        // Commit the exact profile being launched before Java arguments are built.
        // JREUtils resolves RAM from this key, so it can never accidentally use
        // the RAM setting of a previously selected profile.
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, selectedProfile)
                .commit();
        Log.i("ProfileLaunch", "Launching " + selectedProfile + " with global RAM="
                + LauncherPreferences.PREF_RAM_ALLOCATION + "MB");

        if(mAccountSpinner.getSelectedAccount() == null){
            Toast.makeText(this, R.string.no_saved_accounts, Toast.LENGTH_LONG).show();
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return false;
        }
        String normalizedVersionId = AsyncMinecraftDownloader.normalizeVersionId(prof.lastVersionId);
        JMinecraftVersionList.Version mcVersion = AsyncMinecraftDownloader.getListedVersion(normalizedVersionId);

        // Do not load when is a modded version or older than minecraft 1.3 on demo account
        if (mAccountSpinner.getSelectedAccount().isDemo()) {
            boolean isOlderThan13 = true;

            if (mcVersion != null) {
                try {
                    isOlderThan13 = DateUtils.dateBefore(DateUtils.parseReleaseDate(mcVersion.releaseTime), 2012, 6, 22);
                } catch (ParseException ignored) {}
            }

            if (isOlderThan13) {
                hasNoOnlineProfileDialog(this, getString(R.string.global_error), getString(R.string.demo_versions_supported));
                return false;
            }
        }

        // Begin the LAUNCH sequence — phases now drive a premium launch overlay
        // that is completely separate from the download experience.
        net.kdt.pojavlaunch.launch.LaunchTracker.begin(prof.name, normalizedVersionId);
        new MinecraftDownloader().start(
                this,
                mcVersion,
                normalizedVersionId,
                new ContextAwareDoneListener(this, normalizedVersionId)
        );
        return false;
    };

    private final TaskCountListener mDoubleLaunchPreventionListener = taskCount -> {
        // Hide the notification that starts the game if there are tasks executing.
        // Prevents the user from trying to launch the game with tasks ongoing.
        if(taskCount > 0) {
            Tools.runOnUiThread(() ->
                    mNotificationManager.cancel(NotificationUtils.NOTIFICATION_ID_GAME_START)
            );
        }
    };

    // ─── Shortcut boot overlay ("Opening Game…") ───────────────────────

    /** True when the intent carries a shortcut instruction for an immediate game launch. */
    private static boolean isShortcutLaunchRequested(@Nullable Intent intent) {
        if (intent == null) return false;
        if (!intent.hasExtra(net.kdt.pojavlaunch.shortcuts.ShortcutActivity.EXTRA_PROFILE_KEY)) return false;
        String actionId = intent.getStringExtra(net.kdt.pojavlaunch.shortcuts.ShortcutActivity.EXTRA_ACTION);
        return actionId != null && net.kdt.pojavlaunch.shortcuts.ShortcutType.LAUNCH
                == net.kdt.pojavlaunch.shortcuts.ShortcutType.fromId(actionId);
    }

    private void bindBootOverlayViews() {
        if (mBootOverlay != null) return;
        mBootOverlay = findViewById(R.id.launch_boot_overlay);
        if (mBootOverlay == null) return;
        mBootProfileName = mBootOverlay.findViewById(R.id.sg_profile_name);
        mBootVersionChip = mBootOverlay.findViewById(R.id.sg_version_chip);
        mBootStatus = mBootOverlay.findViewById(R.id.sg_status);
        mBootPercent = mBootOverlay.findViewById(R.id.sg_percent);
        mBootBeam = mBootOverlay.findViewById(R.id.sg_beam);
        mBootIndeterminate = mBootOverlay.findViewById(R.id.sg_indeterminate);
        mBootProgressBlock = mBootOverlay.findViewById(R.id.sg_progress_block);
    }

    /**
     * Present the boot overlay instantly — ShortcutActivity already staged the
     * entrance animation on the identical screen, so we must not replay it.
     */
    private void showBootOverlayInstant() {
        bindBootOverlayViews();
        if (mBootOverlay == null || mBootActive) return;
        mBootActive = true;
        mBootSawTasks = false;
        mBootOverlay.setAlpha(1f);
        mBootOverlay.setTranslationY(0f);
        mBootOverlay.setVisibility(View.VISIBLE);
        if (mBootIndeterminate != null) mBootIndeterminate.setVisibility(View.VISIBLE);
        if (mBootProgressBlock != null) {
            mBootProgressBlock.setVisibility(View.GONE);
            mBootProgressBlock.setAlpha(1f);
        }
        if (mBootStatus != null) mBootStatus.setText(R.string.sg_preparing);
        if (mBootProfileName != null) mBootProfileName.setText("");
        if (mBootVersionChip != null) mBootVersionChip.setVisibility(View.GONE);
        ProgressKeeper.addTaskCountListener(mBootTaskListener);
        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, mBootProgressListener);
        ProgressKeeper.addListener(ProgressLayout.UNPACK_RUNTIME, mBootProgressListener);
        mBootOverlay.removeCallbacks(mBootFailsafe);
        // If the launch never produces tasks (missing account / bad profile), the
        // precondition toast must not stay hidden behind the overlay.
        mBootOverlay.postDelayed(mBootFailsafe, 3400);
    }

    /** Fill the profile identity once the router has resolved it. */
    public void showLaunchBoot(@Nullable String profileName, @Nullable String versionId) {
        runOnUiThread(() -> {
            if (!mBootActive) showBootOverlayInstant();
            if (mBootProfileName != null && profileName != null) {
                mBootProfileName.setText(profileName);
            }
            if (mBootVersionChip != null && versionId != null
                    && !versionId.isEmpty() && !"Unknown".equals(versionId)) {
                mBootVersionChip.setText(versionId);
                if (mBootVersionChip.getVisibility() != View.VISIBLE) {
                    mBootVersionChip.setAlpha(0f);
                    mBootVersionChip.setVisibility(View.VISIBLE);
                    mBootVersionChip.animate().alpha(1f).setDuration(220).start();
                }
            }
        });
    }

    private void onBootTaskCount(int tc) {
        runOnUiThread(() -> {
            if (!mBootActive) return;
            if (tc > 0) {
                mBootSawTasks = true;
                if (mBootOverlay != null) {
                    mBootOverlay.removeCallbacks(mBootFailsafe);
                    mBootOverlay.removeCallbacks(mBootPostTaskHide);
                }
                swapBootShimmerForProgress();
                if (mBootStatus != null) mBootStatus.setText(R.string.sg_verifying);
            } else if (mBootSawTasks) {
                // Tasks drained: MainActivity should cover us within seconds (the
                // launcher is finished on launch). If that never happens the
                // download failed — reveal the launcher again.
                if (mBootStatus != null) mBootStatus.setText(R.string.sg_launching);
                if (mBootOverlay != null) {
                    mBootOverlay.removeCallbacks(mBootPostTaskHide);
                    mBootOverlay.postDelayed(mBootPostTaskHide, 3800);
                }
            }
        });
    }

    private void onBootProgress(int progress, int resid) {
        if (!mBootActive) return;
        if (progress >= 0) {
            swapBootShimmerForProgress();
            if (mBootPercent != null) mBootPercent.setText(progress + "%");
            if (mBootBeam != null) mBootBeam.setProgress(progress);
        }
        if (mBootStatus != null) {
            if (resid == R.string.newdl_downloading_game_files
                    || resid == R.string.newdl_downloading_game_files_size) {
                mBootStatus.setText(R.string.sg_downloading_files);
            } else if (resid == R.string.newdl_downloading_jre_runtime) {
                mBootStatus.setText(R.string.sg_runtime);
            } else if (resid == R.string.newdl_starting) {
                mBootStatus.setText(R.string.sg_preparing);
            }
        }
    }

    private void swapBootShimmerForProgress() {
        if (mBootIndeterminate != null && mBootIndeterminate.getVisibility() == View.VISIBLE) {
            mBootIndeterminate.setVisibility(View.GONE);
            if (mBootProgressBlock != null) {
                mBootProgressBlock.setAlpha(0f);
                mBootProgressBlock.setVisibility(View.VISIBLE);
                mBootProgressBlock.animate().alpha(1f).setDuration(260).start();
            }
        }
    }

    private void onBootFailsafe() {
        if (mBootActive && !mBootSawTasks) hideBootOverlay(false);
    }

    private void hideBootOverlay(boolean launching) {
        if (!mBootActive) return;
        mBootActive = false;
        ProgressKeeper.removeTaskCountListener(mBootTaskListener);
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mBootProgressListener);
        ProgressKeeper.removeListener(ProgressLayout.UNPACK_RUNTIME, mBootProgressListener);
        if (!launching) {
            // The game never started — drop the one-shot log flag as well.
            net.kdt.pojavlaunch.MainActivity.sAutoShowLogsOnce = false;
        }
        if (mBootOverlay != null) {
            mBootOverlay.removeCallbacks(mBootFailsafe);
            mBootOverlay.removeCallbacks(mBootPostTaskHide);
            mBootOverlay.animate().cancel();
            mBootOverlay.animate().alpha(0f).setDuration(240).withEndAction(() -> {
                mBootOverlay.setVisibility(View.GONE);
                mBootOverlay.setAlpha(1f);
            }).start();
        }
    }

    private ActivityResultLauncher<String> mRequestNotificationPermissionLauncher;
    private ActivityResultLauncher<String> mRequestMicrophonePermissionLauncher;
    private WeakReference<Runnable> mRequestNotificationPermissionRunnable;
    private WeakReference<Runnable> mRequestMicrophonePermissionRunnable;

    @Override
    protected boolean shouldIgnoreNotch() {
        return false;
    }

    @Override
    public boolean setFullscreen() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Edge-to-edge setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            );
        }

        // Apply saved colour theme before layout inflation
        setTheme(net.kdt.pojavlaunch.theme.ThemeManager.getSavedTheme());
        
        // The launcher UI is designed as a wide dashboard: keep every flow in
        // landscape instead of allowing a partial portrait layout to appear.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        setContentView(R.layout.activity_pojav_launcher);
        
        // Handle window insets properly to prevent navigation bar space reservation
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content),
            (v, insets) -> WindowInsetsCompat.CONSUMED);
        FragmentManager fragmentManager = getSupportFragmentManager();
        // One motion policy for every launcher page (home, settings and all child
        // flows), so new screens do not need to reinvent their own entrance logic.
        fragmentManager.registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment fragment,
                                              @NonNull View view, @Nullable Bundle savedInstanceState) {
                view.post(() -> UiMotion.revealScreen(view));
            }
        }, true);
        // If we don't have a back stack root yet...
        if(fragmentManager.getBackStackEntryCount() < 1) {
            // Check if FastClient is enabled
            android.content.SharedPreferences p = getSharedPreferences("fastclient_prefs", android.content.Context.MODE_PRIVATE);
            boolean fcEnabled = p.getBoolean("fc_enabled", false);
            Class<? extends Fragment> rootFragment = fcEnabled ? net.kdt.pojavlaunch.fragments.FastClientHomeFragment.class : MainMenuFragment.class;

            // Manually add the first fragment to the backstack to get easily back to it
            fragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.fragment_enter_forward, R.anim.fragment_exit_forward,
                            R.anim.fragment_enter_back, R.anim.fragment_exit_back)
                    .setReorderingAllowed(true)
                    .addToBackStack("ROOT")
                    .add(R.id.container_fragment, rootFragment, null, "ROOT").commit();
        }


        IconCacheJanitor.runJanitor();

        // Remote Config retired (item-5/feedback): the loading video is bundled
        // in-APK now. No warm fetch, no periodic refresh loop — zero background
        // network/battery cost. RemoteConfigManager stays in the tree, dormant.

        mRequestNotificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestNotificationPermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        mRequestMicrophonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isAllowed -> {
                    if(!isAllowed) handleNoNotificationPermission();
                    else {
                        Runnable runnable = Tools.getWeakReference(mRequestMicrophonePermissionRunnable);
                        if(runnable != null) runnable.run();
                    }
                }
        );
        getWindow().setBackgroundDrawable(null);
        bindViews();
        // Give the persistent launcher chrome the same polished arrival as pages.
        UiMotion.revealChrome(findViewById(R.id.header_bar));
        UiMotion.revealChrome(mAccountSpinner);
        UiMotion.revealChrome(mSettingsButton);
        setupNavButtons();
        checkNotificationPermission();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ProgressKeeper.addTaskCountListener(mDoubleLaunchPreventionListener);
        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));

        mSettingsButton.setOnClickListener(mSettingButtonListener);

        // Notification per-download card tapped while the process was cold —
        // surface the launcher at its downloads console (home root).
        consumeOpenDownloadsIntent(getIntent());
        ProgressKeeper.addTaskCountListener(mProgressLayout);
        ExtraCore.addExtraListener(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.addExtraListener(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);

        ExtraCore.addExtraListener(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);
        net.kdt.pojavlaunch.launch.LaunchTracker.addListener(mLaunchPhaseListener);

        // A shortcut asking for an immediate launch must not trigger work the
        // game will never need: no version-list refresh, no onboarding dialogs.
        final boolean shortcutLaunchRequested = isShortcutLaunchRequested(getIntent());
        if (!shortcutLaunchRequested) {
            new AsyncVersionList().getVersionList(versions -> ExtraCore.setValue(ExtraConstants.RELEASE_TABLE, versions), false);
        }

        mInstallTracker = new ModloaderInstallTracker(this);

        mProgressLayout.observe(ProgressLayout.DOWNLOAD_MINECRAFT);
        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);
        mProgressLayout.observe(ProgressLayout.AUTHENTICATE_MICROSOFT);
        mProgressLayout.observe(ProgressLayout.DOWNLOAD_VERSION_LIST);

        // Shortcut launches stage the identical boot overlay right away so the
        // home UI never flashes; onboarding dialogs stay out of the way.
        if (shortcutLaunchRequested) {
            showBootOverlayInstant();
        } else if (!maybeShowRuntimeWizard()) {
            // Official partner welcome — shown exactly once, after first launch settles
            maybeShowInfrawireWelcome();
        }
    }

    /**
     * First-launch runtime onboarding — dedicated full-screen installer
     * (Java 8/17/21/25, recommendations, skip, auto-chained downloads).
     * Returns true when it will be shown.
     */
    private boolean maybeShowRuntimeWizard() {
        if (net.kdt.pojavlaunch.multirt.RuntimeSetupActivity.wasShown()) return false;
        View root = findViewById(android.R.id.content);
        if (root == null) return false;
        root.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (net.kdt.pojavlaunch.multirt.RuntimeSetupActivity.wasShown()) return;
            startActivity(new android.content.Intent(this,
                    net.kdt.pojavlaunch.multirt.RuntimeSetupActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }, 900);
        return true;
    }

    /**
     * Infrawire Official Partner welcome dialog. Shown once (flag persisted),
     * never again. Explore opens the partner page; Skip just dismisses.
     */
    private void maybeShowInfrawireWelcome() {
        if (net.kdt.pojavlaunch.sponsor.InfrawirePartner.wasWelcomeShown(this)) return;
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        root.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (net.kdt.pojavlaunch.sponsor.InfrawirePartner.wasWelcomeShown(this)) return;
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.markWelcomeShown(this);

            View dialogView = getLayoutInflater().inflate(R.layout.dialog_infrawire_welcome, null);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                    .setView(dialogView)
                    .setCancelable(true)
                    .create();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }

            View explore = dialogView.findViewById(R.id.infrawire_welcome_btn_explore);
            View skip = dialogView.findViewById(R.id.infrawire_welcome_btn_skip);
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(explore);
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(skip);
            explore.setOnClickListener(v -> {
                dialog.dismiss();
                net.kdt.pojavlaunch.sponsor.InfrawirePartner.openPartnerPage(this);
            });
            skip.setOnClickListener(v -> dialog.dismiss());

            dialog.show();
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.fadeIn(
                    dialogView.findViewById(R.id.infrawire_welcome_root), 0);
        }, 700);
    }

    /**
     * Handle a routing instruction delivered by {@link net.kdt.pojavlaunch.shortcuts.ShortcutActivity}.
     *
     * <p>Called from {@link #onResume()} rather than {@code onCreate}, because a
     * shortcut may arrive while the launcher is already running. The extra is
     * removed once consumed so a later configuration change does not replay it.</p>
     */
    private void handleShortcutIntent() {
        Intent intent = getIntent();
        if (intent == null) return;

        String profileKey = intent.getStringExtra(
                net.kdt.pojavlaunch.shortcuts.ShortcutActivity.EXTRA_PROFILE_KEY);
        String actionId = intent.getStringExtra(
                net.kdt.pojavlaunch.shortcuts.ShortcutActivity.EXTRA_ACTION);

        if (profileKey == null || actionId == null) return;

        // Consume immediately — rotating the device must not relaunch the game.
        intent.removeExtra(net.kdt.pojavlaunch.shortcuts.ShortcutActivity.EXTRA_PROFILE_KEY);
        intent.removeExtra(net.kdt.pojavlaunch.shortcuts.ShortcutActivity.EXTRA_ACTION);

        net.kdt.pojavlaunch.shortcuts.ShortcutType action =
                net.kdt.pojavlaunch.shortcuts.ShortcutType.fromId(actionId);

        net.kdt.pojavlaunch.shortcuts.ShortcutRouter.route(this, profileKey, action);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // A shortcut tapped while the launcher is already alive arrives here;
        // swap the stored intent so onResume() sees the fresh extras.
        setIntent(intent);
        // Warm start: cover the UI with the boot overlay immediately, before
        // profiles finish loading and the router fills in the details.
        if (isShortcutLaunchRequested(intent)) {
            showBootOverlayInstant();
        }
        consumeOpenDownloadsIntent(intent);
        handleFcmNotificationIntent(intent);
    }

    /**
     * Handles navigation when an FCM push notification is tapped.
     * Navigates to existing Update or Announcement screens without creating duplicates.
     */
    private void handleFcmNotificationIntent(@Nullable Intent intent) {
        if (intent == null || !intent.hasExtra("fcm_type")) return;
        String type = intent.getStringExtra("fcm_type");
        String title = intent.getStringExtra("fcm_title");
        String message = intent.getStringExtra("fcm_message");
        String version = intent.getStringExtra("fcm_version");
        String url = intent.getStringExtra("fcm_url");
        String announcementId = intent.getStringExtra("fcm_announcementId");

        // Remove extra immediately so rotating/recreating activity doesn't re-trigger
        intent.removeExtra("fcm_type");

        if ("update".equalsIgnoreCase(type) || "launcher_updates".equalsIgnoreCase(type)) {
            // Open existing Launcher Update screen
            net.kdt.pojavlaunch.remote.FirebaseSyncManager.checkForUpdateFromFcm(this, version, url, message);
        } else if ("announcement".equalsIgnoreCase(type)
                || "server_news".equalsIgnoreCase(type)
                || "maintenance".equalsIgnoreCase(type)
                || "launcher_announcements".equalsIgnoreCase(type)) {
            // Open existing Announcement screen
            String displayTitle = (title != null && !title.trim().isEmpty()) ? title : "Announcement";
            String displayBody = (message != null && !message.trim().isEmpty()) ? message : "";
            net.kdt.pojavlaunch.remote.FirebaseSyncManager.showMarkdownDialog(this, displayTitle, displayBody, false);
        }
    }

    /** Notification tap: a per-download card asked to surface the launcher at
     *  its downloads console — land on the home root, where the activity-level
     *  Download Console overlay shows every active/recent download. */
    private void consumeOpenDownloadsIntent(@Nullable Intent intent) {
        if (intent == null || !intent.getBooleanExtra("cs_open_downloads", false)) return;
        intent.removeExtra("cs_open_downloads");
        if (mFragmentView == null) return;
        mFragmentView.post(() -> {
            try {
                getSupportFragmentManager().popBackStackImmediate("ROOT", 0);
            } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextExecutor.setActivity(this);
        // Firebase real-time sync (admin panel): announcements, notifications,
        // sponsorship toggle, update checks. No-op when disabled (default).
        try {
            net.kdt.pojavlaunch.remote.FirebaseSyncManager.onResume(this);
            handleFcmNotificationIntent(getIntent());
        } catch (Throwable t) {
            android.util.Log.w("LauncherActivity", "firebase sync skipped", t);
        }
        // Load profiles on background thread to keep UI responsive during resume
        net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles.loadAsync(() -> {
            if (isDestroyed() || isFinishing()) return;
            updateNavSkinIcon();
            // Profiles are loaded, so shortcut routing can safely resolve them.
            handleShortcutIntent();
            // Refresh the app-icon long-press menu with the most recent profiles.
            net.kdt.pojavlaunch.shortcuts.ShortcutRouter.syncDynamicShortcuts(this);
        });
        mInstallTracker.attach();
        updateNavSkinIcon();
        // Launch-overlay recovery (item-1/6 root fix): when we regain the
        // foreground (returning from the game, process survived the handoff),
        // the tracker may already have settled back to IDLE while our listener
        // notice raced the activity swap. A still-visible overlay would wedge
        // over the whole UI — hide it on settle. Zero-op when nothing is up.
        if (net.kdt.pojavlaunch.launch.LaunchTracker.getPhase()
                == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.IDLE) {
            hideLaunchOverlay();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ContextExecutor.clearActivity();
        mInstallTracker.detach();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(mFragmentCallbackListener, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBootOverlay != null) {
            mBootOverlay.removeCallbacks(mBootFailsafe);
            mBootOverlay.removeCallbacks(mBootPostTaskHide);
        }
        if (mBootActive) {
            mBootActive = false;
            ProgressKeeper.removeTaskCountListener(mBootTaskListener);
            ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mBootProgressListener);
            ProgressKeeper.removeListener(ProgressLayout.UNPACK_RUNTIME, mBootProgressListener);
        }
        mProgressLayout.cleanUpObservers();
        ProgressKeeper.removeTaskCountListener(mProgressLayout);
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.BACK_PREFERENCE, mBackPreferenceListener);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.SELECT_AUTH_METHOD, mSelectAuthMethod);
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.LAUNCH_GAME, mLaunchGameListener);
        net.kdt.pojavlaunch.launch.LaunchTracker.removeListener(mLaunchPhaseListener);
        if (mLaunchOverlay != null) {
            mLaunchOverlay.finishAndHide(null);
            mLaunchOverlay = null;
        }

        getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentCallbackListener);
    }

    /** Custom implementation to feel more natural when a backstack isn't present */
    @Override
    public void onBackPressed() {
        // A game launch is being staged — the boot overlay owns the screen.
        if (mBootActive) return;

        MicrosoftLoginFragment fragment = (MicrosoftLoginFragment) getVisibleFragment(MicrosoftLoginFragment.TAG);
        if(fragment != null){
            if(fragment.canGoBack()){
                fragment.goBack();
                return;
            }
        }

        // If we are in settings, pop back to home
        if (getVisibleFragment(SETTING_FRAGMENT_TAG) != null) {
            getSupportFragmentManager().popBackStack();
            return;
        }

        // In landscape two-pane mode: if the right pane has content, pop it instead of exiting
        Fragment rootFrag = getVisibleFragment("ROOT");
        if (rootFrag instanceof MainMenuFragment) {
            MainMenuFragment mmf = (MainMenuFragment) rootFrag;
            if (mmf.isRightPaneActive()) {
                mmf.popRightPane();
                return;
            }
            finish();
            return;
        }

        // Default backstack behavior
        if (getSupportFragmentManager().getBackStackEntryCount() > 1) {
            getSupportFragmentManager().popBackStack();
            return;
        }

        finish();
    }

    @Override
    public void onAttachedToWindow() {
        LauncherPreferences.computeNotchSize(this);
    }

    @SuppressWarnings("SameParameterValue")
    private Fragment getVisibleFragment(String tag){
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(tag);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    @SuppressWarnings("unused")
    private Fragment getVisibleFragment(int id){
        Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if(fragment != null && fragment.isVisible()) {
            return fragment;
        }
        return null;
    }

    private void checkNotificationPermission() {
        if(LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK ||
            checkForNotificationPermission()) {
            return;
        }

        if(ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.POST_NOTIFICATIONS)) {
            showNotificationPermissionReasoning();
            return;
        }
        askForNotificationPermission(null);
    }

    private void showNotificationPermissionReasoning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.notification_permission_dialog_title)
                .setMessage(R.string.notification_permission_dialog_text)
                .setPositiveButton(android.R.string.ok, (d, w) -> askForNotificationPermission(null))
                .setNegativeButton(android.R.string.cancel, (d, w)-> handleNoNotificationPermission())
                .show();
    }

    private void handleNoNotificationPermission() {
        LauncherPreferences.PREF_SKIP_NOTIFICATION_PERMISSION_CHECK = true;
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(LauncherPreferences.PREF_KEY_SKIP_NOTIFICATION_CHECK, true)
                .apply();
        Toast.makeText(this, R.string.notification_permission_toast, Toast.LENGTH_LONG).show();
    }

    public boolean checkForNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_DENIED;
    }
    public boolean checkForMicrophonePermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_DENIED;
    }

    public void askForNotificationPermission(Runnable onSuccessRunnable) {
        if(Build.VERSION.SDK_INT < 33) return;
        if(onSuccessRunnable != null) {
            mRequestNotificationPermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    public void askForMicrophonePermission(Runnable onSuccessRunnable) {
        if(onSuccessRunnable != null) {
            mRequestMicrophonePermissionRunnable = new WeakReference<>(onSuccessRunnable);
        }
        mRequestMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    /** Stuff all the view boilerplate here */

    // ── Req-10: premium top-toolbar micro-interactions ─────────────────────
    // Every header icon click now produces (a) a small downward slide nudge on
    // the icon and (b) a floating label pill that slides in under the header,
    // holds briefly, then fades away. Purely cosmetic — actions are unchanged.

    private void nudgeNavIcon(@NonNull View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        // User req: the pressed button GROWS (scale pop) instead of nudging.
        v.animate().scaleX(1.14f).scaleY(1.14f).setDuration(90)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(170)
                        .setInterpolator(new OvershootInterpolator(1.5f))
                        .start())
                .start();
    }

    private void showNavChip(@NonNull View anchor, int labelRes) {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        View firstChild = ((ViewGroup) content).getChildAt(0);
        if (!(firstChild instanceof ViewGroup)) return;
        final ViewGroup root = (ViewGroup) firstChild;

        final float density = getResources().getDisplayMetrics().density;
        final TextView chip = new TextView(this);
        chip.setText(labelRes);
        chip.setTextColor(0xFFE4E4EA);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setLetterSpacing(0.05f);
        chip.setPadding((int) (10 * density), (int) (4 * density),
                (int) (10 * density), (int) (5 * density));
        chip.setBackgroundResource(R.drawable.bg_cs_nav_tip);
        chip.setElevation(14f);
        chip.setClickable(false);
        chip.setFocusable(false);
        chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        root.addView(chip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        int[] anchorPos = new int[2];
        int[] rootPos = new int[2];
        anchor.getLocationInWindow(anchorPos);
        root.getLocationInWindow(rootPos);
        // User req: the label appears WRITTEN ON TOP OF the pressed button
        // (overlay centered on it), not floating near the home edge.
        float x = anchorPos[0] - rootPos[0]
                + (anchor.getWidth() - chip.getMeasuredWidth()) / 2f;
        float maxX = root.getWidth() - chip.getMeasuredWidth() - 8 * density;
        x = Math.max(8 * density, Math.min(x, Math.max(8 * density, maxX)));
        float y = anchorPos[1] - rootPos[1]
                + (anchor.getHeight() - chip.getMeasuredHeight()) / 2f;
        float maxY = root.getHeight() - chip.getMeasuredHeight() - 8 * density;
        y = Math.max(8 * density, Math.min(y, Math.max(8 * density, maxY)));
        chip.setX(x);
        chip.setY(y);

        // Pops open from the button itself, then fades away.
        chip.setAlpha(0f);
        chip.setScaleX(0.4f);
        chip.setScaleY(0.4f);
        chip.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .withEndAction(() -> chip.animate()
                        .alpha(0f).scaleX(0.85f).scaleY(0.85f)
                        .setStartDelay(950)
                        .setDuration(180)
                        .withEndAction(() -> root.removeView(chip))
                        .start())
                .start();
    }

    /** Tap feedback bundle: icon nudge + nav light + floating label, then run the action. */
    private void navTap(@NonNull View v, int labelRes) {
        // Honor the global animation switch (Launcher Customisation).
        if (net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) {
            nudgeNavIcon(v);
            setActiveNavIndicator(labelRes);
            showNavChip(v, labelRes);
        } else {
            setActiveNavIndicator(labelRes);
        }
    }

    /** Maps a nav label to its green indicator line under the icon. */
    private View indicatorForLabel(int labelRes) {
        if (labelRes == R.string.cs_navtip_home) return findViewById(R.id.nav_home_indicator);
        if (labelRes == R.string.cs_navtip_mods) return findViewById(R.id.nav_mod_store_indicator);
        if (labelRes == R.string.cs_navtip_controls) return findViewById(R.id.nav_controls_indicator);
        if (labelRes == R.string.cs_navtip_cursor) return findViewById(R.id.nav_cursor_indicator);
        if (labelRes == R.string.cs_navtip_skins) return findViewById(R.id.nav_skin_indicator);
        return null;
    }

    /**
     * Only the tapped nav button stays "lit" (green indicator); every other
     * indicator returns to invisible — selected vs normal state (user req).
     */
    private void setActiveNavIndicator(int labelRes) {
        View[] indicators = {
                findViewById(R.id.nav_home_indicator),
                findViewById(R.id.nav_mod_store_indicator),
                findViewById(R.id.nav_controls_indicator),
                findViewById(R.id.nav_cursor_indicator),
                findViewById(R.id.nav_skin_indicator)
        };
        for (View ind : indicators) {
            if (ind != null) ind.setVisibility(View.INVISIBLE);
        }
        View active = indicatorForLabel(labelRes);
        if (active != null) {
            active.setVisibility(View.VISIBLE);
            active.setAlpha(0.3f);
            active.animate().alpha(1f).setDuration(220).start();
        }
    }

    /** Wire up the landscape header bar navigation buttons. */
    private void setupNavButtons() {
        View navModStore       = findViewById(R.id.nav_mod_store);
        View navCustomControls  = findViewById(R.id.nav_custom_controls);
        View navCursor          = findViewById(R.id.nav_cursor);
        View navHome            = findViewById(R.id.nav_home);
        View btnHomeLogo        = findViewById(R.id.btn_home_logo);
        View tvLauncherTitle    = findViewById(R.id.tv_launcher_title);

        View.OnClickListener homeListener = v -> {
            navTap(v, R.string.cs_navtip_home);
            // Jank fix (user req): skip the pop when already at root, and
            // defer the home refresh one frame so the press animation and the
            // fragment pop never fight on the same frame (smooth "geach"-free
            // home return even when pressed fast from a child page).
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                getSupportFragmentManager().popBackStackImmediate("ROOT", 0);
            }
            v.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Fragment frag = getVisibleFragment("ROOT");
                if (frag instanceof MainMenuFragment) {
                    ((MainMenuFragment) frag).refreshHomeState();
                }
            });
        };

        if (navHome != null)         navHome.setOnClickListener(homeListener);
        if (btnHomeLogo != null)     btnHomeLogo.setOnClickListener(homeListener);
        if (tvLauncherTitle != null) tvLauncherTitle.setOnClickListener(homeListener);

        // About — opens the full premium About page (credits, links, legal).
        ImageButton btnAbout = findViewById(R.id.btn_about);
        if (btnAbout != null) {
            btnAbout.setOnClickListener(v -> {
                navTap(v, R.string.cs_about_title);
                Tools.swapFragment(this, AboutFragment.class, AboutFragment.TAG, null);
            });
        }

        if (navModStore != null) {
            navModStore.setOnClickListener(v -> {
                navTap(v, R.string.cs_navtip_mods);
                Fragment frag = getVisibleFragment("ROOT");
                if (frag instanceof MainMenuFragment) {
                    ((MainMenuFragment) frag).openChildPane(
                            ModsSearchFragment.class, ModsSearchFragment.TAG, null);
                } else {
                    Tools.swapFragment(this, ModsSearchFragment.class, ModsSearchFragment.TAG, null);
                }
            });
        }

        if (navCustomControls != null) {
            navCustomControls.setOnClickListener(v -> {
                navTap(v, R.string.cs_navtip_controls);
                startActivity(new Intent(this, CustomControlsActivity.class));
            });
        }

        if (navCursor != null) {
            navCursor.setOnClickListener(v -> {
                navTap(v, R.string.cs_navtip_cursor);
                Tools.swapFragment(this, CursorCustomizationFragment.class,
                        CursorCustomizationFragment.TAG, null);
            });
        }

        View navSkin = findViewById(R.id.nav_skin);
        if (navSkin != null) {
            navSkin.setOnClickListener(v -> {
                navTap(v, R.string.cs_navtip_skins);
                Fragment frag = getVisibleFragment("ROOT");
                if (frag instanceof MainMenuFragment) {
                    ((MainMenuFragment) frag).openChildPane(
                            SkinManagerFragment.class, SkinManagerFragment.TAG, null);
                } else {
                    Tools.swapFragment(this, SkinManagerFragment.class, SkinManagerFragment.TAG, null);
                }
            });
        }
        updateNavSkinIcon();
    }

    public void updateNavSkinIcon() {
        final ImageView navSkinIcon = findViewById(R.id.nav_skin_icon);
        if (navSkinIcon == null) return;
        navSkinIcon.setImageResource(R.drawable.ic_manage_skin);

        // Resolve the selected account off the UI thread. Premium accounts now
        // use the first-party Minecraft Services skin cached during login.
        final String expectedAccount = PojavProfile.getCurrentProfileName(this);
        PojavApplication.sExecutorService.execute(() -> {
            MinecraftAccount account = PojavProfile.getCurrentProfileContent(this, expectedAccount);
            if (account == null) return;
            android.graphics.Bitmap face = account.getSkinFace();
            if ((face == null || face.isRecycled()) && account.isMicrosoft
                    && account.username != null && !account.username.isEmpty()) {
                face = net.kdt.pojavlaunch.shortcuts.ShortcutSkinHeadHelper
                        .getSkinHead(getApplicationContext(), account.username);
            }
            if (face == null || face.isRecycled()) return;
            final android.graphics.Bitmap resolvedFace = face;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                String current = PojavProfile.getCurrentProfileName(this);
                if (!java.util.Objects.equals(expectedAccount, current)) return;
                navSkinIcon.animate().cancel();
                navSkinIcon.setAlpha(0f);
                navSkinIcon.setScaleX(0.82f);
                navSkinIcon.setScaleY(0.82f);
                navSkinIcon.setImageBitmap(resolvedFace);
                navSkinIcon.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                        .start();
            });
        });
    }

    private void bindViews(){
        mFragmentView = findViewById(R.id.container_fragment);
        mSettingsButton = findViewById(R.id.setting_button);
        mAccountSpinner = findViewById(R.id.account_spinner);
        mProgressLayout = findViewById(R.id.progress_layout);
    }
}
