package net.kdt.pojavlaunch.fragments;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.worlds.WorldEntry;
import net.kdt.pojavlaunch.worlds.WorldOps;

import java.io.File;

/**
 * DATAPACK BROWSER (Phase 2) — browse/search/install Modrinth datapacks
 * straight into one world's {@code datapacks/} folder. Reuses the store's
 * DownloadListFragment + version picker + install page pipeline, with the
 * install-context override ({@link DownloadListFragment#setInstallContextOverride})
 * so cards show Installed / Update Available against the world's own dir.
 */
public class DatapackBrowserFragment extends Fragment {

    public static final String TAG = "DATAPACK_BROWSER_FRAGMENT";
    public static final String BUNDLE_WORLD_DIR = "dpb_world_dir";
    public static final String BUNDLE_WORLD_NAME = "dpb_world_name";
    public static final String BUNDLE_WORLD_FOLDER = "dpb_world_folder";
    public static final String BUNDLE_PROFILE_KEY = "dpb_profile_key";

    private static final String[] SORT_INDEXES = {"relevance", "downloads", "newest", "updated"};

    private File mWorldDir;
    private File mDatapackDir;
    private String mWorldFolder;
    private String mWorldName;
    private String mProfileKey;
    private String mVersionFilter;

    private DownloadListFragment mList;
    private TextView mInstalledChip;
    private TextView mVersionChip;
    private final TextView[] mSortChips = new TextView[4];
    private int mSelectedSort = 0;

    private final Handler mSearchHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSearchRunnable = this::runSearch;

    public DatapackBrowserFragment() {
        super(R.layout.fragment_datapack_browser);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            String dir = args.getString(BUNDLE_WORLD_DIR);
            mWorldDir = dir != null ? new File(dir) : null;
            mWorldName = args.getString(BUNDLE_WORLD_NAME);
            mWorldFolder = args.getString(BUNDLE_WORLD_FOLDER);
            mProfileKey = args.getString(BUNDLE_PROFILE_KEY);
        }
        if (mWorldDir != null) mDatapackDir = new File(mWorldDir, "datapacks");
        if (mWorldFolder == null && mWorldDir != null) mWorldFolder = mWorldDir.getName();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ((TextView) view.findViewById(R.id.dpb_subtitle)).setText(
                mWorldName != null ? mWorldName : mWorldFolder);
        ((TextView) view.findViewById(R.id.dpb_footer_path)).setText(
                getString(R.string.cs_dp_install_path,
                        mWorldDir != null ? mDatapackDir.getAbsolutePath() : "datapacks/"));
        mInstalledChip = view.findViewById(R.id.dpb_installed_chip);

        view.findViewById(R.id.dpb_back).setOnClickListener(v -> navigateBack());

        // Sort chips
        mSortChips[0] = view.findViewById(R.id.dpb_sort_relevance);
        mSortChips[1] = view.findViewById(R.id.dpb_sort_popular);
        mSortChips[2] = view.findViewById(R.id.dpb_sort_new);
        mSortChips[3] = view.findViewById(R.id.dpb_sort_updated);
        for (int i = 0; i < mSortChips.length; i++) {
            final int idx = i;
            if (mSortChips[i] == null) continue;
            mSortChips[i].setOnClickListener(v -> selectSort(idx));
            UiMotion.pressFeedback(mSortChips[i]);
        }
        updateSortStyles();

        // MC version filter chip
        mVersionChip = view.findViewById(R.id.dpb_version_chip);
        UiMotion.pressFeedback(mVersionChip);
        mVersionChip.setOnClickListener(v -> showVersionFilterDialog());

