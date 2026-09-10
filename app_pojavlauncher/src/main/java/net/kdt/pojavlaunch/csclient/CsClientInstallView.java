package net.kdt.pojavlaunch.csclient;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.annotation.Nullable;
import net.kdt.pojavlaunch.R;

/**
 * CS Launcher Plus — Futuristic Cyber Reactor Client Installation & Download Visualizer.
 * Features rotating holographic gyroscope rings, orbital particle trackers, live telemetry HUD,
 * and multi-stage atomic progress rail.
 */
public final class CsClientInstallView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRingRect = new RectF();
    private final RectF mInnerRect = new RectF();
    private final RectF mCardRect = new RectF();
    private final Path mHexPath = new Path();

    private final Bitmap mLogo;
    private ValueAnimator mMotionAnimator;
    private float mPhase = 0f;
    private float mShownProgress = 0f;
    private float mTargetProgress = 0f;
    private String mStatus = "INITIALIZING CORE";
    private String mDetail = "Preparing secure client environment & verified files...";
    private boolean mIsDone = false;
    private boolean mIsFailed = false;

    private final String[] STAGES = {"LOADER", "RUNTIME", "CS CORE", "MODULES", "OPTIMIZE"};

    // Orbital particles
    private static final int PARTICLE_COUNT = 8;
    private final float[] mParticleOffsets = new float[PARTICLE_COUNT];
    private final float[] mParticleSpeeds = new float[]{1.0f, 1.3f, 0.7f, 1.5f, 0.9f, 1.2f, 0.6f, 1.4f};

    public CsClientInstallView(Context context) {
        this(context, null);
    }

    public CsClientInstallView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CsClientInstallView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mLogo = BitmapFactory.decodeResource(getResources(), R.drawable.cs_logo);
        init();
    }

    private void init() {
        setLayerType(LAYER_TYPE_HARDWARE, null);

        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            mParticleOffsets[i] = (float) (Math.random() * Math.PI * 2);
        }

        mMotionAnimator = ValueAnimator.ofFloat(0f, 1f);
        mMotionAnimator.setDuration(3600);
        mMotionAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mMotionAnimator.setInterpolator(new LinearInterpolator());
        mMotionAnimator.addUpdateListener(anim -> {
            mPhase = (float) anim.getAnimatedValue();
            float delta = mTargetProgress - mShownProgress;
            mShownProgress = Math.abs(delta) < 0.05f ? mTargetProgress : mShownProgress + delta * 0.12f;
            invalidate();
        });
    }

    public void start() {
        if (!mMotionAnimator.isStarted()) {
            mMotionAnimator.start();
        }
    }

    public void stop() {
        mMotionAnimator.cancel();
    }

    public void setInstallProgress(int pct, String text) {
        mTargetProgress = Math.max(0, Math.min(100, pct));
        mDetail = text == null ? "" : text;
        mStatus = stageFor(pct);
        invalidate();
    }

    public void complete(String text) {
        mTargetProgress = 100;
        mIsDone = true;
        mIsFailed = false;
        mStatus = "CLIENT READY";
        mDetail = text != null ? text : "Client successfully installed and verified";
        invalidate();
    }

    public void fail(String text) {
        mIsFailed = true;
        mIsDone = false;
        mStatus = "INSTALLATION ABORTED";
        mDetail = text != null ? text : "Verification failed or connection interrupted";
        invalidate();
    }

    private String stageFor(int pct) {
        if (pct < 20) return "RESOLVING FABRIC & LWJGL3";
        if (pct < 45) return "DEPLOYING CS CLIENT CORE";
        if (pct < 70) return "INJECTING GRAPHICS SHADERS";
        if (pct < 90) return "CONFIGURING OPTIMIZATION PATCHES";
        return "FINALIZING ATOMIC PROFILE";
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f;
        float cy = h * 0.38f;

        // 1. Deep Obsidian Slate Background
        canvas.drawColor(0xFF0A0B10);

        // 2. Cyber grid mesh & radial ambient glow
        drawCyberBackground(canvas, w, h, cx, cy);

        // 3. Central Holographic Core & Gyroscope Rings
        drawHolographicCore(canvas, cx, cy, w, h);

        // 4. Telemetry Header & Stage Info Deck
        drawTelemetryHUD(canvas, cx, cy, w, h);

        // 5. High-Tech Progress Rail & Stage Nodes
        drawProgressRail(canvas, w, h);
    }

    private void drawCyberBackground(Canvas canvas, float w, float h, float cx, float cy) {
        // Radial cyber ambient top glow
        Paint bgGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgGlow.setShader(new RadialGradient(cx, cy * 0.8f, w * 0.7f,
                new int[]{0x1A5BD097, 0x0C38BDF8, 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bgGlow);

        // Fine grid coordinates
        mPaint.setColor(0x07FFFFFF);
        mPaint.setStrokeWidth(dp(0.75f));
        mPaint.setStyle(Paint.Style.STROKE);
        int cols = 10;
        for (int i = 1; i < cols; i++) {
            canvas.drawLine(w * i / (float) cols, 0, w * i / (float) cols, h, mPaint);
        }
        int rows = 7;
        for (int i = 1; i < rows; i++) {
            canvas.drawLine(0, h * i / (float) rows, w, h * i / (float) rows, mPaint);
        }
    }

    private void drawHolographicCore(Canvas canvas, float cx, float cy, float w, float h) {
        float baseRadius = Math.min(w, h) * 0.17f;

        // ── Outer Segmented Tech Ring ──
        float outerRadius = baseRadius + dp(24);
        mRingRect.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(1.5f));
        mPaint.setColor(0x1FFFFFFF);

        float rotOffset = mPhase * 360f;
        int segments = 24;
        for (int i = 0; i < segments; i++) {
            float startAngle = (360f / segments) * i + rotOffset;
            canvas.drawArc(mRingRect, startAngle, (360f / segments) * 0.55f, false, mPaint);
        }

        // ── Counter-Rotating Neon Gyro Ring ──
        float midRadius = baseRadius + dp(12);
        mRingRect.set(cx - midRadius, cy - midRadius, cx + midRadius, cy + midRadius);
        mRingPaint.setStrokeWidth(dp(2.5f));
        mRingPaint.setShader(new SweepGradient(cx, cy,
                new int[]{0x005BD097, 0xFF38BDF8, 0xFF818CF8, 0x005BD097},
                new float[]{0f, 0.45f, 0.85f, 1f}));

        canvas.save();
        canvas.rotate(-rotOffset * 0.75f, cx, cy);
        canvas.drawArc(mRingRect, 0, 300, false, mRingPaint);
        canvas.restore();
        mRingPaint.setShader(null);

        // ── Main Circular Progress Arc ──
        mRingRect.set(cx - baseRadius, cy - baseRadius, cx + baseRadius, cy + baseRadius);

        // Base Track
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(5.5f));
        mPaint.setColor(0xFF141722);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(mRingRect, -90, 360, false, mPaint);

        // Active Progress Sweep
        float sweep = 360f * (mShownProgress / 100f);
        int primaryColor = mIsDone ? 0xFF5BD097 : (mIsFailed ? 0xFFFF5A5A : 0xFFE2E8F0);
        int secondaryColor = mIsDone ? 0xFF38BDF8 : (mIsFailed ? 0xFFB91C1C : 0xFF94A3B8);

        mRingPaint.setStrokeWidth(dp(5.5f));
        mRingPaint.setColor(primaryColor);
        mRingPaint.setShader(new SweepGradient(cx, cy,
                new int[]{secondaryColor, primaryColor, 0xFFFFFFFF, secondaryColor},
                new float[]{0f, 0.6f, 0.85f, 1f}));

        canvas.save();
        canvas.rotate(-90 + (mPhase * 18f), cx, cy);
        canvas.drawArc(mRingRect, 0, Math.max(1f, sweep), false, mRingPaint);
        canvas.restore();
        mRingPaint.setShader(null);

        // ── Orbiting Satellite Energy Particles ──
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float pAngle = (float) ((mPhase * Math.PI * 2 * mParticleSpeeds[i] + mParticleOffsets[i]) % (Math.PI * 2));
            float pRad = baseRadius + dp(6) + (float) Math.sin(pAngle * 2) * dp(5);
            float px = cx + (float) Math.cos(pAngle) * pRad;
            float py = cy + (float) Math.sin(pAngle) * pRad;

            mGlowPaint.setStyle(Paint.Style.FILL);
            mGlowPaint.setColor(i % 2 == 0 ? 0xFF38BDF8 : 0xFF5BD097);
            mGlowPaint.setAlpha((int) (120 + 100 * Math.sin(pAngle + mPhase)));
            canvas.drawCircle(px, py, dp(2f), mGlowPaint);
        }

        // ── Center Holographic Shield / Hexagon Reactor ──
        float hexSize = baseRadius * 0.72f;
        buildHexagonPath(mHexPath, cx, cy, hexSize);

        mCardPaint.setStyle(Paint.Style.FILL);
        mCardPaint.setColor(0xFF10131E);
        canvas.drawPath(mHexPath, mCardPaint);

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(1.2f));
        mPaint.setColor(0x335BD097);
        canvas.drawPath(mHexPath, mPaint);

        // Center Launcher Crest
        if (mLogo != null && !mLogo.isRecycled()) {
            float logoDim = hexSize * 1.05f;
            mInnerRect.set(cx - logoDim, cy - logoDim, cx + logoDim, cy + logoDim);
            mPaint.setAlpha(mIsDone ? 255 : (int) (190 + 55 * Math.sin(mPhase * Math.PI * 2)));
            canvas.drawBitmap(mLogo, null, mInnerRect, mPaint);
            mPaint.setAlpha(255);
        }
    }

    private void drawTelemetryHUD(Canvas canvas, float cx, float cy, float w, float h) {
        float baseRadius = Math.min(w, h) * 0.17f;
        float textTop = cy + baseRadius + dp(38);

        // Main Title Header
        drawCenteredText(canvas, "CS CLIENT CORE", cx, textTop, dp(20), 0xFFFFFFFF, true);

        // Status Badge Pill
        float badgeY = textTop + dp(22);
        String badgeText = mIsDone ? "READY • VERIFIED" : (mIsFailed ? "ERROR • STOPPED" : mStatus);
        int badgeColor = mIsDone ? 0xFF5BD097 : (mIsFailed ? 0xFFFF5A5A : 0xFF38BDF8);

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0x18FFFFFF);
        float badgeWidth = mPaint.measureText(badgeText) + dp(28);
        mCardRect.set(cx - badgeWidth / 2f, badgeY - dp(10), cx + badgeWidth / 2f, badgeY + dp(6));
        canvas.drawRoundRect(mCardRect, dp(4), dp(4), mPaint);

        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(dp(0.8f));
        mPaint.setColor(badgeColor);
        canvas.drawRoundRect(mCardRect, dp(4), dp(4), mPaint);

        drawCenteredText(canvas, badgeText, cx, badgeY + dp(1), dp(9.5f), badgeColor, true);

        // File Detail readout
        drawCenteredText(canvas, trim(mDetail, 62), cx, badgeY + dp(22), dp(10.5f), 0xFF8E95A5, false);
    }

    private void drawProgressRail(Canvas canvas, float w, float h) {
        float left = w * 0.14f;
        float right = w * 0.86f;
        float railY = h * 0.77f;
        float railH = dp(7f);

        // Percentage Readout (Top Right of Rail)
        String pctStr = Math.round(mShownProgress) + "%";
        drawText(canvas, pctStr, right, railY - dp(8), dp(13), 0xFFFFFFFF, true, Paint.Align.RIGHT);
        drawText(canvas, "STAGE SYNCHRONIZATION", left, railY - dp(8), dp(9), 0xFF6B7280, true, Paint.Align.LEFT);

        // Track Background
        mCardPaint.setStyle(Paint.Style.FILL);
        mCardPaint.setColor(0xFF141724);
        mCardRect.set(left, railY, right, railY + railH);
        canvas.drawRoundRect(mCardRect, dp(3.5f), dp(3.5f), mCardPaint);

        // Active Filled Beam
        float fillRight = left + (right - left) * (mShownProgress / 100f);
        if (fillRight > left) {
            mCardPaint.setColor(mIsDone ? 0xFF5BD097 : (mIsFailed ? 0xFFFF5A5A : 0xFF38BDF8));
            mCardPaint.setShader(new LinearGradient(left, 0, Math.max(left + 1, fillRight), 0,
                    new int[]{0xFF38BDF8, 0xFF5BD097, 0xFFE2E8F0},
                    new float[]{0f, 0.75f, 1f}, Shader.TileMode.CLAMP));
            mCardRect.set(left, railY, fillRight, railY + railH);
            canvas.drawRoundRect(mCardRect, dp(3.5f), dp(3.5f), mCardPaint);
            mCardPaint.setShader(null);

            // Shimmer Laser Wave
            if (!mIsDone && !mIsFailed) {
                float wavePos = (mPhase * 1.5f) % 1f;
                float sx = left + (right - left) * wavePos;
                Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                wavePaint.setShader(new LinearGradient(sx - dp(35), 0, sx + dp(25), 0,
                        new int[]{0x00FFFFFF, 0xD0FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(Math.max(left, sx - dp(35)), railY, Math.min(fillRight, sx + dp(25)), railY + railH, wavePaint);
            }
        }

        // 5 Stage Nodes
        int activeStage = mShownProgress < 20 ? 0 : mShownProgress < 45 ? 1 : mShownProgress < 70 ? 2 : mShownProgress < 90 ? 3 : 4;
        float stepGap = (right - left) / (STAGES.length - 1);

        for (int i = 0; i < STAGES.length; i++) {
            float nodeX = left + stepGap * i;
            boolean isReached = i <= activeStage || mIsDone;
            boolean isCurrent = i == activeStage && !mIsDone && !mIsFailed;

            // Dot
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(isCurrent ? 0xFFFFFFFF : (isReached ? 0xFF5BD097 : 0xFF2A2E3D));
            canvas.drawCircle(nodeX, railY + railH / 2f, dp(isCurrent ? 4f : 2.5f), mPaint);

            if (isCurrent) {
                mGlowPaint.setStyle(Paint.Style.STROKE);
                mGlowPaint.setStrokeWidth(dp(1.2f));
                mGlowPaint.setColor(0x665BD097);
                canvas.drawCircle(nodeX, railY + railH / 2f, dp(7.5f), mGlowPaint);
            }

            // Stage Label
            drawText(canvas, STAGES[i], nodeX, railY + dp(22), dp(8f),
                    isCurrent ? 0xFFFFFFFF : (isReached ? 0xFFB4BAD0 : 0xFF535868), true, Paint.Align.CENTER);
        }

        // Bottom Footer Guarantee
        drawCenteredText(canvas, "ATOMIC LOCAL CACHE • ZERO BLOAT • FULL OFFLINE COMPATIBILITY",
                w * 0.5f, h - dp(18), dp(8f), 0xFF4B5563, true);
    }

    private void buildHexagonPath(Path path, float cx, float cy, float radius) {
        path.reset();
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(60 * i - 30);
            float x = cx + (float) (radius * Math.cos(angle));
            float y = cy + (float) (radius * Math.sin(angle));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
    }

    private void drawCenteredText(Canvas canvas, String text, float x, float y, float size, int color, boolean bold) {
        drawText(canvas, text, x, y, size, color, bold, Paint.Align.CENTER);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float size, int color, boolean bold, Paint.Align align) {
        mPaint.setShader(null);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(color);
        mPaint.setTextSize(size);
        mPaint.setTextAlign(align);
        mPaint.setTypeface(bold ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) : Typeface.SANS_SERIF);
        canvas.drawText(text, x, y, mPaint);
    }

    private String trim(String str, int maxLen) {
        if (str == null) return "";
        return str.length() <= maxLen ? str : str.substring(0, maxLen - 1) + "…";
    }

    private float dp(float val) {
        return val * getResources().getDisplayMetrics().density;
    }
}
