package net.kdt.pojavlaunch.customcontrols.buttons;

import static net.kdt.pojavlaunch.LwjglGlfwKeycode.GLFW_KEY_UNKNOWN;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.commands.ChatCommandEngine;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import org.lwjgl.glfw.CallbackBridge;

@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
public class ControlButton extends TextView implements ControlInterface {
    private final Paint mRectPaint = new Paint();
    protected ControlData mProperties;
    private final ControlLayout mControlLayout;

    /* Cache value from the ControlData radius for drawing purposes */
    private float mComputedRadius;

    protected boolean mIsToggled = false;
    protected boolean mIsPointerOutOfBounds = false;

    /* ── Dynamic Button Asset System (foreground icon pass) ─────────── */
    private Bitmap mIconBitmap;
    private pl.droidsonroids.gif.GifDrawable mGifDrawable;
    private final Paint mIconPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF mIconDst = new RectF();
    /** fitCenter auto-padding: fraction of the button kept clear around the icon. */
    private static final float ICON_INSET = 0.14f;

    public ControlButton(ControlLayout layout, ControlData properties) {
        super(layout.getContext());
        mControlLayout = layout;
        setGravity(Gravity.CENTER);
        setAllCaps(LauncherPreferences.PREF_BUTTON_ALL_CAPS);
        setTextColor(Color.WHITE);
        setPadding(4, 4, 4, 4);
        setTextSize(14); // Nullify the default size setting
        setOutlineProvider(null); // Disable shadow casting, removing one drawing pass

        //setOnLongClickListener(this);

        //When a button is created, the width/height has yet to be processed to fit the scaling.
        setProperties(preProcessProperties(properties, layout));

        injectBehaviors();
    }

    @Override
    public View getControlView() {return this;}

    public ControlData getProperties() {
        return mProperties;
    }

    public void setProperties(ControlData properties, boolean changePos) {
        mProperties = properties;
        ControlInterface.super.setProperties(properties, changePos);
        mComputedRadius = ControlInterface.super.computeCornerRadius(mProperties.cornerRadius);

        if (mProperties.isToggle) {
            //For the toggle layer
            final TypedValue value = new TypedValue();
            getContext().getTheme().resolveAttribute(R.attr.colorAccent, value, true);
            mRectPaint.setColor(value.data);
            mRectPaint.setAlpha(128);
        } else {
            mRectPaint.setColor(Color.WHITE);
            mRectPaint.setAlpha(60);
        }

        applyCustomIcon(properties);
    }

    private void applyCustomIcon(ControlData properties) {
        setCompoundDrawables(null, null, null, null);
        setText(properties.name);
        releaseIconState();
        if (!properties.hasIcon()) { invalidate(); return; }
        // Foreground icon replaces the label — background layer stays intact.
        setText("");
        if (ControlIconStore.isGif(properties)) {
            try {
                mGifDrawable = new pl.droidsonroids.gif.GifDrawable(properties.iconPath);
                mGifDrawable.setCallback(this);
                if (getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE) mGifDrawable.start();
            } catch (Throwable ignored) { mGifDrawable = null; }
        } else {
            requestIconBitmap();
        }
        invalidate();
    }

    /** Ask the shared store for the icon at this button's pixel bounds. */
    private void requestIconBitmap() {
        if (mProperties == null || !mProperties.hasIcon() || ControlIconStore.isGif(mProperties)) return;
        int w = getWidth() > 0 ? getWidth() : Math.max(1, Math.round(Tools.dpToPx(mProperties.getWidth())));
        int h = getHeight() > 0 ? getHeight() : Math.max(1, Math.round(Tools.dpToPx(mProperties.getHeight())));
        mIconBitmap = ControlIconStore.getIcon(getContext(), mProperties, w, h, () -> {
            // Decoded async — pick it out of the cache and repaint.
            mIconBitmap = ControlIconStore.getIcon(getContext(), mProperties, w, h, null);
            invalidate();
        });
    }

    private void releaseIconState() {
        mIconBitmap = null;
        if (mGifDrawable != null) {
            try { mGifDrawable.stop(); mGifDrawable.recycle(); } catch (Throwable ignored) {}
            mGifDrawable = null;
        }
    }

