package net.kdt.pojavlaunch.modloaders.modpacks;

import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.View.DragShadowBuilder;

import java.io.File;

/**
 * The thing that follows the finger while a mod is being dragged onto another
 * profile: a small graphite pill with the jar's file name.
 *
 * Android's default shadow is a screenshot of the whole source row, which on a
 * wide landscape card looks like you are dragging the entire list item. Drawing
 * the chip ourselves keeps the affordance readable — you can always see the
 * profile you are about to drop onto.
 */
public class ModCopyDragShadow extends DragShadowBuilder {
    private final String mLabel;
    private final float mDensity;
    private int mWidth;
    private int mHeight;
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GradientDrawable mBg = new GradientDrawable();

    public ModCopyDragShadow(View view, File modFile, float density) {
        super(view);
        mDensity = density;
        String name = modFile != null ? modFile.getName() : "mod";
        if (name.length() > 26) name = name.substring(0, 25) + "…";
        mLabel = name;
        mTextPaint.setColor(Color.parseColor("#F2F3F7"));
        mTextPaint.setTextSize(12f * density);
        mTextPaint.setFakeBoldText(true);
        mIconPaint.setColor(Color.parseColor("#4ADE80"));
        mBg.setColor(Color.parseColor("#E616171C"));
        mBg.setCornerRadius(11f * density);
        mBg.setStroke((int) (1f * density), Color.parseColor("#3A3D47"));
        Rect bounds = new Rect();
        mTextPaint.getTextBounds(mLabel, 0, mLabel.length(), bounds);
        int iconAndGaps = (int) ((14f + 8f + 12f + 12f) * density);
        mWidth = Math.min((int) (280f * density), iconAndGaps + bounds.width());
        mHeight = (int) (40f * density);
    }

    @Override
    public void onProvideShadowMetrics(Point size, Point touch) {
        size.set(mWidth, mHeight);
        touch.set(mWidth / 2, mHeight / 2);
    }

    @Override
    public void onDrawShadow(Canvas canvas) {
        mBg.setBounds(0, 0, mWidth, mHeight);
        mBg.draw(canvas);
        float cy = mHeight / 2f;
        float r = 6.5f * mDensity;
        float ix = 12f * mDensity + r;
        // A "file" glyph: two overlapping sheets, the front one green — reads
        // as "a copy is being carried" without needing an icon resource here.
        mIconPaint.setColor(Color.parseColor("#2E3240"));
        canvas.drawRect(ix - r, cy - r - 2f * mDensity, ix + r, cy + r - 2f * mDensity, mIconPaint);
        mIconPaint.setColor(Color.parseColor("#4ADE80"));
        canvas.drawRect(ix - r, cy - r + 1.5f * mDensity, ix + r, cy + r + 2f * mDensity, mIconPaint);
        canvas.drawText(mLabel, ix + r + 8f * mDensity,
                cy - (mTextPaint.ascent() + mTextPaint.descent()) / 2f, mTextPaint);
    }

    /** ClipData for the drag — a single local URI; the payload is the path. */
    public static ClipData clipDataFor(File modFile) {
        return new ClipData("mod", new String[]{"application/java-archive"},
                new ClipData.Item(android.net.Uri.fromFile(modFile)));
    }
}
