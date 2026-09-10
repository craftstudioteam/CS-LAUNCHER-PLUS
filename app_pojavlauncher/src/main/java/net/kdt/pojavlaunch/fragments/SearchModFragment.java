package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

public class SearchModFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    public static final String TAG = "SearchModFragment";
    private View mOverlay;

    private EditText mSearchEditText;
    private ImageButton mFilterButton;

    /** Back navigation for both the header back button and the system back key. */
    private void goBack() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).refreshHomeState();
        } else if (parent != null) {
            parent.getChildFragmentManager().popBackStackImmediate();
        } else {
            Tools.removeCurrentFragment(requireActivity());
        }
    }
    private RecyclerView mRecyclerview;
    private ModItemAdapter mModItemAdapter;
    private ProgressBar mSearchProgressBar;
    private TextView mStatusTextView;
    private ColorStateList mDefaultTextColor;

    private ModpackApi modpackApi;

    private final SearchFilters mSearchFilters;
    /** Phase 11 (item 7): rows + scroll offset kept while the list view is away. */
    private ModItemAdapter.SavedState mRetainedRows;
    private android.os.Parcelable mRetainedLayoutState;
    /** True between onViewCreated() and onViewStateRestored() — see the TextWatcher. */
    private boolean mRestoringViewState;

    public SearchModFragment(){
        super(R.layout.fragment_mod_search);
        mSearchFilters = new SearchFilters();
        mSearchFilters.isModpack = true;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        modpackApi = new ModpackSearchApi(context.getString(R.string.curseforge_api_key), mSearchFilters);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // You can only access resources after attaching to current context
        mModItemAdapter = new ModItemAdapter(getResources(), modpackApi, this);
        ProgressKeeper.addTaskCountListener(mModItemAdapter);

        mOverlay = view.findViewById(R.id.mod_store_header);
        mSearchEditText = view.findViewById(R.id.search_mod_edittext);
        mSearchProgressBar = view.findViewById(R.id.search_mod_progressbar);
        mRecyclerview = view.findViewById(R.id.search_mod_list);
        mStatusTextView = view.findViewById(R.id.search_mod_status_text);
        mFilterButton = view.findViewById(R.id.search_mod_filter);
        
        // Fix (user report): the back button existed in the layout but was
        // never wired — tapping it did nothing. Same wiring as ModsSearchFragment.
        ImageButton backButton = view.findViewById(R.id.mod_store_back);
        if (backButton != null) {
            backButton.setOnClickListener(v -> goBack());
        }
        // System back key takes the same path (child-pane aware).
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { goBack(); }
                });
        
        if (mSearchFilters.isModpack) {
            TextView title = view.findViewById(R.id.mod_store_title);
            if (title != null) title.setText("Modpacks");
            TextView kicker = view.findViewById(R.id.mod_store_kicker);
            if (kicker != null) kicker.setText("STOREFRONT  ·  MODPACKS");
            mSearchEditText.setHint("Search modpacks...");
        } else {
            TextView kicker = view.findViewById(R.id.mod_store_kicker);
            if (kicker != null) kicker.setText("STOREFRONT  ·  MODS");
        }
        // Phase 9: storefront entrance — hero drops in, search capsule rises, cards stagger via adapter.
        if (mOverlay != null) net.kdt.pojavlaunch.Anime.in(mOverlay, net.kdt.pojavlaunch.Anime.Fx.FADE_DOWN, 0, 460, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        if (mSearchEditText != null && mSearchEditText.getParent() instanceof View)
            net.kdt.pojavlaunch.Anime.in((View) mSearchEditText.getParent(), net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 140, 480, net.kdt.pojavlaunch.Anime.OUT_BACK);

        mDefaultTextColor = mStatusTextView.getTextColors();

        // Phase 9: storefront grid — 2 columns when the pane is wide enough, else 1.
        int paneWidthDp = (int) (getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        final int spans = paneWidthDp >= 640 ? 2 : 1;
        androidx.recyclerview.widget.GridLayoutManager glm = new androidx.recyclerview.widget.GridLayoutManager(getContext(), spans);
        mRecyclerview.setLayoutManager(glm);
        mRecyclerview.setAdapter(mModItemAdapter);
        mModItemAdapter.setOnItemClickListener(item -> {
            Bundle args = new Bundle();
            args.putSerializable("mod_item", item);

            // Profile-based download: read profile info passed from ProfileEditorFragment
            Bundle parentArgs = getArguments();
            String profileKey = parentArgs != null ? parentArgs.getString("profile_key") : null;
            String gameDir = parentArgs != null ? parentArgs.getString("game_dir") : null;
            String targetFolder = parentArgs != null ? parentArgs.getString("target_folder") : "mods";

            args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, profileKey);
            args.putString("profile_game_dir", gameDir);
            args.putString("profile_target_folder", targetFolder);
            if (mSearchFilters.isModpack) {
                args.putString("content_type", "modpack");
            }
            Fragment parent = getParentFragment();
            if (parent instanceof MainMenuFragment) {
                ((MainMenuFragment) parent).openChildPane(ModVersionPickerFragment.class, ModVersionPickerFragment.TAG, args);
            } else if (parent != null) {
                parent.getChildFragmentManager().beginTransaction()
                        .setCustomAnimations(
                                net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[0],
                                net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[1],
                                net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[2],
                                net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[3])
                        .setReorderingAllowed(true)
                        .replace(R.id.right_pane_container, ModVersionPickerFragment.class, args, ModVersionPickerFragment.TAG)
                        .addToBackStack(ModVersionPickerFragment.TAG)
                        .commit();
            } else {
                Tools.swapFragment(requireActivity(), ModVersionPickerFragment.class, ModVersionPickerFragment.TAG, args);
            }
        });

        // Real-time search via TextWatcher with debounce
        Handler mSearchHandler = new Handler(Looper.getMainLooper());
        mSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}

            private Runnable searchRunnable;
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) mSearchHandler.removeCallbacks(searchRunnable);
                // Restored text (back from a modpack page) is not a new search.
                if (mRestoringViewState) return;
                final String text = s.toString();
                searchRunnable = () -> searchMods(text);
                mSearchHandler.postDelayed(searchRunnable, 400);
            }
        });

        mSearchEditText.setOnEditorActionListener((v, actionId, event) -> {
            mSearchHandler.removeCallbacksAndMessages(null);
            searchMods(mSearchEditText.getText().toString());
            mSearchEditText.clearFocus();
            return false;
        });

        mFilterButton.setOnClickListener(v -> displayFilterDialog());

        mRestoringViewState = true;
        // Back from a modpack page: same rows, same scroll offset, no new query.
        if (!restoreRetainedRows()) searchMods(null);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        mRestoringViewState = false;
    }

    /** Puts the rows captured in onDestroyView() back; false when there is nothing to restore. */
    private boolean restoreRetainedRows() {
        ModItemAdapter.SavedState rows = mRetainedRows;
        android.os.Parcelable layoutState = mRetainedLayoutState;
        mRetainedRows = null;
        mRetainedLayoutState = null;
        if (rows == null || mModItemAdapter == null || mRecyclerview == null) return false;
        if (!rows.matches(mSearchFilters.name, mSearchFilters.mcVersion,
                mSearchFilters.modLoader, mSearchFilters.sortIndex)) return false;
        if (!mModItemAdapter.restoreState(rows)) return false;
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
        if (layoutState != null) {
            RecyclerView.LayoutManager lm = mRecyclerview.getLayoutManager();
            if (lm != null) {
                try { lm.onRestoreInstanceState(layoutState); } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        hidePlayPanel(true);
    }

    @Override
    public void onPause() {
        super.onPause();
        hidePlayPanel(false);
    }

    private void hidePlayPanel(boolean hide) {
        if (getActivity() == null) return;
        View bottomBar = getActivity().findViewById(R.id.bottom_bar);
        if (bottomBar != null) bottomBar.setVisibility(hide ? View.GONE : View.VISIBLE);
        View sidePanel = getActivity().findViewById(R.id.right_pane_container);
        if (sidePanel != null) {
            ViewGroup.LayoutParams lp = sidePanel.getLayoutParams();
            if (lp instanceof androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
                ((androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) lp).bottomToBottom = hide
                        ? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                        : R.id.bottom_bar;
                sidePanel.requestLayout();
            }
        }
    }

    @Override
    public void onDestroyView() {
        // Phase 11 (item 7): keep what is on screen for the next onViewCreated().
        try {
            mRetainedRows = mModItemAdapter != null ? mModItemAdapter.saveState() : null;
            RecyclerView.LayoutManager lm = mRecyclerview != null ? mRecyclerview.getLayoutManager() : null;
            mRetainedLayoutState = (mRetainedRows != null && lm != null) ? lm.onSaveInstanceState() : null;
        } catch (Throwable t) {
            mRetainedRows = null;
            mRetainedLayoutState = null;
        }
        super.onDestroyView();
        ProgressKeeper.removeTaskCountListener(mModItemAdapter);
    }

    private boolean isUiReady() {
        return isAdded() && getContext() != null && getView() != null;
    }

    @Override
    public void onSearchFinished() {
        if (!isUiReady()) return;
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        if (!isUiReady()) return;
        mSearchProgressBar.setVisibility(View.GONE);
        mStatusTextView.setVisibility(View.VISIBLE);
        switch (error) {
            case ERROR_INTERNAL:
                mStatusTextView.setTextColor(Color.RED);
                mStatusTextView.setText(R.string.search_modpack_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusTextView.setTextColor(mDefaultTextColor);
                mStatusTextView.setText(R.string.search_modpack_no_result);
                break;
        }
    }

    private void searchMods(String name) {
        mSearchProgressBar.setVisibility(View.VISIBLE);
        mSearchFilters.name = name == null ? "" : name;
        mModItemAdapter.performSearchQuery(mSearchFilters);
    }

    private void displayFilterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(R.layout.dialog_mod_filters)
                .create();

        // setup the view behavior
        dialog.setOnShowListener(dialogInterface -> {
            TextView mSelectedVersion = dialog.findViewById(R.id.search_mod_selected_mc_version_textview);
            Button mSelectVersionButton = dialog.findViewById(R.id.search_mod_mc_version_button);
            Button mApplyButton = dialog.findViewById(R.id.search_mod_apply_filters);
            Spinner mLoaderSpinner = dialog.findViewById(R.id.search_mod_loader_spinner);

            assert mSelectVersionButton != null;
            assert mSelectedVersion != null;
            assert mApplyButton != null;

            // Set up loader spinner
            if (mLoaderSpinner != null) {
                String[] loaderLabels = {"Any loader", "Fabric", "Forge", "Quilt", "NeoForge"};
                final String[] loaderValues = {"", "fabric", "forge", "quilt", "neoforge"};
                ArrayAdapter<String> loaderAdapter = new ArrayAdapter<>(
                        requireContext(), android.R.layout.simple_spinner_item, loaderLabels);
                loaderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                mLoaderSpinner.setAdapter(loaderAdapter);

                // Restore current selection
                String currentLoader = mSearchFilters.modLoader != null ? mSearchFilters.modLoader : "";
                for (int i = 0; i < loaderValues.length; i++) {
                    if (loaderValues[i].equals(currentLoader)) {
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
                    mSearchFilters.modLoader = loaderValues[pos];
                    searchMods(mSearchEditText.getText().toString());
                    dialogInterface.dismiss();
                });
            } else {
                mSelectVersionButton.setOnClickListener(v ->
                        VersionSelectorDialog.open(v.getContext(), true,
                                (id, snapshot) -> mSelectedVersion.setText(id)));
                mSelectedVersion.setText(mSearchFilters.mcVersion);
                mApplyButton.setOnClickListener(v -> {
                    mSearchFilters.mcVersion = mSelectedVersion.getText().toString();
                    searchMods(mSearchEditText.getText().toString());
                    dialogInterface.dismiss();
                });
            }
        });

        dialog.show();
    }

    // ── ModpackSearchApi ──────────────────────────────────────────────────────

    private static class ModpackSearchApi extends CommonApi {
        private final SearchFilters mFilters;
        private final ModrinthApi mModrinthApi = new ModrinthApi();

        ModpackSearchApi(String curseforgeApiKey, SearchFilters filters) {
            super(curseforgeApiKey);
            mFilters = filters;
        }

        /**
         * Override getModDetails so the version dropdown only shows versions
         * matching the selected MC version and loader filter.
         */
        @Override
        public ModDetail getModDetails(ModItem item) {
            if (item.apiSource == Constants.SOURCE_MODRINTH) {
                String filterVer = (mFilters.mcVersion != null && !mFilters.mcVersion.isEmpty())
                        ? mFilters.mcVersion : null;
                String filterLoader = (mFilters.modLoader != null && !mFilters.modLoader.isEmpty())
                        ? mFilters.modLoader : null;
                return mModrinthApi.getModDetails(item, filterVer, filterLoader);
            }
            // CurseForge: delegate normally (CF search already filters by version/loader)
            return super.getModDetails(item);
        }
    }
}