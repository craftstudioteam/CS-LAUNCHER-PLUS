package com.kdt;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.CallSuper;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

/**
 * The base class for side dialog views
 * A side dialog is a dialog appearing from one side of the screen
 */
public abstract class SideDialogView {

    private final ViewGroup mParent;
    private final @LayoutRes int mLayoutId;
    private ViewGroup mDialogLayout;
    private DefocusableScrollView mScrollView;
    protected View mDialogContent;

    protected final int mMargin;
    private ObjectAnimator mSideDialogAnimator;
    protected boolean mDisplaying = false;
    /* Whether the layout is built */
    private boolean mIsInstantiated = false;
    /* Bounded re-posts while the freshly inflated panel awaits its first layout */
    private int mAppearLayoutRetries;
    /* Phase 10: the edge the sheet was last asked to dock on (true = right). X-based
       side detection lied while the sheet was still parked off-screen. */
    private boolean mDockedRight;
    private boolean mHasDock;

    /* UI elements */
    private Button mStartButton, mEndButton;
    private TextView mTitleTextview;
    private View mTitleDivider;

    /* Data to store when the UI element has yet to be inflated */
    private @StringRes int mStartButtonStringId, mEndButtonStringId, mTitleStringId;
    private View.OnClickListener mStartButtonListener, mEndButtonListener;
    /**
     * Phase 11: the header glyph/kicker chrome is gone (the sheet is the plain
     * original again). Kept as no-ops so existing callers keep compiling.
     */
    public void setGlyph(CharSequence glyph) { /* no header glyph any more */ }

    public void setKicker(CharSequence kicker) { /* no header kicker any more */ }


    public SideDialogView(Context context, ViewGroup parent, @LayoutRes int layoutId) {
        mMargin = context.getResources().getDimensionPixelOffset(R.dimen._20sdp);
        mParent = parent;
        mLayoutId = layoutId;
    }

    public void setTitle(@StringRes int textId) {
        mTitleStringId = textId;
        if (mIsInstantiated) {
            mTitleTextview.setText(textId);
            mTitleTextview.setVisibility(View.VISIBLE);
            mTitleDivider.setVisibility(View.VISIBLE);
        }
    }

    public final void setStartButtonListener(@StringRes int textId, @Nullable View.OnClickListener listener) {
        mStartButtonStringId = textId;
        mStartButtonListener = listener;
        if (mIsInstantiated) setButton(mStartButton, textId, listener);
    }

    public final void setEndButtonListener(@StringRes int textId, @Nullable View.OnClickListener listener) {
        mEndButtonStringId = textId;
        mEndButtonListener = listener;
        if (mIsInstantiated) setButton(mEndButton, textId, listener);
    }

    private void setButton(@NonNull Button button, @StringRes int textId, @Nullable View.OnClickListener listener) {
        button.setText(textId);
        button.setOnClickListener(listener);
        button.setVisibility(View.VISIBLE);
    }


    private void inflateLayout() {
        if(mIsInstantiated) {
            Log.w("SideDialogView", "Layout already inflated");
            return;
        }

        // Inflate layouts
        mDialogLayout = (ViewGroup) LayoutInflater.from(mParent.getContext()).inflate(R.layout.dialog_side_dialog, mParent, false);
        mScrollView = mDialogLayout.findViewById(R.id.side_dialog_scrollview);
        mStartButton = mDialogLayout.findViewById(R.id.side_dialog_start_button);
        mEndButton = mDialogLayout.findViewById(R.id.side_dialog_end_button);
        mTitleTextview = mDialogLayout.findViewById(R.id.side_dialog_title_textview);
        mTitleDivider = mDialogLayout.findViewById(R.id.side_dialog_title_divider);

        LayoutInflater.from(mParent.getContext()).inflate(mLayoutId, mScrollView, true);
        mDialogContent = mScrollView.getChildAt(0);

        // Phase 10 root fix for "the edit popup disappears": the frame root was
        // not clickable, so every tap INSIDE the sheet bubbled to the
        // ControlLayout canvas whose ACTION_UP handler closes the sheet. The
        // sheet now consumes its own touches (buttons/sliders inside still win).
        mDialogLayout.setClickable(true);
        mDialogLayout.setFocusable(true);

        // Attach layouts
        mParent.addView(mDialogLayout);

        // Plain slide, the original feel: 600 ms accelerate/decelerate.
        mSideDialogAnimator = ObjectAnimator.ofFloat(mDialogLayout, "x", 0).setDuration(600);
        mSideDialogAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

        mDialogLayout.setElevation(10);
        mDialogLayout.setTranslationZ(10);

        mDialogLayout.setVisibility(View.VISIBLE);

        //TODO offset better according to view width
        mDialogLayout.setX(-mDialogLayout.getResources().getDimensionPixelOffset(R.dimen._280sdp));
        mIsInstantiated = true;

        // Set up UI elements
        if (mTitleStringId != 0) setTitle(mTitleStringId);
        if (mStartButtonStringId != 0) setStartButtonListener(mStartButtonStringId, mStartButtonListener);
        if (mEndButtonStringId != 0) setEndButtonListener(mEndButtonStringId, mEndButtonListener);
    }

