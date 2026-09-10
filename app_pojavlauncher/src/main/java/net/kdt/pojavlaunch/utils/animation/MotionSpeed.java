package net.kdt.pojavlaunch.utils.animation;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * Global animation-speed multiplier (A9) — in-house concept
 * Launcher 2's `getAnimateSpeed()`/`launcherAnimateSpeed`, recreated
 * against CSL's own preference system (R4).
 *
 * Backed by the `launcher_animate_speed` slider (50%–200%, default 100%)
 * in Launcher Settings. Every UiMotion animation multiplies its base
 * duration by {@link #factor()}, so one setting re-times the whole UI
 * without touching any mechanics (R5).
 */
public final class MotionSpeed {

    public static final String PREF_KEY = "launcher_animate_speed";
    public static final String PREF_ANIMATIONS = "launcher_animations";
    private static final int PREF_DEFAULT = 100;
    private static final float MIN_FACTOR = 0.25f; // safety floor

    private MotionSpeed() { }

    /**
     * Master animation switch (user req): "launcher_animations" pref.
     * "off" → every UiMotion call becomes instant (no motion at all).
     */
    public static boolean isEnabled() {
        try {
            return !"off".equals(LauncherPreferences.DEFAULT_PREF.getString(PREF_ANIMATIONS, "full"));
        } catch (Throwable t) {
            return true;
        }
    }

    /** Speed factor: 1.0 = stock, 0.5 = 2× faster, 2.0 = 2× slower. */
    public static float factor() {
        int percent;
        try {
            percent = LauncherPreferences.DEFAULT_PREF.getInt(PREF_KEY, PREF_DEFAULT);
        } catch (Throwable t) {
            percent = PREF_DEFAULT;
        }
        float f = percent / 100f;
        return Math.max(f, MIN_FACTOR);
    }

    /** Scales a base duration by the user's global speed setting. */
    public static long scale(long baseMillis) {
        return Math.max(1L, Math.round(baseMillis * factor()));
    }

    /** Convenience for animator builders: duration as int. */
    public static int scaleInt(long baseMillis) {
        return (int) scale(baseMillis);
    }
}
