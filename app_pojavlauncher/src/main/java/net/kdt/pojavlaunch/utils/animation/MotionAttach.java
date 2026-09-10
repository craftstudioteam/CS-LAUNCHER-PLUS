package net.kdt.pojavlaunch.utils.animation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.SeekBar;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Gives every tappable thing in the launcher the same physical response.
 *
 * <p>Instead of each screen inventing its own {@code animate().scaleX(0.94f)} snippet — which is
 * how the UI ended up with a dozen slightly different press feelings — a screen calls
 * {@link #attachAll(View)} once and every button, card and row underneath it reacts identically:
 * it sinks slightly and dims under the finger, then springs back with a soft overshoot.
 *
 * <p>Details that make it feel right rather than gimmicky:
 * <ul>
 *   <li>the press-in is instant (a UI must never feel laggy under the finger) and only the
 *       release is animated;</li>
 *   <li>dragging the finger off the view cancels the press, exactly like a real button;</li>
 *   <li>scroll containers, text fields and sliders are skipped, so gestures are not disturbed;</li>
 *   <li>views already carrying their own touch listener are left alone;</li>
 *   <li>everything honours the master animation switch and the global speed setting.</li>
 * </ul>
 */
public final class MotionAttach {

    private static final float PRESS_SCALE = 0.92f;
    private static final float PRESS_ALPHA = 0.78f;
    /** Max lean of a tilted card (reactbits TiltCard), in degrees. */
    private static final float TILT_MAX_DEG = 5f;

    private MotionAttach() {}

    /** Applies press feedback to a single view. */
    @SuppressLint("ClickableViewAccessibility")
    public static void press(View view) {
        if (view == null || !MotionSpec.enabled()) return;
        if (Boolean.TRUE.equals(view.getTag(net.kdt.pojavlaunch.R.id.motion_press_tag))) return;
        view.setTag(net.kdt.pojavlaunch.R.id.motion_press_tag, Boolean.TRUE);

        view.setOnTouchListener(new View.OnTouchListener() {
            private final Rect bounds = new Rect();
            private boolean inside;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        inside = true;
                        v.animate().cancel();
                        // No animation on the way in: the response must be immediate.
                        v.setScaleX(PRESS_SCALE);
                        v.setScaleY(PRESS_SCALE);
                        v.setAlpha(PRESS_ALPHA);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        v.getDrawingRect(bounds);
                        boolean nowInside = bounds.contains((int) event.getX(), (int) event.getY());
                        if (nowInside != inside) {
                            inside = nowInside;
                            if (!inside) release(v);
                            else { v.setScaleX(PRESS_SCALE); v.setScaleY(PRESS_SCALE); v.setAlpha(PRESS_ALPHA); }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        release(v);
                        break;
                    default:
                        break;
                }
                return false; // never swallow the event — clicks must still work
            }
        });
    }

    private static void release(View v) {
        // The release is the juice: a damped-elastic bounce makes every tap feel
        // alive instead of just snapping back to scale 1.
        v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(MotionSpeed.scale(480L))
                .setInterpolator(MotionCurves.JELLY)
                .start();
    }

    /** Walks a screen and gives every clickable child the shared press feedback. */
    public static void attachAll(View root) {
        if (root == null || !MotionSpec.enabled()) return;
        walk(root);
    }

    private static void walk(View view) {
        if (skip(view)) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // A clickable container (a card row) gets the feedback itself, not its children.
            if (isButtonLike(group)) { press(group); return; }
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i));
            return;
        }
        if (isButtonLike(view)) press(view);
    }

    /**
     * Only views that actually have a click handler are touched. Anything with its own gesture
     * handling (drag handles, custom canvases) has no click listener, so it is left alone and its
     * gestures keep working.
     */
    private static boolean isButtonLike(View view) {
        return view.isClickable() && view.hasOnClickListeners();
    }

    private static boolean skip(View view) {
        return view instanceof RecyclerView
                || view instanceof AbsListView
                || view instanceof SeekBar
                || view instanceof EditText
                || view instanceof android.widget.ScrollView
                || view instanceof android.widget.HorizontalScrollView
                || view instanceof androidx.core.widget.NestedScrollView
                || view instanceof androidx.viewpager2.widget.ViewPager2;
    }

    /** Adds the platform ripple to a view that has no foreground of its own. */
    public static void ripple(View view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (view.getForeground() != null) return;
        Context context = view.getContext();
        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true)) {
            view.setForeground(context.getDrawable(outValue.resourceId));
        }
    }

    /**
     * press + 3D tilt in ONE touch listener (reactbits "TiltCard"): the card
     * squashes on touch-down like a button, leans toward the finger while it
     * moves across the card, and jelly-settles flat on release. A single
     * listener is required — two OnTouchListeners would overwrite each other.
     */
    @SuppressLint("ClickableViewAccessibility")
    public static void pressTilt(View view) {
        if (view == null || !MotionSpec.enabled()) return;
        if (Boolean.TRUE.equals(view.getTag(net.kdt.pojavlaunch.R.id.motion_press_tag))) return;
        view.setTag(net.kdt.pojavlaunch.R.id.motion_press_tag, Boolean.TRUE);
        float density = view.getResources().getDisplayMetrics().density;
        view.setCameraDistance(6500f * density);

        view.setOnTouchListener(new View.OnTouchListener() {
            private final Rect bounds = new Rect();
            private boolean inside;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        inside = true;
                        v.animate().cancel();
                        v.setScaleX(PRESS_SCALE);
                        v.setScaleY(PRESS_SCALE);
                        v.setAlpha(PRESS_ALPHA);
                        applyTilt(v, event);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        v.getDrawingRect(bounds);
                        boolean nowInside = bounds.contains((int) event.getX(), (int) event.getY());
                        if (nowInside != inside) {
                            inside = nowInside;
                            if (!inside) releaseTilt(v);
                            else {
                                v.setScaleX(PRESS_SCALE);
                                v.setScaleY(PRESS_SCALE);
                                v.setAlpha(PRESS_ALPHA);
                            }
                        }
                        if (inside) applyTilt(v, event);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        releaseTilt(v);
                        break;
                    default:
                        break;
                }
                return false; // clicks must still work
            }
        });
    }

    /** Leans the card toward the touch point, clamped to a tasteful range. */
    private static void applyTilt(View v, MotionEvent e) {
        float w = Math.max(1f, v.getWidth());
        float h = Math.max(1f, v.getHeight());
        float dx = (e.getX() / w) - 0.5f;
        float dy = (e.getY() / h) - 0.5f;
        float rotY = Math.max(-TILT_MAX_DEG, Math.min(TILT_MAX_DEG, dx * 2f * TILT_MAX_DEG));
        float rotX = Math.max(-TILT_MAX_DEG, Math.min(TILT_MAX_DEG, -dy * 2f * TILT_MAX_DEG));
        v.setRotationX(rotX);
        v.setRotationY(rotY);
    }

    /** Card release: jelly back to identity scale/alpha and spring the tilt flat. */
    private static void releaseTilt(View v) {
        v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .rotationX(0f).rotationY(0f)
                .setDuration(MotionSpeed.scale(500L))
                .setInterpolator(MotionCurves.JELLY)
                .start();
    }
}