        // Search with a small debounce so typing never spams the API.
        ((EditText) view.findViewById(R.id.dpb_search_input))
                .addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void afterTextChanged(Editable s) {
                        mSearchHandler.removeCallbacks(mSearchRunnable);
                        mSearchHandler.postDelayed(mSearchRunnable, 350);
                    }
                });

        // The store list, datapack-flavoured.
        mList = DownloadListFragment.newInstance("datapack");
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.dpb_list_container, mList, "dpb_list")
                .commitNow();
        mList.setProfileKey(mProfileKey);
        if (mDatapackDir != null) {
            mList.setInstallContextOverride(mWorldFolder, mDatapackDir);
        }
        mList.setOnModItemClickListener(this::openVersionPicker);

        refreshInstalledCount();
        UiMotion.revealScreen(view);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshInstalledCount();
    }

    @Override
    public void onDestroyView() {
        mSearchHandler.removeCallbacks(mSearchRunnable);
        super.onDestroyView();
    }

    // ══════════════════════ UI ══════════════════════

    private void runSearch() {
        if (mList == null || !isAdded()) return;
        View v = getView();
        if (v == null) return;
        EditText input = v.findViewById(R.id.dpb_search_input);
        String q = input.getText() != null ? input.getText().toString() : "";
        mList.filter(q, mVersionFilter, null);
    }

    private void selectSort(int idx) {
        mSelectedSort = idx;
        updateSortStyles();
        if (mList != null) mList.setSortIndex(SORT_INDEXES[idx]);
    }

    private void updateSortStyles() {
        for (int i = 0; i < mSortChips.length; i++) {
            TextView chip = mSortChips[i];
            if (chip == null) continue;
            boolean sel = i == mSelectedSort;
            chip.setBackgroundResource(sel ? R.drawable.bg_cs_pill_active : R.drawable.bg_cs_pill_idle);
            chip.setTextColor(sel ? 0xFF0D0D0D : 0xFF9CA3AF);
        }
    }

    private void showVersionFilterDialog() {
        if (getContext() == null) return;
        final EditText input = new EditText(getContext());
        input.setHint(R.string.cs_dp_version_hint);
        if (mVersionFilter != null) input.setText(mVersionFilter);
        input.setSelection(input.getText() != null ? input.getText().length() : 0);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF6B7280);
        input.setBackgroundResource(R.drawable.bg_cs_input_field);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setPadding(pad, pad, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cs_dp_version_title)
                .setView(wrap)
                .setPositiveButton(R.string.global_select, (d, w) -> {
                    String v = input.getText() != null ? input.getText().toString().trim() : "";
                    mVersionFilter = v.isEmpty() ? null : v;
                    mVersionChip.setText(mVersionFilter != null
                            ? mVersionFilter : getString(R.string.cs_dp_version_all));
                    runSearch();
                })
                .setNeutralButton(R.string.cs_dp_version_all, (d, w) -> {
                    mVersionFilter = null;
                    mVersionChip.setText(R.string.cs_dp_version_all);
                    runSearch();
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    private void refreshInstalledCount() {
        if (mWorldDir == null || mInstalledChip == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            int n = WorldOps.countDatapacks(new WorldEntry(mWorldDir));
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (mInstalledChip == null) return;
                if (n > 0) {
                    mInstalledChip.setText(getResources().getQuantityString(
                            R.plurals.cs_dp_installed_count, n, n));
                    mInstalledChip.setVisibility(View.VISIBLE);
                } else {
                    mInstalledChip.setVisibility(View.GONE);
                }
            });
        });
    }

    // ══════════════════════ NAV ══════════════════════

    private void openVersionPicker(@NonNull ModItem item) {
        Bundle args = new Bundle();
        args.putSerializable("mod_item", item);
        args.putString("content_type", "datapack");
        args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, mProfileKey);
        if (mDatapackDir != null) {
            args.putString(ModInstallFragment.ARG_TARGET_DIR, mDatapackDir.getAbsolutePath());
            args.putString(ModInstallFragment.ARG_INSTALL_KEY, mWorldFolder);
        }

        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(
                    ModVersionPickerFragment.class, ModVersionPickerFragment.TAG, args);
        } else if (parent != null) {
            parent.getChildFragmentManager().beginTransaction()
                    .setCustomAnimations(
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[0],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[1],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[2],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[3])
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, ModVersionPickerFragment.class,
                            args, ModVersionPickerFragment.TAG)
                    .addToBackStack(ModVersionPickerFragment.TAG)
                    .commit();
        } else {
            Tools.swapFragment(getActivity(), ModVersionPickerFragment.class,
                    ModVersionPickerFragment.TAG, args);
        }
    }

    private void navigateBack() {
        Fragment parent = getParentFragment();
        if (parent != null) {
            FragmentManager fm = parent.getChildFragmentManager();
            if (fm.getBackStackEntryCount() > 0) {
                fm.popBackStack();
                return;
            }
        }
        if (getActivity() != null) getActivity().getSupportFragmentManager().popBackStack();
    }
}
