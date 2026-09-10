package net.kdt.pojavlaunch.shortcuts;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Lists every shortcut the launcher has created and lets the user revoke them.
 *
 * <p>Android gives apps no way to enumerate their pinned shortcuts, so this reads
 * from {@link ShortcutRegistry}. Entries whose profile has been deleted are
 * flagged as orphaned and can be cleaned up in one tap.</p>
 */
public class ShortcutManagerFragment extends Fragment {

    public static final String TAG = "ShortcutManagerFragment";

    private RecyclerView mRecycler;
    private View mEmptyState;
    private TextView mCountLabel;
    private EntryAdapter mAdapter;

    public ShortcutManagerFragment() {
        super(R.layout.fragment_shortcut_manager);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mRecycler = view.findViewById(R.id.shortcut_manager_recycler);
        mEmptyState = view.findViewById(R.id.shortcut_manager_empty);
        mCountLabel = view.findViewById(R.id.shortcut_manager_count);

        ImageButton back = view.findViewById(R.id.shortcut_manager_back);
        back.setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());

        ImageButton clearAll = view.findViewById(R.id.shortcut_manager_clear_all);
        clearAll.setOnClickListener(v -> confirmRemoveAll());

        mRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Another screen may have added a shortcut while this one was hidden.
        refresh();
    }

    private void refresh() {
        if (getContext() == null) return;

        // Drop entries whose profile is gone before rendering the list.
        ProfileShortcutHelper.pruneOrphanShortcuts(requireContext());

        List<ShortcutRecord> records = ShortcutRegistry.loadByUsage(requireContext());

        boolean empty = records.isEmpty();
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        mRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);

        mCountLabel.setText(getResources().getQuantityString(
                R.plurals.shortcut_manage_count, records.size(), records.size()));

        mAdapter = new EntryAdapter(records);
        mRecycler.setAdapter(mAdapter);
    }

    private void confirmRemoveAll() {
        if (getContext() == null) return;
        if (ShortcutRegistry.count(requireContext()) == 0) {
            Toast.makeText(getContext(), R.string.shortcut_manage_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.shortcut_remove_all)
                .setMessage(R.string.shortcut_remove_all_confirm)
                .setPositiveButton(R.string.shortcut_remove, (d, w) -> {
                    ProfileShortcutHelper.removeAllShortcuts(requireContext());
                    Toast.makeText(getContext(), R.string.shortcut_removed,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(@NonNull ShortcutRecord record) {
        if (getContext() == null) return;

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.shortcut_remove)
                .setMessage(getString(R.string.shortcut_remove_confirm, record.label))
                .setPositiveButton(R.string.shortcut_remove, (d, w) -> {
                    ProfileShortcutHelper.removeShortcut(requireContext(), record.shortcutId);
                    Toast.makeText(getContext(), R.string.shortcut_removed,
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** True when the profile behind a shortcut no longer exists. */
    private boolean isOrphaned(@NonNull ShortcutRecord record) {
        if (LauncherProfiles.mainProfileJson == null
                || LauncherProfiles.mainProfileJson.profiles == null) {
            return false;
        }
        return !LauncherProfiles.mainProfileJson.profiles.containsKey(record.profileKey);
    }

    // ─── Adapter ───────────────────────────────────────────────────────

    private class EntryAdapter extends RecyclerView.Adapter<EntryAdapter.ViewHolder> {

        private final List<ShortcutRecord> mRecords;

        EntryAdapter(@NonNull List<ShortcutRecord> records) {
            mRecords = new ArrayList<>(records);
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return mRecords.get(position).shortcutId.hashCode();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_shortcut_entry, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ShortcutRecord record = mRecords.get(position);
            ShortcutType type = record.getType();

            holder.label.setText(record.label);

            String profileName = record.profileName != null
                    ? record.profileName : getString(R.string.shortcut_unknown_profile);
            holder.subtitle.setText(getString(R.string.shortcut_entry_subtitle,
                    getString(type.getLabelRes()), profileName));

            holder.stats.setText(buildStatsLine(record));

            Bitmap icon = ProfileShortcutHelper.loadShortcutIcon(
                    requireContext(), record.shortcutId);
            if (icon != null) {
                holder.icon.setImageBitmap(icon);
            } else {
                // Cached PNG missing (cleared storage) — fall back to the glyph.
                holder.icon.setImageResource(type.getIconRes());
            }

            holder.orphanBadge.setVisibility(isOrphaned(record) ? View.VISIBLE : View.GONE);
            holder.delete.setOnClickListener(v -> confirmRemove(record));
        }

        /** "Used 12 times · last 3 Jul 2026" or a created-on line when never used. */
        @NonNull
        private String buildStatsLine(@NonNull ShortcutRecord record) {
            DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM);
            if (record.useCount <= 0) {
                return getString(R.string.shortcut_entry_never_used,
                        format.format(new Date(record.createdAt)));
            }
            return getResources().getQuantityString(
                    R.plurals.shortcut_entry_used, record.useCount,
                    record.useCount, format.format(new Date(record.lastUsedAt)));
        }

        @Override
        public int getItemCount() {
            return mRecords.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView subtitle;
            final TextView stats;
            final TextView orphanBadge;
            final ImageButton delete;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.entry_icon);
                label = itemView.findViewById(R.id.entry_label);
                subtitle = itemView.findViewById(R.id.entry_subtitle);
                stats = itemView.findViewById(R.id.entry_stats);
                orphanBadge = itemView.findViewById(R.id.entry_orphan_badge);
                delete = itemView.findViewById(R.id.entry_delete);
            }
        }
    }
}
