package net.kdt.pojavlaunch.fragments;

import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Horizontal instance library for the Launcher Home carousel.
 *
 * Interactions:
 *   tap card      → promote to primary (first in saved order)
 *   tap ▶         → launch that instance
 *   tap ⋮         → contextual action sheet (edit / mods / shortcut / directory / favorite)
 *   long-press    → drag to reorder (order persists; position 0 = primary)
 *
 * Reorder/favorite semantics mirror the profile system: favorites stay grouped
 * ahead of plain instances (see ProfileOrderManager).
 */
public class InstanceLibraryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_INSTANCE = 0;
    private static final int TYPE_CREATE = 1;

    private final List<String> mProfileKeys;
    private final List<MinecraftProfile> mProfiles;
    private final Listener mListener;
    private OnStartDragListener mDragStartListener;
    /** Transient tutorial demo keys: rendered like real cards, never persisted. */
    private final Set<String> mDemoKeys = new HashSet<>();

    public interface Listener {
        void onPromote(String profileKey, MinecraftProfile profile);
        void onPlay(String profileKey, MinecraftProfile profile);
        void onMore(@NonNull View anchor, String profileKey, MinecraftProfile profile);
        void onCreateInstance();
        void onOrderChanged(List<String> orderedProfileKeys);
    }

    public interface OnStartDragListener {
        void onStartDrag(@NonNull RecyclerView.ViewHolder holder);
    }

    public InstanceLibraryAdapter(List<String> profileKeys, List<MinecraftProfile> profiles,
                                  @NonNull Listener listener) {
        mProfileKeys = new ArrayList<>(profileKeys);
        mProfiles = new ArrayList<>(profiles);
        mListener = listener;
        setHasStableIds(true);
    }

    public void setDragStartListener(OnStartDragListener l) {
        mDragStartListener = l;
    }

    /** Swaps the dataset in place (keeps the attached scroll/snap state). */
    public void updateData(List<String> profileKeys, List<MinecraftProfile> profiles) {
        mProfileKeys.clear();
        mProfileKeys.addAll(profileKeys);
        mProfiles.clear();
        mProfiles.addAll(profiles);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position == mProfileKeys.size() ? TYPE_CREATE : TYPE_INSTANCE;
    }

    @Override
    public long getItemId(int position) {
        if (position == mProfileKeys.size()) return Long.MIN_VALUE;
        return mProfileKeys.get(position).hashCode();
    }

    @Override
    public int getItemCount() {
        return mProfileKeys.size() + 1;   // + "New Instance" tile
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_CREATE) {
            return new CreateHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_library_create, parent, false));
        }
        return new InstanceHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_library_card, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        if (h instanceof CreateHolder) {
            ((CreateHolder) h).root.setOnClickListener(v ->
                    mListener.onCreateInstance());
            return;
        }
        InstanceHolder holder = (InstanceHolder) h;
        if (position >= mProfileKeys.size()) return;
        String key = mProfileKeys.get(position);
        MinecraftProfile profile = mProfiles.get(position);

        holder.name.setText(profile.name != null ? profile.name : "Instance");
        String version = profile.lastVersionId == null ? "" : profile.lastVersionId;
        String loader = loaderName(version);
        holder.meta.setText(displayVersion(version)
                + (loader != null ? "  •  " + loader : ""));

        // Primary = first in the saved order → accent ring.
        holder.primaryRing.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        holder.fav.setVisibility(profile.favorite ? View.VISIBLE : View.GONE);

        holder.icon.setImageResource(R.drawable.ic_layers);
        holder.icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF8B5CF6));
        PojavApplication.sExecutorService.execute(() -> {
            Drawable icon = ProfileIconCache.fetchIcon(holder.icon.getResources(), key, profile.icon);
            Tools.runOnUiThread(() -> {
                if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                if (icon != null) {
                    holder.icon.setImageDrawable(icon);
                    holder.icon.setImageTintList(null);
                }
            });
        });
        holder.bg.setVisibility(View.GONE);
        PojavApplication.sExecutorService.execute(() -> {
            Drawable bg = ProfileIconCache.fetchBackground(holder.bg.getResources(), key, profile.background);
            Tools.runOnUiThread(() -> {
                if (holder.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
                if (bg != null) {
                    holder.bg.setImageDrawable(bg);
                    holder.bg.setVisibility(View.VISIBLE);
                }
            });
        });

        final String fKey = key;
        final MinecraftProfile fProfile = profile;
        final boolean isDemo = mDemoKeys.contains(key);
        // Demo cards are visual/practice objects: long-press→drag stays alive
        // (it never touches persistence), every other action is inert so a
        // demo key can never leak into promote/play/edit/shortcut paths.
        if (isDemo) {
            holder.root.setOnClickListener(v -> { /* inert demo card */ });
            holder.play.setOnClickListener(v -> { /* inert demo card */ });
            holder.more.setOnClickListener(v -> { /* inert demo card */ });
            // Long-press still starts a REAL drag (visual-only; filtered from persistence).
            holder.root.setOnLongClickListener(v -> {
                if (mDragStartListener == null) return false;
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                mDragStartListener.onStartDrag(holder);
                return true;
            });
            return;
        }
        holder.root.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            mListener.onPromote(fKey, fProfile);
        });
        holder.root.setOnLongClickListener(v -> {
            if (mDragStartListener == null) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mDragStartListener.onStartDrag(holder);
            return true;
        });
        // The ⋮ used to be a dead icon: no feedback at all. It now breathes
        // while idle and springs when tapped, so the menu reads as reachable.
        if (holder.more instanceof android.view.ViewGroup) {
            for (int i = 0; i < ((android.view.ViewGroup) holder.more).getChildCount(); i++) {
                View child = ((android.view.ViewGroup) holder.more).getChildAt(i);
                if (child instanceof android.widget.ImageView) {
                    net.kdt.pojavlaunch.UiMotion.breathPulse(
                            child, 0.45f, 1f, 1500L);
                }
            }
        }
        net.kdt.pojavlaunch.UiMotion.pressFeedback(holder.more);
        holder.more.setOnClickListener(v -> {
            net.kdt.pojavlaunch.UiMotion.springScale(v, 1.12f);
            v.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(120L).setDuration(140L).start();
            mListener.onMore(v, fKey, fProfile);
        });
        holder.play.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            mListener.onPlay(fKey, fProfile);
        });
    }

    // ── Reorder (same favorite-group rule as the profile order manager) ─────

    public boolean isSameFavoriteGroup(int from, int to) {
        return from >= 0 && to >= 0 && from < mProfileKeys.size() && to < mProfileKeys.size()
                && mProfiles.get(from).favorite == mProfiles.get(to).favorite;
    }

    public boolean moveItem(int from, int to) {
        if (!isSameFavoriteGroup(from, to) || from == to) return false;
        if (from < to) {
            for (int i = from; i < to; i++) {
                Collections.swap(mProfiles, i, i + 1);
                Collections.swap(mProfileKeys, i, i + 1);
            }
        } else {
            for (int i = from; i > to; i--) {
                Collections.swap(mProfiles, i, i - 1);
                Collections.swap(mProfileKeys, i, i - 1);
            }
        }
        notifyItemMoved(from, to);
        return true;
    }

    public List<String> getOrderedProfileKeys() {
        // Demo keys are filtered out: persistence can never observe them.
        if (mDemoKeys.isEmpty()) return new ArrayList<>(mProfileKeys);
        List<String> out = new ArrayList<>(mProfileKeys.size());
        for (String key : mProfileKeys) {
            if (!mDemoKeys.contains(key)) out.add(key);
        }
        return out;
    }

    public void dispatchOrderChanged() {
        List<String> ordered = getOrderedProfileKeys();
        // With only demo cards present there is nothing real to persist —
        // swallow the event instead of writing an empty order to storage.
        if (ordered.isEmpty() && !mDemoKeys.isEmpty()) return;
        mListener.onOrderChanged(ordered);
    }

    // ── Transient tutorial demo items (in-memory only) ─────────────────────

    /** Inserts a demo card rendered by the real binder. No-op if already present. */
    public void insertDemoItem(@NonNull String key, @NonNull MinecraftProfile profile, int position) {
        if (mDemoKeys.contains(key)) return;
        position = Math.max(0, Math.min(position, mProfileKeys.size()));
        mProfileKeys.add(position, key);
        mProfiles.add(position, profile);
        mDemoKeys.add(key);
        notifyItemInserted(position);
    }

    /** Removes every demo card (real data untouched). */
    public void removeDemoItems() {
        if (mDemoKeys.isEmpty()) return;
        for (int i = mProfileKeys.size() - 1; i >= 0; i--) {
            if (mDemoKeys.contains(mProfileKeys.get(i))) {
                mProfileKeys.remove(i);
                mProfiles.remove(i);
                notifyItemRemoved(i);
            }
        }
        mDemoKeys.clear();
    }

    /** Removes one specific demo card. */
    public void removeDemoItem(@NonNull String key) {
        int idx = mProfileKeys.indexOf(key);
        if (idx < 0 || !mDemoKeys.contains(key)) return;
        mProfileKeys.remove(idx);
        mProfiles.remove(idx);
        mDemoKeys.remove(key);
        notifyItemRemoved(idx);
    }

    public boolean isDemoKey(@NonNull String key) { return mDemoKeys.contains(key); }
    public boolean hasDemoItems() { return !mDemoKeys.isEmpty(); }
    public int indexOfKey(@NonNull String key) { return mProfileKeys.indexOf(key); }
    /** Count of REAL profiles (demo items excluded). */
    public int getRealCount() { return mProfileKeys.size() - mDemoKeys.size(); }

    // ── View holders ────────────────────────────────────────────────────────

    static class InstanceHolder extends RecyclerView.ViewHolder {
        final View root, primaryRing;
        final ImageView bg, icon, fav;
        final TextView name, meta;
        final View more, play;

        InstanceHolder(@NonNull View v) {
            super(v);
            root = v;
            bg = v.findViewById(R.id.lib_card_bg);
            primaryRing = v.findViewById(R.id.lib_card_primary);
            fav = v.findViewById(R.id.lib_card_fav);
            icon = v.findViewById(R.id.lib_card_icon);
            name = v.findViewById(R.id.lib_card_name);
            meta = v.findViewById(R.id.lib_card_meta);
            more = v.findViewById(R.id.lib_card_more);
            play = v.findViewById(R.id.lib_card_play);
        }
    }

    static class CreateHolder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        CreateHolder(@NonNull View v) {
            super(v);
            root = (FrameLayout) v;
        }
    }

    // ── Helpers (same detection as everywhere in the launcher) ──────────────

    private static String displayVersion(String raw) {
        if (raw == null || raw.isEmpty()) return "—";
        return raw;
    }

    private static String loaderName(String versionId) {
        if (versionId == null) return null;
        String v = versionId.toLowerCase(java.util.Locale.ROOT);
        if (v.contains("neoforge")) return "NeoForge";
        if (v.contains("forge")) return "Forge";
        if (v.contains("fabric")) return "Fabric";
        if (v.contains("quilt")) return "Quilt";
        if (v.contains("optifine")) return "OptiFine";
        return null;
    }
}
