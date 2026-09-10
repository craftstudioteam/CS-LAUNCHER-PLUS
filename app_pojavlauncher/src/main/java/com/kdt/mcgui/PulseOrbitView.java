package com.kdt.mcgui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

/**
 * PulseOrbitView — the Phase-5 premium download animation system (Req-1).
 *
 * Replaces the old kinetic ring with a layered "pulse orbit" choreography:
 *
 *  PREPARING (indeterminate) → a comet arc with a soft glowing head orbits the
 *      dial at eased speed while a low-alpha echo follows behind. The core dot
 *      breathes (scale + alpha) so the dial never feels static.
 *
 *  DOWNLOADING (determinate) → the comet freezes its orbit, then the arc grows
 *      clockwise from 12 o'clock toward the current percentage with
 *      FastOutSlowIn easing; a comet head + trailing glow rides the arc tip.
 *
 *  COMPLETE → the arc snaps closed into a full ring, the ring eases out, a
 *      premium check-mark trims in (two-stroke path animation) while the core
 *      pops with an overshoot spring and a single glow flash fires.
 *
 * All motion is ValueAnimator-driven with proper view-attach lifecycle
 * (animators are cancelled in onDetachedFromWindow to avoid leaks/jank).
 */
public class PulseOrbitView extends View {

    // ── Palette (Phase-5 premium dark) ───────────────────────────────────────
    private static final int ACCENT       = 0xFFFFFFFF; // silver-white
    private static final int ACCENT_SOFT  = 0x59FFFFFF; // 35% glow layer
    private static final int ACCENT_TRACE = 0x26FFFFFF; // 15% echo trail
    private static final int TRACK        = 0x3326262E; // 20% neutral track
    private static final int CORE         = 0xFF2D2D35; // idle core
    private static final int COMPLETE     = 0xFF9FD6AC; // muted premium success

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEchoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCheckPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mDialBounds = new RectF();
    private final Path mCheckPath = new Path();
    private final Path mCheckDraw = new Path();
    private final PathMeasure mCheckMeasure = new PathMeasure();

    // ── Animated state ───────────────────────────────────────────────────────
    private float mOrbitAngle = -90f;     // comet position while indeterminate
    private float mArcSweep = 0f;         // determinate arc sweep
    private float mArcStart = -90f;       // determinate arc start (top)
    private float mCoreScale = 1f;        // breathing / completion pop
    private float mCoreGlow = 0f;         // extra glow burst 0..1
    private float mCheckTrim = 0f;        // 0..1 check-path draw-in
    private float mRingFade = 1f;         // arc visibility during completion
    private int   mProgress = 0;

    private boolean mIndeterminate = true;
    private boolean mCompleting = false;

    private ValueAnimator mOrbitAnimator;
    private ValueAnimator mBreatheAnimator;
    private ValueAnimator mProgressAnimator;
    private ValueAnimator mCompletionAnimator;

