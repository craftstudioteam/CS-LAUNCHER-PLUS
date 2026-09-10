package net.kdt.pojavlaunch.tutorial;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

/**
 * Profile HOLD → DRAG → MOVE-TO-FRONT coach, rebuilt around the REAL carousel.
 *
 * <p>Unlike the previous fake-overlay demo, the "Steve" card here is a real item
 * inside the real {@code InstanceLibraryAdapter}, rendered by the real card
 * binder and positioned by the real {@code LinearLayoutManager} — it IS a real
 * instance card. This overlay only draws the finger, the HOLD chip and the
 * destination glow, and choreographs the real card's own view.
 *
 * <p>Demo choreography (explicitly teaching, not hinting):
 * <ol>
 *   <li> finger approaches the real Steve card.</li>
 *   <li> <b>PRESS</b> — finger compresses (250&nbsp;ms).</li>
 *   <li> <b>HOLD</b> — deliberate 850&nbsp;ms pause: finger stays compressed, the
 *        real card lifts (+6% scale, raised elevation), a "HOLD…" chip appears;
 *        this pause is what separates a TAP from a PRESS-AND-HOLD.</li>
 *   <li> <b>DRAG</b> — ONE shared ValueAnimator drives the finger AND the card's
 *        translationX in perfect lockstep: the card visibly follows the finger,
 *        continuously, all the way to the front slot.</li>
 *   <li> the destination slot glows/pulses as the card approaches it.</li>
 *   <li> <b>RELEASE</b> — finger lifts away; card settles into the front slot
 *        with a soft overshoot snap.</li>
 * </ol>
 *
 * <p>Practice phase: dummies leave (unless needed for a physically possible
 * reorder) and completion comes exclusively from the real ItemTouchHelper
 * chain — hold (drag state) → real reorder (from≠to) → release. No taps, no
 * timers, no observer guesswork.
 */
public final class ProfileDragDemoView extends FrameLayout {

    private static final int   FINGER_COLOR     = 0xFF8B5CF6;
    private static final int   FINGER_HIGHLIGHT = 0xFFA78BFA;
    private static final int   FINGER_SHADOW    = 0x446B3FC6;
    private static final int   FINGER_BORDER    = 0xFFC4B5FD;
    private static final float FINGER_SIZE_DP   = 46f;

    private static final int   GLOW_COLOR  = 0x3D8B5CF6;
    private static final int   GLOW_BORDER = 0xCC9F7BFF;

    private static final long  T_APPROACH = 420;
    private static final long  T_PRESS    = 260;
    private static final long  T_HOLD     = 850;   // the "this is NOT a tap" beat
    private static final long  T_DRAG     = 1050;
    private static final long  T_RELEASE  = 320;
    private static final long  T_SETTLE   = 300;
    private static final long  T_PERFECT  = 750;

    private final float mDensity;
    private final int mFingerPx;

    @Nullable private DragTutorialHost mHost;
    @Nullable private View mFinger;
    @Nullable private View mDestGlow;
    @Nullable private TextView mChip;

    @Nullable private android.animation.Animator mRun;
    @Nullable private Runnable mRetry;
    private int mRetries;

    private boolean mUserPhase;
    private boolean mCompleted;
    private boolean mDemoStarted;
    @Nullable private RectF mLastLibRect;
    @Nullable private RecyclerView mRealLibrary;

    public interface CompletionListener { void onDragCompleted(); }
    @Nullable private CompletionListener mCompletionListener;

    public interface PhaseListener {
        void onDemoStarted();
        void onUserPhaseStarted();
        void onSuccess();
    }
    @Nullable private PhaseListener mPhaseListener;

