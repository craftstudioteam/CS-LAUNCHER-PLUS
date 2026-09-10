package net.kdt.pojavlaunch.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.performance.LauncherQuietPolicy;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * Skeleton shimmer — a soft diagonal light band that sweeps left→right across
 * whatever sits beneath this view (lay it over a skeleton card / grid).
 *
 * <p>One {@link LinearGradient} shader translated by a {@link Matrix} per frame:
 * no bitmap allocation, no per-frame object churn, and it pauses itself while
 * detached or invisible so a loading screen left in the back stack costs nothing.
 * Duration goes through {@link MotionSpeed} like every other launcher animation.
 */
public class ShimmerOverlay extends View {

    private static final long SWEEP_MS = 1400;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final Path mClip = new Path();
    private final RectF mBounds = new RectF();
    private LinearGradient mShader;
    private ValueAnimator mAnimator;
    private float mProgress;
    private float mCornerRadius;
    private int mBand = 0x30FFFFFF;

    public ShimmerOverlay(Context context) { this(context, null); }

    public ShimmerOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        mCornerRadius = 20f * context.getResources().getDisplayMetrics().density;
    }

    /** Corner radius used to clip the sweep to the card shape underneath. */
    public void setCornerRadius(float px) { mCornerRadius = px; invalidate(); }

    /** ARGB colour of the highlight band (default: 19% white). */
    public void setBandColor(int argb) { mBand = argb; mShader = null; invalidate(); }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mShader = null;
        mBounds.set(0, 0, w, h);
        mClip.reset();
        mClip.addRoundRect(mBounds, mCornerRadius, mCornerRadius, Path.Direction.CW);
    }

    private void ensureShader() {
        if (mShader != null || getWidth() == 0) return;
        float w = getWidth();
        // Narrow band: transparent → highlight → transparent, tilted 20°.
        mShader = new LinearGradient(0, 0, w * 0.45f, w * 0.16f,
                new int[]{0x00FFFFFF, mBand, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        mPaint.setShader(mShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mAnimator == null || getWidth() == 0) return;
        ensureShader();
        float w = getWidth();
        // Travel from fully off-screen left to fully off-screen right.
        float dx = -w * 0.6f + mProgress * (w * 1.6f);
        mMatrix.setTranslate(dx, 0);
        mShader.setLocalMatrix(mMatrix);
        int save = canvas.save();
        canvas.clipPath(mClip);
        canvas.drawRect(mBounds, mPaint);
        canvas.restoreToCount(save);
    }

    /** Starts (or restarts) the sweep; no-op when animations are disabled. */
    public void start() {
        stop();
        if (!MotionSpeed.isEnabled() || !LauncherQuietPolicy.animationsAllowed()) return;
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(Math.max(600L, MotionSpeed.scale(SWEEP_MS)));
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setRepeatMode(ValueAnimator.RESTART);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> { mProgress = (float) a.getAnimatedValue(); invalidate(); });
        mAnimator.start();
    }

    public void stop() {
        if (mAnimator != null) { mAnimator.cancel(); mAnimator = null; }
        invalidate();
    }

    public boolean isRunning() { return mAnimator != null && mAnimator.isRunning(); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE && mAnimator == null) start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) { if (mAnimator == null && isAttachedToWindow()) start(); }
        else stop();
    }
}
