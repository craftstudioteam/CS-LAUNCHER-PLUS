package net.kdt.pojavlaunch.fragments;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.CommonApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;
import net.kdt.pojavlaunch.utils.ProfileDetection;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * MOD DETAIL — a marketplace-quality page built from scratch.
 *
 * <ul>
 *   <li><b>Artwork stage</b> — the project's screenshots slide behind the hero
 *       with a cross fade and a slow drift. Images are handed to the ImageViews
 *       heavily downscaled, so the upscale does the blurring for free.</li>
 *   <li><b>Automatic version selection</b> — the launcher detects the Minecraft
 *       version of the current profile, scores every published file against it
 *       (full supported-version list + loader match) and preselects the best
 *       one. The user sees exactly what was chosen and why.</li>
 *   <li><b>Change version</b> — compatible files first, everything else marked.
 *       An incompatible pick is never silently installed: the Install action
 *       is disabled and the reason is spelled out.</li>
 *   <li><b>Sections, not a wall of text</b> — About, Gallery, Versions,
 *       Dependencies and Links.</li>
 * </ul>
 *
 * Nothing on this page is invented: every number and every compatibility
 * verdict comes from the API payload or from the real profile on disk.
 */
public class ModDetailFragment extends Fragment implements ModVersionSheet.Listener {

    public static final String TAG = "ModDetailFragment";
    private static final String ARG_MOD_ITEM = "mod_item";
    private static final long SLIDE_MS = 4200L;
    private static final int BACKDROP_TINY_W = 320;

    private ModItem mModItem;
    private ModDetail mModDetail;
    private ModpackApi mModpackApi;
    private ModIconCache mIconCache;
    private String mProfileKey;
    private String mContentType;

    // views
    private TextView mTopBarTitle;
    private ImageView mModIcon;
    private ImageView mSourceBadge;
    private TextView mModTitle;
    private TextView mModSubtitle;
    private TextView mStatDownloads;
    private TextView mStatVersions;
    private TextView mStatUpdated;
    private LinearLayout mCompatRow;
    private TextView mMcCurrent;
    private TextView mVersionChip;
    private TextView mBtnChangeVersion;
    private TextView mCompatWarning;
    private TextView mFullDescription;
    private View mGalleryCard;
    private LinearLayout mScreenshotContainer;
    private LinearLayout mVersionsContainer;
    private LinearLayout mDependenciesContainer;
    private LinearLayout mLinksContainer;
    private View mDepsCard;
    private View mLinksCard;
    private TextView mDownloadButton;
    private View mScrollContent;
    private View mBottomBar;

    // graphite v3 — optional structural views (null-safe everywhere)
    private View mSidePanel;
    private View mStage;
    private View mIdentityBlock;
    private View mStatsRow;
    private View mInstallConsole;
    private View mAboutCard;
    private View mVersionsCard;
    private TextView mTypeBadge;
    private LinearLayout mStageDots;
    private TextView mStageCounter;
    private View mSelectedRow;
    private TextView mSelectedMeta;
    private TextView mSelectedState;
    private TextView mSelectedCheck;
    private View mQuickLabel;
    private View mQuickScroll;
    private LinearLayout mQuickVersions;
    private TextView mVersionsCount;
    private boolean mFirstDetailReveal = true;

    // backdrop slideshow
    private ImageView mBackdropA;
    private ImageView mBackdropB;
    private boolean mBackdropFront = true;
    private String[] mSlideUrls = new String[0];
    private int mSlideIndex = 0;
    private Bitmap mTinyA;
    private Bitmap mTinyB;
    private final Handler mSlideHandler = new Handler(Looper.getMainLooper());
    private final Runnable mSlideRunnable = new Runnable() {
        @Override public void run() { showSlide(mSlideIndex + 1); }
    };

    // version selection
    private int mSelectedVersionIndex = -1;
    private boolean[] mCompatible = new boolean[0];
    private String mCurrentMcVersion;

