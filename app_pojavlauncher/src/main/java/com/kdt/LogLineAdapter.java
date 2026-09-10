package com.kdt;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Streaming log-line adapter (Phase 3 premium terminal).
 *
 * One row per log line; each arrival animates through the RecyclerView item
 * animator (fade + slide) for a premium line-by-line rhythm.
 *
 * Phase 3 additions:
 * - Raw buffer + filtered display list → live SEARCH without losing data
 * - Severity-tagged lines (info / warn / error / success / debug)
 * - Full-text snapshot for Copy / Export
 */
public class LogLineAdapter extends RecyclerView.Adapter<LogLineAdapter.LineVH> {

    public static final class LogLine {
        public final CharSequence text;   // tinted, for display
        public final String raw;          // untinted, for search/copy/export
        public LogLine(CharSequence text, String raw) {
            this.text = text;
            this.raw = raw != null ? raw : "";
        }
    }

    private static final int MAX_LINES = 400;
    private final List<LogLine> mRaw = new ArrayList<>();
    private final List<LogLine> mVisible = new ArrayList<>();
    @Nullable private String mFilter;

    public void appendLine(LogLine line) {
        if (mRaw.size() >= MAX_LINES) {
            int remove = Math.min(40, mRaw.size());
            mRaw.subList(0, remove).clear();
            refilter();
            // Continue and append the newest event. The previous implementation
            // returned here and silently lost the line that triggered trimming.
        }
        mRaw.add(line);
        if (matches(line)) {
            mVisible.add(line);
            notifyItemInserted(mVisible.size() - 1);
        }
    }

    /** Replace the newest terminal row (used by CLI typing/spinner animation). */
    public void replaceLastLine(@NonNull LogLine line) {
        if (mRaw.isEmpty()) {
            appendLine(line);
            return;
        }
        mRaw.set(mRaw.size() - 1, line);
        if (mFilter == null && !mVisible.isEmpty()) {
            mVisible.set(mVisible.size() - 1, line);
            notifyItemChanged(mVisible.size() - 1, "terminal-frame");
        } else {
            refilter();
        }
    }

    public void setFilter(@Nullable String query) {
        String f = query == null || query.trim().isEmpty()
                ? null : query.trim().toLowerCase(Locale.US);
        if ((f == null && mFilter == null) || (f != null && f.equals(mFilter))) return;
        mFilter = f;
        refilter();
    }

    private void refilter() {
        mVisible.clear();
        if (mFilter == null) {
            mVisible.addAll(mRaw);
        } else {
            for (LogLine l : mRaw) if (matches(l)) mVisible.add(l);
        }
        notifyDataSetChanged();
    }

    private boolean matches(@NonNull LogLine l) {
        return mFilter == null || l.raw.toLowerCase(Locale.US).contains(mFilter);
    }

    public void clear() {
        mRaw.clear();
        mVisible.clear();
        notifyDataSetChanged();
    }

    /** All lines (or the filtered view) as one plain-text blob for share/copy. */
    @NonNull
    public String dumpText() {
        StringBuilder sb = new StringBuilder();
        List<LogLine> src = mFilter == null ? mRaw : mVisible;
        for (LogLine l : src) sb.append(l.raw).append('\n');
        return sb.toString();
    }

    public int rawCount() { return mRaw.size(); }
    public int visibleCount() { return mVisible.size(); }

    @NonNull
    @Override
    public LineVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                .inflate(net.kdt.pojavlaunch.R.layout.item_log_line, parent, false);
        return new LineVH(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull LineVH holder, int position) {
        holder.textView.setText(mVisible.get(position).text);
    }

    @Override
    public void onBindViewHolder(@NonNull LineVH holder, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty()) {
            holder.textView.setText(mVisible.get(position).text);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public int getItemCount() {
        return mVisible.size();
    }

    static class LineVH extends RecyclerView.ViewHolder {
        final TextView textView;
        LineVH(View itemView) {
            super(itemView);
            textView = (TextView) itemView;
        }
    }
}
