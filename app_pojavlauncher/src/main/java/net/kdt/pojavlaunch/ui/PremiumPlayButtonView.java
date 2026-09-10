package net.kdt.pojavlaunch.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;

/**
 * Premium launch button used by profile cards / FastClient home.
 *
 * Idle: platinum capsule + a slow diagonal sheen.
 * Launching: horizontal progress wave, violet edge glow, particle burst and a
 * subtle morph pulse — visually distinct from the normal "download" spinner.
 *
 * Perf notes:
 * - Fixed preallocated paints/path/particle pool; zero per-frame allocations.
 * - Animators only run while attached and visible.
 * - Automatically mirrors ProgressKeeper task state so failure/cancel paths
 *   never leave the button stuck in the launching morph.
 */
public class PremiumPlayButtonView extends FrameLayout implements TaskCountListener {

    private static final int PARTICLE_COUNT = 14;
    private static final long FAILSAFE_MS = 12000L;

    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mRoundPath = new Path();
    private final RectF mRect = new RectF();

    private LinearGradient mBaseGradient;
    private LinearGradient mSheenGradient;
    private LinearGradient mWaveGradient;

    private ValueAnimator mIdleAnimator;
    private ValueAnimator mLaunchAnimator;
    private float mIdleFraction = 0f;
    private float mLaunchFraction = 0f;
    private boolean mLaunching;
    private boolean mExplicitLaunch;
    private boolean mStopMode;
    private boolean mAnimationsAllowed = true;

    private final float[] mPX = new float[PARTICLE_COUNT];
    private final float[] mPY = new float[PARTICLE_COUNT];
    private final float[] mVX = new float[PARTICLE_COUNT];
    private final float[] mVY = new float[PARTICLE_COUNT];
    private final int[] mPLife = new int[PARTICLE_COUNT];

    private final Runnable mFailsafeReset = () -> {
        mExplicitLaunch = false;
        if (ProgressKeeper.getTaskCount() == 0) setLaunching(false);
    };

    /**
     * Launch phases → button beats. STARTING = one glow-burst pop, then settle.
     * FAILED/IDLE = abort cleanly (never stuck in the launch morph).
     */
    private final net.kdt.pojavlaunch.launch.LaunchTracker.PhaseListener mLaunchPhaseListener =
            phase -> post(() -> {
                if (phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.STARTING) {
                    completeLaunch();
                } else if (phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.FAILED
                        || phase == net.kdt.pojavlaunch.launch.LaunchTracker.Phase.IDLE) {
                    if (mExplicitLaunch) reset();
                }
            });

    public PremiumPlayButtonView(@NonNull Context context) {
        this(context, null);
    }

    public PremiumPlayButtonView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PremiumPlayButtonView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setStrokeWidth(dp(1.2f));
        mParticlePaint.setStyle(Paint.Style.FILL);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mRect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        mRoundPath.reset();
        mRoundPath.addRoundRect(mRect, h / 2f, h / 2f, Path.Direction.CW);

