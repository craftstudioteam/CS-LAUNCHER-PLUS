package net.kdt.pojavlaunch.fragments;

import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.ui.PremiumPlayButtonView;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LEGACY grid adapter — used only by {@link RightPaneHomeFragment} (old
 * MainMenuFragment right pane, kept for settings/theme hooks). The actual
 * Home screen now uses {@link InstanceLibraryAdapter} (LauncherHomeFragment).
 */
public class HomeProfileAdapter extends RecyclerView.Adapter<HomeProfileAdapter.ViewHolder> {

    private static final String TAG = "HomeProfileAdapter";

    private static final int PAYLOAD_MOD_COUNT = 1;

    private final List<MinecraftProfile> mProfileList;
    private final List<String> mProfileKeys;
    private final OnProfileActionListener mListener;
    private final Map<String, Integer> mModCountCache = new ConcurrentHashMap<>();
    private OnStartDragListener mDragStartListener;
    private boolean mModCountsReady;
    private int mBoundCount = 0;

    public interface OnProfileActionListener {
        void onProfilePlay(String profileKey, MinecraftProfile profile);
        void onProfileBrowse(String profileKey, MinecraftProfile profile);
        void onProfileEdit(String profileKey, MinecraftProfile profile);
        void onProfileAddShortcut(String profileKey, MinecraftProfile profile);
        void onProfileFavoriteChanged(String profileKey, MinecraftProfile profile, boolean favorite);
        void onProfileOrderChanged(List<String> orderedProfileKeys);
    }

    public interface OnStartDragListener {
        void onStartDrag(@NonNull RecyclerView.ViewHolder holder);
    }

    public HomeProfileAdapter(List<String> profileKeys, List<MinecraftProfile> profiles,
                              OnProfileActionListener listener) {
        mProfileKeys = new ArrayList<>(profileKeys);
        mProfileList = new ArrayList<>(profiles);
        mListener = listener;
        setHasStableIds(true);
        preloadModCounts();
    }

    @Override
    public long getItemId(int position) {
        return mProfileKeys.get(position).hashCode();
    }

    /** Profile key at a row — what a dragged mod gets dropped onto. */
    @androidx.annotation.Nullable
    public String keyAt(int position) {
        if (position < 0 || position >= mProfileKeys.size()) return null;
        return mProfileKeys.get(position);
    }

