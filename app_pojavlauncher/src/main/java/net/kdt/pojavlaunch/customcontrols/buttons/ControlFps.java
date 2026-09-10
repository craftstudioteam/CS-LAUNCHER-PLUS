package net.kdt.pojavlaunch.customcontrols.buttons;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.utils.FpsCounter;
import net.kdt.pojavlaunch.utils.animation.MotionSpec;

/**
 * The canvas FPS read-out — a real control, not a floating label.
 *
 * <p>It is deliberately built on top of {@link ControlButton}, so everything the
 * editor already does for a control works here for free and nothing has to be
 * special-cased:
 * <ul>
 *   <li><b>persistence</b> — it is one {@link ControlData} inside
 *       {@code CustomControls.mControlDataList}, saved/exported/imported with the
 *       layout JSON like any button (the marker is
 *       {@link ControlData#SPECIALBTN_FPS}, no new schema field);</li>
 *   <li><b>move / resize</b> — the existing handle + action row drive it, and
 *       dynamic {@code ${...}} position equations apply unchanged;</li>
 *   <li><b>undo / redo</b> — {@code ControlLayout.pushUndoSnapshot()} already ran
 *       in {@code addControlButton};</li>
 *   <li><b>look</b> — opacity, background, stroke colour/width and corner radius
 *       are the real {@code ControlData} values, applied by
 *       {@code ControlInterface.setBackground()};</li>
 *   <li><b>visibility</b> — display-in-game / display-in-menu and hideability come
 *       from {@link ControlInterface#onGrabState}.</li>
 * </ul>
 *
 * <p>The number itself is never simulated: it is read from
 * {@link FpsCounter#getFps()}, the frame rate measured at the real swap boundary.
 * Its documented contract — {@code >0} a reading, {@code 0} warming up,
 * {@code -1} native bridge not linked yet — is mapped onto three visible states,
 * so the editor (where no game is presenting frames) honestly shows
 * {@code -- FPS} instead of a made-up figure.
 */
public class ControlFps extends ControlButton {

    /** Matches the launch HUD's cadence: live, but never jittery. */
    private static final long TICK_INTERVAL_MS = 400L;
    /** Presentation-only smoothing of real samples (no value is invented). */
    private static final float SMOOTHING = 0.45f;

    private int mLastSample = -1;
    private int mShownValue = -1;
    private int mState = STATE_EMPTY;

    private static final int STATE_EMPTY = 0;     // no bridge yet  -> "--"
    private static final int STATE_WARMING = 1;   // 0 presented     -> "0"
    private static final int STATE_LIVE = 2;      // real reading    -> "123"

    private final Paint mPulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mPulseAlpha = 0;
    private ValueAnimator mPulseAnimator;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            sample();
            removeCallbacks(this);
            postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    public ControlFps(ControlLayout layout, ControlData properties) {
        super(layout, properties);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setIncludeFontPadding(false);
        setTextColor(0xFFF2F3F5);
        // A counter changes width every tick; monospace digits keep the chip from
        // breathing left and right.
        setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mPulsePaint.setColor(0xFFFFFFFF);
        render();
    }

    // ───────────────────────── the read-out never presses a key ─────────────

    @Override
    public void sendKeyPresses(boolean isDown) {
        // FPS is a report, not an input: no key and no special action is sent.
    }

    @Override
    public boolean triggerToggle() {
        return false;
    }

