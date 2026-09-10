package net.kdt.pojavlaunch.multirt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Aggregate install ring for the Runtime Forge deck (v3).
 *
 * A thin platinum arc that sweeps with the overall installation progress,
 * wrapped in a whisper-soft glow, decorated with a static tick bezel and a
 * luminous leading dot that rides the tip of the arc. Everything is drawn with
 * the compositor-friendly canvas pipeline and every value change is animated,
 * so the ring never jumps between progress reports.
 */
public class RuntimeRingView extends View {

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mArcRect = new RectF();
    private float mProgress = 0f;      // 0..1 displayed (animated)
    private float mTargetProgress = 0f;
    private ValueAnimator mAnimator;
    private float mDensity;

    public RuntimeRingView(Context context) { super(context); init(); }
    public RuntimeRingView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public RuntimeRingView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); init(); }

    private void init() {
        mDensity = getResources().getDisplayMetrics().density;

        mTrackPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeWidth(4.5f * mDensity);
        mTrackPaint.setColor(0xFF1E2128);

        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setStrokeWidth(5.5f * mDensity);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);
        mArcPaint.setColor(0xFFEDEFF4);

        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setStrokeWidth(13f * mDensity);
        mGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlowPaint.setColor(0x24E8EAF2);

        mTickPaint.setStyle(Paint.Style.STROKE);
        mTickPaint.setStrokeWidth(1f * mDensity);
        mTickPaint.setColor(0x1AFFFFFF);

        mDotPaint.setStyle(Paint.Style.FILL);
        mDotPaint.setColor(0xFFFFFFFF);

        mDotGlowPaint.setStyle(Paint.Style.FILL);
        mDotGlowPaint.setColor(0x33FFFFFF);
    }

    /** Animate the ring toward the new 0..1 progress. */
    /**
     * Re-tint the ring (arc, glow, leading dot) so it can match whatever
     * surface it is dropped into. Exposed here on purpose: the activity should
     * not have to reach into paint internals to restyle a view.
     */
    public void setAccentColor(int accent) {
        mArcPaint.setColor(accent);
        mGlowPaint.setColor((accent & 0x00FFFFFF) | 0x2A000000);
        mDotPaint.setColor(0xFFFFFFFF);
        mDotGlowPaint.setColor((accent & 0x00FFFFFF) | 0x66000000);
        mTrackPaint.setColor((accent & 0x00FFFFFF) | 0x1A000000);
        invalidate();
    }

    public void setProgress(float target) {
        target = Math.max(0f, Math.min(1f, target));
        if (Math.abs(target - mTargetProgress) < 0.001f) return;
        mTargetProgress = target;
        if (mAnimator != null) mAnimator.cancel();
        mAnimator = ValueAnimator.ofFloat(mProgress, target);
        mAnimator.setDuration(320);
        mAnimator.setInterpolator(new DecelerateInterpolator(1.3f));
        mAnimator.addUpdateListener(a -> {
            mProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        mAnimator.start();
    }

    public float getProgress() { return mTargetProgress; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f, cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 9f * mDensity;
        mArcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        mArcPaint.setShader(new SweepGradient(cx, cy,
                new int[]{0xFF9AA0AE, 0xFFFFFFFF, 0xFFD5D9E2, 0xFF9AA0AE},
                new float[]{0f, 0.35f, 0.7f, 1f}));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - 9f * mDensity;

        // ── tick bezel (static, very faint) ──
        float tickOuter = radius - 9f * mDensity;
        float tickInner = radius - 13f * mDensity;
        for (int i = 0; i < 60; i++) {
            double a = Math.toRadians(i * 6 - 90);
            float sx = cx + (float) Math.cos(a) * tickInner;
            float sy = cy + (float) Math.sin(a) * tickInner;
            float ex = cx + (float) Math.cos(a) * tickOuter;
            float ey = cy + (float) Math.sin(a) * tickOuter;
            canvas.drawLine(sx, sy, ex, ey, mTickPaint);
        }

        // ── track ──
        canvas.drawCircle(cx, cy, radius, mTrackPaint);

        if (mProgress > 0.0005f) {
            float sweep = 360f * mProgress;
            // ── glow + platinum arc ──
            canvas.drawArc(mArcRect, -90f, sweep, false, mGlowPaint);
            canvas.drawArc(mArcRect, -90f, sweep, false, mArcPaint);

            // ── leading dot riding the arc tip ──
            double a = Math.toRadians(sweep - 90);
            float dx = cx + (float) Math.cos(a) * radius;
            float dy = cy + (float) Math.sin(a) * radius;
            canvas.drawCircle(dx, dy, 7.5f * mDensity, mDotGlowPaint);
            canvas.drawCircle(dx, dy, 3.1f * mDensity, mDotPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) mAnimator.cancel();
        super.onDetachedFromWindow();
    }
}
