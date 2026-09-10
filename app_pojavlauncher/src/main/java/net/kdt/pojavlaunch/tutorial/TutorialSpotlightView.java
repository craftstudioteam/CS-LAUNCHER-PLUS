package net.kdt.pojavlaunch.tutorial;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * Full-screen dim layer with a hardware-accelerated "spotlight" cutout
 * punched cleanly over the highlighted target element.
 *
 * <p>Features:
 * <ul>
 *   <li>Obsidian dim (~40% black) keeping the Home Screen fully visible.</li>
 *   <li>Crisp Minecraft-purple accent border.</li>
 *   <li>Subtle breathing pulse glow surrounding the target.</li>
 * </ul>
 */
public final class TutorialSpotlightView extends View {

    private static final int DIM_COLOR     = 0x6608060C;
    private static final int BORDER_COLOR  = 0xDD9B6BFF;
    private static final int GLOW_COLOR    = 0x338B5CF6;

    private final Paint mDimPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClearPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private RectF mHole;
    private final float mCornerRadius;
    private float mPulseFraction = 0f;
    private ValueAnimator mPulseAnimator;

    public TutorialSpotlightView(Context context) {
        super(context);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        float density = context.getResources().getDisplayMetrics().density;
        mCornerRadius = 14f * density;

        mDimPaint.setStyle(Paint.Style.FILL);
        mDimPaint.setColor(DIM_COLOR);

        mClearPaint.setStyle(Paint.Style.FILL);
        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setColor(BORDER_COLOR);
        mBorderPaint.setStrokeWidth(Math.max(2f, 2.5f * density));

        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setColor(GLOW_COLOR);
        mGlowPaint.setStrokeWidth(Math.max(4f, 6f * density));

        startPulse();
    }

    private void startPulse() {
        mPulseAnimator = ValueAnimator.ofFloat(0f, 1f, 0f);
        mPulseAnimator.setDuration(1600);
        mPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mPulseAnimator.addUpdateListener(anim -> {
            mPulseFraction = (float) anim.getAnimatedValue();
            if (mHole != null) invalidate();
        });
        mPulseAnimator.start();
    }

    public void setHole(@Nullable RectF hole) {
        mHole = hole == null ? null : new RectF(hole);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);

        // Translucent dark backdrop
        canvas.drawRect(0, 0, getWidth(), getHeight(), mDimPaint);

        // Punch clear hole
        if (mHole != null) {
            canvas.drawRoundRect(mHole, mCornerRadius, mCornerRadius, mClearPaint);
        }

        canvas.restoreToCount(save);

        // Draw breathing glow + border
        if (mHole != null) {
            int glowAlpha = Math.round(0x22 + 0x33 * mPulseFraction);
            mGlowPaint.setAlpha(glowAlpha);
            canvas.drawRoundRect(mHole, mCornerRadius, mCornerRadius, mGlowPaint);
            canvas.drawRoundRect(mHole, mCornerRadius, mCornerRadius, mBorderPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
            mPulseAnimator = null;
        }
    }
}
