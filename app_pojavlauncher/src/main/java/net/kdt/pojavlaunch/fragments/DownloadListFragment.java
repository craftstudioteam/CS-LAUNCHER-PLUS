package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.GridSpacingItemDecoration;
import net.kdt.pojavlaunch.modloaders.modpacks.ModItemAdapter;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class DownloadListFragment extends Fragment implements ModItemAdapter.SearchResultCallback {

    private static final String ARG_TYPE = "content_type";

    private String mContentType;
    // ── Phase 2: per-world datapack context + sort ──
    private String mInstallKeyOverride;
    private java.io.File mInstallDirOverride;
    private String mSortIndex = "relevance";
    private String mLastQuery = "";
    private String mLastVersion;
    private String mLastLoader;
    private RecyclerView mRecyclerView;
    /** 2dp line at the top edge of the page — the only in-flight indicator. */
    private ProgressBar mPageProgress;
    private View mSkeletonGrid;
    private View mLoadingCapsule;
    private TextView mStatusText;
    private ModItemAdapter mAdapter;
    private ModpackApi mApi;
    private String mProfileKey;
    /**
     * Phase 11 (item 7): rows + grid scroll offset kept across the list VIEW being
     * destroyed (opening a mod detail page replaces this pane; the fragment
     * instance itself survives on the back stack / in the ViewPager).
     */
    private ModItemAdapter.SavedState mRetainedRows;
    private android.os.Parcelable mRetainedLayoutState;

    private OnModItemClickListener mItemClickListener;

    /** The profile whose installed-content states should be drawn on the cards. */
    public void setProfileKey(String profileKey) {
        mProfileKey = profileKey;
        applyInstallContext();
    }

    /**
     * The progress notification already carries percent/speed/ETA, but on Android 13+
     * it simply does not exist until the user allows notifications — and the launcher
     * asks once, on first start. This strip is the second door: it appears on the page
     * whose downloads the user is actually watching, and never after they said no
     * (the "skip the nag" preference already silences the startup dialog).
     */
    private void setupNotificationBanner(@NonNull View root) {
        View banner = root.findViewById(R.id.download_notif_banner);
        if (banner == null) return;
        boolean needsPermission = android.os.Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
        banner.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        if (!needsPermission) return;
        View btn = banner.findViewById(R.id.download_notif_banner_btn);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                if (getActivity() instanceof net.kdt.pojavlaunch.LauncherActivity) {
                    ((net.kdt.pojavlaunch.LauncherActivity) getActivity())
                            .askForNotificationPermission(() -> {
                                if (!isAdded()) return;
                                View b = getView() == null ? null
                                        : getView().findViewById(R.id.download_notif_banner);
                                if (b != null) b.setVisibility(View.GONE);
                            });
                } else {
                    banner.setVisibility(View.GONE);
                }
            });
        }
        banner.setAlpha(0f);
        banner.setTranslationY(10f * getResources().getDisplayMetrics().density);
        banner.animate().alpha(1f).translationY(0f).setStartDelay(320L).setDuration(260)
                .setInterpolator(new androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .start();
    }

    public interface OnModItemClickListener {
        void onItemClick(ModItem item);
    }

    public void setOnModItemClickListener(OnModItemClickListener listener) {
        mItemClickListener = listener;
    }

    public String getContentType() {
        return mContentType;
    }

    public static DownloadListFragment newInstance(String type) {
        Bundle args = new Bundle();
        args.putString(ARG_TYPE, type);
        DownloadListFragment f = new DownloadListFragment();
        f.setArguments(args);
        return f;
    }

    public DownloadListFragment() {
        super(R.layout.fragment_download_list);
    }

    /** True only while it is safe to touch views/resources/activity. */
    private boolean isUiReady() {
        if (!isAdded() || getContext() == null || getActivity() == null) return false;
        if (getView() == null || mRecyclerView == null) return false;
        try {
            return getViewLifecycleOwner().getLifecycle().getCurrentState()
                    .isAtLeast(Lifecycle.State.INITIALIZED);
        } catch (IllegalStateException e) {
            return false;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mContentType = getArguments() != null ? getArguments().getString(ARG_TYPE, "mod") : "mod";

        mRecyclerView = view.findViewById(R.id.download_list);
        mStatusText = view.findViewById(R.id.download_list_status);
        setupNotificationBanner(view);
        mSkeletonGrid = view.findViewById(R.id.download_skeleton_grid);
        mLoadingCapsule = view.findViewById(R.id.download_loading_capsule);
        mPageProgress = findPageProgress();

        // Two-column discovery grid; every card has a fixed height so the
        // columns stay aligned while artwork streams in.
        //
        // Predictive animations lay the previous dataset out before an update is
        // applied. Combined with a whole-dataset replace that is how a recycled
        // holder ends up at a position past the new end of the list, so they are
        // off for this grid — the cards keep their own entrance motion.
        GridLayoutManager grid = new GridLayoutManager(getContext(), 2) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        };
        mRecyclerView.setLayoutManager(grid);
        mRecyclerView.addItemDecoration(new GridSpacingItemDecoration(
                2, (int) (6 * getResources().getDisplayMetrics().density), true));
        // CRASH GUARD (java.lang.IndexOutOfBoundsException: Inconsistency
        // detected. Invalid view holder adapter position): the grid fills its
        // parent, so a "fixed size" measurement is a lie the moment a whole
        // dataset is swapped, and a row entrance LayoutAnimation replaying
        // across that swap validates recycled holders against the old count.
        // Neither is needed — the cards animate themselves.
        mRecyclerView.setHasFixedSize(false);
        // Keep result updates fluid without the default cross-fade blinking every
        // installed-state refresh. Entrance motion is handled once by the adapter.
        androidx.recyclerview.widget.DefaultItemAnimator animator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setAddDuration(180L);
        animator.setRemoveDuration(140L);
        animator.setMoveDuration(180L);
        mRecyclerView.setItemAnimator(animator);

        // Use ModrinthApi directly for non-standard types (CF doesn't support them)
        if (mContentType.equals("mod")) {
            mApi = new CommonApi(requireContext().getString(R.string.curseforge_api_key));
        } else {
            mApi = new ModrinthApi();
        }

        mAdapter = new ModItemAdapter(getResources(), mApi, this);
        mRecyclerView.setAdapter(mAdapter);
        GridLayoutManager gridLayout = (GridLayoutManager) mRecyclerView.getLayoutManager();
        if (gridLayout != null) {
            // Every row is a card now (the load-more footer row is gone), so
            // each one spans a single column — the lookup stays only so a future
            // full-width row has an obvious place to declare itself.
            gridLayout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override public int getSpanSize(int position) {
                    ModItemAdapter a = mAdapter;
                    if (a == null || position < 0 || position >= a.getItemCount()) return 1;
                    return 1;
                }
            });
        }
        applyInstallContext();

        mAdapter.setOnItemClickListener(item -> {
            if (mItemClickListener != null && isUiReady()) {
                mItemClickListener.onItemClick(item);
            }
        });

        // Paging is a scroll event, not a row in the list. The old spinner row
        // existed only so onBind could trigger the next page — and that row was
        // the exact position that went stale in the crash.
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                ModItemAdapter adapter = mAdapter;
                if (adapter != null) adapter.loadMoreIfNeeded(6);
            }
        });

        if (!restoreRetainedRows()) loadContent();
    }

    /**
     * Coming back from a detail page: put the previous rows back (no network,
     * no skeleton, no entrance motion) and scroll to where the user was. Only
     * rows that answer the current query/version/loader/sort are reused — a
     * filter changed while we were away means a real reload instead.
     */
    private boolean restoreRetainedRows() {
        ModItemAdapter.SavedState rows = mRetainedRows;
        android.os.Parcelable layoutState = mRetainedLayoutState;
        mRetainedRows = null;
        mRetainedLayoutState = null;
        if (rows == null || mAdapter == null || mRecyclerView == null) return false;
        if (!rows.matches(mLastQuery, mLastVersion, mLastLoader, mSortIndex)) return false;
        if (!mAdapter.restoreState(rows)) return false;
        if (mPageProgress != null) mPageProgress.setVisibility(View.GONE);
        if (mStatusText != null) mStatusText.setVisibility(View.GONE);
        if (layoutState != null) {
            RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
            if (lm != null) {
                try { lm.onRestoreInstanceState(layoutState); } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    /** The page-level progress line lives in the parent fragment's layout. */
    private ProgressBar findPageProgress() {
        Fragment parent = getParentFragment();
        View root = parent != null ? parent.getView() : null;
        if (root == null || !(parent instanceof ModsSearchFragment)) return null;
        View v = root.findViewById(R.id.browse_progress_line);
        return v instanceof ProgressBar ? (ProgressBar) v : null;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Pick up installs/removals that happened while we were away.
        applyInstallContext();
        if (mAdapter != null) mAdapter.refreshInstallStates();
    }

    /** World Manager datapacks: exact install slot instead of profile resolution. */
    public void setInstallContextOverride(String key, java.io.File dir) {
        mInstallKeyOverride = key;
        mInstallDirOverride = dir;
        applyInstallContext();
    }

    /** Modrinth sort index (relevance / downloads / newest / updated / follows). */
    public void setSortIndex(String index) {
        if (index == null || index.isEmpty()) index = "relevance";
        if (index.equals(mSortIndex)) return;
        mSortIndex = index;
        // re-run with the same query + filters under the new ordering
        filter(mLastQuery, mLastVersion, mLastLoader);
    }

    public String getSortIndex() { return mSortIndex; }

    /** Resolve the target profile + its content directory for state rendering. */
    private void applyInstallContext() {
        if (mAdapter == null || getContext() == null) return;
        if (mInstallDirOverride != null) {
            mAdapter.setInstallContext(
                    mInstallKeyOverride != null ? mInstallKeyOverride : mProfileKey,
                    mContentType, mInstallDirOverride);
            if (mAdapter != null) mAdapter.refreshInstallStates();
            return;
        }
        String key = mProfileKey;
        if (key == null || key.isEmpty()) {
            key = LauncherPreferences.DEFAULT_PREF.getString(
                    LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        }
        File contentDir = null;
        if (key != null && !key.isEmpty()) {
            try {
                LauncherProfiles.load();
                MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
                if (profile != null) {
                    File gameDir = Tools.getGameDirPath(profile);
                    String sub;
                    switch (mContentType == null ? "mod" : mContentType) {
                        case "resourcepack": sub = "resourcepacks"; break;
                        case "shader": sub = "shaderpacks"; break;
                        case "world": sub = "saves"; break;
                        default: sub = "mods"; break;
                    }
                    contentDir = new File(gameDir, sub);
                } else {
                    key = null;
                }
            } catch (Exception ignored) {
                key = null;
            }
        }
        mAdapter.setInstallContext(key, mContentType, contentDir);
    }

    @Override
    public void onDestroyView() {
        // Phase 11 (item 7): remember what is on screen before the view goes,
        // so the next onViewCreated() can put it straight back.
        try {
            mRetainedRows = mAdapter != null ? mAdapter.saveState() : null;
            RecyclerView.LayoutManager lm = mRecyclerView != null ? mRecyclerView.getLayoutManager() : null;
            mRetainedLayoutState = (mRetainedRows != null && lm != null) ? lm.onSaveInstanceState() : null;
        } catch (Throwable t) {
            mRetainedRows = null;
            mRetainedLayoutState = null;
        }
        // Cancel every pending animation / callback before views are torn down.
        if (mStatusText != null) mStatusText.animate().cancel();
        if (mPageProgress != null) mPageProgress.setVisibility(View.GONE);
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(null);
        }
        mAdapter = null;
        mRecyclerView = null;
        mPageProgress = null;
        mStatusText = null;
        super.onDestroyView();
    }

    private void loadContent() {
        if (!isUiReady()) return;
        SearchFilters filters = buildFilters("");
        beginLoading();
        if (mAdapter != null) mAdapter.performSearchQuery(filters);
    }

    public void filter(String query) {
        filter(query, null, null);
    }

    /**
     * Phase 11 (item 7): tab switches and pager state restores push the search
     * box's text at every list; only a list that is NOT already showing that
     * exact query re-runs it, so the rows (and scroll offset) survive.
     */
    public void filterIfChanged(String query, @Nullable String mcVersion, @Nullable String modLoader) {
        String q = query != null ? query : "";
        boolean same = q.equals(mLastQuery)
                && java.util.Objects.equals(emptyToNull(mcVersion), emptyToNull(mLastVersion))
                && java.util.Objects.equals(emptyToNull(modLoader), emptyToNull(mLastLoader));
        if (same && mAdapter != null && mAdapter.getItemCount() > 0) return;
        filter(query, mcVersion, modLoader);
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    public void filter(String query, @Nullable String mcVersion, @Nullable String modLoader) {
        if (!isUiReady()) return;
        mLastQuery = query != null ? query : "";
        mLastVersion = mcVersion;
        mLastLoader = modLoader;
        SearchFilters filters = buildFilters(mLastQuery);
        filters.mcVersion = mcVersion != null && !mcVersion.isEmpty() ? mcVersion : null;
        filters.modLoader = modLoader != null && !modLoader.isEmpty() ? modLoader : null;
        beginLoading();
        if (mAdapter != null) mAdapter.performSearchQuery(filters);
    }

    /**
     * One indicator for the whole page: skeleton placeholders while the grid has
     * nothing to show, and the top-edge line while a query runs over existing
     * rows. A list that also grows a spinner row (plus a floating capsule on top
     * of the cards) reads as busy — and that row was the crash.
     */
    private void beginLoading() {
        if (mPageProgress != null) mPageProgress.setVisibility(View.VISIBLE);
        if (mStatusText != null) mStatusText.setVisibility(View.GONE);
        boolean empty = mAdapter == null || mAdapter.getItemCount() == 0;
        if (empty) showSkeleton();
        showCapsule(empty ? "SEARCHING" : "LOADING MORE");
    }

    private void endLoading() {
        if (mPageProgress != null) mPageProgress.setVisibility(View.GONE);
        hideSkeleton();
        hideCapsule();
    }

    /** Floating "SEARCHING ···" pill: drops in with a spring, three dots bounce in a stagger. */
    private void showCapsule(String label) {
        if (mLoadingCapsule == null) return;
        TextView tv = mLoadingCapsule.findViewById(R.id.download_loading_label);
        if (tv != null && !label.contentEquals(tv.getText())) tv.setText(label);
        if (mLoadingCapsule.getVisibility() == View.VISIBLE) return;
        float d = getResources().getDisplayMetrics().density;
        mLoadingCapsule.animate().cancel();
        mLoadingCapsule.setVisibility(View.VISIBLE);
        mLoadingCapsule.setAlpha(0f);
        mLoadingCapsule.setTranslationY(-16f * d);
        mLoadingCapsule.setScaleX(0.9f);
        mLoadingCapsule.setScaleY(0.9f);
        mLoadingCapsule.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(net.kdt.pojavlaunch.utils.animation.MotionSpeed.scale(420))
                .setInterpolator(net.kdt.pojavlaunch.Anime.SPRING).start();
    }

    private void hideCapsule() {
        if (mLoadingCapsule == null || mLoadingCapsule.getVisibility() != View.VISIBLE) return;
        float d = getResources().getDisplayMetrics().density;
        final View capsule = mLoadingCapsule;
        capsule.animate().cancel();
        capsule.animate().alpha(0f).translationY(-10f * d).scaleX(0.92f).scaleY(0.92f)
                .setDuration(net.kdt.pojavlaunch.utils.animation.MotionSpeed.scale(200))
                .setInterpolator(net.kdt.pojavlaunch.Anime.IN_BACK)
                .withEndAction(() -> { capsule.setVisibility(View.GONE); capsule.setAlpha(1f); capsule.setTranslationY(0f); capsule.setScaleX(1f); capsule.setScaleY(1f); })
                .start();
    }

    /** Placeholder cards pulse gently so a slow network never looks frozen. */
    private void showSkeleton() {
        if (mSkeletonGrid == null) return;
        if (mSkeletonGrid.getVisibility() == View.VISIBLE) return;
        mSkeletonGrid.setVisibility(View.VISIBLE);
        mSkeletonGrid.setAlpha(1f);
        // Placeholder cards pour in on the same diagonal stagger the real cards use,
        // so the swap from skeleton → results reads as one continuous motion.
        if (mSkeletonGrid instanceof android.view.ViewGroup) {
            android.view.ViewGroup rows = (android.view.ViewGroup) mSkeletonGrid;
            for (int r = 0; r < rows.getChildCount(); r++) {
                View row = rows.getChildAt(r);
                if (!(row instanceof android.view.ViewGroup)) continue;
                android.view.ViewGroup cells = (android.view.ViewGroup) row;
                for (int c = 0; c < cells.getChildCount(); c++) {
                    net.kdt.pojavlaunch.Anime.in(cells.getChildAt(c), net.kdt.pojavlaunch.Anime.Fx.SCALE_IN,
                            r * 55L + c * 40L, 480, net.kdt.pojavlaunch.Anime.OUT_EXPO);
                }
            }
        }
        pulseSkeleton(mSkeletonGrid, true);
    }

    private void hideSkeleton() {
        if (mSkeletonGrid == null) return;
        if (mSkeletonGrid.getVisibility() != View.VISIBLE) return;
        mSkeletonGrid.animate().cancel();
        mSkeletonGrid.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (mSkeletonGrid == null) return;
                    mSkeletonGrid.setVisibility(View.GONE);
                    pulseSkeleton(mSkeletonGrid, false);
                }).start();
    }

    private void pulseSkeleton(View host, boolean on) {
        if (!(host instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) host;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.animate().cancel();
            if (on) {
                child.setAlpha(0.45f);
                child.animate().alpha(0.9f).setDuration(900).setStartDelay(i * 120L)
                        .withEndAction(() -> child.animate().alpha(0.45f).setDuration(900)
                                .withEndAction(() -> {
                                    if (mSkeletonGrid != null
                                            && mSkeletonGrid.getVisibility() == View.VISIBLE) {
                                        pulseSkeleton(mSkeletonGrid, true);
                                    }
                                }).start())
                        .start();
            } else {
                child.setAlpha(1f);
            }
        }
    }

    private SearchFilters buildFilters(String query) {
        SearchFilters filters = new SearchFilters();
        filters.name = query;
        if (mContentType == null) mContentType = "mod";
        if (mContentType.equals("world")) {
            // Modrinth : "world" project type nahi hai — "datapack" type + adventure category use karo
            filters.projectType = "datapack";
            filters.categories = "adventure";
            filters.isModpack = false;
        } else if (mContentType.equals("modpack")) {
            filters.projectType = "modpack";
            filters.isModpack = true;
        } else {
            filters.projectType = mContentType;
            filters.isModpack = false;
        }
        filters.sortIndex = mSortIndex;
        return filters;
    }

    @Override
    public void onSearchFinished() {
        if (!isUiReady()) return;
        endLoading();
        if (mStatusText != null) mStatusText.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        if (!isUiReady()) return;
        endLoading();
        if (mStatusText == null) return;
        mStatusText.setVisibility(View.VISIBLE);
        // Status pill fades in rather than popping
        mStatusText.setAlpha(0f);
        mStatusText.animate().alpha(1f).setDuration(220).start();
        switch (error) {
            case ERROR_INTERNAL:
                mStatusText.setTextColor(android.graphics.Color.parseColor("#E5A0A6"));
                mStatusText.setText(R.string.search_mod_error);
                break;
            case ERROR_NO_RESULTS:
                mStatusText.setTextColor(android.graphics.Color.parseColor("#9C9CA8"));
                mStatusText.setText(R.string.search_mod_no_result);
                break;
        }
    }
}
