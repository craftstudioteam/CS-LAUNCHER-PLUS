package net.kdt.pojavlaunch.utils.animation;

import android.animation.TimeInterpolator;

/**
 * Jelly bounce easing — modelled on modern launcher
 * `_Easing.kt` (JellyBounce), recreated here as a plain Android
 * TimeInterpolator (no UI copied, R4).
 *
 * Damped-cosine overshoot model:
 *     1 - 0.6 * e^(-8t) * cos(6πt)
 * Amplitude A = 0.6 (60% elasticity), exponential decay e^(-8t),
 * cosine term gives ~3 full oscillation periods. Result overshoots
 * slightly around 1.0 and settles at exactly 1.0.
 *
 * Use for icon/success/check "pop" moments, not for page-level motion.
 */
public final class JellyBounceInterpolator implements TimeInterpolator {

    private static final float AMPLITUDE = 0.6f;
    private static final float DECAY = 8f;
    private static final float FREQUENCY = (float) (6.0 * Math.PI);

    @Override
    public float getInterpolation(float t) {
        // Clamp input defensively (animators may overshoot [0,1] with fractions).
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return 1f - AMPLITUDE * (float) Math.exp(-DECAY * t) * (float) Math.cos(FREQUENCY * t);
    }
}
