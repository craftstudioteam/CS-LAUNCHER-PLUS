package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;
import android.util.Log;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    private ViewGroup mRightPane;
    private View mBottomBarBg;
    private View mPlayButton;
    private View mEditProfileButton;
    private View mBottomBar;
    private OnBackPressedCallback mRightPaneBackCallback;

    private boolean isTwoPane() {
        return mRightPane != null;
    }

    public boolean isRightPaneActive() {
        return isTwoPane() && getChildFragmentManager().getBackStackEntryCount() > 0;
    }

    public void popRightPane() {
        if (!isTwoPane()) return;
        if (getChildFragmentManager().getBackStackEntryCount() > 0) {
            getChildFragmentManager().popBackStack();
        }
    }

    public void clearRightPane() {
        if (!isTwoPane()) return;
        int count = getChildFragmentManager().getBackStackEntryCount();
        if (count > 0) {
            getChildFragmentManager().popBackStack(
                    getChildFragmentManager().getBackStackEntryAt(0).getName(),
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
    }

    private void setBottomBarVisible(boolean visible) {
        if (mBottomBar != null) {
            mBottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
            mBottomBar.requestLayout();
        }
    }

    public void refreshHomeState() {
        if (getView() == null || !isAdded()) return;
        clearRightPane();
        setBottomBarVisible(true);
        if (mVersionSpinner != null) mVersionSpinner.reloadProfiles();
    }

    public void selectInstance(String profileKey) {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .apply();
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, profileKey);
        clearRightPane();
        if (mVersionSpinner != null) mVersionSpinner.reloadProfiles();
    }

    public void reloadSpinner() {
        if (mVersionSpinner != null) mVersionSpinner.reloadProfiles();
    }

    public void openChildPane(Class<? extends Fragment> fragmentClass, String tag,
                              @Nullable Bundle args) {
        openPane(fragmentClass, tag, args);
    }

    public boolean tryOpenInRightPane(Class<? extends Fragment> fragmentClass, String tag,
                                      @Nullable Bundle args) {
        if (!isTwoPane()) return false;
        openPane(fragmentClass, tag, args);
        return true;
    }

    private void openPane(Class<? extends Fragment> fragmentClass, String tag,
                          @Nullable Bundle args) {
        if (isTwoPane()) {
            androidx.fragment.app.FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.setReorderingAllowed(true);
            // Opening a detail pane is a hierarchy change, so it uses the deep-dive transition.
            net.kdt.pojavlaunch.utils.animation.MotionTransitions.applyDeepDive(transaction);
            transaction
                    .replace(R.id.right_pane_container, fragmentClass, args, tag)
                    .addToBackStack(tag)
                    .commit();
        } else {
            if (fragmentClass == ModsSearchFragment.class) {
                Tools.swapFragment(requireActivity(), fragmentClass, tag, args,
                        R.anim.fade_in_slide_up, R.anim.fade_out_slide_down,
                        R.anim.fade_in_slide_up, R.anim.fade_out_slide_down);
            } else {
                Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
            }
        }
    }

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRightPaneBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (mRightPane == null) return;
                if (getChildFragmentManager().getBackStackEntryCount() > 0) {
                    getChildFragmentManager().popBackStackImmediate();
                }
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(this, mRightPaneBackCallback);
        getChildFragmentManager().addOnBackStackChangedListener(mBackStackListener);
    }

    private final androidx.fragment.app.FragmentManager.OnBackStackChangedListener
            mBackStackListener = () -> {
        mRightPaneBackCallback.setEnabled(isRightPaneActive());
        if (!isTwoPane()) return;
        boolean showBar = getChildFragmentManager().getBackStackEntryCount() == 0;
        setBottomBarVisible(showBar);
    };

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton          = view.findViewById(R.id.news_button);
        Button mDiscordButton       = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton   = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton    = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_directory_button);
        Button mCursorCustomButton  = view.findViewById(R.id.cursor_customization_button);
        Button mHomeButton          = view.findViewById(R.id.home_button);
        Button mManageSkinButton    = view.findViewById(R.id.btn_manage_skin);

        ImageButton mEditProfileBtn = view.findViewById(R.id.edit_profile_button);
        Button mPlayBtn = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);
        mRightPane = view.findViewById(R.id.right_pane_container);
        mBottomBarBg       = view.findViewById(R.id._background_display_view);
        mPlayButton        = mPlayBtn;
        mEditProfileButton = mEditProfileBtn;
        mBottomBar         = view.findViewById(R.id.bottom_bar);

        if (isTwoPane()) {
            Fragment existing = getChildFragmentManager().findFragmentById(R.id.right_pane_container);
            if (existing == null) {
                // First paint of the home pane arrives softly instead of hard-cutting in.
                getChildFragmentManager().beginTransaction().setReorderingAllowed(true)
                        .setCustomAnimations(R.anim.motion_fade_enter, R.anim.motion_fade_exit)
                        .replace(R.id.right_pane_container, RightPaneHomeFragment.class, null, RightPaneHomeFragment.TAG).commit();
            }

        // Home entrance choreography — deferred one frame (window token),
        // no-op when animations are Off.
        view.post(() -> {
            if (isAdded() && !isRemoving()) {
                net.kdt.pojavlaunch.UiMotion.revealScreen(view);
                // The launcher's centre of gravity: PLAY arrives with a jelly pop.
                net.kdt.pojavlaunch.UiMotion.heroIn(mPlayBtn);
            }
        });
        }

        Button mBrowserResourcesButton = view.findViewById(R.id.browser_resources_button);
        if (mBrowserResourcesButton != null) mBrowserResourcesButton.setOnClickListener(v -> {
            // Item-3: premium glass browser (Modrinth → current profile's folders)
            if (getActivity() instanceof androidx.fragment.app.FragmentActivity) {
                ResourceBrowserDialog.show((androidx.fragment.app.FragmentActivity) getActivity());
            }
        });
        if (mHomeButton != null) mHomeButton.setOnClickListener(v -> refreshHomeState());
        if (mNewsButton != null) mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        if (mDiscordButton != null) mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        if (mCustomControlButton != null) mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        if (mCursorCustomButton != null) mCursorCustomButton.setOnClickListener(v -> Tools.swapFragment(requireActivity(), CursorCustomizationFragment.class, CursorCustomizationFragment.TAG, null));
        if (mManageSkinButton != null) mManageSkinButton.setOnClickListener(v ->
                openPane(SkinLibraryOverviewFragment.class, SkinLibraryOverviewFragment.TAG, null));

        if (mInstallJarButton != null) {
            if (hasOnlineProfile()) {
                mInstallJarButton.setOnClickListener(v -> Tools.installMod(requireActivity(), false));
                mInstallJarButton.setOnLongClickListener(v -> { Tools.installMod(requireActivity(), true); return true; });
            } else mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        }

        if (mShareLogsButton != null) mShareLogsButton.setOnClickListener(v -> shareLog(requireContext()));
        if (mOpenDirectoryButton != null) {
            mOpenDirectoryButton.setOnClickListener(v -> {
                if (Tools.isDemoProfile(v.getContext())) hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
                else if (!hasOnlineProfile()) hasNoOnlineProfileDialog(requireActivity());
                else openPath(v.getContext(), getCurrentProfileDirectory(), false);
            });
        }

        if (mEditProfileBtn != null) mEditProfileBtn.setOnClickListener(v -> { if (mVersionSpinner != null) mVersionSpinner.openProfileEditor(requireActivity()); });
        if (isTwoPane() && mVersionSpinner != null) mVersionSpinner.setOnClickListener(v -> openPane(InstancePickerFragment.class, InstancePickerFragment.TAG, null));
        if (isTwoPane()) setBottomBarVisible(getChildFragmentManager().getBackStackEntryCount() == 0);

        applyPremiumTouchAnimation(mHomeButton, mCursorCustomButton, mCustomControlButton, mBrowserResourcesButton, mInstallJarButton, mShareLogsButton, mOpenDirectoryButton, mPlayBtn, mEditProfileBtn, mVersionSpinner, mManageSkinButton);

        if (mPlayBtn != null) mPlayBtn.setOnClickListener(v -> ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mRightPane = null; mBottomBarBg = null; mPlayButton = null; mEditProfileButton = null; mBottomBar = null;
        getChildFragmentManager().removeOnBackStackChangedListener(mBackStackListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVersionSpinner != null) mVersionSpinner.post(() -> { if (mVersionSpinner != null) mVersionSpinner.reloadProfiles(); });
        if (isTwoPane() && mBottomBar != null) mBottomBar.post(() -> setBottomBarVisible(getChildFragmentManager().getBackStackEntryCount() == 0));
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if (!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if (profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    private void applyPremiumTouchAnimation(View... views) {
        for (View v : views) {
            if (v == null) continue;
            v.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    // Item-7: snappier press, lighter alpha dip (no fade spam),
                    // fast-out spring-feel release (view anims are HW-accelerated).
                    case android.view.MotionEvent.ACTION_DOWN: view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.9f).setDuration(70).setInterpolator(new android.view.animation.AccelerateInterpolator()).start(); break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160).setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start(); break;
                }
                return false;
            });
        }
    }
}
