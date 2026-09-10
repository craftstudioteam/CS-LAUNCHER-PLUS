package net.kdt.pojavlaunch.prefs;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.utils.animation.MotionCurves;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

public class CustomToggleView extends View {

    private boolean mChecked = false;
    private float mAnimProgress = 0f;
    /** Squash-and-stretch envelope while the thumb is in flight (0 → 1 → 0). */
    private float mFlightStretch = 0f;
    private android.graphics.LinearGradient mOnTrackShader;
    private float mOnTrackShaderWidth;
    private ValueAnimator mAnimator;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mTrackRect = new RectF();
    private final RectF mThumbRect = new RectF();
    private OnCheckedChangeListener mListener;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(CustomToggleView view, boolean isChecked);
    }

    public CustomToggleView(Context context) {
        super(context);
        init();
    }

    public CustomToggleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CustomToggleView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        mAnimProgress = mChecked ? 1f : 0f;
    }

    public void setChecked(boolean checked) {
        setChecked(checked, true);
    }

    public void setChecked(boolean checked, boolean animate) {
        if (mChecked == checked) return;
        mChecked = checked;

        if (mAnimator != null) {
            mAnimator.cancel();
        }

        if (animate && MotionSpeed.isEnabled()) {
            // Originkit-style elastic travel: the thumb launches with squash &
            // stretch, rides a damped spring and squishes against the far wall
            // instead of gliding to a boring stop.
            mAnimator = ValueAnimator.ofFloat(mAnimProgress, checked ? 1f : 0f);
            mAnimator.setDuration(MotionSpeed.scale(430L));
            mAnimator.setInterpolator(MotionCurves.spring(0.42f, 7f));
            mAnimator.addUpdateListener(animation -> {
                mAnimProgress = (float) animation.getAnimatedValue();
                // 0 → 1 → 0 envelope across the flight: stretch mid-travel.
                mFlightStretch = (float) Math.sin(
                        Math.PI * animation.getAnimatedFraction()) * 0.24f;
                invalidate();
            });
            mAnimator.start();
            // …and the whole pill does a tiny jelly pop to sell the weight of the flip.
            animate().cancel();
            setScaleX(0.9f);
            setScaleY(0.9f);
            animate().scaleX(1f).scaleY(1f)
                    .setDuration(MotionSpeed.scale(480L))
                    .setInterpolator(MotionCurves.JELLY)
                    .start();
        } else {
            mAnimProgress = checked ? 1f : 0f;
            mFlightStretch = 0f;
            invalidate();
        }

        if (mListener != null) {
            mListener.onCheckedChanged(this, mChecked);
        }
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
    }

    @Override
    public boolean performClick() {
        toggle();
        return super.performClick();
    }

    public void toggle() {
        setChecked(!mChecked, true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        float density = getResources().getDisplayMetrics().density;
        int defaultWidth = Math.round(52 * density);
        int defaultHeight = Math.round(28 * density);

        int width = (widthMode == MeasureSpec.EXACTLY) ? widthSize : defaultWidth;
        int height = (heightMode == MeasureSpec.EXACTLY) ? heightSize : defaultHeight;

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        // ── Graphite Toggle: Charcoal Track → Silver/Graphite Gradient ──
        mTrackRect.set(0, 0, w, h);
        float radius = h / 2f;
        float density = getResources().getDisplayMetrics().density;

        // Travel may overshoot past [0,1] thanks to the spring — clamp it for
        // colour/fill maths, and turn the excess into the thumb's wall-squish.
        float p = mAnimProgress;
        float cp = Math.max(0f, Math.min(1f, p));
        float overshoot = p - cp;

        // Off track: deep neutral graphite
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF141519);
        canvas.drawRoundRect(mTrackRect, radius, radius, mPaint);

        // On track: horizontal light-silver to steel-grey gradient beam fading in
        if (cp > 0.001f) {
            if (mOnTrackShader == null || mOnTrackShaderWidth != w) {
                mOnTrackShader = new android.graphics.LinearGradient(0, 0, w, 0,
                        0xFFE2E5EB, 0xFF8A909C, android.graphics.Shader.TileMode.CLAMP);
                mOnTrackShaderWidth = w;
            }
            mPaint.setShader(mOnTrackShader);
            mPaint.setAlpha((int) (255 * cp));
            canvas.drawRoundRect(mTrackRect, radius, radius, mPaint);
            mPaint.setShader(null);
            mPaint.setAlpha(255);
        }

        // Rim light on the track (soft silver)
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1f, density));
        mPaint.setColor(blendColors(0x338A909C, 0x99E2E5EB, cp));
        canvas.drawRoundRect(mTrackRect, radius, radius, mPaint);
        mPaint.setStyle(Paint.Style.FILL);

        // Thumb — an ellipse: stretched while flying, squished when the spring
        // overshoot presses it against the track wall. Never leaves the track.
        float padding = 3.5f * density;
        float thumbRadius = radius - padding;
        float minX = radius;
        float maxX = w - radius;
        float squish = Math.min(0.20f, Math.abs(overshoot) * 1.5f);
        float rx = thumbRadius * (1f + mFlightStretch) * (1f - squish);
        float ry = thumbRadius * (1f - mFlightStretch * 0.6f) * (1f + squish * 1.1f);
        ry = Math.min(ry, thumbRadius * 1.12f);
        float thumbX = minX + (maxX - minX) * cp;
        float thumbY = h / 2f;
        mThumbRect.set(thumbX - rx, thumbY - ry, thumbX + rx, thumbY + ry);

        // Soft silver halo when enabled
        if (cp > 0.05f) {
            mPaint.setColor(blendColors(0x00E2E5EB, 0x4DE2E5EB, cp));
            canvas.drawCircle(thumbX, thumbY, ry + padding * 0.8f, mPaint);
        }

        // Graphite thumb body (dim grey knob -> bright silver when on)
        mPaint.setColor(blendColors(0xFF7A808C, 0xFFFFFFFF, cp));
        canvas.drawOval(mThumbRect, mPaint);

        // Thumb rim
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1f, density * 0.8f));
        mPaint.setColor(blendColors(0x4D8A909C, 0xCCFFFFFF, cp));
        float inset = mPaint.getStrokeWidth() / 2f;
        canvas.drawOval(new RectF(mThumbRect.left + inset, mThumbRect.top + inset,
                mThumbRect.right - inset, mThumbRect.bottom - inset), mPaint);
        mPaint.setStyle(Paint.Style.FILL);

        // Pupil in the center of the thumb
        mPaint.setColor(blendColors(0xFF1B1D23, 0xFF0A0B0E, cp));
        canvas.drawCircle(thumbX, thumbY, Math.min(rx, ry) * 0.42f, mPaint);
    }

    private int blendColors(int color1, int color2, float ratio) {
        int a = (int) (((color1 >> 24) & 0xff) * (1 - ratio) + ((color2 >> 24) & 0xff) * ratio);
        int r = (int) (((color1 >> 16) & 0xff) * (1 - ratio) + ((color2 >> 16) & 0xff) * ratio);
        int g = (int) (((color1 >> 8) & 0xff) * (1 - ratio) + ((color2 >> 8) & 0xff) * ratio);
        int b = (int) (((color1 & 0xff) * (1 - ratio) + (color2 & 0xff) * ratio));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
