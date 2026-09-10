package net.kdt.pojavlaunch.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.performance.LauncherQuietPolicy;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * Three dots doing the anime.js "stagger" bounce:
 * <pre>anime({ targets: '.dot', translateY: [0,-8,0], scale: [1,1.25,1],
 *        delay: anime.stagger(120), loop: true, easing: 'easeInOutSine' })</pre>
 *
 * <p>Drawn with one Paint on one ValueAnimator — the cheapest possible loading
 * indicator, and it stops itself while detached or hidden. Silver on graphite,
 * no accent colour (the launcher's motion is the accent).
 */
public class LoadingDots extends View {

    private static final int DOTS = 3;
    private static final long PERIOD_MS = 900;
    private static final float STAGGER = 0.18f; // fraction of the period between dots

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator mAnimator;
    private float mT;
    private final float mDensity;

    public LoadingDots(Context context) { this(context, null); }

    public LoadingDots(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPaint.setColor(0xFFD2D6DE);
        setWillNotDraw(false);
    }

    public void setDotColor(int argb) { mPaint.setColor(argb); invalidate(); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (34 * mDensity), h = (int) (18 * mDensity);
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float r = 2.6f * mDensity;
        float gap = (w - DOTS * 2 * r) / (DOTS + 1);
        float base = h * 0.68f;
        float lift = 5.5f * mDensity;
        for (int i = 0; i < DOTS; i++) {
            // each dot runs the same 0→1→0 sine bump, offset by the stagger
            float p = mT - i * STAGGER;
            p = p - (float) Math.floor(p);           // wrap into [0,1)
            float bump = p < 0.5f ? (float) Math.sin(p * Math.PI * 2) : 0f; // bounce then rest
            if (bump < 0) bump = 0;
            float cx = gap + r + i * (gap + 2 * r);
            float cy = base - bump * lift;
            float rr = r * (1f + 0.25f * bump);
            mPaint.setAlpha(mAnimator == null ? 140 : (int) (120 + 135 * bump));
            canvas.drawCircle(cx, cy, rr, mPaint);
        }
    }

    public void start() {
        stop();
        if (!MotionSpeed.isEnabled() || !LauncherQuietPolicy.animationsAllowed()) { invalidate(); return; }
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(Math.max(450L, MotionSpeed.scale(PERIOD_MS)));
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> { mT = (float) a.getAnimatedValue(); invalidate(); });
        mAnimator.start();
    }

    public void stop() {
        if (mAnimator != null) { mAnimator.cancel(); mAnimator = null; }
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) start();
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
