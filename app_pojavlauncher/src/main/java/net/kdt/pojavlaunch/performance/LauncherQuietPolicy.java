package net.kdt.pojavlaunch.performance;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * "While Minecraft is on screen, the launcher should not be spending CPU."
 *
 * <p>The launcher UI process keeps an {@code :launcher} surface alive behind the game — an FPS graph,
 * animated backgrounds, shine sweeps, skeletons and periodic sync all keep running there. Each of those
 * is a wake-up on a core the game would like. This class is one shared boolean per process, derived
 * from the resolved profile, that those loops check before they start infinite work:
 *
 * <ul>
 *   <li>{@link #animationsAllowed()} — {@code UiMotion} refuses to start an infinite animator while a
 *   boosted session is in the foreground (finite, interaction-driven motion is unaffected, so nothing
 *   in the UI ever looks broken — it just stops animating for nobody).</li>
 *   <li>{@link #backgroundTrafficAllowed()} — sync and telemetry traffic defers itself until the session
 *   ends, on the profiles whose name says the game comes first.</li>
 * </ul>
 *
 * <p>The balanced profile leaves both true: it asks for nothing, by design.
 */
public final class LauncherQuietPolicy {

    private static volatile boolean sAnimationsAllowed = true;
    private static volatile boolean sBackgroundTrafficAllowed = true;
    private static volatile PerformancePolicy.QuietLevel sLevel = PerformancePolicy.QuietLevel.OFF;

    private LauncherQuietPolicy() {}

    public static void apply(PerformancePolicy.QuietLevel level) {
        if (level == null) level = PerformancePolicy.QuietLevel.OFF;
        if (level == sLevel) return;
        sLevel = level;
        switch (level) {
            case MINIMAL:
                sAnimationsAllowed = false;
                sBackgroundTrafficAllowed = false;
                break;
            case REDUCED:
                sAnimationsAllowed = false;
                sBackgroundTrafficAllowed = true;
                break;
            case OFF:
            default:
                sAnimationsAllowed = true;
                sBackgroundTrafficAllowed = true;
                break;
        }
    }

    /**
     * Used by the launcher UI process, which never resolves the policy itself (the game process froze
     * it before launch). Mapping mode → quiet level is a pure function of the mode, so re-deriving it
     * here cannot disagree with what the game process did.
     */
    public static void applyForPersistedMode() {
        apply(quietLevelFor(PerformanceMode.fromPrefs(LauncherPreferences.DEFAULT_PREF)));
    }

    public static PerformancePolicy.QuietLevel quietLevelFor(PerformanceMode mode) {
        if (mode == PerformanceMode.MAXIMUM) return PerformancePolicy.QuietLevel.MINIMAL;
        if (mode == PerformanceMode.PERFORMANCE) return PerformancePolicy.QuietLevel.REDUCED;
        return PerformancePolicy.QuietLevel.OFF;
    }

    public static boolean animationsAllowed() {
        return sAnimationsAllowed;
    }

    public static boolean backgroundTrafficAllowed() {
        return sBackgroundTrafficAllowed;
    }

    public static PerformancePolicy.QuietLevel level() {
        return sLevel;
    }
}
