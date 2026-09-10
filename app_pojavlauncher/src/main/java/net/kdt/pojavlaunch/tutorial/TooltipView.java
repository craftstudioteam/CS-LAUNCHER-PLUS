package net.kdt.pojavlaunch.tutorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

/**
 * Compact, Minecraft-styled tooltip card for the guided-tutorial overlay.
 *
 * <p>Typography uses the official in-project Minecraft font ({@code @font/minecraftia}).
 * Positioning dynamically places the card above or below the target and clamps
 * within visible window boundaries, intelligently shifting to the open left-side
 * region to prevent covering the 3D player skin character.
 */
public final class TooltipView extends LinearLayout {

    private static final int BG_COLOR        = 0xF012101B;
    private static final int STROKE_COLOR    = 0xFF4A4268;
    private static final int TEXT_MAIN       = 0xFFFFFFFF;
    private static final int TEXT_SUB        = 0xFFD8D2EB;
    private static final int ACCENT_COLOR    = 0xFF8B5CF6;
    private static final int ACCENT_STROKE   = 0xFFA78BFA;
    private static final int SKIP_COLOR      = 0xFFA89EC8;
    private static final float CORNER_RADIUS = 10f;
    private static final float ARROW_SIZE    = 8f;
    private static final int PAD_H           = 14; // dp
    private static final int PAD_V           = 10; // dp

    private final TextView mTaskCounter;
    private final TextView mTitleView;
    private final TextView mMessageView;
    private final LinearLayout mButtonRow;
    private final TextView mActionBtn;
    private final TextView mSkipBtn;

    private final int mArrowPx;
    private final int mPadH;
    private final int mPadV;
    private final float mDensity;
    private final Paint mArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private Typeface mMinecraftFont;

    enum ArrowDir { UP, DOWN, LEFT, RIGHT }
    private ArrowDir mArrowDir = ArrowDir.UP;
    private boolean mShowArrow = false;
    private float mArrowAnchorX = -1;

    public interface ButtonListener {
        void onActionClicked();
        void onSkipClicked();
    }

    @Nullable private ButtonListener mButtonListener;

