package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.viewpager2.widget.ViewPager2;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

public class ModsSearchFragment extends Fragment {

    public static final String TAG = "ModsSearchFragment";

    private static final String[] TAB_TITLES = {"Mods", "Resource Packs", "Shaders"};
    private static final String[] TAB_TYPES  = {"mod", "resourcepack", "shader"};

    private EditText mSearchEditText;
    private ImageButton mFilterButton;
    private View mFilterDot;
    private ViewPager2 mViewPager;
    private DownloadTabAdapter mTabAdapter;
    private LinearLayout mTabBar;
    private View mTabIndicator;
    private HorizontalScrollView mTabScroll;

    private int mCurrentTab = 0;
    private final SearchFilters mSearchFilters = new SearchFilters();
    private String mProfileKey;

    private final Handler mSearchHandler = new Handler(Looper.getMainLooper());
    private String mPendingSearchQuery = "";

    // Reusable Runnable for debounced search — avoids allocation per keystroke
    private final Runnable mSearchRunnable = () -> {
        DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
        if (dlf != null) {
            dlf.filter(mPendingSearchQuery, mSearchFilters.mcVersion, mSearchFilters.modLoader);
        }
    };

    // Cached filter dialog arrays — avoid allocation on every dialog open
    private static final String[] LOADER_LABELS = {"Any loader", "Fabric", "Forge", "Quilt", "NeoForge"};
    private static final String[] LOADER_VALUES = {"", "fabric", "forge", "quilt", "neoforge"};

    /** Modrinth orderings, exposed as one-tap chips under the category tabs. */
    private static final String[] SORT_LABELS = {"Relevance", "Most downloaded", "Newest", "Recently updated", "Most followed"};
    private static final String[] SORT_VALUES = {"relevance", "downloads", "newest", "updated", "follows"};
    private int mSortIndex = 0;
    private LinearLayout mSortBar;

    // Stored reference to lifecycle callback so it can be unregistered in onDestroyView
    private FragmentManager.FragmentLifecycleCallbacks mFragmentLifecycleCallbacks;
    /**
     * Phase 11 (item 7): true between onViewCreated() and onViewStateRestored().
     * Restoring the search field's text (coming back from a mod page) fires the
     * TextWatcher exactly like typing does; without this gate the debounced
     * search re-ran page 1 and the list jumped back to the top.
     */
    private boolean mRestoringViewState;

