package net.kdt.pojavlaunch.worlds;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the World Manager.
 * - Icons decoded down-sampled off-thread and kept in a shared LruCache.
 * - Filterable by search text, sortable (last played / name / size / version).
 * - No per-bind allocations beyond what RecyclerView recycles.
 */
public final class WorldListAdapter extends RecyclerView.Adapter<WorldListAdapter.VH> {

    public static final int SORT_LAST_PLAYED = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_SIZE = 2;
    public static final int SORT_VERSION = 3;

    public interface Listener {
        void onWorldClick(@NonNull WorldEntry world);
        void onWorldMenu(@NonNull WorldEntry world, @NonNull View anchor);
    }

    private static final LruCache<String, Bitmap> ICON_CACHE =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private final List<WorldEntry> mAll = new ArrayList<>();
    private final List<WorldEntry> mVisible = new ArrayList<>();
    private final Listener mListener;
    private String mQuery = "";
    private int mSortMode = SORT_LAST_PLAYED;

    /**
     * CRASH FIX — stable IDs must be UNIQUE per item, forever.
     * The old implementation used {@code folderName.hashCode()}: two folders can
     * collide ("Two different ViewHolders have the same stable ID" crash) and a
     * hash may equal -1 == {@link RecyclerView#NO_ID}, which poisons the
     * GridLayoutManager recycling path (the "tmp detached view" crash family).
     * We assign a monotonically increasing long per folderName and skip NO_ID.
     */
    private final java.util.HashMap<String, Long> mStableIds = new java.util.HashMap<>();
    private long mNextStableId = 1L;

    public WorldListAdapter(@NonNull Listener listener) {
        mListener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        String key = mVisible.get(position).stableKey();
        Long id = mStableIds.get(key);
        if (id == null) {
            id = mNextStableId++;
            if (id == RecyclerView.NO_ID) id = mNextStableId++; // never hand out NO_ID
            mStableIds.put(key, id);
        }
        return id;
    }

    public void submit(@NonNull List<WorldEntry> worlds) {
        mAll.clear();
        mAll.addAll(worlds);
        refilter();
    }

