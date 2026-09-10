package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.utils.ProfileDetection;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ModVersionPickerFragment extends Fragment {

    public static final String TAG = "ModVersionPickerFragment";
    private static final String ARG_MOD_ITEM = "mod_item";
    private static final String ARG_CONTENT_TYPE = "content_type";
    private static final String ARG_PROFILE_KEY = "profile_key";

    private static final int PAGE_SIZE = 15;

    private ModItem mModItem;
    private ModDetail mModDetail;
    private String mContentType;
    /** MC version + loader of the selected profile — drives compat badges (Req: accurate). */
    private String mTargetMcVersion;
    private String mTargetLoader; // fabric/forge/neoforge/quilt/liteloader or null (vanilla/unknown)
    private String mProfileKey;

    // Views
    private ImageButton mBackButton;
    private TextView mTitleView;
    private ProgressBar mLoadingView;
    private RecyclerView mVersionList;
    private View mPaginationFooter;
    private TextView mPaginationText;
    private ImageButton mPrevButton;
    private ImageButton mNextButton;
    private TextView mErrorView;

    private VersionAdapter mAdapter;
    private List<VersionEntry> mAllVersions = new ArrayList<>();
    private int mCurrentPage = 0;
    private int mTotalPages = 0;

    public static ModVersionPickerFragment newInstance(ModItem item, String profileKey,
                                                       String contentType) {
        ModVersionPickerFragment f = new ModVersionPickerFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_MOD_ITEM, item);
        args.putString(ARG_PROFILE_KEY, profileKey);
        args.putString(ARG_CONTENT_TYPE, contentType != null ? contentType : "mod");
        f.setArguments(args);
        return f;
    }

    public ModVersionPickerFragment() {
        super(R.layout.fragment_version_picker);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mModItem = (ModItem) getArguments().getSerializable(ARG_MOD_ITEM);
            mContentType = getArguments().getString(ARG_CONTENT_TYPE);
            mProfileKey = getArguments().getString(ARG_PROFILE_KEY);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mBackButton = view.findViewById(R.id.version_picker_back);
        mTitleView = view.findViewById(R.id.version_picker_title);
        mLoadingView = view.findViewById(R.id.version_picker_loading);
        mVersionList = view.findViewById(R.id.version_list);
        mPaginationFooter = view.findViewById(R.id.pagination_footer);
        mPaginationText = view.findViewById(R.id.pagination_text);
        mPrevButton = view.findViewById(R.id.pagination_prev);
        mNextButton = view.findViewById(R.id.pagination_next);
        mErrorView = view.findViewById(R.id.version_picker_error);

        if (mModItem != null) {
            mTitleView.setText(mModItem.title);
        }
        bindHero(view);
        bindChannelRail(view);

        mBackButton.setOnClickListener(v -> {
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            } else if (getActivity() != null) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });
        UiMotion.pressFeedback(mBackButton, mPrevButton, mNextButton);

        mVersionList.setLayoutManager(new LinearLayoutManager(requireContext()));
        mVersionList.addItemDecoration(new net.kdt.pojavlaunch.modloaders.modpacks.SpacesItemDecoration(8));
        mAdapter = new VersionAdapter();
        mVersionList.setAdapter(mAdapter);
        mVersionList.setItemAnimator(null); // Disable default animation, use custom

        mPrevButton.setOnClickListener(v -> goToPage(mCurrentPage - 1));
        mNextButton.setOnClickListener(v -> goToPage(mCurrentPage + 1));

        // Analyze the selected profile once — badges + recommended pin depend on it
        mTargetMcVersion = resolveTargetMcVersion();
        mTargetLoader = resolveTargetLoader();

        // Screens hosted inside a pane (child FragmentManager) skip the global
        // activity-level reveal, so animate here; activity-level screens are
        // already revealed by LauncherActivity's lifecycle callback.
        if (getParentFragment() != null) UiMotion.revealScreen(view);
        loadVersions();
    }

    /**
     * Resolves the plain MC version of the selected profile. Prefers the version
     * JSON inheritsFrom chain (authoritative) and falls back to a correct
     * extraction from the version id — the loader version must never be picked
     * (e.g. "fabric-loader-0.19.3-1.21.10" must resolve to "1.21.10").
     */
    @Nullable
    private String resolveTargetMcVersion() {
        try {
            MinecraftProfile profile = getSelectedProfile();
            if (profile == null) return null;
            String mc = ProfileDetection.getMcVersion(profile);
            return (mc == null || mc.isEmpty()) ? null : mc;
        } catch (Exception e) {
            return null;
        }
    }

    /** Detects the mod loader of the selected profile (fabric/forge/neoforge/quilt/...). */
    @Nullable
    private String resolveTargetLoader() {
        try {
            MinecraftProfile profile = getSelectedProfile();
            if (profile == null) return null;
            // Fast path: the version id usually names the loader directly.
            String vid = profile.lastVersionId != null
                    ? profile.lastVersionId.toLowerCase(java.util.Locale.US) : null;
            if (vid != null) {
                if (vid.contains("neoforge")) return "neoforge";
                if (vid.contains("fabric")) return "fabric";
                if (vid.contains("quilt")) return "quilt";
                if (vid.contains("forge")) return "forge";
                if (vid.contains("liteloader")) return "liteloader";
            }
            // Deeper fallback: version JSON chain or installed mods folder —
            // catches loaders whose id does not carry the loader name.
            if (ProfileDetection.hasLoader(profile, "neoforge")) return "neoforge";
            if (ProfileDetection.hasLoader(profile, "fabric")) return "fabric";
            if (ProfileDetection.hasLoader(profile, "quilt")) return "quilt";
            if (ProfileDetection.hasLoader(profile, "forge")) return "forge";
            if (ProfileDetection.hasLoader(profile, "liteloader")) return "liteloader";
            return null; // vanilla / optifine / unknown → loader check not applicable
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private MinecraftProfile getSelectedProfile() {
        try {
            String key = mProfileKey != null ? mProfileKey
                    : LauncherPreferences.DEFAULT_PREF.getString(
                            LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            if (key == null || key.isEmpty()) return null;
            LauncherProfiles.load();
            return LauncherProfiles.mainProfileJson.profiles.get(key);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean mcMatches(VersionEntry entry) {
        if (mTargetMcVersion == null) return true; // nothing to judge → treat as OK
        String target = mTargetMcVersion.trim();
        if (entry.mcList != null) {
            for (String v : entry.mcList) {
                if (v != null && ProfileDetection.isVersionCompatible(target, v.trim())) return true;
            }
        }
        // Fallback evidence for sources that only carry a display value
        // (e.g. CurseForge); Modrinth always fills the full mcList above.
        if (entry.mcVersion != null) {
            for (String token : entry.mcVersion.split(",")) {
                if (ProfileDetection.isVersionCompatible(target, token.trim())) return true;
            }
        }
        return false;
    }

    private boolean loaderMatches(VersionEntry entry) {
        if (mTargetLoader == null) return true;          // vanilla/unknown profile → no constraint
        if (entry.loaders == null || entry.loaders.length == 0) return true; // unknown → MC-only verdict
        // Resource packs, shaders, worlds and data packs never depend on the
        // loader (Phase 8 — the "minecraft"/"iris" tags are not loaders).
        if (ProfileDetection.isLoaderAgnosticContent(mContentType)) return true;
        boolean sawRealLoader = false;
        for (String l : entry.loaders) {
            if (l == null) continue;
            String lower = l.toLowerCase(java.util.Locale.US).trim();
            if (lower.isEmpty() || lower.equals("minecraft") || lower.equals("vanilla")
                    || lower.equals("iris") || lower.equals("optifine") || lower.equals("canvas")
                    || lower.equals("datapack")) continue;
            sawRealLoader = true;
            if (lower.equals(mTargetLoader)) return true;
            if ("quilt".equals(mTargetLoader) && lower.equals("fabric")) return true; // Quilt runs Fabric mods
        }
        return !sawRealLoader;
    }

    /** Accurate three-state verdict: true-green / false-red. Unknown MC → green-neutral. */
    private boolean isEntryCompatible(VersionEntry entry) {
        return mcMatches(entry) && loaderMatches(entry);
    }

    private void loadVersions() {
        mLoadingView.setVisibility(View.VISIBLE);
        mErrorView.setVisibility(View.GONE);

        PojavApplication.sExecutorService.execute(() -> {
            try {
                ModpackApi api;
                if (mModItem.apiSource == Constants.SOURCE_MODRINTH) {
                    api = new ModrinthApi();
                } else {
                    api = new CommonApi(requireContext().getString(R.string.curseforge_api_key));
                }

                ModDetail detail = api.getModDetails(mModItem);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mLoadingView.setVisibility(View.GONE);
                    if (detail != null && detail.versionNames != null && detail.versionNames.length > 0) {
                        mModDetail = detail;
                        buildVersionList(detail);
                    } else {
                        mErrorView.setVisibility(View.VISIBLE);
                        mErrorView.setText(R.string.search_modpack_download_error);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Failed to load versions", e);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    mLoadingView.setVisibility(View.GONE);
                    mErrorView.setVisibility(View.VISIBLE);
                    mErrorView.setText(getString(R.string.search_modpack_download_error));
                });
            }
        });
    }

    // Safe version comparator — guaranteed transitive, never violates TimSort contract.
    // Handles null, empty, non-numeric suffixes, unequal-length parts.
    private java.util.Comparator<VersionEntry> sLatestFirst = (a, b) -> {
        String va = a.mcVersion != null ? a.mcVersion : "";
        String vb = b.mcVersion != null ? b.mcVersion : "";
        if (va.equals(vb)) return b.name.compareTo(a.name);
        int result = compareVersionStrings(vb, va);
        return result != 0 ? result : b.name.compareTo(a.name);
    };

    private static int compareVersionStrings(String v1, String v2) {
        String clean1 = v1.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
        String clean2 = v2.replaceAll("[^0-9.]", "").replaceAll("\\.$", "");
        String[] parts1 = clean1.isEmpty() ? new String[]{"0"} : clean1.split("\\.");
        String[] parts2 = clean2.isEmpty() ? new String[]{"0"} : clean2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = 0, n2 = 0;
            try { n1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0; } catch (NumberFormatException ignored) {}
            try { n2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0; } catch (NumberFormatException ignored) {}
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private void buildVersionList(ModDetail detail) {
        mAllVersions.clear();
        for (int i = 0; i < detail.versionNames.length; i++) {
            String mcVer = (detail.mcVersionNames != null && i < detail.mcVersionNames.length)
                    ? detail.mcVersionNames[i] : "";
            mAllVersions.add(new VersionEntry(
                    detail.versionNames[i],
                    mcVer,
                    detail.versionUrls[i],
                    i,
                    (detail.versionHashes != null && i < detail.versionHashes.length) ? detail.versionHashes[i] : null,
                    (detail.versionDependencyIds != null && i < detail.versionDependencyIds.length) ? detail.versionDependencyIds[i] : null,
                    (detail.versionDependencyTypes != null && i < detail.versionDependencyTypes.length) ? detail.versionDependencyTypes[i] : null,
                    (detail.mcVersionLists != null && i < detail.mcVersionLists.length) ? detail.mcVersionLists[i] : null,
                    (detail.versionLoaders != null && i < detail.versionLoaders.length) ? detail.versionLoaders[i] : null
            ));
        }

        // Sort: latest MC version first
        java.util.Collections.sort(mAllVersions, sLatestFirst);

        if (isModpackContent()) {
            // MODPACKS are self-contained (each bundle pins its own MC version and
            // loader) — they must NOT be judged or re-ordered against the selected
            // profile, and no version may ever be force-crowned. Users pick freely;
            // we only tag the release channel (Latest / Stable / Beta / Alpha / Old).
            for (int i = 0; i < mAllVersions.size(); i++) {
                VersionEntry entry = mAllVersions.get(i);
                entry.compatible = true;
                entry.recommended = false;
                entry.channel = channelFor(entry, i);
            }
        } else {
            // Req-16: judge compatibility against the selected profile, then present
            // compatible entries first (stable partition) and crown the top one as
            // the Recommended pick.
            boolean recommendedCrowned = false;
            for (VersionEntry entry : mAllVersions) {
                entry.compatible = isEntryCompatible(entry);
            }
            List<VersionEntry> compatible = new ArrayList<>();
            List<VersionEntry> incompatible = new ArrayList<>();
            for (VersionEntry entry : mAllVersions) {
                (entry.compatible ? compatible : incompatible).add(entry);
            }
            for (VersionEntry entry : compatible) {
                if (!recommendedCrowned) {
                    entry.recommended = true;
                    recommendedCrowned = true;
                }
            }
            mAllVersions.clear();
            mAllVersions.addAll(compatible);
            mAllVersions.addAll(incompatible);
        }

        // Publish the store's latest-known version for card state resolution
        // (Installed / Update Available / Newer-than-store).
        try {
            if (mModItem != null && mModItem.id != null && !mAllVersions.isEmpty() && getContext() != null) {
                net.kdt.pojavlaunch.modloaders.modpacks.InstalledContentTracker.recordLatestKnown(
                        requireContext(), mContentType, mModItem.id, mAllVersions.get(0).name);
            }
        } catch (Exception ignored) {}

        recomputePages();
        mCurrentPage = 0;
        showPage(0);
    }

    // ── Phase 10: hero band + channel rail ─────────────────────────────

    private TextView mHeroCount;
    private int mChannelFilter = 0; // 0 all, 1 release, 2 beta, 3 alpha
    private final List<TextView> mFilterChips = new ArrayList<>();

    private void bindHero(View view) {
        mHeroCount = view.findViewById(R.id.vp_hero_count);
        TextView author = view.findViewById(R.id.vp_hero_author);
        TextView source = view.findViewById(R.id.vp_hero_source);
        android.widget.ImageView icon = view.findViewById(R.id.vp_hero_icon);
        if (mModItem == null) return;
        if (author != null) {
            boolean has = mModItem.author != null && !mModItem.author.trim().isEmpty();
            author.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) author.setText("by " + mModItem.author.trim());
        }
        if (source != null) source.setText(mModItem.apiSource == Constants.SOURCE_MODRINTH ? "MODRINTH" : "CURSEFORGE");
        if (icon != null && mModItem.imageUrl != null) {
            try {
                net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache.getInstance().getImage(
                        bmp -> { if (bmp != null && isAdded()) { icon.setImageBitmap(bmp); icon.setAlpha(0f); icon.animate().alpha(1f).setDuration(260).start(); } },
                        mModItem.getIconCacheTag(), mModItem.imageUrl);
            } catch (Throwable ignored) {}
        }
        View hero = view.findViewById(R.id.vp_hero);
        if (hero != null) net.kdt.pojavlaunch.Anime.in(hero, net.kdt.pojavlaunch.Anime.Fx.FADE_DOWN, 40, 520, net.kdt.pojavlaunch.Anime.OUT_EXPO);
    }

    private void bindChannelRail(View view) {
        int[] ids = {R.id.vp_filter_all, R.id.vp_filter_release, R.id.vp_filter_beta, R.id.vp_filter_alpha};
        mFilterChips.clear();
        for (int i = 0; i < ids.length; i++) {
            final int which = i;
            TextView chip = view.findViewById(ids[i]);
            if (chip == null) continue;
            mFilterChips.add(chip);
            chip.setOnClickListener(v -> {
                if (mChannelFilter == which) return;
                mChannelFilter = which;
                styleFilterChips();
                net.kdt.pojavlaunch.Anime.pulse(chip);
                recomputePages();
                showPage(0);
            });
        }
        styleFilterChips();
        View rail = view.findViewById(R.id.vp_filter_rail);
        if (rail != null) net.kdt.pojavlaunch.Anime.in(rail, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 140, 480, net.kdt.pojavlaunch.Anime.OUT_EXPO);
    }

    private void styleFilterChips() {
        for (int i = 0; i < mFilterChips.size(); i++) {
            TextView c = mFilterChips.get(i);
            boolean on = i == mChannelFilter;
            c.setBackgroundResource(on ? R.drawable.vp_chip_on : R.drawable.vp_chip_off);
            c.setTextColor(on ? 0xFF141519 : 0xFFC9CED8);
        }
    }

    /** Channel bucket of a build for the rail filter (name/url heuristics, all sources). */
    private static int channelBucket(VersionEntry e) {
        String probe = (e.name + " " + (e.url != null ? e.url : "")).toLowerCase(java.util.Locale.US);
        if (probe.contains("alpha")) return 3;
        if (probe.contains("beta") || probe.contains("snapshot") || probe.contains("-pre") || probe.contains("-rc") || probe.contains("pre-release")) return 2;
        return 1;
    }

    private List<VersionEntry> filteredVersions() {
        if (mChannelFilter == 0) return mAllVersions;
        List<VersionEntry> out = new ArrayList<>();
        for (VersionEntry e : mAllVersions) if (channelBucket(e) == mChannelFilter) out.add(e);
        return out;
    }

    private void recomputePages() {
        int n = filteredVersions().size();
        mTotalPages = (int) Math.ceil((double) n / PAGE_SIZE);
        if (mTotalPages < 1) mTotalPages = 1;
        mPaginationFooter.setVisibility(mTotalPages > 1 ? View.VISIBLE : View.GONE);
    }

    private void showPage(int page) {
        mCurrentPage = page;
        List<VersionEntry> pool = filteredVersions();
        int start = Math.min(page * PAGE_SIZE, Math.max(0, pool.size()));
        int end = Math.min(start + PAGE_SIZE, pool.size());
        List<VersionEntry> pageItems = pool.subList(start, end);

        mAdapter.setData(pageItems);
        if (mHeroCount != null) {
            mHeroCount.setVisibility(View.VISIBLE);
            mHeroCount.setText(pool.size() + (pool.size() == 1 ? " BUILD" : " BUILDS"));
        }
        if (mErrorView != null && pool.isEmpty() && !mAllVersions.isEmpty()) {
            mErrorView.setVisibility(View.VISIBLE);
            mErrorView.setText("No builds in this channel");
        } else if (mErrorView != null && !pool.isEmpty()) {
            mErrorView.setVisibility(View.GONE);
        }

        mPaginationText.setText("PAGE " + (page + 1) + " / " + mTotalPages);
        mPrevButton.setEnabled(page > 0);
        mNextButton.setEnabled(page < mTotalPages - 1);

        // Staggered item entrance + soft page fade on every page change
        if (mVersionList != null && isAdded()) {
            android.view.animation.LayoutAnimationController controller =
                    android.view.animation.AnimationUtils.loadLayoutAnimation(
                            requireContext(), R.anim.list_item_enter);
            mVersionList.setLayoutAnimation(controller);
            mVersionList.scheduleLayoutAnimation();
        }
        mVersionList.setAlpha(0.6f);
        mVersionList.setTranslationY(30f);
        mVersionList.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start();
    }

    private void goToPage(int page) {
        if (page < 0 || page >= mTotalPages) return;
        showPage(page);
    }

    private void openInstallScreen(VersionEntry entry) {
        ModInstallFragment fragment = ModInstallFragment.newInstance(
                mModItem, mModDetail, entry.index, mProfileKey, mContentType);
        Fragment parent = getParentFragment();
        Bundle args = fragment.getArguments();
        // Forward the per-world datapack target (World Manager flow), if any.
        Bundle self = getArguments();
        if (self != null && args != null) {
            String td = self.getString(ModInstallFragment.ARG_TARGET_DIR);
            if (td != null) args.putString(ModInstallFragment.ARG_TARGET_DIR, td);
            String ik = self.getString(ModInstallFragment.ARG_INSTALL_KEY);
            if (ik != null) args.putString(ModInstallFragment.ARG_INSTALL_KEY, ik);
        }
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(ModInstallFragment.class, ModInstallFragment.TAG, args);
        } else if (parent != null) {
            parent.getChildFragmentManager()
                    .beginTransaction()
                    .setCustomAnimations(
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[0],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[1],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[2],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[3])
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, ModInstallFragment.class, args, ModInstallFragment.TAG)
                    .addToBackStack(ModInstallFragment.TAG)
                    .commit();
        } else if (getActivity() != null) {
            Tools.swapFragment(getActivity(), ModInstallFragment.class, ModInstallFragment.TAG, args);
        }
    }

    // ── Data class ────────────────────────────────────────────

    private static final int CHANNEL_NONE = 0;
    private static final int CHANNEL_LATEST = 1;
    private static final int CHANNEL_STABLE = 2;
    private static final int CHANNEL_BETA = 3;
    private static final int CHANNEL_ALPHA = 4;
    private static final int CHANNEL_OLD = 5;

    private boolean isModpackContent() {
        return "modpack".equals(mContentType);
    }

    /** Release-channel heuristic for modpack versions (display only). */
    private static int channelFor(VersionEntry entry, int sortedIndex) {
        if (sortedIndex == 0) return CHANNEL_LATEST;
        String probe = (entry.name + " " + (entry.url != null ? entry.url : "")).toLowerCase(java.util.Locale.US);
        if (probe.contains("alpha")) return CHANNEL_ALPHA;
        if (probe.contains("beta") || probe.contains("snapshot")
                || probe.contains("pre") || probe.contains("rc")) return CHANNEL_BETA;
        // plain releases: newest handful read as stable, the long tail as old
        return sortedIndex < 6 ? CHANNEL_STABLE : CHANNEL_OLD;
    }

    static class VersionEntry {
        final String name;
        final String mcVersion;
        final String url;
        final int index;
        final String hash;
        final String[] depIds;
        final String[] depTypes;
        final String[] mcList;   // FULL supported MC versions (accurate compat)
        final String[] loaders;  // supported loaders for this version
        boolean compatible = true;   // vs selected profile (Req-16)
        boolean recommended = false; // best compatible pick, pinned at top
        int channel = 0;             // release channel (modpacks only)

        VersionEntry(String name, String mcVersion, String url, int index,
                     String hash, String[] depIds, String[] depTypes) {
            this(name, mcVersion, url, index, hash, depIds, depTypes, null, null);
        }

        VersionEntry(String name, String mcVersion, String url, int index,
                     String hash, String[] depIds, String[] depTypes,
                     String[] mcList, String[] loaders) {
            this.name = name;
            this.mcVersion = mcVersion;
            this.url = url;
            this.index = index;
            this.hash = hash;
            this.depIds = depIds;
            this.depTypes = depTypes;
            this.mcList = mcList;
            this.loaders = loaders;
        }
    }

    // ── RecyclerView Adapter ──────────────────────────────────

    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.VH> {

        private List<VersionEntry> mData = new ArrayList<>();

        void setData(List<VersionEntry> data) {
            mData = data;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_version_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            VersionEntry entry = mData.get(position);
            holder.nameView.setText(entry.name);
            if (entry.mcVersion != null && !entry.mcVersion.isEmpty()) {
                holder.mcBadge.setVisibility(View.VISIBLE);
                holder.mcBadge.setText(entry.mcVersion);
            } else {
                holder.mcBadge.setVisibility(View.GONE);
            }

            if (isModpackContent()) {
                // Modpacks: no profile-compat judging, no dimming — channel tag instead
                holder.compatBadge.setVisibility(View.GONE);
                holder.itemView.setAlpha(1f);
            } else {
                // Compat badge: green ✓ / red ✕ / grey Unknown against the selected profile
                boolean hasMcEvidence = (entry.mcList != null && entry.mcList.length > 0)
                        || (entry.mcVersion != null && !entry.mcVersion.isEmpty());
                if (mTargetMcVersion == null || !hasMcEvidence) {
                    holder.compatBadge.setVisibility(View.VISIBLE);
                    holder.compatBadge.setText("? Unknown");
                    holder.compatBadge.setTextColor(0xFF9C9CA8);
                    holder.compatBadge.setBackgroundResource(R.drawable.vp_tag_neutral);
                    holder.itemView.setAlpha(1f);
                } else if (entry.compatible) {
                    holder.compatBadge.setVisibility(View.VISIBLE);
                    holder.compatBadge.setText("✓ Compatible");
                    holder.compatBadge.setTextColor(0xFF9FD6AC); // muted success
                    holder.compatBadge.setBackgroundResource(R.drawable.vp_tag_ok);
                    holder.itemView.setAlpha(1f);
                } else {
                    holder.compatBadge.setVisibility(View.VISIBLE);
                    holder.compatBadge.setText("✕ Incompatible");
                    holder.compatBadge.setTextColor(0xFFE5A0A6); // muted rose
                    holder.compatBadge.setBackgroundResource(R.drawable.vp_tag_bad);
                    holder.itemView.setAlpha(0.62f); // visually de-emphasize
                }
            }

            // Loader chip(s): first loader, capitalized (Fabric/Forge/NeoForge/Quilt...)
            if (holder.loaderBadge != null) {
                String loaderName = (entry.loaders != null && entry.loaders.length > 0 && entry.loaders[0] != null)
                        ? entry.loaders[0] : null;
                if (loaderName != null && !loaderName.isEmpty()) {
                    holder.loaderBadge.setVisibility(View.VISIBLE);
                    holder.loaderBadge.setText(loaderName.substring(0, 1).toUpperCase(java.util.Locale.US)
                            + loaderName.substring(1));
                } else {
                    holder.loaderBadge.setVisibility(View.GONE);
                }
            }

            // Mods: Recommended crown on the best compatible pick.
            // Modpacks: release-channel tag on every row (never a forced pick).
            if (isModpackContent()) {
                switch (entry.channel) {
                    case CHANNEL_LATEST:
                        holder.recBadge.setVisibility(View.VISIBLE);
                        holder.recBadge.setText(R.string.mp_channel_latest);
                        holder.recBadge.setTextColor(0xFFE6E9EF);
                        holder.recBadge.setBackgroundResource(R.drawable.vp_tag_recommended);
                        break;
                    case CHANNEL_STABLE:
                        holder.recBadge.setVisibility(View.VISIBLE);
                        holder.recBadge.setText(R.string.mp_channel_stable);
                        holder.recBadge.setTextColor(0xFFD2D6DE);
                        holder.recBadge.setBackgroundResource(R.drawable.vp_tag_channel);
                        break;
                    case CHANNEL_BETA:
                        holder.recBadge.setVisibility(View.VISIBLE);
                        holder.recBadge.setText(R.string.mp_channel_beta);
                        holder.recBadge.setTextColor(0xFFF6C177);
                        holder.recBadge.setBackgroundResource(R.drawable.vp_tag_channel);
                        break;
                    case CHANNEL_ALPHA:
                        holder.recBadge.setVisibility(View.VISIBLE);
                        holder.recBadge.setText(R.string.mp_channel_alpha);
                        holder.recBadge.setTextColor(0xFFF9A8AE);
                        holder.recBadge.setBackgroundResource(R.drawable.vp_tag_channel);
                        break;
                    case CHANNEL_OLD:
                        holder.recBadge.setVisibility(View.VISIBLE);
                        holder.recBadge.setText(R.string.mp_channel_old);
                        holder.recBadge.setTextColor(0xFF9C9CA8);
                        holder.recBadge.setBackgroundResource(R.drawable.vp_tag_channel);
                        break;
                    default:
                        holder.recBadge.setVisibility(View.GONE);
                        break;
                }
            } else {
                holder.recBadge.setVisibility(entry.recommended ? View.VISIBLE : View.GONE);
                if (entry.recommended) {
                    holder.recBadge.setText("\u2605 RECOMMENDED");
                    holder.recBadge.setBackgroundResource(R.drawable.vp_tag_recommended);
                }
            }

            // Phase 10 timeline: the recommended / newest build gets the hot node
            boolean hot = entry.recommended || (isModpackContent() && entry.channel == CHANNEL_LATEST);
            if (holder.node != null) holder.node.setBackgroundResource(hot ? R.drawable.vp_node_hot : R.drawable.vp_node);
            if (holder.card != null) holder.card.setBackgroundResource(hot ? R.drawable.vp_row_card_hot : R.drawable.vp_row_card);
            if (holder.recBadge.getVisibility() == View.VISIBLE && hot) {
                holder.recBadge.setTextColor(0xFF141519); // dark label on the light "hot" tag
            }
            View click = holder.card != null ? holder.card : holder.itemView;
            click.setOnClickListener(v -> openInstallScreen(entry));
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView nameView;
            final TextView mcBadge;
            final TextView compatBadge;
            final TextView recBadge;
            final TextView loaderBadge;
            final View node, card;

            VH(View itemView) {
                super(itemView);
                node = itemView.findViewById(R.id.vp_node);
                card = itemView.findViewById(R.id.vp_card);
                nameView = itemView.findViewById(R.id.version_name);
                mcBadge = itemView.findViewById(R.id.version_mc_badge);
                compatBadge = itemView.findViewById(R.id.version_compat_badge);
                recBadge = itemView.findViewById(R.id.version_recommended_badge);
                loaderBadge = itemView.findViewById(R.id.version_loader_badge);
            }
        }
    }
}