        mBaseGradient = new LinearGradient(0, 0, w, h,
                new int[]{0xFFFAFAFC, 0xFFFFFFFF, 0xFFBFC1CB},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
        mSheenGradient = new LinearGradient(0, 0, w * 0.32f, h,
                new int[]{0x00FFFFFF, 0x7FFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        mWaveGradient = new LinearGradient(0, 0, Math.max(1, w), 0,
                new int[]{0x00FFFFFF, 0x66FFFFFF, 0xCCD0D0D0, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.28f, 0.5f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        mBasePaint.setShader(mBaseGradient);
        mSheenPaint.setShader(mSheenGradient);
        mWavePaint.setShader(mWaveGradient);
    }

    /** Immediate premium launch morph. Call before dispatching LAUNCH_GAME. */
    public void beginLaunch() {
        mExplicitLaunch = true;
        setLaunching(true);
        spawnParticles();
        animate().cancel();
        animate().scaleX(0.965f).scaleY(0.90f).setDuration(80)
                .withEndAction(() -> animate().scaleX(1.035f).scaleY(1.06f)
                        .setDuration(180)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> animate().scaleX(1f).scaleY(1f)
                                .setDuration(120).start())
                        .start())
                .start();
        removeCallbacks(mFailsafeReset);
        postDelayed(() -> {
            if (mExplicitLaunch) setStopMode(true);
        }, 360);
        postDelayed(mFailsafeReset, FAILSAFE_MS);
    }

    /** Return to the idle platinum state. */
    public void reset() {
        mExplicitLaunch = false;
        setStopMode(false);
        setLaunching(false);
    }

    public boolean isStopMode() { return mStopMode; }

    /** Cancel pending verification/download work before the game-process handoff. */
    public void cancelPendingLaunch() {
        net.kdt.pojavlaunch.utils.DownloadControl.requestCancel(com.kdt.mcgui.ProgressLayout.DOWNLOAD_MINECRAFT);
        net.kdt.pojavlaunch.utils.DownloadControl.requestCancel(com.kdt.mcgui.ProgressLayout.UNPACK_RUNTIME);
        net.kdt.pojavlaunch.utils.DownloadControl.requestCancel(com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK);
        // User-cancelled, NOT a failure — idle() resets the phase without
        // firing the "Launch failed" notification.
        net.kdt.pojavlaunch.launch.LaunchTracker.idle();
        reset();
    }

    private void setStopMode(boolean stop) {
        if (mStopMode == stop) return;
        mStopMode = stop;
        if (getChildCount() > 0 && getChildAt(0) instanceof android.widget.ImageView) {
            android.widget.ImageView icon = (android.widget.ImageView) getChildAt(0);
            icon.setImageResource(stop ? net.kdt.pojavlaunch.R.drawable.ic_stop_square
                    : net.kdt.pojavlaunch.R.drawable.ic_play_arrow);
            icon.setColorFilter(stop ? 0xFFFFFFFF : 0xFF0E0E11);
        }
        invalidate();
    }

    /**
     * STARTING beat — the game is truly leaving: one last radial burst and a
     * bright overshoot pop, then the button sits back down. This is the
     * launch-only finale; downloads never trigger it.
     */
    public void completeLaunch() {
        mExplicitLaunch = false;
        spawnParticles();
        animate().cancel();
        animate().scaleX(1.06f).scaleY(1.09f).setDuration(140)
                .withEndAction(() -> animate().scaleX(1f).scaleY(1f).setDuration(170)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start())
                .start();
        // Keep the red STOP state visible until the game-process handoff.
    }

    public boolean isLaunching() {
        return mLaunching;
    }

    private void setLaunching(boolean launching) {
        if (mLaunching == launching) return;
        mLaunching = launching;
        if (launching) startLaunchAnimator();
        else stopLaunchAnimator();
        setSelected(launching);
        invalidate();
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {
        post(() -> {
            if (taskCount > 0) {
                setLaunching(true);
            } else {
                // Small settle so the wave completes instead of snapping off;
                // also completes click-launched runs once every launch task ends.
                postDelayed(() -> {
                    if (ProgressKeeper.getTaskCount() == 0) {
                        mExplicitLaunch = false;
                        setLaunching(false);
                    }
                }, 360);
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ProgressKeeper.addTaskCountListener(this, false);
        net.kdt.pojavlaunch.launch.LaunchTracker.addListener(mLaunchPhaseListener);
        onUpdateTaskCount(ProgressKeeper.getTaskCount());
        if (mAnimationsAllowed) startIdleAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mFailsafeReset);
        ProgressKeeper.removeTaskCountListener(this);
        net.kdt.pojavlaunch.launch.LaunchTracker.removeListener(mLaunchPhaseListener);
        stopIdleAnimator();
        stopLaunchAnimator();
        mLaunching = false;
        mExplicitLaunch = false;
        mStopMode = false;
        clearParticles();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mAnimationsAllowed = visibility == View.VISIBLE;
        if (!mAnimationsAllowed) {
            stopIdleAnimator();
            stopLaunchAnimator();
        } else {
            startIdleAnimator();
            if (mLaunching) startLaunchAnimator();
        }
    }

    private void startIdleAnimator() {
        if (mIdleAnimator != null || !mAnimationsAllowed || !isAttachedToWindow()) return;
        mIdleAnimator = ValueAnimator.ofFloat(0f, 1f);
        mIdleAnimator.setDuration(2400);
        mIdleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mIdleAnimator.setInterpolator(new LinearInterpolator());
        mIdleAnimator.addUpdateListener(a -> {
            mIdleFraction = (Float) a.getAnimatedValue();
            if (!mLaunching) invalidate();
        });
        mIdleAnimator.start();
    }

    private void stopIdleAnimator() {
        if (mIdleAnimator != null) {
            mIdleAnimator.cancel();
            mIdleAnimator = null;
        }
    }

    private void startLaunchAnimator() {
        if (!mAnimationsAllowed || !isAttachedToWindow()) return;
        if (mLaunchAnimator == null) {
            mLaunchAnimator = ValueAnimator.ofFloat(-0.25f, 1.25f);
            mLaunchAnimator.setDuration(1150);
            mLaunchAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mLaunchAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            mLaunchAnimator.addUpdateListener(a -> {
                mLaunchFraction = (Float) a.getAnimatedValue();
                stepParticles();
                invalidate();
            });
        }
        if (!mLaunchAnimator.isStarted()) mLaunchAnimator.start();
    }

    private void stopLaunchAnimator() {
        if (mLaunchAnimator != null) {
            mLaunchAnimator.cancel();
            mLaunchAnimator = null;
        }
        clearParticles();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(mRoundPath);
        if (mStopMode) {
            mBasePaint.setShader(null);
            mBasePaint.setColor(0xFFE53945);
        }
        canvas.drawPath(mRoundPath, mBasePaint);
        if (mStopMode) mBasePaint.setShader(mBaseGradient);

        // Idle sheen: a quiet diagonal sweep; runs only while visible.
        float sheenX = (-0.35f + mIdleFraction * 1.7f) * getWidth();
        canvas.save();
        canvas.translate(sheenX, 0);
        canvas.skew(-0.25f, 0f);
        canvas.drawRect(-getWidth() * 0.25f, 0, getWidth() * 0.5f, getHeight(), mSheenPaint);
        canvas.restore();

        if (mLaunching) {
            // Premium horizontal wave: progress-like motion, not a circular download.
            canvas.save();
            canvas.translate((mLaunchFraction - 0.5f) * getWidth(), 0);
            canvas.drawRect(-getWidth(), 0, getWidth(), getHeight(), mWavePaint);
            canvas.restore();
        }
        canvas.restoreToCount(save);

        // Child content (icon/text).
        super.dispatchDraw(canvas);

        // Glow + edge highlight above content.
        if (mLaunching) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(mLaunchFraction * Math.PI * 2.0);
            mStrokePaint.setColor(0x55D0D0D0);
            mStrokePaint.setStrokeWidth(dp(4.5f + pulse * 1.3f));
            canvas.drawPath(mRoundPath, mStrokePaint);
        }
        mStrokePaint.setColor(mStopMode ? 0xFFFFA0A7 : mLaunching ? 0x99F3F3F7 : 0x66FFFFFF);
        mStrokePaint.setStrokeWidth(dp(1.15f));
        canvas.drawPath(mRoundPath, mStrokePaint);

        drawParticles(canvas);
    }

    private void spawnParticles() {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            double a = (Math.PI * 2.0 * i / PARTICLE_COUNT) + (Math.random() * 0.22);
            float speed = dp(1.2f + (float) Math.random() * 2.8f);
            mPX[i] = cx;
            mPY[i] = cy;
            mVX[i] = (float) Math.cos(a) * speed;
            mVY[i] = (float) Math.sin(a) * speed - dp(0.6f);
            mPLife[i] = 18 + (int) (Math.random() * 16);
        }
    }

    private void stepParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            if (mPLife[i] <= 0) continue;
            mPLife[i]--;
            mPX[i] += mVX[i];
            mPY[i] += mVY[i];
            mVY[i] += dp(0.035f);
        }
    }

    private void drawParticles(Canvas canvas) {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            int life = mPLife[i];
            if (life <= 0) continue;
            int alpha = Math.min(220, life * 9);
            mParticlePaint.setColor((alpha << 24) | (i % 3 == 0 ? 0xD0D0D0 : 0xFFFFFF));
            canvas.drawCircle(mPX[i], mPY[i], dp(i % 4 == 0 ? 1.7f : 1.1f), mParticlePaint);
        }
    }

    private void clearParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) mPLife[i] = 0;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