    // ─────────────────────────────── lifecycle ──────────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(mTicker);
        postDelayed(mTicker, TICK_INTERVAL_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mTicker);
        cancelPulse();
        super.onDetachedFromWindow();
    }

    @Override
    public void setProperties(ControlData properties, boolean changePos) {
        super.setProperties(properties, changePos);
        render();
    }

    @Override
    protected void onVisibilityChanged(android.view.View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            removeCallbacks(mTicker);
            postDelayed(mTicker, 0L);
        } else {
            removeCallbacks(mTicker);
        }
    }

    // ─────────────────────────────── sampling ───────────────────────────────

    private void sample() {
        int raw = FpsCounter.getFps();
        int state;
        int value;
        if (raw > 0) {
            state = STATE_LIVE;
            value = mLastSample < 0 || mState != STATE_LIVE
                    ? raw
                    : (int) Math.round(mLastSample * (1f - SMOOTHING) + raw * SMOOTHING);
        } else if (raw == 0) {
            // frames not presented yet — show the truth, not a placeholder number
            state = STATE_WARMING;
            value = 0;
        } else {
            state = STATE_EMPTY;
            value = -1;
        }
        int previous = mShownValue;
        boolean changed = state != mState || value != mShownValue;
        // Only a real move deserves a highlight: a chip that flickers on every
        // tick would be more interesting to look at than the number itself.
        boolean worthHighlighting = state == STATE_LIVE
                && (mState != STATE_LIVE || Math.abs(value - previous) >= 3);
        mLastSample = state == STATE_LIVE ? value : -1;
        mState = state;
        mShownValue = value;
        if (changed) {
            render();
            if (worthHighlighting) pulse();
        }
    }

    /** "123 FPS" — the number leads, the unit is quiet; "-- FPS" when idle. */
    private void render() {
        String number = mShownValue > 0 ? String.valueOf(mShownValue)
                : mState == STATE_WARMING ? "0" : "--";
        ControlData props = getProperties();
        boolean showUnit = props == null || props.fpsShowUnit;
        applyLook(props);
        if (!showUnit) {
            setText(number);
            return;
        }
        String text = number + " FPS";
        SpannableString out = new SpannableString(text);
        int unitStart = number.length();
        out.setSpan(new RelativeSizeSpan(0.66f), unitStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(0x9AF2F3F5), unitStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.NORMAL), unitStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText(out);
    }

    /**
     * Phase 9 — per-control look. Text size follows {@code fpsTextSize} (sp),
     * or auto-fits the control height when 0. A transparent background
     * (bgColor alpha 0, no stroke — set from the editor's toggle) gets a soft
     * text shadow so the number stays readable over any world.
     */
    private void applyLook(ControlData props) {
        if (props == null) return;
        float sp = props.fpsTextSize;
        if (sp <= 0f) {
            float d = getResources().getDisplayMetrics().density;
            float hPx = props.getHeight();
            sp = Math.max(9f, Math.min(28f, (hPx / d) * 0.42f));
        }
        if (Math.abs(getTextSize() / getResources().getDisplayMetrics().scaledDensity - sp) > 0.4f) {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        }
        boolean transparent = ((props.bgColor >>> 24) == 0) && props.strokeWidth <= 0f;
        if (transparent) setShadowLayer(6f, 0f, 1f, 0xCC000000);
        else setShadowLayer(0f, 0f, 0f, 0);
    }

    // ─────────────────────────────── motion ─────────────────────────────────

    /**
     * A short sheen across the chip whenever the value moves. It paints over the
     * control instead of touching alpha, because {@code ControlLayout} owns the
     * view alpha for the user's opacity setting.
     */
    private void pulse() {
        if (!MotionSpec.enabled()) return;
        cancelPulse();
        mPulseAnimator = ValueAnimator.ofInt(26, 0);
        mPulseAnimator.setDuration(380L);
        mPulseAnimator.addUpdateListener(animation -> {
            mPulseAlpha = (int) animation.getAnimatedValue();
            invalidate();
        });
        mPulseAnimator.start();
    }

    private void cancelPulse() {
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
            mPulseAnimator = null;
        }
        mPulseAlpha = 0;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mPulseAlpha <= 0) return;
        mPulsePaint.setAlpha(mPulseAlpha);
        float radius = computeCornerRadius(getProperties().cornerRadius);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, mPulsePaint);
        // one brighter hairline along the top edge, so the flash reads as glass
        mPulsePaint.setAlpha(Math.min(255, mPulseAlpha * 3));
        float edge = 1.6f * getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(0, 0, getWidth(), edge * 2f, radius, radius, mPulsePaint);
    }
}
