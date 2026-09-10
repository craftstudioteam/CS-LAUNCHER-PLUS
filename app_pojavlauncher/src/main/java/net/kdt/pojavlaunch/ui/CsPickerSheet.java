package net.kdt.pojavlaunch.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 9 — generic single-choice picker popup ("sheet") in the graphite
 * CS design language. Replaces raw {@link android.widget.Spinner} dropdowns
 * in the profile editor for Java runtime + renderer selection.
 *
 * Usage:
 * <pre>
 *   new CsPickerSheet(ctx)
 *       .glyph("J").kicker("SELECT").title("Java runtime").subtitle("…")
 *       .add("17", "Java 17", "jre-17 · aarch64", "DEFAULT")
 *       .selected(2)
 *       .onPick(index -> …)
 *       .show();
 * </pre>
 */
public class CsPickerSheet {

    public interface OnPick { void onPick(int index); }

    public static final class Option {
        final CharSequence glyph, title, meta, badge;
        public Option(CharSequence glyph, CharSequence title, CharSequence meta, CharSequence badge) {
            this.glyph = glyph; this.title = title; this.meta = meta; this.badge = badge;
        }
    }

    private final Context mContext;
    private final List<Option> mOptions = new ArrayList<>();
    private CharSequence mGlyph = "•", mKicker = "SELECT", mTitle = "", mSubtitle = null, mFooter = null;
    private int mSelected = -1;
    private OnPick mOnPick;
    private boolean mClosing;
    private Dialog mDialog;

    public CsPickerSheet(Context context) { mContext = context; }

    public CsPickerSheet glyph(CharSequence g) { mGlyph = g; return this; }
    public CsPickerSheet kicker(CharSequence k) { mKicker = k; return this; }
    public CsPickerSheet title(CharSequence t) { mTitle = t; return this; }
    public CsPickerSheet subtitle(CharSequence s) { mSubtitle = s; return this; }
    public CsPickerSheet footer(CharSequence f) { mFooter = f; return this; }
    public CsPickerSheet selected(int index) { mSelected = index; return this; }
    public CsPickerSheet onPick(OnPick l) { mOnPick = l; return this; }

    public CsPickerSheet add(CharSequence glyph, CharSequence title, CharSequence meta) {
        return add(glyph, title, meta, null);
    }

    public CsPickerSheet add(CharSequence glyph, CharSequence title, CharSequence meta, CharSequence badge) {
        mOptions.add(new Option(glyph, title, meta, badge));
        return this;
    }

    public Dialog show() {
        final Dialog dialog = new Dialog(mContext);
        mDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_cs_picker);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.66f);
            w.setWindowAnimations(0);
        }

        final View card = dialog.findViewById(R.id.pk_card);
        final View well = dialog.findViewById(R.id.pk_glyph_well);
        final TextView glyph = dialog.findViewById(R.id.pk_glyph);
        final TextView kicker = dialog.findViewById(R.id.pk_kicker);
        final TextView title = dialog.findViewById(R.id.pk_title);
        final TextView subtitle = dialog.findViewById(R.id.pk_subtitle);
        final TextView close = dialog.findViewById(R.id.pk_close);
        final TextView footer = dialog.findViewById(R.id.pk_footer);
        final ScrollView scroll = dialog.findViewById(R.id.pk_scroll);
        final LinearLayout rows = dialog.findViewById(R.id.pk_rows);

        glyph.setText(mGlyph);
        kicker.setText(mKicker);
        title.setText(mTitle);
        if (mSubtitle == null || mSubtitle.length() == 0) subtitle.setVisibility(View.GONE);
        else subtitle.setText(mSubtitle);
        if (mFooter != null) footer.setText(mFooter);

        float d = mContext.getResources().getDisplayMetrics().density;
        int screenH = mContext.getResources().getDisplayMetrics().heightPixels;
        // Rows area never taller than ~55% of the screen — the sheet scrolls instead.
        ViewGroup.LayoutParams slp = scroll.getLayoutParams();
        int maxRows = (int) (screenH * 0.55f);
        int estimated = (int) (mOptions.size() * 66 * d + 12 * d);
        slp.height = Math.min(maxRows, estimated);
        scroll.setLayoutParams(slp);

        LayoutInflater inflater = LayoutInflater.from(mContext);
        final List<View> rowViews = new ArrayList<>();
        for (int i = 0; i < mOptions.size(); i++) {
            final int index = i;
            Option o = mOptions.get(i);
            View row = inflater.inflate(R.layout.item_cs_picker_row, rows, false);
            TextView g = row.findViewById(R.id.pkr_glyph);
            TextView t = row.findViewById(R.id.pkr_title);
            TextView m = row.findViewById(R.id.pkr_meta);
            TextView b = row.findViewById(R.id.pkr_badge);
            View check = row.findViewById(R.id.pkr_check);
            g.setText(o.glyph == null ? "" : o.glyph);
            t.setText(o.title);
            if (o.meta == null || o.meta.length() == 0) m.setVisibility(View.GONE); else m.setText(o.meta);
            if (o.badge != null && o.badge.length() > 0) { b.setVisibility(View.VISIBLE); b.setText(o.badge); }
            boolean sel = index == mSelected;
            row.setSelected(sel);
            check.setSelected(sel);
            row.setOnClickListener(v -> {
                if (mClosing) return;
                for (View rv : rowViews) { rv.setSelected(false); rv.findViewById(R.id.pkr_check).setSelected(false); }
                row.setSelected(true);
                check.setSelected(true);
                Anime.pulse(check);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                mSelected = index;
                row.postDelayed(() -> close(card, true), 170);
            });
            UiMotion.pressFeedback(row);
            rows.addView(row);
            rowViews.add(row);
        }

        UiMotion.pressFeedback(close);
        close.setOnClickListener(v -> close(card, false));

        dialog.show();
        if (w != null) {
            int width = (int) Math.min(420 * d, mContext.getResources().getDisplayMetrics().widthPixels - 36 * d);
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }

        // Entrance timeline — card outBack, glyph well pop, rows staggered from the selected one.
        card.setAlpha(0f); card.setScaleX(0.9f); card.setScaleY(0.9f); card.setTranslationY(18 * d);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(400)
                .setInterpolator(Anime.OUT_BACK).withLayer().start();
        well.setScaleX(0.6f); well.setScaleY(0.6f);
        well.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(480)
                .setInterpolator(Anime.OUT_ELASTIC).start();
        Anime.stagger(rows, 90, 34, Anime.Fx.FADE_UP);

        // Scroll the selected row into view once laid out.
        if (mSelected >= 0 && mSelected < rowViews.size()) {
            final View target = rowViews.get(mSelected);
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop() - (int) (40 * d))));
        }
        return dialog;
    }

    private void close(View card, boolean fire) {
        if (mClosing) return;
        mClosing = true;
        final int picked = mSelected;
        card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(200)
                .setInterpolator(Anime.IN_BACK).withEndAction(() -> {
                    try { if (mDialog != null) mDialog.dismiss(); } catch (Exception ignored) {}
                    if (fire && mOnPick != null) mOnPick.onPick(picked);
                }).start();
    }
}
