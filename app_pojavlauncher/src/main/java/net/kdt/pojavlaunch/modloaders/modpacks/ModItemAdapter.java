package net.kdt.pojavlaunch.modloaders.modpacks;

import android.annotation.SuppressLint;
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
    private static final int VIEW_TYPE_LOADING = 1;

    private final ModIconCache mIconCache = ModIconCache.getInstance();
    private final SearchResultCallback mSearchResultCallback;
    private ModItem[] mModItems;
    private final ModpackApi mModpackApi;

    private Future<?> mTaskInProgress;
    private SearchFilters mSearchFilters;
    private SearchResult mCurrentResult;
    private boolean mLastPage;
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
    @SuppressLint("NotifyDataSetChanged")
    public void setInstallContext(String profileKey, String contentType, java.io.File contentDir) {
        if (java.util.Objects.equals(mInstallProfileKey, profileKey)
                && java.util.Objects.equals(mInstallContentType, contentType)
                && java.util.Objects.equals(mInstallContentDir, contentDir)) return;
        mInstallProfileKey = profileKey;
        mInstallContentType = contentType == null ? "mod" : contentType;
        mInstallContentDir = contentDir;
        notifyDataSetChanged();
    }

    /** Force a re-render of install states (e.g. after returning to the list). */
    @SuppressLint("NotifyDataSetChanged")
    public void refreshInstallStates() {
        if (mInstallProfileKey != null) notifyDataSetChanged();
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
        LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
        switch (viewType) {
            case VIEW_TYPE_MOD_ITEM:
                View view = inflater.inflate(R.layout.item_mod_modern, viewGroup, false);
                return new ModItemViewHolder(view);
            case VIEW_TYPE_LOADING:
                view = inflater.inflate(R.layout.view_loading, viewGroup, false);
                return new LoadingViewHolder(view);
            default:
                throw new RuntimeException("Unimplemented view type: " + viewType);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_MOD_ITEM:
                ((ModItemViewHolder) holder).bind(mModItems[position]);
                break;
            case VIEW_TYPE_LOADING:
                loadMoreResults();
                break;
        }
    }

    @Override
    public int getItemCount() {
        if (mModItems.length == 0) return 0;
        return mLastPage ? mModItems.length : mModItems.length + 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= mModItems.length) return VIEW_TYPE_LOADING;
        return VIEW_TYPE_MOD_ITEM;
    }

    private void loadMoreResults() {
        if (mTaskInProgress != null) return;
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
            mDescriptionView = itemView.findViewById(R.id.mod_body_textview);
            mLikeButton = itemView.findViewById(R.id.btn_like);
            mShareButton = itemView.findViewById(R.id.btn_share);
            mInstallButton = itemView.findViewById(R.id.btn_install);
            mInstallStatePill = itemView.findViewById(R.id.mod_install_state_pill);
            mInstallStateIcon = itemView.findViewById(R.id.mod_install_state_icon);
            mInstallStateText = itemView.findViewById(R.id.mod_install_state_text);
            itemView.setOnClickListener(this);
            net.kdt.pojavlaunch.UiMotion.pressFeedback(
                    itemView, mInstallButton, mShareButton, mLikeButton);
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
                itemView.setAlpha(0f);
                itemView.setTranslationX(18f * d);
                itemView.setScaleX(0.985f);
                itemView.setScaleY(0.985f);
                itemView.animate()
                        .alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                        .setDuration(240)
                        .setStartDelay(Math.min(position, 4) * 28L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f))
                        .withLayer()
                        .start();
            } else {
                itemView.setAlpha(1f);
                itemView.setTranslationX(0f);
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

            if (item.description != null && !item.description.isEmpty()) {
                mDescriptionView.setText(item.description);
                mDescriptionView.setVisibility(View.VISIBLE);
            } else {
                mDescriptionView.setVisibility(View.GONE);
            }

            mSourceIconView.setImageResource(getSourceDrawable(item.apiSource));

            // Result rows intentionally use a stable graphite surface instead of
            // per-card gallery slideshows. This keeps text contrast consistent,
            // prevents recycler image churn and makes every row align identically.
            stopSlideshow();
            mBackgroundView1.animate().cancel();
            mBackgroundView2.animate().cancel();
            mBackgroundView1.setImageDrawable(null);
            mBackgroundView2.setImageDrawable(null);

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
            int state = InstalledContentTracker.queryState(
                    itemView.getContext().getApplicationContext(),
                    mInstallProfileKey, mInstallContentType, item.id, mInstallContentDir);
            switch (state) {
                case InstalledContentTracker.STATE_INSTALLED:
                    mInstallButton.setVisibility(View.GONE);
                    mInstallStatePill.setVisibility(View.VISIBLE);
                    mInstallStatePill.setBackgroundResource(R.drawable.bg_cs_installed_pill);
                    mInstallStateIcon.setColorFilter(Color.parseColor("#2BD97B"));
                    mInstallStateText.setText(R.string.cs_installed_profile);
                    mInstallStateText.setTextColor(Color.parseColor("#8FEBBC"));
                    break;
                case InstalledContentTracker.STATE_INSTALLED_NEWER:
                    mInstallButton.setVisibility(View.GONE);
                    mInstallStatePill.setVisibility(View.VISIBLE);
                    mInstallStatePill.setBackgroundResource(R.drawable.bg_cs_installed_pill);
                    mInstallStateIcon.setColorFilter(Color.parseColor("#2BD97B"));
                    mInstallStateText.setText(R.string.cs_installed_newer);
                    mInstallStateText.setTextColor(Color.parseColor("#8FEBBC"));
                    break;
                case InstalledContentTracker.STATE_UPDATE_AVAILABLE:
                    // The old card displayed UPDATE and INSTALL controls together,
                    // making the action column overflow. One explicit CTA is clearer.
                    mInstallStatePill.setVisibility(View.GONE);
                    showInstallAction(true);
                    break;
                default:
                    mInstallStatePill.setVisibility(View.GONE);
                    showInstallAction(false);
                    break;
            }
        }

        private void showInstallAction(boolean update) {
            mInstallButton.setVisibility(View.VISIBLE);
            mInstallButton.setBackgroundResource(update
                    ? R.drawable.bg_browse_update_button
                    : R.drawable.bg_browse_install_button);
            if (mInstallButton instanceof TextView) {
                TextView label = (TextView) mInstallButton;
                label.setText(update ? "UPDATE" : "INSTALL");
                label.setTextColor(Color.parseColor(update ? "#FFD07A" : "#101114"));
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

    private static class LoadingViewHolder extends RecyclerView.ViewHolder {
        public LoadingViewHolder(View view) {
            super(view);
        }
    }

    private class SearchApiTask implements SelfReferencingFuture.FutureInterface {
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousResult;

        private SearchApiTask(SearchFilters searchFilters, SearchResult previousResult) {
            this.mSearchFilters = searchFilters;
            this.mPreviousResult = previousResult;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void run(Future<?> myFuture) {
            SearchResult result = mModpackApi.searchMod(mSearchFilters, mPreviousResult);
            ModItem[] resultModItems = result != null ? result.results : null;
            if (resultModItems != null && resultModItems.length != 0 && mPreviousResult != null) {
                ModItem[] newModItems = new ModItem[resultModItems.length + mModItems.length];
                System.arraycopy(mModItems, 0, newModItems, 0, mModItems.length);
                System.arraycopy(resultModItems, 0, newModItems, mModItems.length, resultModItems.length);
                resultModItems = newModItems;
            }
            ModItem[] finalModItems = resultModItems;
            Tools.runOnUiThread(() -> {
                if (myFuture.isCancelled()) return;
                mTaskInProgress = null;
                if (finalModItems == null) {
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL);
                } else if (finalModItems.length == 0) {
                    if (mPreviousResult != null) {
                        mLastPage = true;
                        notifyItemChanged(mModItems.length);
                        mSearchResultCallback.onSearchFinished();
                        return;
                    }
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS);
                } else {
                    mSearchResultCallback.onSearchFinished();
                }
                mCurrentResult = result;
                if (finalModItems == null) {
                    mModItems = MOD_ITEMS_EMPTY;
                    notifyDataSetChanged();
                    return;
                }
                if (mPreviousResult != null) {
                    int prevLength = mModItems.length;
                    mModItems = finalModItems;
                    notifyItemChanged(prevLength);
                    notifyItemRangeInserted(prevLength + 1, mModItems.length);
                } else {
                    mModItems = finalModItems;
                    notifyDataSetChanged();
                }
            });
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
