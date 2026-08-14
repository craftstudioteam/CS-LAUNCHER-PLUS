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
    private ProgressBar mProgressBar;
    private View mLoadingCard;
    private TextView mStatusText;
    private ModItemAdapter mAdapter;
    private ModpackApi mApi;
    private String mProfileKey;

    private OnModItemClickListener mItemClickListener;

    /** The profile whose installed-content states should be drawn on the cards. */
    public void setProfileKey(String profileKey) {
        mProfileKey = profileKey;
        applyInstallContext();
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
        mProgressBar = view.findViewById(R.id.download_list_progress);
        mLoadingCard = view.findViewById(R.id.download_loading_card);
        mStatusText = view.findViewById(R.id.download_list_status);

        // A single compact result column remains readable inside the profile's
        // right pane and avoids the uneven card heights of the old 2-column grid.
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.setHasFixedSize(true);
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
        applyInstallContext();

        mAdapter.setOnItemClickListener(item -> {
            if (mItemClickListener != null && isUiReady()) {
                mItemClickListener.onItemClick(item);
            }
        });

        loadContent();
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
        // Cancel every pending animation / callback before views are torn down.
        if (mLoadingCard != null) mLoadingCard.animate().cancel();
        if (mStatusText != null) mStatusText.animate().cancel();
        if (mProgressBar != null) mProgressBar.animate().cancel();
        if (mRecyclerView != null) {
            mRecyclerView.setAdapter(null);
        }
        mAdapter = null;
        mRecyclerView = null;
        mProgressBar = null;
        mLoadingCard = null;
        mStatusText = null;
        super.onDestroyView();
    }

    private void loadContent() {
        if (!isUiReady()) return;
        SearchFilters filters = buildFilters("");
        showLoadingCapsule();
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        if (mAdapter != null) mAdapter.performSearchQuery(filters);
    }

    public void filter(String query) {
        filter(query, null, null);
    }

    public void filter(String query, @Nullable String mcVersion, @Nullable String modLoader) {
        if (!isUiReady()) return;
        mLastQuery = query != null ? query : "";
        mLastVersion = mcVersion;
        mLastLoader = modLoader;
        SearchFilters filters = buildFilters(mLastQuery);
        filters.mcVersion = mcVersion != null && !mcVersion.isEmpty() ? mcVersion : null;
        filters.modLoader = modLoader != null && !modLoader.isEmpty() ? modLoader : null;
        showLoadingCapsule();
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        if (mAdapter != null) mAdapter.performSearchQuery(filters);
    }

    /** Loading capsule drops in softly instead of popping. */
    private void showLoadingCapsule() {
        if (!isUiReady() || mLoadingCard == null) return;
        if (mLoadingCard.getVisibility() == View.VISIBLE) return;
        float density = mLoadingCard.getContext().getResources().getDisplayMetrics().density;
        mLoadingCard.setVisibility(View.VISIBLE);
        mLoadingCard.setAlpha(0f);
        mLoadingCard.setTranslationY(-12f * density);
        mLoadingCard.animate().alpha(1f).translationY(0f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    private void hideLoadingCapsule() {
        if (!isUiReady() || mLoadingCard == null) return;
        if (mLoadingCard.getVisibility() != View.VISIBLE) return;
        mLoadingCard.animate().cancel();
        float density = mLoadingCard.getContext().getResources().getDisplayMetrics().density;
        mLoadingCard.animate().alpha(0f).translationY(-8f * density)
                .setDuration(180)
                .withEndAction(() -> {
                    if (mLoadingCard == null) return;
                    mLoadingCard.setVisibility(View.GONE);
                    mLoadingCard.setAlpha(1f);
                    mLoadingCard.setTranslationY(0f);
                })
                .start();
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
        if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
        hideLoadingCapsule();
        if (mStatusText != null) mStatusText.setVisibility(View.GONE);
    }

    @Override
    public void onSearchError(int error) {
        if (!isUiReady()) return;
        if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
        hideLoadingCapsule();
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