    public ProfileDragDemoView(@NonNull Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mFingerPx = dp(FINGER_SIZE_DP);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setCompletionListener(@Nullable CompletionListener l) { mCompletionListener = l; }
    public void setPhaseListener(@Nullable PhaseListener l) { mPhaseListener = l; }

    // ── Phase 1: scripted demo on the real card ─────────────────────────────

    public void startDemo(@Nullable View libraryView) {
        mRealLibrary = libraryView instanceof RecyclerView ? (RecyclerView) libraryView : null;
        mUserPhase = false;
        mCompleted = false;
        removeAllViews();

        mHost = DragTutorialHost.Registry.current();
        if (mHost == null || !mHost.beginDragDemo()) {
            // Carousel unavailable (never happens on Home, but never trap the
            // user): end the task gracefully instead of soft-locking.
            postDelayed(() -> { if (!mCleanedUp && mCompletionListener != null) mCompletionListener.onDragCompleted(); }, 400);
            return;
        }
        mRetries = 0;
        awaitDemoCardThenChoreograph();
    }

    /** Card rect is only valid after the insert + scroll + layout pass. */
    private void awaitDemoCardThenChoreograph() {
        DragTutorialHost host = mHost;
        if (host == null || mUserPhase || mCompleted) return;
        View card = host.getDemoCardView();
        Rect cardRect = host.getDemoCardScreenRect();
        Rect frontRect = host.getFrontSlotScreenRect();
        if (card != null && cardRect != null && frontRect != null
                && cardRect.width() > 0 && frontRect.width() > 0) {
            choreograph(card, cardRect, frontRect);
            return;
        }
        if (mRetries++ < 12) {
            mRetry = this::awaitDemoCardThenChoreograph;
            postDelayed(mRetry, 110);
        } else {
            // Could not measure in time: do not strand the user.
            if (!mCleanedUp && mCompletionListener != null) mCompletionListener.onDragCompleted();
        }
    }

    private void choreograph(@NonNull View card, @NonNull Rect cardScreen, @NonNull Rect frontScreen) {
        mDemoStarted = true;
        int[] overlayLoc = new int[2];
        getLocationOnScreen(overlayLoc);

        // Overlay-space geometry
        float cardCx = cardScreen.exactCenterX() - overlayLoc[0];
        float cardCy = cardScreen.exactCenterY() - overlayLoc[1];
        float frontCx = frontScreen.exactCenterX() - overlayLoc[0];
        float dragDx = frontCx - cardCx;
        // Fallback: if the front slot already IS the demo slot (0 real profiles
        // edge), drag toward the carousel's left edge instead.
        if (Math.abs(dragDx) < dp(24)) dragDx = -cardScreen.width();

        // ── Destination glow (slot 0) ──
        mDestGlow = createDestGlow();
        int gw = frontScreen.width() + dp(8), gh = frontScreen.height() + dp(8);
        addView(mDestGlow, new LayoutParams(gw, gh));
        mDestGlow.setTranslationX(frontScreen.left - overlayLoc[0] - dp(4));
        mDestGlow.setTranslationY(frontScreen.top - overlayLoc[1] - dp(4));
        mDestGlow.setAlpha(0f);

        // ── Stage chip ("PRESS & HOLD" → "HOLD…" → "DRAG" → "RELEASE") ──
        mChip = createChip();
        addView(mChip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        mChip.setAlpha(0f);
        positionChip(cardCx, cardScreen.top - overlayLoc[1]);

        // ── Finger ──
        mFinger = createFinger();
        addView(mFinger, new LayoutParams(mFingerPx, mFingerPx));
        final float fingerStartX = cardCx - mFingerPx / 2f;
        final float fingerStartY = cardScreen.top - overlayLoc[1] - mFingerPx - dp(14);
        final float fingerTouchY = cardCy - mFingerPx * 0.9f; // fingertip lands mid-card
        mFinger.setTranslationX(fingerStartX);
        mFinger.setTranslationY(fingerStartY);
        mFinger.setAlpha(0f);
        setFingerPivot();

        if (mPhaseListener != null) mPhaseListener.onDemoStarted();

        // 1) APPROACH — finger fades in and descends onto the card
        ObjectAnimator aIn = ObjectAnimator.ofFloat(mFinger, "alpha", 0f, 1f);
        ObjectAnimator aDown = ObjectAnimator.ofFloat(mFinger, "translationY", fingerStartY, fingerTouchY);
        aDown.setInterpolator(new DecelerateInterpolator());
        AnimatorSet pApproach = new AnimatorSet();
        pApproach.setDuration(T_APPROACH);
        pApproach.playTogether(aIn, aDown);

        // 2) PRESS — finger compresses on the card
        ObjectAnimator pFx = ObjectAnimator.ofFloat(mFinger, "scaleX", 1f, 0.84f);
        ObjectAnimator pFy = ObjectAnimator.ofFloat(mFinger, "scaleY", 1f, 0.84f);
        ObjectAnimator pFty = ObjectAnimator.ofFloat(mFinger, "translationY", fingerTouchY, fingerTouchY + dp(4));
        AnimatorSet pPress = new AnimatorSet();
        pPress.setDuration(T_PRESS);
        pPress.playTogether(pFx, pFy, pFty);
        final float fingerPressedY = fingerTouchY + dp(4);

        // 3) HOLD — deliberate beat: card lifts, glow wakes, chip narrates
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight() / 2f);
        ObjectAnimator hCardSx = ObjectAnimator.ofFloat(card, "scaleX", 1f, 1.06f);
        ObjectAnimator hCardSy = ObjectAnimator.ofFloat(card, "scaleY", 1f, 1.06f);
        ObjectAnimator hCardEl = ObjectAnimator.ofFloat(card, "elevation", 0f, dp(10));
        ObjectAnimator hGlow = ObjectAnimator.ofFloat(mDestGlow, "alpha", 0f, 1f);
        AnimatorSet pHold = new AnimatorSet();
        pHold.setDuration(T_HOLD);
        pHold.setInterpolator(new AccelerateDecelerateInterpolator());
        pHold.playTogether(hCardSx, hCardSy, hCardEl, hGlow);

        // 4) DRAG — one clock drives finger + card in perfect lockstep.
        ValueAnimator pDrag = ValueAnimator.ofFloat(0f, 1f);
        pDrag.setDuration(T_DRAG);
        pDrag.setInterpolator(new AccelerateDecelerateInterpolator());
        final float fDragDx = dragDx;
        final float fFingerStartX = fingerStartX;
        pDrag.addUpdateListener(anim -> {
            float t = (float) anim.getAnimatedValue();
            float dx = fDragDx * t;
            card.setTranslationX(dx);
            mFinger.setTranslationX(fFingerStartX + dx);
            // glow breathes as the card closes in
            if (mDestGlow != null) mDestGlow.setAlpha(0.55f + 0.45f * t);
        });

        // 5) RELEASE — finger rises and fades off the card
        ObjectAnimator rFx = ObjectAnimator.ofFloat(mFinger, "scaleX", 0.84f, 1f);
        ObjectAnimator rFy = ObjectAnimator.ofFloat(mFinger, "scaleY", 0.84f, 1f);
        ObjectAnimator rFty = ObjectAnimator.ofFloat(mFinger, "translationY", fingerPressedY, fingerPressedY - dp(10));
        ObjectAnimator rFade = ObjectAnimator.ofFloat(mFinger, "alpha", 1f, 0f);
        AnimatorSet pRelease = new AnimatorSet();
        pRelease.setDuration(T_RELEASE);
        pRelease.playTogether(rFx, rFy, rFty, rFade);

        // 6) SETTLE — card drops into the front slot with a soft snap
        ObjectAnimator sSx = ObjectAnimator.ofFloat(card, "scaleX", 1.06f, 1f);
        ObjectAnimator sSy = ObjectAnimator.ofFloat(card, "scaleY", 1.06f, 1f);
        ObjectAnimator sEl = ObjectAnimator.ofFloat(card, "elevation", dp(10), 0f);
        AnimatorSet pSettle = new AnimatorSet();
        pSettle.setDuration(T_SETTLE);
        pSettle.setInterpolator(new OvershootInterpolator(1.4f));
        pSettle.playTogether(sSx, sSy, sEl);

        AnimatorSet run = new AnimatorSet();
        run.playSequentially(pApproach, pPress, pHold, pDrag, pRelease, pSettle);
        run.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;
            @Override public void onAnimationCancel(Animator animation) { mCancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (mCancelled) return;
                onDemoCycleComplete();
            }
        });

