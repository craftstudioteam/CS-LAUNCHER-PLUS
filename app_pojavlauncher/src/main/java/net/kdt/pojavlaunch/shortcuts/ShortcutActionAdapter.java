package net.kdt.pojavlaunch.shortcuts;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.List;

/**
 * Lists the available {@link ShortcutType}s as single-choice rows.
 *
 * <p>Rows whose action already has a shortcut for this profile show an
 * "Added" chip, so the user can tell at a glance what is left to create.</p>
 */
public class ShortcutActionAdapter
        extends RecyclerView.Adapter<ShortcutActionAdapter.ViewHolder> {

    public interface OnActionSelectedListener {
        void onActionSelected(@NonNull ShortcutType type);
    }

    private final List<ShortcutType> mActions;
    private final OnActionSelectedListener mListener;

    /** Actions that already exist on the home screen for the current profile. */
    private final List<ShortcutType> mExisting;

    private ShortcutType mSelected;

    public ShortcutActionAdapter(@NonNull List<ShortcutType> actions,
                                 @NonNull ShortcutType selected,
                                 @NonNull List<ShortcutType> existing,
                                 @Nullable OnActionSelectedListener listener) {
        mActions = actions;
        mSelected = selected;
        mExisting = existing;
        mListener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return mActions.get(position).ordinal();
    }

    @NonNull
    public ShortcutType getSelected() {
        return mSelected;
    }

    /** Change the selection and repaint only the two affected rows. */
    public void setSelected(@NonNull ShortcutType type) {
        if (mSelected == type) return;
        int previous = mActions.indexOf(mSelected);
        int next = mActions.indexOf(type);
        mSelected = type;
        if (previous >= 0) notifyItemChanged(previous);
        if (next >= 0) notifyItemChanged(next);
    }

    /** Refresh the "Added" chips after a shortcut is created or removed. */
    public void setExisting(@NonNull List<ShortcutType> existing) {
        mExisting.clear();
        mExisting.addAll(existing);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shortcut_action, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ShortcutType type = mActions.get(position);
        boolean isSelected = type == mSelected;

        holder.title.setText(type.getLabelRes());
        holder.description.setText(type.getDescriptionRes());
        holder.icon.setImageResource(type.getIconRes());

        int accent = ContextCompat.getColor(
                holder.itemView.getContext(), R.color.premium_cyan);
        int muted = ContextCompat.getColor(
                holder.itemView.getContext(), R.color.secondary_text);

        holder.icon.setColorFilter(isSelected ? accent : muted);
        holder.root.setBackgroundResource(isSelected
                ? R.drawable.bg_shortcut_action_selected
                : R.drawable.bg_shortcut_action_default);
        holder.check.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
        holder.existingBadge.setVisibility(
                mExisting.contains(type) ? View.VISIBLE : View.GONE);

        holder.root.setOnClickListener(v -> {
            setSelected(type);
            if (mListener != null) mListener.onActionSelected(type);
        });
    }

    @Override
    public int getItemCount() {
        return mActions.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final View root;
        final ImageView icon;
        final TextView title;
        final TextView description;
        final ImageView check;
        final TextView existingBadge;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            root = itemView.findViewById(R.id.action_root);
            icon = itemView.findViewById(R.id.action_icon);
            title = itemView.findViewById(R.id.action_title);
            description = itemView.findViewById(R.id.action_description);
            check = itemView.findViewById(R.id.action_check);
            existingBadge = itemView.findViewById(R.id.action_existing_badge);
        }
    }
}
