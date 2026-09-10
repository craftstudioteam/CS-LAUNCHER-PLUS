package net.kdt.pojavlaunch.utils.animation;

import android.animation.TimeInterpolator;

/**
 * Four-phase bounce easing — modelled on modern launcher
 * `_Easing.kt` (BounceEasing), recreated in plain Android (R4).
 *
 * Phase-stretched quadratic bounce (mirrors the classic Android
 * BounceInterpolator shape but with slightly different phase cutoffs):
 *   phase 1: bounce(x) = 8x²                      x < 0.3535
 *   phase 2: bounce(x - 0.54719) + 0.7            x < 0.7408
 *   phase 3: bounce(x - 0.8526) + 0.9             x < 0.9644
 *   phase 4: bounce(x - 1.0435) + 0.95            else
 * where bounce(y) = 8y².
 */
public final class BounceInterpolator implements TimeInterpolator {

    private static float bounce(float x) {
        return x * x * 8.0f;
    }

    @Override
    public float getInterpolation(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;

        float input = t * 1.1226f;
        if (input < 0.3535f) return bounce(input);
        if (input < 0.7408f) return bounce(input - 0.54719f) + 0.7f;
        if (input < 0.9644f) return bounce(input - 0.8526f) + 0.9f;
        return bounce(input - 1.0435f) + 0.95f;
    }
}
