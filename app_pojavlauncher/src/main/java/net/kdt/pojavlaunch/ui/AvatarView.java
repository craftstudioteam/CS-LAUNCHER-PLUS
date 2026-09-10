package net.kdt.pojavlaunch.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * Phase 10 — a portrait that is ALWAYS a clean disc.
 *
 * <p>The About page team tiles used a plain ImageView (square, centerCrop)
 * sitting on an oval ring drawable. That only looks right for a render with a
 * transparent background (the lead developer's); a full-bleed photo shows its
 * square corners poking out of the ring — "forced rounded, looks broken".
 *
 * <p>This view centre-crops the source to a square and paints it through a
 * circular {@link BitmapShader} (anti-aliased, no clipPath jaggies), so any
 * aspect ratio — portrait, landscape, transparent render — becomes a perfect
 * circle that fits inside its ring. Non-bitmap drawables are rasterised once.
 */
public class AvatarView extends AppCompatImageView {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final RectF mBounds = new RectF();
    private Bitmap mBitmap;
    private BitmapShader mShader;
    private Drawable mShaderSource;
    /** 0 = full circle; otherwise corner radius in px for a rounded square. */
    private float mCornerRadiusPx = 0f;

    public AvatarView(Context context) { super(context); }
    public AvatarView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public AvatarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    /** Use a rounded square instead of a disc (radius in dp). */
    public void setCornerRadiusDp(float dp) {
        mCornerRadiusPx = dp * getResources().getDisplayMetrics().density;
        invalidate();
    }

    private void ensureShader() {
        Drawable d = getDrawable();
        if (d == null) { mShader = null; mBitmap = null; mShaderSource = null; return; }
        if (d == mShaderSource && mShader != null) return;
        Bitmap bmp = null;
        if (d instanceof BitmapDrawable) bmp = ((BitmapDrawable) d).getBitmap();
        if (bmp == null) {
            int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96);
            int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96);
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, w, h);
                d.draw(c);
            } catch (Throwable t) { bmp = null; }
        }
        mBitmap = bmp;
        mShaderSource = d;
        mShader = bmp == null ? null : new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (mShader != null) mPaint.setShader(mShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        ensureShader();
        if (mShader == null || mBitmap == null) { super.onDraw(canvas); return; }
        int pl = getPaddingLeft(), pt = getPaddingTop();
        float w = getWidth() - pl - getPaddingRight();
        float h = getHeight() - pt - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        float side = Math.min(w, h);
        float left = pl + (w - side) / 2f, top = pt + (h - side) / 2f;
        mBounds.set(left, top, left + side, top + side);

        // centre-crop the bitmap into the square
        float bw = mBitmap.getWidth(), bh = mBitmap.getHeight();
        float scale = Math.max(side / bw, side / bh);
        float dx = left + (side - bw * scale) / 2f;
        float dy = top + (side - bh * scale) / 2f;
        mMatrix.reset();
        mMatrix.setScale(scale, scale);
        mMatrix.postTranslate(dx, dy);
        mShader.setLocalMatrix(mMatrix);

        if (mCornerRadiusPx > 0f) {
            canvas.drawRoundRect(mBounds, mCornerRadiusPx, mCornerRadiusPx, mPaint);
        } else {
            canvas.drawCircle(mBounds.centerX(), mBounds.centerY(), side / 2f, mPaint);
        }
    }

    @Override
    public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        mShader = null; mShaderSource = null;
        invalidate();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        mShader = null; mShaderSource = null;
        invalidate();
    }
}
