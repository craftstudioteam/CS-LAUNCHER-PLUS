package net.kdt.pojavlaunch.prefs.screens;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.FastClientHelper;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.performance.DeviceCapability;
import net.kdt.pojavlaunch.performance.PerformanceMode;
import net.kdt.pojavlaunch.performance.PerformancePolicy;
import net.kdt.pojavlaunch.performance.RendererPolicy;
import net.kdt.pojavlaunch.performance.SchedulingPolicy;
import net.kdt.pojavlaunch.performance.SessionStats;
import net.kdt.pojavlaunch.performance.ThermalGovernor;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.fragments.GamepadMapperFragment;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.fragments.RightPaneHomeFragment;
import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.prefs.CustomToggleView;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.SettingsSaveManager;
import net.kdt.pojavlaunch.theme.ThemeManager;
import net.kdt.pojavlaunch.utils.GLInfoUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import fr.spse.gamepad_remapper.Remapper;

public class LauncherPreferenceFragment extends Fragment {

    private RecyclerView mRecyclerView;
    private SettingsAdapter mAdapter;
    private SharedPreferences mDraftPrefs;
    private boolean mIsDirty = false;
    private String mCategoryName = null;
    private TextView mHeaderTitle;
    private TextView mHeaderSubtitle;
    private TextView mHeaderBadge;
    private ImageView mHeaderIcon;
    private LinearLayout mCategoryRail;
    private View mCategoryRailScroll;
    private View mDockSpacer;
    // ══ Phase 3: search / favorites / recent / focus-jump ══
    private String mSearchQuery = "";
    private EditText mSearchInput;
    private final java.util.Set<String> mAnimatedSettingIcons = new java.util.HashSet<>();
    private static final String[] ALL_CATEGORIES = {
            "Launcher Customisation", "Launcher Settings", "Video & Graphics", "All Controls", "Java Runtime",
            "Audio", "Account", "Experimental", "Maintenance", "Miscellaneous", "Sponsors", "Performance"};

