package net.kdt.pojavlaunch.capes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Pixel-perfect 2D/3D Minecraft Cape Texture Preview.
 * Renders authentic Minecraft Cape textures (64x32 standard UV, HD 128x64, 256x128)
 * with sharp nearest-neighbor filtering, realistic fabric shadows, dual front/back
 * side display, and high-tech graphite framing.
 */
public class Cape2DPreviewView extends View {

    private Bitmap mCapeBitmap;
    private final Paint mPaint = new Paint();
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Rect mSrcRectBack = new Rect();
    private final Rect mSrcRectFront = new Rect();
    private final RectF mDstRectBack = new RectF();
    private final RectF mDstRectFront = new RectF();
    private final RectF mCardBounds = new RectF();

    private boolean mShowBothSides = true;

    public Cape2DPreviewView(Context context) {
        super(context);
        init();
    }

    public Cape2DPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public Cape2DPreviewView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mPaint.setFilterBitmap(false); // Sharp nearest-neighbor Minecraft pixels
        mPaint.setDither(false);
        mPaint.setAntiAlias(false);

        mBgPaint.setColor(Color.parseColor("#0C0E14"));
        mBgPaint.setStyle(Paint.Style.FILL);

        mBorderPaint.setColor(Color.parseColor("#222836"));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.2f);

        mLabelPaint.setColor(Color.parseColor("#7A8499"));
        mLabelPaint.setTextSize(getResources().getDisplayMetrics().density * 9.5f);
        mLabelPaint.setTextAlign(Paint.Align.CENTER);
        mLabelPaint.setLetterSpacing(0.08f);

        mShadowPaint.setColor(Color.parseColor("#50000000"));
        mShadowPaint.setStyle(Paint.Style.FILL);

        mHighlightPaint.setColor(Color.parseColor("#15FFFFFF"));
        mHighlightPaint.setStyle(Paint.Style.FILL);
    }

    public void setCapeBitmap(@Nullable Bitmap bitmap) {
        setCapeBitmap(bitmap, true);
    }

    public void setCapeBitmap(@Nullable Bitmap bitmap, boolean showBothSides) {
        this.mCapeBitmap = bitmap;
        this.mShowBothSides = showBothSides;
        invalidate();
    }

    @Nullable
    public Bitmap getCapeBitmap() {
        return mCapeBitmap;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float density = getResources().getDisplayMetrics().density;
        float cornerRadius = 10f * density;
        mCardBounds.set(2, 2, w - 2, h - 2);

        // Dark elevated background container
        canvas.drawRoundRect(mCardBounds, cornerRadius, cornerRadius, mBgPaint);
        canvas.drawRoundRect(mCardBounds, cornerRadius, cornerRadius, mBorderPaint);

        if (mCapeBitmap == null || mCapeBitmap.isRecycled()) {
            drawPlaceholder(canvas, w, h, density);
            return;
        }

        int bmpW = mCapeBitmap.getWidth();
        int bmpH = mCapeBitmap.getHeight();

        // Minecraft standard Cape UV:
        // Texture dimensions: 64x32 (or 128x64, 256x128 for HD)
        // Ratio = Width / 64
        int scale = Math.max(1, bmpW / 64);

        // Back face (Facing outward / main artwork): X: 1..11, Y: 1..17 (Width 10, Height 16)
        int backLeft = 1 * scale;
        int backTop = 1 * scale;
        int backRight = 11 * scale;
        int backBottom = 17 * scale;

        // Front face (Facing player's back / collar lining): X: 12..22, Y: 1..17 (Width 10, Height 16)
        int frontLeft = 12 * scale;
        int frontTop = 1 * scale;
        int frontRight = 22 * scale;
        int frontBottom = 17 * scale;

        boolean isStandardLayout = (backRight <= bmpW && backBottom <= bmpH);

        float pad = 12f * density;
        float labelSpace = 16f * density;
        float availW = w - (pad * 2);
        float availH = h - (pad * 2) - labelSpace;

        if (isStandardLayout && mShowBothSides) {
            // Dual presentation: Outward Back Side & Inward Front Side
            mSrcRectBack.set(backLeft, backTop, backRight, backBottom);
            mSrcRectFront.set(frontLeft, frontTop, frontRight, frontBottom);

            float capeGap = 16f * density;
            float capeW = (availW - capeGap) / 2f;
            float capeH = capeW * 1.6f; // Standard 10:16 aspect ratio

            if (capeH > availH) {
                capeH = availH;
                capeW = capeH / 1.6f;
            }

            float totalWidth = (capeW * 2) + capeGap;
            float startX = (w - totalWidth) / 2f;
            float topY = pad + (availH - capeH) / 2f;

            mDstRectBack.set(startX, topY, startX + capeW, topY + capeH);
            mDstRectFront.set(startX + capeW + capeGap, topY, startX + (capeW * 2) + capeGap, topY + capeH);

            // Draw Cape Drop Shadows
            float shadowOffset = 3f * density;
            RectF shadowBack = new RectF(mDstRectBack.left + shadowOffset, mDstRectBack.top + shadowOffset,
                    mDstRectBack.right + shadowOffset, mDstRectBack.bottom + shadowOffset);
            RectF shadowFront = new RectF(mDstRectFront.left + shadowOffset, mDstRectFront.top + shadowOffset,
                    mDstRectFront.right + shadowOffset, mDstRectFront.bottom + shadowOffset);

            canvas.drawRoundRect(shadowBack, 4f * density, 4f * density, mShadowPaint);
            canvas.drawRoundRect(shadowFront, 4f * density, 4f * density, mShadowPaint);

            // Draw Cape Textures (Back & Front)
            canvas.drawBitmap(mCapeBitmap, mSrcRectBack, mDstRectBack, mPaint);
            canvas.drawBitmap(mCapeBitmap, mSrcRectFront, mDstRectFront, mPaint);

            // Draw subtle fabric lighting bevels
            drawFabricLighting(canvas, mDstRectBack, density);
            drawFabricLighting(canvas, mDstRectFront, density);

            // Labels under previews
            float labelY = mDstRectBack.bottom + 12f * density;
            canvas.drawText("OUTWARD BACK", mDstRectBack.centerX(), labelY, mLabelPaint);
            canvas.drawText("INWARD FRONT", mDstRectFront.centerX(), labelY, mLabelPaint);
        } else {
            // Single view or full texture
            if (isStandardLayout) {
                mSrcRectBack.set(backLeft, backTop, backRight, backBottom);
            } else {
                mSrcRectBack.set(0, 0, bmpW, bmpH);
            }

            float capeH = availH;
            float capeW = isStandardLayout ? (capeH / 1.6f) : (availW * 0.8f);
            if (capeW > availW) {
                capeW = availW;
                capeH = isStandardLayout ? (capeW * 1.6f) : (availH * 0.8f);
            }

            float leftX = (w - capeW) / 2f;
            float topY = pad + (availH - capeH) / 2f;
            mDstRectBack.set(leftX, topY, leftX + capeW, topY + capeH);

            float shadowOffset = 3f * density;
            RectF shadow = new RectF(mDstRectBack.left + shadowOffset, mDstRectBack.top + shadowOffset,
                    mDstRectBack.right + shadowOffset, mDstRectBack.bottom + shadowOffset);
            canvas.drawRoundRect(shadow, 4f * density, 4f * density, mShadowPaint);

            canvas.drawBitmap(mCapeBitmap, mSrcRectBack, mDstRectBack, mPaint);
            drawFabricLighting(canvas, mDstRectBack, density);

            float labelY = mDstRectBack.bottom + 12f * density;
            canvas.drawText("MINECRAFT CAPE", mDstRectBack.centerX(), labelY, mLabelPaint);
        }
    }

    private void drawFabricLighting(Canvas canvas, RectF rect, float density) {
        // Highlight top ridge
        RectF topRidge = new RectF(rect.left, rect.top, rect.right, rect.top + 2f * density);
        canvas.drawRect(topRidge, mHighlightPaint);

        // Thin border stroke around cape edges
        Paint capeEdgePaint = new Paint();
        capeEdgePaint.setColor(Color.parseColor("#40000000"));
        capeEdgePaint.setStyle(Paint.Style.STROKE);
        capeEdgePaint.setStrokeWidth(1f * density);
        canvas.drawRect(rect, capeEdgePaint);
    }

    private void drawPlaceholder(Canvas canvas, float w, float h, float density) {
        mLabelPaint.setColor(Color.parseColor("#4C556A"));
        canvas.drawText("NO CAPE LOADED", w / 2f, h / 2f + 4f * density, mLabelPaint);
    }
}