    public PulseOrbitView(Context context) { super(context); init(); }
    public PulseOrbitView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public PulseOrbitView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setColor(TRACK);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);

        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setColor(ACCENT);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);

        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setColor(ACCENT_SOFT);
        mGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        mEchoPaint.setStyle(Paint.Style.STROKE);
        mEchoPaint.setColor(ACCENT_TRACE);
        mEchoPaint.setStrokeCap(Paint.Cap.ROUND);

        mCorePaint.setStyle(Paint.Style.FILL);
        mCorePaint.setColor(CORE);

        mHeadPaint.setStyle(Paint.Style.FILL);
        mHeadPaint.setColor(Color.WHITE);

        mCheckPaint.setStyle(Paint.Style.STROKE);
        mCheckPaint.setColor(COMPLETE);
        mCheckPaint.setStrokeCap(Paint.Cap.ROUND);
        mCheckPaint.setStrokeJoin(Paint.Join.ROUND);

        startOrbitLoop();
        startBreathing();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Determinate progress, 0..100. Switches the dial out of indeterminate mode. */
    public void setProgress(int progress) {
        if (mCompleting) return;
        progress = Math.max(0, Math.min(100, progress));
        if (progress == mProgress && !mIndeterminate) return;
        if (mIndeterminate) transitionToDeterminate();
        mProgress = progress;
        float target = progress * 3.6f;
        if (mProgressAnimator != null) mProgressAnimator.cancel();
        mProgressAnimator = ValueAnimator.ofFloat(mArcSweep, target);
        mProgressAnimator.setDuration(280);
        mProgressAnimator.setInterpolator(new AccelerateDecelerateInterpolator()); // premium easing
        mProgressAnimator.addUpdateListener(a -> {
            mArcSweep = (Float) a.getAnimatedValue();
            invalidate();
        });
        mProgressAnimator.start();
    }

    /** Comet orbit mode (no known progress yet). */
    public void setIndeterminate(boolean indeterminate) {
        if (mCompleting) return;
        if (indeterminate && !mIndeterminate) {
            mIndeterminate = true;
            mArcSweep = 0f;
            startOrbitLoop();
            invalidate();
        } else if (!indeterminate && mIndeterminate) {
            transitionToDeterminate();
        }
    }

    /** Premium completion choreography: ring close-out + check trim-in + core pop. */
    public void showCompleted() {
        if (mCompleting) return;
        mCompleting = true;
        mIndeterminate = false;
        stopLoop(mOrbitAnimator);
        stopLoop(mBreatheAnimator);
        if (mProgressAnimator != null) mProgressAnimator.cancel();

        mCompletionAnimator = ValueAnimator.ofFloat(0f, 1f);
        mCompletionAnimator.setDuration(650);
        mCompletionAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mCompletionAnimator.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            // 0.0–0.35: arc closes to a full ring
            if (t < 0.35f) {
                float u = t / 0.35f;
                mArcSweep = mArcSweep + (360f - mArcSweep) * u * 0.3f;
                mRingFade = 1f;
                mCheckTrim = 0f;
            }
            // 0.3–0.7: check trims in, ring eases back to a hairline
            if (t >= 0.3f && t < 0.75f) {
                float u = (t - 0.3f) / 0.45f;
                mCheckTrim = Math.min(1f, u * 1.3f);
                mRingFade = 1f - u * 0.35f;
            }
            // 0.6–1.0: core pops + glow flash fires
            if (t >= 0.6f) {
                float u = (t - 0.6f) / 0.4f;
                mCoreGlow = (float) Math.sin(u * Math.PI); // rise & fall flash
                mCoreScale = 1f + Overshoot(u) * 0.28f;
            }
            invalidate();
        });
        mCompletionAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                mCheckTrim = 1f;
                mCoreGlow = 0f;
                mCoreScale = 1.12f;
                invalidate();
            }
        });
        mCompletionAnimator.start();
    }

    private static float Overshoot(float t) {
        // Small overshoot curve (approx OvershootInterpolator tension 2.4)
        t -= 1f;
        return t * t * ((2.4f + 1f) * t + 2.4f) + 1f;
    }

    private void transitionToDeterminate() {
        mIndeterminate = false;
        stopLoop(mOrbitAnimator);
        mArcStart = -90f;
        invalidate();
    }

    // ── Animation loops ──────────────────────────────────────────────────────

    private void startOrbitLoop() {
        stopLoop(mOrbitAnimator);
        mOrbitAnimator = ValueAnimator.ofFloat(0f, 360f);
        mOrbitAnimator.setDuration(1250);
        mOrbitAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mOrbitAnimator.setInterpolator(new LinearInterpolator());
        mOrbitAnimator.addUpdateListener(a -> {
            if (!mIndeterminate) return;
            mOrbitAngle = -90f + (Float) a.getAnimatedValue();
            invalidate();
        });
        mOrbitAnimator.start();
    }

    private void startBreathing() {
        stopLoop(mBreatheAnimator);
        mBreatheAnimator = ValueAnimator.ofFloat(0f, 1f);
        mBreatheAnimator.setDuration(1600);
        mBreatheAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mBreatheAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mBreatheAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mBreatheAnimator.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            mCoreScale = 1f + t * 0.18f; // subtle breathing
            invalidate();
        });
        mBreatheAnimator.start();
    }

    private static void stopLoop(ValueAnimator animator) {
        if (animator != null) animator.cancel();
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float stroke = Math.max(2.5f, w * 0.055f);
        float radius = (Math.min(w, h) - stroke * 2f) / 2f;
        mDialBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);

        mTrackPaint.setStrokeWidth(stroke);
        mArcPaint.setStrokeWidth(stroke);
        mGlowPaint.setStrokeWidth(stroke * 2.6f);
        mEchoPaint.setStrokeWidth(stroke);
        mHeadPaint.setStrokeWidth(stroke);

        // 1) Static hairline track
        canvas.drawArc(mDialBounds, 0, 360, false, mTrackPaint);

        // 2) Breathing core
        float coreRadius = radius * 0.30f * mCoreScale;
        if (mCoreGlow > 0f) {
            mHeadPaint.setColor(withAlpha(COMPLETE, (int) (90 * mCoreGlow)));
            canvas.drawCircle(cx, cy, coreRadius * (1.6f + mCoreGlow), mHeadPaint);
        }
        mCorePaint.setColor(mCompleting && mCheckTrim > 0.3f ? COMPLETE : CORE);
        canvas.drawCircle(cx, cy, coreRadius, mCorePaint);

        // 3) Arc system
        if (mCompleting) {
            // Closing ring in the muted success tone, easing back
            int alpha = (int) (0x90 * mRingFade);
            mArcPaint.setColor(withAlpha(COMPLETE, alpha));
            mGlowPaint.setColor(withAlpha(COMPLETE, (int) (0x30 * mRingFade)));
            canvas.drawArc(mDialBounds, -90f, mArcSweep, false, mGlowPaint);
            canvas.drawArc(mDialBounds, -90f, mArcSweep, false, mArcPaint);
        } else if (mIndeterminate) {
            // Comet: 100° main sweep + 46° echo behind
            mArcPaint.setColor(ACCENT);
            mGlowPaint.setColor(ACCENT_SOFT);
            mEchoPaint.setColor(ACCENT_TRACE);
            canvas.drawArc(mDialBounds, mOrbitAngle, 100f, false, mGlowPaint);
            canvas.drawArc(mDialBounds, mOrbitAngle, 100f, false, mArcPaint);
            canvas.drawArc(mDialBounds, mOrbitAngle - 52f, 46f, false, mEchoPaint);
            drawCometHead(canvas, cx, cy, radius, mOrbitAngle + 100f, ACCENT);
        } else {
            // Determinate: eased arc + comet head at the tip
            mArcPaint.setColor(ACCENT);
            mGlowPaint.setColor(ACCENT_SOFT);
            canvas.drawArc(mDialBounds, mArcStart, mArcSweep, false, mGlowPaint);
            canvas.drawArc(mDialBounds, mArcStart, mArcSweep, false, mArcPaint);
            if (mArcSweep > 2f) drawCometHead(canvas, cx, cy, radius, mArcStart + mArcSweep, ACCENT);
        }

        // 4) Completion check (trimmed two-stroke path)
        if (mCheckTrim > 0f) {
            buildCheckPath(cx, cy, radius);
            mCheckMeasure.setPath(mCheckPath, false);
            float len = mCheckMeasure.getLength();
            mCheckDraw.reset();
            mCheckMeasure.getSegment(0f, len * mCheckTrim, mCheckDraw, true);
            mCheckPaint.setStrokeWidth(stroke * 1.05f);
            canvas.drawPath(mCheckDraw, mCheckPaint);
        }
    }

    private void drawCometHead(Canvas canvas, float cx, float cy, float radius, float angleDeg, int color) {
        double rad = Math.toRadians(angleDeg);
        float hx = (float) (cx + radius * Math.cos(rad));
        float hy = (float) (cy + radius * Math.sin(rad));
        float headR = mArcPaint.getStrokeWidth() * 0.9f;
        mHeadPaint.setColor(withAlpha(color, 90));
        canvas.drawCircle(hx, hy, headR * 1.9f, mHeadPaint);      // halo
        mHeadPaint.setColor(Color.WHITE);
        canvas.drawCircle(hx, hy, headR, mHeadPaint);             // bright tip
    }

    private void buildCheckPath(float cx, float cy, float radius) {
        float s = radius * 0.52f;
        mCheckPath.reset();
        mCheckPath.moveTo(cx - s * 0.62f, cy + s * 0.02f);
        mCheckPath.lineTo(cx - s * 0.14f, cy + s * 0.48f);
        mCheckPath.lineTo(cx + s * 0.66f, cy - s * 0.42f);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color), Color.green(color), Color.blue(color));
    }

    // ── Lifecycle hygiene ────────────────────────────────────────────────────

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopLoop(mOrbitAnimator);
        stopLoop(mBreatheAnimator);
        if (mProgressAnimator != null) mProgressAnimator.cancel();
        if (mCompletionAnimator != null) mCompletionAnimator.cancel();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mIndeterminate && !mCompleting) {
            startOrbitLoop();
            startBreathing();
        }
    }
}
