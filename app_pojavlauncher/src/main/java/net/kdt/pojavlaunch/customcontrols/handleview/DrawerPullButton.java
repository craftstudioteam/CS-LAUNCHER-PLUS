package net.kdt.pojavlaunch.customcontrols.handleview;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Base64;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;

import net.kdt.pojavlaunch.R;

/** Movable, resizable and image-customisable launcher drawer/settings handle. */
public class DrawerPullButton extends View {
    private static final String PREFS = "drawer_button_custom";
    private static final String KEY_X = "x_fraction", KEY_Y = "y_fraction";
    private static final String KEY_W = "width_dp", KEY_H = "height_dp", KEY_IMAGE = "image";
    private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private VectorDrawableCompat mDrawable;
    private Bitmap mCustomBitmap;

    public DrawerPullButton(Context context) { super(context); init(); }
    public DrawerPullButton(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        mDrawable = VectorDrawableCompat.create(getResources(), R.drawable.ic_sharp_settings_24, null);
        if (mDrawable != null) mDrawable.setTint(Color.WHITE);
        setAlpha(0.95f); setClickable(true); setFocusable(true);
        mBackgroundPaint.setColor(0xFF1F212B);
        loadImage();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::applySavedLayout);
    }

    public void applySavedLayout() {
        if (!(getParent() instanceof View)) return;
        View parent = (View)getParent();
        SharedPreferences p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int w = dp(p.getFloat(KEY_W, 60f)), h = dp(p.getFloat(KEY_H, 30f));
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) { lp.width = w; lp.height = h; setLayoutParams(lp); }
        post(() -> {
            float maxX = Math.max(0, parent.getWidth() - getWidth());
            float maxY = Math.max(0, parent.getHeight() - getHeight());
            setX(p.getFloat(KEY_X, 0.5f) * maxX);
            setY(p.getFloat(KEY_Y, 0f) * maxY);
        });
    }

    public void savePosition() {
        if (!(getParent() instanceof View)) return;
        View parent = (View)getParent();
        float maxX = Math.max(1, parent.getWidth() - getWidth());
        float maxY = Math.max(1, parent.getHeight() - getHeight());
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_X, Math.max(0f, Math.min(1f, getX() / maxX)))
                .putFloat(KEY_Y, Math.max(0f, Math.min(1f, getY() / maxY)))
                .putFloat(KEY_W, getWidth() / getResources().getDisplayMetrics().density)
                .putFloat(KEY_H, getHeight() / getResources().getDisplayMetrics().density).apply();
    }

    public void resizeBy(float factor) {
        setSizeDp(getWidth() / getResources().getDisplayMetrics().density * factor,
                getHeight() / getResources().getDisplayMetrics().density * factor);
    }

    public void setSizeDp(float width, float height) {
        int w = Math.max(dp(36), Math.min(dp(140), dp(width)));
        int h = Math.max(dp(24), Math.min(dp(90), dp(height)));
        android.view.ViewGroup.LayoutParams lp = getLayoutParams();
        lp.width = w; lp.height = h; setLayoutParams(lp); post(this::savePosition);
    }

    public void setCustomImageData(@Nullable String data) {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_IMAGE, data).apply();
        loadImage(); invalidate();
    }

    private void loadImage() {
        mCustomBitmap = null;
        String data = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_IMAGE, null);
        if (data == null) return;
        try {
            int comma = data.indexOf(',');
            byte[] bytes = Base64.decode(comma >= 0 ? data.substring(comma + 1) : data, Base64.DEFAULT);
            mCustomBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {}
    }

    @Override protected void onDraw(Canvas canvas) {
        if (mCustomBitmap != null) {
            // Custom art owns the complete handle: no forced black capsule.
            // Preserve aspect ratio and center it, so square/portrait artwork is
            // never stretched. Resizing the handle scales the art naturally.
            float scale = Math.min(getWidth() / (float)mCustomBitmap.getWidth(),
                    getHeight() / (float)mCustomBitmap.getHeight());
            int drawW = Math.max(1, Math.round(mCustomBitmap.getWidth() * scale));
            int drawH = Math.max(1, Math.round(mCustomBitmap.getHeight() * scale));
            int left = (getWidth() - drawW) / 2;
            int top = (getHeight() - drawH) / 2;
            canvas.drawBitmap(mCustomBitmap, null,
                    new android.graphics.Rect(left, top, left + drawW, top + drawH), mBitmapPaint);
            return;
        }

        // The graphite capsule belongs only to the default settings glyph.
        float radius = Math.min(getWidth(), getHeight()) * 0.34f;
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, mBackgroundPaint);
        int pad = Math.max(dp(4), Math.min(getWidth(), getHeight()) / 6);
        if (mDrawable != null) {
            int side = Math.min(getWidth(), getHeight()) - pad * 2;
            int left = (getWidth() - side) / 2, top = (getHeight() - side) / 2;
            mDrawable.setBounds(left, top, left + side, top + side);
            mDrawable.draw(canvas);
        }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}