    /**
     * Phase 3 crash fix — every data refresh goes through DiffUtil (never
     * notifyDataSetChanged), so the RecyclerView only touches rows that
     * actually moved/changed. No more recycling storms, no binding work for
     * untouched rows, and Tmp-detached views are never animated by hand.
     */
    private void dispatchSwap(@NonNull List<WorldEntry> next) {
        final List<WorldEntry> old = new ArrayList<>(mVisible);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return old.get(o).stableKey().equals(next.get(n).stableKey());
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                WorldEntry a = old.get(o);
                WorldEntry b = next.get(n);
                return eq(a.displayName, b.displayName)
                        && eq(a.versionName, b.versionName)
                        && a.lastPlayedMs == b.lastPlayedMs
                        && a.sizeBytes == b.sizeBytes
                        && a.datapackCount == b.datapackCount
                        && a.hardcore == b.hardcore;
            }
        }, false);
        mVisible.clear();
        mVisible.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    private static boolean eq(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    public void setQuery(@Nullable String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.equals(mQuery)) return;
        mQuery = q;
        refilter();
    }

    public void setSortMode(int mode) {
        if (mSortMode == mode) return;
        mSortMode = mode;
        refilter();
    }

    public int getSortMode() { return mSortMode; }

    private void refilter() {
        List<WorldEntry> next = new ArrayList<>(mAll.size());
        for (WorldEntry w : mAll) {
            if (mQuery.isEmpty()
                    || w.displayName.toLowerCase(Locale.ROOT).contains(mQuery)
                    || w.folderName.toLowerCase(Locale.ROOT).contains(mQuery)) {
                next.add(w);
            }
        }
        next.sort(comparatorFor(mSortMode));
        dispatchSwap(next);
    }

    @NonNull
    private static Comparator<WorldEntry> comparatorFor(int mode) {
        switch (mode) {
            case SORT_NAME:
                return (a, b) -> a.displayName.compareToIgnoreCase(b.displayName);
            case SORT_SIZE:
                return (a, b) -> Long.compare(b.sizeBytes, a.sizeBytes);
            case SORT_VERSION:
                return (a, b) -> {
                    if (a.versionName == null && b.versionName == null) return 0;
                    if (a.versionName == null) return 1;
                    if (b.versionName == null) return -1;
                    return compareVersionsDesc(a.versionName, b.versionName);
                };
            case SORT_LAST_PLAYED:
            default:
                return (a, b) -> Long.compare(b.lastPlayedMs, a.lastPlayedMs);
        }
    }

    private static int compareVersionsDesc(@NonNull String x, @NonNull String y) {
        String[] a = x.split("\\.");
        String[] b = y.split("\\.");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = i < a.length ? parseIntSafe(a[i]) : 0;
            int bi = i < b.length ? parseIntSafe(b[i]) : 0;
            if (ai != bi) return Integer.compare(bi, ai); // newest first
        }
        return 0;
    }

    private static int parseIntSafe(@NonNull String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_world, parent, false);
        VH vh = new VH(v);
        // Click listeners are wired ONCE here (not per bind). onBindViewHolder
        // then performs only pure data-binding — zero lambdas allocated per row.
        vh.itemView.setOnClickListener(vw -> onHolderClick(vh, false));
        vh.menu.setOnClickListener(vw -> onHolderClick(vh, true));
        // RecyclerView rows are skipped by the screen-wide attach pass, so each
        // row gets the launcher's shared tactile press feel right here.
        net.kdt.pojavlaunch.UiMotion.pressFeedback(vh.itemView, vh.menu);
        return vh;
    }

    private void onHolderClick(@NonNull VH vh, boolean menu) {
        int pos = vh.getAbsoluteAdapterPosition();
        if (pos == RecyclerView.NO_POSITION || pos >= mVisible.size()) return;
        WorldEntry w = mVisible.get(pos);
        if (menu) mListener.onWorldMenu(w, vh.menu);
        else mListener.onWorldClick(w);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        WorldEntry w = mVisible.get(position);
        h.name.setText(w.displayName);

        String ver = w.versionName != null ? w.versionName : "—";
        h.meta.setText(ver + "  •  " + WorldOps.formatLastPlayed(w.lastPlayedMs)
                + (w.hardcore ? "  •  Hardcore" : ""));
        h.size.setText(w.sizeBytes >= 0 ? WorldOps.formatSize(w.sizeBytes) : "…");
        h.datapacks.setText(w.datapackCount >= 0
                ? h.itemView.getResources().getQuantityString(
                        R.plurals.cs_world_datapack_count, w.datapackCount, w.datapackCount)
                : "");
        h.datapacks.setVisibility(w.datapackCount > 0 ? View.VISIBLE : View.GONE);

        bindIcon(h.icon, w);

        // Phase 3 crash fix: the default RecyclerView ItemAnimator owns ALL
        // motion. We never start our own ViewPropertyAnimator on bound rows.
        // Every bind restores a pristine transform so recycled rows can never
        // carry leftovers from a previous animation lifetime.
        resetRowTransforms(h.itemView);
    }

    /** onViewRecycled: cancel anything in flight + scrub view state. */
    @Override
    public void onViewRecycled(@NonNull VH holder) {
        super.onViewRecycled(holder);
        holder.itemView.animate().cancel();
        resetRowTransforms(holder.itemView);
        holder.icon.setTag(null);
    }

    /** Detached rows must never animate — the ItemAnimator handles motion. */
    @Override
    public void onViewDetachedFromWindow(@NonNull VH holder) {
        super.onViewDetachedFromWindow(holder);
        holder.itemView.animate().cancel();
        resetRowTransforms(holder.itemView);
    }

    private static void resetRowTransforms(@NonNull View row) {
        row.setAlpha(1f);
        row.setTranslationX(0f);
        row.setTranslationY(0f);
        row.setScaleX(1f);
        row.setScaleY(1f);
        row.setRotation(0f);
    }

    private static void bindIcon(@NonNull ImageView into, @NonNull WorldEntry w) {
        File iconFile = w.iconFile();
        String key = iconFile != null
                ? iconFile.getAbsolutePath() + "@" + iconFile.lastModified()
                : "none:" + w.folderName;
        into.setTag(key);
        Bitmap cached = ICON_CACHE.get(key);
        if (cached != null) {
            into.setImageTintList(null);
            into.clearColorFilter();
            into.setImageBitmap(cached);
            return;
        }
        if (iconFile == null) {
            into.setImageResource(R.drawable.ic_nav_worlds);
            into.setColorFilter(0xFF6B7280);
            return;
        }
        into.setImageResource(R.drawable.ic_nav_worlds);
        into.setColorFilter(0xFF6B7280);
        PojavApplication.sExecutorService.execute(() -> {
            Bitmap bmp = decodeSampled(iconFile);
            if (bmp != null) {
                ICON_CACHE.put(key, bmp);
                into.post(() -> {
                    if (key.equals(into.getTag())) {
                        into.setImageTintList(null);
                        into.clearColorFilter();
                        into.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    @Nullable
    private static Bitmap decodeSampled(@NonNull File f) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sample = 1;
            while (opts.outWidth / sample > 256 && sample < 8) sample *= 2;
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), real);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public int getItemCount() {
        return mVisible.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;
        final TextView size;
        final TextView datapacks;
        final ImageButton menu;

        VH(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.world_icon);
            name = v.findViewById(R.id.world_name);
            meta = v.findViewById(R.id.world_meta);
            size = v.findViewById(R.id.world_size_chip);
            datapacks = v.findViewById(R.id.world_datapack_chip);
            menu = v.findViewById(R.id.world_menu_button);
        }
    }
}
