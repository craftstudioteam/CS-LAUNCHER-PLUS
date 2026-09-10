package com.kdt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.DashPathEffect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * The "CS" mark of the animated launch console.
 *
 * <p>The letters are real vector geometry — a swept arc for the C and a double-arc spine for the
 * S — stroked with rounded caps. Nothing here depends on a font, which is what broke the previous
 * ASCII-art version: its box-drawing characters were missing from Android's monospace font, fell
 * back to a proportional one and sheared the logo apart.
 *
 * <p>Animation timeline, replayed by {@link #startReveal()}:
 * <ol>
 *   <li><b>Draw-on</b> — each letter is stroked into existence along its own path, C first, S a
 *       beat later, with a bright tip travelling ahead of the ink.</li>
 *   <li><b>Bloom</b> — when a letter lands it flashes a soft halo that settles into a steady glow.</li>
 *   <li><b>Idle</b> — a diagonal light band keeps sweeping across the mark, the glow breathes, and
 *       the whole logo floats by a hair, so the console never looks frozen while the game loads.</li>
 * </ol>
 */
public final class CsBlockLogoView extends View {

    /** Canonical design space; everything is authored here and scaled to the view. */
    private static final float DESIGN_W = 260f;
    private static final float DESIGN_H = 120f;
    private static final float STROKE = 15f;

    private static final long DRAW_C_MS = 620L;
    private static final long DRAW_S_MS = 700L;
    private static final long S_DELAY_MS = 240L;
    private static final long BLOOM_MS = 420L;
    private static final long IDLE_PERIOD_MS = 3200L;

    private final Path mPathC = new Path();
    private final Path mPathS = new Path();
    private final Path mScaledC = new Path();
    private final Path mScaledS = new Path();

    private final Paint mInkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final PathMeasure mMeasure = new PathMeasure();
    private final Matrix mScaleMatrix = new Matrix();
    private final Matrix mShineMatrix = new Matrix();
    private final float[] mTipPos = new float[2];

    private LinearGradient mInkGradient;
    private LinearGradient mShineGradient;
    private RadialGradient mHaloGradient;

    private float mLenC, mLenS;
    private float mScale = 1f, mOffsetX, mOffsetY;

    private ValueAnimator mAnimator;
    private long mRevealStartedAt = -1L;
    private float mIdlePhase;
    private boolean mInstant;

    public CsBlockLogoView(Context context) { this(context, null); }
    public CsBlockLogoView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public CsBlockLogoView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        buildLetters();

        mInkPaint.setStyle(Paint.Style.STROKE);
        mInkPaint.setStrokeCap(Paint.Cap.ROUND);
        mInkPaint.setStrokeJoin(Paint.Join.ROUND);

        mGlowPaint.setStyle(Paint.Style.STROKE);
        mGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        mGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        mGlowPaint.setColor(0xFFFFFFFF);

        mShinePaint.setStyle(Paint.Style.STROKE);
        mShinePaint.setStrokeCap(Paint.Cap.ROUND);
        mShinePaint.setStrokeJoin(Paint.Join.ROUND);

        mTipPaint.setStyle(Paint.Style.FILL);
        mTipPaint.setColor(0xFFFFFFFF);

        mHaloPaint.setStyle(Paint.Style.FILL);

        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    // ───────────────────────── geometry ─────────────────────────

    private void buildLetters() {
        // Geometry verified by rendering it out before it ever reached a device.
        // C — one open ring, terminals at the upper and lower right.
        RectF cRect = new RectF(14f, 12f, 116f, 108f);
        mPathC.reset();
        mPathC.addArc(cRect, -48f, -264f);

        // S — two TANGENT bowls (the top one's bottom edge is the bottom one's top edge), so the
        // waist is a single shared point and the curve flows without a step. arcTo (not addArc)
        // keeps it in ONE contour, which is what lets the dash-based draw-on run cleanly from the
        // first terminal to the last.
        RectF sTop = new RectF(152f, 6f, 244f, 60f);
        RectF sBottom = new RectF(152f, 60f, 244f, 114f);
        mPathS.reset();
        mPathS.arcTo(sTop, -20f, -250f, true);    // upper bowl: right terminal, over the top, into the waist
        mPathS.arcTo(sBottom, 270f, 250f, false); // lower bowl: waist, around the right and bottom, to the left terminal

        mMeasure.setPath(mPathC, false);
        mLenC = mMeasure.getLength();
        mMeasure.setPath(mPathS, false);
        float total = mMeasure.getLength();
        while (mMeasure.nextContour()) total += mMeasure.getLength();
        mLenS = total;
    }

    // ───────────────────────── public API ─────────────────────────

    /** Replays the full draw-on. */
    public void startReveal() {
        mInstant = false;
        mRevealStartedAt = -1L;
        ensureAnimator();
        invalidate();
    }

    /** Shows the finished mark without replaying the draw-on. */
    public void showInstantly() {
        mInstant = true;
        ensureAnimator();
        invalidate();
    }

    // ───────────────────────── animation ─────────────────────────

    private void ensureAnimator() {
        if (mAnimator != null && mAnimator.isRunning()) return;
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(IDLE_PERIOD_MS);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(a -> {
            mIdlePhase = (float) a.getAnimatedValue();
            if (mRevealStartedAt < 0L) mRevealStartedAt = SystemClock.uptimeMillis();
            invalidate();
        });
        mAnimator.start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureAnimator();
    }

    @Override protected void onDetachedFromWindow() {
        if (mAnimator != null) { mAnimator.cancel(); mAnimator = null; }
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(dp(150)), widthMeasureSpec);
        int desiredHeight = Math.round(width * (DESIGN_H / DESIGN_W));
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;

        // Leave room for the stroke and its glow so nothing clips at the edges.
        float pad = STROKE * 0.9f;
        mScale = Math.min(w / (DESIGN_W + pad), h / (DESIGN_H + pad));
        mOffsetX = (w - DESIGN_W * mScale) * 0.5f;
        mOffsetY = (h - DESIGN_H * mScale) * 0.5f;

        mScaleMatrix.reset();
        mScaleMatrix.setScale(mScale, mScale);
        mScaleMatrix.postTranslate(mOffsetX, mOffsetY);
        mPathC.transform(mScaleMatrix, mScaledC);
        mPathS.transform(mScaleMatrix, mScaledS);

        float stroke = STROKE * mScale;
        mInkPaint.setStrokeWidth(stroke);
        mGlowPaint.setStrokeWidth(stroke * 1.05f);
        mShinePaint.setStrokeWidth(stroke * 0.9f);
        mGlowPaint.setMaskFilter(new BlurMaskFilter(Math.max(1f, stroke * 0.55f), BlurMaskFilter.Blur.NORMAL));

        float top = mOffsetY, bottom = mOffsetY + DESIGN_H * mScale;
        mInkGradient = new LinearGradient(0, top, 0, bottom,
                new int[]{0xFFFFFFFF, 0xFFE3E7EF, 0xFFA8B2C2},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        mInkPaint.setShader(mInkGradient);

        // Narrow travelling band; its position is animated through a local matrix.
        float band = Math.max(24f, w * 0.28f);
        mShineGradient = new LinearGradient(0, 0, band, 0,
                new int[]{0x00FFFFFF, 0x66FFFFFF, 0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.5f, 0.65f, 1f}, Shader.TileMode.CLAMP);
        mShinePaint.setShader(mShineGradient);

        mHaloGradient = new RadialGradient(w * 0.5f, h * 0.5f, Math.max(1f, Math.min(w, h) * 0.75f),
                new int[]{0x2EFFFFFF, 0x12FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        mHaloPaint.setShader(mHaloGradient);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0 || mScale <= 0f) return;

        long elapsed = mInstant || mRevealStartedAt < 0L
                ? Long.MAX_VALUE / 4
                : SystemClock.uptimeMillis() - mRevealStartedAt;

        float pC = clamp01(elapsed / (float) DRAW_C_MS);
        float pS = clamp01((elapsed - S_DELAY_MS) / (float) DRAW_S_MS);
        pC = easeOut(pC);
        pS = easeOut(pS);

        float bloomC = clamp01((elapsed - DRAW_C_MS) / (float) BLOOM_MS);
        float bloomS = clamp01((elapsed - S_DELAY_MS - DRAW_S_MS) / (float) BLOOM_MS);
        boolean settled = pC >= 1f && pS >= 1f;

        // Gentle breathing + float once the mark has landed.
        float breath = (float) Math.sin(mIdlePhase * Math.PI * 2f) * 0.5f + 0.5f;
        float floatY = settled ? (breath - 0.5f) * dp(1.6f) : 0f;

        canvas.save();
        canvas.translate(0f, floatY);

        // Backdrop halo — very faint, gives the mark some air on the black console.
        if (mHaloPaint.getShader() != null) {
            mHaloPaint.setAlpha((int) (46 + 26 * breath * (settled ? 1f : 0.3f)));
            canvas.drawRect(0, 0, getWidth(), getHeight(), mHaloPaint);
        }

        drawLetter(canvas, mScaledC, mLenC * mScale, pC, bloomC, breath, settled);
        drawLetter(canvas, mScaledS, mLenS * mScale, pS, bloomS, breath, settled);

        if (settled) drawShine(canvas);

        canvas.restore();
    }

    private void drawLetter(Canvas canvas, Path path, float length, float progress,
                            float bloom, float breath, boolean settled) {
        if (progress <= 0f || length <= 0f) return;

        boolean partial = progress < 1f;
        DashPathEffect effect = null;
        if (partial) {
            // One dash as long as the path, offset so only the drawn part shows.
            effect = new DashPathEffect(new float[]{length, length}, length * (1f - progress));
        }

        // Glow underlay: strong while the letter lands, then a calm breathing halo.
        float glowAlpha = settled ? 26f + 22f * breath : 30f + 70f * bloom;
        mGlowPaint.setPathEffect(effect);
        mGlowPaint.setAlpha((int) Math.min(255f, glowAlpha));
        canvas.drawPath(path, mGlowPaint);

        mInkPaint.setPathEffect(effect);
        mInkPaint.setAlpha(255);
        canvas.drawPath(path, mInkPaint);

        // Bright tip riding ahead of the ink while the letter is being drawn.
        if (partial) {
            mMeasure.setPath(path, false);
            float walked = 0f;
            float target = length * progress;
            do {
                float segment = mMeasure.getLength();
                if (walked + segment >= target) {
                    if (mMeasure.getPosTan(target - walked, mTipPos, null)) {
                        float r = mInkPaint.getStrokeWidth() * 0.62f;
                        mTipPaint.setAlpha(210);
                        canvas.drawCircle(mTipPos[0], mTipPos[1], r, mTipPaint);
                        mTipPaint.setAlpha(60);
                        canvas.drawCircle(mTipPos[0], mTipPos[1], r * 2.1f, mTipPaint);
                    }
                    break;
                }
                walked += segment;
            } while (mMeasure.nextContour());
        }
    }

    /** Diagonal light band travelling across both letters. */
    private void drawShine(Canvas canvas) {
        if (mShineGradient == null) return;
        float w = getWidth();
        float travel = -w * 0.4f + (mIdlePhase * (w * 1.8f));
        mShineMatrix.reset();
        mShineMatrix.setTranslate(travel, 0f);
        mShineMatrix.postRotate(18f, travel, getHeight() * 0.5f);
        mShineGradient.setLocalMatrix(mShineMatrix);

        mShinePaint.setPathEffect(null);
        mShinePaint.setAlpha(150);
        canvas.drawPath(mScaledC, mShinePaint);
        canvas.drawPath(mScaledS, mShinePaint);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : Math.min(v, 1f); }

    private static float easeOut(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
