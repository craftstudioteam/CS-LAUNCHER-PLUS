package net.kdt.pojavlaunch.utils.animation;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * The launcher's motion specification: how long things take and how far they travel.
 *
 * <p>Everything animated in the app asks this class instead of hard-coding numbers, so the three
 * user settings below re-time and re-scale the entire UI consistently:
 * <ul>
 *   <li>{@code launcher_animations} — master switch (full / off)</li>
 *   <li>{@code launcher_animate_speed} — 50–200 % speed</li>
 *   <li>{@code launcher_animate_amplitude} — 0–10, how far elements travel (5 = normal)</li>
 * </ul>
 */
public final class MotionSpec {

    public static final String PREF_AMPLITUDE = "launcher_animate_amplitude";
    public static final String PREF_TRANSITION = "launcher_transition_style";

    /** Base durations, before the speed setting is applied. */
    public static final long SHORT = 180L;
    public static final long MEDIUM = 300L;
    public static final long LONG = 450L;
    public static final long EXTRA_LONG = 600L;

    private MotionSpec() {}

    public static boolean enabled() {
        return MotionSpeed.isEnabled();
    }

    // ── durations ──
    public static long shortMs()     { return MotionSpeed.scale(SHORT); }
    public static long mediumMs()    { return MotionSpeed.scale(MEDIUM); }
    public static long longMs()      { return MotionSpeed.scale(LONG); }
    public static long extraLongMs() { return MotionSpeed.scale(EXTRA_LONG); }

    /** Stagger between consecutive items in a list or grid. */
    public static long staggerMs(int index) {
        long step = MotionSpeed.scale(34L);
        // Cap the cascade: a 40-item list must not take two seconds to appear.
        return Math.min(step * index, MotionSpeed.scale(320L));
    }

    // ── travel distance ──

    /**
     * Amplitude, matching the 0–10 scale used by the settings slider:
     * 0 → half the distance, 5 → the designed distance, 10 → 1.5×.
     */
    public static float amplitude() {
        int value;
        try {
            value = LauncherPreferences.DEFAULT_PREF.getInt(PREF_AMPLITUDE, 5);
        } catch (Throwable t) {
            value = 5;
        }
        value = Math.max(0, Math.min(10, value));
        return 0.5f + (value / 10f);
    }

    /** Scales a designed travel distance (px) by the amplitude setting. */
    public static float travel(float basePx) {
        return basePx * amplitude();
    }

    // ── transition style ──

    public static final String STYLE_SLIDE = "slide";
    public static final String STYLE_ZOOM = "zoom";
    public static final String STYLE_JELLY = "jelly";
    public static final String STYLE_BOUNCE = "bounce";
    public static final String STYLE_FADE = "fade";
    public static final String STYLE_OFF = "off";

    /** The page-transition personality chosen by the user. */
    public static String transitionStyle() {
        if (!enabled()) return STYLE_OFF;
        try {
            String style = LauncherPreferences.DEFAULT_PREF.getString(PREF_TRANSITION, STYLE_SLIDE);
            return style == null ? STYLE_SLIDE : style;
        } catch (Throwable t) {
            return STYLE_SLIDE;
        }
    }
}
