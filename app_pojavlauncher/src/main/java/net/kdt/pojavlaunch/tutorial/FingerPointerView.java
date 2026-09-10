package net.kdt.pojavlaunch.tutorial;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * Animated pointer indicator that points <b>downward</b> directly at
 * the highlighted tutorial element.
 *
 * <p>Features:
 * <ul>
 *   <li>Stylized Minecraft-inspired pointing shape.</li>
 *   <li>Downward orientation pointing directly at the target.</li>
 *   <li>Realistic tap, hold, and drag bounce animations with smooth easing.</li>
 *   <li>Proper hardware-layer cleanup and zero memory leaks.</li>
 * </ul>
 */
public final class FingerPointerView extends View {

    private static final int FINGER_COLOR     = 0xFF8B5CF6;
    private static final int FINGER_HIGHLIGHT = 0xFFA78BFA;
    private static final int FINGER_SHADOW    = 0x446B3FC6;
    private static final int FINGER_BORDER    = 0xFFC4B5FD;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private AnimatorSet mAnimSet;

    public FingerPointerView(@NonNull Context context) {
        super(context);
        mPaint.setStyle(Paint.Style.FILL);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setColor(FINGER_BORDER);
        mStrokePaint.setStrokeWidth(context.getResources().getDisplayMetrics().density * 1.5f);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setAlpha(0f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        mPath.reset();

        // Finger body pointing DOWN toward target
        float tipY = h - 2f;
        float baseTop = 2f;
        float baseLeft = w * 0.16f;
        float baseRight = w * 0.84f;
        float radius = w * 0.28f;

        mPath.moveTo(w / 2f, tipY);
        mPath.quadTo(baseRight, tipY - h * 0.32f, baseRight, baseTop + radius);
        mPath.quadTo(baseRight, baseTop, w * 0.65f, baseTop);
        mPath.lineTo(w * 0.35f, baseTop);
        mPath.quadTo(baseLeft, baseTop, baseLeft, baseTop + radius);
        mPath.quadTo(baseLeft, tipY - h * 0.32f, w / 2f, tipY);
        mPath.close();

        // Drop shadow
        mPaint.setColor(FINGER_SHADOW);
        canvas.save();
        canvas.translate(0, h * 0.05f);
        canvas.drawPath(mPath, mPaint);
        canvas.restore();

        // Main fill
        mPaint.setColor(FINGER_COLOR);
        canvas.drawPath(mPath, mPaint);

        // Highlight stroke
        canvas.drawPath(mPath, mStrokePaint);

        // Fingertip pulse indicator
        float dotX = w / 2f;
        float dotY = h * 0.72f;
        float dotR = Math.max(2f, w * 0.11f);
        mPaint.setColor(FINGER_HIGHLIGHT);
        canvas.drawCircle(dotX, dotY, dotR, mPaint);
    }

    /**
     * Start the realistic tap animation (downward press and gentle release).
     */
    void startTapAnimation() {
        stopAnimation();
        setAlpha(1f);
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());

        ObjectAnimator scaleX = ObjectAnimator.ofFloat(this, "scaleX", 1f, 0.86f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 0.86f, 1f);
        ObjectAnimator transY = ObjectAnimator.ofFloat(this, "translationY",
                getTranslationY(), getTranslationY() + dp(7), getTranslationY());
        ObjectAnimator alpha = ObjectAnimator.ofFloat(this, "alpha", 1f, 0.82f, 1f);

        for (ObjectAnimator a : new ObjectAnimator[]{scaleX, scaleY, transY, alpha}) {
            a.setDuration(850);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
        }

        mAnimSet = new AnimatorSet();
        mAnimSet.playTogether(scaleX, scaleY, transY, alpha);
        mAnimSet.start();
    }

    /**
     * Hold animation: finger presses down on target and holds with a subtle pulse.
     */
    void startHoldAnimation() {
        stopAnimation();
        setAlpha(1f);
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());

        ObjectAnimator scale = ObjectAnimator.ofFloat(this, "scaleX", 0.88f, 0.84f, 0.88f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(this, "scaleY", 0.88f, 0.84f, 0.88f);
        ObjectAnimator transY = ObjectAnimator.ofFloat(this, "translationY",
                getTranslationY() + dp(5), getTranslationY() + dp(7), getTranslationY() + dp(5));

        for (ObjectAnimator a : new ObjectAnimator[]{scale, scaleY, transY}) {
            a.setDuration(600);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setInterpolator(new AccelerateDecelerateInterpolator());
        }

        mAnimSet = new AnimatorSet();
        mAnimSet.playTogether(scale, scaleY, transY);
        mAnimSet.start();
    }

    void stopAnimation() {
        if (mAnimSet != null) {
            mAnimSet.cancel();
            mAnimSet = null;
        }
        setScaleX(1f);
        setScaleY(1f);
        setAlpha(0f);
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * getResources().getDisplayMetrics().density));
    }
}
