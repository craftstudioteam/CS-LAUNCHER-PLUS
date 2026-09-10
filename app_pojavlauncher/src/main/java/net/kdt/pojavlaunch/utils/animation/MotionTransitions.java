package net.kdt.pojavlaunch.utils.animation;

import androidx.fragment.app.FragmentTransaction;

import net.kdt.pojavlaunch.R;

/**
 * Page transitions for the whole launcher.
 *
 * <p>Every screen change goes through here instead of naming animation resources by hand, so the
 * navigation feels like one product and the user's chosen style applies everywhere at once.
 *
 * <p>The styles follow the two directions a launcher actually navigates in:
 * <ul>
 *   <li><b>Slide</b> — lateral movement (shared axis X): the new page enters from the side while
 *       the old one drifts the other way. The default, because it reads as "next / back".</li>
 *   <li><b>Zoom</b> — hierarchy (shared axis Z): going deeper grows into the screen, going back
 *       shrinks away. Best for opening an item from a list.</li>
 *   <li><b>Jelly</b> and <b>Bounce</b> — expressive landings for people who like the motion to
 *       be part of the personality.</li>
 *   <li><b>Fade</b> — the quiet option, and <b>off</b> for no motion at all.</li>
 * </ul>
 */
public final class MotionTransitions {

    private MotionTransitions() {}

    /** enter, exit, popEnter, popExit for the current style. */
    public static int[] current() {
        switch (MotionSpec.transitionStyle()) {
            case MotionSpec.STYLE_OFF:
                return new int[]{R.anim.motion_none_enter, R.anim.motion_none_exit,
                        R.anim.motion_none_pop_enter, R.anim.motion_none_pop_exit};
            case MotionSpec.STYLE_FADE:
                return new int[]{R.anim.motion_fade_enter, R.anim.motion_fade_exit,
                        R.anim.motion_fade_pop_enter, R.anim.motion_fade_pop_exit};
            case MotionSpec.STYLE_ZOOM:
                return new int[]{R.anim.motion_zoom_enter, R.anim.motion_zoom_exit,
                        R.anim.motion_zoom_pop_enter, R.anim.motion_zoom_pop_exit};
            case MotionSpec.STYLE_JELLY:
                return new int[]{R.anim.motion_jelly_enter, R.anim.motion_jelly_exit,
                        R.anim.motion_jelly_pop_enter, R.anim.motion_jelly_pop_exit};
            case MotionSpec.STYLE_BOUNCE:
                return new int[]{R.anim.motion_bounce_enter, R.anim.motion_bounce_exit,
                        R.anim.motion_bounce_pop_enter, R.anim.motion_bounce_pop_exit};
            case MotionSpec.STYLE_SLIDE:
            default:
                return new int[]{R.anim.motion_slide_enter, R.anim.motion_slide_exit,
                        R.anim.motion_slide_pop_enter, R.anim.motion_slide_pop_exit};
        }
    }

    /** Applies the user's transition to a fragment transaction. */
    public static FragmentTransaction apply(FragmentTransaction transaction) {
        int[] a = current();
        return transaction.setCustomAnimations(a[0], a[1], a[2], a[3]);
    }

    /**
     * Hierarchy transition (opening a detail screen from a list). Uses zoom unless the user picked
     * something expressive, in which case their choice wins — their setting, their launcher.
     */
    public static FragmentTransaction applyDeepDive(FragmentTransaction transaction) {
        String style = MotionSpec.transitionStyle();
        if (MotionSpec.STYLE_SLIDE.equals(style)) {
            return transaction.setCustomAnimations(R.anim.motion_zoom_enter, R.anim.motion_zoom_exit,
                    R.anim.motion_zoom_pop_enter, R.anim.motion_zoom_pop_exit);
        }
        return apply(transaction);
    }
}
