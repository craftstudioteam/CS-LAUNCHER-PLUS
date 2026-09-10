package net.kdt.pojavlaunch.modloaders.modpacks;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

import java.util.concurrent.Future;

public class ModItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements TaskCountListener {
    private static final ModItem[] MOD_ITEMS_EMPTY = new ModItem[0];
    private static final int VIEW_TYPE_MOD_ITEM = 0;

    private final ModIconCache mIconCache = ModIconCache.getInstance();
    private final SearchResultCallback mSearchResultCallback;
    private ModItem[] mModItems;
    private final ModpackApi mModpackApi;

    private Future<?> mTaskInProgress;
    private SearchFilters mSearchFilters;
    private SearchResult mCurrentResult;
    private boolean mLastPage;
    /** Item count the RecyclerView was last told about. See publish(). */
    private int mReportedCount;
    /** RecyclerView this adapter is attached to (null while detached). */
    private RecyclerView mAttachedRv;
    /** Dataset change queued because a layout pass was in progress. */
    private boolean mPendingRepublish;
    /** Highest row already animated for the current result set. Prevents recycled
     * cards from replaying entrance motion while the user scrolls. */
    private int mLastAnimatedPosition = -1;

    private OnItemClickListener mOnItemClickListener;

    // ── Installed-state context (set by DownloadListFragment) ──
    private String mInstallProfileKey;
    private String mInstallContentType = "mod";
    private java.io.File mInstallContentDir;

    public ModItemAdapter(Resources resources, ModpackApi api, SearchResultCallback callback) {
        mModpackApi = api;
        mModItems = new ModItem[]{};
        mSearchResultCallback = callback;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.mOnItemClickListener = listener;
    }

    /**
     * Point the adapter at a profile + content type so cards can render
     * Installed / Update Available states. Null profile disables the feature.
     */
    public void setInstallContext(String profileKey, String contentType, java.io.File contentDir) {
        if (java.util.Objects.equals(mInstallProfileKey, profileKey)
                && java.util.Objects.equals(mInstallContentType, contentType)
                && java.util.Objects.equals(mInstallContentDir, contentDir)) return;
        mInstallProfileKey = profileKey;
        mInstallContentType = contentType == null ? "mod" : contentType;
        mInstallContentDir = contentDir;
        refreshInstallStates();
    }

    /**
     * Force a re-render of install states (e.g. after returning to the list).
     * No count change, so no ledger entry is needed — the whole range is
     * re-bound in place.
     */
    public void refreshInstallStates() {
        if (mInstallProfileKey == null || mModItems.length == 0) return;
        notifyItemRangeChanged(0, mModItems.length);
    }

    /**
     * Phase 11 (item 7): the loaded result set, detached from any view. A host
     * fragment keeps this across its view being destroyed (opening a mod detail
     * page replaces the list view) and hands it back to the next adapter, so
     * coming back shows the same rows at the same scroll offset instead of a
     * fresh page-1 query that jumps to the top.
     */
    public static final class SavedState {
        final ModItem[] items;
        final SearchResult currentResult;
        final SearchFilters filters;
        final boolean lastPage;
        /** Identity of the query the rows belong to, for the host to compare. */
        public final String queryName;
        public final String mcVersion;
        public final String modLoader;
        public final String sortIndex;

        SavedState(ModItem[] items, SearchResult currentResult, SearchFilters filters, boolean lastPage) {
            this.items = items;
            this.currentResult = currentResult;
            this.filters = filters;
            this.lastPage = lastPage;
            this.queryName = filters != null && filters.name != null ? filters.name : "";
            this.mcVersion = filters != null ? filters.mcVersion : null;
            this.modLoader = filters != null ? filters.modLoader : null;
            this.sortIndex = filters != null ? filters.sortIndex : null;
        }

        public int size() { return items == null ? 0 : items.length; }

        /** True when these rows answer exactly this query (same text/version/loader/sort). */
        public boolean matches(String name, String version, String loader, String sort) {
            return queryName.equals(name == null ? "" : name)
                    && java.util.Objects.equals(emptyToNull(mcVersion), emptyToNull(version))
                    && java.util.Objects.equals(emptyToNull(modLoader), emptyToNull(loader))
                    && java.util.Objects.equals(sortIndex == null ? "relevance" : sortIndex,
                                                sort == null ? "relevance" : sort);
        }