    private void preloadVisualAssets(@NonNull android.content.res.Resources resources) {
        if (mProfileList.isEmpty()) return;
        final List<MinecraftProfile> profileSnapshot = new ArrayList<>(mProfileList);
        final List<String> keySnapshot = new ArrayList<>(mProfileKeys);
        PojavApplication.sExecutorService.execute(() -> {
            for (int i = 0; i < profileSnapshot.size(); i++) {
                MinecraftProfile profile = profileSnapshot.get(i);
                String key = keySnapshot.get(i);
                try {
                    ProfileIconCache.fetchIcon(resources, key, profile.icon);
                    ProfileIconCache.fetchBackground(resources, key, profile.background);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void preloadModCounts() {
        if (mProfileList.isEmpty()) return;
        final List<MinecraftProfile> profileSnapshot = new ArrayList<>(mProfileList);
        final List<String> keySnapshot = new ArrayList<>(mProfileKeys);
        PojavApplication.sExecutorService.execute(() -> {
            for (int i = 0; i < profileSnapshot.size(); i++) {
                MinecraftProfile profile = profileSnapshot.get(i);
                int count = 0;
                try {
                    java.io.File gameDir = profile.resolveGameDir();
                    if (gameDir != null) {
                        java.io.File modsDir = new java.io.File(gameDir, "mods");
                        if (modsDir.exists() && modsDir.isDirectory()) {
                            java.io.File[] files = modsDir.listFiles(f -> f.isFile() &&
                                    (f.getName().toLowerCase().endsWith(".jar") || f.getName().toLowerCase().endsWith(".jar.disabled")));
                            count = files != null ? files.length : 0;
                        }
                    }
                } catch (Throwable ignored) {}
                mModCountCache.put(keySnapshot.get(i), count);
            }
            mModCountsReady = true;
            Tools.runOnUiThread(() -> notifyItemRangeChanged(0, mProfileList.size(), PAYLOAD_MOD_COUNT));
        });
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_profile_card, parent, false);
        // Smooth staggered fade + slide-up entrance for premium feel.
        // No temporary hardware layer here: the state-list animator/compositor
        // already handles presses, and forcing LAYER_TYPE_HARDWARE for a ~300ms
        // entrance costs one extra offscreen buffer per profile card.
        android.view.animation.Animation enterAnim =
                AnimationUtils.loadAnimation(parent.getContext(), R.anim.item_fade_slide_in);
        int pos = mProfileList.isEmpty() ? 0 : Math.min(mBoundCount, 11);
        enterAnim.setStartOffset(pos * 45L);
        mBoundCount++;
        view.startAnimation(enterAnim);
        ViewHolder holder = new ViewHolder(view);
        // Rows are skipped by the screen-wide attach pass — give the cards and
        // their action buttons the launcher's shared juicy press feel here.
        // The whole card also leans toward the finger (TiltCard style).
        net.kdt.pojavlaunch.UiMotion.pressTiltFeedback(holder.cardRoot);
        net.kdt.pojavlaunch.UiMotion.pressFeedback(
                holder.btnPlay, holder.btnBrowse, holder.btnFavorite, holder.btnShortcut);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        onBindViewHolder(holder, position, null);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            for (Object payload : payloads) {
                if (payload instanceof Integer && (Integer) payload == PAYLOAD_MOD_COUNT) {
                    String key = mProfileKeys.get(position);
                    int modCount = mModCountCache.getOrDefault(key, 0);
                    holder.tvModCount.setText("Installed Mods: " + modCount);
                    return;
                }
            }
        }

        MinecraftProfile profile = mProfileList.get(position);
        String profileKey = mProfileKeys.get(position);

        holder.tvName.setText(profile.name != null ? profile.name : "");

        // Exact Loader/Version
        String version = profile.lastVersionId != null ? profile.lastVersionId : "";
        if (version.length() > 20) {
            version = version.substring(0, 20) + "...";
        }
        holder.tvVersion.setText(version);

        // Mod count (may not be loaded yet)
        int modCount = mModCountsReady ? mModCountCache.getOrDefault(profileKey, 0) : 0;
        holder.tvModCount.setText("Installed Mods: " + modCount);

        // RAM chip shows the effective global allocation (per-profile RAM was removed)
        holder.tvRam.setText("RAM: " + net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_RAM_ALLOCATION + " MB");

        // Req-9: premium MC-version badge pinned at the card's top-right corner.
        // Extracted from lastVersionId ("fabric-loader-0.15.11-1.20.4" → "1.20.4");
        // hidden when the string carries no plain MC version (rare custom ids).
        if (holder.tvVersionBadge != null) {
            String mcVer = extractMcVersion(profile.lastVersionId);
            if (mcVer != null) {
                holder.tvVersionBadge.setText(mcVer);
                holder.tvVersionBadge.setVisibility(View.VISIBLE);
            } else {
                holder.tvVersionBadge.setVisibility(View.GONE);
            }
        }

        bindIcon(holder.imgIcon, profileKey, profile);
        bindBackground(holder.imgBackground, profileKey, profile);

        holder.cardRoot.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfileEdit(profileKey, profile);
        });

        // Whole-card long press is intentionally unassigned. Reordering starts
        // only from the dedicated drag handle; the explicit Shortcut button stays.
        holder.cardRoot.setOnLongClickListener(null);

        holder.btnPlay.setOnClickListener(v -> {
            if (holder.btnPlay.isStopMode()) {
                holder.btnPlay.cancelPendingLaunch();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                return;
            }
            // Phase 3: "launch" is a unique morph, not the old download pulse.
            holder.btnPlay.beginLaunch();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (mListener != null) mListener.onProfilePlay(profileKey, profile);
        });

        holder.btnBrowse.setOnClickListener(v -> {
            if (mListener != null) mListener.onProfileBrowse(profileKey, profile);
        });

        holder.btnFavorite.setImageResource(profile.favorite
                ? R.drawable.ic_csp_star_filled : R.drawable.ic_csp_star_outline);
        holder.btnFavorite.setColorFilter(profile.favorite ? 0xFFFFD166 : 0xFFA9ABB4);
        holder.btnFavorite.setContentDescription(profile.favorite
                ? "Remove profile from favorites" : "Add profile to favorites");
        holder.btnFavorite.setOnClickListener(v -> {
            boolean next = !profile.favorite;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (mListener != null) mListener.onProfileFavoriteChanged(profileKey, profile, next);
        });

        holder.dragHandle.setOnLongClickListener(v -> {
            if (mDragStartListener == null) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mDragStartListener.onStartDrag(holder);
            return true;
        });

        // Explicit shortcut button remains available; only card long-press was removed.
        if (holder.btnShortcut != null) {
            holder.btnShortcut.setOnClickListener(v -> {
                if (mListener != null) mListener.onProfileAddShortcut(profileKey, profile);
            });
        }
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        super.onViewRecycled(holder);
        // Pause GIF render threads promptly when cards leave the screen
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.stopDrawable(holder.imgIcon.getDrawable());
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.stopDrawable(holder.imgBackground.getDrawable());
        holder.imgIcon.setImageDrawable(null);
        holder.imgBackground.setImageDrawable(null);
        holder.btnPlay.reset();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        // Warm icon/banner decode off the UI thread. First bind then hits the
        // shared LRU caches instead of decoding a Base64/GIF payload per row.
        preloadVisualAssets(recyclerView.getResources());
        // Rebind when a remotely-cached asset (e.g. the default animated GIF)
        // finishes downloading so it fades in without user action.
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.addAssetReadyListener(mAssetReadyListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.removeAssetReadyListener(mAssetReadyListener);
        super.onDetachedFromRecyclerView(recyclerView);
    }

    // NOTE: must not reference the blank-final mProfileList here — instance
    // field initializers run BEFORE the constructor body assigns it (javac
    // definite-assignment error). getItemCount() defers the read to call time.
    private final net.kdt.pojavlaunch.profiles.ProfileGifSupport.OnAssetReadyListener mAssetReadyListener =
            assetKey -> Tools.runOnUiThread(() ->
                    notifyItemRangeChanged(0, getItemCount()));

    private void bindIcon(ImageView target, String profileKey, MinecraftProfile profile) {
        String icon = profile.icon;
        Drawable drawable = null;

        // 1) Custom user icon / modpack artwork (data URI) or named loader icons
        boolean hasCustomOrNamedIcon = icon != null
                && (icon.startsWith("data:") || icon.equals("fabric") || icon.equals("quilt"));
        if (hasCustomOrNamedIcon) {
            try {
                drawable = ProfileIconCache.fetchIcon(target.getResources(), profileKey, icon);
            } catch (Exception e) {
                Log.w(TAG, "Icon load failed for " + profileKey, e);
            }
        }

        // 2) Loader-specific artwork when the version names a mod loader
        if (drawable == null) {
            drawable = resolveTypeFallback(target, profile.lastVersionId);
        }

        // 3) Intelligent defaults: Vanilla → vanilla tile, modded → modpack tile
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(target.getContext(),
                    isVanillaVersion(profile.lastVersionId)
                            ? R.drawable.ic_vanilla_grass
                            : R.drawable.ic_profile_modpack);
        }

        // 4) Absolute safety net: the official CS Launcher logo
        if (drawable == null) {
            drawable = ContextCompat.getDrawable(target.getContext(), R.drawable.ic_cs_logo_placeholder);
        }
        target.setImageDrawable(drawable);
        target.setClipToOutline(true);
        target.setBackgroundResource(ProfileIconCache.hasTransparentCorners(drawable)
                ? R.drawable.bg_profile_icon_circle : R.drawable.bg_profile_icon_square);
        // GIF icons survive refresh/rebinds (Req-4 lifecycle): resume if paused
        net.kdt.pojavlaunch.profiles.ProfileGifSupport.resumeDrawable(drawable);
    }

    /** Filesystem-free vanilla heuristic for scroll-safe binding. */
    private static boolean isVanillaVersion(@Nullable String lastVersionId) {
        if (lastVersionId == null) return true;
        String lower = lastVersionId.toLowerCase();
        return !(lower.contains("fabric") || lower.contains("forge") || lower.contains("neoforge")
                || lower.contains("quilt") || lower.contains("liteloader") || lower.contains("optifine"));
    }

    /**
     * Extracts both legacy 1.x versions and Mojang's 2026+ year-based Java
     * versions (26.1, 26.1.2, 26.2-snapshot-4) from compounded loader IDs.
     */
    @Nullable
    static String extractMcVersion(@Nullable String lastVersionId) {
        if (lastVersionId == null) return null;
        java.util.regex.Matcher modern = java.util.regex.Pattern
                .compile("(?:^|[^0-9])((?:2[6-9]|[3-9][0-9])\\.\\d+(?:\\.\\d+)?)(?:$|[^0-9])")
                .matcher(lastVersionId);
        if (modern.find()) return modern.group(1);
        java.util.regex.Matcher legacy = java.util.regex.Pattern
                .compile("(?:^|[^0-9])(1\\.\\d+(?:\\.\\d+)?)(?:$|[^0-9])")
                .matcher(lastVersionId);
        return legacy.find() ? legacy.group(1) : null;
    }

    private void bindBackground(ImageView target, String profileKey, MinecraftProfile profile) {
        Drawable drawable = null;
        try {
            drawable = ProfileIconCache.fetchBackground(target.getResources(), profileKey, profile.background);
        } catch (Exception e) {
            Log.w(TAG, "Background load failed for " + profileKey, e);
        }
        if (drawable != null) {
            target.setImageDrawable(drawable);
            // Cache-hit GIFs paused by recycling keep playing after a refresh
            net.kdt.pojavlaunch.profiles.ProfileGifSupport.resumeDrawable(drawable);
            target.setVisibility(View.VISIBLE);
            // Show scrim when background is present
            View scrim = ((ViewGroup) target.getParent()).findViewById(R.id.img_profile_background_scrim);
            if (scrim != null) scrim.setVisibility(View.VISIBLE);
        } else {
            target.setImageDrawable(null);
            target.setVisibility(View.GONE);
            View scrim = ((ViewGroup) target.getParent()).findViewById(R.id.img_profile_background_scrim);
            if (scrim != null) scrim.setVisibility(View.GONE);
        }
    }

    private Drawable resolveTypeFallback(ImageView target, String lastVersionId) {
        if (lastVersionId == null) return null;
        String lower = lastVersionId.toLowerCase();
        int resId = -1;
        if (lower.contains("fabric")) resId = R.drawable.ic_fabric;
        else if (lower.contains("quilt")) resId = R.drawable.ic_quilt;
        else if (lower.contains("neoforge") || lower.contains("forge") || lower.contains("liteloader"))
            resId = R.drawable.ic_profile_modpack; // loader found, no brand art → modpack tile
        if (resId == -1) return null;
        return ContextCompat.getDrawable(target.getContext(), resId);
    }

    public void setDragStartListener(@Nullable OnStartDragListener listener) {
        mDragStartListener = listener;
    }

    public boolean isSameFavoriteGroup(int from, int to) {
        return from >= 0 && to >= 0 && from < mProfileList.size() && to < mProfileList.size()
                && mProfileList.get(from).favorite == mProfileList.get(to).favorite;
    }

    public boolean moveItem(int from, int to) {
        if (!isSameFavoriteGroup(from, to) || from == to) return false;
        if (from < to) {
            for (int i = from; i < to; i++) {
                Collections.swap(mProfileList, i, i + 1);
                Collections.swap(mProfileKeys, i, i + 1);
            }
        } else {
            for (int i = from; i > to; i--) {
                Collections.swap(mProfileList, i, i - 1);
                Collections.swap(mProfileKeys, i, i - 1);
            }
        }
        notifyItemMoved(from, to);
        return true;
    }

    @NonNull
    public List<String> getOrderedProfileKeys() {
        return new ArrayList<>(mProfileKeys);
    }

    public void dispatchOrderChanged() {
        if (mListener != null) mListener.onProfileOrderChanged(getOrderedProfileKeys());
    }

    @Override
    public int getItemCount() {
        return mProfileList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View cardRoot;
        final ImageView imgIcon;
        final ImageView imgBackground;
        final TextView tvName;
        final TextView tvVersion;
        final TextView tvModCount;
        final TextView tvRam;
        final TextView tvVersionBadge;
        final ImageView btnFavorite;
        final PremiumPlayButtonView btnPlay;
        final FrameLayout btnBrowse;
        final View btnShortcut;
        final View dragHandle;

        ViewHolder(View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.card_profile_root);
            imgIcon = itemView.findViewById(R.id.img_profile_icon);
            imgBackground = itemView.findViewById(R.id.img_profile_background);
            tvName = itemView.findViewById(R.id.tv_profile_name);
            tvVersion = itemView.findViewById(R.id.tv_profile_version);
            tvModCount = itemView.findViewById(R.id.tv_profile_mod_count);
            tvRam = itemView.findViewById(R.id.tv_profile_ram);
            tvVersionBadge = itemView.findViewById(R.id.tv_version_badge);
            btnFavorite = itemView.findViewById(R.id.btn_profile_favorite);
            btnPlay = itemView.findViewById(R.id.btn_profile_play);
            btnBrowse = itemView.findViewById(R.id.btn_profile_browse);
            btnShortcut = itemView.findViewById(R.id.btn_profile_shortcut);
            dragHandle = itemView.findViewById(R.id.btn_profile_drag);
        }
    }
}