    public ModsSearchFragment() {
        super(R.layout.fragment_mod_search_tabbed);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mProfileKey = getArguments() != null
                ? getArguments().getString(ManageModsFragment.BUNDLE_PROFILE_KEY) : null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        mFilterDot = view.findViewById(R.id.search_mod_filter_dot);
        mViewPager = view.findViewById(R.id.download_view_pager);
        mTabBar = view.findViewById(R.id.tab_bar);
        mTabIndicator = view.findViewById(R.id.tab_indicator);
        mTabScroll = view.findViewById(R.id.tab_scroll);
        mSortBar = view.findViewById(R.id.sort_chip_bar);
        buildSortChips();

        ImageButton backButton = view.findViewById(R.id.mod_store_back);
        backButton.setOnClickListener(v -> {
            Fragment parent = getParentFragment();
            if (parent instanceof MainMenuFragment) {
                ((MainMenuFragment) parent).refreshHomeState();
            } else if (parent != null) {
                parent.getChildFragmentManager().popBackStackImmediate();
            } else {
                Tools.removeCurrentFragment(requireActivity());
            }
        });
        UiMotion.pressFeedback(backButton, mFilterButton);

        setupTabs();

        mTabAdapter = new DownloadTabAdapter(this, TAB_TYPES);
        mViewPager.setAdapter(mTabAdapter);
        mViewPager.setOffscreenPageLimit(2);
        mViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                mCurrentTab = position;
                updateTabSelection(position);
                animateTabContent(position);
                String query = mSearchEditText.getText().toString().trim();
                DownloadListFragment dlf = getListFragment(TAB_TYPES[position]);
                if (dlf != null && !query.isEmpty()) {
                    // Only when this tab is not already showing that query — a
                    // pager restore re-selects the page and must not reload it.
                    dlf.filterIfChanged(query, mSearchFilters.mcVersion, mSearchFilters.modLoader);
                }
            }
        });

        ImageButton clearButton = view.findViewById(R.id.search_clear_button);
        mSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mPendingSearchQuery = s.toString();
                if (clearButton != null) {
                    clearButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
                mSearchHandler.removeCallbacks(mSearchRunnable);
                if (mRestoringViewState) return;   // text came back from saved state, not from the user
                mSearchHandler.postDelayed(mSearchRunnable, 400);
            }
        });
        mRestoringViewState = true;

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                mSearchEditText.setText("");
                mPendingSearchQuery = "";
                mSearchHandler.removeCallbacksAndMessages(null);
                DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
                if (dlf != null) {
                    dlf.filter("", mSearchFilters.mcVersion, mSearchFilters.modLoader);
                }
                v.animate().cancel();
                v.setScaleX(0.7f); v.setScaleY(0.7f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
            });
        }

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            mSearchHandler.removeCallbacksAndMessages(null);
            DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
            if (dlf != null) {
                dlf.filter(mSearchEditText.getText().toString(),
                        mSearchFilters.mcVersion, mSearchFilters.modLoader);
            }
            mSearchEditText.clearFocus();
            return false;
        });

        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        // The header no longer carries a separate SEARCH pill (the field
        // searches while typing and the keyboard action does the rest), but the
        // zero-size view with that id is still in the layout, so both paths stay
        // wired to the same code.
        View searchGoBtn = view.findViewById(R.id.search_button_go);
        if (searchGoBtn != null) {
            UiMotion.pressFeedback(searchGoBtn);
            searchGoBtn.setOnClickListener(v -> {
                mSearchHandler.removeCallbacksAndMessages(null);
                DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
                if (dlf != null) {
                    dlf.filter(mSearchEditText.getText().toString(),
                            mSearchFilters.mcVersion, mSearchFilters.modLoader);
                }
                mSearchEditText.clearFocus();
                try {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null && requireActivity().getCurrentFocus() != null) {
                        imm.hideSoftInputFromWindow(requireActivity().getCurrentFocus().getWindowToken(), 0);
                    }
                } catch (Throwable ignored) {}
            });
        }

        View poweredBadge=view.findViewById(R.id.infrawire_powered_badge);if(poweredBadge!=null)poweredBadge.setVisibility(View.GONE);

        mSearchEditText.setHint(getString(R.string.browse_search_hint_typed, "mods"));
        updateFilterDot();

        // Wire up click listeners — handles fragment creation and recreation
        mFragmentLifecycleCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                              @NonNull View v, @Nullable Bundle savedInstanceState) {
                if (f instanceof DownloadListFragment) {
                    DownloadListFragment dlf = (DownloadListFragment) f;
                    dlf.setProfileKey(mProfileKey);
                    dlf.setSortIndex(SORT_VALUES[mSortIndex]);
                    String type = dlf.getContentType();
                    for (int i = 0; i < TAB_TYPES.length; i++) {
                        if (TAB_TYPES[i].equals(type)) {
                            final int tabPos = i;
                            dlf.setOnModItemClickListener(
                                    item -> onModItemClick(item, TAB_TYPES[tabPos]));
                            break;
                        }
                    }
                }
            }
        };
        getChildFragmentManager().registerFragmentLifecycleCallbacks(mFragmentLifecycleCallbacks, true);

        playEntryAnimation(view);
    }

    /** Smooth premium entrance: header slides down, content fades/scales up. */
    /** One-tap ordering. The chosen index is pushed to whichever result list is on screen. */
    /** One quiet control that opens the ordering sheet — five chips were noise. */
    private void buildSortChips() {
        if (mSortBar == null) return;
        mSortBar.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(requireContext());
        chip.setText(getString(R.string.browse_sort_chip, SORT_LABELS[mSortIndex]));
        chip.setTextSize(10f);
        chip.setSingleLine(true);
        chip.setLetterSpacing(0.03f);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setTextColor(Color.parseColor("#C3C6CE"));
        chip.setBackgroundResource(R.drawable.bg_mr_sort_chip);
        int padH = (int) (12 * d);
        int padV = (int) (5 * d);
        chip.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> showSortSheet());
        mSortBar.addView(chip);
    }

    /** Keeps the single sort chip labelled with the ordering actually in use. */
    private void refreshSortChip() {
        if (mSortBar == null) return;
        View chip = mSortBar.getChildAt(0);
        if (chip instanceof TextView) {
            ((TextView) chip).setText(getString(R.string.browse_sort_chip, SORT_LABELS[mSortIndex]));
        }
    }

    private void showSortSheet() {
        AlertDialog sheet = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.browse_sort_title)
                .setSingleChoiceItems(SORT_LABELS, mSortIndex,
                        (dialog, which) -> {
                            applySort(which);
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.global_cancel, null)
                .create();
        sheet.show();
        if (sheet.getWindow() != null) {
            sheet.getWindow().setWindowAnimations(R.style.DialogFadeScale);
        }
    }

    private void applySort(int index) {
        if (index == mSortIndex) return;
        mSortIndex = index;
        refreshSortChip();
        View chip = mSortBar == null ? null : mSortBar.getChildAt(0);
        if (chip != null) {
            chip.animate().cancel();
            chip.setScaleX(0.94f);
            chip.setScaleY(0.94f);
            chip.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                    .start();
        }
        for (String type : TAB_TYPES) {
            DownloadListFragment dlf = getListFragment(type);
            if (dlf != null) dlf.setSortIndex(SORT_VALUES[index]);
        }
    }

    private void playEntryAnimation(@NonNull View root) {
        View header = root.findViewById(R.id.mod_store_header);
        View pager = root.findViewById(R.id.download_view_pager);
        View tabs = root.findViewById(R.id.tab_scroll);
        View search = root.findViewById(R.id.search_capsule_container);

        // anime.js timeline: header drops in → search capsule flips up →
        // tab chips stagger from the left → the grid rises with outExpo.
        net.kdt.pojavlaunch.Anime.in(header, net.kdt.pojavlaunch.Anime.Fx.FADE_DOWN, 0, 520, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(search, net.kdt.pojavlaunch.Anime.Fx.FLIP_UP, 90, 560, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        if (mTabBar != null) net.kdt.pojavlaunch.Anime.stagger(mTabBar, 160, 45, net.kdt.pojavlaunch.Anime.Fx.FADE_RIGHT);
        else net.kdt.pojavlaunch.Anime.in(tabs, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 160, 480, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        if (mSortBar != null) net.kdt.pojavlaunch.Anime.in(mSortBar, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 240, 480, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(pager, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 200, 620, net.kdt.pojavlaunch.Anime.OUT_EXPO);
    }

    private void animateTabContent(int position) {
        if (mViewPager == null) return;
        float d = getResources().getDisplayMetrics().density;
        mViewPager.animate().cancel();
        mViewPager.setAlpha(0.7f);
        mViewPager.setTranslationX((position % 2 == 0 ? 1f : -1f) * 14f * d);
        mViewPager.setScaleX(0.985f);
        mViewPager.setScaleY(0.985f);
        mViewPager.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(net.kdt.pojavlaunch.utils.animation.MotionSpeed.scale(360))
                .setInterpolator(net.kdt.pojavlaunch.Anime.OUT_EXPO).start();
    }

    private void setupTabs() {
        mTabBar.removeAllViews();
        mTabBar.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < TAB_TITLES.length; i++) {
            TextView tab = new TextView(requireContext());
            tab.setText(TAB_TITLES[i]);
            tab.setTextSize(13f);
            tab.setPadding((int) (d * 12), 0, (int) (d * 12), 0);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setIncludeFontPadding(false);
            tab.setBackgroundResource(i == 0 ? R.drawable.bg_mr_tab_active : R.drawable.bg_mr_tab_idle);
            tab.setTextColor(Color.parseColor(i == 0 ? "#FFFFFF" : "#7E818D"));
            tab.setTypeface(null, i == 0 ? Typeface.BOLD : Typeface.NORMAL);
            tab.setTag(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            if (i < TAB_TITLES.length - 1) lp.rightMargin = (int) (d * 2);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(v -> {
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60)
                        .withEndAction(() -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                            mViewPager.setCurrentItem((int) v.getTag(), true);
                        }).start();
            });
            mTabBar.addView(tab);
        }
    }

    private void updateTabSelection(int position) {
        for (int i = 0; i < mTabBar.getChildCount(); i++) {
            TextView tab = (TextView) mTabBar.getChildAt(i);
            boolean active = i == position;
            tab.setBackgroundResource(active ? R.drawable.bg_mr_tab_active : R.drawable.bg_mr_tab_idle);
            tab.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#7E818D"));
            tab.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tab.animate().alpha(active ? 1f : 0.85f).setDuration(140).start();
        }
        View selectedTab = mTabBar.getChildAt(position);
        if (selectedTab != null) {
            mTabScroll.smoothScrollTo(selectedTab.getLeft() - 50, 0);
        }
        // Context-aware search hint
        if (mSearchEditText != null) {
            String type = TAB_TYPES[position];
            String noun;
            switch (type) {
                case "resourcepack": noun = "resource packs"; break;
                case "shader": noun = "shaders"; break;
                case "world": noun = "worlds"; break;
                default: noun = "mods"; break;
            }
            mSearchEditText.setHint(getString(R.string.browse_search_hint_typed, noun));
        }
    }

    private DownloadListFragment getListFragment(String contentType) {
        for (Fragment f : getChildFragmentManager().getFragments()) {
            if (f instanceof DownloadListFragment) {
                DownloadListFragment dlf = (DownloadListFragment) f;
                if (contentType.equals(dlf.getContentType())) {
                    return dlf;
                }
            }
        }
        return null;
    }

    private void onModItemClick(ModItem item, String contentType) {
        navigateToVersionPicker(item, contentType);
    }

    /** Detail page with an auto-selected version + direct Install button. */
    private void navigateToVersionPicker(ModItem item, String contentType) {
        Bundle args = new Bundle();
        args.putSerializable("mod_item", item);
        args.putString("content_type", contentType);
        args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, mProfileKey);

        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(
                    ModDetailFragment.class, ModDetailFragment.TAG, args);
        } else if (parent != null) {
            parent.getChildFragmentManager()
                    .beginTransaction()
                    .setCustomAnimations(
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[0],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[1],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[2],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[3])
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container,
                            ModDetailFragment.class, args, ModDetailFragment.TAG)
                    .addToBackStack(ModDetailFragment.TAG)
                    .commit();
        } else {
            Tools.swapFragment(requireActivity(),
                    ModDetailFragment.class, ModDetailFragment.TAG, args);
        }
    }


    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);
            Button mClearButton = dialog.findViewById(R.id.search_mod_clear_filters);
            Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);

            assert mSelectedVersion != null;
            assert mSelectVersionButton != null;
            assert mApplyButton != null;

            // Clear resets both filters and re-runs the current query
            if (mClearButton != null) {
                mClearButton.setOnClickListener(v -> {
                    mSearchFilters.mcVersion = null;
                    mSearchFilters.modLoader = null;
                    updateFilterDot();
                    DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
                    if (dlf != null) {
                        dlf.filter(mSearchEditText.getText().toString(), null, null);
                    }
                    dialogInterface.dismiss();
                });
            }

            if (mLoaderSpinner != null) {
                android.widget.ArrayAdapter<String> loaderAdapter = new android.widget.ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_spinner_item, LOADER_LABELS);
                loaderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mLoaderSpinner.setAdapter(loaderAdapter);

                String currentLoader = mSearchFilters.modLoader != null ? mSearchFilters.modLoader : "";
                for (int i = 0; i < LOADER_VALUES.length; i++) {
                    if (LOADER_VALUES[i].equals(currentLoader)) {
                        mLoaderSpinner.setSelection(i);
                        break;
                    }
                }

                mSelectVersionButton.setOnClickListener(v ->
                        VersionSelectorDialog.open(v.getContext(), true,
                                (id, snapshot) -> mSelectedVersion.setText(id)));

                mSelectedVersion.setText(mSearchFilters.mcVersion);

                mApplyButton.setOnClickListener(v -> {
                    mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                    int pos = mLoaderSpinner.getSelectedItemPosition();
                    mSearchFilters.modLoader = LOADER_VALUES[pos];
                    updateFilterDot();
                    DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
                    if (dlf != null) {
                        dlf.filter(mSearchEditText.getText().toString(),
                                mSearchFilters.mcVersion, mSearchFilters.modLoader);
                    }
                    dialogInterface.dismiss();
                });
            } else {
                mSelectVersionButton.setOnClickListener(v ->
                        VersionSelectorDialog.open(v.getContext(), true,
                                (id, snapshot) -> mSelectedVersion.setText(id)));

                mSelectedVersion.setText(mSearchFilters.mcVersion);

                mApplyButton.setOnClickListener(v -> {
                    mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                    updateFilterDot();
                    DownloadListFragment dlf = getListFragment(TAB_TYPES[mCurrentTab]);
                    if (dlf != null) {
                        dlf.filter(mSearchEditText.getText().toString(),
                                mSearchFilters.mcVersion, mSearchFilters.modLoader);
                    }
                    dialogInterface.dismiss();
                });
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setWindowAnimations(R.style.DialogFadeScale);
        }
    }

    /** Silver dot on the filter button while any filter is active. */
    private void updateFilterDot() {
        if (mFilterDot == null) return;
        boolean active = (mSearchFilters.mcVersion != null && !mSearchFilters.mcVersion.isEmpty())
                || (mSearchFilters.modLoader != null && !mSearchFilters.modLoader.isEmpty());
        mFilterDot.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        // Hierarchy state (search text, pager page) has been replayed by now.
        mRestoringViewState = false;
        mSearchHandler.removeCallbacks(mSearchRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
        applyInstanceRules();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mSearchHandler.removeCallbacks(mSearchRunnable);
        if (mFragmentLifecycleCallbacks != null) {
            getChildFragmentManager().unregisterFragmentLifecycleCallbacks(mFragmentLifecycleCallbacks);
            mFragmentLifecycleCallbacks = null;
        }
    }

    /**
     * Apply version instance rules for the active profile:
     *  - vanilla profile → block the Mods tab; show only Resource Packs / Shaders / Worlds.
     *  - OptiFine profile → block the Mods tab; show only Resource Packs / Shaders / Worlds.
     *  - everything else → leave all four tabs.
     * If the user has the mod store already open and the rules now ban the Mods tab,
     * the ViewPager is moved to the first allowed tab.
     */
    private void applyInstanceRules() {
        if (!isAdded() || getView() == null) return;
        MinecraftProfile profile = resolveActiveProfile();
        if (profile == null) return;
        boolean isVanilla = profile.isVanilla();
        boolean isOptifine = profile.isOptiFine();
        if (!isVanilla && !isOptifine) return;

        // Mods tab is the first tab; force the ViewPager to the second one (Resource Packs).
        if (mCurrentTab == 0 && mViewPager != null) {
            mViewPager.setCurrentItem(1, false);
            mCurrentTab = 1;
            updateTabSelection(1);
        }
    }

    /** Resolve the active profile either via {@link #mProfileKey} arg or the global pref. */
    private MinecraftProfile resolveActiveProfile() {
        try {
            LauncherProfiles.load();
            String key = mProfileKey;
            if (key == null || key.isEmpty()) {
                key = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF
                        .getString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            }
            if (key == null || key.isEmpty()) return null;
            return LauncherProfiles.mainProfileJson.profiles.get(key);
        } catch (Throwable t) {
            return null;
        }
    }
}
