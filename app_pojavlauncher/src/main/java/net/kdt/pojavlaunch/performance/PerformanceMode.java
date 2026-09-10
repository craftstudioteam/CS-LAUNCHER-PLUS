package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The three performance profiles the user can pick, and the only persisted input the
 * {@link PerformancePolicy} takes.
 *
 * <p>A mode is a <i>request</i>, not a promise: what actually happens on this phone is decided by
 * {@link PerformancePolicy#resolve}, which folds in {@link DeviceCapability} and the APIs the
 * running Android version exposes. A device without ADPF, or without a thermal HAL, gets the same
 * mode with fewer levers — and the settings card says so instead of pretending.
 *
 * <p>Normal is the default on purpose. It is the only mode that requests nothing from the
 * scheduler, touches no JVM flag, and starts no periodic work: the launcher behaves the way it
 * did before a performance policy existed. It is <i>not</i> a capped mode — no profile in this list
 * may impose a frame-rate ceiling, because a ceiling is a fidelity change the player did not ask for.
 */
public enum PerformanceMode {

    /** Stability, temperature and battery first. No aggressive requests of any kind. */
    NORMAL("normal",
            "Balanced performance and device temperature",
            "Scheduler left alone · JVM at its own defaults · background work normal · "
                    + "no frame-rate cap, no display-mode change and no resolution change: the game is "
                    + "paced only by Minecraft's own VSync and FPS settings"),

    /** Noticeably higher FPS, at moderate heat. Safe Android APIs only. */
    PERFORMANCE("performance",
            "Higher FPS with increased CPU/GPU usage and moderate heat",
            "Game threads raised to display priority · short GC pauses · launcher goes quiet "
                    + "while Minecraft is on screen · no sustained-clock cap and no frame-rate limit"),

    /** Everything the device can hold, until the thermal API says stop. */
    MAXIMUM("maximum",
            "Maximum safe performance the device will give, for the highest sustainable FPS",
            "Nothing held back: no FPS cap, no vsync, no sustained-clock request, no resolution or "
                    + "graphics change · game threads at urgent display priority · ADPF hint session "
                    + "(Android 12+) targeting the display's highest refresh rate · JVM tuned per device · "
                    + "launcher fully quiet · the only limiter left is the device's own thermal report, "
                    + "which sheds boost when it says severe throttling");

    /** Preference key. One key, one decision — nothing else may store a mode. */
    public static final String PREF_KEY = "perfMode";

    /**
     * Acknowledgement flag for {@link #MAXIMUM}. The warning about heat and battery is shown once
     * per install; after that the mode is still freely selectable from its own card.
     */
    public static final String PREF_MAX_ACK = "perfModeMaximumAck";

    /**
     * Gate for optimizations that are documented and plausible but not yet measured on real
     * phones (heap headroom bumps, code-cache sizing, JIT tier caps, heap-region tuning).
     * Off by default: a profile is allowed to ship only what has a number behind it.
     */
    public static final String PREF_EXPERIMENTAL_FLAGS = "perfExperimentalFlags";

    public final String key;
    public final String headline;
    public final String behaviour;

    PerformanceMode(String key, String headline, String behaviour) {
        this.key = key;
        this.headline = headline;
        this.behaviour = behaviour;
    }

    /** Unknown, missing or garbage values resolve to {@link #NORMAL} — never to "aggressive". */
    public static PerformanceMode fromKey(String key) {
        if (key != null) {
            for (PerformanceMode m : values()) {
                if (m.key.equalsIgnoreCase(key.trim())) return m;
            }
        }
        return NORMAL;
    }

    public static PerformanceMode fromPrefs(SharedPreferences prefs) {
        if (prefs == null) return NORMAL;
        try {
            return fromKey(prefs.getString(PREF_KEY, NORMAL.key));
        } catch (Throwable ignored) {
            return NORMAL;
        }
    }

    public boolean isExperimentalEnabled(SharedPreferences prefs) {
        if (prefs == null || this == NORMAL) return false; // no experiment runs in the safe profile
        try {
            return prefs.getBoolean(PREF_EXPERIMENTAL_FLAGS, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True for the two modes that may request anything beyond the platform default. */
    public boolean requestsBoost() {
        return this == PERFORMANCE || this == MAXIMUM;
    }

    /** The order levers are shed in when the device gets hot: strongest request goes first. */
    public PerformanceMode shedStep() {
        return this == MAXIMUM ? PERFORMANCE : NORMAL;
    }
}
