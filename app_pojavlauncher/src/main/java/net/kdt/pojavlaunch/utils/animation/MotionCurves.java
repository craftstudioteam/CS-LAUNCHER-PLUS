package net.kdt.pojavlaunch.utils.animation;

import android.animation.TimeInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import androidx.core.view.animation.PathInterpolatorCompat;

/**
 * The launcher's motion curves, in one place.
 *
 * <p>Three families, mirroring what a modern Android UI needs:
 * <ul>
 *   <li><b>Standard</b> — for small, functional changes (fades, colour, chips).</li>
 *   <li><b>Emphasized</b> — the hero curve: a slow start, a quick middle and a long, soft
 *       landing. This is what makes a screen transition feel expensive instead of linear.</li>
 *   <li><b>Expressive</b> — spring, jelly and bounce, for moments that should feel physical
 *       (buttons, cards, page changes when the user asks for them).</li>
 * </ul>
 *
 * <p>The curves match the XML ones in {@code res/interpolator}, so a view animated from Java and
 * a fragment animated from XML move identically.
 */
public final class MotionCurves {

    private MotionCurves() {}

    /** Small utility changes. */
    public static final Interpolator STANDARD =
            PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f);
    public static final Interpolator STANDARD_DECELERATE =
            PathInterpolatorCompat.create(0f, 0f, 0f, 1f);
    public static final Interpolator STANDARD_ACCELERATE =
            PathInterpolatorCompat.create(0.3f, 0f, 1f, 1f);

    /** Screen-level movement. */
    public static final Interpolator EMPHASIZED =
            PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f);
    public static final Interpolator EMPHASIZED_DECELERATE =
            PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f);
    public static final Interpolator EMPHASIZED_ACCELERATE =
            PathInterpolatorCompat.create(0.3f, 0f, 0.8f, 0.15f);

    /** Physical feedback. */
    public static final Interpolator SPRING =
            PathInterpolatorCompat.create(0.16f, 1.15f, 0.3f, 1f);
    public static final Interpolator LINEAR = new LinearInterpolator();
    public static final Interpolator ACCELERATE = new AccelerateInterpolator();
    public static final Interpolator BOUNCE = new BounceInterpolator();
    /** Damped-elastic landing; implements TimeInterpolator, which is all animators need. */
    public static final TimeInterpolator JELLY = new JellyBounceInterpolator();

    /**
     * A critically-damped spring, evaluated analytically so it can be used anywhere a
     * {@link TimeInterpolator} is accepted — no extra dependency, no animation loop of its own.
     *
     * @param damping  &lt;1 oscillates (bouncy), 1 lands without overshoot, &gt;1 is sluggish
     * @param stiffness how hard it is pulled towards the target (4–12 is a useful range)
     */
    public static TimeInterpolator spring(final float damping, final float stiffness) {
        final float d = Math.max(0.05f, damping);
        final float s = Math.max(1f, stiffness);
        return t -> {
            if (t >= 1f) return 1f;
            double envelope = Math.exp(-d * s * t);
            if (d < 1f) {
                double w = s * Math.sqrt(1f - d * d);
                return (float) (1 - envelope * (Math.cos(w * t) + (d * s / w) * Math.sin(w * t)));
            }
            return (float) (1 - envelope * (1 + s * t));
        };
    }

    /** The default expressive spring used by buttons and cards. */
    public static final TimeInterpolator SPRING_SOFT = spring(0.55f, 7f);
    /** A snappier spring for small elements (chips, icons). */
    public static final TimeInterpolator SPRING_SNAPPY = spring(0.72f, 9f);
}
