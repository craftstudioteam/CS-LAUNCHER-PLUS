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

    // Stored reference to lifecycle callback so it can be unregistered in onDestroyView
    private FragmentManager.FragmentLifecycleCallbacks mFragmentLifecycleCallbacks;

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
                String query = mSearchEditText.getText().toString().trim();
                DownloadListFragment dlf = getListFragment(TAB_TYPES[position]);
                if (dlf != null && !query.isEmpty()) {
                    dlf.filter(query, mSearchFilters.mcVersion, mSearchFilters.modLoader);
                }
            }
        });

        mSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mPendingSearchQuery = s.toString();
                mSearchHandler.removeCallbacks(mSearchRunnable);
                mSearchHandler.postDelayed(mSearchRunnable, 400);
            }
        });

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

        View searchGoBtn = view.findViewById(R.id.search_button_go);
        if (searchGoBtn != null) {
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

        // Infrawire Powered Badge in AppBar
        View poweredBadge = view.findViewById(R.id.infrawire_powered_badge);
        if (poweredBadge != null) {
            // Global sponsorship gate (Firebase admin panel).
            net.kdt.pojavlaunch.remote.FirebaseSyncManager.gateSponsorView(poweredBadge);
            net.kdt.pojavlaunch.sponsor.InfrawirePartner.applyPressAnimation(poweredBadge);
            poweredBadge.setOnClickListener(v -> {
                if (getActivity() != null) {
                    net.kdt.pojavlaunch.sponsor.InfrawirePartner.openPartnerPage(getActivity());
                }
            });
        }

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
    private void playEntryAnimation(@NonNull View root) {
        View header = root.findViewById(R.id.mod_store_header);
        View pager = root.findViewById(R.id.download_view_pager);
        View tabs = root.findViewById(R.id.tab_scroll);

        if (header != null) {
            header.setAlpha(0f);
            header.setTranslationY(-40f);
            header.animate().alpha(1f).translationY(0f)
                    .setDuration(320).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (tabs != null) {
            tabs.setAlpha(0f);
            tabs.animate().alpha(1f).setStartDelay(120).setDuration(300).start();
        }
        if (pager != null) {
            pager.setAlpha(0f);
            pager.setScaleX(0.98f);
            pager.setScaleY(0.98f);
            pager.setTranslationY(48f);
            pager.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(90).setDuration(360)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void setupTabs() {
        mTabBar.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < TAB_TITLES.length; i++) {
            TextView tab = new TextView(requireContext());
            tab.setText(TAB_TITLES[i]);
            tab.setTextSize(12.5f);
            tab.setPadding((int) (d * 10), (int) (d * 6), (int) (d * 10), (int) (d * 6));
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setBackgroundResource(i == 0 ? R.drawable.bg_browse_tab_active : R.drawable.bg_browse_tab_idle);
            tab.setTextColor(i == 0 ? Color.parseColor("#0E0E11") : Color.parseColor("#9C9CA8"));
            tab.setTypeface(null, i == 0 ? Typeface.BOLD : Typeface.NORMAL);
            tab.setTag(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f);
            if (i < TAB_TITLES.length - 1) lp.rightMargin = (int) (d * 3);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(v -> {
                v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(60)
                        .withEndAction(() -> {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                            mViewPager.setCurrentItem((int) v.getTag(), true);
                        }).start();
            });
            mTabBar.addView(tab);
        }
        mTabIndicator.post(() -> {
            if (mTabBar.getChildCount() > 0) {
                View firstTab = mTabBar.getChildAt(0);
                firstTab.post(() -> {
                    int w = firstTab.getWidth();
                    if (w > 0) {
                        mTabIndicator.getLayoutParams().width = w;
                        mTabIndicator.requestLayout();
                    }
                });
            }
        });
    }

    private void updateTabSelection(int position) {
        for (int i = 0; i < mTabBar.getChildCount(); i++) {
            TextView tab = (TextView) mTabBar.getChildAt(i);
            boolean active = i == position;
            tab.setBackgroundResource(active ? R.drawable.bg_browse_tab_active : R.drawable.bg_browse_tab_idle);
            tab.setTextColor(Color.parseColor(active ? "#0E0E11" : "#9C9CA8"));
            tab.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            tab.animate().scaleX(active ? 1.02f : 1f).scaleY(active ? 1.02f : 1f).setDuration(140).start();
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

    private void navigateToVersionPicker(ModItem item, String contentType) {
        Bundle args = new Bundle();
        args.putSerializable("mod_item", item);
        args.putString("content_type", contentType);
        args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, mProfileKey);

        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(
                    ModVersionPickerFragment.class, ModVersionPickerFragment.TAG, args);
        } else if (parent != null) {
            parent.getChildFragmentManager()
                    .beginTransaction()
                    .setCustomAnimations(
                            R.anim.fade_in_slide_up, R.anim.fade_out_slide_down,
                            R.anim.fade_in_slide_up, R.anim.fade_out_slide_down)
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container,
                            ModVersionPickerFragment.class, args, ModVersionPickerFragment.TAG)
                    .addToBackStack(ModVersionPickerFragment.TAG)
                    .commit();
        } else {
            Tools.swapFragment(requireActivity(),
                    ModVersionPickerFragment.class, ModVersionPickerFragment.TAG, args);
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