    // ── Settings v6 · PLAIN SETTINGS ──────────────────────────────────────
    // Four short pages (General / Game / Display / Controls) hold ONLY the
    // rows a normal player ever touches, written in plain words. Every other
    // option keeps its key, type and default and lives on the Advanced page,
    // grouped under its old section name — nothing was removed, it was moved.
    /** Keys shown on the four simple pages (filtered out of Advanced so nothing appears twice). */
    private static final java.util.Set<String> SIMPLE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "check_for_update_btn", "launcher_animations", "launcher_transition_style", "launcher_animate_speed",
            "downloadSource", "game_log_display_mode", "force_english", "verifyManifest", "notification_permission_request",
            "perf_presets_general", "perf_presets_game", "perf_presets_display", "perf_presets_launcher", "perf_presets_graphics", "perf_presets_java", "perf_presets",
            "allocation", "ram_effective_info", PerformanceMode.PREF_KEY, "install_jre", "javaArgs", "java_sandbox", "disable_autojre_select",
            "resolutionRatio", "force_vsync", "mg_renderer_setting_fsr", "alternate_surface", "ignoreNotch", "vsync_in_zink", "mg_renderer_setting_angle", "mg_renderer_setting_multidraw",
            "buttonscale", "mousescale", "mousespeed", "enableGyro", "gyroSensitivity", "gyroSmoothing",
            "timeLongPressTrigger", "disableDoubleTap", "mouse_start", "always_grab_mouse",
            "gamepad_remap_action", "gamepad_deadzone_scale", "gamepadPassthru", "touchControllerVibrateLength"));
    /** Legacy pages that feed the Advanced page, in display order. */
    private static final String[] ADVANCED_SOURCES = {
            "Launcher Settings", "Launcher Customisation", "Audio", "Java Runtime", "Video & Graphics",
            "All Controls", "Performance", "Experimental", "Miscellaneous", "Maintenance"};
    private static final String[] SIMPLE_PERF_LABELS = {
            "Normal (recommended)",
            "Performance \u2014 more FPS",
            "Maximum \u2014 highest FPS, more heat"
    };
    /** One-shot: a pinned setting (Favorite/Recent) asked its page to flash a row. */
    private static String sPendingFocusKey;

    // ── Phase 10 · SIMPLE SETTINGS ──────────────────────────────────────
    // Casual users see only the everyday rows of a section. Everything a
    // power user might need still exists, folded behind ONE "Show more
    // options" row at the bottom of the section card. The fold is
    // remembered per section for the session only.
    private static final java.util.Set<String> sExpandedSections = new java.util.HashSet<>();
    private static final java.util.Set<String> ADVANCED_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            // General
            "force_english", "verifyManifest", "launcher_transition_style",
            "launcher_animate_amplitude", "launcher_animate_speed",
            "notification_permission_request", "microphone_permission_request", "launcher_language",
            "perf_presets_launcher", "perf_presets_graphics",
            // Game
            "javaArgs", "disable_autojre_select", "java_sandbox", "ram_effective_info", "install_jre",
            // Display
            "mg_renderer_setting_angle", "mg_renderer_setting_multidraw", "mg_renderer_setting_errorSetting",
            "mg_renderer_setting_timerQueryExt", "mg_renderer_setting_angleDepthClearFixMode",
            "mg_renderer_setting_gl43exts", "mg_renderer_computeShaderext", "mg_renderer_dsaExt",
            "mg_renderer_multidrawCompute", "mg_renderer_setting_glsl_cache_size", "alternate_surface",
            "vsync_in_zink", "ignoreNotch",
            // Controls
            "timeLongPressTrigger", "always_grab_mouse", "mouse_start", "disableDoubleTap",
            "gyroSampleRate", "gyroSmoothing",
            "gyroInvertX", "gyroInvertY", "gamepad_deadzone_scale", "gamepadPassthru",
            "gamepadPassthruForced", "forceEnableTouchController", "touchControllerVibrateLength",
            "gamepad_wipe_action",
            // Audio
            "use_opensles"));

    private boolean isAdvancedKey(@Nullable String key) {
        return key != null && ADVANCED_KEYS.contains(key);
    }

    /** True while a section card is folded (search results are never folded). */
    private boolean isSectionFolded(@NonNull String sectionTitle) {
        // Settings v6: no fold rows. Simple pages only carry simple rows and the
        // Advanced page shows everything it has — one less thing to tap.
        return false;
    }

    // JRE result launcher
    private final ActivityResultLauncher<Object> mVmInstallLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("xz"), (data) -> {
                if (data != null) Tools.installRuntimeFromUri(getContext(), data);
            });

    // Custom background picker launcher
    private final ActivityResultLauncher<String> mImagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) copyImageToBgFile(uri);
            });

    // Launch-screen GIF picker launcher (video removed per user directive; the
    // launch stage is black unless the user imports a GIF of their own)
    private final ActivityResultLauncher<String> mGifPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) copyGifToStageFile(uri);
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            mCategoryName = args.getString("category", null);
        }

        if (savedInstanceState == null && mCategoryName == null) {
            initializeDraft(requireContext());
        }
        mDraftPrefs = SettingsSaveManager.getDraftPrefs(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_custom_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mRecyclerView = view.findViewById(R.id.settings_recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mRecyclerView.setHasFixedSize(false);
        mRecyclerView.setItemViewCacheSize(8);

        mHeaderTitle = view.findViewById(R.id.settings_title);
        mHeaderSubtitle = view.findViewById(R.id.settings_subtitle);
        mHeaderBadge = view.findViewById(R.id.settings_live_badge);
        mHeaderIcon = view.findViewById(R.id.settings_header_icon);
        mCategoryRail = view.findViewById(R.id.settings_category_rail);
        mCategoryRailScroll = view.findViewById(R.id.settings_category_rail_scroll);
        mDockSpacer = view.findViewById(R.id.settings_dock_spacer);

        setupHeaderUi();
        setupCategoryRail();
        setupSettingsSearch(view);
        capColumnWidth(view.findViewById(R.id.settings_column));
        capColumnWidth(view.findViewById(R.id.unsaved_changes_bar));

        View backButton = view.findViewById(R.id.settings_back_button);
        net.kdt.pojavlaunch.UiMotion.pressFeedback(backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                Activity activity = getActivity();
                if (activity != null) {
                    activity.onBackPressed();
                }
            });
        }

        TextView saveBtn = view.findViewById(R.id.btn_save_settings);
        net.kdt.pojavlaunch.UiMotion.pressFeedback(saveBtn);
        if (saveBtn != null) {
            saveBtn.setOnClickListener(v -> {
                saveChanges();
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                        .start();
            });
        }

        setupSettingsList();
        playSettingsStudioEntrance(view);
        updateSaveBar();
    }

    /** Phase 10: the simple list never stretches wider than a phone column (720dp) — it sits centred. */
    private void capColumnWidth(@Nullable View column) {
        if (column == null) return;
        float den = getResources().getDisplayMetrics().density;
        int maxW = (int) (720 * den);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        if (screenW <= maxW) return;
        ViewGroup.LayoutParams lp = column.getLayoutParams();
        if (lp == null) return;
        lp.width = maxW;
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            ((android.widget.FrameLayout.LayoutParams) lp).gravity |= android.view.Gravity.CENTER_HORIZONTAL;
        }
        column.setLayoutParams(lp);
    }

    /** GPU-friendly page choreography: transform/alpha only, no animated sizes. */
    private android.animation.ObjectAnimator mBadgePulse;

    private void playSettingsStudioEntrance(@NonNull View root) {
        // ── GRAPHITE cinematic entrance ──────────────────────────────────────
        // Every chrome element arrives on its own spring beat instead of the
        // whole bar moving as one block: app bar drops in, then icon well,
        // title/subtitle, search pill and the live badge cascade after it.
        final float d = getResources().getDisplayMetrics().density;
        final boolean motionOn = net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled();
        final android.view.animation.Interpolator SPRING =
                net.kdt.pojavlaunch.utils.animation.MotionCurves.SPRING;
        final android.view.animation.Interpolator DECEL =
                new android.view.animation.DecelerateInterpolator(1.6f);

        // App bar — gentle drop + settle
        View header = root.findViewById(R.id.settings_header_bar);
        if (header != null) {
            header.animate().cancel();
            header.setAlpha(0f);
            header.setTranslationY(-22f * d);
            header.animate().alpha(1f).translationY(0f)
                    .setStartDelay(20L).setDuration(460)
                    .setInterpolator(SPRING).withLayer().start();
        }

        // Back button — springs in from the left
        View backBtn = root.findViewById(R.id.settings_back_button);
        if (backBtn != null && motionOn) {
            backBtn.animate().cancel();
            backBtn.setAlpha(0f);
            backBtn.setTranslationX(-18f * d);
            backBtn.setScaleX(0.7f);
            backBtn.setScaleY(0.7f);
            backBtn.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(120L).setDuration(420).setInterpolator(SPRING).start();
        }

        // Header icon well — retired in Phase 10 (icon stays GONE)
        View iconWell = null;
        if (iconWell != null && motionOn) {
            iconWell.animate().cancel();
            iconWell.setAlpha(0f);
            iconWell.setScaleX(0.4f);
            iconWell.setScaleY(0.4f);
            iconWell.setRotation(-12f);
            iconWell.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                    .setStartDelay(170L).setDuration(520)
                    .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.JELLY)
                    .start();
        }

        // Title & subtitle — slide up + fade, staggered
        if (mHeaderTitle != null && motionOn) {
            mHeaderTitle.animate().cancel();
            mHeaderTitle.setAlpha(0f);
            mHeaderTitle.setTranslationX(24f * d);
            mHeaderTitle.animate().alpha(1f).translationX(0f)
                    .setStartDelay(210L).setDuration(400).setInterpolator(DECEL).start();
        }
        if (mHeaderSubtitle != null && motionOn) {
            mHeaderSubtitle.animate().cancel();
            mHeaderSubtitle.setAlpha(0f);
            mHeaderSubtitle.setTranslationX(24f * d);
            mHeaderSubtitle.animate().alpha(1f).translationX(0f)
                    .setStartDelay(270L).setDuration(400).setInterpolator(DECEL).start();
        }

        // Search pill — rises into place with a soft overshoot
        View searchBar = mSearchInput != null ? (View) mSearchInput.getParent() : null;
        if (searchBar != null && motionOn) {
            searchBar.animate().cancel();
            searchBar.setAlpha(0f);
            searchBar.setTranslationY(20f * d);
            searchBar.setScaleX(0.96f);
            searchBar.animate().alpha(1f).translationY(0f).scaleX(1f)
                    .setStartDelay(300L).setDuration(440).setInterpolator(SPRING).start();
        }

        // Phase 10: no live badge in the simple design (kept GONE).

        // Category rail — fade/slide in; its chips already stagger via sx_rail_stagger
        if (mCategoryRailScroll != null && mCategoryRailScroll.getVisibility() == View.VISIBLE) {
            mCategoryRailScroll.animate().cancel();
            mCategoryRailScroll.setAlpha(0f);
            mCategoryRailScroll.setTranslationY(10f * d);
            mCategoryRailScroll.animate().alpha(1f).translationY(0f)
                    .setStartDelay(360L).setDuration(340).setInterpolator(DECEL)
                    .withLayer().start();
        }
    }

    /** Infinite, GPU-cheap breathing glow on the live/status badge. */
    private void startBadgeBreathing(@NonNull final View badge) {
        if (!net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) return;
        if (mBadgePulse != null) mBadgePulse.cancel();
        mBadgePulse = android.animation.ObjectAnimator.ofFloat(badge, "alpha", 0.62f, 1f);
        mBadgePulse.setDuration(900);
        mBadgePulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mBadgePulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        mBadgePulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        mBadgePulse.setStartDelay(600);
        badge.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                if (mBadgePulse != null) { mBadgePulse.cancel(); mBadgePulse = null; }
            }
        });
        mBadgePulse.start();
    }

    private int resolveCategoryIconByName(String catName) {
        if (catName == null) return R.drawable.ic_menu_settings;
        switch (catName) {
            case "General": return R.drawable.ic_settings_launcher;
            case "Game": return R.drawable.ic_settings_java;
            case "Display": return R.drawable.ic_settings_video;
            case "Advanced": return R.drawable.ic_settings_advanced;
            case "Launcher Customisation": return R.drawable.ic_settings_launcher;
            case "Launcher Settings": return R.drawable.ic_settings_launcher;
            case "Video & Graphics": return R.drawable.ic_settings_video;
            case "Controls": return R.drawable.ic_settings_control;
            case "Java Runtime": return R.drawable.ic_settings_java;
            case "Audio": return R.drawable.ic_settings_audio;
            case "Account": return R.drawable.ic_settings_account;
            case "Experimental": return R.drawable.ic_settings_experimental;
            case "Maintenance": return R.drawable.ic_settings_advanced;
            case "Miscellaneous": return R.drawable.ic_settings_misc;
            case "Sponsors": return R.drawable.ic_infrawire_mark_white;
            case "Performance": return R.drawable.ic_settings_performance;
            default: return R.drawable.ic_menu_settings;
        }
    }


    private void setupHeaderUi() {
        // Phase 10 simple settings: a big plain title and one short line
        // under it. No kicker badge, no icon well, no metallic sweep.
        if (mHeaderTitle != null) {
            mHeaderTitle.setText(mCategoryName != null ? mCategoryName : "Settings");
        }
        if (mHeaderSubtitle != null) {
            mHeaderSubtitle.setText(mCategoryName != null
                    ? resolveCategorySubtitleByName(mCategoryName)
                    : "Pick a section below");
            mHeaderSubtitle.setVisibility(View.VISIBLE);
        }
        if (mHeaderBadge != null) mHeaderBadge.setVisibility(View.GONE);
        if (mHeaderIcon != null) mHeaderIcon.setVisibility(View.GONE);
    }

    private void setupCategoryRail() {
        // Phase 10 simple settings: the left rail is retired. One way to
        // move around — tap a section, use the back arrow. The views stay
        // in the layout only so old ids keep resolving.
        if (mCategoryRailScroll != null) mCategoryRailScroll.setVisibility(View.GONE);
        if (mDockSpacer != null) mDockSpacer.setVisibility(View.GONE);
        if (true) return;
        if (mCategoryRail == null || mCategoryRailScroll == null) return;
        mCategoryRail.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());

        View homeView = inflater.inflate(R.layout.item_settings_nav_chip, mCategoryRail, false);
        TextView homeText = homeView.findViewById(R.id.settings_nav_chip_text);
        boolean homeSelected = mCategoryName == null;
        homeText.setText("Home");
        styleNavChip(homeText, homeSelected);
        decorateRailChip(homeText, null, homeSelected);
        net.kdt.pojavlaunch.UiMotion.pressFeedback(homeText);
        homeText.setOnClickListener(v -> {
            springChip(v);
            if (!homeSelected) returnToSettingsHome();
        });
        mCategoryRail.addView(homeView);

        for (SettingItem item : buildRootCategoryItems()) {
            if (item.type != SettingItem.TYPE_CATEGORY_LINK || item.categoryLinkTarget == null) continue;
            View chipView = inflater.inflate(R.layout.item_settings_nav_chip, mCategoryRail, false);
            TextView chipText = chipView.findViewById(R.id.settings_nav_chip_text);
            boolean selected = Objects.equals(item.categoryLinkTarget, mCategoryName);
            chipText.setText(shortenCategoryLabel(item.title));
            styleNavChip(chipText, selected);
            decorateRailChip(chipText, item.categoryLinkTarget, selected);
            net.kdt.pojavlaunch.UiMotion.pressFeedback(chipText);
            if (selected) {
                // Selected chip gently pulses once on entry.
                chipText.setScaleX(0.85f);
                chipText.setScaleY(0.85f);
                chipText.animate().scaleX(1f).scaleY(1f).setStartDelay(420L).setDuration(460)
                        .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.JELLY).start();
            }
            final String target = item.categoryLinkTarget;
            chipText.setOnClickListener(v -> {
                springChip(v);
                if (!selected) openCategoryPage(target);
            });
            mCategoryRail.addView(chipView);
        }
        if (mDockSpacer != null) mDockSpacer.setVisibility(View.GONE);
        mCategoryRailScroll.setVisibility(View.VISIBLE);
    }

    /** GRAPHITE nav chip: silver when selected, muted grey when idle. */
    private void styleNavChip(@NonNull TextView chip, boolean selected) {
        // Phase 9: vertical rail entry — silver text + left marker when selected,
        // muted when idle; each entry carries its section glyph on the left.
        chip.setBackgroundResource(selected ? R.drawable.st_rail_item_on : R.drawable.st_rail_item_off);
        chip.setTextColor(Color.parseColor(selected ? "#F4F6F9" : "#8A909C"));
        chip.setSelected(selected);
    }

    /** Phase 9: put the section icon at the start of a rail entry (pre-tinted at runtime). */
    private void decorateRailChip(@NonNull TextView chip, @Nullable String categoryName, boolean selected) {
        try {
            android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(
                    requireContext(), categoryName == null ? R.drawable.ic_menu_home : resolveCategoryIconByName(categoryName));
            if (d == null) return;
            d = d.mutate();
            float den = getResources().getDisplayMetrics().density;
            int px = (int) (15 * den);
            d.setBounds(0, 0, px, px);
            d.setTint(Color.parseColor(selected ? "#F4F6F9" : "#6B7180"));
            chip.setCompoundDrawablesRelative(d, null, null, null);
            chip.setCompoundDrawablePadding((int) (10 * den));
        } catch (Throwable ignored) {}
    }

    /** Quick squash-and-spring when a category chip is tapped. */
    private void springChip(@NonNull View v) {
        if (!net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) return;
        v.animate().cancel();
        v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(360)
                        .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.JELLY).start())
                .start();
    }

    private void returnToSettingsHome() {
        androidx.fragment.app.FragmentManager fm = requireActivity().getSupportFragmentManager();
        int guard = 20;
        while (guard-- > 0 && fm.getBackStackEntryCount() > 0) {
            if (!fm.popBackStackImmediate()) break;
            Fragment visible = fm.findFragmentById(R.id.container_fragment);
            if (visible instanceof LauncherPreferenceFragment) {
                Bundle args = visible.getArguments();
                if (args == null || args.getString("category", null) == null) break;
            }
        }
    }

    private void openCategoryPage(@NonNull String categoryName) {
        Bundle bundle = new Bundle();
        bundle.putString("category", categoryName);
        Tools.swapFragment(
                requireActivity(),
                LauncherPreferenceFragment.class,
                "SETTINGS_" + categoryName,
                bundle,
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
        );
    }

    private String shortenCategoryLabel(@NonNull String fullName) {
        switch (fullName) {
            case "Launcher Customisation": return "Customise";
            case "Launcher Settings": return "Launcher";
            case "Video & Graphics": return "Graphics";
            case "Java Runtime": return "Java";
            case "Miscellaneous": return "Misc";
            default: return fullName;
        }
    }

    private String resolveCategorySubtitleByName(@Nullable String catName) {
        if (catName == null) return "";
        switch (catName) {
            case "General":
                return "Updates, animations, download server";
            case "Game":
                return "RAM, performance mode, Java";
            case "Display":
                return "Resolution, VSync, FSR";
            case "Advanced":
                return "Everything else \u2014 only if you know what you are doing";
            case "Launcher Customisation":
                return "Home artwork, launch-stage motion, language and theme controls in one visual deck.";
            case "Launcher Settings":
                return "Language, downloads, permissions, and launcher-side behavior in one quick deck.";
            case "Video & Graphics":
                return "Renderer, resolution, VSync, and display behavior for smooth Minecraft sessions.";
            case "Controls":
                return "Buttons, mouse, gyro, gamepad";
            case "All Controls":
                return "Every control option";
            case "Java Runtime":
                return "Memory, runtimes, sandboxing, and advanced JVM launch parameters.";
            case "Audio":
                return "Master audio behavior, backend selection, and sound quality controls.";
            case "Account":
                return "Profile visibility and account-related launcher status panels.";
            case "Experimental":
                return "Visual experiments, background personalization, and power-user toggles.";
            case "Maintenance":
                return "Cache management, reset tools, and maintenance actions for the launcher.";
            case "Miscellaneous":
                return "Verification, capes, and extra compatibility switches that support special cases.";
            case "Sponsors":
                return "Official partners who keep CS LAUNCHER PLUS fast, free, and professionally backed.";
            case "Performance":
                return "Live FPS, memory telemetry and low-pause runtime tuning for steadier gameplay.";
            default:
                return "Premium launcher settings tailored for a mobile Minecraft experience.";
        }
    }

    private String resolveCategoryBadgeByName(@Nullable String catName) {
        if (catName == null) return "HUB";
        switch (catName) {
            case "Launcher Customisation": return "STYLE";
            case "General": return "CORE";
            case "Game": return "JVM";
            case "Display": return "GPU";
            case "Advanced": return "LAB";
            case "Launcher Settings": return "CORE";
            case "Video & Graphics": return "GPU";
            case "Controls": return "INPUT";
            case "Java Runtime": return "JVM";
            case "Audio": return "AUDIO";
            case "Account": return "PROFILE";
            case "Experimental": return "LAB";
            case "Maintenance": return "TOOLS";
            case "Miscellaneous": return "EXTRA";
            case "Sponsors": return "PARTNER";
            case "Performance": return "LIVE";
            default: return "PAGE";
        }
    }

    private List<SettingItem> buildRootCategoryItems() {
        // Settings v5: five plain-language groups. Every legacy page still
        // exists behind them (search indexes ALL of them) — only the hub got
        // simpler. Power-user content lives under Advanced, hidden by default.
        List<SettingItem> rootItems = new ArrayList<>();
        rootItems.add(new SettingItem("cat_general", SettingItem.TYPE_CATEGORY_LINK,
                "General", "Updates, animations, download server", "General"));
        rootItems.add(new SettingItem("cat_game", SettingItem.TYPE_CATEGORY_LINK,
                "Game", "RAM, performance mode, Java", "Game"));
        rootItems.add(new SettingItem("cat_display", SettingItem.TYPE_CATEGORY_LINK,
                "Display", "Resolution, VSync, FSR", "Display"));
        rootItems.add(new SettingItem("cat_controls", SettingItem.TYPE_CATEGORY_LINK,
                "Controls", "Buttons, mouse, gyro, gamepad", "Controls"));
        rootItems.add(new SettingItem("cat_advanced", SettingItem.TYPE_CATEGORY_LINK,
                "Advanced", "Everything else", "Advanced"));
        return rootItems;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
        updateSaveBar();
    }

    @Override
    public void onDestroyView() {
        if (mAdapter != null) {
            mAdapter.cleanup();
        }
        if (mIsDirty) {
            SettingsSaveManager.commitChanges(getContext());
            LauncherPreferences.loadPreferences(getContext());
            mIsDirty = false;
        }
        super.onDestroyView();
    }

    private void initializeDraft(Context context) {
        // The game process may have changed shared settings (FPS chip, gyro, resolution…) while
        // the launcher was in the background. Pull those in first, otherwise the draft — and the
        // bulk save built from it — would silently roll them back.
        try {
            net.kdt.pojavlaunch.prefs.SharedSettings.syncIntoPreferences(context);
        } catch (Throwable ignored) {}
        SharedPreferences mainPrefs = context.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
        SharedPreferences draftPrefs = SettingsSaveManager.getDraftPrefs(context);
        SharedPreferences.Editor editor = draftPrefs.edit();
        editor.clear();
        for (Map.Entry<String, ?> entry : mainPrefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            }
        }
        editor.commit();
    }


    /**
     * Settings v6: the four everyday pages. Same keys, types, defaults and
     * ranges as the legacy rows (the game reads exactly these prefs) — only the
     * wording is plain and the list is short.
     */
    private void addSimplePageTo(List<SettingCategory> categories, String catName) {
        List<SettingItem> items = new ArrayList<>();
        switch (catName) {
            case "General":
                items.add(new SettingItem("perf_presets_general", SettingItem.TYPE_PRESET_PANEL,
                        "Quick Device Optimizer", "1-Click auto-tuning for your device", null));
                items.add(new SettingItem("check_for_update_btn", SettingItem.TYPE_ACTION,
                        "Check for updates", "See if a newer CS Launcher Plus is available", null)
                        .setAction(() -> net.kdt.pojavlaunch.remote.FirebaseSyncManager.checkForUpdateManual(requireActivity())));
                items.add(new SettingItem("launcher_animations", SettingItem.TYPE_DROPDOWN,
                        "Animations", "Turn launcher animations on or off", "full")
                        .setDropdownOptions(new String[]{"On", "Off"}, new String[]{"full", "off"}));
                items.add(new SettingItem("launcher_transition_style", SettingItem.TYPE_DROPDOWN,
                        "Page transitions", "How screens move when you navigate", "slide")
                        .setDropdownOptions(new String[]{"Slide (Default)", "Zoom", "Jelly Bounce", "Bounce", "Fade", "None"},
                                new String[]{"slide", "zoom", "jelly", "bounce", "fade", "off"}));
                items.add(new SettingItem("launcher_animate_speed", SettingItem.TYPE_SLIDER,
                        "Animation speed", "Global UI animation speed multiplier", 100)
                        .setSliderConfig(50, 200, 10, " %"));
                items.add(new SettingItem("downloadSource", SettingItem.TYPE_DROPDOWN,
                        "Download server", "Where game files are downloaded from", "default")
                        .setDropdownOptions(new String[]{"Default", "Mirror (China)"}, new String[]{"default", "china"}));
                items.add(new SettingItem("game_log_display_mode", SettingItem.TYPE_DROPDOWN,
                        "Game launch logs", "Animated Smart replaces startup spam with CS intro; Full Raw shows all lines", "animated_smart")
                        .setDropdownOptions(new String[]{"Animated Smart Logs (Default)", "Full Raw Logs"},
                                new String[]{"animated_smart", "full_raw"}));
                items.add(new SettingItem("force_english", SettingItem.TYPE_SWITCH,
                        "Force English", "Override system language to English", false));
                items.add(new SettingItem("verifyManifest", SettingItem.TYPE_SWITCH,
                        "Verify file integrity", "Validate game asset SHA checksums before launching", true));
                items.add(new SettingItem("notification_permission_request", SettingItem.TYPE_SWITCH,
                        "Notifications", "Show download and launch notifications", false));
                break;
            case "Game": {
                int maxRAM = Tools.getMaximumRamAllocation(requireContext());
                int selectedRam = LauncherPreferences.DEFAULT_PREF.getInt("allocation", 1024);
                items.add(new SettingItem("perf_presets_game", SettingItem.TYPE_PRESET_PANEL,
                        "Quick Device Optimizer", "1-Click auto-tuning for your device", null));
                items.add(new SettingItem("allocation", SettingItem.TYPE_SLIDER,
                        "RAM for Minecraft", "Allocated heap for Minecraft and mods", 1024)
                        .setSliderConfig(256, Math.max(maxRAM, selectedRam), 128, " MB"));
                items.add(new SettingItem("ram_effective_info", SettingItem.TYPE_INFO,
                        "Exact Minecraft heap", "Minecraft will launch with -Xmx" + selectedRam + "M", null));
                items.add(new SettingItem(PerformanceMode.PREF_KEY, SettingItem.TYPE_DROPDOWN,
                        "Performance mode", "Normal is balanced; Performance boosts FPS; Maximum unlocks hardware limits", PerformanceMode.NORMAL.key)
                        .setDropdownOptions(SIMPLE_PERF_LABELS, PERF_MODE_VALUES));
                items.add(new SettingItem("install_jre", SettingItem.TYPE_ACTION,
                        "Java runtime", "Download, install, or pick installed Java runtimes", null)
                        .setAction(this::openMultiRTDialog));
                items.add(new SettingItem("javaArgs", SettingItem.TYPE_INPUT,
                        "Custom JVM arguments", "Extra JVM launch flags (e.g. -XX:+UseG1GC)", ""));
                items.add(new SettingItem("java_sandbox", SettingItem.TYPE_SWITCH,
                        "Java sandbox", "Isolate Minecraft environment for stability", true));
                items.add(new SettingItem("disable_autojre_select", SettingItem.TYPE_SWITCH,
                        "Disable auto JRE selection", "Stop automatic Java version picking", false));
                break;
            }
            case "Display":
                items.add(new SettingItem("perf_presets_display", SettingItem.TYPE_PRESET_PANEL,
                        "Quick Device Optimizer", "1-Click auto-tuning for your device", null));
                items.add(new SettingItem("resolutionRatio", SettingItem.TYPE_SLIDER,
                        "Resolution", "Lower for more FPS. 100% is native sharpness.", 100)
                        .setSliderConfig(25, 100, 5, " %"));
                items.add(new SettingItem("force_vsync", SettingItem.TYPE_SWITCH,
                        "VSync", "Locks FPS to your display refresh rate for smooth tear-free motion", false));
                items.add(new SettingItem("mg_renderer_setting_fsr", SettingItem.TYPE_DROPDOWN,
                        "FSR upscaling", "Render at lower resolution and upscale with AMD FSR for high FPS", "0")
                        .setDropdownOptions(new String[]{"Off", "25%", "50%", "75%", "100%"},
                                new String[]{"0", "1", "2", "3", "4"}));
                items.add(new SettingItem("alternate_surface", SettingItem.TYPE_SWITCH,
                        "Alternate Surface (SurfaceView)", "Use direct SurfaceView rendering for lower latency and better frame pacing", true));
                items.add(new SettingItem("ignoreNotch", SettingItem.TYPE_SWITCH,
                        "Use notch area", "Draw the game under the camera cutout", false));
                items.add(new SettingItem("vsync_in_zink", SettingItem.TYPE_SWITCH,
                        "VSync in Zink (Vulkan)", "Allow Minecraft to control frame synchronization in Vulkan Zink", true));
                items.add(new SettingItem("mg_renderer_setting_angle", SettingItem.TYPE_DROPDOWN,
                        "ANGLE backend", "ANGLE translation backend configuration", "1")
                        .setDropdownOptions(new String[]{"Vulkan", "OpenGL (System)", "OpenGLES"}, new String[]{"1", "2", "3"}));
                items.add(new SettingItem("mg_renderer_setting_multidraw", SettingItem.TYPE_DROPDOWN,
                        "Multidraw emulation", "Select multidraw emulation mode for complex scenes", "0")
                        .setDropdownOptions(new String[]{"Auto", "Prefer Indirect", "Prefer BaseVertex", "Prefer MultiDraw Indirect", "Force DrawElements"},
                                new String[]{"0", "1", "2", "3", "4"}));
                break;
            case "Controls":
                items.add(new SettingItem("buttonscale", SettingItem.TYPE_SLIDER,
                        "Button size", "Size of the on-screen buttons", 100)
                        .setSliderConfig(20, 200, 10, " %"));
                items.add(new SettingItem("mousescale", SettingItem.TYPE_SLIDER,
                        "Cursor size", "Size of the virtual mouse cursor", 100)
                        .setSliderConfig(25, 300, 25, " %"));
                items.add(new SettingItem("mousespeed", SettingItem.TYPE_SLIDER,
                        "Mouse speed", "How fast the cursor moves", 100)
                        .setSliderConfig(25, 300, 25, " %"));
                items.add(new SettingItem("enableGyro", SettingItem.TYPE_SWITCH,
                        "Gyroscope aim", "Tilt the phone to look around", false));
                items.add(new SettingItem("gyroSensitivity", SettingItem.TYPE_SLIDER,
                        "Gyro sensitivity", "How strongly tilting moves the camera", 100)
                        .setSliderConfig(10, 500, 10, " %"));
                items.add(new SettingItem("gyroSmoothing", SettingItem.TYPE_SWITCH,
                        "Gyro smoothing", "Filter out tiny micro-shakes", true));
                items.add(new SettingItem("timeLongPressTrigger", SettingItem.TYPE_SLIDER,
                        "Long press delay", "Hold duration for virtual clicks", 300)
                        .setSliderConfig(100, 1000, 50, " ms"));
                items.add(new SettingItem("disableDoubleTap", SettingItem.TYPE_SWITCH,
                        "Swipe to swap hand", "Quick swipe swaps secondary item", false));
                items.add(new SettingItem("mouse_start", SettingItem.TYPE_SWITCH,
                        "Virtual mouse auto-start", "Turn pointer cursor on automatically on game start", false));
                items.add(new SettingItem("always_grab_mouse", SettingItem.TYPE_SWITCH,
                        "Always grab mouse", "Keep mouse focus locked inside the window", false));
                items.add(new SettingItem("gamepad_remap_action", SettingItem.TYPE_ACTION,
                        "Gamepad buttons", "Remap your controller's buttons", null)
                        .setAction(() -> Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, "GAMEPAD_MAPPER", null)));
                items.add(new SettingItem("gamepad_deadzone_scale", SettingItem.TYPE_SLIDER,
                        "Gamepad deadzone", "Analog joystick drift ignore range", 100)
                        .setSliderConfig(0, 100, 5, " %"));
                items.add(new SettingItem("gamepadPassthru", SettingItem.TYPE_SWITCH,
                        "Gamepad SDL passthrough", "Route controller input directly through SDL", true));
                items.add(new SettingItem("touchControllerVibrateLength", SettingItem.TYPE_SLIDER,
                        "Touch vibration length", "Haptic duration on touch presses", 100)
                        .setSliderConfig(0, 500, 10, " ms"));
                break;
            default:
                return;
        }
        categories.add(new SettingCategory(catName, items));
    }

    /** Builds the real option page for one category (extracted so the Search
     *  index can build every page's items off-screen). */
    private void addCategoryPageTo(List<SettingCategory> categories, String catName) {
        // Settings v5 top-level groups — composed from the legacy pages so no
        // functionality moves or disappears, it is just presented simpler.
        switch (catName) {
            case "General":
            case "Game":
            case "Display":
            case "Controls":
                addSimplePageTo(categories, catName);
                return;
            case "Advanced": {
                // Every legacy page, minus the rows already on the simple pages
                // and minus info / preset / telemetry panels. Section names stay.
                List<SettingCategory> raw = new ArrayList<>();
                for (String legacy : ADVANCED_SOURCES) {
                    try { addCategoryPageTo(raw, legacy); } catch (Throwable ignored) {}
                }
                for (SettingCategory sc : raw) {
                    List<SettingItem> keep = new ArrayList<>();
                    for (SettingItem it : sc.items) {
                        if (it.type == SettingItem.TYPE_INFO || it.type == SettingItem.TYPE_PRESET_PANEL
                                || it.type == SettingItem.TYPE_THEME_SELECTOR
                                || it.type == SettingItem.TYPE_PERFORMANCE_PANEL) continue;
                        if (it.key != null && SIMPLE_KEYS.contains(it.key)) continue;
                        keep.add(it);
                    }
                    if (!keep.isEmpty()) categories.add(new SettingCategory(sc.title, keep));
                }
                return;
            }
        }
        // Subcategory Pages Detail (100% Real Authentic Launcher Options)
            switch (catName) {
                case "Launcher Customisation":
                    List<SettingItem> custItems = new ArrayList<>();
                    // ── Home Screen Background option REMOVED from the Settings UI
                    //    (developer req 8). The underlying functionality is
                    //    intentionally preserved — the image picker
                    //    (mImagePickerLauncher), RightPaneHomeFragment.CUSTOM_BG_PATH,
                    //    notifyHomeFragmentBgChanged() and ThemeManager still exist —
                    //    so nothing that depends on it breaks; the two picker/reset
                    //    items simply are no longer shown to the user.
                    // Launch GIF import retired; the launch stage uses the lightweight static color.
                    // ── Launcher theme ──
                    // ── Launcher animations (user req: pick Full or Off) ──
                    custItems.add(new SettingItem("launcher_animations", SettingItem.TYPE_DROPDOWN,
                            "Launcher Animations",
                            "Full = all animations on. Off = instant, no motion at all.",
                            "full")
                            .setDropdownOptions(new String[]{"Full Animations", "Off (No Animations)"},
                                    new String[]{"full", "off"}));
                    // ── Language (English only for now — Android default) ──
                    custItems.add(new SettingItem("launcher_language", SettingItem.TYPE_DROPDOWN,
                            "Language",
                            "English only for now — the launcher keeps the system default",
                            "en")
                            .setDropdownOptions(new String[]{"English"}, new String[]{"en"}));
                    categories.add(new SettingCategory("Look & feel", custItems));
                    break;

                case "Launcher Settings":
                    List<SettingItem> launcherItems = new ArrayList<>();
                    launcherItems.add(new SettingItem("perf_presets_launcher", SettingItem.TYPE_PRESET_PANEL, "Performance Presets", "", null));
                    launcherItems.add(new SettingItem("check_for_update_btn", SettingItem.TYPE_ACTION,
                            "Check for Update",
                            "Check online for the latest CS LAUNCHER PLUS release",
                            null).setAction(() -> {
                        net.kdt.pojavlaunch.remote.FirebaseSyncManager.checkForUpdateManual(requireActivity());
                    }));
                    launcherItems.add(new SettingItem("force_english", SettingItem.TYPE_SWITCH, getString(R.string.preference_force_english_title), getString(R.string.preference_force_english_description), false));
                    launcherItems.add(new SettingItem("notification_permission_request", SettingItem.TYPE_SWITCH, getString(R.string.preference_ask_for_notification_title), getString(R.string.preference_ask_for_notification_description), false));
                    launcherItems.add(new SettingItem("microphone_permission_request", SettingItem.TYPE_SWITCH, getString(R.string.preference_ask_for_microphone_title), getString(R.string.preference_ask_for_microphone_description), false));
                    launcherItems.add(new SettingItem("downloadSource", SettingItem.TYPE_DROPDOWN, getString(R.string.preference_download_source_title), getString(R.string.preference_download_source_description), "default").setDropdownOptions(new String[]{"Default", "Mirror (China)"}, new String[]{"default", "china"}));
                    launcherItems.add(new SettingItem("verifyManifest", SettingItem.TYPE_SWITCH, getString(R.string.preference_verify_manifest_title), getString(R.string.preference_verify_manifest_description), true));
                    launcherItems.add(new SettingItem("game_log_display_mode", SettingItem.TYPE_DROPDOWN,
                            "Game Launch Logs",
                            "Animated Smart replaces startup spam with the CS CLI intro and keeps important logs; Full Raw shows every line.",
                            "animated_smart")
                            .setDropdownOptions(
                                    new String[]{"Animated Smart Logs (Default)", "Full Raw Logs"},
                                    new String[]{"animated_smart", "full_raw"}));
                    // A9: global animation speed (in-house concept). One
                    // slider retimes every UiMotion animation via MotionSpeed.
                    launcherItems.add(new SettingItem("launcher_animate_speed", SettingItem.TYPE_SLIDER,
                            "Animation Speed", "Global animation duration multiplier for the launcher UI",
                            100).setSliderConfig(50, 200, 10, " %"));
                    // Page-transition personality. Every screen change in the launcher reads this
                    // through MotionTransitions, so one choice restyles the whole navigation.
                    launcherItems.add(new SettingItem("launcher_transition_style", SettingItem.TYPE_DROPDOWN,
                            "Page Transition",
                            "How screens move when you navigate: Slide travels sideways, Zoom goes deeper, Jelly and Bounce land with physics",
                            "slide")
                            .setDropdownOptions(
                                    new String[]{"Slide (Default)", "Zoom", "Jelly Bounce", "Bounce", "Fade", "None"},
                                    new String[]{"slide", "zoom", "jelly", "bounce", "fade", "off"}));
                    // How far elements travel while animating (0 = subtle, 10 = dramatic).
                    launcherItems.add(new SettingItem("launcher_animate_amplitude", SettingItem.TYPE_SLIDER,
                            "Animation Amplitude",
                            "How far cards, rows and pages travel while they animate",
                            5).setSliderConfig(0, 10, 1, ""));
                    categories.add(new SettingCategory("Launcher", launcherItems));
                    break;

                case "Video & Graphics":
                    List<SettingItem> graphicsItems = new ArrayList<>();
                    graphicsItems.add(new SettingItem("perf_presets_graphics", SettingItem.TYPE_PRESET_PANEL, "Performance Presets", "", null));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_angle", SettingItem.TYPE_DROPDOWN, getString(R.string.mg_renderer_angle), "Select backend ANGLE configuration", "1").setDropdownOptions(new String[]{"Vulkan", "OpenGL (System)", "OpenGLES"}, new String[]{"1", "2", "3"}));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_multidraw", SettingItem.TYPE_DROPDOWN, getString(R.string.mg_renderer_multidraw), "Select multidraw emulation style", "0").setDropdownOptions(new String[]{"Auto", "Prefer Indirect", "Prefer BaseVertex", "Prefer MultiDraw Indirect", "Force DrawElements"}, new String[]{"0", "1", "2", "3", "4"}));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_fsr", SettingItem.TYPE_DROPDOWN, getString(R.string.mg_renderer_title_fsr), "Enable AMD FSR scaler", "0").setDropdownOptions(new String[]{"Disabled", "25%", "50%", "75%", "100%"}, new String[]{"0", "1", "2", "3", "4"}));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_errorSetting", SettingItem.TYPE_DROPDOWN, getString(R.string.mg_renderer_title_errorSetting), "Configure error handling in GL", "0").setDropdownOptions(new String[]{"Report", "Ignore"}, new String[]{"0", "1"}));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_timerQueryExt", SettingItem.TYPE_SWITCH, getString(R.string.mg_renderer_title_timerQueryExt), getString(R.string.mg_renderer_summary_timerQueryExt), false));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_angleDepthClearFixMode", SettingItem.TYPE_SWITCH, getString(R.string.mg_renderer_title_angleDepthClearFixMode), getString(R.string.mg_renderer_summary_angleDepthClearFixMode), false));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_gl43exts", SettingItem.TYPE_SWITCH, getString(R.string.mg_renderer_title_gl43exts), getString(R.string.mg_renderer_summary_gl43exts), false));
                    graphicsItems.add(new SettingItem("mg_renderer_computeShaderext", SettingItem.TYPE_SWITCH, getString(R.string.mg_renderer_title_computeShaderext), getString(R.string.mg_renderer_summary_computeShaderext), false));
                    graphicsItems.add(new SettingItem("mg_renderer_dsaExt", SettingItem.TYPE_SWITCH, getString(R.string.mg_renderer_title_dsaExt), getString(R.string.mg_renderer_summary_dsaExt), false));
                    graphicsItems.add(new SettingItem("mg_renderer_multidrawCompute", SettingItem.TYPE_SWITCH, getString(R.string.mg_renderer_title_multidrawCompute), getString(R.string.mg_renderer_summary_multidrawCompute), false));
                    graphicsItems.add(new SettingItem("mg_renderer_setting_glsl_cache_size", SettingItem.TYPE_INPUT, getString(R.string.mg_renderer_glsl_cache), "Input cache size limit", "128"));
                    graphicsItems.add(new SettingItem("ignoreNotch", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_title_ignore_notch), getString(R.string.mcl_setting_subtitle_ignore_notch), false));
                    graphicsItems.add(new SettingItem("resolutionRatio", SettingItem.TYPE_SLIDER, getString(R.string.mcl_setting_title_resolution_scaler), getString(R.string.mcl_setting_subtitle_resolution_scaler), 100).setSliderConfig(25, 100, 5, " %"));
                    graphicsItems.add(new SettingItem("alternate_surface", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_title_use_surface_view), getString(R.string.mcl_setting_subtitle_use_surface_view), true));
                    graphicsItems.add(new SettingItem("force_vsync", SettingItem.TYPE_SWITCH, getString(R.string.preference_force_vsync_title), getString(R.string.preference_force_vsync_description), false));
                    graphicsItems.add(new SettingItem("vsync_in_zink", SettingItem.TYPE_SWITCH, getString(R.string.preference_vsync_in_zink_title), getString(R.string.preference_vsync_in_zink_description), true));
                    categories.add(new SettingCategory("Graphics", graphicsItems));
                    break;

                case "All Controls":
                    List<SettingItem> controlsItems = new ArrayList<>();
                    controlsItems.add(new SettingItem("buttonscale", SettingItem.TYPE_SLIDER, "Button Scale", "Adjust sized layout overlays", 100).setSliderConfig(20, 200, 10, " %"));
                    controlsItems.add(new SettingItem("mousescale", SettingItem.TYPE_SLIDER, "Mouse Cursor Scale", "Adjust virtual mouse cursor size", 100).setSliderConfig(25, 300, 25, " %"));
                    controlsItems.add(new SettingItem("mousespeed", SettingItem.TYPE_SLIDER, "Mouse Speed", "Adjust virtual cursor movement sensitivity", 100).setSliderConfig(25, 300, 25, " %"));
                    controlsItems.add(new SettingItem("disableGestures", SettingItem.TYPE_SWITCH, "Disable Gestures", "Disable gesture-based navigation overrides", false));
                    controlsItems.add(new SettingItem("timeLongPressTrigger", SettingItem.TYPE_SLIDER, "Long Press Trigger Delay", "Hold duration for virtual clicks", 300).setSliderConfig(100, 1000, 50, " ms"));
                    controlsItems.add(new SettingItem("disableDoubleTap", SettingItem.TYPE_SWITCH, "Swipe to Swap Hand", "Quick swipe swaps secondary item", false));
                    controlsItems.add(new SettingItem("mouse_start", SettingItem.TYPE_SWITCH, "Virtual Mouse Auto-Start", "Turn pointer cursor on automatically on game start", false));
                    controlsItems.add(new SettingItem("always_grab_mouse", SettingItem.TYPE_SWITCH, "Always Grab Mouse", "Keep mouse focus locked inside the window", false));
                    controlsItems.add(new SettingItem("enableGyro", SettingItem.TYPE_SWITCH, "Enable Gyroscope Sensor", "Use device motion sensors for controls", false));
                    controlsItems.add(new SettingItem("gyroSensitivity", SettingItem.TYPE_SLIDER, "Gyro Sensitivity", "Adjust motion sensitivity scaling", 100).setSliderConfig(10, 500, 10, " %"));
                    controlsItems.add(new SettingItem("gyroSampleRate", SettingItem.TYPE_SLIDER, "Gyro Sample Rate", "Adjust sensor update delay", 16).setSliderConfig(10, 100, 2, " ms"));
                    controlsItems.add(new SettingItem("gyroSmoothing", SettingItem.TYPE_SWITCH, "Gyro Smoothing", "Filter out tiny micro-shakes", true));
                    controlsItems.add(new SettingItem("gyroInvertX", SettingItem.TYPE_SWITCH, "Gyro Invert X Axis", "Invert horizontal rotation controls", false));
                    controlsItems.add(new SettingItem("gyroInvertY", SettingItem.TYPE_SWITCH, "Gyro Invert Y Axis", "Invert vertical rotation controls", false));
                    controlsItems.add(new SettingItem("gamepad_deadzone_scale", SettingItem.TYPE_SLIDER, "Gamepad Deadzone", "Analog joystick drift ignore range", 100).setSliderConfig(0, 100, 5, " %"));
                    controlsItems.add(new SettingItem("gamepadPassthru", SettingItem.TYPE_SWITCH, "Gamepad SDL Passthrough", "Route controller input directly through SDL", true));
                    controlsItems.add(new SettingItem("gamepadPassthruForced", SettingItem.TYPE_SWITCH, "Force Gamepad SDL Passthrough", "Detect unsupported controllers", false));
                    controlsItems.add(new SettingItem("forceEnableTouchController", SettingItem.TYPE_SWITCH, "Force Touch Controls", "Force visual control pads", false));
                    controlsItems.add(new SettingItem("touchControllerVibrateLength", SettingItem.TYPE_SLIDER, "Vibration length", "Haptic duration on touch presses", 100).setSliderConfig(0, 500, 10, " ms"));
                    controlsItems.add(new SettingItem("gamepad_remap_action", SettingItem.TYPE_ACTION, "Remap Gamepad Controller", "Map gamepad key layouts", null).setAction(() -> Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, "GAMEPAD_MAPPER", null)));
                    controlsItems.add(new SettingItem("gamepad_wipe_action", SettingItem.TYPE_ACTION, "Wipe Controller Map", "Clear custom gamepad configurations", null).setAction(() -> {
                        Remapper.wipePreferences(getContext());
                        Toast.makeText(getContext(), R.string.preference_controller_map_wiped, Toast.LENGTH_SHORT).show();
                    }));
                    categories.add(new SettingCategory("Controls", controlsItems));
                    break;

                case "Java Runtime":
                    List<SettingItem> javaItems = new ArrayList<>();
                    javaItems.add(new SettingItem("perf_presets_java", SettingItem.TYPE_PRESET_PANEL, "Performance Presets", "", null));
                    javaItems.add(new SettingItem("install_jre", SettingItem.TYPE_ACTION, getString(R.string.multirt_title), getString(R.string.multirt_subtitle), null).setAction(this::openMultiRTDialog));
                    javaItems.add(new SettingItem("javaArgs", SettingItem.TYPE_INPUT, getString(R.string.mcl_setting_title_javaargs), getString(R.string.mcl_setting_subtitle_javaargs), ""));

                    int maxRAM = Tools.getMaximumRamAllocation(requireContext());
                    int selectedRam = LauncherPreferences.DEFAULT_PREF.getInt("allocation", 1024);
                    javaItems.add(new SettingItem("allocation", SettingItem.TYPE_SLIDER, getString(R.string.mcl_memory_allocation), getString(R.string.mcl_memory_allocation_subtitle), 1024).setSliderConfig(256, Math.max(maxRAM, selectedRam), 128, " MB"));
                    javaItems.add(new SettingItem("ram_effective_info", SettingItem.TYPE_INFO,
                            "Exact Minecraft heap", "Minecraft will launch with -Xmx" + selectedRam + "M", null));

                    javaItems.add(new SettingItem("disable_autojre_select", SettingItem.TYPE_SWITCH, "Disable automatic JRE selection", "Stops automatic selection of which runtime to use", false));
                    javaItems.add(new SettingItem("java_sandbox", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_java_sandbox), getString(R.string.mcl_setting_java_sandbox_subtitle), true));
                    categories.add(new SettingCategory("Memory & Java", javaItems));
                    break;

                case "Audio":
                    List<SettingItem> audioItems = new ArrayList<>();
                    audioItems.add(new SettingItem("enable_audio", SettingItem.TYPE_SWITCH, "Enable Game Sound", "Allow game instances to play audio", true));
                    audioItems.add(new SettingItem("launcher_volume", SettingItem.TYPE_SLIDER, "Launcher Master Volume", "Default volume control for launched games", 80).setSliderConfig(0, 100, 5, " %"));
                    audioItems.add(new SettingItem("use_opensles", SettingItem.TYPE_SWITCH, "Use OpenSL ES Backend", "Enable low-latency high performance sound library", false));
                    categories.add(new SettingCategory("Sound", audioItems));
                    break;

                case "Account":
                    List<SettingItem> accountItems = new ArrayList<>();
                    MinecraftAccount activeAccount = PojavProfile.getCurrentProfileContent(requireContext(), null);
                    String accountName = activeAccount != null ? activeAccount.username : "None";
                    accountItems.add(new SettingItem("active_profile_info", SettingItem.TYPE_INFO,
                            "Active Account Profile", "Currently signed-in launcher profile", accountName));
                    categories.add(new SettingCategory("Account Configurations", accountItems));
                    break;

                case "Experimental":
                    List<SettingItem> expItems = new ArrayList<>();
                    expItems.add(new SettingItem("dump_shaders", SettingItem.TYPE_SWITCH, getString(R.string.preference_shader_dump_title), getString(R.string.preference_shader_dump_description), false));
                    // Manual CPU affinity is deprecated: Android's scheduler owns core placement, and
                    // the sysfs thermal sampler it replaced is gone — heat is read through
                    // PowerManager by the engine itself while a boosted session runs.
                    expItems.add(new SettingItem(PerformanceMode.PREF_EXPERIMENTAL_FLAGS, SettingItem.TYPE_SWITCH,
                            "Experimental JVM tuning",
                            "Lets the Performance and Maximum profiles also apply heap headroom, code-cache sizing and G1 young-generation sizing. "
                                    + "Off by default: those flags need a device benchmark before they belong in a profile.",
                            false));
                    // The retired orientation switch is gone for good: every page is
                    // built for a wide screen, and unlocking orientation app-wide is
                    // precisely what made the server hub open vertically.
                    categories.add(new SettingCategory("Experiments", expItems));
                    break;

                case "Maintenance":
                    List<SettingItem> advItems = new ArrayList<>();
                    advItems.add(new SettingItem("check_for_update_adv_btn", SettingItem.TYPE_ACTION,
                            "Check for Update",
                            "Check online for the latest CS LAUNCHER PLUS release",
                            null).setAction(() -> {
                        net.kdt.pojavlaunch.remote.FirebaseSyncManager.checkForUpdateManual(requireActivity());
                    }));
                    advItems.add(new SettingItem("clear_cache_files", SettingItem.TYPE_ACTION, "Clear Shader & Temporary Caches", "Free up storage by deleting temporary rendering files", null).setAction(() -> {
                        try {
                            clearCacheLocal(requireContext());
                            Toast.makeText(getContext(), "Caches cleared successfully", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(getContext(), "Failed to clear cache", Toast.LENGTH_SHORT).show();
                        }
                    }));
                    advItems.add(new SettingItem("reset_all_settings", SettingItem.TYPE_ACTION, "Reset Launcher Settings", "Restore factory defaults for all configuration profiles", null).setAction(() -> {
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Reset Settings")
                                .setMessage("Are you sure you want to reset all settings to defaults?")
                                .setPositiveButton("Reset", (dialog, which) -> {
                                    mDraftPrefs.edit().clear().commit();
                                    requireContext().getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE).edit().clear().commit();
                                    LauncherPreferences.loadPreferences(requireContext());
                                    Toast.makeText(requireContext(), "Settings reset successfully", Toast.LENGTH_SHORT).show();
                                    requireActivity().recreate();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    }));
                    categories.add(new SettingCategory("Tools", advItems));
                    break;

                case "Miscellaneous":
                    List<SettingItem> miscItems = new ArrayList<>();
                    miscItems.add(new SettingItem("checkLibraries", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_check_libraries), getString(R.string.mcl_setting_check_libraries_subtitle), true));
                    miscItems.add(new SettingItem("arc_capes", SettingItem.TYPE_SWITCH, getString(R.string.arc_capes_title), getString(R.string.arc_capes_desc), false));
                    miscItems.add(new SettingItem("zinkPreferSystemDriver", SettingItem.TYPE_SWITCH, getString(R.string.preference_vulkan_driver_system_title), getString(R.string.preference_vulkan_driver_system_description), false));
                    categories.add(new SettingCategory("Extras", miscItems));
                    break;

                case "Performance":
                    List<SettingItem> perfItems = new ArrayList<>();
                    // One control decides everything the engine does. The cards underneath print the
                    // policy exactly as it resolves on THIS phone, so the promise and the behaviour are
                    // produced by the same code and cannot drift apart.
                    perfItems.add(new SettingItem(PerformanceMode.PREF_KEY, SettingItem.TYPE_DROPDOWN,
                            "Performance Mode", perfModeSummary(mDraftPrefs), PerformanceMode.NORMAL.key)
                            .setDropdownOptions(PERF_MODE_LABELS, PERF_MODE_VALUES));
                    perfItems.add(new SettingItem("perfPolicyInfo", SettingItem.TYPE_INFO,
                            "What this means on this phone", perfPolicySummary(), null));
                    perfItems.add(new SettingItem("perfRendererAdvice", SettingItem.TYPE_INFO,
                            "Renderer on this device", perfRendererAdvice(), null));
                    // Phase 9: "Show FPS" / "Transparent FPS" left this page. The
                    // frame counter is a canvas control now (Custom Controls →
                    // ADD → FPS Counter) with its own look settings — transparent
                    // background, text size, unit — inside the control editor.
                    perfItems.add(new SettingItem("fpsCounterInfo", SettingItem.TYPE_INFO,
                            "FPS counter",
                            "Lives in Custom Controls now: add the FPS element from the control editor and style it there (transparent, size, unit).", null));
                    perfItems.add(new SettingItem("perf_presets", SettingItem.TYPE_PRESET_PANEL,
                            "Performance Presets",
                            "Sets the mode and the RAM band for a device class. Never touches resolution, shaders, FSR or frame pacing.",
                            null));
                    categories.add(new SettingCategory("Performance", perfItems));
                    break;

                case "Sponsors":
                    List<SettingItem> sponsorItems = new ArrayList<>();
                    // Global sponsorship gate (Firebase admin panel): when the
                    // admin disables sponsorship, this page shows a notice instead.
                    if (!net.kdt.pojavlaunch.remote.FirebaseSyncManager.isSponsorshipEnabled()) {
                        sponsorItems.add(new SettingItem("sponsors_disabled", SettingItem.TYPE_INFO,
                                "Sponsorship Disabled",
                                "The admin has turned sponsorship off — sponsor content is hidden across the launcher.",
                                null));
                        categories.add(new SettingCategory("Sponsors", sponsorItems));
                        break;
                    }
                    sponsorItems.add(new SettingItem("infrawire_partner_info", SettingItem.TYPE_INFO,
                            "Infrawire — Official Hosting Partner",
                            "High-Performance VPS & Cloud Hosting • Official Cloud Hosting Partner of CS LAUNCHER PLUS", null));
                    sponsorItems.add(new SettingItem("infrawire_about_info", SettingItem.TYPE_INFO,
                            "Official Sponsor",
                            "Infrawire powers CS LAUNCHER PLUS with latest-generation VPS & cloud infrastructure — NVMe SSD storage, DDR4 memory, a 10 Gbps independent global network, multi-layer Anti-DDoS protection, hourly billing from €0.007/hour and 24/7 expert support.", null));
                    sponsorItems.add(new SettingItem("infrawire_view_partner_page", SettingItem.TYPE_ACTION,
                            "View Partner Page", "Plans, benefits, and promotions — inside the launcher", null)
                            .setAction(() -> Tools.swapFragment(requireActivity(),
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartnerFragment.class,
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartnerFragment.TAG, null)));
                    sponsorItems.add(new SettingItem("infrawire_visit_website", SettingItem.TYPE_ACTION,
                            "Visit Website", "infrawire.net — VPS, cloud and dedicated infrastructure", null)
                            .setAction(() -> net.kdt.pojavlaunch.sponsor.InfrawirePartner.openLink(requireContext(),
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_WEBSITE)));
                    sponsorItems.add(new SettingItem("infrawire_deploy_vps", SettingItem.TYPE_ACTION,
                            "Deploy VPS", "High-performance VPS from €0.007/hour — deploy in ~55 seconds", null)
                            .setAction(() -> net.kdt.pojavlaunch.sponsor.InfrawirePartner.openLink(requireContext(),
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_VPS)));
                    sponsorItems.add(new SettingItem("infrawire_promotions", SettingItem.TYPE_ACTION,
                            "Latest Promotions", "Current offers and seasonal deals from Infrawire", null)
                            .setAction(() -> net.kdt.pojavlaunch.sponsor.InfrawirePartner.openLink(requireContext(),
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_PROMOTIONS)));
                    sponsorItems.add(new SettingItem("infrawire_documentation", SettingItem.TYPE_ACTION,
                            "Documentation", "Guides and the Infrawire knowledge base", null)
                            .setAction(() -> net.kdt.pojavlaunch.sponsor.InfrawirePartner.openLink(requireContext(),
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_DOCS)));
                    sponsorItems.add(new SettingItem("infrawire_support", SettingItem.TYPE_ACTION,
                            "Support — 24/7", "Get help from Infrawire's expert technical team", null)
                            .setAction(() -> net.kdt.pojavlaunch.sponsor.InfrawirePartner.openLink(requireContext(),
                                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.URL_SUPPORT)));
                    categories.add(new SettingCategory("Official Sponsors", sponsorItems));
                    break;
            }
    }

    private void setupSettingsList() {
        mAdapter = new SettingsAdapter(buildCurrentCategories(), mDraftPrefs);
        mRecyclerView.setAdapter(mAdapter);
        // Phase 3: every settings page gets its OWN entrance choreography
        mRecyclerView.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(
                requireContext(), pickPageAnimation()));
        mRecyclerView.scheduleLayoutAnimation();
        flashPendingFocusRow();
    }

    /** Root/hub page, category page, or live search results as grouped categories. */
    private List<SettingCategory> buildCurrentCategories() {
        List<SettingCategory> categories = new ArrayList<>();
        if (mSearchQuery != null && !mSearchQuery.isEmpty()) {
            buildSearchResults(categories, mSearchQuery);
        } else if (mCategoryName == null) {
            categories.add(new SettingCategory("Settings Categories", buildRootCategoryItems()));
        } else {
            addCategoryPageTo(categories, mCategoryName);
        }
        return categories;
    }

    /** Match title + summary + key across EVERY real settings page. */
    private void buildSearchResults(List<SettingCategory> out, String queryRaw) {
        String q = queryRaw.toLowerCase(java.util.Locale.US);
        for (String cat : ALL_CATEGORIES) {
            List<SettingCategory> tmp = new ArrayList<>();
            addCategoryPageTo(tmp, cat);
            List<SettingItem> matches = new ArrayList<>();
            for (SettingCategory page : tmp) {
                for (SettingItem item : page.items) {
                    if (item.type == SettingItem.TYPE_CATEGORY_LINK || item.type == SettingItem.TYPE_INFO
                            || item.type == SettingItem.TYPE_PRESET_PANEL) continue;
                    String hay = ((item.title != null ? item.title : "") + "\n"
                            + (item.summary != null ? item.summary : "") + "\n"
                            + (item.key != null ? item.key : "")).toLowerCase(java.util.Locale.US);
                    if (hay.contains(q)) matches.add(item);
                }
            }
            if (!matches.isEmpty()) {
                out.add(new SettingCategory(cat + " · " + matches.size() + (matches.size() == 1 ? " result" : " results"), matches));
            }
        }
        if (out.isEmpty()) {
            List<SettingItem> empty = new ArrayList<>();
            empty.add(new SettingItem("search_no_results", SettingItem.TYPE_ACTION,
                    getString(R.string.cs_settings_no_results),
                    getString(R.string.cs_settings_search_hint), null));
            out.add(new SettingCategory("Search", empty));
        }
    }

    /** Settings v4: one cohesive spring-rise entrance signature for every page. */
    private int pickPageAnimation() {
        return R.anim.settings_rows_layout_v4;
    }

    // ══ Search bar ══
    private void setupSettingsSearch(@NonNull View root) {
        mSearchInput = root.findViewById(R.id.settings_search_input);
        if (mSearchInput == null) return;
        // Settings v6: the four short pages need no search box. It stays on the
        // hub (find anything) and on Advanced (long list).
        if (mCategoryName != null && !"Advanced".equals(mCategoryName)) {
            View pillGone = (View) mSearchInput.getParent();
            if (pillGone != null) pillGone.setVisibility(View.GONE);
            mSearchInput = null;
            return;
        }
        mSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                mSearchQuery = e != null ? e.toString().trim() : "";
                if (mAdapter != null) mAdapter.replaceCategories(buildCurrentCategories());
            }
        });
        // Tactile focus: the search pill lifts & scales slightly while typing.
        final View pill = (View) mSearchInput.getParent();
        mSearchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) return;
            float target = hasFocus ? 1.02f : 1f;
            pill.animate().cancel();
            pill.animate().scaleX(target).scaleY(target).setDuration(200)
                    .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.SPRING)
                    .start();
        });
    }

    // ══ Favorite star (bound on every real setting row) ══
    private void bindFavoriteStar(@NonNull View row, @NonNull SettingItem item) {
        android.widget.ImageButton star = row.findViewById(R.id.setting_fav_star);
        if (star == null) return;
        // Phase 10 simple settings: no pin/favourite chrome on rows. The
        // meta store keeps working for "recent" bookkeeping; the star is
        // simply never shown.
        if (true) { star.setVisibility(View.GONE); return; }
        if (item.key == null || item.key.isEmpty() || item.key.startsWith("cat_")) {
            star.setVisibility(View.GONE);
            return;
        }
        star.setVisibility(View.VISIBLE);
        refreshFavoriteStar(star, item.key);
        star.setOnClickListener(v -> {
            Context c = v.getContext();
            net.kdt.pojavlaunch.prefs.SettingsMetaStore.toggleFavorite(c, item.key);
            refreshFavoriteStar(star, item.key);
            boolean fav = net.kdt.pojavlaunch.prefs.SettingsMetaStore.isFavorite(c, item.key);
            Toast.makeText(c, fav ? R.string.cs_settings_fav_add : R.string.cs_settings_fav_remove,
                    Toast.LENGTH_SHORT).show();
            star.animate().cancel();
            star.setScaleX(0.6f); star.setScaleY(0.6f);
            star.animate().scaleX(1.3f).scaleY(1.3f).setDuration(150)
                    .withEndAction(() -> star.animate().scaleX(1f).scaleY(1f).setDuration(160).start())
                    .start();
        });
    }

    private void refreshFavoriteStar(@NonNull android.widget.ImageButton star, @NonNull String key) {
        boolean fav = net.kdt.pojavlaunch.prefs.SettingsMetaStore.isFavorite(star.getContext(), key);
        star.setImageResource(fav ? R.drawable.ic_csp_star_filled : R.drawable.ic_csp_star_outline);
        star.setColorFilter(fav ? 0xFFFFFFFF : 0xFF6B7280);
    }

    // ══ Focus-jump: open a page and softly flash the target row ══
    private void jumpToSetting(@NonNull String category, @NonNull String key) {
        sPendingFocusKey = key;
        openCategoryPage(category);
    }

    private void flashPendingFocusRow() {
        if (sPendingFocusKey == null || mRecyclerView == null) return;
        final String key = sPendingFocusKey;
        sPendingFocusKey = null;
        mRecyclerView.postDelayed(() -> {
            if (mRecyclerView == null) return;
            for (int i = 0; i < mRecyclerView.getChildCount(); i++) {
                View vh = mRecyclerView.getChildAt(i);
                if (!(vh instanceof ViewGroup)) continue;
                View row = vh.findViewWithTag(key);
                if (row == null) continue;
                mRecyclerView.smoothScrollToPosition(i);
                final View target = row;
                target.postDelayed(() -> {
                    target.animate().cancel();
                    target.setAlpha(0.35f);
                    target.animate().alpha(1f).setDuration(320)
                            .withEndAction(() -> {
                                target.setAlpha(0.45f);
                                target.animate().alpha(1f).setDuration(320).start();
                            }).start();
                }, 380);
                break;
            }
        }, 420);
    }

    // ── Performance Mode cards ──────────────────────────────────────────────────────────────────
    // The mode labels live here and nowhere else: the dropdown, the summary line and the
    // confirmation sheet are all rendered from PerformanceMode, so adding a mode is a one-file change.

    private static final String[] PERF_MODE_LABELS = {
            "Normal — balanced (recommended)",
            "Performance — higher FPS",
            "Maximum Performance — highest sustainable FPS"
    };
    private static final String[] PERF_MODE_VALUES = {
            PerformanceMode.NORMAL.key, PerformanceMode.PERFORMANCE.key, PerformanceMode.MAXIMUM.key
    };

    private static String perfModeSummary(SharedPreferences prefs) {
        PerformanceMode mode = PerformanceMode.fromPrefs(prefs);
        String text = mode.headline + "\n" + mode.behaviour;
        if (mode == PerformanceMode.MAXIMUM) {
            text += "\nHeat and battery drain rise, and the device will throttle: the engine then sheds its own "
                    + "boost instead of pushing through it.";
        }
        return text;
    }

    /** Preview of the policy the next launch will freeze, from the draft settings on screen. */
    private String perfPolicySummary() {
        try {
            Context ctx = requireContext();
            DeviceCapability.ensureAsync(ctx);
            PerformancePolicy preview = PerformancePolicy.resolve(ctx, mDraftPrefs, null);
            StringBuilder b = new StringBuilder(preview.describe());
            b.append("\n").append(DeviceCapability.current().describe());
            b.append("\n").append(ThermalGovernor.describe(ctx));
            b.append("\n").append(SchedulingPolicy.lastEngineNote());
            String measured = SessionStats.describeRecent(ctx, Tools.LOCAL_RENDERER, preview.mode.key);
            if (!measured.isEmpty()) b.append("\n").append(measured);
            return b.toString();
        } catch (Throwable t) {
            return "Device profile unavailable right now — the launch still resolves its own policy.";
        }
    }

    private String perfRendererAdvice() {
        try {
            return RendererPolicy.recommendationText(requireContext(), DeviceCapability.current());
        } catch (Throwable t) {
            return "Renderer detection unavailable.";
        }
    }

    /** Maximum is allowed, but never silently: what it costs is said once, before it is turned on. */
    private void confirmMaximumMode(android.content.Context ctx, final Runnable onAccept) {
        try {
            SharedPreferences prefs = SettingsSaveManager.getDraftPrefs(ctx);
            if (prefs.getBoolean(PerformanceMode.PREF_MAX_ACK, false)) {
                onAccept.run();
                return;
            }
            String body = "Maximum Performance asks the phone for everything it is willing to give, "
                    + "for as long as it can hold it: the game's render and present threads run at urgent "
                    + "display priority, an ADPF hint session carries the frame budget on Android 12 and "
                    + "newer, the launcher pauses its own background work while you play, and the JVM's "
                    + "garbage collection is tuned for this device instead of its generic defaults."
                    + "\n\n"
                    + "What it costs: more heat and noticeably faster battery drain. It is not an overclock "
                    + "and it cannot bypass the thermals — when the device reports throttling, the engine "
                    + "reduces its own requests rather than pushing through, which is why the promise is the "
                    + "highest sustainable frame rate, not the highest number in the first minute."
                    + "\n\n"
                    + "Nothing about your game changes: resolution, render distance, particles, shaders and "
                    + "any frame cap you set stay exactly as you left them. Do not use it while charging "
                    + "under a pillow or in a hot car."
                    + "\n\n"
                    + "Applies from the next launch of Minecraft. A phone that gets too warm will settle "
                    + "back toward normal clocks on its own.";
            new AlertDialog.Builder(ctx)
                    .setTitle("Maximum Performance")
                    .setMessage(body)
                    .setPositiveButton("Use it", (d, w) -> {
                        try {
                            SettingsSaveManager.getDraftPrefs(ctx).edit()
                                    .putBoolean(PerformanceMode.PREF_MAX_ACK, true).apply();
                        } catch (Throwable ignored) {
                        }
                        onAccept.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable t) {
            onAccept.run();
        }
    }

    private boolean isItemVisible(SettingItem item, SharedPreferences draftPrefs) {
        try {
            return isItemVisibleInternal(item, draftPrefs);
        } catch (Throwable t) {
            // A visibility probe (sensor/GPU/permission query) must NEVER crash
            // the whole settings page. If it fails, fail safe by showing the
            // item (the row itself is independently guarded when bound).
            android.util.Log.w("CSSettings", "isItemVisible failed for " + item.key, t);
            return true;
        }
    }

    private boolean isItemVisibleInternal(SettingItem item, SharedPreferences draftPrefs) {
        if ("notification_permission_request".equals(item.key)) {
            Activity activity = getActivity();
            return !(activity instanceof LauncherActivity)
                    || !((LauncherActivity) activity).checkForNotificationPermission();
        }
        if ("microphone_permission_request".equals(item.key)) {
            Activity activity = getActivity();
            return !(activity instanceof LauncherActivity)
                    || !((LauncherActivity) activity).checkForMicrophonePermission();
        }
        if ("ignoreNotch".equals(item.key)) {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && LauncherPreferences.PREF_NOTCH_SIZE > 0;
        }
        if ("force_vsync".equals(item.key)) {
            // Always reachable. egl_bridge reads FORCE_VSYNC and gl_bridge then clamps every swap
            // interval to 1 — a hard lock to the panel rate — and that applies to the GL4ES path
            // whether or not an alternate surface is in use. Hiding the row behind alternate_surface
            // could strand a stored "true" as an unreachable 60 FPS cap.
            return true;
        }
        if ("timeLongPressTrigger".equals(item.key)) {
            return !draftPrefs.getBoolean("disableGestures", false);
        }
        if ("gyroSensitivity".equals(item.key) || "gyroSampleRate".equals(item.key) ||
                "gyroSmoothing".equals(item.key) || "gyroInvertX".equals(item.key) || "gyroInvertY".equals(item.key)) {
            Context c = getContext();
            if (c == null) return false;
            boolean hasGyro;
            try {
                hasGyro = Tools.deviceSupportsGyro(c);
            } catch (Throwable t) {
                hasGyro = false; // no/unavailable sensor manager → just hide gyro sub-options
            }
            return hasGyro && draftPrefs.getBoolean("enableGyro", false);
        }
        if ("zinkPreferSystemDriver".equals(item.key)) {
            PackageManager pm = getContext().getPackageManager();
            boolean supportsTurnip = Tools.checkVulkanSupport(pm) && GLInfoUtils.getGlInfo().isAdreno();
            return supportsTurnip;
        }
        return true;
    }

    private void markDirty() {
        mIsDirty = true;
        try {
            SettingsSaveManager.commitChanges(requireContext());
            LauncherPreferences.loadPreferences(requireContext());
        } catch (Throwable ignored) {}
        updateSaveBar();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            SettingsSaveManager.commitChanges(requireContext());
            LauncherPreferences.loadPreferences(requireContext());
        } catch (Throwable ignored) {}
    }

    private void updateSaveBar() {
        // Settings v6: every change saves itself the moment it is made
        // (markDirty commits the draft), so there is no Save button and no
        // "unsaved changes" bar. The views stay in the layout for their ids.
        if (getView() == null) return;
        View bar = getView().findViewById(R.id.unsaved_changes_bar);
        if (bar != null) bar.setVisibility(View.GONE);
        if (mHeaderBadge != null) mHeaderBadge.setVisibility(View.GONE);
    }

    private void saveChanges() {
        Context context = requireContext();
        boolean prevEnglish = mDraftPrefs.getBoolean("force_english", false);
        boolean prevGradient = mDraftPrefs.getBoolean("enable_bg_gradient", false);
        boolean prevLandscape = mDraftPrefs.getBoolean("force_landscape", false);
        boolean prevNotch = LauncherPreferences.PREF_IGNORE_NOTCH;

        SharedPreferences mainPrefs = context.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = mainPrefs.edit();
        for (Map.Entry<String, ?> entry : mDraftPrefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Boolean) editor.putBoolean(key, (Boolean) val);
            else if (val instanceof Integer) editor.putInt(key, (Integer) val);
            else if (val instanceof Long) editor.putLong(key, (Long) val);
            else if (val instanceof Float) editor.putFloat(key, (Float) val);
            else if (val instanceof String) editor.putString(key, (String) val);
        }
        editor.commit();
        // Publish the cross-process settings so the game picks them up on its next start.
        try {
            net.kdt.pojavlaunch.prefs.SharedSettings.mirrorAll(context, mDraftPrefs.getAll());
        } catch (Throwable ignored) {}

        boolean verified = verifySavedSettings(context);
        if (verified) {
            LauncherPreferences.loadPreferences(context);
            mIsDirty = false;
            updateSaveBar();
            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show();

            boolean newEnglish = mDraftPrefs.getBoolean("force_english", false);
            boolean newGradient = mDraftPrefs.getBoolean("enable_bg_gradient", false);
            boolean newLandscape = mDraftPrefs.getBoolean("force_landscape", false);
            boolean newNotch = LauncherPreferences.PREF_IGNORE_NOTCH;

            if (prevEnglish != newEnglish || prevGradient != newGradient || prevNotch != newNotch) {
                requireActivity().recreate();
            }
            // The old "force landscape" toggle unlocked the orientation app-wide
            // when it was OFF. Every page (server hub, browse, skins) is laid out
            // for a wide screen only, so that unlock is what made pages open
            // vertical. The launcher stays landscape; the switch is retired.
        } else {
            Toast.makeText(context, "Failed to save settings", Toast.LENGTH_LONG).show();
        }
    }

    private boolean verifySavedSettings(Context context) {
        SharedPreferences mainPrefs = context.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
        SharedPreferences draftPrefs = SettingsSaveManager.getDraftPrefs(context);
        Map<String, ?> mainMap = mainPrefs.getAll();
        Map<String, ?> draftMap = draftPrefs.getAll();

        boolean allMatch = true;
        for (Map.Entry<String, ?> entry : draftMap.entrySet()) {
            String key = entry.getKey();
            Object draftVal = entry.getValue();
            if (key.startsWith("cat_") || "theme_picker".equals(key) || key.endsWith("_action") || "gamepad_remap_action".equals(key) || "gamepad_wipe_action".equals(key) || "clear_cache_files".equals(key) || "reset_all_settings".equals(key) || "active_profile_info".equals(key) || "install_jre".equals(key) || "fastclient_preference".equals(key)) {
                continue;
            }
            if (!mainMap.containsKey(key)) {
                Log.e("SettingsVerification", "Failed to save settings: key " + key + " is missing from disk storage!");
                allMatch = false;
                continue;
            }
            Object mainVal = mainMap.get(key);
            if (draftVal == null) {
                if (mainVal != null) {
                    Log.e("SettingsVerification", "Failed to save settings: key " + key + " mismatch (draft=null, disk=" + mainVal + ")");
                    allMatch = false;
                }
            } else if (!draftVal.equals(mainVal)) {
                Log.e("SettingsVerification", "Failed to save settings: key " + key + " mismatch (draft=" + draftVal + ", disk=" + mainVal + ")");
                allMatch = false;
            }
        }
        return allMatch;
    }

    private void openMultiRTDialog() {
        MultiRTConfigDialog dialogScreen = new MultiRTConfigDialog();
        dialogScreen.prepare(getContext(), mVmInstallLauncher);
        dialogScreen.show();
    }

    /**
     * Imports a user GIF into files/launch_gif.gif — the ONE file
     * LaunchStageView looks at. Stream-copied with a hard 50 MB cap and
     * validated by GIF magic bytes (GIF87a/GIF89a) because "image/*" pickers on
     * some OEMs don't filter properly. Any failure deletes the partial file —
     * the launch stage can never be handed a broken GIF.
     */
    /** ColorSelector dialog for the launch stage background color. */
    private void pickLaunchStageColor() {
        try {
            ViewGroup parent = requireActivity().findViewById(android.R.id.content);
            if (parent == null) return;
            net.kdt.pojavlaunch.colorselector.ColorSelector selector =
                    new net.kdt.pojavlaunch.colorselector.ColorSelector(requireContext(), parent, color -> {
                        LauncherPreferences.DEFAULT_PREF.edit()
                                .putInt("launch_stage_color", color | 0xFF000000).apply();
                        net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(),
                                getString(R.string.cs_launch_color_set), android.R.drawable.ic_menu_edit);
                    });
            int current = LauncherPreferences.DEFAULT_PREF.getInt("launch_stage_color", 0xFF000000);
            selector.show(true, current == 0 ? 0xFF000000 : current);
        } catch (Throwable t) {
            Tools.showError(requireContext(), t);
        }
    }

    private void copyGifToStageFile(@NonNull Uri uri) {
        final long MAX_GIF_BYTES = 100L * 1024L * 1024L; // 100 MB max
        final File target1 = new File(requireContext().getFilesDir(), "launch_gif.gif");
        final File target2 = new File(net.kdt.pojavlaunch.Tools.DIR_DATA + "/launch_gif.gif");
        try {
            target1.getParentFile().mkdirs();
            target2.getParentFile().mkdirs();
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(target1)) {
                if (in == null) throw new Exception("Cannot open URI");
                byte[] buf = new byte[8192];
                int len; long total = 0;
                while ((len = in.read(buf)) != -1) {
                    total += len;
                    if (total > MAX_GIF_BYTES) throw new Exception("GIF too large");
                    out.write(buf, 0, len);
                }
            }
            try {
                org.apache.commons.io.FileUtils.copyFile(target1, target2);
            } catch (Throwable ignored) {}

            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString("launch_screen_style", "gif").apply();
            try { SettingsSaveManager.commitChanges(requireContext()); } catch (Throwable ignored) {}

            net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(),
                    getString(R.string.cs_launch_gif_set_ok), android.R.drawable.ic_menu_gallery);
        } catch (Exception e) {
            if (target1.exists()) target1.delete();
            if (target2.exists()) target2.delete();
            String msg = "GIF too large".equals(e.getMessage())
                    ? getString(R.string.cs_launch_gif_too_big)
                    : getString(R.string.cs_launch_gif_invalid);
            net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(), msg);
        }
    }

    private static int safeReadFully(InputStream in, byte[] dst, int want) throws java.io.IOException {
        int off = 0;
        while (off < want) {
            int n = in.read(dst, off, want - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    /** Removes the imported launch GIF; the next launch is pure black again. */
    private void removeLaunchGif() {
        File target1 = new File(requireContext().getFilesDir(), "launch_gif.gif");
        File target2 = new File(net.kdt.pojavlaunch.Tools.DIR_DATA + "/launch_gif.gif");
        boolean was = target1.exists() || target2.exists();
        if (target1.exists()) target1.delete();
        if (target2.exists()) target2.delete();
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString("launch_screen_style", "black").apply();
        try { SettingsSaveManager.commitChanges(requireContext()); } catch (Throwable ignored) {}
        Toast.makeText(requireContext(),
                was ? R.string.cs_launch_gif_removed : R.string.cs_launch_gif_none_set,
                Toast.LENGTH_SHORT).show();
    }

    private void copyImageToBgFile(@NonNull Uri uri) {
        File bgFile = new File(RightPaneHomeFragment.CUSTOM_BG_PATH);
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(bgFile)) {
            if (in == null) throw new Exception("Cannot open URI");
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            notifyHomeFragmentBgChanged();
            net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(),
                    getString(R.string.preference_custom_bg_set_success), android.R.drawable.ic_menu_gallery);
        } catch (Exception e) {
            if (bgFile.exists()) bgFile.delete();
            net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(),
                    getString(R.string.preference_custom_bg_error));
        }
    }

    private void showPresetDialog() {
        ThemeManager.Preset[] presets = ThemeManager.PRESETS;
        String[] labels = new String[presets.length + 1];
        for (int i = 0; i < presets.length; i++) labels[i] = presets[i].name;
        labels[presets.length] = getString(R.string.preference_colour_reset);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.preference_colour_presets_title)
                .setItems(labels, (dialog, which) -> {
                    if (which < presets.length) {
                        ThemeManager.applyPreset(presets[which]);
                    } else {
                        ThemeManager.resetToDefault();
                    }
                    requireActivity().recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void notifyHomeFragmentBgChanged() {
        MainMenuFragment mmf = (MainMenuFragment) requireActivity()
                .getSupportFragmentManager()
                .findFragmentByTag("ROOT");
        if (mmf == null) return;
        RightPaneHomeFragment home = (RightPaneHomeFragment) mmf
                .getChildFragmentManager()
                .findFragmentByTag(RightPaneHomeFragment.TAG);
        if (home != null) home.reloadBackground();
    }

    private void clearCacheLocal(Context context) {
        try {
            File dir = context.getCacheDir();
            deleteRecursive(dir);
        } catch (Exception e) {
            Log.e("LauncherPreferenceFragment", "Failed to clear cache", e);
        }
    }

    private void deleteRecursive(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    /** (category, item) pair for search / pins lookup. */
    private static final class SettingItemHolder {
        final String category;
        final SettingItem item;
        SettingItemHolder(String category, SettingItem item) {
            this.category = category;
            this.item = item;
        }
    }

    // ── RecyclerView Adapter & ViewHolder ─────────────────────────────────────

    private class SettingsAdapter extends RecyclerView.Adapter<SettingsAdapter.CategoryViewHolder> {

        private final List<SettingCategory> mCategories;
        private final SharedPreferences mPrefs;
        private final SharedPreferences.OnSharedPreferenceChangeListener mListener;

        public SettingsAdapter(List<SettingCategory> categories, SharedPreferences prefs) {
            this.mCategories = categories;
            this.mPrefs = prefs;
            this.mListener = (prefsChanged, key) -> {
                if (key == null) return;
                Context c = LauncherPreferenceFragment.this.getContext();
                if (c != null) net.kdt.pojavlaunch.prefs.SettingsMetaStore.recordChange(c, key);
            };
            this.mPrefs.registerOnSharedPreferenceChangeListener(mListener);
        }

        public void cleanup() {
            mPrefs.unregisterOnSharedPreferenceChangeListener(mListener);
        }

        /** Live search re-render: swap the grouped categories atomically. */
        public void replaceCategories(@NonNull List<SettingCategory> next) {
            mCategories.clear();
            mCategories.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_settings_category, parent, false);
            CategoryViewHolder vh = new CategoryViewHolder(v);
            // Smooth expand/collapse when rows are added/removed (search filter,
            // Advanced reveal). Respects the global "Animations Off" preference.
            if (vh.container != null
                    && net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) {
                android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
                lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
                lt.setDuration(220);
                vh.container.setLayoutTransition(lt);
            }
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
            SettingCategory cat = mCategories.get(position);
            holder.categoryTitle.setText(cat.title);
            holder.container.removeAllViews();

            boolean hasVisibleItems = false;
            int visibleCount = 0;
            LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());

            if (mCategoryName == null && "Settings Categories".equals(cat.title)) {
                bindDashboardHolder(holder, cat, inflater);
                return;
            }

            for (SettingItem item : cat.items) {
              try {
                if (!isItemVisible(item, mPrefs)) {
                    continue;
                }
                hasVisibleItems = true;
                visibleCount++;

                View itemView;
                if (item.type == SettingItem.TYPE_THEME_SELECTOR) {
                    itemView = inflater.inflate(R.layout.item_setting_theme_selector, holder.container, false);
                    populateThemeSelector(itemView, inflater);

                } else if (item.type == SettingItem.TYPE_CUSTOM_FASTCLIENT) {
                    itemView = inflater.inflate(R.layout.fragment_settings_fastclient, holder.container, false);
                    FastClientHelper.setup(itemView, holder.itemView.getContext(), getChildFragmentManager());
                    holder.container.addView(itemView);
                    continue;

                } else if (item.type == SettingItem.TYPE_PERFORMANCE_PANEL) {
                    itemView = inflater.inflate(R.layout.item_setting_performance, holder.container, false);
                    bindPerformancePanel(itemView);
                    holder.container.addView(itemView);
                    continue;

                } else if (item.type == SettingItem.TYPE_PRESET_PANEL) {
                    itemView = inflater.inflate(R.layout.item_setting_presets, holder.container, false);
                    bindSettingIcon(itemView, item);
                    bindPresetPanel(itemView);
                    holder.container.addView(itemView);
                    continue;

                } else if (item.type == SettingItem.TYPE_CATEGORY_LINK) {
                    itemView = inflater.inflate(R.layout.item_setting_button, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary);

                    itemView.setOnClickListener(v -> {
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(55)
                                .withEndAction(() -> {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(430)
                                            .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.JELLY).start();
                                    Bundle bundle = new Bundle();
                                    bundle.putString("category", item.categoryLinkTarget);
                                    Tools.swapFragment(
                                            requireActivity(),
                                            LauncherPreferenceFragment.class,
                                            "SETTINGS_" + item.categoryLinkTarget,
                                            bundle,
                                            R.anim.slide_in_right,
                                            R.anim.slide_out_left,
                                            R.anim.slide_in_left,
                                            R.anim.slide_out_right
                                    );
                                }).start();
                    });

                } else if (item.type == SettingItem.TYPE_SWITCH) {
                    itemView = inflater.inflate(R.layout.item_setting_toggle, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    CustomToggleView toggle = itemView.findViewById(R.id.setting_toggle);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary);

                    boolean isChecked = mPrefs.getBoolean(item.key, (Boolean) item.defaultValue);
                    toggle.setChecked(isChecked, false);

                    itemView.setOnClickListener(v -> toggle.toggle());

                    toggle.setOnCheckedChangeListener((view1, checkedVal) -> {
                        if ("notification_permission_request".equals(item.key)) {
                            Activity act = getActivity();
                            if (act instanceof LauncherActivity) {
                                LauncherActivity la = (LauncherActivity) act;
                                if (checkedVal) {
                                    la.askForNotificationPermission(() -> {
                                        toggle.setChecked(la.checkForNotificationPermission(), false);
                                        if (la.checkForNotificationPermission()) {
                                            mRecyclerView.post(this::notifyDataSetChanged);
                                        }
                                    });
                                }
                            }
                        } else if ("microphone_permission_request".equals(item.key)) {
                            Activity act = getActivity();
                            if (act instanceof LauncherActivity) {
                                LauncherActivity la = (LauncherActivity) act;
                                if (checkedVal) {
                                    la.askForMicrophonePermission(() -> {
                                        toggle.setChecked(la.checkForMicrophonePermission(), false);
                                        if (la.checkForMicrophonePermission()) {
                                            mRecyclerView.post(this::notifyDataSetChanged);
                                        }
                                    });
                                }
                            }
                        } else {
                            mPrefs.edit().putBoolean(item.key, checkedVal).apply();
                            net.kdt.pojavlaunch.prefs.SharedSettings.mirror(getContext(), item.key, checkedVal);
                            markDirty();
                        }

                        if (item.key.equals("enableGyro") || item.key.equals("disableGestures")
                                || item.key.equals("alternate_surface") || item.key.equals("mg_renderer_multidrawCompute")) {
                            mRecyclerView.post(() -> notifyDataSetChanged());
                        }
                    });

                } else if (item.type == SettingItem.TYPE_SLIDER) {
                    itemView = inflater.inflate(R.layout.item_setting_slider, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    TextView tvVal = itemView.findViewById(R.id.setting_value_text);
                    SeekBar seekBar = itemView.findViewById(R.id.setting_seekbar);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary);

                    int curVal = mPrefs.getInt(item.key, (Integer) item.defaultValue);
                    tvVal.setText(curVal + item.unitSuffix);

                    seekBar.setMax((item.maxVal - item.minVal) / item.stepVal);
                    seekBar.setProgress((curVal - item.minVal) / item.stepVal);

                    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                            int calculatedVal = item.minVal + progress * item.stepVal;
                            tvVal.setText(calculatedVal + item.unitSuffix);
                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar sb) {}

                        @Override
                        public void onStopTrackingTouch(SeekBar sb) {
                            int finalVal = item.minVal + sb.getProgress() * item.stepVal;
                            mPrefs.edit().putInt(item.key, finalVal).apply();
                            net.kdt.pojavlaunch.prefs.SharedSettings.mirror(getContext(), item.key, finalVal);
                            markDirty();
                        }
                    });

                    // O9 (CS Launcher interaction): tapping the value chip (e.g.
                    // "4096 MB") opens a dialog to type an exact number, clamped
                    // to the slider's min/max — no more dragging through steps.
                    tvVal.setClickable(true);
                    tvVal.setFocusable(true);
                    tvVal.setOnClickListener(v -> {
                        final EditText editText = new EditText(requireContext());
                        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                        editText.setText(String.valueOf(mPrefs.getInt(item.key, (Integer) item.defaultValue)));
                        editText.setSelection(editText.getText().length());
                        new AlertDialog.Builder(requireContext())
                                .setTitle(item.title)
                                .setView(editText)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    String input = editText.getText().toString().trim();
                                    if (input.isEmpty()) return;
                                    int newValue;
                                    try {
                                        newValue = Integer.parseInt(input);
                                    } catch (NumberFormatException e) {
                                        return;
                                    }
                                    if (newValue < item.minVal) newValue = item.minVal;
                                    if (newValue > item.maxVal) newValue = item.maxVal;
                                    mPrefs.edit().putInt(item.key, newValue).apply();
                                    net.kdt.pojavlaunch.prefs.SharedSettings.mirror(getContext(), item.key, newValue);
                                    tvVal.setText(newValue + item.unitSuffix);
                                    seekBar.setProgress((newValue - item.minVal) / item.stepVal);
                                    markDirty();
                                })
                                .show();
                    });

                } else if (item.type == SettingItem.TYPE_DROPDOWN) {
                    itemView = inflater.inflate(R.layout.item_setting_dropdown, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    TextView tvSpinner = itemView.findViewById(R.id.setting_spinner_text);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary);

                    String curVal = mPrefs.getString(item.key, (String) item.defaultValue);
                    int selIndex = 0;
                    if (item.dropdownValues != null) {
                        for (int i = 0; i < item.dropdownValues.length; i++) {
                            if (Objects.equals(item.dropdownValues[i], curVal)) {
                                selIndex = i;
                                break;
                            }
                        }
                    }
                    if (item.dropdownEntries != null && selIndex < item.dropdownEntries.length) {
                        tvSpinner.setText(item.dropdownEntries[selIndex]);
                    }

                    final int selectedNow = selIndex;
                    itemView.setOnClickListener(v -> {
                        if (item.dropdownEntries == null) return;
                        net.kdt.pojavlaunch.ui.CsPickerSheet sheet =
                                new net.kdt.pojavlaunch.ui.CsPickerSheet(holder.itemView.getContext())
                                        .glyph("\u2630").kicker("CHOOSE").title(item.title)
                                        .subtitle(item.summary);
                        for (int i = 0; i < item.dropdownEntries.length; i++) {
                            sheet.add(String.valueOf(i + 1), item.dropdownEntries[i], null);
                        }
                        int cur = 0;
                        String nowVal = mPrefs.getString(item.key, (String) item.defaultValue);
                        if (item.dropdownValues != null) {
                            for (int i = 0; i < item.dropdownValues.length; i++) {
                                if (Objects.equals(item.dropdownValues[i], nowVal)) { cur = i; break; }
                            }
                        }
                        sheet.selected(cur >= 0 ? cur : selectedNow)
                                .onPick(which -> {
                                    String chosenVal = item.dropdownValues[which];
                                    Runnable apply = () -> {
                                        mPrefs.edit().putString(item.key, chosenVal).apply();
                                        tvSpinner.setText(item.dropdownEntries[which]);
                                        markDirty();
                                        if (PerformanceMode.PREF_KEY.equals(item.key)) {
                                            // The mode summary and the two policy cards are rendered
                                            // from the resolved policy, so the list is rebuilt from the
                                            // draft settings — the refresh the search filter already
                                            // uses — rather than patching row text and risking stale copy.
                                            holder.itemView.post(() -> {
                                                try {
                                                    mAdapter.replaceCategories(buildCurrentCategories());
                                                } catch (Throwable ignored) {
                                                }
                                            });
                                        }
                                    };
                                    if (PerformanceMode.PREF_KEY.equals(item.key)
                                            && PerformanceMode.MAXIMUM.key.equals(chosenVal)) {
                                        confirmMaximumMode(holder.itemView.getContext(), apply);
                                    } else {
                                        apply.run();
                                    }
                                })
                                .show();
                    });

                } else if (item.type == SettingItem.TYPE_INPUT) {
                    itemView = inflater.inflate(R.layout.item_setting_dropdown, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    TextView tvSpinner = itemView.findViewById(R.id.setting_spinner_text);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary);

                    String curVal = mPrefs.getString(item.key, (String) item.defaultValue);
                    tvSpinner.setText(curVal != null && !curVal.isEmpty() ? curVal : "Default");
                    View inputChevron = itemView.findViewById(R.id.setting_chevron);
                    if (inputChevron instanceof ImageView) ((ImageView) inputChevron).setImageResource(R.drawable.ic_chevron_right);

                    itemView.setOnClickListener(v -> {
                        Context ctx = holder.itemView.getContext();
                        EditText et = new EditText(ctx);
                        et.setInputType(InputType.TYPE_CLASS_TEXT);
                        et.setText(mPrefs.getString(item.key, (String) item.defaultValue));

                        new AlertDialog.Builder(ctx)
                                .setTitle(item.title)
                                .setView(et)
                                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                                    String input = et.getText().toString();
                                    mPrefs.edit().putString(item.key, input).apply();
                                    tvSpinner.setText(input.isEmpty() ? "Default" : input);
                                    markDirty();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    });

                } else if (item.type == SettingItem.TYPE_ACTION) {
                    itemView = inflater.inflate(R.layout.item_setting_button, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary);

                    itemView.setOnClickListener(v -> {
                        v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(55)
                                .withEndAction(() -> {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                                    if (item.action != null) {
                                        item.action.run();
                                    }
                                }).start();
                    });

                } else if (item.type == SettingItem.TYPE_INFO) {
                    itemView = inflater.inflate(R.layout.item_setting_dropdown, holder.container, false);
                    TextView tvTitle = itemView.findViewById(R.id.setting_title);
                    TextView tvSummary = itemView.findViewById(R.id.setting_summary);
                    TextView tvSpinner = itemView.findViewById(R.id.setting_spinner_text);
                    bindSettingIcon(itemView, item);

                    tvTitle.setText(item.title);
                    tvSummary.setText(item.summary != null && !item.summary.isEmpty()
                            ? item.summary : String.valueOf(item.defaultValue));
                    if (item.summary != null && item.defaultValue != null) {
                        tvSpinner.setText(String.valueOf(item.defaultValue));
                    } else {
                        tvSpinner.setVisibility(View.GONE);
                    }
                    itemView.setClickable(false);
                    itemView.setFocusable(false);
                    View infoChevron = itemView.findViewById(R.id.setting_chevron);
                    if (infoChevron != null) infoChevron.setVisibility(View.GONE);
                } else {
                    itemView = new View(holder.itemView.getContext());
                }

                itemView.setTag(item.key);
                // One press feel for every tappable settings row (toggles, actions,
                // dropdowns, inputs) — RecyclerView rows are skipped by the
                // screen-wide attach pass, so rows are wired right here. Rows
                // without a click listener (sliders' bare views) stay untouched.
                if (itemView.hasOnClickListeners()) {
                    net.kdt.pojavlaunch.UiMotion.pressFeedback(itemView);
                }
                bindFavoriteStar(itemView, item);
                holder.container.addView(itemView);
              } catch (Throwable rowError) {
                // A single malformed/unsupported row must never crash the whole
                // settings page (this was the Controls-page silent crash). Log
                // it and skip only that row; the rest of the page renders fine.
                android.util.Log.e("CSSettings",
                        "Failed to build settings row: "
                                + (item != null ? item.key : "null"), rowError);
              }
            }

            if (holder.container.getChildCount() > 0) {
                // Ensure the last real row uses the rounded bottom background
                View last = holder.container.getChildAt(holder.container.getChildCount() - 1);
                if (last.getTag() instanceof String) last.setBackgroundResource(R.drawable.ss_row_last);
            }

            if (holder.categoryCount != null) holder.categoryCount.setVisibility(View.GONE);

            if (!hasVisibleItems) {
                holder.itemView.setVisibility(View.GONE);
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
                params.height = 0;
                params.topMargin = 0;
                params.bottomMargin = 0;
                holder.itemView.setLayoutParams(params);
            } else {
                holder.itemView.setVisibility(View.VISIBLE);
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.topMargin = (int) (8 * holder.itemView.getResources().getDisplayMetrics().density);
                params.bottomMargin = (int) (8 * holder.itemView.getResources().getDisplayMetrics().density);
                holder.itemView.setLayoutParams(params);
                // Premium entrance: the whole category card fades + rises in,
                // staggered by its position, and its rows cascade after it.
                animateCardEntrance(holder, position);
            }
        }

        /** One-shot fade/rise for a category card + a springy cascade for its rows. */
        private void animateCardEntrance(@NonNull CategoryViewHolder holder, int position) {
            // Respect the global "Animations Off" preference.
            if (!net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) return;
            View card = holder.itemView;
            float dp = card.getResources().getDisplayMetrics().density;
            final android.view.animation.Interpolator SPRING =
                    net.kdt.pojavlaunch.utils.animation.MotionCurves.SPRING;
            card.setAlpha(0f);
            card.setTranslationY(22f * dp);
            card.setScaleX(0.98f);
            card.setScaleY(0.98f);
            long delay = Math.min(position, 6) * 55L;
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(delay).setDuration(420)
                    .setInterpolator(SPRING)
                    .start();
            // Cascade the rows inside this card: alternating sides + a soft
            // rise/scale gives a lively, mobile-app style wave-in.
            LinearLayout c = holder.container;
            for (int i = 0; i < c.getChildCount(); i++) {
                View row = c.getChildAt(i);
                row.setAlpha(0f);
                row.setTranslationY(14f * dp);
                row.animate().alpha(1f).translationY(0f)
                        .setStartDelay(delay + 70L + Math.min(i, 10) * 40L).setDuration(380)
                        .setInterpolator(net.kdt.pojavlaunch.Anime.OUT_EXPO)
                        .start();
            }
        }


        private void bindDashboardHolder(@NonNull CategoryViewHolder holder,
                                         @NonNull SettingCategory cat,
                                         @NonNull LayoutInflater inflater) {
            holder.categoryTitle.setText("Sections");
            holder.categoryCount.setVisibility(View.GONE);
            holder.container.removeAllViews();

            Context gridCtx = holder.itemView.getContext();
            LinearLayout categoryGrid = new LinearLayout(gridCtx);
            categoryGrid.setOrientation(LinearLayout.VERTICAL);
            try {
                categoryGrid.setDividerDrawable(androidx.core.content.ContextCompat.getDrawable(gridCtx, R.drawable.ss_divider));
                categoryGrid.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            } catch (Throwable ignored) {}
            categoryGrid.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            final int gap = 0;
            LinearLayout currentRow = null;
            int cardIndex = 0;
            final int cols = 1; // Phase 10: one tall row per section, full width
            for (SettingItem item : cat.items) {
                if (cardIndex % cols == 0) {
                    currentRow = new LinearLayout(gridCtx);
                    currentRow.setOrientation(LinearLayout.HORIZONTAL);
                    categoryGrid.addView(currentRow);
                }
                View card = inflater.inflate(R.layout.item_setting_category_card, currentRow, false);
                ImageView icon = card.findViewById(R.id.category_card_icon);
                TextView title = card.findViewById(R.id.category_card_title);
                TextView summary = card.findViewById(R.id.category_card_summary);
                TextView badge = card.findViewById(R.id.category_card_badge);
                if (icon != null) icon.setImageResource(resolveCategoryIconByName(item.categoryLinkTarget));
                if (title != null) title.setText(item.title);
                if (summary != null) summary.setText(item.summary);
                if (badge != null) badge.setVisibility(View.GONE);
                if (cardIndex == cat.items.size() - 1) card.setBackgroundResource(R.drawable.ss_row_last);
                card.setOnClickListener(v -> {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(50)
                            .withEndAction(() -> {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(430)
                                        .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.JELLY).start();
                                openCategoryPage(item.categoryLinkTarget);
                            }).start();
                });
                LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                currentRow.addView(card, slot);
                if (net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) {
                    net.kdt.pojavlaunch.Anime.in(card, net.kdt.pojavlaunch.Anime.Fx.FADE_UP,
                            60L + 45L * cardIndex, 460L, net.kdt.pojavlaunch.Anime.OUT_EXPO);
                }
                cardIndex++;
            }
            holder.container.addView(categoryGrid);
            holder.itemView.setVisibility(View.VISIBLE);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            if (params != null) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.topMargin = dp(4);
                params.bottomMargin = dp(7);
                holder.itemView.setLayoutParams(params);
            }
        }

        // ══ Performance: live RAM stats — 1 Hz, attach-gated (zero leaks),
        //    PSS-accurate (launcher / :game process / available), with smooth
        //    animated transitions between readings (item-2 + item-7) ══
        private final java.util.WeakHashMap<View, android.animation.ValueAnimator> mPerfAnims =
                new java.util.WeakHashMap<>();

        private void bindPerformancePanel(@NonNull View panel) {
            updateMemoryStats(panel);
            final Runnable tick = new Runnable() {
                @Override public void run() {
                    if (!panel.isAttachedToWindow()) return;
                    updateMemoryStats(panel);
                    Tools.MAIN_HANDLER.postDelayed(this, 1000);
                }
            };
            panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(@NonNull View v) {
                    Tools.MAIN_HANDLER.postDelayed(tick, 1000);
                }
                @Override public void onViewDetachedFromWindow(@NonNull View v) {
                    Tools.MAIN_HANDLER.removeCallbacks(tick);
                }
            });
        }

        private void updateMemoryStats(@NonNull View panel) {
            long launcherMb = -1, gameMb = -1, availMb = 0, totalMb = 0;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        panel.getContext().getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    // Real launcher RAM — this process' total PSS (native+dalvik+shared).
                    android.os.Debug.MemoryInfo[] mine =
                            am.getProcessMemoryInfo(new int[]{android.os.Process.myPid()});
                    if (mine != null && mine.length > 0 && mine[0] != null)
                        launcherMb = mine[0].getTotalPss() / 1024L;
                    // Real game RAM — the separate ":game" process, when alive
                    // (getRunningAppProcesses always reports our own app's processes).
                    int gamePid = -1;
                    java.util.List<android.app.ActivityManager.RunningAppProcessInfo> procs =
                            am.getRunningAppProcesses();
                    if (procs != null) {
                        for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                            if (p != null && p.processName != null && p.processName.endsWith(":game")) {
                                gamePid = p.pid;
                                break;
                            }
                        }
                    }
                    if (gamePid > 0) {
                        android.os.Debug.MemoryInfo[] g = am.getProcessMemoryInfo(new int[]{gamePid});
                        if (g != null && g.length > 0 && g[0] != null)
                            gameMb = g[0].getTotalPss() / 1024L;
                    }
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    availMb = mi.availMem / 1048576L;
                    totalMb = mi.totalMem / 1048576L;
                }
            } catch (Throwable ignored) {}
            TextView v;
            if ((v = panel.findViewById(R.id.perf_native_value)) != null)
                animatePerfValue(v, launcherMb);                    // LAUNCHER chip
            if ((v = panel.findViewById(R.id.perf_java_value)) != null)
                animatePerfValue(v, gameMb);                        // GAME chip ("Idle" when not running)
            if ((v = panel.findViewById(R.id.perf_device_value)) != null)
                animatePerfValue(v, Math.max(0, availMb));          // AVAILABLE chip
            View fill = panel.findViewById(R.id.perf_meter_fill);
            View spacer = panel.findViewById(R.id.perf_meter_spacer);
            if (fill != null && spacer != null && totalMb > 0) {
                int used = (int) Math.min(100, Math.max(0, 100 - (availMb * 100L) / totalMb));
                animatePerfMeter(fill, spacer, used);
            }
        }

        /** Smooth value count between readings — no sudden text jumps (item-7). */
        private void animatePerfValue(@NonNull TextView v, long newMb) {
            if (newMb < 0) { // game process not running
                android.animation.ValueAnimator old = mPerfAnims.remove(v);
                if (old != null) old.cancel();
                v.setTag(-1);
                v.setText("Idle");
                return;
            }
            Object last = v.getTag();
            if (!(last instanceof Integer) || (Integer) last < 0) {
                v.setTag((int) newMb);
                v.setText(newMb + " MB");
                return;
            }
            final int from = (Integer) last;
            v.setTag((int) newMb);
            android.animation.ValueAnimator old = mPerfAnims.remove(v);
            if (old != null) old.cancel();
            if (from == (int) newMb) { v.setText(newMb + " MB"); return; }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(from, (int) newMb);
            a.setDuration(420);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(anim -> v.setText(anim.getAnimatedValue() + " MB"));
            mPerfAnims.put(v, a);
            a.start();
        }

        /** Meter fill glides between used-% readings instead of snapping. */
        private void animatePerfMeter(@NonNull final View fill, @NonNull final View spacer, int used) {
            LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) fill.getLayoutParams();
            final float from = fp.weight;
            if (from == used) return;
            android.animation.ValueAnimator old = mPerfAnims.remove(fill);
            if (old != null) old.cancel();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, used);
            a.setDuration(420);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(anim -> {
                float w = (Float) anim.getAnimatedValue();
                LinearLayout.LayoutParams p1 = (LinearLayout.LayoutParams) fill.getLayoutParams();
                p1.weight = w;
                fill.setLayoutParams(p1);
                LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) spacer.getLayoutParams();
                p2.weight = 100f - w;
                spacer.setLayoutParams(p2);
            });
            mPerfAnims.put(fill, a);
            a.start();
        }

        // ══ 1-Click Quick Device Optimizer (4GB, 6GB, 8GB+ RAM Phone) ══
        private void bindPresetPanel(@NonNull View panel) {
            final android.content.Context ctx = panel.getContext();
            int totalMb = 6144;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    totalMb = (int) (mi.totalMem / 1048576L);
                }
            } catch (Throwable ignored) {}

            final int highRam = Math.max(4096,
                    Math.min(6144, (int) ((totalMb * 0.55f) / 256f) * 256));

            TextView pointsLow = panel.findViewById(R.id.preset_low_points);
            if (pointsLow != null) {
                pointsLow.setText("1536 MB RAM • 70% Resolution • GL4ES • 50% FSR • Performance Mode");
            }
            TextView pointsMid = panel.findViewById(R.id.preset_mid_points);
            if (pointsMid != null) {
                pointsMid.setText("3072 MB RAM • 85% Resolution • MobileGlues • 25% FSR • Balanced");
            }
            TextView pointsHigh = panel.findViewById(R.id.preset_high_points);
            if (pointsHigh != null) {
                pointsHigh.setText(Tools.sanitizeRamAllocation(ctx, highRam)
                        + " MB RAM • 100% Native Res • Zink/MobileGlues • Maximum Mode");
            }

            View lowApply = panel.findViewById(R.id.preset_low_apply);
            View midApply = panel.findViewById(R.id.preset_mid_apply);
            View highApply = panel.findViewById(R.id.preset_high_apply);
            if (lowApply != null) lowApply.setOnClickListener(v -> applyPreset(panel, 0, highRam));
            if (midApply != null) midApply.setOnClickListener(v -> applyPreset(panel, 1, highRam));
            if (highApply != null) highApply.setOnClickListener(v -> applyPreset(panel, 2, highRam));
            refreshPresetMarker(panel);
        }

        private void applyPreset(@NonNull View panel, int presetType, int highRam) {
            android.content.Context ctx = panel.getContext();
            int maxRam = Tools.getMaximumRamAllocation(ctx);
            PerformanceMode mode;
            int requestedRam;
            int resolution;
            boolean vsync;
            String fsr;
            boolean altSurface;
            String presetName;
            String toastMsg;

            if (presetType == 0) { // 4GB Phone
                mode = PerformanceMode.PERFORMANCE;
                requestedRam = Math.min(1536, maxRam);
                resolution = 70;
                vsync = false;
                fsr = "2"; // 50% FSR
                altSurface = false;
                presetName = "low";
                toastMsg = "4GB Phone Preset Applied: " + requestedRam + " MB RAM, 70% Res, 50% FSR, Performance Mode";
            } else if (presetType == 1) { // 6GB Phone
                mode = PerformanceMode.NORMAL;
                requestedRam = Math.min(3072, maxRam);
                resolution = 85;
                vsync = true;
                fsr = "1"; // 25% FSR
                altSurface = true;
                presetName = "med";
                toastMsg = "6GB Phone Preset Applied: " + requestedRam + " MB RAM, 85% Res, 25% FSR, Balanced Mode";
            } else { // 8GB+ Phone
                mode = PerformanceMode.MAXIMUM;
                requestedRam = Math.max(4096, Math.min(highRam, maxRam));
                resolution = 100;
                vsync = true;
                fsr = "0"; // Native / Disabled
                altSurface = true;
                presetName = "high";
                toastMsg = "8GB+ Phone Preset Applied: " + requestedRam + " MB RAM, 100% Native Res, Native Quality, Maximum Mode";
            }

            int ram = Tools.sanitizeRamAllocation(ctx, requestedRam);

            try {
                android.content.SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                        ? LauncherPreferences.DEFAULT_PREF
                        : ctx.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor e = p.edit();
                e.putInt("allocation", ram);
                e.putInt("resolutionRatio", resolution);
                e.putBoolean("force_vsync", vsync);
                e.putString("mg_renderer_setting_fsr", fsr);
                e.putString(PerformanceMode.PREF_KEY, mode.key);
                e.putBoolean("alternate_surface", altSurface);
                e.putString("perfPreset", presetName);
                e.commit();

                try {
                    android.content.SharedPreferences.Editor d =
                            SettingsSaveManager.getDraftPrefs(ctx).edit();
                    d.putInt("allocation", ram);
                    d.putInt("resolutionRatio", resolution);
                    d.putBoolean("force_vsync", vsync);
                    d.putString("mg_renderer_setting_fsr", fsr);
                    d.putString(PerformanceMode.PREF_KEY, mode.key);
                    d.putBoolean("alternate_surface", altSurface);
                    d.putString("perfPreset", presetName);
                    d.apply();
                } catch (Throwable ignored) {}

                LauncherPreferences.loadPreferences(ctx.getApplicationContext());
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(ctx, "allocation", ram);
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(ctx, "resolutionRatio", resolution);
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(ctx, "force_vsync", vsync);
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(ctx, "mg_renderer_setting_fsr", fsr);
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(ctx, PerformanceMode.PREF_KEY, mode.key);
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(ctx, "alternate_surface", altSurface);
            } catch (Throwable t) {
                android.widget.Toast.makeText(ctx, "Preset failed to apply", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            int pillId = R.id.preset_low_apply;
            if (presetType == 1) pillId = R.id.preset_mid_apply;
            else if (presetType == 2) pillId = R.id.preset_high_apply;
            View pill = panel.findViewById(pillId);
            if (pill instanceof TextView) {
                final TextView tv = (TextView) pill;
                tv.setText("APPLIED ✓");
                Tools.MAIN_HANDLER.postDelayed(() -> tv.setText("APPLY"), 1200);
            }
            refreshPresetMarker(panel);
            notifyDataSetChanged();
            android.widget.Toast.makeText(ctx, toastMsg, android.widget.Toast.LENGTH_LONG).show();
        }

        private void refreshPresetMarker(@NonNull View panel) {
            TextView state = panel.findViewById(R.id.preset_state);
            if (state == null) return;
            String cur = null;
            try {
                android.content.SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                        ? LauncherPreferences.DEFAULT_PREF
                        : panel.getContext().getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
                cur = p.getString("perfPreset", null);
            } catch (Throwable ignored) {}
            if ("low".equals(cur)) { state.setText("4GB ACTIVE"); state.setVisibility(View.VISIBLE); }
            else if ("med".equals(cur)) { state.setText("6GB ACTIVE"); state.setVisibility(View.VISIBLE); }
            else if ("high".equals(cur)) { state.setText("8GB+ ACTIVE"); state.setVisibility(View.VISIBLE); }
            else state.setVisibility(View.GONE);
        }

        private void populateThemeSelector(@NonNull View itemView, @NonNull LayoutInflater inflater) {
            LinearLayout swatchesContainer = itemView.findViewById(R.id.theme_swatches_container);
            if (swatchesContainer == null) return;
            swatchesContainer.removeAllViews();
            ThemeManager.Preset[] presets = ThemeManager.PRESETS;
            String[] colors = new String[]{"#D0D0D0", "#7C8AA0", "#3E8E6E", "#B45454", "#8E6AB8", "#4E9AA8"};

            for (int i = 0; i < presets.length; i++) {
                ThemeManager.Preset p = presets[i];
                View swatchView = inflater.inflate(R.layout.item_theme_color_swatch, swatchesContainer, false);
                View colorCircle = swatchView.findViewById(R.id.swatch_color_circle);
                TextView nameTv = swatchView.findViewById(R.id.swatch_name);

                if (nameTv != null) nameTv.setText(p.name.split(" ")[0]);
                if (colorCircle != null) {
                    int hexColor = Color.parseColor(colors[i % colors.length]);
                    colorCircle.setBackgroundColor(hexColor);
                }

                final ThemeManager.Preset targetPreset = p;
                swatchView.setOnClickListener(v -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80)
                            .withEndAction(() -> {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                                ThemeManager.applyPreset(targetPreset);
                                Toast.makeText(requireContext(), "Applied " + targetPreset.name, Toast.LENGTH_SHORT).show();
                                requireActivity().recreate();
                            }).start();
                });

                swatchesContainer.addView(swatchView);
            }
        }

        private View createDashboardSectionHeader(@NonNull Context context, @NonNull String overline, @NonNull String title, @NonNull String summary) {
            LinearLayout shell = new LinearLayout(context);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(dp(2), dp(8), dp(2), dp(10));

            TextView overlineView = new TextView(context);
            overlineView.setText(overline);
            overlineView.setTextColor(Color.parseColor("#B9BBC4"));
            overlineView.setTextSize(10);
            overlineView.setTypeface(overlineView.getTypeface(), android.graphics.Typeface.BOLD);
            overlineView.setLetterSpacing(0.12f);

            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextColor(Color.parseColor("#F0F0F3"));
            titleView.setTextSize(16);
            titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
            titleView.setPadding(0, dp(4), 0, 0);

            TextView summaryView = new TextView(context);
            summaryView.setText(summary);
            summaryView.setTextColor(Color.parseColor("#9C9CA8"));
            summaryView.setTextSize(12);
            summaryView.setPadding(0, dp(4), 0, 0);

            shell.addView(overlineView);
            shell.addView(titleView);
            shell.addView(summaryView);
            return shell;
        }

        private View createDashboardStat(@NonNull LayoutInflater inflater, @NonNull LinearLayout parent, @NonNull String value, @NonNull String label) {
            View view = inflater.inflate(R.layout.item_settings_dashboard_stat, parent, false);
            TextView valueText = view.findViewById(R.id.dashboard_stat_value);
            TextView labelText = view.findViewById(R.id.dashboard_stat_label);
            valueText.setText(value);
            labelText.setText(label);
            return view;
        }

        private View createQuickActionChip(@NonNull LayoutInflater inflater, @NonNull LinearLayout parent, @NonNull String text, @NonNull Runnable action) {
            View view = inflater.inflate(R.layout.item_settings_nav_chip, parent, false);
            TextView chipText = view.findViewById(R.id.settings_nav_chip_text);
            chipText.setText(text);
            chipText.setBackgroundResource(R.drawable.st_tile);
            chipText.setPadding(dp(12), 0, dp(12), 0);
            ViewGroup.LayoutParams clp = chipText.getLayoutParams();
            if (clp != null) { clp.width = ViewGroup.LayoutParams.WRAP_CONTENT; clp.height = dp(34); chipText.setLayoutParams(clp); }
            chipText.setCompoundDrawablesRelative(null, null, null, null);
            chipText.setOnClickListener(v -> {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                        .withEndAction(() -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                            action.run();
                        }).start();
            });
            return view;
        }

        private View buildPinnedRail(@NonNull LayoutInflater inflater,
                                     @NonNull java.util.List<String> keys, boolean isFavorite) {
            Context ctx = requireContext();
            android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(ctx);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            scroller.addView(row);

            int shown = 0;
            for (String key : keys) {
                SettingItemHolder meta = findSettingMeta(key);
                if (meta == null || meta.item == null) continue;
                View card = inflater.inflate(R.layout.item_settings_pin_card, row, false);
                ((TextView) card.findViewById(R.id.pin_card_title)).setText(meta.item.title);
                ((TextView) card.findViewById(R.id.pin_card_category)).setText(
                        shortenCategoryLabel(meta.category));
                android.widget.ImageView glyph = card.findViewById(R.id.pin_card_glyph);
                glyph.setImageResource(isFavorite
                        ? R.drawable.ic_csp_star_filled : R.drawable.ic_csp_recent);
                glyph.setColorFilter(isFavorite ? 0xFFFFFFFF : 0xFF9CA3AF);

                View remove = card.findViewById(R.id.pin_card_remove);
                if (isFavorite) {
                    remove.setVisibility(View.VISIBLE);
                    remove.setOnClickListener(rv -> {
                        net.kdt.pojavlaunch.prefs.SettingsMetaStore.toggleFavorite(
                                rv.getContext(), key);
                        row.removeView(card);
                    });
                } else {
                    remove.setVisibility(View.GONE);
                }

                final String cat = meta.category;
                final String k = key;
                card.setOnClickListener(v -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                            .withEndAction(() -> {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                                jumpToSetting(cat, k);
                            }).start();
                });
                row.addView(card);
                if (++shown >= 10) break;
            }
            return scroller;
        }

        /** Locate a setting's owning page + row across every real category. */
        private SettingItemHolder findSettingMeta(@NonNull String key) {
            for (String cat : ALL_CATEGORIES) {
                List<SettingCategory> tmp = new ArrayList<>();
                addCategoryPageTo(tmp, cat);
                for (SettingCategory page : tmp) {
                    for (SettingItem item : page.items) {
                        if (key.equals(item.key)) return new SettingItemHolder(cat, item);
                    }
                }
            }
            return null;
        }

        private int dp(int value) {
            return (int) (value * requireContext().getResources().getDisplayMetrics().density);
        }

        @Override
        public int getItemCount() {
            return mCategories.size();
        }

        public class CategoryViewHolder extends RecyclerView.ViewHolder {
            TextView categoryTitle;
            TextView categoryCount;
            LinearLayout container;

            public CategoryViewHolder(@NonNull View itemView) {
                super(itemView);
                categoryTitle = itemView.findViewById(R.id.category_title);
                categoryCount = itemView.findViewById(R.id.category_count);
                container = itemView.findViewById(R.id.settings_list_container);
                // Phase 10: rounded card clips the pressed-row highlight (attr is API 31+ in XML).
                if (container != null) container.setClipToOutline(true);
            }
        }
    }

    private void bindSettingIcon(@NonNull View itemView, @NonNull SettingItem item) {
        ImageView icon = itemView.findViewById(R.id.setting_icon);
        if (icon == null) return;
        icon.setImageResource(resolveSettingIcon(item.key));
        if (item.key != null && mAnimatedSettingIcons.add(item.key)) {
            icon.animate().cancel();
            icon.setAlpha(0f);
            icon.animate().alpha(1f).setDuration(120).withLayer().start();
        } else {
            icon.setAlpha(1f);
        }
    }

    private int resolveSettingIcon(@Nullable String key) {
        if (key == null || key.trim().isEmpty()) return R.drawable.ic_menu_settings;
        // Every setting owns a dedicated vector drawable named after its key.
        // getIdentifier keeps this mapping maintainable as new settings are added;
        // missing resources still fall back safely instead of crashing a page.
        String resourceName = "ic_setting_" + key.toLowerCase(java.util.Locale.US)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        int icon = getResources().getIdentifier(
                resourceName, "drawable", requireContext().getPackageName());
        return icon != 0 ? icon : R.drawable.ic_menu_settings;
    }

    // ── Setting Data Models ───────────────────────────────────────────────────

    private static class SettingCategory {
        String title;
        List<SettingItem> items;

        public SettingCategory(String title, List<SettingItem> items) {
            this.title = title;
            this.items = items;
        }
    }

    private static class SettingItem {
        public static final int TYPE_SWITCH = 1;
        public static final int TYPE_SLIDER = 2;
        public static final int TYPE_DROPDOWN = 3;
        public static final int TYPE_ACTION = 4;
        public static final int TYPE_INFO = 5;
        public static final int TYPE_INPUT = 6;
        public static final int TYPE_CUSTOM_FASTCLIENT = 7;
        public static final int TYPE_CATEGORY_LINK = 8;
        public static final int TYPE_THEME_SELECTOR = 9;
        public static final int TYPE_PERFORMANCE_PANEL = 10;
        public static final int TYPE_PRESET_PANEL = 11;

        String key;
        int type;
        String title;
        String summary;
        Object defaultValue;

        // Slider config
        int minVal;
        int maxVal;
        int stepVal;
        String unitSuffix;

        // Dropdown config
        String[] dropdownEntries;
        String[] dropdownValues;

        // Action config
        Runnable action;

        // Category link config
        String categoryLinkTarget;

        public SettingItem(String key, int type, String title, String summary, Object defaultValue) {
            this.key = key;
            this.type = type;
            this.title = title;
            this.summary = summary;
            this.defaultValue = defaultValue;
            if (type == TYPE_CATEGORY_LINK && defaultValue instanceof String) {
                this.categoryLinkTarget = (String) defaultValue;
            }
        }

        public SettingItem setSliderConfig(int min, int max, int step, String suffix) {
            this.minVal = min;
            this.maxVal = max;
            this.stepVal = step;
            this.unitSuffix = suffix;
            return this;
        }

        public SettingItem setDropdownOptions(String[] entries, String[] values) {
            this.dropdownEntries = entries;
            this.dropdownValues = values;
            return this;
        }

        public SettingItem setAction(Runnable r) {
            this.action = r;
            return this;
        }
    }
}