        private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
    }

    /** Snapshot of the rows currently shown, or null when nothing worth keeping is loaded. */
    @androidx.annotation.Nullable
    public SavedState saveState() {
        if (mModItems == null || mModItems.length == 0 || mSearchFilters == null) return null;
        return new SavedState(mModItems, mCurrentResult, mSearchFilters, mLastPage);
    }

    /**
     * Re-adopt a snapshot taken by {@link #saveState()}. Entrance animation is
     * suppressed for the restored rows (they were already on screen once), and
     * paging continues from the saved cursor. Returns false when the state is
     * empty, in which case the host should run a normal query.
     */
    public boolean restoreState(@androidx.annotation.Nullable SavedState state) {
        if (state == null || state.items == null || state.items.length == 0) return false;
        if (mTaskInProgress != null) {
            mTaskInProgress.cancel(true);
            mTaskInProgress = null;
        }
        mSearchFilters = state.filters;
        mCurrentResult = state.currentResult;
        mLastPage = state.lastPage;
        mModItems = state.items;
        mLastAnimatedPosition = state.items.length - 1;   // no replayed pour-in; later pages still animate
        mReportedCount = -1;                        // force the full-refresh path
        publish();
        return true;
    }

    public void performSearchQuery(SearchFilters searchFilters) {
        if (mTaskInProgress != null) {
            mTaskInProgress.cancel(true);
            mTaskInProgress = null;
        }
        this.mSearchFilters = searchFilters;
        this.mLastPage = false;
        this.mLastAnimatedPosition = -1;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, null))
                .startOnExecutor(PojavApplication.sExecutorService);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        // One row type only. The old "load more" footer row had no content of
        // its own — it existed so onBind could start the next page — and that
        // row was the position that went stale in the RecyclerView crash. Paging
        // is now a scroll event (loadMoreIfNeeded), so the list holds cards and
        // nothing else, which makes count and content impossible to desync.
        return new ModItemViewHolder(LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.item_mod_modern, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        // Bounds-checked on purpose: if a layout pass ever asks for a row
        // outside the current dataset, an ArrayIndexOutOfBounds here would look
        // like a brand new crash on an untested device.
        if (position >= 0 && position < mModItems.length
                && mModItems[position] != null
                && holder instanceof ModItemViewHolder) {
            ((ModItemViewHolder) holder).bind(mModItems[position]);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mAttachedRv = recyclerView;
        // RecyclerView was just given a fresh adapter: it knows nothing about
        // our contents yet, so the ledger has to start from zero. (Hosts call
        // restoreState() only after setAdapter(), so a restored dataset is
        // published on top of this zero — never lost under it.)
        mReportedCount = 0;
        mPendingRepublish = false;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mAttachedRv = null;
        mPendingRepublish = false;
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        // A dataset change landed during a layout pass: flush it now that the
        // pass is over, before anything can bind against stale positions.
        if (mPendingRepublish && !mAttachedRv.isComputingLayout()) {
            mPendingRepublish = false;
            notifyDataSetChanged();
        }
    }

    /**
     * Publish the item count. The count is derived from the dataset, and
     * only a matching notify operation is handed to RecyclerView — if the two
     * ever disagree (a race, a mid-animation refresh), fall back to a full
     * dataset change, which discards stale scrap instead of validating it.
     * That fallback is the actual crash fix: "Inconsistency detected. Invalid
     * view holder adapter position" only fires when a partial notify leaves a
     * holder pointing past the end of the list.
     */
    private void publish() {
        publish(-1, -1);
    }

    /** @param insertedAt / @param insertedCount describe a pure append, if any. */
    private void publish(int insertedAt, int insertedCount) {
        final int newCount = computeCount();
        final int delta = newCount - mReportedCount;
        if (insertedAt >= 0 && insertedCount == delta) {
            mReportedCount = newCount;
            safeNotify(() -> notifyItemRangeInserted(insertedAt, insertedCount));
            return;
        }
        if (delta == 0) {
            // Same number of rows — content changed, structure did not.
            mReportedCount = newCount;
            if (mModItems.length > 0) {
                safeNotify(() -> notifyItemRangeChanged(0, mModItems.length));
            }
            return;
        }
        mReportedCount = newCount;
        safeNotify(this::notifyDataSetChanged);
    }

    /** Post the notify if a layout/scroll pass is running instead of joining it. */
    private void safeNotify(Runnable notifyAction) {
        final RecyclerView rv = mAttachedRv;
        if (rv == null) {
            // Detached (e.g. the host view is being destroyed): the ledger stays
            // at zero and the next attach re-binds everything.
            return;
        }
        if (rv.isComputingLayout() || rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            mPendingRepublish = true;
            rv.post(notifyAction);
            return;
        }
        notifyAction.run();
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ModItemViewHolder) {
            ((ModItemViewHolder) holder).recycle();
        }
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() {
        return computeCount();
    }

    /** Single definition of the row list — the cards themselves, nothing else. */
    private int computeCount() {
        return mModItems.length;
    }

    @Override
    public int getItemViewType(int position) {
        return VIEW_TYPE_MOD_ITEM;
    }

    /** True while a page request is in flight — drives the progress line. */
    public boolean isBusy() {
        return mTaskInProgress != null;
    }

    /**
     * Ask for the next page once the user is within {@code within} rows of the
     * end. Called from a scroll callback, never from onBind: it does nothing
     * while a request is in flight or when the last page has arrived, so no row
     * has to exist purely to trigger it.
     */
    public void loadMoreIfNeeded(int within) {
        if (mTaskInProgress != null || mSearchFilters == null || mLastPage) return;
        if (mModItems.length == 0) return;
        final RecyclerView rv = mAttachedRv;
        if (rv == null) return;
        androidx.recyclerview.widget.LinearLayoutManager lm =
                (androidx.recyclerview.widget.LinearLayoutManager) rv.getLayoutManager();
        if (lm == null) return;
        int lastVisible = lm.findLastVisibleItemPosition();
        if (lastVisible < 0) return;
        if (lastVisible >= mModItems.length - 1 - within) loadMoreResults();
    }

    private void loadMoreResults() {
        if (mTaskInProgress != null || mSearchFilters == null) return;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, mCurrentResult))
                .startOnExecutor(PojavApplication.sExecutorService);
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {}

    private String formatDownloads(String downloads) {
        try {
            long d = Long.parseLong(downloads);
            if (d >= 1000000) return (d / 1000000) + "M";
            if (d >= 1000) return (d / 1000) + "K";
            return String.valueOf(d);
        } catch (Exception e) {
            return downloads;
        }
    }

    /** One small chip per category — the Modrinth card anatomy, built on demand. */
    private void buildTagChips(LinearLayout container, String[] categories) {
        LinearLayout mTagsContainer = container;
        if (mTagsContainer == null) return;
        mTagsContainer.removeAllViews();
        if (categories == null || categories.length == 0) {
            mTagsContainer.setVisibility(View.GONE);
            return;
        }
        android.content.Context ctx = mTagsContainer.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        int shown = 0;
        int budget = mTagsContainer.getWidth() > 0 ? mTagsContainer.getWidth() : (int) (140 * d);
        int used = 0;
        for (String raw : categories) {
            if (raw == null || raw.trim().isEmpty()) continue;
            if (shown >= 3) break;
            String label = raw.trim().replace('-', ' ');
            if (label.length() > 12) label = label.substring(0, 11) + "\u2026";
            label = Character.toUpperCase(label.charAt(0)) + label.substring(1);

            TextView chip = new TextView(ctx);
            chip.setText(label);
            chip.setSingleLine(true);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setIncludeFontPadding(false);
            chip.setTextSize(8f);
            chip.setTextColor(android.graphics.Color.parseColor("#A3A6B0"));
            chip.setBackgroundResource(R.drawable.sf_tag_chip);
            chip.setTypeface(android.graphics.Typeface.MONOSPACE);
            int padH = (int) (6 * d);
            int padV = (int) (2.5f * d);
            chip.setPadding(padH, padV, padH, padV);
            chip.measure(0, 0);
            int w = chip.getMeasuredWidth() + (int) (4 * d);
            if (shown > 0 && used + w > budget) break;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (int) (17 * d));
            if (shown > 0) lp.setMarginStart((int) (4 * d));
            chip.setLayoutParams(lp);
            mTagsContainer.addView(chip);
            used += w;
            shown++;
        }
        mTagsContainer.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private String installedLabel(InstalledContentTracker.InstallInfo info, boolean newer) {
        if (info.installedAt > 0L) {
            String date = android.text.format.DateFormat.format("dd MMM", info.installedAt)
                    .toString().toUpperCase(java.util.Locale.getDefault());
            return (newer ? "NEWER" : "INSTALLED") + "  •  " + date;
        }
        if (info.version != null && !info.version.isEmpty()) {
            String version = info.version.length() > 14
                    ? info.version.substring(0, 14) + "…" : info.version;
            return (newer ? "NEWER" : "INSTALLED") + "  •  " + version;
        }
        return newer ? "INSTALLED NEWER" : "INSTALLED";
    }

    private int getSourceDrawable(int apiSource) {
        switch (apiSource) {
            case net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_CURSEFORGE:
                return R.drawable.ic_curseforge;
            case net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_MODRINTH:
                return R.drawable.ic_modrinth;
            default:
                return 0;
        }
    }

    public class ModItemViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {
        private final ImageView mIconView;
        private final ImageView mSourceIconView;
        private final ImageView mBackgroundView1;
        private final ImageView mBackgroundView2;
        private final TextView mTitleView;
        private final TextView mInfoView;
        private final TextView mDownloadsView;
        private final TextView mFollowersView;
        private final LinearLayout mTagsContainer;
        private final TextView mDescriptionView;
        private final ImageButton mLikeButton;
        private final ImageButton mShareButton;
        private final View mInstallButton;
        private final View mInstallStatePill;
        private final ImageView mInstallStateIcon;
        private final TextView mInstallStateText;
        private ModItem mCurrentItem;
        private final SharedPreferences mLikedPrefs;

        private int mCurrentImageIndex = 0;
        private boolean mUsingFirstView = true;
        private final Handler mSlideshowHandler = new Handler(Looper.getMainLooper());
        private Runnable mSlideshowRunnable;

        public ModItemViewHolder(@NonNull View itemView) {
            super(itemView);
            mLikedPrefs = itemView.getContext().getSharedPreferences("liked_mods", Context.MODE_PRIVATE);
            mIconView = itemView.findViewById(R.id.mod_thumbnail_imageview);
            mSourceIconView = itemView.findViewById(R.id.mod_source_imageview);
            mBackgroundView1 = itemView.findViewById(R.id.mod_background_image_1);
            mBackgroundView2 = itemView.findViewById(R.id.mod_background_image_2);
            mTitleView = itemView.findViewById(R.id.mod_title_textview);
            mInfoView = itemView.findViewById(R.id.mod_info_textview);
            mDownloadsView = itemView.findViewById(R.id.mod_downloads_text);
            mFollowersView = itemView.findViewById(R.id.mod_followers_text);
            mTagsContainer = itemView.findViewById(R.id.mod_tags_container);
            mDescriptionView = itemView.findViewById(R.id.mod_body_textview);
            mLikeButton = itemView.findViewById(R.id.btn_like);
            mShareButton = itemView.findViewById(R.id.btn_share);
            mInstallButton = itemView.findViewById(R.id.btn_install);
            mInstallStatePill = itemView.findViewById(R.id.mod_install_state_pill);
            mInstallStateIcon = itemView.findViewById(R.id.mod_install_state_icon);
            mInstallStateText = itemView.findViewById(R.id.mod_install_state_text);
            itemView.setOnClickListener(this);
            // The card leans toward the finger (TiltCard style); the action
            // buttons keep the classic juicy press.
            net.kdt.pojavlaunch.UiMotion.pressTiltFeedback(itemView);
            net.kdt.pojavlaunch.UiMotion.pressFeedback(
                    mInstallButton, mShareButton, mLikeButton);
        }

        public void bind(ModItem item) {
            mCurrentItem = item;
            mTitleView.setText(item.title);
            
            // Lightweight first-appearance motion only. Alpha + translation are
            // compositor-friendly and do not trigger layout passes on mobile GPUs.
            int position = getBindingAdapterPosition();
            itemView.animate().cancel();
            if (position != RecyclerView.NO_POSITION && position > mLastAnimatedPosition) {
                mLastAnimatedPosition = position;
                float d = itemView.getResources().getDisplayMetrics().density;
                // anime.js stagger with grid: [2, n] — the delay grows along the
                // diagonal (row + column), so a page of cards pours in from the
                // top-left instead of blinking on as a block.
                int row = position / 2, col = position % 2;
                long delay = Math.min(row, 5) * 55L + col * 40L;
                itemView.setAlpha(0f);
                itemView.setTranslationY(22f * d);
                itemView.setScaleX(0.94f);
                itemView.setScaleY(0.94f);
                itemView.animate()
                        .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(net.kdt.pojavlaunch.utils.animation.MotionSpeed.scale(520))
                        .setStartDelay(net.kdt.pojavlaunch.utils.animation.MotionSpeed.scale(delay))
                        .setInterpolator(net.kdt.pojavlaunch.Anime.OUT_EXPO)
                        .withLayer()
                        .start();
            } else {
                itemView.setAlpha(1f);
                itemView.setTranslationX(0f);
                itemView.setTranslationY(0f);
                itemView.setScaleX(1f);
                itemView.setScaleY(1f);
            }

            if (item.author != null && !item.author.isEmpty()) {
                mInfoView.setText("by " + item.author);
                mInfoView.setVisibility(View.VISIBLE);
            } else {
                mInfoView.setVisibility(View.GONE);
            }

            if (mDownloadsView != null) {
                if (item.downloads != null && !item.downloads.isEmpty()) {
                    mDownloadsView.setText(formatDownloads(item.downloads) + " downloads");
                    mDownloadsView.setVisibility(View.VISIBLE);
                } else {
                    mDownloadsView.setVisibility(View.GONE);
                }
            }

            if (mFollowersView != null) {
                if (item.followers != null && !item.followers.isEmpty()) {
                    mFollowersView.setText(formatDownloads(item.followers) + " followers");
                    mFollowersView.setVisibility(View.VISIBLE);
                } else {
                    mFollowersView.setVisibility(View.GONE);
                }
            }

            buildTagChips(mTagsContainer, item.categories);

            if (item.description != null && !item.description.isEmpty()) {
                mDescriptionView.setText(item.description);
                mDescriptionView.setVisibility(View.VISIBLE);
            } else {
                mDescriptionView.setVisibility(View.GONE);
            }

            mSourceIconView.setImageResource(getSourceDrawable(item.apiSource));

            // One static Modrinth gallery banner per card. No slideshow/timer:
            // visual cards stay fast and deterministic while scrolling.
            stopSlideshow();
            mBackgroundView1.animate().cancel();
            mBackgroundView2.animate().cancel();
            mBackgroundView1.setAlpha(0f);
            mBackgroundView1.setImageDrawable(null);
            mBackgroundView2.setImageDrawable(null);
            String bannerUrl = null;
            if (item.galleryUrls != null && item.galleryUrls.length > 0) {
                bannerUrl = item.galleryUrls[0];
            } else if (item.galleryUrl != null && !item.galleryUrl.isEmpty()) {
                bannerUrl = item.galleryUrl;
            } else if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                bannerUrl = item.imageUrl;
            }
            if (bannerUrl != null) {
                final String requestedBanner = bannerUrl;
                mIconCache.getImage(bitmap -> {
                    if (mCurrentItem == item && bitmap != null) {
                        mBackgroundView1.setImageBitmap(bitmap);
                        mBackgroundView1.animate().alpha(0.72f).setDuration(180).start();
                    }
                }, item.getIconCacheTag() + "_bg", requestedBanner);
            }

            mIconView.setImageDrawable(null);
            mIconCache.getImage(
                    bitmap -> {
                        if (mCurrentItem == item) {
                            if (bitmap != null) mIconView.setImageBitmap(bitmap);
                            else mIconView.setImageResource(R.mipmap.ic_launcher_foreground);
                        }
                    },
                    item.getIconCacheTag(),
                    item.imageUrl
            );

            String modId = item.id;
            boolean isLiked = mLikedPrefs.getBoolean(modId, false);
            mLikeButton.setImageResource(isLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
            mLikeButton.setColorFilter(isLiked ? Color.parseColor("#FF2D55") : Color.parseColor("#7C7C88"));
            mLikeButton.setOnClickListener(v -> {
                boolean nowLiked = !mLikedPrefs.getBoolean(modId, false);
                mLikedPrefs.edit().putBoolean(modId, nowLiked).apply();
                mLikeButton.setImageResource(nowLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart_outline);
                mLikeButton.setColorFilter(nowLiked ? Color.parseColor("#FF2D55") : Color.parseColor("#7C7C88"));
                if (nowLiked) {
                    v.animate().cancel();
                    v.setScaleX(0.7f); v.setScaleY(0.7f);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                }
            });

            applyInstallState(item);

            mShareButton.setOnClickListener(v -> {
                if (mCurrentItem == null) return;
                v.animate().cancel();
                v.setScaleX(0.82f); v.setScaleY(0.82f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                shareModItem(v.getContext(), mCurrentItem);
            });

            mInstallButton.setOnClickListener(v -> {
                if (mOnItemClickListener != null && mCurrentItem != null) {
                    mOnItemClickListener.onItemClick(mCurrentItem);
                }
            });
        }

        /** Real installed-state resolution for the current profile context. */
        private void applyInstallState(ModItem item) {
            if (mInstallStatePill == null || mInstallButton == null) return;
            if (mInstallProfileKey == null || item == null || item.id == null) {
                mInstallStatePill.setVisibility(View.GONE);
                showInstallAction(false);
                return;
            }
            InstalledContentTracker.InstallInfo info = InstalledContentTracker.queryInfo(
                    itemView.getContext().getApplicationContext(),
                    mInstallProfileKey, mInstallContentType, item.id, mInstallContentDir);
            switch (info.state) {
                case InstalledContentTracker.STATE_INSTALLED:
                    mInstallButton.setVisibility(View.GONE);
                    mInstallStatePill.setVisibility(View.VISIBLE);
                    mInstallStatePill.setBackgroundResource(R.drawable.bg_cs_installed_pill);
                    mInstallStateIcon.setColorFilter(Color.parseColor("#E6E9EF"));
                    mInstallStateText.setText(installedLabel(info, false));
                    mInstallStateText.setTextColor(Color.parseColor("#D2D6DE"));
                    break;
                case InstalledContentTracker.STATE_INSTALLED_NEWER:
                    mInstallButton.setVisibility(View.GONE);
                    mInstallStatePill.setVisibility(View.VISIBLE);
                    mInstallStatePill.setBackgroundResource(R.drawable.bg_cs_installed_pill);
                    mInstallStateIcon.setColorFilter(Color.parseColor("#E6E9EF"));
                    mInstallStateText.setText(installedLabel(info, true));
                    mInstallStateText.setTextColor(Color.parseColor("#D2D6DE"));
                    break;
                case InstalledContentTracker.STATE_UPDATE_AVAILABLE:
                    // The old card displayed UPDATE and INSTALL controls together,
                    // making the action column overflow. One explicit CTA is clearer.
                    mInstallStatePill.setVisibility(View.GONE);
                    showInstallAction(true);
                    break;
                default:
                    // The index only knows what THIS app installed through the old
                    // page. A jar the user dropped in the folder, copied with the
                    // drag gesture, or installed from the detail page before the
                    // write-back existed, used to leave the card saying INSTALL —
                    // and pressing it started a download of a file that was already
                    // there. Say "IN FOLDER" (softly: INSTALL stays, because the
                    // prefix match is a hint, not a version-verified fact).
                    if (mInstallContentDir != null && item != null
                            && net.kdt.pojavlaunch.modloaders.modpacks.InstalledModFolder
                                .hasJarMatchingTitle(mInstallContentDir, item.title)) {
                        mInstallStatePill.setVisibility(View.VISIBLE);
                        mInstallStatePill.setBackgroundResource(R.drawable.bg_cs_installed_pill);
                        mInstallStateIcon.setColorFilter(Color.parseColor("#FFC46B"));
                        mInstallStateText.setText("IN FOLDER");
                        mInstallStateText.setTextColor(Color.parseColor("#F5D9A8"));
                        showInstallAction(false);
                        break;
                    }
                    mInstallStatePill.setVisibility(View.GONE);
                    showInstallAction(false);
                    break;
            }
        }

        private void showInstallAction(boolean update) {
            mInstallButton.setVisibility(View.VISIBLE);
            mInstallButton.setBackgroundResource(update
                    ? R.drawable.bg_browse_update_button
                    : R.drawable.bg_modrinth_install_dark);
            if (mInstallButton instanceof TextView) {
                TextView label = (TextView) mInstallButton;
                label.setText(update ? "UPDATE" : "INSTALL");
                label.setTextColor(Color.parseColor(update ? "#FFD07A" : "#F1F1F4"));
            }
        }

        private void loadSlideshowImage(String url, ImageView view, boolean initial) {
            mIconCache.getImage(
                    bitmap -> {
                        if (mCurrentItem != null && bitmap != null) {
                            view.setImageBitmap(bitmap);
                            view.animate().alpha(0.55f).setDuration(initial ? 500 : 800).start();
                        }
                    },
                    url + "_bg",
                    url
            );
        }

        private void startSlideshow() {
            mSlideshowRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mCurrentItem == null || mCurrentItem.galleryUrls == null || mCurrentItem.galleryUrls.length <= 1) return;
                    mCurrentImageIndex = (mCurrentImageIndex + 1) % mCurrentItem.galleryUrls.length;
                    String nextUrl = mCurrentItem.galleryUrls[mCurrentImageIndex];
                    
                    final ImageView activeView = mUsingFirstView ? mBackgroundView1 : mBackgroundView2;
                    final ImageView inactiveView = mUsingFirstView ? mBackgroundView2 : mBackgroundView1;
                    
                    mIconCache.getImage(bitmap -> {
                        if (mCurrentItem != null && bitmap != null) {
                            inactiveView.setImageBitmap(bitmap);
                            inactiveView.setAlpha(0f);
                            inactiveView.animate().alpha(0.55f).setDuration(1200).start();
                            activeView.animate().alpha(0f).setDuration(1200).start();
                            mUsingFirstView = !mUsingFirstView;
                        }
                    }, nextUrl + "_bg", nextUrl);
                    
                    mSlideshowHandler.postDelayed(this, 6000);
                }
            };
            mSlideshowHandler.postDelayed(mSlideshowRunnable, 6000);
        }

        private void stopSlideshow() {
            if (mSlideshowRunnable != null) {
                mSlideshowHandler.removeCallbacks(mSlideshowRunnable);
                mSlideshowRunnable = null;
            }
        }

        private void recycle() {
            stopSlideshow();
            mCurrentItem = null;
            itemView.animate().cancel();
            mBackgroundView1.animate().cancel();
            mBackgroundView1.setImageDrawable(null);
            mBackgroundView1.setAlpha(0f);
            mBackgroundView2.setImageDrawable(null);
            mIconView.setImageDrawable(null);
        }

        @Override
        public void onClick(View v) {
            if (mOnItemClickListener != null && mCurrentItem != null) {
                mOnItemClickListener.onItemClick(mCurrentItem);
            }
        }
    }

    /** Share via the project page (Modrinth slug / CF project id / website). */
    private static void shareModItem(Context context, ModItem item) {
        String url = item.websiteUrl;
        if (url == null || url.isEmpty()) {
            if (item.apiSource == net.kdt.pojavlaunch.modloaders.modpacks.models.Constants.SOURCE_MODRINTH) {
                url = "https://modrinth.in/project/" + item.id;
            } else {
                url = "https://www.curseforge.com/projects/" + item.id;
            }
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, item.title + " — " + url);
        context.startActivity(android.content.Intent.createChooser(send, item.title));
    }

    private class SearchApiTask implements SelfReferencingFuture.FutureInterface {
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousResult;
        /** Snapshot of the list taken on the UI thread — the background thread
         *  must never read mModItems, which the UI thread mutates. */
        private final ModItem[] mPreviousItems;

        private SearchApiTask(SearchFilters searchFilters, SearchResult previousResult) {
            this.mSearchFilters = searchFilters;
            this.mPreviousResult = previousResult;
            this.mPreviousItems = mModItems;
        }

        @Override
        public void run(Future<?> myFuture) {
            SearchResult result;
            try {
                result = mModpackApi.searchMod(mSearchFilters, mPreviousResult);
            } catch (Exception e) {
                // Network/parse failure on a page that was cancelled mid-flight
                // is expected. Swallowing it keeps the UI stable either way.
                if (isCancelled(myFuture)) return;
                Tools.runOnUiThread(() -> {
                    if (!isCancelled(myFuture)) {
                        mTaskInProgress = null;
                        publish();
                        mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL);
                    }
                });
                return;
            }
            ModItem[] resultModItems = result != null ? result.results : null;
            if (resultModItems != null && resultModItems.length != 0 && mPreviousResult != null) {
                ModItem[] newModItems = new ModItem[resultModItems.length + mPreviousItems.length];
                System.arraycopy(mPreviousItems, 0, newModItems, 0, mPreviousItems.length);
                System.arraycopy(resultModItems, 0, newModItems, mPreviousItems.length, resultModItems.length);
                resultModItems = newModItems;
            }
            ModItem[] finalModItems = resultModItems;
            Tools.runOnUiThread(() -> applyResult(myFuture, result, finalModItems));
        }

        /**
         * All dataset mutation happens here, on the UI thread, and it is always
         * followed by exactly one publish() — so the adapter count RecyclerView
         * holds can never drift away from getItemCount().
         */
        private void applyResult(Future<?> myFuture, SearchResult result, ModItem[] finalModItems) {
            if (isCancelled(myFuture)) {
                // A newer query owns mTaskInProgress by now; only clear the slot
                // if this really is the task still recorded there.
                if (mTaskInProgress == myFuture) mTaskInProgress = null;
                return;
            }
            mTaskInProgress = null;

            if (finalModItems == null) {
                mCurrentResult = null;
                mLastPage = false;
                publish();
                mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL);
                return;
            }

            if (finalModItems.length == 0) {
                if (mPreviousResult != null) {
                    // Last page with no new items: only the footer disappears.
                    mLastPage = true;
                    publish();
                    mSearchResultCallback.onSearchFinished();
                    return;
                }
                mModItems = MOD_ITEMS_EMPTY;
                mLastPage = true;
                publish();
                mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS);
                return;
            }

            mSearchResultCallback.onSearchFinished();
            mCurrentResult = result;

            if (mPreviousResult == null) {
                boolean sameSize = mModItems.length == finalModItems.length;
                mModItems = finalModItems;
                if (sameSize) {
                    // Same row count, entirely different content: re-bind in
                    // place instead of a full dataset change so the cards do not
                    // blink and no scrap holder is left with a dead position.
                    mLastAnimatedPosition = -1;
                    publish();
                } else {
                    mLastAnimatedPosition = -1;
                    mReportedCount = -1;   // force the full-refresh path
                    publish();
                }
                return;
            }

            // Append a page. mPreviousItems was snapshotted on the UI thread when
            // the task started, so prevLength is the row count RecyclerView
            // currently believes it has (cards only — the footer is not part of
            // the reported delta beyond the insert below).
            int prevLength = mPreviousItems.length;
            if (prevLength != mModItems.length) {
                // Rows were replaced while this page was in flight (a new search
                // finished first). Incremental notify would desync — take the
                // safe route and rebuild the list.
                mModItems = finalModItems;
                mLastPage = false;
                mReportedCount = -1;
                publish();
                return;
            }
            mModItems = finalModItems;
            int added = finalModItems.length - prevLength;
            if (added <= 0) {
                // Nothing new came back: this was the last page.
                mLastPage = true;
                publish();
                return;
            }
            publish(prevLength, added);
        }
    }

    private static boolean isCancelled(Future<?> future) {
        try {
            return future != null && future.isCancelled();
        } catch (Throwable t) {
            return false;
        }
    }

    public interface SearchResultCallback {
        int ERROR_INTERNAL = 0;
        int ERROR_NO_RESULTS = 1;
        void onSearchFinished();
        void onSearchError(int error);
    }

    public interface OnItemClickListener {
        void onItemClick(ModItem item);
    }
}