    public static ModDetailFragment newInstance(ModItem item) {
        ModDetailFragment fragment = new ModDetailFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_MOD_ITEM, item);
        fragment.setArguments(args);
        return fragment;
    }

    public ModDetailFragment() {
        super(R.layout.fragment_mod_detail);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mModItem = (ModItem) getArguments().getSerializable(ARG_MOD_ITEM);
        }
        mProfileKey = getArguments() != null
                ? getArguments().getString(ManageModsFragment.BUNDLE_PROFILE_KEY) : null;
        if (mProfileKey == null) {
            mProfileKey = LauncherPreferences.DEFAULT_PREF
                    .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        }
        mIconCache = ModIconCache.getInstance();
        mContentType = getArguments() != null ? getArguments().getString("content_type") : null;
        if (mContentType == null || mContentType.isEmpty()) mContentType = "mod";
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mTopBarTitle = view.findViewById(R.id.detail_title_textview);
        mModIcon = view.findViewById(R.id.detail_mod_icon);
        mSourceBadge = view.findViewById(R.id.detail_source_badge);
        mModTitle = view.findViewById(R.id.detail_mod_title);
        mModSubtitle = view.findViewById(R.id.detail_mod_subtitle);
        mStatDownloads = view.findViewById(R.id.detail_stat_downloads);
        mStatVersions = view.findViewById(R.id.detail_stat_versions);
        mStatUpdated = view.findViewById(R.id.detail_stat_updated);
        mCompatRow = view.findViewById(R.id.detail_compat_row);
        mMcCurrent = view.findViewById(R.id.detail_mc_current);
        mVersionChip = view.findViewById(R.id.detail_version_chip);
        mBtnChangeVersion = view.findViewById(R.id.detail_btn_change_version);
        mCompatWarning = view.findViewById(R.id.detail_compat_warning);
        mFullDescription = view.findViewById(R.id.detail_full_description);
        mGalleryCard = view.findViewById(R.id.detail_gallery_card);
        mScreenshotContainer = view.findViewById(R.id.detail_screenshot_container);
        mVersionsContainer = view.findViewById(R.id.detail_versions_container);
        mDependenciesContainer = view.findViewById(R.id.detail_dependencies_container);
        mLinksContainer = view.findViewById(R.id.detail_links_container);
        mDepsCard = view.findViewById(R.id.detail_deps_card);
        mLinksCard = view.findViewById(R.id.detail_links_card);
        mDownloadButton = view.findViewById(R.id.detail_download_button);
        mScrollContent = view.findViewById(R.id.detail_scroll_content);
        mBottomBar = view.findViewById(R.id.detail_bottom_bar);
        mBackdropA = view.findViewById(R.id.detail_backdrop_a);
        mBackdropB = view.findViewById(R.id.detail_backdrop_b);
        mSidePanel = view.findViewById(R.id.detail_side_panel);
        mStage = view.findViewById(R.id.detail_stage);
        mIdentityBlock = view.findViewById(R.id.detail_identity_block);
        mStatsRow = view.findViewById(R.id.detail_stats_row);
        mInstallConsole = view.findViewById(R.id.detail_install_console);
        mAboutCard = view.findViewById(R.id.detail_about_card);
        mVersionsCard = view.findViewById(R.id.detail_versions_card);
        mTypeBadge = view.findViewById(R.id.detail_type_badge);
        if (mTypeBadge != null) mTypeBadge.setText(typeBadgeLabel());
        mStageDots = view.findViewById(R.id.detail_stage_dots);
        mStageCounter = view.findViewById(R.id.detail_stage_counter);
        mSelectedRow = view.findViewById(R.id.detail_selected_row);
        mSelectedMeta = view.findViewById(R.id.detail_selected_meta);
        mSelectedState = view.findViewById(R.id.detail_selected_state);
        mSelectedCheck = view.findViewById(R.id.detail_selected_check);
        mQuickLabel = view.findViewById(R.id.detail_quick_label);
        mQuickScroll = view.findViewById(R.id.detail_quick_scroll);
        mQuickVersions = view.findViewById(R.id.detail_quick_versions);
        mVersionsCount = view.findViewById(R.id.detail_versions_count);
        if (mSelectedRow != null) {
            net.kdt.pojavlaunch.UiMotion.pressFeedback(mSelectedRow);
            mSelectedRow.setOnClickListener(v -> showChangeVersionDialog());
        }

        net.kdt.pojavlaunch.UiMotion.pressFeedback(
                view.findViewById(R.id.detail_back_button), mBtnChangeVersion, mDownloadButton);

        if (mModItem != null && mModItem.apiSource == Constants.SOURCE_MODRINTH) {
            mModpackApi = new ModrinthApi();
        } else {
            mModpackApi = new CommonApi(requireContext().getString(R.string.curseforge_api_key));
        }

        view.findViewById(R.id.detail_back_button).setOnClickListener(v -> {
            if (getParentFragmentManager().getBackStackEntryCount() > 0) {
                getParentFragmentManager().popBackStack();
            } else if (getActivity() != null) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        });

        mBtnChangeVersion.setOnClickListener(v -> showChangeVersionDialog());

        populateModInfo();
        detectCurrentVersion();
        loadModDetails();
        animateEntrance();
    }

    // ─────────────────────────── identity ───────────────────────────

    private void populateModInfo() {
        if (mModItem == null) return;
        mTopBarTitle.setText(mModItem.title);
        mModTitle.setText(mModItem.title);

        StringBuilder info = new StringBuilder();
        if (mModItem.author != null && !mModItem.author.isEmpty()) {
            info.append("by ").append(mModItem.author);
        }
        mModSubtitle.setText(info.length() > 0 ? info.toString() : "Unknown author");
        animateDownloads(mModItem.downloads);
        mFullDescription.setText(mModItem.description != null ? mModItem.description : "");

        mSourceBadge.setImageResource(getSourceDrawable(mModItem.apiSource));

        mIconCache.getImage(bitmap -> {
            if (bitmap != null && isAdded()) mModIcon.setImageBitmap(bitmap);
        }, mModItem.getIconCacheTag(), mModItem.imageUrl);

        // No screenshots? The project icon alone still makes a fine backdrop.
        startBackdrop();
    }

    private int getSourceDrawable(int apiSource) {
        switch (apiSource) {
            case Constants.SOURCE_CURSEFORGE: return R.drawable.ic_curseforge;
            case Constants.SOURCE_MODRINTH: return R.drawable.ic_modrinth;
            default: return R.mipmap.ic_launcher_foreground;
        }
    }

    // ─────────────────────── artwork stage ───────────────────────

    private void startBackdrop() {
        if (mBackdropA == null || mBackdropB == null) return;
        List<String> urls = new ArrayList<>();
        // 1) search-hit gallery (Modrinth hands these over with the result) — instant
        if (mModItem != null && mModItem.galleryUrls != null) {
            for (String u : mModItem.galleryUrls) if (u != null && !u.isEmpty() && !urls.contains(u)) urls.add(u);
        }
        if (urls.isEmpty() && mModItem != null && mModItem.galleryUrl != null && !mModItem.galleryUrl.isEmpty()) {
            urls.add(mModItem.galleryUrl);
        }
        // 2) icon as the opener / fallback
        if (mModItem != null && mModItem.imageUrl != null && !mModItem.imageUrl.isEmpty() && !urls.contains(mModItem.imageUrl)) {
            urls.add(urls.isEmpty() ? 0 : urls.size(), mModItem.imageUrl);
        }
        if (urls.isEmpty()) return;
        mSlideUrls = urls.toArray(new String[0]);
        mSlideIndex = 0;
        rebuildStageDots();
        showSlide(0);
        // 3) full project gallery (CurseForge only exposes screenshots here) — background, best effort
        fetchGalleryInBackground();
        installStageGestures();
    }

    /** Pulls the project's screenshot gallery through the same API the mod-install page uses. */
    private void fetchGalleryInBackground() {
        if (mModItem == null || mModItem.id == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            String[] gallery = null;
            try {
                net.kdt.pojavlaunch.modloaders.modpacks.models.ModProjectInfo info;
                if (mModItem.apiSource == Constants.SOURCE_MODRINTH) {
                    info = new ModrinthApi().fetchProjectInfo(mModItem.id);
                } else {
                    android.content.Context c = getContext();
                    String key = c != null ? c.getString(R.string.curseforge_api_key) : "";
                    info = new net.kdt.pojavlaunch.modloaders.modpacks.api.CurseforgeApi(key).fetchProjectInfo(mModItem.id);
                }
                if (info != null) gallery = info.galleryUrls;
            } catch (Throwable ignored) {}
            final String[] fGallery = gallery;
            if (fGallery == null || fGallery.length == 0) return;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (mModDetail != null) mModDetail.setScreenshotUrls(fGallery);
                mPendingGallery = fGallery;
                refreshSlideUrls();
                populateGallery();
            });
        });
    }

    private String[] mPendingGallery;

    /** Merges every known screenshot into the stage deck (icon first, no duplicates). */
    private void refreshSlideUrls() {
        List<String> urls = new ArrayList<>();
        String[] shots = mModDetail != null && mModDetail.screenshotUrls != null ? mModDetail.screenshotUrls : mPendingGallery;
        if (shots != null) for (String u : shots) if (u != null && !u.isEmpty() && !urls.contains(u)) urls.add(u);
        if (mModItem != null && mModItem.galleryUrls != null)
            for (String u : mModItem.galleryUrls) if (u != null && !u.isEmpty() && !urls.contains(u)) urls.add(u);
        if (mModItem != null && mModItem.imageUrl != null && !mModItem.imageUrl.isEmpty() && !urls.contains(mModItem.imageUrl)) {
            urls.add(mModItem.imageUrl);
        }
        if (urls.isEmpty()) return;
        boolean grew = urls.size() > mSlideUrls.length;
        mSlideUrls = urls.toArray(new String[0]);
        rebuildStageDots();
        if (grew && mSlideUrls.length > 1) {
            // New pictures arrived: advance soon instead of waiting the full cycle.
            mSlideHandler.removeCallbacks(mSlideRunnable);
            mSlideHandler.postDelayed(mSlideRunnable, 1600);
        }
    }

    private void showSlide(int index) {
        if (!isAdded() || mSlideUrls.length == 0) return;
        mSlideHandler.removeCallbacks(mSlideRunnable);
        mSlideIndex = ((index % mSlideUrls.length) + mSlideUrls.length) % mSlideUrls.length;
        final int shown = mSlideIndex;
        final String url = mSlideUrls[shown];
        final boolean toFront = !mBackdropFront;
        final ImageView incoming = toFront ? mBackdropA : mBackdropB;
        final ImageView outgoing = toFront ? mBackdropB : mBackdropA;
        updateStageDots(shown);

        mIconCache.getImage(bitmap -> {
            if (!isAdded()) return;
            if (bitmap == null) {
                // unreachable image → skip it after a short beat
                if (mSlideUrls.length > 1) mSlideHandler.postDelayed(mSlideRunnable, 900);
                return;
            }
            if (shown != mSlideIndex) return; // user already moved on
            Bitmap small = downscaleForStage(bitmap);
            if (small == null) return;

            incoming.setImageBitmap(small);
            if (toFront) { recycleTinyB(); mTinyA = small; } else { recycleTinyA(); mTinyB = small; }

            // Ken-Burns: alternate pan direction per slide; zoom 1.18 → 1.04 over the dwell.
            float d = getResources().getDisplayMetrics().density;
            boolean panRight = (shown & 1) == 0;
            incoming.animate().cancel();
            incoming.setAlpha(0f);
            incoming.setScaleX(1.18f);
            incoming.setScaleY(1.18f);
            incoming.setTranslationX(panRight ? -10f * d : 10f * d);
            incoming.animate().alpha(1f).setDuration(MotionSpeedSafe(700)).setInterpolator(Anime.OUT_QUART).start();
            incoming.animate().scaleX(1.04f).scaleY(1.04f).translationX(panRight ? 10f * d : -10f * d)
                    .setDuration(SLIDE_MS + 700).setInterpolator(new LinearInterpolator()).start();

            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).setDuration(MotionSpeedSafe(700)).setInterpolator(Anime.OUT_QUART).start();

            mBackdropFront = toFront;
            if (mSlideUrls.length > 1) mSlideHandler.postDelayed(mSlideRunnable, SLIDE_MS);
        }, "stage_" + url, url);
    }

    private static long MotionSpeedSafe(long ms) { return net.kdt.pojavlaunch.utils.animation.MotionSpeed.scale(ms); }

    /** Tap = next slide; horizontal fling = next / previous. */
    private void installStageGestures() {
        if (mStage == null) return;
        final android.view.GestureDetector gd = new android.view.GestureDetector(requireContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(android.view.MotionEvent e) { userSlide(+1); return true; }
                    @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                        if (Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 600) { userSlide(vx < 0 ? +1 : -1); return true; }
                        return false;
                    }
                });
        mStage.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
    }

    private void userSlide(int dir) {
        if (mSlideUrls.length <= 1) { Anime.pulse(mStage); return; }
        // small nudge so the tap feels acknowledged even before the image lands
        if (mStage != null) {
            float d = getResources().getDisplayMetrics().density;
            mStage.animate().cancel();
            mStage.setTranslationX(dir > 0 ? 6f * d : -6f * d);
            mStage.animate().translationX(0f).setDuration(320).setInterpolator(Anime.OUT_BACK).start();
        }
        showSlide(mSlideIndex + dir);
    }

    // ── stage dots ──
    private void rebuildStageDots() {
        if (mStageDots == null) return;
        mStageDots.removeAllViews();
        int n = mSlideUrls.length;
        if (mStageCounter != null) mStageCounter.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        if (n <= 1) return;
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Math.min(n, 8); i++) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * d), (int) (6 * d));
            lp.setMarginStart((int) (4 * d));
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.rd_dot_off);
            mStageDots.addView(dot);
        }
        updateStageDots(mSlideIndex);
    }

    /** The active dot stretches into a pill (anime "morph"), the others shrink back. */
    private void updateStageDots(int active) {
        if (mStageCounter != null && mSlideUrls.length > 1) {
            mStageCounter.setText((active + 1) + " / " + mSlideUrls.length);
        }
        if (mStageDots == null) return;
        float d = getResources().getDisplayMetrics().density;
        int n = mStageDots.getChildCount();
        if (n == 0) return;
        int shownActive = Math.min(active, n - 1);
        for (int i = 0; i < n; i++) {
            View dot = mStageDots.getChildAt(i);
            boolean on = i == shownActive;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            int target = (int) ((on ? 16 : 6) * d);
            if (lp.width != target) {
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(lp.width, target);
                va.setDuration(MotionSpeedSafe(360));
                va.setInterpolator(Anime.OUT_EXPO);
                va.addUpdateListener(a -> { lp.width = (int) a.getAnimatedValue(); dot.setLayoutParams(lp); });
                va.start();
            }
            dot.setBackgroundResource(on ? R.drawable.rd_dot_on : R.drawable.rd_dot_off);
        }
    }

    /**
     * Stage imagery: ~220px wide keeps the picture legible on a 124dp stage while
     * staying cheap (one small allocation per slide, no RenderScript). The scrim
     * drawable handles readability, not a blur.
     */
    private Bitmap downscaleForStage(Bitmap src) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= 0 || h <= 0) return null;
            int tw = Math.min(BACKDROP_TINY_W, w);
            int th = Math.max(1, tw * h / w);
            return Bitmap.createScaledBitmap(src, tw, th, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private void recycleTinyA() { if (mTinyA != null && !mTinyA.isRecycled()) mTinyA.recycle(); mTinyA = null; }
    private void recycleTinyB() { if (mTinyB != null && !mTinyB.isRecycled()) mTinyB.recycle(); mTinyB = null; }

    // ─────────────────────── version detection ───────────────────────

    /** Reads the real Minecraft version of the profile the mod would land in. */
    private void detectCurrentVersion() {
        MinecraftProfile profile = currentProfile();
        mCurrentMcVersion = profile != null ? ProfileDetection.getMcVersion(profile) : null;
        if (mMcCurrent != null) {
            boolean known = mCurrentMcVersion != null && !mCurrentMcVersion.isEmpty();
            mMcCurrent.setText(known ? "MC " + mCurrentMcVersion : "Not detected");
            mMcCurrent.setBackgroundResource(known ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
            mMcCurrent.setTextColor(known ? 0xFF141519 : 0xFFC9CED8);
            // The pill pops once the target is known — the one fact that decides compatibility.
            mMcCurrent.setScaleX(0.7f); mMcCurrent.setScaleY(0.7f); mMcCurrent.setAlpha(0f);
            mMcCurrent.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(380).setDuration(360)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f)).start();
        }
    }

    @Nullable
    private MinecraftProfile currentProfile() {
        try {
            LauncherProfiles.load();
            if (LauncherProfiles.mainProfileJson == null
                    || LauncherProfiles.mainProfileJson.profiles == null) return null;
            if (mProfileKey != null) {
                MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(mProfileKey);
                if (p != null) return p;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Scores every published file against the detected Minecraft version and
     * the profile's mod loader. Nothing is guessed: the verdict comes from the
     * API's own supported-version lists and loader metadata.
     */
    private void computeCompatibility() {
        if (mModDetail == null || mModDetail.versionNames == null) return;
        MinecraftProfile profile = currentProfile();
        int n = mModDetail.versionNames.length;
        mCompatible = new boolean[n];

        for (int i = 0; i < n; i++) {
            String[] list = arr(mModDetail.mcVersionLists, i);
            String[] loaders = arr(mModDetail.versionLoaders, i);
            String mcVer = str(mModDetail.mcVersionNames, i);

            boolean versionOk = true;
            if (mCurrentMcVersion != null && !mCurrentMcVersion.isEmpty()) {
                if (list != null && list.length > 0) {
                    versionOk = false;
                    for (String s : list) {
                        if (s != null && ProfileDetection.isVersionCompatible(
                                mCurrentMcVersion, s.trim())) { versionOk = true; break; }
                    }
                } else if (mcVer != null && !mcVer.isEmpty()) {
                    versionOk = ProfileDetection.isVersionCompatible(mCurrentMcVersion, mcVer);
                }
            }

            // Loader verdict: resource packs / shaders / worlds never depend
            // on the loader, "minecraft"/"iris"-style tags are not loaders,
            // Quilt runs Fabric files. (Phase 8 — fixes the false
            // "NOT COMPATIBLE" on packs for modded profiles.)
            boolean loaderOk = ProfileDetection.isLoaderCompatible(
                    profile, mContentType, loaders, mCurrentMcVersion);
            mCompatible[i] = versionOk && loaderOk;
        }
    }

    private static String[] arr(String[][] src, int i) {
        return src != null && i < src.length ? src[i] : null;
    }

    private static String str(String[] src, int i) {
        return src != null && i < src.length ? src[i] : null;
    }

    /** Files are published newest-first, so the first compatible hit is the best pick. */
    private void autoSelectVersion() {
        int pick = -1;
        for (int i = 0; i < mCompatible.length; i++) {
            if (mCompatible[i]) { pick = i; break; }
        }
        if (pick < 0 && mModDetail != null && mModDetail.versionNames != null
                && mModDetail.versionNames.length > 0) {
            pick = 0; // nothing matches — surface the newest file, but block the install
        }
        selectVersion(pick, true);
    }

    private void selectVersion(int index, boolean auto) {
        mSelectedVersionIndex = index;
        if (mModDetail == null || index < 0) return;
        boolean ok = index < mCompatible.length && mCompatible[index];
        float d = getResources().getDisplayMetrics().density;

        if (mVersionChip != null) {
            String name = str(mModDetail.versionNames, index);
            String next = name != null ? name : "Unknown file";
            if (!auto && !next.contentEquals(mVersionChip.getText())) {
                // Text swap with a tiny vertical flip so the change is noticed.
                mVersionChip.animate().cancel();
                mVersionChip.animate().alpha(0f).translationY(-5f * d).setDuration(110)
                        .withEndAction(() -> {
                            mVersionChip.setText(next);
                            mVersionChip.setTranslationY(6f * d);
                            mVersionChip.animate().alpha(1f).translationY(0f).setDuration(220)
                                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                        }).start();
            } else {
                mVersionChip.setText(next);
            }
        }

        if (mCompatWarning != null) {
            if (ok) {
                if (mCompatWarning.getVisibility() == View.VISIBLE) {
                    mCompatWarning.animate().cancel();
                    mCompatWarning.animate().alpha(0f).translationY(-6f * d).setDuration(160)
                            .withEndAction(() -> {
                                mCompatWarning.setVisibility(View.GONE);
                                mCompatWarning.setAlpha(1f);
                                mCompatWarning.setTranslationY(0f);
                            }).start();
                }
            } else {
                mCompatWarning.setText(getString(R.string.mod_detail_incompatible_notice,
                        mCurrentMcVersion != null && !mCurrentMcVersion.isEmpty()
                                ? mCurrentMcVersion : "this profile"));
                if (mCompatWarning.getVisibility() != View.VISIBLE) {
                    mCompatWarning.animate().cancel();
                    mCompatWarning.setVisibility(View.VISIBLE);
                    mCompatWarning.setAlpha(0f);
                    mCompatWarning.setTranslationY(8f * d);
                    mCompatWarning.animate().alpha(1f).translationY(0f).setDuration(280)
                            .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                }
            }
        }

        if (mDownloadButton != null) {
            boolean installed = isAlreadyInstalled(index);
            mDownloadButton.animate().cancel();
            mDownloadButton.setScaleX(1f);
            mDownloadButton.setScaleY(1f);
            if (installed) {
                // Already in this profile: the button goes green (that is the
                // state) and the label on it is UNINSTALL (that is the action).
                mDownloadButton.setEnabled(true);
                mDownloadButton.setAlpha(1f);
                mDownloadButton.setBackgroundResource(R.drawable.rd_btn_installed);
                mDownloadButton.setTextColor(0xFF141519);
                mDownloadButton.setText(R.string.mod_detail_uninstall);
                mDownloadButton.setOnClickListener(v -> confirmUninstall(index));
            } else {
                mDownloadButton.setBackgroundResource(R.drawable.rd_btn_primary);
                mDownloadButton.setTextColor(0xFF141519);
                mDownloadButton.setEnabled(ok);
                mDownloadButton.setAlpha(ok ? 1f : 0.45f);
                if (ok) mDownloadButton.setText(installLabel());
                else mDownloadButton.setText(R.string.mod_detail_blocked);
                if (ok) {
                    mDownloadButton.setOnClickListener(v -> handleDownload());
                } else {
                    mDownloadButton.setOnClickListener(null);
                }
            }
            if (!auto) {
                // Any manual change: the dock button pulses once to confirm.
                Anime.pulse(mDownloadButton);
            } else if (ok && !installed) {
                // First good match found: a single delayed pulse says "you can press me".
                mDownloadButton.postDelayed(() -> { if (isAdded()) Anime.pulse(mDownloadButton); }, 900);
            }
        }

        // selected-row companions
        if (mSelectedMeta != null) {
            String m = metaLine(index);
            Anime.swapText(mSelectedMeta, m.isEmpty() ? (ok ? "Matches your profile" : "Does not match your profile") : m);
        }
        if (mSelectedState != null) {
            boolean installed = isAlreadyInstalled(index);
            mSelectedState.setText(installed ? "INSTALLED" : ok ? "COMPATIBLE" : "MISMATCH");
            mSelectedState.setTextColor(installed ? 0xFFD2D6DE : ok ? 0xFFD2D6DE : 0xFFE8C989);
            if (!auto) Anime.pop(mSelectedState);
        }
        if (mSelectedCheck != null) {
            mSelectedCheck.setText(ok ? "✓" : "!");
            mSelectedCheck.setBackgroundResource(ok ? R.drawable.rd_pill_silver : R.drawable.rd_warn);
            mSelectedCheck.setTextColor(ok ? 0xFF141519 : 0xFFE8C989);
            if (!auto) Anime.pop(mSelectedCheck);
        }
        if (mSelectedRow != null) {
            mSelectedRow.setBackgroundResource(ok ? R.drawable.rd_selected_row : R.drawable.rd_row);
            if (!auto) Anime.pulse(mSelectedRow);
        }
        refreshQuickPicks(index);

        populateDependencies(index);
        populateCompatChips(index);
        highlightSelectedRow(index, auto);
    }

    /** Re-skins the version rows in place (no rebuild) so the selection ring
     *  slides between rows instead of the whole list flashing. */
    private void highlightSelectedRow(int index, boolean auto) {
        if (mVersionsContainer == null) return;
        for (int i = 0; i < mVersionsContainer.getChildCount(); i++) {
            View row = mVersionsContainer.getChildAt(i);
            Object tag = row.getTag();
            if (!(tag instanceof Integer)) continue;
            boolean sel = ((Integer) tag) == index;
            boolean wasSel = Boolean.TRUE.equals(row.getTag(R.id.detail_versions_container));
            if (sel == wasSel && !auto) continue;
            row.setBackgroundResource(sel ? R.drawable.rd_row_selected : R.drawable.rd_row);
            row.setTag(R.id.detail_versions_container, sel);
            View check = row.findViewWithTag("rd_check");
            if (check instanceof TextView) {
                TextView disc = (TextView) check;
                Object idx = row.getTag();
                disc.setText(sel ? "✓" : String.valueOf(((Integer) idx) + 1));
                disc.setTextSize(sel ? 12f : 9.5f);
                disc.setTextColor(sel ? 0xFF141519 : 0xFF9AA0AC);
                disc.setBackgroundResource(sel ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
                if (sel && !auto) Anime.pop(disc);
            }
            if (sel && !auto) {
                row.animate().cancel();
                row.setScaleX(0.985f); row.setScaleY(0.985f);
                row.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            }
        }
    }

    private void populateCompatChips(int index) {
        if (mCompatRow == null) return;
        mCompatRow.removeAllViews();
        String[] loaders = arr(mModDetail.versionLoaders, index);
        if (loaders != null) {
            for (String l : loaders) {
                if (l == null || l.isEmpty()) continue;
                addChip(capitalise(l), true);
            }
        }
        String[] list = arr(mModDetail.mcVersionLists, index);
        if (list != null && list.length > 0) {
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (String v : list) {
                if (v == null || v.isEmpty()) continue;
                if (shown > 0) sb.append("  •  ");
                sb.append(v.trim());
                if (++shown >= 4) break;
            }
            if (list.length > 4) sb.append("  •  +").append(list.length - 4);
            if (sb.length() > 0) addChip(sb.toString(), false);
        } else {
            String mcVer = str(mModDetail.mcVersionNames, index);
            if (mcVer != null && !mcVer.isEmpty()) addChip(mcVer, false);
        }
    }

    private void addChip(String text, boolean filled) {
        TextView chip = new TextView(requireContext());
        chip.setText(text);
        chip.setTextSize(8.5f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setSingleLine(true);
        chip.setLetterSpacing(0.05f);
        chip.setGravity(Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setTextColor(filled ? 0xFF14151A : 0xFFC9CED8);
        chip.setBackgroundResource(filled ? R.drawable.rd_chip_on : R.drawable.rd_chip_off);
        float d = getResources().getDisplayMetrics().density;
        int padH = (int) (10 * d);
        int padV = (int) (5.5f * d);
        chip.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (6 * d));
        int index = mCompatRow.getChildCount();
        mCompatRow.addView(chip, lp);

        // Chips pop in one after another (scale 0.7 → 1 with a small overshoot).
        chip.setAlpha(0f);
        chip.setScaleX(0.7f);
        chip.setScaleY(0.7f);
        chip.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(60L + index * 55L)
                .setDuration(320)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f))
                .start();
    }

    // ─────────────────────── change version ───────────────────────

    private void showChangeVersionDialog() {
        if (mModDetail == null || mModDetail.versionNames == null) {
            Anime.shake(mSelectedRow != null ? mSelectedRow : mBtnChangeVersion);
            return;
        }
        final int n = mModDetail.versionNames.length;
        String[] names = new String[n];
        String[] meta = new String[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            String name = str(mModDetail.versionNames, i);
            names[i] = name != null ? name : "File " + (i + 1);
            meta[i] = metaLine(i);
            ok[i] = i < mCompatible.length && mCompatible[i];
        }
        // Phase 8 picker: rail filters need the per-file MC lists + loaders and
        // the profile's own loader so the right chips start selected.
        String profileLoader = detectProfileLoader();
        String target = mCurrentMcVersion != null && !mCurrentMcVersion.isEmpty()
                ? "MC " + mCurrentMcVersion + (profileLoader != null ? "  ·  " + capitalise(profileLoader) : "")
                : null;
        String[][] mcLists = new String[n][];
        String[][] loaderLists = new String[n][];
        for (int i = 0; i < n; i++) {
            String[] list = arr(mModDetail.mcVersionLists, i);
            String single = str(mModDetail.mcVersionNames, i);
            mcLists[i] = list != null && list.length > 0 ? list
                    : (single != null && !single.isEmpty() ? new String[]{single} : new String[0]);
            String[] loaders = arr(mModDetail.versionLoaders, i);
            loaderLists[i] = loaders != null ? loaders : new String[0];
        }
        ModVersionSheet sheet = ModVersionSheet.newInstance(
                mModItem != null ? mModItem.title : "", target, names, meta, ok, mSelectedVersionIndex)
                .withFilters(mcLists, loaderLists, mCurrentMcVersion,
                        ProfileDetection.isLoaderAgnosticContent(mContentType) ? null : profileLoader);
        sheet.setListener(this);
        sheet.show(getChildFragmentManager(), ModVersionSheet.TAG);
    }

    /** fabric / forge / neoforge / quilt / liteloader — or null for vanilla / unknown. */
    @Nullable
    private String detectProfileLoader() {
        MinecraftProfile profile = currentProfile();
        if (profile == null) return null;
        String[] known = {"neoforge", "fabric", "quilt", "forge", "liteloader"};
        for (String k : known) if (ProfileDetection.hasLoader(profile, k)) return k;
        return null;
    }

    /** {@link ModVersionSheet.Listener} — also reached after a config change (parent-fragment fallback). */
    @Override
    public void onVersionPicked(int index) {
        if (!isAdded() || mModDetail == null) return;
        selectVersion(index, false);
        if (mScrollContent instanceof androidx.core.widget.NestedScrollView) {
            mScrollContent.post(() -> ((androidx.core.widget.NestedScrollView) mScrollContent).smoothScrollTo(0, 0));
        }
    }

    /** "1.21.1  ·  Fabric, Quilt" style one-liner for file {@code i}. */
    private String metaLine(int i) {
        StringBuilder meta = new StringBuilder();
        String mc = str(mModDetail.mcVersionNames, i);
        String[] list = arr(mModDetail.mcVersionLists, i);
        if (list != null && list.length > 1) {
            meta.append(list[0]);
            if (list.length > 1) meta.append(" – ").append(list[list.length - 1]);
        } else if (mc != null && !mc.isEmpty()) meta.append(mc);
        String[] loaders = arr(mModDetail.versionLoaders, i);
        if (loaders != null && loaders.length > 0) {
            if (meta.length() > 0) meta.append("  ·  ");
            for (int k = 0; k < loaders.length; k++) {
                if (k > 0) meta.append(", ");
                meta.append(capitalise(loaders[k]));
            }
        }
        return meta.toString();
    }

    /** Newest compatible files as one-tap chips under the selected row. */
    private void populateQuickPicks() {
        if (mQuickVersions == null || mModDetail == null || mModDetail.versionNames == null) return;
        mQuickVersions.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int added = 0;
        for (int i = 0; i < mModDetail.versionNames.length && added < 6; i++) {
            boolean ok = i < mCompatible.length && mCompatible[i];
            if (!ok) continue;
            final int idx = i;
            boolean sel = i == mSelectedVersionIndex;
            TextView chip = new TextView(requireContext());
            String name = str(mModDetail.versionNames, i);
            chip.setText(shortName(name != null ? name : "File " + (i + 1)));
            chip.setTextSize(9.5f);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            chip.setMaxWidth((int) (150 * d));
            chip.setIncludeFontPadding(false);
            chip.setGravity(Gravity.CENTER);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setTextColor(sel ? 0xFF141519 : 0xFFC9CED8);
            chip.setBackgroundResource(sel ? R.drawable.rd_quick_chip_on : R.drawable.rd_quick_chip);
            chip.setPadding((int) (11 * d), (int) (6 * d), (int) (11 * d), (int) (6 * d));
            chip.setTag(idx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (6 * d));
            mQuickVersions.addView(chip, lp);
            net.kdt.pojavlaunch.UiMotion.pressFeedback(chip);
            chip.setOnClickListener(v -> selectVersion(idx, false));
            added++;
        }
        boolean show = added > 1;
        if (mQuickLabel != null) mQuickLabel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (mQuickScroll != null) mQuickScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) Anime.stagger(mQuickVersions, 260, 50, Anime.Fx.POP);
    }

    /** Re-skins quick chips in place so the highlight slides instead of the strip rebuilding. */
    private void refreshQuickPicks(int selected) {
        if (mQuickVersions == null) return;
        for (int i = 0; i < mQuickVersions.getChildCount(); i++) {
            View c = mQuickVersions.getChildAt(i);
            if (!(c instanceof TextView) || !(c.getTag() instanceof Integer)) continue;
            boolean sel = ((Integer) c.getTag()) == selected;
            boolean was = c.getBackground() != null && Boolean.TRUE.equals(c.getTag(R.id.detail_quick_versions));
            c.setTag(R.id.detail_quick_versions, sel);
            ((TextView) c).setTextColor(sel ? 0xFF141519 : 0xFFC9CED8);
            c.setBackgroundResource(sel ? R.drawable.rd_quick_chip_on : R.drawable.rd_quick_chip);
            if (sel && !was) Anime.pulse(c);
        }
    }

    /** Trims "sodium-fabric-0.6.0+mc1.21.1 - 1.21.1" down to the part people recognise. */
    private static String shortName(String name) {
        String n = name;
        int dash = n.indexOf(" - ");
        if (dash > 0) n = n.substring(0, dash);
        if (n.length() > 26) n = n.substring(0, 25) + "…";
        return n;
    }

    // ─────────────────────── detail loading ───────────────────────

    private void loadModDetails() {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                ModDetail detail = mModpackApi.getModDetails(mModItem);
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    if (detail != null && detail.versionNames != null && detail.versionNames.length > 0) {
                        mModDetail = detail;
                        onDetailsLoaded();
                    } else {
                        Toast.makeText(requireContext(),
                                R.string.search_modpack_download_error, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Failed to load mod details", e);
                Tools.runOnUiThread(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(),
                                R.string.search_modpack_download_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void onDetailsLoaded() {
        net.kdt.pojavlaunch.UiMotion.countUp(mStatVersions, mModDetail.versionNames.length, 700, null, null);
        mStatUpdated.setText(relativeTime(latestVersionName()));
        popText(mStatUpdated);
        refreshSlideUrls();
        populateGallery();
        computeCompatibility();
        autoSelectVersion();
        populateQuickPicks();
        populateVersionHistory();
        populateLinks();
        if (mVersionsCount != null) {
            int okCount = 0;
            for (boolean b : mCompatible) if (b) okCount++;
            mVersionsCount.setText(okCount + " of " + mModDetail.versionNames.length + " compatible");
            Anime.pop(mVersionsCount);
        }
    }

    private String latestVersionName() {
        return mModDetail.versionNames != null && mModDetail.versionNames.length > 0
                ? mModDetail.versionNames[0] : null;
    }

    /** A rough recency label derived from the newest published file name. */
    private String relativeTime(String name) {
        if (name == null || name.isEmpty()) return "—";
        if (name.length() >= 10 && name.charAt(4) == '-') {
            return name.substring(0, 10);
        }
        return "—";
    }

    private void populateGallery() {
        String[] shots = mModDetail != null && mModDetail.screenshotUrls != null ? mModDetail.screenshotUrls : mPendingGallery;
        if (shots == null || shots.length == 0 || mGalleryCard == null || mScreenshotContainer == null) return;
        if (mScreenshotContainer.getChildCount() == shots.length) return; // already built for this set
        float d = getResources().getDisplayMetrics().density;
        if (mGalleryCard.getVisibility() != View.VISIBLE) {
            mGalleryCard.setVisibility(View.VISIBLE);
            mGalleryCard.setAlpha(0f);
            mGalleryCard.setTranslationY(16f * d);
            mGalleryCard.animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        mScreenshotContainer.removeAllViews();
        int h = (int) (120 * d);
        int w = (int) (196 * d);
        int gap = (int) (8 * d);
        int i = 0;
        for (String url : shots) {
            if (url == null || url.isEmpty()) continue;
            ImageView iv = new ImageView(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMarginEnd(gap);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundResource(R.drawable.rd_thumb);
            iv.setClipToOutline(true);
            iv.setAlpha(0f);
            iv.setTranslationX(24f * d);
            iv.setScaleX(0.94f);
            iv.setScaleY(0.94f);
            iv.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(80L + i * 70L).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            net.kdt.pojavlaunch.UiMotion.pressFeedback(iv);
            final String tapUrl = url;
            iv.setOnClickListener(v -> {
                for (int k = 0; k < mSlideUrls.length; k++) {
                    if (tapUrl.equals(mSlideUrls[k])) { showSlide(k); break; }
                }
                if (mStage != null) Anime.pulse(mStage);
            });
            mScreenshotContainer.addView(iv);
            mIconCache.getImage(bitmap -> {
                if (bitmap != null && isAdded()) {
                    iv.setImageBitmap(bitmap);
                    // gentle Ken-Burns settle when the real image lands
                    iv.setScaleX(1.06f); iv.setScaleY(1.06f);
                    iv.animate().scaleX(1f).scaleY(1f).setDuration(600)
                            .setInterpolator(new DecelerateInterpolator()).start();
                }
            }, "screenshot_" + url, url);
            i++;
        }
    }

    private void populateVersionHistory() {
        mVersionsContainer.removeAllViews();
        int count = Math.min(12, mModDetail.versionNames.length);
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < count; i++) {
            String name = str(mModDetail.versionNames, i);
            String mc = str(mModDetail.mcVersionNames, i);
            String[] loaders = arr(mModDetail.versionLoaders, i);
            String changelog = mModDetail.versionChangelogs != null && i < mModDetail.versionChangelogs.length
                    ? mModDetail.versionChangelogs[i] : null;
            boolean ok = i < mCompatible.length && mCompatible[i];
            boolean selected = i == mSelectedVersionIndex;
            View row = buildVersionRow(name, mc, loaders, changelog, ok, selected, i);
            mVersionsContainer.addView(row);
            // Rows rise in a cascade the first time the list is built.
            if (mFirstDetailReveal) {
                row.setAlpha(0f);
                row.setTranslationY(14f * d);
                row.animate().alpha(1f).translationY(0f)
                        .setStartDelay(140L + Math.min(i, 8) * 45L).setDuration(340)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            }
        }
        mFirstDetailReveal = false;
    }

    private View buildVersionRow(String name, String mc, String[] loaders, String changelog,
                                 boolean ok, boolean selected, int index) {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(selected ? R.drawable.rd_row_selected : R.drawable.rd_row);
        row.setTag(index);
        row.setTag(R.id.detail_versions_container, selected);
        int padH = (int) (12 * d), padV = (int) (11 * d);
        row.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (7 * d);
        row.setLayoutParams(lp);

        // ── index disc that becomes a check when selected ──
        TextView check = new TextView(requireContext());
        check.setTag("rd_check");
        check.setText(selected ? "✓" : String.valueOf(index + 1));
        check.setTextSize(selected ? 12f : 9.5f);
        check.setGravity(Gravity.CENTER);
        check.setIncludeFontPadding(false);
        check.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        check.setTextColor(selected ? 0xFF141519 : 0xFF9AA0AC);
        check.setBackgroundResource(selected ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams((int) (26 * d), (int) (26 * d));
        clp.setMarginEnd((int) (11 * d));
        row.addView(check, clp);

        // ── text column ──
        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(col, colLp);

        TextView title = new TextView(requireContext());
        title.setText(name != null ? name : "File " + (index + 1));
        title.setTextSize(11.5f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTextColor(ok ? 0xFFF4F6F9 : 0xFF9AA0AC);
        col.addView(title);

        String meta = metaLine(index);
        if (!meta.isEmpty()) {
            TextView sub = new TextView(requireContext());
            sub.setText(meta);
            sub.setTextSize(9f);
            sub.setIncludeFontPadding(false);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub.setTextColor(0xFF8A909C);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = (int) (3 * d);
            col.addView(sub, slp);
        }
        if (changelog != null && !changelog.trim().isEmpty()) {
            TextView ch = new TextView(requireContext());
            String t = changelog.trim().replace('\n', ' ');
            ch.setText(t.length() > 140 ? t.substring(0, 139) + "…" : t);
            ch.setTextSize(9f);
            ch.setTextColor(0xFF6B717D);
            ch.setMaxLines(2);
            ch.setEllipsize(android.text.TextUtils.TruncateAt.END);
            ch.setLineSpacing(2f, 1f);
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chLp.topMargin = (int) (4 * d);
            col.addView(ch, chLp);
        }

        // ── right-side status pill ──
        TextView status = new TextView(requireContext());
        boolean newest = ok && index == firstCompatible();
        status.setText(newest ? "LATEST" : ok ? "OK" : "MISMATCH");
        status.setTextSize(7f);
        status.setLetterSpacing(0.08f);
        status.setIncludeFontPadding(false);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setGravity(Gravity.CENTER);
        status.setTextColor(ok ? 0xFFD2D6DE : 0xFFE8C989);
        status.setBackgroundResource(R.drawable.rd_pill);
        status.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.setMarginStart((int) (8 * d));
        row.addView(status, stLp);

        net.kdt.pojavlaunch.UiMotion.pressFeedback(row);
        row.setOnClickListener(v -> selectVersion(index, false));
        return row;
    }

    private int firstCompatible() {
        for (int i = 0; i < mCompatible.length; i++) if (mCompatible[i]) return i;
        return -1;
    }

    private void populateDependencies(int index) {
        String[][] ids = mModDetail.versionDependencyIds;
        String[][] types = mModDetail.versionDependencyTypes;
        float d = getResources().getDisplayMetrics().density;
        if (ids == null || index >= ids.length || ids[index] == null || ids[index].length == 0) {
            mDepsCard.setVisibility(View.GONE);
            return;
        }
        if (mDepsCard.getVisibility() != View.VISIBLE) {
            mDepsCard.setVisibility(View.VISIBLE);
            mDepsCard.setAlpha(0f);
            mDepsCard.setTranslationY(12f * d);
            mDepsCard.animate().alpha(1f).translationY(0f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
        mDependenciesContainer.removeAllViews();
        for (int i = 0; i < ids[index].length; i++) {
            String type = (types != null && index < types.length && types[index] != null
                    && i < types[index].length && types[index][i] != null)
                    ? types[index][i] : "required";
            boolean required = "required".equalsIgnoreCase(type);

            LinearLayout line = new LinearLayout(requireContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView kind = new TextView(requireContext());
            kind.setText(required ? "REQUIRED" : "OPTIONAL");
            kind.setTextSize(7f);
            kind.setLetterSpacing(0.08f);
            kind.setIncludeFontPadding(false);
            kind.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            kind.setGravity(Gravity.CENTER);
            kind.setTextColor(required ? 0xFF141519 : 0xFFC9CED8);
            kind.setBackgroundResource(required ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
            kind.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            klp.setMarginEnd((int) (10 * d));
            line.addView(kind, klp);

            TextView idText = new TextView(requireContext());
            idText.setText(ids[index][i]);
            idText.setTextSize(10.5f);
            idText.setIncludeFontPadding(false);
            idText.setSingleLine(true);
            idText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            idText.setTextColor(required ? 0xFFF4F6F9 : 0xFF9AA0AC);
            line.addView(idText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (7 * d);
            mDependenciesContainer.addView(line, lp);

            line.setAlpha(0f);
            line.setTranslationX(10f * d);
            line.animate().alpha(1f).translationX(0f).setStartDelay(60L + i * 50L).setDuration(280)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }

    private void populateLinks() {
        String url = mModItem != null ? mModItem.websiteUrl : null;
        if (url == null || url.isEmpty()) {
            mLinksCard.setVisibility(View.GONE);
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        mLinksCard.setVisibility(View.VISIBLE);
        mLinksContainer.removeAllViews();

        LinearLayout line = new LinearLayout(requireContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackgroundResource(R.drawable.rd_row);
        line.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));

        TextView label = new TextView(requireContext());
        label.setText("Project page");
        label.setTextSize(10.5f);
        label.setIncludeFontPadding(false);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(0xFFF4F6F9);
        line.addView(label);

        TextView urlText = new TextView(requireContext());
        urlText.setText(url.replaceFirst("^https?://", ""));
        urlText.setTextSize(9f);
        urlText.setIncludeFontPadding(false);
        urlText.setSingleLine(true);
        urlText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        urlText.setTextColor(0xFF8A909C);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ulp.setMarginStart((int) (10 * d));
        line.addView(urlText, ulp);

        TextView chev = new TextView(requireContext());
        chev.setText("↗");
        chev.setTextSize(12f);
        chev.setGravity(Gravity.CENTER);
        chev.setIncludeFontPadding(false);
        chev.setTextColor(0xFFD2D6DE);
        chev.setBackgroundResource(R.drawable.csc_chev_pill);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams((int) (26 * d), (int) (26 * d));
        clp.setMarginStart((int) (8 * d));
        line.addView(chev, clp);

        net.kdt.pojavlaunch.UiMotion.pressFeedback(line);
        line.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)));
            } catch (Throwable ignored) {}
        });
        mLinksContainer.addView(line);
    }

    // ─────────────────────── install ───────────────────────

    private void handleDownload() {
        if (mModDetail == null || mSelectedVersionIndex < 0) return;
        if (mSelectedVersionIndex >= mCompatible.length || !mCompatible[mSelectedVersionIndex]) {
            Toast.makeText(requireContext(), R.string.mod_detail_blocked_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        String url = (mModDetail.versionUrls != null
                && mSelectedVersionIndex < mModDetail.versionUrls.length)
                ? mModDetail.versionUrls[mSelectedVersionIndex] : null;
        if (url == null || url.isEmpty()) {
            Toast.makeText(requireContext(),
                    R.string.modpack_install_download_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        // One assignment, not two. The download runs in a lambda, and a local
        // that is reassigned is not effectively final — javac refuses the capture
        // with "local variables referenced from a lambda expression must be final".
        final String fileName = downloadFileName(url);

        java.io.File dir = getTargetDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(requireContext(), R.string.mod_update_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final java.io.File tmp = new java.io.File(dir, fileName + ".part");
        final java.io.File target = new java.io.File(dir, fileName);
        final int index = mSelectedVersionIndex;

        // Same-named file already sitting there (installed from another page, or
        // a leftover of a crashed run): never download it a second time.
        if (target.isFile() && target.length() > 0) {
            Toast.makeText(requireContext(), R.string.mod_detail_install_done,
                    Toast.LENGTH_SHORT).show();
            selectVersion(index, true);
            return;
        }
        // Same bytes already in the folder under the name the CDN gives and the
        // folder does not: the store hands out "modid-1.2.0.jar" style names that
        // differ from whatever the user pulled a week ago, so the index check
        // above cannot see it and used to fetch 40 MB again for nothing.
        java.io.File sameBytes = net.kdt.pojavlaunch.modloaders.modpacks.InstalledModFolder
                .exactDuplicate(target, dir);
        if (sameBytes != null && !sameBytes.equals(target)) {
            Toast.makeText(requireContext(),
                    R.string.mod_detail_already_in_folder, Toast.LENGTH_LONG).show();
            // Trust the disk over the index: record it so the card agrees.
            recordInstall(target.getName(), verOrNull(index));
            selectVersion(index, true);
            return;
        }
        // A .part from an interrupted attempt is dead weight and would be appended
        // to by some code paths — drop it before starting fresh.
        if (tmp.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }

        if (mDownloadButton != null) {
            mDownloadButton.setEnabled(false);
            mDownloadButton.setAlpha(0.6f);
            mDownloadButton.setText(R.string.mod_detail_installing);
            mDownloadButton.setOnClickListener(null);
        }

        String title = mModItem != null ? mModItem.title : "Mod";
        String ver = (mModDetail.versionNames != null
                && index < mModDetail.versionNames.length
                && mModDetail.versionNames[index] != null) ? mModDetail.versionNames[index] : "";
        String img = mModItem != null ? mModItem.imageUrl : null;
        String type = mContentType != null ? mContentType : "mod";

        PojavApplication.sExecutorService.execute(() -> {
            boolean ok;
            try {
                DownloadUtils.downloadFileMonitored(url, tmp, null,
                        new DownloaderProgressWrapper(
                                R.string.modpack_download_downloading_mods,
                                com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK,
                                title, ver, img, type));
                ok = tmp.isFile() && tmp.length() > 0 && tmp.renameTo(target);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();          // no half-written jar left to confuse the next run
            } else {
                recordInstall(fileName, ver);
            }
            // Without this the notification is left at 100 % forever: the download
            // loop stops reporting when the stream ends, and only clearProgress
            // retires the record. The old ModInstallFragment path always called it;
            // this page's newer path did not, so a finished install looked stuck.
            com.kdt.mcgui.ProgressLayout.clearProgress(
                    com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK);
            final boolean done = ok;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                net.kdt.pojavlaunch.modloaders.modpacks.InstalledModFolder.invalidate();
                Toast.makeText(requireContext(),
                        done ? R.string.mod_detail_install_done : R.string.mod_update_failed,
                        Toast.LENGTH_SHORT).show();
                selectVersion(index, true);
            });
        });
    }

    /** Removes the installed file for this version after a confirmation. */
    private void confirmUninstall(int index) {
        java.io.File f = installedFileFor(index);
        if (f == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.mod_detail_uninstall_confirm_title)
                .setMessage(getString(R.string.mod_detail_uninstall_confirm_message, f.getName()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mod_detail_uninstall, (d, w) -> {
                    boolean deleted = f.delete();
                    Toast.makeText(requireContext(),
                            deleted ? R.string.mod_detail_uninstalled : R.string.mod_update_failed,
                            Toast.LENGTH_SHORT).show();
                    selectVersion(index, true);
                })
                .show();
    }

    private java.io.File installedFileFor(int index) {
        if (mModDetail == null || mModDetail.versionUrls == null
                || index >= mModDetail.versionUrls.length) return null;
        String name = fileNameFromUrl(mModDetail.versionUrls[index]);
        if (name.isEmpty()) return null;
        java.io.File dir = getTargetDir();
        java.io.File f = new java.io.File(dir, name);
        if (f.isFile()) return f;
        java.io.File d = new java.io.File(dir, name + ".disabled");
        return d.isFile() ? d : null;
    }

    // ─────────────────────── misc ───────────────────────

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    /** Rolls the download counter up to its value ("1.2M" style) instead of popping it. */
    private void animateDownloads(String downloads) {
        if (mStatDownloads == null) return;
        if (downloads == null || downloads.isEmpty()) { mStatDownloads.setText("—"); return; }
        try {
            long n = Long.parseLong(downloads);
            if (n >= 1_000_000) {
                net.kdt.pojavlaunch.UiMotion.countUp(mStatDownloads, (int) (n / 1_000_000), 900, null, "M");
            } else if (n >= 1000) {
                net.kdt.pojavlaunch.UiMotion.countUp(mStatDownloads, (int) (n / 1000), 900, null, "K");
            } else {
                net.kdt.pojavlaunch.UiMotion.countUp(mStatDownloads, (int) n, 700, null, null);
            }
        } catch (Exception e) {
            mStatDownloads.setText(formatDownloads(downloads));
        }
    }

    private static void popText(View v) {
        if (v == null) return;
        v.setScaleX(0.8f); v.setScaleY(0.8f); v.setAlpha(0.4f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
    }

    private String formatDownloads(String downloads) {
        if (downloads == null || downloads.isEmpty()) return "—";
        try {
            long d = Long.parseLong(downloads);
            if (d >= 1000000) return (d / 1000000) + "M";
            if (d >= 1000) return (d / 1000) + "K";
            return String.valueOf(d);
        } catch (Exception e) {
            return downloads;
        }
    }

    private void animateEntrance() {
        final float d = getResources().getDisplayMetrics().density;

        // ── anime.js-style timeline ────────────────────────────────────────
        // 0    left column slides in from the left (outExpo)
        Anime.in(mSidePanel, Anime.Fx.FADE_RIGHT, 0, 620, Anime.OUT_EXPO);
        // 60   stage settles from a 1.08 zoom
        if (mStage != null) {
            mStage.setScaleX(1.08f); mStage.setScaleY(1.08f);
            mStage.animate().scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(1000)
                    .setInterpolator(Anime.OUT_EXPO).start();
        }
        // 180  hero icon jelly-pops, source badge outElastic, title tightens, subtitle rises
        if (mModIcon != null) net.kdt.pojavlaunch.UiMotion.heroIn(mModIcon);
        if (mSourceBadge != null) mSourceBadge.postDelayed(() -> Anime.pop(mSourceBadge), 560);
        Anime.in(mModTitle, Anime.Fx.FADE_UP, 220, 560, Anime.OUT_EXPO);
        Anime.tightenTitle(mModTitle, 220);
        Anime.in(mModSubtitle, Anime.Fx.FADE_UP, 290, 560, Anime.OUT_EXPO);
        // 340  stat tiles stagger(60) scale-in with outBack
        if (mStatsRow instanceof android.view.ViewGroup) Anime.stagger((android.view.ViewGroup) mStatsRow, 340, 60, Anime.Fx.SCALE_IN);
        // 120  right column slides in from the right; cards cascade upward
        Anime.in(mScrollContent, Anime.Fx.FADE_LEFT, 120, 620, Anime.OUT_EXPO);
        Anime.in(mInstallConsole, Anime.Fx.FADE_UP, 240, 620, Anime.OUT_EXPO);
        Anime.in(mSelectedRow, Anime.Fx.FLIP_UP, 380, 640, Anime.OUT_EXPO);
        Anime.in(mAboutCard, Anime.Fx.FADE_UP, 330, 620, Anime.OUT_EXPO);
        Anime.in(mVersionsCard, Anime.Fx.FADE_UP, 420, 620, Anime.OUT_EXPO);
        // 480  action dock springs up last
        if (mBottomBar != null) {
            mBottomBar.setAlpha(0f);
            mBottomBar.setTranslationY(40f * d);
            mBottomBar.animate().alpha(1f).translationY(0f)
                    .setStartDelay(480).setDuration(560)
                    .setInterpolator(Anime.SPRING).start();
        }
        // parallax: the artwork stage drifts as the right column scrolls
        if (mScrollContent instanceof androidx.core.widget.NestedScrollView && mStage != null) {
            ((androidx.core.widget.NestedScrollView) mScrollContent).setOnScrollChangeListener(
                    (androidx.core.widget.NestedScrollView.OnScrollChangeListener)
                            (v, sx, sy, osx, osy) -> mStage.setTranslationY(-Math.min(sy, 400) * 0.06f));
        }
    }

    private static void riseIn(View v, long delay, long dur, float fromY,
                               android.view.animation.Interpolator in) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(fromY);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(dur)
                .setInterpolator(in).start();
    }

    /** Uppercase content-type badge for the command bar. */
    private String typeBadgeLabel() {
        switch (mContentType == null ? "mod" : mContentType) {
            case "modpack": return "MODPACK";
            case "resourcepack": return "RESOURCE PACK";
            case "shader": return "SHADER";
            case "world": return "WORLD";
            case "datapack": return "DATAPACK";
            default: return "MOD";
        }
    }

    @Override
    public void onDestroyView() {
        mSlideHandler.removeCallbacks(mSlideRunnable);
        recycleTinyA();
        recycleTinyB();
        super.onDestroyView();
    }

    /** "Install Mod" / "Install Modpack" / "Install Resource Pack" / "Install Shader". */
    private String installLabel() {
        switch (mContentType == null ? "mod" : mContentType) {
            case "modpack": return getString(R.string.mod_detail_install_modpack);
            case "resourcepack": return getString(R.string.mod_detail_install_resourcepack);
            case "shader": return getString(R.string.mod_detail_install_shader);
            case "world":
            case "datapack": return getString(R.string.mod_detail_install_datapack);
            default: return getString(R.string.mod_detail_install_mod);
        }
    }

    /** True when the selected file is already present in this profile's folder. */
    private boolean isAlreadyInstalled(int index) {
        if (mModDetail == null || mModDetail.versionUrls == null
                || index >= mModDetail.versionUrls.length) return false;
        String url = mModDetail.versionUrls[index];
        if (url == null) return false;
        String name = fileNameFromUrl(url);
        if (name.isEmpty()) return false;
        java.io.File dir = getTargetDir();
        return new java.io.File(dir, name).isFile()
                || new java.io.File(dir, name + ".disabled").isFile();
    }

    private static String fileNameFromUrl(String url) {
        String n = url;
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        return n.trim();
    }

    private String downloadFileName(String url) {
        String name = fileNameFromUrl(url);
        return name.isEmpty() ? (mModItem != null ? mModItem.title : "mod") + ".jar" : name;
    }

    @Nullable
    private String verOrNull(int index) {
        try {
            return (mModDetail != null && mModDetail.versionNames != null
                    && index >= 0 && index < mModDetail.versionNames.length)
                    ? mModDetail.versionNames[index] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The browse list reads InstalledContentTracker, not this page's in-memory
     * state — so without this write-back the card still said INSTALL after a
     * successful install, which is exactly the "actually the install failed?"
     * confusion the green pill exists to prevent.
     */
    private void recordInstall(String fileName, String version) {
        try {
            if (mModItem == null || mModItem.id == null) return;
            net.kdt.pojavlaunch.modloaders.modpacks.InstalledContentTracker.markInstalled(
                    requireContext().getApplicationContext(), mProfileKey, mContentType,
                    mModItem.id, version, fileName);
            net.kdt.pojavlaunch.modloaders.modpacks.InstalledContentTracker.recordLatestKnown(
                    requireContext().getApplicationContext(), mContentType, mModItem.id, version);
        } catch (Throwable ignored) {}
    }

    private java.io.File getTargetDir() {
        Bundle self = getArguments();
        String override = self != null ? self.getString(ModInstallFragment.ARG_TARGET_DIR) : null;
        if (override != null && !override.isEmpty()) return new java.io.File(override);
        java.io.File base = new java.io.File(Tools.DIR_GAME_NEW);
        String folder = "mods";
        try {
            String key = mProfileKey != null ? mProfileKey : LauncherPreferences.DEFAULT_PREF
                    .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            if (key != null && !key.isEmpty()) {
                LauncherProfiles.load();
                MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
                if (profile != null) base = Tools.getGameDirPath(profile);
            }
        } catch (Throwable ignored) {}
        if ("resourcepack".equals(mContentType)) folder = "resourcepacks";
        else if ("shader".equals(mContentType)) folder = "shaderpacks";
        else if ("world".equals(mContentType) || "datapack".equals(mContentType)) folder = "datapacks";
        return new java.io.File(base, folder);
    }
}