        // Chip narration in lockstep with the phases
        runChipNarration();

        mRun = run;
        run.start();
    }

    private void onDemoCycleComplete() {
        // Hold the "card at the front" tableau briefly, then hand over to the
        // real-practice phase.
        postDelayed(this::enterUserPhase, T_PERFECT);
    }

    // ── Phase 2: real user practice ─────────────────────────────────────────

    private void enterUserPhase() {
        if (mCompleted || mUserPhase || mCleanedUp) return;
        mUserPhase = true;
        cancelRunQuietly();

        DragTutorialHost host = mHost;
        boolean keepSteve = false;
        if (host != null) {
            // A real reorder needs ≥2 draggable cards. With 0–1 real profiles
            // Steve stays (still 100% transient); with ≥2 real profiles the
            // dummies leave and the practise happens on real cards.
            keepSteve = host.getRealProfileCount() < 2;
            host.removeDemoCardsAfterDemo(keepSteve);
        }

        if (mFinger != null) mFinger.animate().alpha(0f).setDuration(180).start();
        if (mDestGlow != null) mDestGlow.animate().alpha(0f).setDuration(180).start();
        if (mChip != null) mChip.animate().alpha(0f).setDuration(150).start();

        if (host != null) {
            host.setDemoPracticeListener(new DragTutorialHost.DemoPracticeListener() {
                @Override public void onPracticeDragStart() { /* chime hook */ }
                @Override public void onPracticeDragMoved() { /* chime hook */ }
                @Override public void onPracticeComplete() { triggerSuccess(); }
            });
        }

        if (mPhaseListener != null) mPhaseListener.onUserPhaseStarted();
    }

    public boolean isUserPhase() { return mUserPhase; }
    public boolean isCompleted() { return mCompleted; }

    private void triggerSuccess() {
        if (mCompleted || mCleanedUp) return;
        mCompleted = true;
        DragTutorialHost host = mHost;
        if (host != null) host.setDemoPracticeListener(null);
        post(() -> {
            if (mPhaseListener != null) mPhaseListener.onSuccess();
            postDelayed(() -> {
                if (!mCleanedUp && mCompletionListener != null) mCompletionListener.onDragCompleted();
            }, 1100);
        });
    }

    private boolean mCleanedUp = false;

    public void cleanup() {
        mCleanedUp = true;
        cancelRunQuietly();
        if (mRetry != null) { removeCallbacks(mRetry); mRetry = null; }
        DragTutorialHost host = mHost;
        mHost = null;
        if (host != null) {
            // Tears down every demo card, clears listeners, restores the UI.
            host.endDragTutorial();
        }
        removeAllViews();
    }

    private void cancelRunQuietly() {
        if (mRun != null) { mRun.cancel(); mRun = null; }
    }

    // ── Overlay visual elements ─────────────────────────────────────────────

    private void setFingerPivot() {
        if (mFinger == null) return;
        mFinger.setPivotX(mFingerPx / 2f);
        mFinger.setPivotY(mFingerPx); // pivot at fingertip so compression reads as pressure
    }

    private void positionChip(float centerX, float cardTopOverlay) {
        if (mChip == null) return;
        mChip.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        float w = Math.max(mChip.getMeasuredWidth(), dp(96));
        float x = centerX - w / 2f;
        float y = cardTopOverlay - dp(34);
        if (y < dp(4)) y = cardTopOverlay + dp(56); // no room above → tuck under the card
        mChip.setTranslationX(x);
        mChip.setTranslationY(y);
    }

    private void runChipNarration() {
        if (mChip == null) return;
        setChipText("PRESS & HOLD", true);
        long t = T_APPROACH + T_PRESS;
        postDelayed(() -> setChipText("HOLD…", false), t);
        postDelayed(() -> setChipText("DRAG", false), t + T_HOLD);
        postDelayed(() -> setChipText("RELEASE", false), t + T_HOLD + T_DRAG);
        postDelayed(() -> { if (mChip != null) mChip.animate().alpha(0f).setDuration(220).start(); },
                t + T_HOLD + T_DRAG + T_RELEASE + T_SETTLE);
    }

    private void setChipText(String text, boolean fadeIn) {
        if (mChip == null) return;
        mChip.setText(text);
        if (fadeIn) {
            mChip.animate().alpha(1f).setDuration(200).start();
        }
    }

    private TextView createChip() {
        TextView chip = new TextView(getContext());
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(11f);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(5), dp(12), dp(5));
        chip.setBackgroundColor(0xE6140F24);
        try {
            Typeface mc = ResourcesCompat.getFont(getContext(), R.font.minecraftia);
            chip.setTypeface(mc);
        } catch (Throwable ignored) {
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        }
        chip.setMinWidth(dp(96));
        chip.setElevation(dp(6));
        // rounded pill look via outline
        chip.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        chip.setClipToOutline(true);
        return chip;
    }

    @NonNull
    private View createFinger() {
        return new View(getContext()) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();
            {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setColor(FINGER_BORDER);
                stroke.setStrokeWidth(mDensity * 1.5f);
            }
            @Override protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;
                path.reset();
                float tipY = h - 2f;
                float baseLeft = w * 0.16f;
                float baseRight = w * 0.84f;
                float baseTop = 2f;
                float radius = w * 0.28f;
                path.moveTo(w / 2f, tipY);
                path.quadTo(baseRight, tipY - h * 0.32f, baseRight, baseTop + radius);
                path.quadTo(baseRight, baseTop, w * 0.65f, baseTop);
                path.lineTo(w * 0.35f, baseTop);
                path.quadTo(baseLeft, baseTop, baseLeft, baseTop + radius);
                path.quadTo(baseLeft, tipY - h * 0.32f, w / 2f, tipY);
                path.close();

                p.setColor(FINGER_SHADOW);
                canvas.save();
                canvas.translate(0, h * 0.05f);
                canvas.drawPath(path, p);
                canvas.restore();

                p.setColor(FINGER_COLOR);
                canvas.drawPath(path, p);
                canvas.drawPath(path, stroke);

                p.setColor(FINGER_HIGHLIGHT);
                canvas.drawCircle(w / 2f, h * 0.72f, Math.max(2f, w * 0.11f), p);
            }
        };
    }

    @NonNull
    private View createDestGlow() {
        return new View(getContext()) {
            final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RectF rect = new RectF();
            {
                fillPaint.setColor(GLOW_COLOR);
                fillPaint.setStyle(Paint.Style.FILL);
                borderPaint.setColor(GLOW_BORDER);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(Math.max(2, 2 * mDensity));
            }
            @Override protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;
                float r = 14 * mDensity;
                rect.set(2, 2, w - 2, h - 2);
                canvas.drawRoundRect(rect, r, r, fillPaint);
                canvas.drawRoundRect(rect, r, r, borderPaint);
            }
        };
    }

    /** Rect of the real carousel in overlay space (for the practice spotlight). */
    @Nullable
    public RectF getLibraryRect() {
        if (mRealLibrary == null) return null;
        int[] overlayLoc = new int[2];
        getLocationOnScreen(overlayLoc);
        int[] libLoc = new int[2];
        mRealLibrary.getLocationOnScreen(libLoc);
        float pad = dp(10);
        RectF r = new RectF(
                libLoc[0] - overlayLoc[0] - pad,
                libLoc[1] - overlayLoc[1] - pad,
                libLoc[0] - overlayLoc[0] + mRealLibrary.getWidth() + pad,
                libLoc[1] - overlayLoc[1] + mRealLibrary.getHeight() + pad);
        mLastLibRect = r;
        return r;
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * mDensity));
    }
}
