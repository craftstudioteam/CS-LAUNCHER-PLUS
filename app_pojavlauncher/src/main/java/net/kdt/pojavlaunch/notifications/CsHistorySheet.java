package net.kdt.pojavlaunch.notifications;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Recent launcher events — a compact dark sheet that springs in from the top
 * right, listing the notification history (downloads, launches, errors,
 * profile operations). Tap outside to dismiss.
 */
final class CsHistorySheet {

    private CsHistorySheet() { }

    static void show(Activity activity, ArrayDeque<CsNotifier.HistoryEntry> history) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        // one sheet at a time
        for (int i = decor.getChildCount() - 1; i >= 0; i--) {
            if (decor.getChildAt(i).getTag() instanceof String
                    && decor.getChildAt(i).getTag().equals("cs_history_sheet")) {
                decor.removeViewAt(i);
            }
        }
        float dp = activity.getResources().getDisplayMetrics().density;

        FrameLayout dim = new FrameLayout(activity);
        dim.setTag("cs_history_sheet");
        dim.setBackgroundColor(0x66000000);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * dp);
        bg.setColor(0xF415121A);
        bg.setStroke(Math.max(1, (int) dp), 0xFF2B2734);
        card.setBackground(bg);
        card.setElevation(18 * dp);
        int pad = (int) (16 * dp);
        card.setPadding(pad, pad, pad, (int) (12 * dp));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                (int) (330 * dp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clp.rightMargin = (int) (14 * dp);
        clp.topMargin = (int) (12 * dp);
        dim.addView(card, clp);

        TextView title = new TextView(activity);
        title.setText("Recent Activity");
        title.setTextColor(0xFFF2F2F5);
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        card.addView(title);
        TextView sub = new TextView(activity);
        int n = history.size();
        sub.setText(n == 0 ? "Nothing yet — downloads, launches and errors land here"
                : "Last " + n + " launcher event" + (n == 1 ? "" : "s"));
        sub.setTextColor(0xFF8F8A9E);
        sub.setTextSize(10.5f);
        sub.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (3 * dp);
        card.addView(sub, slp);

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scp.topMargin = (int) (10 * dp);
        card.addView(scroll, scp);

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.US);
        int shown = 0;
        for (CsNotifier.HistoryEntry e : history) {
            if (shown++ >= 12) break;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(activity);
            icon.setImageResource(CsNotifier.CardView.iconFor(e.type));
            icon.setColorFilter(CsNotifier.CardView.accentFor(e.type));
            row.addView(icon, new LinearLayout.LayoutParams((int) (15 * dp), (int) (15 * dp)));

            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.leftMargin = (int) (9 * dp);
            TextView t1 = new TextView(activity);
            t1.setText(e.title);
            t1.setTextColor(0xFFE9E6F2);
            t1.setTextSize(11.5f);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setIncludeFontPadding(false);
            t1.setSingleLine(true);
            t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView t2 = new TextView(activity);
            t2.setText((e.text == null ? "" : e.text) + "   ·  " + fmt.format(new Date(e.at)));
            t2.setTextColor(0xFF8F8A9E);
            t2.setTextSize(10f);
            t2.setIncludeFontPadding(false);
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams t2lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t2lp.topMargin = (int) (2 * dp);
            col.addView(t1);
            col.addView(t2, t2lp);
            row.addView(col, colLp);

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = (int) (9 * dp);
            list.addView(row, rlp);
        }

        decor.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.setOnClickListener(v -> dismiss(dim));
        // entrance choreography
        card.setAlpha(0f);
        card.setTranslationX(40 * dp);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        dim.setAlpha(0f);
        dim.animate().alpha(1f).setDuration(180).start();
        card.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(340)
                .setInterpolator(new DecelerateInterpolator(1.3f))
                .start();
        // staggered rows
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            row.setAlpha(0f);
            row.setTranslationY(8 * dp);
            row.animate().alpha(1f).translationY(0f)
                    .setStartDelay(120 + i * 28L)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    private static void dismiss(FrameLayout dim) {
        dim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> {
                    ViewGroup p = (ViewGroup) dim.getParent();
                    if (p != null) p.removeView(dim);
                }).start();
    }
}
