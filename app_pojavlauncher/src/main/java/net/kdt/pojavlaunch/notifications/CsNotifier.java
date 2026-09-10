package net.kdt.pojavlaunch.notifications;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CS LAUNCHER PLUS — advanced in-app notification system.
 *
 * Floating stacked cards (top-end, cutout/status-inset aware) for downloads,
 * game-launch phases, runtime installs, profile operations, account switches,
 * errors and success states. Cards glide in, morph between states (progress →
 * success), and glide out. All motion is lightweight ViewProperty/ValueAnimator
 * work with no loops running on dismissed cards.
 *
 * Motion language adapted from modern component-library patterns (entrance
 * choreography, count-up numbers, state morphs) — implemented natively.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public final class CsNotifier {

    public static final int TYPE_INFO = 0;
    public static final int TYPE_SUCCESS = 1;
    public static final int TYPE_ERROR = 2;
    public static final int TYPE_PROGRESS = 3;

    private static final int MAX_CARDS = 3;
    private static final int MAX_HISTORY = 25;
    private static final long AUTO_DISMISS_MS = 3400L;
    private static final long SUCCESS_DISMISS_MS = 1900L;
    private static final long ERROR_DISMISS_MS = 6500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Card> sCards = new HashMap<>();
    private static final ArrayDeque<HistoryEntry> sHistory = new ArrayDeque<>();

    private static LinearLayout sStack;
    private static Activity sAttachedTo;
    private static final ArrayDeque<Runnable> sQueued = new ArrayDeque<>();

    private CsNotifier() { }

    // ── Public API (thread-safe; everything is posted to the main thread) ────

    public static void info(String title, String text) { show(TYPE_INFO, title, text, null, null); }

    public static void success(String title, String text) { show(TYPE_SUCCESS, title, text, null, null); }

    public static void error(String title, String text, String actionLabel, Runnable action) {
        show(TYPE_ERROR, title, text, actionLabel, action);
    }

    public static void show(int type, String title, String text, String actionLabel, Runnable action) {
        show(type, title, text, actionLabel, action, "n" + System.nanoTime());
    }

    /** Progress card that can be updated by key while a task runs. */
    public static void progress(String key, String title, String text) {
        MAIN.post(() -> ensureCard(TYPE_PROGRESS, key, title, text, null, null));
    }

    /** Smoothly move a progress card to a new percentage (0–100). */
    public static void updateProgress(String key, int percent, String text) {
        MAIN.post(() -> {
            Card c = sCards.get(key);
            if (c != null) c.setProgress(percent, text);
        });
    }

    /** Morph a progress card into a success card, then auto-dismiss it. */
    public static void success(String key, String title, String text) {
        MAIN.post(() -> {
            Card c = sCards.remove(key);
            if (c != null) c.morphToSuccess(title, text);
            else success(title, text);
        });
    }

    /** Remove a card quietly (e.g. the user cancelled the task). */
    public static void dismiss(String key) {
        MAIN.post(() -> {
            Card c = sCards.remove(key);
            if (c != null) c.remove();
        });
    }

    public static ArrayDeque<HistoryEntry> history() { return sHistory; }

    public static void openHistory(Activity activity) { CsHistorySheet.show(activity, sHistory); }

    // ── Internals ────────────────────────────────────────────────────────────

    private static void show(int type, String title, String text,
                             String actionLabel, Runnable action, String key) {
        MAIN.post(() -> {
            if (type == TYPE_PROGRESS) { ensureCard(type, key, title, text, actionLabel, action); return; }
            record(type, title, text);
            // Anti-spam: an identical card already on screen just refreshes in place.
            for (Card live : sCards.values()) {
                if (!live.mGone && live.type == type && title.equals(live.title)) {
                    live.update(title, text);
                    live.view.scheduleAutoDismiss();
                    return;
                }
            }
            Activity activity = ContextExecutor.peekActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                if (sQueued.size() < 5) sQueued.add(() -> show(type, title, text, actionLabel, action, key));
                return;
            }
            LinearLayout stack = stack(activity);
            if (stack == null) return;
            Card card = new Card(type, key, title, text, actionLabel, action, activity);
            addCard(stack, card);
        });
    }

    /** Notice that may arrive before any activity exists (e.g. boot post-mortem). */
    public static void queueBootNotice(int type, String title, String text,
                                       String actionLabel, Runnable action) {
        show(type, title, text, actionLabel, action, "boot" + System.nanoTime());
    }

    private static void ensureCard(int type, String key, String title, String text,
                                   String actionLabel, Runnable action) {
        Card existing = sCards.get(key);
        if (existing != null) { existing.update(title, text); return; }
        Activity activity = ContextExecutor.peekActivity();
        record(type, title, text);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        LinearLayout stack = stack(activity);
        if (stack == null) return;
        Card card = new Card(type, key, title, text, actionLabel, action, activity);
        addCard(stack, card);
    }

    private static void addCard(LinearLayout stack, Card card) {
        sCards.put(card.key, card);
        stack.addView(card.view, 0);
        card.enter();

        // Hard cap: when the stack overflows, the oldest card slides away.
        while (stack.getChildCount() > MAX_CARDS) {
            int last = stack.getChildCount() - 1;
            View oldest = stack.getChildAt(last);
            if (oldest instanceof CardView) {
                Card old = ((CardView) oldest).owner;
                sCards.remove(old.key);
                old.remove();
            } else break;
        }
    }

    /** One overlay container per activity — top-end, inset aware. */
    private static LinearLayout stack(Activity activity) {
        try {
            if (sStack != null && sAttachedTo == activity && sStack.getParent() != null) return sStack;
            if (sStack != null && sStack.getParent() instanceof ViewGroup) {
                ((ViewGroup) sStack.getParent()).removeView(sStack);
            }
            final float dp = activity.getResources().getDisplayMetrics().density;
            final LinearLayout stack = new LinearLayout(activity);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(Gravity.END);
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.rightMargin = (int) (14 * dp);
            lp.topMargin = (int) (10 * dp);
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(stack, (v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                lp.topMargin = Math.max((int) (10 * dp), top + (int) (6 * dp));
                v.setLayoutParams(lp);
                return insets;
            });
            ((ViewGroup) activity.getWindow().getDecorView()).addView(stack, lp);
            sStack = stack;
            sAttachedTo = activity;
            // Anything queued before a window existed (boot notices) shows now.
            while (!sQueued.isEmpty()) sQueued.poll().run();
            return stack;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void record(int type, String title, String text) {
        sHistory.addFirst(new HistoryEntry(type, title, text));
        while (sHistory.size() > MAX_HISTORY) sHistory.removeLast();
    }

    // ── Card ─────────────────────────────────────────────────────────────────

    static class Card {
        final int type;
        final String key;
        String title, text;
        final CardView view;
        private int mShownProgress = -1;
        private android.animation.ValueAnimator mChase;
        private boolean mGone = false;

        Card(int type, String key, String title, String text,
             String actionLabel, Runnable action, Context ctx) {
            this.type = type;
            this.key = key;
            this.title = title;
            this.text = text;
            this.view = new CardView(this, ctx, title, text, actionLabel, action);
        }

        void update(String newTitle, String newText) {
            title = newTitle; text = newText;
            view.refresh(newTitle, newText);
        }

        void enter() {
            float dp = view.getResources().getDisplayMetrics().density;
            view.setAlpha(0f);
            view.setTranslationX(46 * dp);
            view.setScaleX(0.95f);
            view.setScaleY(0.95f);
            view.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setDuration(340)
                    .setInterpolator(new DecelerateInterpolator(1.35f))
                    .start();
            if (type != TYPE_PROGRESS) view.scheduleAutoDismiss();
        }

        void setProgress(int percent, String newText) {
            if (mGone) return;
            if (newText != null && !newText.isEmpty()) text = newText;
            int target = Math.max(0, Math.min(100, percent));
            if (target == mShownProgress) return;
            final int from = mShownProgress < 0 ? 0 : mShownProgress;
            mShownProgress = target;
            if (mChase != null) mChase.cancel();
            mChase = android.animation.ValueAnimator.ofInt(from, target);
            mChase.setDuration(320);
            mChase.setInterpolator(new DecelerateInterpolator());
            mChase.addUpdateListener(a -> view.setProgressUi((int) a.getAnimatedValue()));
            mChase.start();
        }

        void morphToSuccess(String newTitle, String newText) {
            if (mGone) return;
            if (mChase != null) mChase.cancel();
            view.becomeSuccess(newTitle, newText);
            view.scheduleAutoDismiss(SUCCESS_DISMISS_MS);
        }

        void remove() {
            if (mGone) return;
            mGone = true;
            if (mChase != null) mChase.cancel();
            view.removeCallbacks(view.mDismiss);
            view.depart();
        }
    }

    /** The visual card — built entirely in code, dark/grey with purple accent. */
    static class CardView extends LinearLayout {
        final Card owner;
        final Runnable mDismiss;
        private final ImageView mIcon;
        private final TextView mTitle, mText, mAction, mPercent;
        private final View mFill;
        private final LinearLayout mBarTrack;
        private final float mDp;
        private int mType;

        CardView(Card card, Context ctx, String title, String text,
                 String actionLabel, Runnable action) {
            super(ctx);
            this.owner = card;
            mDismiss = () -> {
                Card c = sCards.remove(owner.key);
                if (c != null) c.remove(); else owner.remove();
            };
            this.mType = card.type;
            mDp = getResources().getDisplayMetrics().density;

            setOrientation(VERTICAL);
            setBackground(cardBg(0xF017141B, 0xFF2B2734));
            setElevation(14 * mDp);
            setPadding((int) (12 * mDp), (int) (10 * mDp), (int) (12 * mDp), (int) (10 * mDp));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    (int) (292 * mDp), ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = (int) (8 * mDp);
            setLayoutParams(cardLp);

            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            mIcon = new ImageView(ctx);
            mIcon.setImageResource(iconFor(mType));
            mIcon.setColorFilter(accentFor(mType));
            head.addView(mIcon, new LinearLayout.LayoutParams((int) (17 * mDp), (int) (17 * mDp)));

            mTitle = new TextView(ctx);
            mTitle.setText(title);
            mTitle.setTextColor(0xFFF2F2F5);
            mTitle.setTextSize(12f);
            mTitle.setTypeface(Typeface.DEFAULT_BOLD);
            mTitle.setIncludeFontPadding(false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlp.leftMargin = (int) (9 * mDp);
            tlp.rightMargin = (int) (8 * mDp);
            head.addView(mTitle, tlp);

            mPercent = new TextView(ctx);
            mPercent.setTextColor(0xFFC9C4D8);
            mPercent.setTextSize(11.5f);
            mPercent.setTypeface(Typeface.DEFAULT_BOLD);
            mPercent.setIncludeFontPadding(false);
            mPercent.setVisibility(mType == TYPE_PROGRESS ? VISIBLE : GONE);
            head.addView(mPercent);

            addView(head);

            mText = new TextView(ctx);
            mText.setText(text == null ? "" : text);
            mText.setTextColor(0xFFA6A1B5);
            mText.setTextSize(10.5f);
            mText.setIncludeFontPadding(false);
            mText.setSingleLine(true);
            mText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = (int) (4 * mDp);
            addView(mText, blp);

            mBarTrack = new LinearLayout(ctx);
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(2 * mDp);
            track.setColor(0xFF262230);
            mBarTrack.setBackground(track);
            LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (4 * mDp));
            trackLp.topMargin = (int) (8 * mDp);
            mBarTrack.setLayoutParams(trackLp);
            mFill = new View(ctx);
            GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF9C7BD8, 0xFF7A5BC0});
            fill.setCornerRadius(2 * mDp);
            mFill.setBackground(fill);
            mBarTrack.addView(mFill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            mBarTrack.setVisibility(mType == TYPE_PROGRESS ? VISIBLE : GONE);
            addView(mBarTrack);

            if (actionLabel != null && action != null && mType == TYPE_ERROR) {
                mAction = new TextView(ctx);
                mAction.setText(actionLabel);
                mAction.setTextColor(0xFFC4B5FD);
                mAction.setTextSize(11f);
                mAction.setTypeface(Typeface.DEFAULT_BOLD);
                mAction.setIncludeFontPadding(false);
                mAction.setPadding((int) (10 * mDp), (int) (5 * mDp), (int) (10 * mDp), (int) (5 * mDp));
                mAction.setBackground(cardBg(0x00000000, 0x33C4B5FD));
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alp.topMargin = (int) (8 * mDp);
                addView(mAction, alp);
                mAction.setOnClickListener(v -> {
                    UiMotion.springScale(v, 0.94f);
                    removeCallbacks(mDismiss);
                    mDismiss.run();
                    try { action.run(); } catch (Throwable ignored) { }
                });
            } else mAction = null;
        }

        void refresh(String title, String text) {
            mTitle.setText(title);
            mText.setText(text == null ? "" : text);
        }

        void setProgressUi(int percent) {
            mPercent.setText(percent + "%");
            Runnable apply = () -> {
                ViewGroup.LayoutParams lp = mFill.getLayoutParams();
                lp.width = (int) (mBarTrack.getWidth() * (percent / 100f));
                mFill.setLayoutParams(lp);
            };
            if (mBarTrack.getWidth() == 0) post(apply); else apply.run();
        }

        void becomeSuccess(String title, String text) {
            mType = TYPE_SUCCESS;
            mIcon.setImageResource(R.drawable.ic_check);
            mIcon.setColorFilter(0xFF8FEBBC);
            mTitle.setText(title);
            mTitle.setTextColor(0xFFDDF3E6);
            mText.setText(text == null ? "" : text);
            mPercent.setVisibility(GONE);
            mBarTrack.setVisibility(GONE);
            setBackground(cardBg(0xF0131A17, 0xFF2B4735));
            mIcon.setScaleX(0.3f);
            mIcon.setScaleY(0.3f);
            mIcon.animate().scaleX(1f).scaleY(1f)
                    .setDuration(380)
                    .setInterpolator(new OvershootInterpolator(1.6f))
                    .start();
        }

        void scheduleAutoDismiss() {
            scheduleAutoDismiss(mType == TYPE_ERROR ? ERROR_DISMISS_MS : AUTO_DISMISS_MS);
        }

        void scheduleAutoDismiss(long delay) {
            removeCallbacks(mDismiss);
            postDelayed(mDismiss, delay);
        }

        void depart() {
            animate().cancel();
            animate().alpha(0f).translationX(52 * mDp)
                    .setDuration(220)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        ViewGroup p = (ViewGroup) getParent();
                        if (p != null) p.removeView(CardView.this);
                    }).start();
        }

        private android.graphics.drawable.Drawable cardBg(int color, int stroke) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(16 * mDp);
            d.setColor(color);
            d.setStroke(Math.max(1, (int) mDp), stroke);
            return d;
        }

        static int iconFor(int type) {
            switch (type) {
                case TYPE_SUCCESS: return R.drawable.ic_check;
                case TYPE_ERROR: return R.drawable.ic_stop;
                case TYPE_PROGRESS: return R.drawable.ic_download;
                default: return R.drawable.ic_info;
            }
        }

        static int accentFor(int type) {
            switch (type) {
                case TYPE_SUCCESS: return 0xFF8FEBBC;
                case TYPE_ERROR: return 0xFFFF8A9B;
                case TYPE_PROGRESS: return 0xFFB39DF0;
                default: return 0xFFC9C4D8;
            }
        }
    }

    /** One history record. */
    public static class HistoryEntry {
        public final int type;
        public final String title, text;
        public final long at = System.currentTimeMillis();

        HistoryEntry(int type, String title, String text) {
            this.type = type;
            this.title = title;
            this.text = text;
        }
    }
}