    /** fitCenter destination box, aspect-preserving, with auto padding. */
    private void computeIconDst(float srcW, float srcH) {
        float availW = getWidth() * (1f - 2f * ICON_INSET);
        float availH = getHeight() * (1f - 2f * ICON_INSET);
        float scale = Math.min(availW / srcW, availH / srcH);
        float w = srcW * scale, h = srcH * scale;
        float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f;
        mIconDst.set(l, t, l + w, t + h);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mGifDrawable || super.verifyDrawable(who);
    }

    @Override
    public void invalidateDrawable(Drawable drawable) {
        if (drawable == mGifDrawable) invalidate();
        else super.invalidateDrawable(drawable);
    }

    /** Performance guardrail: GIF playback halts whenever the control layer hides. */
    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (mGifDrawable == null) return;
        if (visibility == VISIBLE) { if (!mGifDrawable.isRunning()) mGifDrawable.start(); }
        else if (mGifDrawable.isRunning()) mGifDrawable.stop();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (mGifDrawable == null) return;
        if (visibility == VISIBLE) { if (!mGifDrawable.isRunning()) mGifDrawable.start(); }
        else if (mGifDrawable.isRunning()) mGifDrawable.stop();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mGifDrawable != null && mGifDrawable.isRunning()) mGifDrawable.stop();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Re-request at the new pixel bounds so the cache serves the right size.
        if (w > 0 && h > 0 && mProperties != null && mProperties.hasIcon()
                && !ControlIconStore.isGif(mProperties)) requestIconBitmap();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // ── Foreground icon pass (fitCenter, aspect ratio kept) ──
        if (mGifDrawable != null) {
            computeIconDst(mGifDrawable.getIntrinsicWidth(), mGifDrawable.getIntrinsicHeight());
            mGifDrawable.setBounds((int) mIconDst.left, (int) mIconDst.top,
                    (int) mIconDst.right, (int) mIconDst.bottom);
            if (mProperties.iconTint != 0)
                mGifDrawable.setColorFilter(new PorterDuffColorFilter(mProperties.iconTint, PorterDuff.Mode.SRC_IN));
            else mGifDrawable.setColorFilter(null);
            mGifDrawable.draw(canvas);
        } else if (mProperties != null && mProperties.hasIcon()) {
            if (mIconBitmap == null) requestIconBitmap();
            if (mIconBitmap != null && !mIconBitmap.isRecycled()) {
                computeIconDst(mIconBitmap.getWidth(), mIconBitmap.getHeight());
                mIconPaint.setColorFilter(mProperties.iconTint != 0
                        ? new PorterDuffColorFilter(mProperties.iconTint, PorterDuff.Mode.SRC_IN) : null);
                canvas.drawBitmap(mIconBitmap, null, mIconDst, mIconPaint);
            }
        }

        if (mIsToggled || (!mProperties.isToggle && isActivated()))
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), mComputedRadius, mComputedRadius, mRectPaint);
    }


    public void loadEditValues(EditControlSideDialog editControlPopup){
        editControlPopup.loadValues(getProperties());
    }

    /** Add another instance of the ControlButton to the parent layout */
    public void cloneButton(){
        ControlData cloneData = new ControlData(getProperties());
        cloneData.dynamicX = "0.5 * ${screen_width}";
        cloneData.dynamicY = "0.5 * ${screen_height}";
        ((ControlLayout) getParent()).addControlButton(cloneData);
    }

    /** Remove any trace of this button from the layout */
    public void removeButton() {
        getControlLayoutParent().getLayout().mControlDataList.remove(getProperties());
        getControlLayoutParent().removeView(this);
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()){
            case MotionEvent.ACTION_MOVE:
                //Send the event to be taken as a mouse action
                if(getProperties().passThruEnabled && CallbackBridge.isGrabbing()){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }

                //If out of bounds
                if(event.getX() < getControlView().getLeft() || event.getX() > getControlView().getRight() ||
                        event.getY() < getControlView().getTop()  || event.getY() > getControlView().getBottom()){
                    if(getProperties().isSwipeable && !mIsPointerOutOfBounds){
                        //Remove keys
                        if(!triggerToggle()) {
                            sendKeyPresses(false);
                        }
                    }
                    mIsPointerOutOfBounds = true;
                    getControlLayoutParent().onTouch(this, event);
                    break;
                }

                //Else if we now are in bounds
                if(mIsPointerOutOfBounds) {
                    getControlLayoutParent().onTouch(this, event);
                    //RE-press the button
                    if(getProperties().isSwipeable && !getProperties().isToggle){
                        sendKeyPresses(true);
                    }
                }
                mIsPointerOutOfBounds = false;
                break;

            case MotionEvent.ACTION_DOWN: // 0
            case MotionEvent.ACTION_POINTER_DOWN: // 5
                if(!getProperties().isToggle){
                    sendKeyPresses(true);
                }
                break;

            case MotionEvent.ACTION_UP: // 1
            case MotionEvent.ACTION_CANCEL: // 3
            case MotionEvent.ACTION_POINTER_UP: // 6
                if(getProperties().passThruEnabled){
                    View gameSurface = getControlLayoutParent().getGameSurface();
                    if(gameSurface != null) gameSurface.dispatchTouchEvent(event);
                }
                if(mIsPointerOutOfBounds) getControlLayoutParent().onTouch(this, event);
                mIsPointerOutOfBounds = false;

                if(!triggerToggle()) {
                    sendKeyPresses(false);
                }
                break;

            default:
                return false;
        }

        return super.onTouchEvent(event);
    }



    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean triggerToggle(){
        //returns true a the toggle system is triggered
        if(mProperties.isToggle){
            mIsToggled = !mIsToggled;
            invalidate();
            sendKeyPresses(mIsToggled);
            return true;
        }
        return false;
    }

    public void sendKeyPresses(boolean isDown){
        setActivated(isDown);
        for(int keycode : mProperties.keycodes){
            if(keycode >= GLFW_KEY_UNKNOWN){
                sendKeyPress(keycode, EfficientAndroidLWJGLKeycode.getLwjglChar(keycode), CallbackBridge.getCurrentMods(), isDown);
                CallbackBridge.setModifiers(keycode, isDown);
            }else{
                Log.i("punjabilauncher", "sendSpecialKey("+keycode+","+isDown+")");
                sendSpecialKey(keycode, isDown);
            }
        }
    }

    private void sendSpecialKey(int keycode, boolean isDown){
        switch (keycode) {
            case ControlData.SPECIALBTN_KEYBOARD:
                if(isDown) MainActivity.switchKeyboardState();
                break;

            case ControlData.SPECIALBTN_TOGGLECTRL:
                if(isDown)getControlLayoutParent().toggleControlVisible();
                break;

            case ControlData.SPECIALBTN_VIRTUALMOUSE:
                if(isDown) MainActivity.toggleMouse(getContext());
                break;

            case ControlData.SPECIALBTN_MOUSEPRI:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSEMID:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE, isDown);
                break;

            case ControlData.SPECIALBTN_MOUSESEC:
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, isDown);
                break;

            case ControlData.SPECIALBTN_SCROLLDOWN:
                if (!isDown) CallbackBridge.sendScroll(0, 1d);
                break;

            case ControlData.SPECIALBTN_SCROLLUP:
                if (!isDown) CallbackBridge.sendScroll(0, -1d);
                break;
            case ControlData.SPECIALBTN_MENU:
                mControlLayout.notifyAppMenu();
                break;
            case ControlData.SPECIALBTN_CHATCOMMAND:
                if (isDown) sendChatCommand(mProperties.command);
                break;
            case ControlData.SPECIALBTN_FPS:
                // Read-only control: ControlFps paints it, a press does nothing.
                break;
        }
    }

    /**
     * Opens chat/command input, waits a frame for the UI to actually appear,
     * types the payload, then submits it. This avoids the old behaviour where
     * commands only worked when chat was already open or chat stayed stuck.
     */
    private void sendChatCommand(String command) {
        if (command == null) return;
        final String raw = command.trim();
        if (raw.isEmpty()) return;

        // Phase 3 Command Studio: the same old field can now carry a
        // multi-step script (multiple commands, delays, variables, conditions,
        // repeat:) while single-line legacy commands behave exactly as before.
        ChatCommandEngine.execute(getContext(), raw, chatLine -> {
            final boolean useCommandBar = chatLine.startsWith("/");
            final String payload = useCommandBar ? chatLine.substring(1) : chatLine;
            final int openerKey = useCommandBar
                    ? LwjglGlfwKeycode.GLFW_KEY_SLASH
                    : LwjglGlfwKeycode.GLFW_KEY_T;

            CallbackBridge.sendKeyPress(openerKey);
            Tools.MAIN_HANDLER.postDelayed(() -> {
                for (int i = 0; i < payload.length(); i++) {
                    CallbackBridge.sendChar(payload.charAt(i), 0);
                }
                Tools.MAIN_HANDLER.postDelayed(
                        () -> CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ENTER),
                        28
                );
            }, 42);
        });
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
