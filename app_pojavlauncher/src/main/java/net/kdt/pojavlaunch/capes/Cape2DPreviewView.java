package net.kdt.pojavlaunch.capes;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Pixel-perfect 2D Cape Texture Preview View.
 * Extracts and displays the front and back of a Minecraft cape texture with crisp nearest-neighbor
 * pixel scaling and rounded frame.
 */
public class Cape2DPreviewView extends View {

    private Bitmap mCapeBitmap;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrcRectFront = new Rect();
    private final Rect mSrcRectBack = new Rect();
    private final RectF mDstRectFront = new RectF();
    private final RectF mDstRectBack = new RectF();

    private boolean mShowBothSides = false;

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
        mPaint.setFilterBitmap(false); // Sharp nearest-neighbor pixels
        mPaint.setDither(false);

        mBgPaint.setColor(Color.parseColor("#12141C"));
        mBgPaint.setStyle(Paint.Style.FILL);

        mBorderPaint.setColor(Color.parseColor("#252A38"));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 1f);
    }

    public void setCapeBitmap(@Nullable Bitmap bitmap) {
        setCapeBitmap(bitmap, false);
    }

    public void setCapeBitmap(@Nullable Bitmap bitmap, boolean showBothSides) {
        this.mCapeBitmap = bitmap;
        this.mShowBothSides = showBothSides;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cornerRadius = 8f * getResources().getDisplayMetrics().density;
        RectF bounds = new RectF(1, 1, w - 1, h - 1);
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, mBgPaint);
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, mBorderPaint);

        if (mCapeBitmap == null || mCapeBitmap.isRecycled()) {
            // Draw subtle placeholder icon/shimmer
            return;
        }

        int bmpW = mCapeBitmap.getWidth();
        int bmpH = mCapeBitmap.getHeight();

        // Standard 64x32 cape sheet has:
        // Front: x: 1..11, y: 1..17 (w=10, h=16)
        // Back:  x: 12..22, y: 1..17 (w=10, h=16)
        // Scale factor for HD capes:
        int scale = bmpW / 64;
        if (scale < 1) scale = 1;

        int fL = 1 * scale;
        int fT = 1 * scale;
        int fR = 11 * scale;
        int fB = 17 * scale;

        int bL = 12 * scale;
        int bT = 1 * scale;
        int bR = 22 * scale;
        int bB = 17 * scale;

        if (fR > bmpW || fB > bmpH) {
            // If dimensions are non-standard, draw whole bitmap
            mSrcRectFront.set(0, 0, bmpW, bmpH);
            float pad = 8f * getResources().getDisplayMetrics().density;
            mDstRectFront.set(pad, pad, w - pad, h - pad);
            canvas.drawBitmap(mCapeBitmap, mSrcRectFront, mDstRectFront, mPaint);
            return;
        }

        mSrcRectFront.set(fL, fT, fR, fB);
        mSrcRectBack.set(bL, bT, bR, bB);

        float pad = 6f * getResources().getDisplayMetrics().density;
        float availW = w - (pad * 2);
        float availH = h - (pad * 2);

        if (mShowBothSides) {
            float capeW = (availW - pad) / 2f;
            float capeH = capeW * 1.6f;
            if (capeH > availH) {
                capeH = availH;
                capeW = capeH / 1.6f;
            }

            float top = (h - capeH) / 2f;
            float left1 = (w - (capeW * 2 + pad)) / 2f;
            float left2 = left1 + capeW + pad;

            mDstRectFront.set(left1, top, left1 + capeW, top + capeH);
            mDstRectBack.set(left2, top, left2 + capeW, top + capeH);

            canvas.drawBitmap(mCapeBitmap, mSrcRectFront, mDstRectFront, mPaint);
            canvas.drawBitmap(mCapeBitmap, mSrcRectBack, mDstRectBack, mPaint);
        } else {
            float capeH = availH;
            float capeW = capeH / 1.6f;
            if (capeW > availW) {
                capeW = availW;
                capeH = capeW * 1.6f;
            }

            float left = (w - capeW) / 2f;
            float top = (h - capeH) / 2f;

            mDstRectFront.set(left, top, left + capeW, top + capeH);
            canvas.drawBitmap(mCapeBitmap, mSrcRectFront, mDstRectFront, mPaint);
        }
    }
}