    public TooltipView(@NonNull Context context) {
        super(context);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        mDensity = context.getResources().getDisplayMetrics().density;
        mArrowPx = Math.max(4, Math.round(ARROW_SIZE * mDensity));
        mPadH = Math.max(4, Math.round(PAD_H * mDensity));
        mPadV = Math.max(4, Math.round(PAD_V * mDensity));

        // Load Minecraftia font
        try {
            mMinecraftFont = ResourcesCompat.getFont(context, R.font.minecraftia);
        } catch (Throwable ignored) {
            mMinecraftFont = Typeface.DEFAULT_BOLD;
        }

        // Minecraft Slate Background
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(BG_COLOR);
        bg.setStroke(Math.max(1, Math.round(1.5f * mDensity)), STROKE_COLOR);
        bg.setCornerRadius(CORNER_RADIUS * mDensity);
        setBackground(bg);

        mArrowPaint.setColor(BG_COLOR);
        mArrowPaint.setStyle(Paint.Style.FILL);

        // Task counter "TASK 2 OF 7"
        mTaskCounter = new TextView(context);
        mTaskCounter.setTextColor(SKIP_COLOR);
        mTaskCounter.setTextSize(9f);
        if (mMinecraftFont != null) mTaskCounter.setTypeface(mMinecraftFont);
        mTaskCounter.setIncludeFontPadding(false);
        mTaskCounter.setAllCaps(true);
        mTaskCounter.setVisibility(GONE);
        LayoutParams counterLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        counterLp.leftMargin = mPadH;
        counterLp.rightMargin = mPadH;
        counterLp.topMargin = mPadV;
        counterLp.bottomMargin = 0;
        addView(mTaskCounter, counterLp);

        // Title
        mTitleView = new TextView(context);
        mTitleView.setTextColor(TEXT_MAIN);
        mTitleView.setTextSize(13.5f);
        if (mMinecraftFont != null) mTitleView.setTypeface(mMinecraftFont);
        mTitleView.setIncludeFontPadding(false);
        mTitleView.setShadowLayer(4f, 0, 2f, 0xB3000000);
        LayoutParams titleLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = mPadH;
        titleLp.rightMargin = mPadH;
        titleLp.topMargin = mPadV / 2;
        titleLp.bottomMargin = mPadV / 3;
        addView(mTitleView, titleLp);

        // Message
        mMessageView = new TextView(context);
        mMessageView.setTextColor(TEXT_SUB);
        mMessageView.setTextSize(11f);
        if (mMinecraftFont != null) mMessageView.setTypeface(mMinecraftFont);
        mMessageView.setIncludeFontPadding(false);
        mMessageView.setMaxWidth(Math.round(270 * mDensity));
        LayoutParams msgLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        msgLp.leftMargin = mPadH;
        msgLp.rightMargin = mPadH;
        msgLp.topMargin = 0;
        msgLp.bottomMargin = mPadV / 2;
        addView(mMessageView, msgLp);

        // Button row
        mButtonRow = new LinearLayout(context);
        mButtonRow.setOrientation(HORIZONTAL);
        mButtonRow.setGravity(Gravity.CENTER_VERTICAL);
        mButtonRow.setVisibility(GONE);

        // Skip link
        mSkipBtn = new TextView(context);
        mSkipBtn.setText("SKIP");
        mSkipBtn.setTextColor(SKIP_COLOR);
        mSkipBtn.setTextSize(10f);
        if (mMinecraftFont != null) mSkipBtn.setTypeface(mMinecraftFont);
        mSkipBtn.setIncludeFontPadding(false);
        mSkipBtn.setClickable(true);
        mSkipBtn.setFocusable(true);
        int skipPad = Math.round(6 * mDensity);
        mSkipBtn.setPadding(skipPad, skipPad, skipPad, skipPad);
        mSkipBtn.setOnClickListener(v -> {
            if (mButtonListener != null) mButtonListener.onSkipClicked();
        });
        LayoutParams skipLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        mButtonRow.addView(mSkipBtn, skipLp);

        // Action button (NEXT / FINISH)
        mActionBtn = new TextView(context);
        mActionBtn.setText("NEXT");
        mActionBtn.setTextColor(TEXT_MAIN);
        mActionBtn.setTextSize(11f);
        if (mMinecraftFont != null) mActionBtn.setTypeface(mMinecraftFont);
        mActionBtn.setIncludeFontPadding(false);
        mActionBtn.setGravity(Gravity.CENTER);
        mActionBtn.setClickable(true);
        mActionBtn.setFocusable(true);
        int btnPadH = Math.round(16 * mDensity);
        int btnPadV = Math.round(7 * mDensity);
        mActionBtn.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setShape(GradientDrawable.RECTANGLE);
        btnBg.setColor(ACCENT_COLOR);
        btnBg.setStroke(Math.max(1, Math.round(1f * mDensity)), ACCENT_STROKE);
        btnBg.setCornerRadius(6 * mDensity);
        mActionBtn.setBackground(btnBg);
        mActionBtn.setOnClickListener(v -> {
            if (mButtonListener != null) mButtonListener.onActionClicked();
        });
        LayoutParams actionLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        mButtonRow.addView(mActionBtn, actionLp);

        LayoutParams rowLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLp.leftMargin = mPadH;
        rowLp.rightMargin = mPadH;
        rowLp.topMargin = mPadV / 2;
        rowLp.bottomMargin = mPadV;
        addView(mButtonRow, rowLp);
    }

    public void setButtonListener(@Nullable ButtonListener listener) {
        mButtonListener = listener;
    }

    public void setTitle(@Nullable String title) {
        mTitleView.setText(title != null ? title.toUpperCase() : "");
        mTitleView.setVisibility(title != null && !title.isEmpty() ? VISIBLE : GONE);
    }

    public void setMessage(@Nullable String message) {
        mMessageView.setText(message);
        mMessageView.setVisibility(message != null && !message.isEmpty() ? VISIBLE : GONE);
    }

    public void setTaskInfo(int current, int total) {
        if (current > 0 && total > 0) {
            mTaskCounter.setText("TASK " + current + " OF " + total);
            mTaskCounter.setVisibility(VISIBLE);
        } else {
            mTaskCounter.setVisibility(GONE);
        }
    }

    public void configureButtons(@NonNull TutorialActionType type, boolean showSkip) {
        switch (type) {
            case INFORMATION:
                mActionBtn.setText("NEXT");
                mActionBtn.setVisibility(VISIBLE);
                mButtonRow.setVisibility(VISIBLE);
                mSkipBtn.setVisibility(showSkip ? VISIBLE : GONE);
                break;
            case FINISH:
                mActionBtn.setText("FINISH");
                mActionBtn.setVisibility(VISIBLE);
                mButtonRow.setVisibility(VISIBLE);
                mSkipBtn.setVisibility(GONE);
                break;
            case TAP:
            case ACCOUNT_CREATION:
            case DRAG_DEMO:
                mActionBtn.setVisibility(GONE);
                if (showSkip) {
                    mSkipBtn.setVisibility(VISIBLE);
                    mButtonRow.setVisibility(VISIBLE);
                } else {
                    mButtonRow.setVisibility(GONE);
                }
                break;
        }
    }