    /** Destroy the layout, cleanup variables */
    private void deflateLayout() {
        if(!mIsInstantiated) {
            Log.w("SideDialogView", "Layout not inflated");
            return;
        }

        mSideDialogAnimator.removeAllUpdateListeners();
        mSideDialogAnimator.removeAllListeners();
        mHasDock = false;
        mAppearLayoutRetries = 0;

        mParent.removeView(mDialogLayout);
        mIsInstantiated = false;

        mScrollView = null;
        mSideDialogAnimator = null;
        mDialogLayout = null;
        mDialogContent = null;
        mTitleTextview = null;
        mTitleDivider = null;
        mStartButton = null;
        mEndButton = null;
    }


    /**
     * Slide the layout into the visible screen area
     */
    @CallSuper
    public final void appear(final boolean fromRight) {
        if (!mIsInstantiated) {
            inflateLayout();
            onInflate();
        }

        // To avoid UI sizing issue when the dialog is not fully inflated
        onAppear();
        Tools.runOnUiThread(() -> {
            // First-appearance race (item-1/6 root fix): when the panel was
            // inflated THIS frame its measured width is still 0, so the slide
            // target computed below lands fully OFF-SCREEN — the classic
            // "quick settings sometimes never opens during launch" bug. Wait
            // one layout pass (bounded 3 tries), then animate for real.
            if (!mIsInstantiated || mDialogLayout == null) return; // deflated meanwhile
            int measured = mDialogLayout.getWidth();
            // Wait up to ~12 frames for a real width, then fall back to the
            // LayoutParams width so the sheet ALWAYS slides in.
            if (measured == 0 && mAppearLayoutRetries < 12) {
                mAppearLayoutRetries++;
                mDialogLayout.post(() -> appear(fromRight));
                return;
            }
            mAppearLayoutRetries = 0;
            if (measured == 0) {
                ViewGroup.LayoutParams lp = mDialogLayout.getLayoutParams();
                measured = lp != null && lp.width > 0 ? lp.width
                        : mDialogLayout.getResources().getDimensionPixelOffset(R.dimen._280sdp);
            }
            final int screenW = currentDisplayMetrics.widthPixels;
            if (fromRight) {
                if (!mDisplaying || !isAtRight()) {
                    mSideDialogAnimator.setFloatValues(screenW, screenW - measured - mMargin);
                    mSideDialogAnimator.start();
                    mDisplaying = true;
                }
            } else {
                if (!mDisplaying || isAtRight()) {
                    mSideDialogAnimator.setFloatValues(-measured, mMargin);
                    mSideDialogAnimator.start();
                    mDisplaying = true;
                }
            }
            mDockedRight = fromRight;
            mHasDock = true;
        });
    }

    protected final boolean isAtRight() {
        // Phase 10: while parked/animating the X test is meaningless — trust the
        // edge we were asked to dock on once we have one.
        if (mHasDock && mDisplaying) return mDockedRight;
        return mDialogLayout != null && mDialogLayout.getX() > currentDisplayMetrics.widthPixels / 2f;
    }

    /**
     * Slide out the layout
     * @param destroy Whether the layout should be destroyed after disappearing.
     *                Recommended to be true if the layout is not going to be used anymore
     */
    @CallSuper
    public final void disappear(boolean destroy) {
        if(!mIsInstantiated) {
            Log.w("SideDialogView", "Layout not inflated");
            return;
        }

        if (!mDisplaying) {
            if(destroy) {
                onDisappear();
                onDestroy();
                deflateLayout();
            }
            return;
        }

        final boolean wasRight = isAtRight();
        mDisplaying = false;
        int w = mDialogLayout.getWidth();
        if (w <= 0) {
            ViewGroup.LayoutParams lp = mDialogLayout.getLayoutParams();
            w = lp != null && lp.width > 0 ? lp.width : mDialogLayout.getResources().getDimensionPixelOffset(R.dimen._280sdp);
        }
        if (wasRight)
            mSideDialogAnimator.setFloatValues(currentDisplayMetrics.widthPixels - w - mMargin, currentDisplayMetrics.widthPixels);
        else
            mSideDialogAnimator.setFloatValues(mMargin, -w);

        if(destroy) {
            onDisappear();
            onDestroy();
            mSideDialogAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    deflateLayout();
                }
            });
        }

        mSideDialogAnimator.start();
    }

    /** @return Whether the dialog is currently displaying */
    public final boolean isDisplaying(){
        return mDisplaying;
    }

    /**
     * Called when the dialog is inflated, ideal for setting up UI elements bindings
     */
    protected void onInflate() {}

    /**
     * Called after the dialog has appeared
     */
    protected void onAppear() {}

    /**
     * Called after the dialog has disappeared
     */
    protected void onDisappear() {}

    /**
     * Called before the dialog gets destroyed (removing views from parent)
     * Ideal for cleaning up resources
     */
    protected void onDestroy() {}


}
