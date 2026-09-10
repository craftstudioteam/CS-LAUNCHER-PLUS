package net.kdt.pojavlaunch.customcontrols.handleview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;

import androidx.core.math.MathUtils;

import org.lwjgl.glfw.CallbackBridge;

public class ControlHandleView extends View {
    public ControlHandleView(Context context) {
        super(context);
        init();
    }

    public ControlHandleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private final Drawable mDrawable = ResourcesCompat.getDrawable(getResources(), R.drawable.ic_view_handle, getContext().getTheme());
    private ControlInterface mView;
    private float mDownRawX, mDownRawY, mStartW, mStartH;
    private final ViewTreeObserver.OnPreDrawListener mPositionListener = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if(mView == null || mView.getControlView() == null || !mView.getControlView().isShown()){
                hideImmediately();
                return true;
            }

            setX(mView.getControlView().getX() + mView.getControlView().getWidth());
            setY(mView.getControlView().getY() + mView.getControlView().getHeight());
            return true;
        }
    };

    private void init(){
        int size = getResources().getDimensionPixelOffset(R.dimen._22sdp);
        mDrawable.setBounds(0,0,size,size);
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(size, size);
        setLayoutParams(params);
        setBackground(mDrawable);
        setTranslationZ(10.5F);
        setVisibility(GONE);
        setScaleX(0f);
        setScaleY(0f);
        setAlpha(0f);
    }

    public void setControlButton(@Nullable ControlInterface controlInterface){
        if (controlInterface == null || controlInterface.getControlView() == null) {
            hideImmediately();
            return;
        }

        if(mView != null) {
            try {
                mView.getControlView().getViewTreeObserver().removeOnPreDrawListener(mPositionListener);
            } catch (Throwable ignored) {}
        }

        boolean fresh = mView != controlInterface || getVisibility() != VISIBLE;
        mView = controlInterface;
        setVisibility(VISIBLE);
        
        if (fresh) {
            // Spring out of the control's corner cleanly
            animate().cancel();
            setScaleX(0f); setScaleY(0f); setAlpha(0f);
            animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(60).setDuration(320)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.0f)).start();
        } else {
            setScaleX(1f); setScaleY(1f); setAlpha(1f);
        }

        try {
            mView.getControlView().getViewTreeObserver().addOnPreDrawListener(mPositionListener);
        } catch (Throwable ignored) {}

        setX(controlInterface.getControlView().getX() + controlInterface.getControlView().getWidth());
        setY(controlInterface.getControlView().getY() + controlInterface.getControlView().getHeight());
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mView == null || mView.getControlView() == null) return false;
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mStartW = mView.getControlView().getWidth();
                mStartH = mView.getControlView().getHeight();
                animate().cancel();
                animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start();
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().cancel();
                animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mView == null || mView.getControlView() == null) break;
                View v = mView.getControlView();
                float minPx = 22f * getResources().getDisplayMetrics().density;
                // Sizing deltas come from the finger, not from the handle's own
                // position — no self-referential jitter. Clamped so a control
                // cannot go below min size or off-screen.
                float newW = MathUtils.clamp(mStartW + (event.getRawX() - mDownRawX),
                        minPx, CallbackBridge.physicalWidth - v.getX());
                float newH = MathUtils.clamp(mStartH + (event.getRawY() - mDownRawY),
                        minPx, CallbackBridge.physicalHeight - v.getY());
                mView.getProperties().setWidth(newW);
                mView.getProperties().setHeight(newH);
                mView.regenerateDynamicCoordinates();
                break;
        }

        return true;
    }

    public void hideImmediately() {
        if (mView != null) {
            try {
                mView.getControlView().getViewTreeObserver().removeOnPreDrawListener(mPositionListener);
            } catch (Throwable ignored) {}
            mView = null;
        }
        animate().cancel();
        setVisibility(GONE);
        setScaleX(0f);
        setScaleY(0f);
        setAlpha(0f);
    }

    public void hide(){
        if(mView != null) {
            try {
                mView.getControlView().getViewTreeObserver().removeOnPreDrawListener(mPositionListener);
            } catch (Throwable ignored) {}
            mView = null;
        }
        if (getVisibility() != VISIBLE) {
            setVisibility(GONE);
            return;
        }
        animate().cancel();
        animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(140)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    setScaleX(0f);
                    setScaleY(0f);
                    setAlpha(0f);
                }).start();
    }
}