    public void setShowArrow(boolean show) {
        if (mShowArrow != show) {
            mShowArrow = show;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int rootWidth = MeasureSpec.getSize(widthSpec);
        if (rootWidth > 0) {
            int maxW = Math.min(rootWidth - dp(32), dp(290));
            widthSpec = MeasureSpec.makeMeasureSpec(Math.max(1, maxW), MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!mShowArrow) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float arrowSize = mArrowPx;
        float anchorX = (mArrowAnchorX >= 0) ? mArrowAnchorX : w / 2f;
        anchorX = Math.max(arrowSize * 2, Math.min(anchorX, w - arrowSize * 2));
        Path path = new Path();

        switch (mArrowDir) {
            case UP:
                path.moveTo(anchorX - arrowSize, 0);
                path.lineTo(anchorX, -arrowSize);
                path.lineTo(anchorX + arrowSize, 0);
                path.close();
                break;
            case DOWN:
                path.moveTo(anchorX - arrowSize, h);
                path.lineTo(anchorX, h + arrowSize);
                path.lineTo(anchorX + arrowSize, h);
                path.close();
                break;
            case LEFT:
                float cy = h / 2f;
                path.moveTo(0, cy - arrowSize);
                path.lineTo(-arrowSize, cy);
                path.lineTo(0, cy + arrowSize);
                path.close();
                break;
            case RIGHT:
                float cyR = h / 2f;
                path.moveTo(w, cyR - arrowSize);
                path.lineTo(w + arrowSize, cyR);
                path.lineTo(w, cyR + arrowSize);
                path.close();
                break;
        }

        canvas.drawPath(path, mArrowPaint);
    }

    /**
     * Position this tooltip intelligently relative to the target rect.
     * For bottom-anchored components (like the carousel), places the card in the
     * open LEFT-SIDE region above the carousel, preventing obstruction of the center 3D character.
     */
    boolean positionRelativeTo(@Nullable RectF targetRect, int overlayW, int overlayH) {
        if (targetRect == null || overlayW <= 0 || overlayH <= 0) return false;

        measure(MeasureSpec.makeMeasureSpec(overlayW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(overlayH, MeasureSpec.AT_MOST));
        int tw = getMeasuredWidth();
        int th = getMeasuredHeight();
        if (tw <= 0 || th <= 0) return false;

        int margin = Math.round(14 * mDensity);
        float targetCx = targetRect.centerX();
        float targetCy = targetRect.centerY();

        int left, top;

        // Check if target is in bottom half (e.g. carousel / launch row)
        boolean targetInBottomHalf = targetCy >= overlayH * 0.45f;

        if (targetInBottomHalf) {
            // Place ABOVE target
            mArrowDir = ArrowDir.DOWN;
            top = Math.round(targetRect.top - th - margin);

            // Shift card to open LEFT-SIDE area if target spans width or is near center
            boolean shiftToLeft = targetRect.width() > overlayW * 0.45f || targetCx > overlayW * 0.35f;
            if (shiftToLeft) {
                left = Math.round(18 * mDensity); // Left side with clean padding
                // Anchor arrow down toward the leading card / slot position
                float targetLeadX = Math.max(targetRect.left + dp(94), targetRect.left + dp(24));
                mArrowAnchorX = targetLeadX - left;
            } else {
                left = Math.round(targetCx - tw / 2f);
                mArrowAnchorX = targetCx - left;
            }
        } else {
            // Target is in top half (e.g. Account Chip) -> Place BELOW target
            mArrowDir = ArrowDir.UP;
            top = Math.round(targetRect.bottom + margin);
            left = Math.round(targetCx - tw / 2f);
            mArrowAnchorX = targetCx - left;
        }

        // Clamp inside screen boundaries
        left = Math.max(margin, Math.min(left, overlayW - tw - margin));
        top = Math.max(margin, Math.min(top, overlayH - th - margin));

        // Re-clamp anchor
        mArrowAnchorX = Math.max(mArrowPx * 2f, Math.min(mArrowAnchorX, tw - mArrowPx * 2f));

        applyPosition(left, top);
        return true;
    }

    void positionCentered(int overlayW, int overlayH) {
        if (overlayW <= 0 || overlayH <= 0) return;
        measure(MeasureSpec.makeMeasureSpec(overlayW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(overlayH, MeasureSpec.AT_MOST));
        int tw = getMeasuredWidth();
        int th = getMeasuredHeight();
        int left = (overlayW - tw) / 2;
        int top = (overlayH - th) / 2;
        applyPosition(left, top);
    }

    private void applyPosition(int left, int top) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.gravity = Gravity.TOP | Gravity.START;
            flp.leftMargin = left;
            flp.topMargin = top;
            setLayoutParams(flp);
        } else {
            int tw = getMeasuredWidth();
            int th = getMeasuredHeight();
            layout(left, top, left + tw, top + th);
        }
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * mDensity));
    }
}
