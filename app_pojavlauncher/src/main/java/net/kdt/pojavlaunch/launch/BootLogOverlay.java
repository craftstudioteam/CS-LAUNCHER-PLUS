package net.kdt.pojavlaunch.launch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.utils.FpsCounter;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Phase 8 — BOOT LOG OVERLAY.
 *
 * <p>The small transparent console that sits on the LEFT of the game window
 * from the moment Play is pressed until Minecraft presents its first frame.
 * It mirrors the launcher log in a mini monospace font, colours each line by
 * severity (errors red, warnings amber, info silver, noise dim), caps itself
 * to the newest {@value #MAX_LINES} lines, and offers one tap target — EXPAND —
 * that opens the full log view. The moment a real frame is presented (same
 * signal the launch stage uses) it fades out on its own and stops listening;
 * it never blocks touches (the whole view is non-clickable except the pill).
 *
 * <p>No state of its own beyond the visible ring buffer: it does not write
 * files (Logger already does) and it survives config changes by simply being
 * re-attached with the activity.
 */
public class BootLogOverlay extends FrameLayout {

    private static final int MAX_LINES = 12; // Phase 9: taller console
    private static final long FIRST_FRAME_POLL_MS = 300L;
    // Phase 10: the console must never linger over a running world. It leaves
    // on the first presented frame (now also counted on the EGL/gl4es/Zink
    // bridge), on the first game touch, or after this cap — whichever is first.
    private static final long HARD_CAP_MS = 45L * 1000L;

    public interface Host { void onExpandRequested(); }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<CharSequence> mBuffer = new ArrayDeque<>(MAX_LINES + 1);
    private final ArrayDeque<String> mPending = new ArrayDeque<>();
    private boolean mFlushScheduled;
    private boolean mStarted, mDismissed;
    private int mErrors, mWarnings;
    private long mPresentBaseline = -1;

    private LinearLayout mLines;
    private TextView mTitle, mCounter, mExpand;
    private View mDot;
    private ValueAnimator mDotPulse;
    @Nullable private Host mHost;

    private final Logger.eventLogListener mListener = text -> {
        if (text == null || mDismissed) return;
        synchronized (mPending) {
            // Log bursts arrive as multi-line blobs; keep the last lines only.
            for (String l : text.split("\n")) if (!l.trim().isEmpty()) mPending.addLast(l);
            while (mPending.size() > 40) mPending.pollFirst();
            if (!mFlushScheduled) {
                mFlushScheduled = true;
                mHandler.postDelayed(this::flush, 48L);
            }
        }
    };

    private final Runnable mFirstFramePoll = new Runnable() {
        @Override public void run() {
            if (mDismissed) return;
            long total = FpsCounter.getTotalPresents();
            if (mPresentBaseline < 0) mPresentBaseline = total;
            if (total > mPresentBaseline && total > 0) {
                appendStyled(colour("› world presented — enjoy", 0xFFD2D6DE));
                mHandler.postDelayed(() -> dismiss(true), 900L);
                return;
            }
            mHandler.postDelayed(this, FIRST_FRAME_POLL_MS);
        }
    };

    public BootLogOverlay(@NonNull Context context) { this(context, null); }
    public BootLogOverlay(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public BootLogOverlay(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        build();
    }

    public void setHost(@Nullable Host host) { mHost = host; }

    // ───────────────────────── layout ─────────────────────────

    private void build() {
        final float d = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setClipChildren(false);

        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClickable(false);
        // Phase 9: the console was too small to read on a phone — 440dp wide
        // (capped at 48% of the screen so the game stays visible), 11sp lines.
        int screenW = getResources().getDisplayMetrics().widthPixels;
        // Phase 10: 36% of the screen max — a corner console, not half the game.
        int colW = (int) Math.min(380 * d, screenW * 0.36f);
        LayoutParams clp = new LayoutParams(colW, LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        clp.setMarginStart((int) (14 * d));
        addView(col, clp);

        // header: live dot · BOOT LOG · counter · EXPAND
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mDot = new View(getContext());
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(0xFFD2D6DE);
        mDot.setBackground(dot);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) (8 * d), (int) (8 * d));
        dlp.setMarginEnd((int) (9 * d));
        header.addView(mDot, dlp);

        mTitle = mono("BOOT LOG", 10.5f, 0xFF9AA0AC);
        mTitle.setLetterSpacing(0.16f);
        mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(mTitle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mCounter = mono("", 10f, 0xFF6B7280);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart((int) (8 * d));
        header.addView(mCounter, cl);

        mExpand = mono("EXPAND ›", 10f, 0xFF141519);
        mExpand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mExpand.setLetterSpacing(0.1f);
        mExpand.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFE6E9EF, 0xFFC9CED8});
        pill.setCornerRadius(20 * d);
        mExpand.setBackground(pill);
        mExpand.setClickable(true);
        mExpand.setFocusable(true);
        mExpand.setOnClickListener(v -> {
            net.kdt.pojavlaunch.Anime.pop(v);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (mHost != null) mHost.onExpandRequested();
        });
        header.addView(mExpand, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // hairline — short (header-width, not the whole column) so it can never
        // read as a stray line drawn across the game.
        View line = new View(getContext());
        line.setBackgroundColor(0x26FFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams((int) (120 * d), Math.max(1, (int) (1 * d)));
        llp.topMargin = (int) (8 * d); llp.bottomMargin = (int) (8 * d);
        col.addView(line, llp);

        // the line stack (transparent: scrim comes from a soft shadow on text)
        mLines = new LinearLayout(getContext());
        mLines.setOrientation(LinearLayout.VERTICAL);
        mLines.setClickable(false);
        col.addView(mLines, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setAlpha(0f);
        setVisibility(GONE);
    }

    private TextView mono(String text, float sp, int color) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(6f, 0f, 1f, 0xCC000000);
        return t;
    }

    // ───────────────────────── lifecycle ─────────────────────────

    /** Called once the game surface is being prepared (Play pressed). */
    public void start() {
        if (mStarted || mDismissed) return;
        mStarted = true;
        setVisibility(VISIBLE);
        setTranslationX(-24f * getResources().getDisplayMetrics().density);
        animate().alpha(1f).translationX(0f).setDuration(420)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        appendStyled(colour("› cs launcher plus · boot log", 0xFF9AA0AC));
        appendStyled(colour("› starting java runtime…", 0xFFD2D6DE));
        Logger.addLogListener(mListener);
        mPresentBaseline = FpsCounter.getTotalPresents();
        mHandler.postDelayed(mFirstFramePoll, FIRST_FRAME_POLL_MS);
        mHandler.postDelayed(() -> dismiss(true), HARD_CAP_MS);
        // live dot breathes
        mDotPulse = ValueAnimator.ofFloat(0.35f, 1f);
        mDotPulse.setDuration(700);
        mDotPulse.setRepeatCount(ValueAnimator.INFINITE);
        mDotPulse.setRepeatMode(ValueAnimator.REVERSE);
        mDotPulse.addUpdateListener(a -> mDot.setAlpha((float) a.getAnimatedValue()));
        mDotPulse.start();
    }

    /** Fade out + stop listening. Safe to call more than once. */
    public void dismiss(boolean animated) {
        if (mDismissed) return;
        mDismissed = true;
        mHandler.removeCallbacksAndMessages(null);
        Logger.removeLogListener(mListener);
        if (mDotPulse != null) { mDotPulse.cancel(); mDotPulse = null; }
        if (!animated) { setVisibility(GONE); return; }
        animate().alpha(0f).translationX(-16f * getResources().getDisplayMetrics().density)
                .setDuration(360).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    public boolean isDismissed() { return mDismissed; }

    /** Phase 10: the host saw the world (first frame / first game touch) — leave now. */
    public void onGameVisible() {
        if (mDismissed || !mStarted) return;
        appendStyled(colour("\u203a world presented \u2014 enjoy", 0xFFD2D6DE));
        mHandler.removeCallbacks(mFirstFramePoll);
        mHandler.postDelayed(() -> dismiss(true), 600L);
    }

    @Override
    protected void onDetachedFromWindow() {
        dismiss(false);
        super.onDetachedFromWindow();
    }

    // ───────────────────────── lines ─────────────────────────

    private void flush() {
        java.util.List<String> batch = new java.util.ArrayList<>();
        synchronized (mPending) {
            mFlushScheduled = false;
            while (!mPending.isEmpty()) batch.add(mPending.pollFirst());
        }
        if (mDismissed) return;
        // Only the last MAX_LINES of a burst can be visible anyway.
        int from = Math.max(0, batch.size() - MAX_LINES);
        for (int i = from; i < batch.size(); i++) appendStyled(style(batch.get(i)));
        mCounter.setText(String.format(Locale.US, "%d err · %d warn", mErrors, mWarnings));
        mCounter.setTextColor(mErrors > 0 ? 0xFFE8A0A6 : mWarnings > 0 ? 0xFFE8C989 : 0xFF6B7280);
    }

    private CharSequence style(String raw) {
        String line = raw.length() > 160 ? raw.substring(0, 157) + "…" : raw;
        String up = line.toUpperCase(Locale.US);
        int color;
        if (up.contains("FATAL") || up.contains("ERROR") || up.contains("EXCEPTION") || up.contains("CRASH")) {
            color = 0xFFE8A0A6; mErrors++;
        } else if (up.contains("WARN")) {
            color = 0xFFE8C989; mWarnings++;
        } else if (up.contains("DEBUG") || up.contains("TRACE") || up.startsWith("[")) {
            color = 0xFF8A909C;
        } else if (up.contains("INFO") || up.contains("LOADING") || up.contains("STARTING")) {
            color = 0xFFD2D6DE;
        } else {
            color = 0xFFB9BEC7;
        }
        return colour(line, color);
    }

    private static CharSequence colour(String s, int color) {
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, s.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private void appendStyled(CharSequence styled) {
        if (mLines == null) return;
        mBuffer.addLast(styled);
        while (mBuffer.size() > MAX_LINES) mBuffer.pollFirst();
        // Reuse views: the oldest row scrolls away, the newest slides in.
        if (mLines.getChildCount() >= MAX_LINES) mLines.removeViewAt(0);
        TextView row = mono("", 11.5f, 0xFFB9BEC7);
        row.setText(styled);
        row.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (3 * getResources().getDisplayMetrics().density);
        mLines.addView(row, lp);
        row.setAlpha(0f);
        row.setTranslationX(-10f * getResources().getDisplayMetrics().density);
        row.animate().alpha(1f).translationX(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        // older lines fade with age so the eye lands on the newest
        int n = mLines.getChildCount();
        for (int i = 0; i < n - 1; i++) {
            float age = (n - 1 - i) / (float) MAX_LINES;
            mLines.getChildAt(i).animate().alpha(Math.max(0.35f, 1f - age * 0.7f)).setDuration(220).start();
        }
    }
}
