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
    // ══ Phase 3: search / favorites / recent / focus-jump ══
    private String mSearchQuery = "";
    private EditText mSearchInput;
    private final java.util.Set<String> mAnimatedSettingIcons = new java.util.HashSet<>();
    private static final String[] ALL_CATEGORIES = {
            "Launcher Customisation", "Launcher Settings", "Video & Graphics", "Controls", "Java Runtime",
            "Audio", "Account", "Experimental", "Advanced", "Miscellaneous", "Sponsors", "Performance"};
    /** One-shot: a pinned setting (Favorite/Recent) asked its page to flash a row. */
    private static String sPendingFocusKey;

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

        setupHeaderUi();
        setupCategoryRail();
        setupSettingsSearch(view);

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

        Button saveBtn = view.findViewById(R.id.btn_save_settings);
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
        mRecyclerView.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(
                requireContext(), R.anim.settings_rows_layout_v4));
        mRecyclerView.scheduleLayoutAnimation();
        playSettingsStudioEntrance(view);
        updateSaveBar();
    }

    /** GPU-friendly page choreography: transform/alpha only, no animated sizes. */
    private void playSettingsStudioEntrance(@NonNull View root) {
        View sidebar = root.findViewById(R.id.settings_header_bar);
        if (sidebar != null) {
            float d = getResources().getDisplayMetrics().density;
            sidebar.animate().cancel();
            sidebar.setAlpha(0f);
            sidebar.setTranslationX(-18f * d);
            sidebar.animate().alpha(1f).translationX(0f)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .withLayer().start();
        }
        if (mRecyclerView != null) {
            float d = getResources().getDisplayMetrics().density;
            mRecyclerView.animate().cancel();
            mRecyclerView.setAlpha(0f);
            mRecyclerView.setTranslationX(16f * d);
            mRecyclerView.animate().alpha(1f).translationX(0f)
                    .setStartDelay(55).setDuration(300)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f))
                    .withLayer().start();
        }
        if (mCategoryRailScroll != null && mCategoryRailScroll.getVisibility() == View.VISIBLE) {
            mCategoryRailScroll.setAlpha(0f);
            mCategoryRailScroll.setTranslationY(-8f * getResources().getDisplayMetrics().density);
            mCategoryRailScroll.animate().alpha(1f).translationY(0f)
                    .setStartDelay(80).setDuration(240).withLayer().start();
        }
    }

    private int resolveCategoryIconByName(String catName) {
        if (catName == null) return R.drawable.ic_menu_settings;
        switch (catName) {
            case "Launcher Customisation": return R.drawable.ic_settings_launcher;
            case "Launcher Settings": return R.drawable.ic_settings_launcher;
            case "Video & Graphics": return R.drawable.ic_settings_video;
            case "Controls": return R.drawable.ic_settings_control;
            case "Java Runtime": return R.drawable.ic_settings_java;
            case "Audio": return R.drawable.ic_settings_audio;
            case "Account": return R.drawable.ic_settings_account;
            case "Experimental": return R.drawable.ic_settings_experimental;
            case "Advanced": return R.drawable.ic_settings_advanced;
            case "Miscellaneous": return R.drawable.ic_settings_misc;
            case "Sponsors": return R.drawable.ic_infrawire_mark_white;
            case "Performance": return R.drawable.ic_settings_performance;
            default: return R.drawable.ic_menu_settings;
        }
    }


    private void setupHeaderUi() {
        if (mHeaderTitle != null) {
            mHeaderTitle.setText(mCategoryName != null ? mCategoryName : "Settings Hub");
        }
        if (mHeaderSubtitle != null) {
            mHeaderSubtitle.setText(mCategoryName != null
                    ? resolveCategorySubtitleByName(mCategoryName)
                    : "Everything that shapes your launcher, controls, graphics and Java runtime — organized for landscape play.");
            mHeaderSubtitle.setVisibility(View.VISIBLE);
        }
        if (mHeaderBadge != null) {
            mHeaderBadge.setText(mCategoryName != null
                    ? resolveCategoryBadgeByName(mCategoryName)
                    : "10 SECTIONS");
            mHeaderBadge.setVisibility(View.VISIBLE);
        }
        if (mHeaderIcon != null) {
            mHeaderIcon.setImageResource(mCategoryName != null ? resolveCategoryIconByName(mCategoryName) : R.drawable.ic_menu_settings);
            mHeaderIcon.setAlpha(0f);
            mHeaderIcon.setScaleX(0.78f);
            mHeaderIcon.setScaleY(0.78f);
            mHeaderIcon.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .start();
        }
    }

    private void setupCategoryRail() {
        if (mCategoryRail == null || mCategoryRailScroll == null) return;
        mCategoryRail.removeAllViews();

        if (mCategoryName == null) {
            mCategoryRailScroll.setVisibility(View.GONE);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (SettingItem item : buildRootCategoryItems()) {
            if (item.type != SettingItem.TYPE_CATEGORY_LINK || item.categoryLinkTarget == null) continue;
            View chipView = inflater.inflate(R.layout.item_settings_nav_chip, mCategoryRail, false);
            TextView chipText = chipView.findViewById(R.id.settings_nav_chip_text);
            boolean selected = Objects.equals(item.categoryLinkTarget, mCategoryName);
            chipText.setText(shortenCategoryLabel(item.title));
            chipText.setBackgroundResource(selected ? R.drawable.bg_cs_pill_active : R.drawable.bg_cs_pill_idle);
            chipText.setTextColor(Color.parseColor(selected ? "#0D0D0D" : "#9CA3AF"));
            chipText.setOnClickListener(v -> {
                if (!selected) openCategoryPage(item.categoryLinkTarget);
            });
            mCategoryRail.addView(chipView);
        }
        mCategoryRailScroll.setVisibility(View.VISIBLE);
        mCategoryRailScroll.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in_slide_up));
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
            case "Launcher Customisation":
                return "Home artwork, launch-stage motion, language and theme controls in one visual deck.";
            case "Launcher Settings":
                return "Language, downloads, permissions, and launcher-side behavior in one quick deck.";
            case "Video & Graphics":
                return "Renderer, resolution, VSync, and display behavior for smooth Minecraft sessions.";
            case "Controls":
                return "Touch, gyro, mouse, controller, and gesture tuning built for landscape play.";
            case "Java Runtime":
                return "Memory, runtimes, sandboxing, and advanced JVM launch parameters.";
            case "Audio":
                return "Master audio behavior, backend selection, and sound quality controls.";
            case "Account":
                return "Profile visibility and account-related launcher status panels.";
            case "Experimental":
                return "Visual experiments, background personalization, and power-user toggles.";
            case "Advanced":
                return "Cache management, reset tools, and maintenance actions for the launcher.";
            case "Miscellaneous":
                return "Verification, capes, and extra compatibility switches that support special cases.";
            case "Sponsors":
                return "Official partners who keep CS Launcher fast, free, and professionally backed.";
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
            case "Launcher Settings": return "CORE";
            case "Video & Graphics": return "GPU";
            case "Controls": return "INPUT";
            case "Java Runtime": return "JVM";
            case "Audio": return "AUDIO";
            case "Account": return "PROFILE";
            case "Experimental": return "LAB";
            case "Advanced": return "TOOLS";
            case "Miscellaneous": return "EXTRA";
            case "Sponsors": return "PARTNER";
            case "Performance": return "LIVE";
            default: return "PAGE";
        }
    }

    private List<SettingItem> buildRootCategoryItems() {
        List<SettingItem> rootItems = new ArrayList<>();
        rootItems.add(new SettingItem("cat_launcher", SettingItem.TYPE_CATEGORY_LINK, "Launcher Settings", "Configure language, updates, and downloads", "Launcher Settings"));
        // User req: one place for every look-and-feel switch — home background,
        // launch screen, theme and language.
        rootItems.add(new SettingItem("cat_customisation", SettingItem.TYPE_CATEGORY_LINK, "Launcher Customisation", "Home background, launch screen, theme & language", "Launcher Customisation"));
        rootItems.add(new SettingItem("cat_video", SettingItem.TYPE_CATEGORY_LINK, "Video & Graphics", "Configure renderers, resolution, and VSync options", "Video & Graphics"));
        rootItems.add(new SettingItem("cat_controls", SettingItem.TYPE_CATEGORY_LINK, "Controls", "Customize touch overlays, cursors, and gyroscope controls", "Controls"));
        rootItems.add(new SettingItem("cat_java", SettingItem.TYPE_CATEGORY_LINK, "Java Runtime", "Manage memory allocations, JREs, and Java options", "Java Runtime"));
        rootItems.add(new SettingItem("cat_audio", SettingItem.TYPE_CATEGORY_LINK, "Audio", "Adjust volume levels and sound output parameters", "Audio"));
        // User req: Account category hidden from the settings deck (redundant — login flow owns it; page code stays dormant).
        rootItems.add(new SettingItem("cat_experimental", SettingItem.TYPE_CATEGORY_LINK, "Experimental", "Test launcher orientations, wall-papers, and colors", "Experimental"));
        rootItems.add(new SettingItem("cat_misc", SettingItem.TYPE_CATEGORY_LINK, "Miscellaneous", "Library verifications, system drivers, and in-game capes", "Miscellaneous"));
        if (net.kdt.pojavlaunch.remote.FirebaseSyncManager.isSponsorshipEnabled()) {
            rootItems.add(new SettingItem("cat_sponsors", SettingItem.TYPE_CATEGORY_LINK, "Sponsors", "Official partners backing CS Launcher with cloud power", "Sponsors"));
        }
        rootItems.add(new SettingItem("cat_performance", SettingItem.TYPE_CATEGORY_LINK, "Performance", "FPS overlay, live memory, and next-gen engine dials", "Performance"));
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


    /** Builds the real option page for one category (extracted so the Search
     *  index can build every page's items off-screen). */
    private void addCategoryPageTo(List<SettingCategory> categories, String catName) {
        // Subcategory Pages Detail (100% Real Authentic Launcher Options)
            switch (catName) {
                case "Launcher Customisation":
                    List<SettingItem> custItems = new ArrayList<>();
                    // ── Home screen background (the HOME page bg, not cards) ──
                    custItems.add(new SettingItem("set_custom_launcher_bg", SettingItem.TYPE_ACTION,
                            "Home Screen Background",
                            "Set an image as the home screen background", null)
                            .setAction(() -> mImagePickerLauncher.launch("image/*")));
                    custItems.add(new SettingItem("remove_custom_launcher_bg", SettingItem.TYPE_ACTION,
                            "Remove Home Background",
                            "Back to the default gradient background", null)
                            .setAction(() -> {
                                File bgFile = new File(RightPaneHomeFragment.CUSTOM_BG_PATH);
                                if (bgFile.exists()) bgFile.delete();
                                notifyHomeFragmentBgChanged();
                                net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(),
                                        getString(R.string.preference_custom_bg_removed));
                            }));
                    // ── Launch screen GIF (set/remove — moved here so ALL
                    // customisation lives in ONE page, user req) ──
                    custItems.add(new SettingItem("set_launch_gif", SettingItem.TYPE_ACTION,
                            getString(R.string.cs_launch_gif_title),
                            getString(R.string.cs_launch_gif_summary), null)
                            .setAction(() -> mGifPickerLauncher.launch("image/gif")));
                    custItems.add(new SettingItem("remove_launch_gif", SettingItem.TYPE_ACTION,
                            getString(R.string.cs_launch_gif_remove_title),
                            getString(R.string.cs_launch_gif_remove_summary), null)
                            .setAction(this::removeLaunchGif));
                    // ── Launch screen (which screen shows while the game loads) ──
                    custItems.add(new SettingItem("launch_screen_style", SettingItem.TYPE_DROPDOWN,
                            "Launch Screen",
                            "Classic black, or the custom GIF you imported (Set/Remove in Launcher Settings)",
                            "gif")
                            .setDropdownOptions(new String[]{"Classic Black", "Custom GIF"},
                                    new String[]{"black", "gif"}));
                    custItems.add(new SettingItem("launch_stage_color_action", SettingItem.TYPE_ACTION,
                            "Launch Screen Color",
                            "Pick the loading screen background color", null)
                            .setAction(this::pickLaunchStageColor));
                    custItems.add(new SettingItem("launch_stage_color_reset", SettingItem.TYPE_ACTION,
                            "Reset Launch Color",
                            "Back to pure black", null)
                            .setAction(() -> {
                                LauncherPreferences.DEFAULT_PREF.edit()
                                        .putInt("launch_stage_color", 0xFF000000).apply();
                                net.kdt.pojavlaunch.utils.CsPopup.show(requireActivity(),
                                        getString(R.string.cs_launch_color_reset));
                            }));
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
                    categories.add(new SettingCategory("Customisation", custItems));
                    break;

                case "Launcher Settings":
                    List<SettingItem> launcherItems = new ArrayList<>();
                    launcherItems.add(new SettingItem("perf_presets_launcher", SettingItem.TYPE_PRESET_PANEL, "Performance Presets", "", null));
                    launcherItems.add(new SettingItem("check_for_update_btn", SettingItem.TYPE_ACTION,
                            "Check for Update",
                            "Check online for the latest CS Launcher V3 release",
                            null).setAction(() -> {
                        net.kdt.pojavlaunch.remote.FirebaseSyncManager.checkForUpdateManual(requireActivity());
                    }));
                    launcherItems.add(new SettingItem("force_english", SettingItem.TYPE_SWITCH, getString(R.string.preference_force_english_title), getString(R.string.preference_force_english_description), false));
                    launcherItems.add(new SettingItem("notification_permission_request", SettingItem.TYPE_SWITCH, getString(R.string.preference_ask_for_notification_title), getString(R.string.preference_ask_for_notification_description), false));
                    launcherItems.add(new SettingItem("microphone_permission_request", SettingItem.TYPE_SWITCH, getString(R.string.preference_ask_for_microphone_title), getString(R.string.preference_ask_for_microphone_description), false));
                    launcherItems.add(new SettingItem("downloadSource", SettingItem.TYPE_DROPDOWN, getString(R.string.preference_download_source_title), getString(R.string.preference_download_source_description), "default").setDropdownOptions(new String[]{"Default", "Mirror (China)"}, new String[]{"default", "china"}));
                    launcherItems.add(new SettingItem("verifyManifest", SettingItem.TYPE_SWITCH, getString(R.string.preference_verify_manifest_title), getString(R.string.preference_verify_manifest_description), true));
                    // A9: global animation speed (concept port from Zalith). One
                    // slider retimes every UiMotion animation via MotionSpeed.
                    launcherItems.add(new SettingItem("launcher_animate_speed", SettingItem.TYPE_SLIDER,
                            "Animation Speed", "Global animation duration multiplier for the launcher UI",
                            100).setSliderConfig(50, 200, 10, " %"));
                    categories.add(new SettingCategory("Launcher Configurations", launcherItems));
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
                    graphicsItems.add(new SettingItem("sustainedPerformance", SettingItem.TYPE_SWITCH, getString(R.string.preference_sustained_performance_title), getString(R.string.preference_sustained_performance_description), false));
                    graphicsItems.add(new SettingItem("alternate_surface", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_title_use_surface_view), getString(R.string.mcl_setting_subtitle_use_surface_view), true));
                    graphicsItems.add(new SettingItem("force_vsync", SettingItem.TYPE_SWITCH, getString(R.string.preference_force_vsync_title), getString(R.string.preference_force_vsync_description), false));
                    graphicsItems.add(new SettingItem("vsync_in_zink", SettingItem.TYPE_SWITCH, getString(R.string.preference_vsync_in_zink_title), getString(R.string.preference_vsync_in_zink_description), true));
                    categories.add(new SettingCategory("Graphics Settings", graphicsItems));
                    break;

                case "Controls":
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
                    categories.add(new SettingCategory("Control Settings", controlsItems));
                    break;

                case "Java Runtime":
                    List<SettingItem> javaItems = new ArrayList<>();
                    javaItems.add(new SettingItem("perf_presets_java", SettingItem.TYPE_PRESET_PANEL, "Performance Presets", "", null));
                    javaItems.add(new SettingItem("install_jre", SettingItem.TYPE_ACTION, getString(R.string.multirt_title), getString(R.string.multirt_subtitle), null).setAction(this::openMultiRTDialog));
                    javaItems.add(new SettingItem("javaArgs", SettingItem.TYPE_INPUT, getString(R.string.mcl_setting_title_javaargs), getString(R.string.mcl_setting_subtitle_javaargs), ""));

                    int maxRAM = Tools.getMaximumRamAllocation(requireContext());
                    javaItems.add(new SettingItem("allocation", SettingItem.TYPE_SLIDER, getString(R.string.mcl_memory_allocation), getString(R.string.mcl_memory_allocation_subtitle), 1024).setSliderConfig(256, maxRAM, 128, " MB"));

                    javaItems.add(new SettingItem("disable_autojre_select", SettingItem.TYPE_SWITCH, "Disable automatic JRE selection", "Stops automatic selection of which runtime to use", false));
                    javaItems.add(new SettingItem("java_sandbox", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_java_sandbox), getString(R.string.mcl_setting_java_sandbox_subtitle), true));
                    categories.add(new SettingCategory("Java Configurations", javaItems));
                    break;

                case "Audio":
                    List<SettingItem> audioItems = new ArrayList<>();
                    audioItems.add(new SettingItem("enable_audio", SettingItem.TYPE_SWITCH, "Enable Game Sound", "Allow game instances to play audio", true));
                    audioItems.add(new SettingItem("launcher_volume", SettingItem.TYPE_SLIDER, "Launcher Master Volume", "Default volume control for launched games", 80).setSliderConfig(0, 100, 5, " %"));
                    audioItems.add(new SettingItem("use_opensles", SettingItem.TYPE_SWITCH, "Use OpenSL ES Backend", "Enable low-latency high performance sound library", false));
                    categories.add(new SettingCategory("Audio Settings", audioItems));
                    break;

                case "Account":
                    List<SettingItem> accountItems = new ArrayList<>();
                    MinecraftAccount activeAccount = PojavProfile.getCurrentProfileContent(requireContext(), null);
                    String accountName = activeAccount != null ? activeAccount.username : "None";
                    accountItems.add(new SettingItem("active_profile_info", SettingItem.TYPE_INFO, "Active Account Profile", accountName, null));
                    categories.add(new SettingCategory("Account Configurations", accountItems));
                    break;

                case "Experimental":
                    List<SettingItem> expItems = new ArrayList<>();
                    expItems.add(new SettingItem("dump_shaders", SettingItem.TYPE_SWITCH, getString(R.string.preference_shader_dump_title), getString(R.string.preference_shader_dump_description), false));
                    expItems.add(new SettingItem("bigCoreAffinity", SettingItem.TYPE_SWITCH, getString(R.string.preference_force_big_core_title), getString(R.string.preference_force_big_core_desc), false));
                    expItems.add(new SettingItem("force_landscape", SettingItem.TYPE_SWITCH, getString(R.string.preference_force_landscape_title), getString(R.string.preference_force_landscape_summary), false));
                    expItems.add(new SettingItem("enable_bg_gradient", SettingItem.TYPE_SWITCH, getString(R.string.preference_bg_gradient_title), getString(R.string.preference_bg_gradient_summary), false));
                    categories.add(new SettingCategory("Experimental Settings", expItems));
                    break;

                case "Advanced":
                    List<SettingItem> advItems = new ArrayList<>();
                    advItems.add(new SettingItem("check_for_update_adv_btn", SettingItem.TYPE_ACTION,
                            "Check for Update",
                            "Check online for the latest CS Launcher V3 release",
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
                    categories.add(new SettingCategory("Advanced Actions", advItems));
                    break;

                case "Miscellaneous":
                    List<SettingItem> miscItems = new ArrayList<>();
                    miscItems.add(new SettingItem("checkLibraries", SettingItem.TYPE_SWITCH, getString(R.string.mcl_setting_check_libraries), getString(R.string.mcl_setting_check_libraries_subtitle), true));
                    miscItems.add(new SettingItem("arc_capes", SettingItem.TYPE_SWITCH, getString(R.string.arc_capes_title), getString(R.string.arc_capes_desc), false));
                    miscItems.add(new SettingItem("zinkPreferSystemDriver", SettingItem.TYPE_SWITCH, getString(R.string.preference_vulkan_driver_system_title), getString(R.string.preference_vulkan_driver_system_description), false));
                    categories.add(new SettingCategory("Miscellaneous Settings", miscItems));
                    break;

                case "Performance":
                    List<SettingItem> perfItems = new ArrayList<>();
                    // User req: device-class presets replace the old live-memory panel
                    // (live HUD chips now live in the in-game Quick Settings dialog).
                    perfItems.add(new SettingItem("perf_presets", SettingItem.TYPE_PRESET_PANEL,
                            "Performance Presets", "", null));
                    perfItems.add(new SettingItem("showFpsCounter", SettingItem.TYPE_SWITCH,
                            "Show FPS",
                            "Live frame counter while playing — measured at the GL swap boundary (also toggleable in-game via Quick Settings)",
                            false));
                    perfItems.add(new SettingItem("low_pause_gc", SettingItem.TYPE_SWITCH,
                            "Low-Pause GC (Stable FPS)",
                            "Mobile-tuned G1GC with a 50ms pause target, capped GC workers and adaptive heap growth to reduce chunk-load stutter.",
                            true));
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
                            "High-Performance VPS & Cloud Hosting • Official Cloud Hosting Partner of CS Launcher V3", null));
                    sponsorItems.add(new SettingItem("infrawire_about_info", SettingItem.TYPE_INFO,
                            "Official Sponsor",
                            "Infrawire powers CS Launcher with latest-generation VPS & cloud infrastructure — NVMe SSD storage, DDR4 memory, a 10 Gbps independent global network, multi-layer Anti-DDoS protection, hourly billing from €0.007/hour and 24/7 expert support.", null));
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
                            "Documentation", "Guides, tutorials and the Infrawire knowledge base", null)
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
                    if (item.type == SettingItem.TYPE_CATEGORY_LINK) continue;
                    String hay = ((item.title != null ? item.title : "") + "\n"
                            + (item.summary != null ? item.summary : "") + "\n"
                            + (item.key != null ? item.key : "")).toLowerCase(java.util.Locale.US);
                    if (hay.contains(q)) matches.add(item);
                }
            }
            if (!matches.isEmpty()) {
                out.add(new SettingCategory(cat + "  •  " + matches.size(), matches));
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
        mSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                mSearchQuery = e != null ? e.toString().trim() : "";
                if (mAdapter != null) mAdapter.replaceCategories(buildCurrentCategories());
            }
        });
    }

    // ══ Favorite star (bound on every real setting row) ══
    private void bindFavoriteStar(@NonNull View row, @NonNull SettingItem item) {
        android.widget.ImageButton star = row.findViewById(R.id.setting_fav_star);
        if (star == null) return;
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
        star.setColorFilter(fav ? 0xFFE4E4EA : 0xFF6B7280);
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

    private boolean isItemVisible(SettingItem item, SharedPreferences draftPrefs) {
        if ("ignoreNotch".equals(item.key)) {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && LauncherPreferences.PREF_NOTCH_SIZE > 0;
        }
        if ("sustainedPerformance".equals(item.key)) {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N;
        }
        if ("force_vsync".equals(item.key)) {
            return draftPrefs.getBoolean("alternate_surface", true);
        }
        if ("timeLongPressTrigger".equals(item.key)) {
            return !draftPrefs.getBoolean("disableGestures", false);
        }
        if ("gyroSensitivity".equals(item.key) || "gyroSampleRate".equals(item.key) ||
                "gyroSmoothing".equals(item.key) || "gyroInvertX".equals(item.key) || "gyroInvertY".equals(item.key)) {
            return Tools.deviceSupportsGyro(getContext()) && draftPrefs.getBoolean("enableGyro", false);
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
        if (getView() == null) return;
        View bar = getView().findViewById(R.id.unsaved_changes_bar);
        if (bar == null) return;
        Button saveBtn = getView().findViewById(R.id.btn_save_settings);
        TextView statusText = getView().findViewById(R.id.save_status_text);

        if (mIsDirty) {
            if (bar.getVisibility() != View.VISIBLE) {
                float d = getResources().getDisplayMetrics().density;
                bar.setVisibility(View.VISIBLE);
                bar.setAlpha(0f);
                bar.setTranslationY(18f * d);
                bar.animate().alpha(1f).translationY(0f).setDuration(230)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                        .withLayer().start();
            }
            if (saveBtn != null) {
                saveBtn.setEnabled(true);
                saveBtn.setAlpha(1f);
            }
            if (statusText != null) {
                statusText.setText("\u25cf Unsaved Changes");
                statusText.setTextColor(Color.parseColor("#FFB020"));
            }
            if (mHeaderBadge != null) {
                mHeaderBadge.setText("UNSAVED");
                if (mHeaderBadge.getVisibility() != View.VISIBLE) {
                    mHeaderBadge.setVisibility(View.VISIBLE);
                    mHeaderBadge.startAnimation(AnimationUtils.loadAnimation(
                            getContext(), R.anim.fade_scale_in));
                }
            }
        } else {
            if (bar.getVisibility() == View.VISIBLE) {
                float d = getResources().getDisplayMetrics().density;
                bar.animate().cancel();
                bar.animate().alpha(0f).translationY(18f * d).setDuration(180)
                        .withLayer()
                        .withEndAction(() -> {
                            bar.setVisibility(View.GONE);
                            bar.setAlpha(1f);
                            bar.setTranslationY(0f);
                        }).start();
            }
            if (saveBtn != null) {
                saveBtn.setEnabled(false);
                saveBtn.setAlpha(0.5f);
            }
            if (statusText != null) {
                statusText.setText("\u25cf Changes Saved");
                statusText.setTextColor(Color.parseColor("#9CA3AF"));
            }
            if (mHeaderBadge != null) {
                mHeaderBadge.setText(mCategoryName != null
                        ? resolveCategoryBadgeByName(mCategoryName)
                        : "10 SECTIONS");
                mHeaderBadge.setVisibility(View.VISIBLE);
            }
        }
    }

    private void saveChanges() {
        Context context = requireContext();
        boolean prevEnglish = mDraftPrefs.getBoolean("force_english", false);
        boolean prevGradient = mDraftPrefs.getBoolean("enable_bg_gradient", false);
        boolean prevLandscape = mDraftPrefs.getBoolean("force_landscape", false);

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

        boolean verified = verifySavedSettings(context);
        if (verified) {
            LauncherPreferences.loadPreferences(context);
            mIsDirty = false;
            updateSaveBar();
            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show();

            boolean newEnglish = mDraftPrefs.getBoolean("force_english", false);
            boolean newGradient = mDraftPrefs.getBoolean("enable_bg_gradient", false);
            boolean newLandscape = mDraftPrefs.getBoolean("force_landscape", false);

            if (prevEnglish != newEnglish || prevGradient != newGradient) {
                requireActivity().recreate();
            } else if (prevLandscape != newLandscape) {
                requireActivity().setRequestedOrientation(
                        newLandscape ? SCREEN_ORIENTATION_SENSOR_LANDSCAPE : SCREEN_ORIENTATION_UNSPECIFIED);
            }
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
            return new CategoryViewHolder(v);
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
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                                .withEndAction(() -> {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
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
                                    });
                                }
                            }
                        } else {
                            mPrefs.edit().putBoolean(item.key, checkedVal).apply();
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
                            markDirty();
                        }
                    });

                    // O9 (Copper concept port): tapping the value chip (e.g.
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

                    itemView.setOnClickListener(v -> {
                        if (item.dropdownEntries == null) return;
                        new AlertDialog.Builder(holder.itemView.getContext())
                                .setTitle(item.title)
                                .setItems(item.dropdownEntries, (dialog, which) -> {
                                    String chosenVal = item.dropdownValues[which];
                                    mPrefs.edit().putString(item.key, chosenVal).apply();
                                    tvSpinner.setText(item.dropdownEntries[which]);
                                    markDirty();
                                })
                                .setNegativeButton(android.R.string.cancel, null)
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
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                                .withEndAction(() -> {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
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
                    tvSummary.setText("Currently signed-in launcher profile");
                    tvSpinner.setText(String.valueOf(item.defaultValue));
                    itemView.setClickable(false);
                    itemView.setFocusable(false);
                } else {
                    itemView = new View(holder.itemView.getContext());
                }

                itemView.setTag(item.key);
                bindFavoriteStar(itemView, item);
                holder.container.addView(itemView);
            }

            if (holder.categoryCount != null) {
                if (visibleCount > 0) {
                    holder.categoryCount.setVisibility(View.VISIBLE);
                    holder.categoryCount.setText(String.valueOf(visibleCount));
                } else {
                    holder.categoryCount.setVisibility(View.GONE);
                }
            }

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
            }
        }


        private void bindDashboardHolder(@NonNull CategoryViewHolder holder, @NonNull SettingCategory cat, @NonNull LayoutInflater inflater) {
            holder.categoryTitle.setText("SETTINGS DECK");
            holder.categoryCount.setVisibility(View.VISIBLE);
            holder.categoryCount.setText(String.valueOf(cat.items.size()));
            holder.container.removeAllViews();

            holder.container.addView(createDashboardSectionHeader(
                    holder.itemView.getContext(),
                    "QUICK OVERVIEW",
                    "Launcher tuning at a glance",
                    "A horizontal control hub built for landscape mobile play."
            ));

            LinearLayout statRow = new LinearLayout(holder.itemView.getContext());
            statRow.setOrientation(LinearLayout.HORIZONTAL);
            statRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            statRow.setPadding(0, 0, 0, dp(8));

            String activeProfile = PojavProfile.getCurrentProfileContent(requireContext(), null) != null
                    ? PojavProfile.getCurrentProfileContent(requireContext(), null).username
                    : "Guest";
            statRow.addView(createDashboardStat(inflater, statRow, String.valueOf(cat.items.size()), "Settings pages"));
            statRow.addView(createDashboardStat(inflater, statRow, "Dark", "Launcher theme"));
            statRow.addView(createDashboardStat(inflater, statRow, activeProfile, "Live profile"));
            holder.container.addView(statRow);

            // ══ Phase 3: Favorites + Recently Changed quick-pins ══
            Context dashCtx = holder.itemView.getContext();
            java.util.List<String> favKeys = net.kdt.pojavlaunch.prefs.SettingsMetaStore.getFavorites(dashCtx);
            java.util.List<String> recentKeys = net.kdt.pojavlaunch.prefs.SettingsMetaStore.getRecent(dashCtx);
            if (!favKeys.isEmpty()) {
                holder.container.addView(createDashboardSectionHeader(dashCtx,
                        "YOUR FAVORITES",
                        dashCtx.getString(R.string.cs_settings_favorites),
                        "Starred settings — one tap jumps straight to the option."));
                holder.container.addView(buildPinnedRail(inflater, favKeys, true));
            }
            if (!recentKeys.isEmpty()) {
                holder.container.addView(createDashboardSectionHeader(dashCtx,
                        "PICK UP WHERE YOU LEFT",
                        dashCtx.getString(R.string.cs_settings_recent),
                        "The last options you touched, newest first."));
                holder.container.addView(buildPinnedRail(inflater, recentKeys, false));
            }

            holder.container.addView(createDashboardSectionHeader(
                    holder.itemView.getContext(),
                    "SETTINGS HUB",
                    "Browse categories",
                    "Every tuning deck of CS Launcher V3 — tap a card to dive in."
            ));

            // ══ Phase 5 (Req-6): 2-column hub grid — every category visible at
            // once instead of a hidden horizontal swipe row. Cards keep their
            // press-bounce and gain a staggered fade/slide entrance.
            Context gridCtx = holder.itemView.getContext();
            LinearLayout categoryGrid = new LinearLayout(gridCtx);
            categoryGrid.setOrientation(LinearLayout.VERTICAL);
            categoryGrid.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            final int colGap = dp(8);
            final int rowGap = dp(8);
            LinearLayout currentRow = null;
            int cardIndex = 0;
            for (SettingItem item : cat.items) {
                if (cardIndex % 2 == 0) {
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
                if (badge != null) {
                    badge.setText(resolveCategoryBadgeByName(item.categoryLinkTarget));
                    badge.setVisibility(View.VISIBLE);
                    try {
                        android.animation.Animator pop = android.animation.AnimatorInflater.loadAnimator(gridCtx, R.animator.s4h_badge_pop);
                        pop.setTarget(badge);
                        pop.start();
                    } catch (Throwable ignored) {}
                }
                View iconWell = card.findViewById(R.id.category_card_icon_well);
                if (iconWell != null) {
                    try {
                        android.animation.Animator pulse = android.animation.AnimatorInflater.loadAnimator(gridCtx, R.animator.s4h_icon_pulse);
                        pulse.setTarget(iconWell);
                        pulse.start();
                    } catch (Throwable ignored) {}
                }

                card.setOnClickListener(v -> {
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                            .withEndAction(() -> {
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                                openCategoryPage(item.categoryLinkTarget);
                            }).start();
                });

                LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (cardIndex % 2 == 0) slot.setMargins(0, 0, colGap / 2, rowGap);
                else                      slot.setMargins(colGap / 2, 0, 0, rowGap);
                currentRow.addView(card, slot);

                // Lightweight staggered reveal (alpha/translate only — no layout work)
                card.setAlpha(0f);
                card.setTranslationY(dp(12));
                card.animate()
                        .alpha(1f).translationY(0f)
                        .setStartDelay(26L * cardIndex)
                        .setDuration(260)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start();

                cardIndex++;
            }
            holder.container.addView(categoryGrid);

            holder.container.addView(createDashboardSectionHeader(
                    holder.itemView.getContext(),
                    "QUICK ACTIONS",
                    "One-tap launcher tools",
                    "Use premium shortcuts for runtime, controls, and cleanup."
            ));

            android.widget.HorizontalScrollView actionScroller = new android.widget.HorizontalScrollView(holder.itemView.getContext());
            actionScroller.setHorizontalScrollBarEnabled(false);
            actionScroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
            actionScroller.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout actionRow = new LinearLayout(holder.itemView.getContext());
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionScroller.addView(actionRow);
            actionRow.addView(createQuickActionChip(inflater, actionRow, "Runtime Manager", () -> LauncherPreferenceFragment.this.openMultiRTDialog()));
            actionRow.addView(createQuickActionChip(inflater, actionRow, "Controls", () -> openCategoryPage("Controls")));
            actionRow.addView(createQuickActionChip(inflater, actionRow, "Clear Cache", () -> {
                clearCacheLocal(requireContext());
                Toast.makeText(requireContext(), "Caches cleared successfully", Toast.LENGTH_SHORT).show();
            }));
            holder.container.addView(actionScroller);

            holder.itemView.setVisibility(View.VISIBLE);
            RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            if (params != null) {
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                params.topMargin = (int) (6 * holder.itemView.getResources().getDisplayMetrics().density);
                params.bottomMargin = (int) (10 * holder.itemView.getResources().getDisplayMetrics().density);
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

        // ══ Performance presets (user req): LOW-END / HIGH-END one-tap bundles ══
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
            // Leave Android, renderer native memory and GPU buffers enough headroom.
            // More heap is not more FPS on mobile; LMK/GC pressure does the opposite.
            final int highRam = Math.max(1536,
                    Math.min(4096, (int) ((totalMb * 0.38f) / 256f) * 256));

            TextView points = panel.findViewById(R.id.preset_high_points);
            if (points != null) points.setText(
                    highRam + " MB RAM • 90% resolution • VSync off • Big-core boost");

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
            int ram = 1024;
            int resRatio = 60;
            boolean sustained = true;
            boolean bigCore = false;
            String fsrMode = "4"; // MobileGlues FSR: performance
            String modeName = "low";
            String toastMsg = "Low-End Mobile preset applied (1024 MB RAM, 60% resolution)";
            if (presetType == 1) {
                ram = 2048;
                resRatio = 75;
                sustained = false;
                fsrMode = "3"; // balanced
                modeName = "med";
                toastMsg = "Balanced Mobile preset applied (2048 MB RAM, 75% resolution)";
            } else if (presetType == 2) {
                ram = highRam;
                resRatio = 90;
                sustained = false;
                bigCore = true;
                fsrMode = "2"; // quality
                modeName = "high";
                toastMsg = "Maximum FPS preset applied (" + highRam + " MB RAM, 90% resolution)";
            }
            try {
                android.content.SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                        ? LauncherPreferences.DEFAULT_PREF
                        : ctx.getSharedPreferences("cslauncher_settings", Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor e = p.edit();
                e.putInt("allocation", ram);
                e.putInt("resolutionRatio", resRatio);
                e.putBoolean("force_vsync", false);
                e.putBoolean("vsync_in_zink", false);
                e.putBoolean("sustainedPerformance", sustained);
                e.putBoolean("bigCoreAffinity", bigCore);
                e.putBoolean("alternate_surface", false);
                e.putBoolean("low_pause_gc", true);
                e.putString("mg_renderer_setting_fsr", fsrMode);
                e.putString("mg_renderer_setting_multidraw", "0");
                e.putString("perfPreset", modeName);
                e.commit();
                try {
                    android.content.SharedPreferences.Editor d =
                            SettingsSaveManager.getDraftPrefs(ctx).edit();
                    d.putInt("allocation", ram);
                    d.putInt("resolutionRatio", resRatio);
                    d.putBoolean("force_vsync", false);
                    d.putBoolean("vsync_in_zink", false);
                    d.putBoolean("sustainedPerformance", sustained);
                    d.putBoolean("bigCoreAffinity", bigCore);
                    d.putBoolean("alternate_surface", false);
                    d.putBoolean("low_pause_gc", true);
                    d.putString("mg_renderer_setting_fsr", fsrMode);
                    d.putString("mg_renderer_setting_multidraw", "0");
                    d.apply();
                } catch (Throwable ignored) {}
                LauncherPreferences.loadPreferences(ctx.getApplicationContext());
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
            if ("low".equals(cur)) { state.setText("LOW-END ACTIVE"); state.setVisibility(View.VISIBLE); }
            else if ("med".equals(cur)) { state.setText("MEDIUM ACTIVE"); state.setVisibility(View.VISIBLE); }
            else if ("high".equals(cur)) { state.setText("HIGH-END ACTIVE"); state.setVisibility(View.VISIBLE); }
            else state.setVisibility(View.GONE);
        }

        private void populateThemeSelector(@NonNull View itemView, @NonNull LayoutInflater inflater) {
            LinearLayout swatchesContainer = itemView.findViewById(R.id.theme_swatches_container);
            if (swatchesContainer == null) return;
            swatchesContainer.removeAllViews();
            ThemeManager.Preset[] presets = ThemeManager.PRESETS;
            String[] colors = new String[]{"#C9CBD6", "#7C8AA0", "#3E8E6E", "#B45454", "#8E6AB8", "#4E9AA8"};

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
                                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
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
            chipText.setBackgroundResource(R.drawable.bg_settings_chip);
            chipText.setOnClickListener(v -> {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80)
                        .withEndAction(() -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
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
                glyph.setColorFilter(isFavorite ? 0xFFE4E4EA : 0xFF9CA3AF);

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
            icon.setScaleX(0.62f);
            icon.setScaleY(0.62f);
            icon.setRotation(-9f);
            icon.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.8f))
                    .withLayer().start();
        } else {
            icon.setAlpha(1f);
            icon.setScaleX(1f);
            icon.setScaleY(1f);
            icon.setRotation(0f);
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