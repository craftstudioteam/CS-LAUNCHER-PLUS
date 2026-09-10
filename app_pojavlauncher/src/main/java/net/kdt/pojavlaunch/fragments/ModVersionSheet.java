package net.kdt.pojavlaunch.fragments;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resource Detail — FILE PICKER v2 (Phase 8).
 *
 * <p>A centred two-pane card: the left rail filters by <b>Minecraft version</b>
 * and <b>loader</b> (chips derived from the files themselves, the profile's own
 * version/loader pre-selected), the right pane lists the files that survive the
 * filters — compatible ones first under RECOMMENDED, mismatches below with a
 * dashed edge. Tapping a row selects it (footer updates); USE THIS FILE (or a
 * second tap on the selected row) confirms.
 *
 * <p>Pure presentation: the host hands over parallel arrays (names, meta lines,
 * compatibility flags, selected index, and optionally per-file MC lists and
 * loader lists) and receives the chosen index back through {@link Listener}.
 * The three-argument-family {@link #newInstance} of the previous build keeps
 * working — without the extra arrays the rail simply shows no version/loader
 * chips.
 */
public class ModVersionSheet extends DialogFragment {

    public static final String TAG = "ModVersionSheet";

    public interface Listener { void onVersionPicked(int index); }

    private static final String A_TITLE = "title", A_TARGET = "target", A_NAMES = "names",
            A_META = "meta", A_OK = "ok", A_SEL = "sel",
            A_MC = "mc", A_LOADERS = "loaders", A_TARGET_MC = "target_mc", A_TARGET_LOADER = "target_loader";

    private static final String SEP = "\u001f";

    private Listener mListener;
    private View mSheet;
    private LinearLayout mList;
    private LinearLayout mMcChips, mLoaderChips;
    private TextView mCount;
    private TextView mFilterCompat, mFilterAll;
    private TextView mSelectedName, mSelectedMeta, mUse;
    private boolean mOnlyCompatible = true;
    private boolean mClosing;

    private String[] mNames, mMeta;
    private boolean[] mOk;
    private String[][] mMc, mLoaders;
    private int mSelected;

    /** Active rail filters (null = any). */
    @Nullable private String mMcFilter;
    @Nullable private String mLoaderFilter;

    private final Map<Integer, View> mRowByIndex = new LinkedHashMap<>();

    public static ModVersionSheet newInstance(String title, String target, String[] names,
                                              String[] meta, boolean[] ok, int selected) {
        ModVersionSheet s = new ModVersionSheet();
        Bundle b = new Bundle();
        b.putString(A_TITLE, title);
        b.putString(A_TARGET, target);
        b.putStringArray(A_NAMES, names);
        b.putStringArray(A_META, meta);
        b.putBooleanArray(A_OK, ok);
        b.putInt(A_SEL, selected);
        s.setArguments(b);
        return s;
    }

    /**
     * Adds the per-file Minecraft lists + loader lists (so the rail can offer
     * version/loader chips) and the profile's own target so the right chips
     * start selected. Call on the instance returned by {@link #newInstance}.
     */
    public ModVersionSheet withFilters(@Nullable String[][] mcLists, @Nullable String[][] loaderLists,
                                       @Nullable String targetMc, @Nullable String targetLoader) {
        Bundle b = getArguments() != null ? getArguments() : new Bundle();
        b.putStringArray(A_MC, pack(mcLists));
        b.putStringArray(A_LOADERS, pack(loaderLists));
        b.putString(A_TARGET_MC, targetMc);
        b.putString(A_TARGET_LOADER, targetLoader);
        setArguments(b);
        return this;
    }

    private static String[] pack(@Nullable String[][] src) {
        if (src == null) return null;
        String[] out = new String[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? "" : TextUtils.join(SEP, src[i]);
        return out;
    }

    private static String[][] unpack(@Nullable String[] src, int n) {
        String[][] out = new String[n][];
        for (int i = 0; i < n; i++) {
            if (src == null || i >= src.length || src[i] == null || src[i].isEmpty()) out[i] = new String[0];
            else out[i] = src[i].split(SEP);
        }
        return out;
    }

    public void setListener(Listener l) { mListener = l; }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_mod_version_sheet);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.66f);
        }

        Bundle a = getArguments() != null ? getArguments() : new Bundle();
        mNames = a.getStringArray(A_NAMES);
        mMeta = a.getStringArray(A_META);
        mOk = a.getBooleanArray(A_OK);
        mSelected = a.getInt(A_SEL, -1);
        if (mNames == null) mNames = new String[0];
        if (mMeta == null) mMeta = new String[mNames.length];
        if (mOk == null) mOk = new boolean[mNames.length];
        mMc = unpack(a.getStringArray(A_MC), mNames.length);
        mLoaders = unpack(a.getStringArray(A_LOADERS), mNames.length);

        mSheet = dialog.findViewById(R.id.mvs_sheet);
        mList = dialog.findViewById(R.id.mvs_list);
        mCount = dialog.findViewById(R.id.mvs_count);
        mFilterCompat = dialog.findViewById(R.id.mvs_filter_compat);
        mFilterAll = dialog.findViewById(R.id.mvs_filter_all);
        mMcChips = dialog.findViewById(R.id.mvs_mc_chips);
        mLoaderChips = dialog.findViewById(R.id.mvs_loader_chips);
        mSelectedName = dialog.findViewById(R.id.mvs_selected_name);
        mSelectedMeta = dialog.findViewById(R.id.mvs_selected_meta);
        mUse = dialog.findViewById(R.id.mvs_use);
        TextView title = dialog.findViewById(R.id.mvs_title);
        TextView target = dialog.findViewById(R.id.mvs_target);
        View close = dialog.findViewById(R.id.mvs_close);
        View reset = dialog.findViewById(R.id.mvs_reset);

        title.setText(a.getString(A_TITLE, ""));
        String t = a.getString(A_TARGET, null);
        if (t == null || t.isEmpty()) target.setVisibility(View.GONE); else target.setText(t);

        // If nothing is compatible, the "fits" filter would show an empty list.
        boolean anyOkTmp = false;
        for (boolean b : mOk) if (b) { anyOkTmp = true; break; }
        final boolean anyOk = anyOkTmp;
        mOnlyCompatible = anyOk;

        // Rail chips start on the profile's own version/loader when those
        // actually appear in the file list; otherwise "any".
        String tMc = a.getString(A_TARGET_MC, null);
        String tLoader = a.getString(A_TARGET_LOADER, null);
        List<String> mcOptions = distinct(mMc, 8);
        List<String> loaderOptions = distinct(mLoaders, 6);
        mMcFilter = tMc != null && containsIgnoreCase(mcOptions, tMc) ? tMc : null;
        mLoaderFilter = tLoader != null && containsIgnoreCase(loaderOptions, tLoader) ? tLoader : null;
        buildRailChips(mMcChips, dialog.findViewById(R.id.mvs_mc_label), mcOptions, true);
        buildRailChips(mLoaderChips, dialog.findViewById(R.id.mvs_loader_label), loaderOptions, false);

        UiMotion.pressFeedback(close, mFilterCompat, mFilterAll, mUse, reset);
        close.setOnClickListener(v -> closeAnimated(-1));
        mFilterCompat.setOnClickListener(v -> setFilter(true));
        mFilterAll.setOnClickListener(v -> setFilter(false));
        reset.setOnClickListener(v -> {
            mMcFilter = null; mLoaderFilter = null; mOnlyCompatible = anyOk;
            refreshRailChips(); applyFilterChips(); buildRows(false);
            Anime.pulse(v);
        });
        mUse.setOnClickListener(v -> {
            if (mSelected < 0) { Anime.shake(mUse); return; }
            confirm(mSelected);
        });

        applyFilterChips();
        buildRows(true);
        updateFooter();

        // Entrance timeline: card pops in (outBack), rail slides from the left,
        // header fades down, rows stagger (in buildRows), footer rises last.
        float d = getResources().getDisplayMetrics().density;
        mSheet.setAlpha(0f);
        mSheet.setScaleX(0.92f); mSheet.setScaleY(0.92f);
        mSheet.setTranslationY(24f * d);
        mSheet.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(460).setInterpolator(Anime.OUT_BACK).withLayer().start();
        Anime.in(dialog.findViewById(R.id.mvs_rail), Anime.Fx.FADE_RIGHT, 120, 520, Anime.OUT_EXPO);
        Anime.in(dialog.findViewById(R.id.mvs_header), Anime.Fx.FADE_DOWN, 160, 480, Anime.OUT_EXPO);
        Anime.in(dialog.findViewById(R.id.mvs_footer), Anime.Fx.FADE_UP, 360, 520, Anime.OUT_EXPO);
        return dialog;
    }

    // ───────────────────────────── rail ─────────────────────────────

    private static List<String> distinct(String[][] lists, int cap) {
        Set<String> seen = new LinkedHashSet<>();
        for (String[] l : lists) if (l != null) for (String s : l) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty()) seen.add(v);
        }
        List<String> out = new ArrayList<>(seen);
        // Newest Minecraft first (natural descending), loaders as given.
        return out.size() > cap ? new ArrayList<>(out.subList(0, cap)) : out;
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        for (String s : list) if (s.equalsIgnoreCase(v)) return true;
        return false;
    }

    private void buildRailChips(LinearLayout host, View label, List<String> options, boolean isMc) {
        host.removeAllViews();
        if (options.isEmpty()) { host.setVisibility(View.GONE); label.setVisibility(View.GONE); return; }
        float d = getResources().getDisplayMetrics().density;
        List<String> all = new ArrayList<>(options.size() + 1);
        all.add(null); // "Any"
        all.addAll(options);
        // Two chips per row so the rail stays narrow.
        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = (int) (5 * d);
                host.addView(row, rlp);
            }
            final String value = all.get(i);
            TextView chip = new TextView(requireContext());
            chip.setText(value == null ? "Any" : (isMc ? value : capitalise(value)));
            chip.setTextSize(9.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setIncludeFontPadding(false);
            chip.setSingleLine(true);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
            chip.setTag(value == null ? "" : value);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (26 * d), 1f);
            if (i % 2 == 1) lp.setMarginStart((int) (5 * d));
            row.addView(chip, lp);
            UiMotion.pressFeedback(chip);
            chip.setOnClickListener(v -> {
                if (isMc) mMcFilter = value; else mLoaderFilter = value;
                refreshRailChips();
                Anime.pop(chip);
                buildRows(false);
            });
        }
        // Odd count → pad the last row so the chip keeps half width.
        if (row != null && row.getChildCount() == 1) {
            View pad = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginStart((int) (5 * d));
            row.addView(pad, lp);
        }
        refreshRailChips();
    }

    private void refreshRailChips() {
        styleRail(mMcChips, mMcFilter);
        styleRail(mLoaderChips, mLoaderFilter);
    }

    private static void styleRail(LinearLayout host, @Nullable String active) {
        if (host == null) return;
        for (int r = 0; r < host.getChildCount(); r++) {
            View row = host.getChildAt(r);
            if (!(row instanceof LinearLayout)) continue;
            LinearLayout rl = (LinearLayout) row;
            for (int c = 0; c < rl.getChildCount(); c++) {
                View v = rl.getChildAt(c);
                if (!(v instanceof TextView)) continue;
                String tag = String.valueOf(v.getTag());
                boolean on = active == null ? tag.isEmpty() : tag.equalsIgnoreCase(active);
                v.setBackgroundResource(on ? R.drawable.mvs_rail_chip_on : R.drawable.mvs_rail_chip);
                ((TextView) v).setTextColor(on ? 0xFF141519 : 0xFFC9CED8);
            }
        }
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "";
        if ("neoforge".equalsIgnoreCase(s)) return "NeoForge";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void setFilter(boolean onlyCompatible) {
        if (mOnlyCompatible == onlyCompatible) return;
        mOnlyCompatible = onlyCompatible;
        applyFilterChips();
        Anime.pulse(onlyCompatible ? mFilterCompat : mFilterAll);
        buildRows(false);
    }

    private void applyFilterChips() {
        mFilterCompat.setBackgroundResource(mOnlyCompatible ? R.drawable.mvs_rail_chip_on : R.drawable.mvs_rail_chip);
        mFilterCompat.setTextColor(mOnlyCompatible ? 0xFF141519 : 0xFFC9CED8);
        mFilterAll.setBackgroundResource(!mOnlyCompatible ? R.drawable.mvs_rail_chip_on : R.drawable.mvs_rail_chip);
        mFilterAll.setTextColor(!mOnlyCompatible ? 0xFF141519 : 0xFFC9CED8);
    }

    // ───────────────────────────── rows ─────────────────────────────

    private boolean passesRail(int i) {
        if (mMcFilter != null) {
            boolean hit = false;
            for (String v : mMc[i]) if (v != null && v.trim().equalsIgnoreCase(mMcFilter)) { hit = true; break; }
            if (!hit) return false;
        }
        if (mLoaderFilter != null) {
            boolean hit = false;
            for (String v : mLoaders[i]) if (v != null && v.trim().equalsIgnoreCase(mLoaderFilter)) { hit = true; break; }
            if (!hit) return false;
        }
        return true;
    }

    private void buildRows(boolean first) {
        mList.removeAllViews();
        mRowByIndex.clear();
        float d = getResources().getDisplayMetrics().density;
        int shown = 0, fit = 0;
        for (boolean b : mOk) if (b) fit++;
        // pass 1: compatible (RECOMMENDED band), pass 2: mismatches
        for (int pass = 0; pass < 2; pass++) {
            boolean wantOk = pass == 0;
            if (!wantOk && mOnlyCompatible) break;
            boolean labelAdded = false;
            for (int i = 0; i < mNames.length; i++) {
                if (mOk[i] != wantOk || !passesRail(i)) continue;
                if (!labelAdded) {
                    mList.addView(groupLabel(wantOk ? "RECOMMENDED FOR YOUR PROFILE" : "OTHER FILES  ·  may not work", wantOk, d));
                    labelAdded = true;
                }
                View row = buildRow(i, d);
                mList.addView(row);
                mRowByIndex.put(i, row);
                shown++;
            }
        }
        if (shown == 0) {
            TextView empty = new TextView(requireContext());
            empty.setText(mOnlyCompatible
                    ? "No file fits this profile with these filters.\nTry \"All files\" or reset the filters."
                    : "No file matches these filters.");
            empty.setTextColor(0xFF8A909C);
            empty.setTextSize(10.5f);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.25f);
            empty.setPadding(0, (int) (28 * d), 0, (int) (28 * d));
            mList.addView(empty);
            Anime.in(empty, Anime.Fx.FADE_UP, 60, 420, Anime.OUT_EXPO);
        }
        mCount.setText(mNames.length + (mNames.length == 1 ? " file" : " files") + "  ·  " + fit + " fit");
        // anime stagger(40): rows fade-up one after another
        Anime.stagger(mList, first ? 260 : 0, 40, Anime.Fx.FADE_UP);
    }

    private View groupLabel(String text, boolean ok, float d) {
        LinearLayout wrap = new LinearLayout(requireContext());
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * d); lp.bottomMargin = (int) (8 * d);
        wrap.setLayoutParams(lp);
        wrap.setPadding((int) (4 * d), 0, (int) (4 * d), 0);

        View dot = new View(requireContext());
        dot.setBackgroundResource(ok ? R.drawable.rd_dot : R.drawable.rd_dot_off);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (6 * d), (int) (6 * d));
        dlp.setMarginEnd((int) (8 * d));
        wrap.addView(dot, dlp);

        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(8f);
        tv.setLetterSpacing(0.12f);
        tv.setIncludeFontPadding(false);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(ok ? 0xFFD2D6DE : 0xFF8A909C);
        wrap.addView(tv);
        return wrap;
    }

    private View buildRow(final int index, float d) {
        boolean ok = mOk[index];
        boolean selected = index == mSelected;
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(selected ? R.drawable.rd_row_selected
                : ok ? R.drawable.rd_row : R.drawable.mvs_row_mismatch);
        row.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * d);
        row.setLayoutParams(lp);
        row.setTag(index);

        // index / check disc
        TextView disc = new TextView(requireContext());
        disc.setText(selected ? "✓" : String.valueOf(index + 1));
        disc.setTextSize(selected ? 12f : 9.5f);
        disc.setGravity(Gravity.CENTER);
        disc.setIncludeFontPadding(false);
        disc.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        disc.setTextColor(selected ? 0xFF141519 : 0xFF9AA0AC);
        disc.setBackgroundResource(selected ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
        disc.setTag("disc");
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (26 * d), (int) (26 * d));
        dlp.setMarginEnd((int) (11 * d));
        row.addView(disc, dlp);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(requireContext());
        name.setText(mNames[index] != null ? mNames[index] : "File " + (index + 1));
        name.setTextSize(12f);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setTextColor(ok ? 0xFFF4F6F9 : 0xFF9AA0AC);
        col.addView(name);

        // meta as small tags: MC range + loaders
        LinearLayout tags = new LinearLayout(requireContext());
        tags.setOrientation(LinearLayout.HORIZONTAL);
        tags.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (5 * d);
        col.addView(tags, tlp);
        String[] mcs = mMc[index];
        if (mcs.length > 0) {
            String range = mcs.length == 1 ? mcs[0] : mcs[0] + " – " + mcs[mcs.length - 1];
            tags.addView(tag(range, false, d));
        } else if (mMeta[index] != null && !mMeta[index].isEmpty()) {
            String m = mMeta[index];
            int cut = m.indexOf("  ·  ");
            tags.addView(tag(cut > 0 ? m.substring(0, cut) : m, false, d));
        }
        for (int k = 0; k < Math.min(3, mLoaders[index].length); k++) {
            String l = mLoaders[index][k];
            if (l == null || l.trim().isEmpty()) continue;
            boolean hot = mLoaderFilter != null && l.trim().equalsIgnoreCase(mLoaderFilter);
            tags.addView(tag(capitalise(l.trim()), hot, d));
        }
        if (tags.getChildCount() == 0) col.removeView(tags);

        TextView state = new TextView(requireContext());
        state.setText(ok ? (index == firstOk() ? "LATEST" : "OK") : "MISMATCH");
        state.setTextSize(7f);
        state.setLetterSpacing(0.08f);
        state.setIncludeFontPadding(false);
        state.setGravity(Gravity.CENTER);
        state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        state.setTextColor(ok ? 0xFFD2D6DE : 0xFFE8C989);
        state.setBackgroundResource(ok ? R.drawable.mvs_state_ok : R.drawable.mvs_state_warn);
        state.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart((int) (8 * d));
        row.addView(state, slp);

        UiMotion.pressFeedback(row);
        row.setOnClickListener(v -> {
            if (mClosing) return;
            if (mSelected == index) { confirm(index); return; }  // second tap = confirm
            select(index);
        });
        return row;
    }

    private View tag(String text, boolean hot, float d) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextSize(8.5f);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setTextColor(hot ? 0xFF141519 : 0xFF9AA0AC);
        t.setBackgroundResource(hot ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
        t.setPadding((int) (7 * d), (int) (2 * d), (int) (7 * d), (int) (2 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (5 * d));
        t.setLayoutParams(lp);
        return t;
    }

    /** Move the selection highlight to {@code index} without closing. */
    private void select(int index) {
        int previous = mSelected;
        mSelected = index;
        View old = mRowByIndex.get(previous);
        if (old != null) restyleRow(old, previous, false);
        View now = mRowByIndex.get(index);
        if (now != null) {
            restyleRow(now, index, true);
            View disc = now.findViewWithTag("disc");
            if (disc != null) Anime.pop(disc);
            Anime.pulse(now);
        }
        updateFooter();
        Anime.pop(mUse);
    }

    private void restyleRow(View row, int index, boolean selected) {
        boolean ok = mOk[index];
        row.setBackgroundResource(selected ? R.drawable.rd_row_selected
                : ok ? R.drawable.rd_row : R.drawable.mvs_row_mismatch);
        View v = row.findViewWithTag("disc");
        if (v instanceof TextView) {
            TextView disc = (TextView) v;
            disc.setText(selected ? "✓" : String.valueOf(index + 1));
            disc.setTextSize(selected ? 12f : 9.5f);
            disc.setTextColor(selected ? 0xFF141519 : 0xFF9AA0AC);
            disc.setBackgroundResource(selected ? R.drawable.rd_pill_silver : R.drawable.rd_pill);
        }
    }

    private void updateFooter() {
        if (mSelected < 0 || mSelected >= mNames.length) {
            mSelectedName.setText("Nothing selected yet");
            mSelectedMeta.setText("Tap a file on the right");
            mUse.setEnabled(false);
            mUse.setAlpha(0.55f);
            return;
        }
        mSelectedName.setText(mNames[mSelected] != null ? mNames[mSelected] : "File " + (mSelected + 1));
        String meta = mMeta[mSelected] != null ? mMeta[mSelected] : "";
        mSelectedMeta.setText(mOk[mSelected] ? meta : (meta.isEmpty() ? "May not work on this profile" : meta + "  ·  may not work"));
        mSelectedMeta.setTextColor(mOk[mSelected] ? 0xFF8A909C : 0xFFE8C989);
        mUse.setEnabled(true);
        mUse.setAlpha(1f);
        mUse.setText(mOk[mSelected] ? "USE THIS FILE" : "USE ANYWAY");
    }

    private int firstOk() {
        for (int i = 0; i < mOk.length; i++) if (mOk[i]) return i;
        return -1;
    }

    private void confirm(int index) {
        if (mClosing) return;
        View row = mRowByIndex.get(index);
        if (row != null) {
            View disc = row.findViewWithTag("disc");
            if (disc != null) Anime.pop(disc);
            Anime.pulse(row);
        }
        Anime.pulse(mUse);
        mSheet.postDelayed(() -> closeAnimated(index), 200);
    }

    private void closeAnimated(final int result) {
        if (mClosing) return;
        mClosing = true;
        float d = getResources().getDisplayMetrics().density;
        mSheet.animate().cancel();
        mSheet.animate().translationY(28f * d).alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(220)
                .setInterpolator(Anime.IN_BACK)
                .withEndAction(() -> {
                    if (!isAdded()) return;
                    Listener l = mListener;
                    if (l == null && getParentFragment() instanceof Listener) l = (Listener) getParentFragment();
                    dismissAllowingStateLoss();
                    if (result >= 0 && l != null) l.onVersionPicked(result);
                }).start();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window w = dialog.getWindow();
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float d = dm.density;
            // Centred card: up to 640dp wide, 88% of the height — the rail +
            // list need the height, landscape phones still keep the dim visible.
            int width = (int) Math.min(640 * d, dm.widthPixels - 28 * d);
            int height = (int) Math.min(440 * d, dm.heightPixels * 0.90f);
            w.setLayout(width, height);
            w.setGravity(Gravity.CENTER);
            w.setWindowAnimations(0);
        }
    }
}
