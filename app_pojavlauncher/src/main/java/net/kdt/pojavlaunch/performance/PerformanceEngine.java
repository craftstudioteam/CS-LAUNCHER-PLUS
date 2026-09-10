package net.kdt.pojavlaunch.performance;

import android.app.Activity;
import android.content.Context;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * The one entry point the rest of the launcher has for the performance engine.
 *
 * <p>Call sites are lifecycle statements, never policy decisions: they say "a session started",
 * "the game went to the background", "the session ended". Everything about what that means for
 * threads, heat, the JVM or the launcher UI is decided inside {@link PerformancePolicy} and executed by
 * {@link SchedulingPolicy}, {@link ThermalGovernor}, {@link FrameSampleCollector} and
 * {@link LauncherQuietPolicy}. No other class in the app should compare against a
 * {@link PerformanceMode} value.
 */
public final class PerformanceEngine {

    private PerformanceEngine() {}

    /**
     * Called from {@code MainActivity.onCreate}. Resolves the presentation part of the policy — which
     * the window needs before the first frame — and starts building the device profile in the
     * background. The full policy (with the game context) is re-frozen on the launch path, and that is
     * the one the JVM and the thermal governor use.
     */
    public static void onSessionCreate(Context ctx) {
        if (ctx == null) return;
        DeviceCapability.ensureAsync(ctx);
        PerformancePolicy.resolveAndFreeze(ctx, LauncherPreferences.DEFAULT_PREF, null);
        LauncherQuietPolicy.applyForPersistedMode();
    }

    /**
     * Game visible again: the only moment the engine is allowed to run at all.
     *
     * <p>There is deliberately no window-level performance call anywhere in this class.
     * {@code Window.setSustainedPerformanceMode()} used to be applied here and it is a frame-rate
     * ceiling in disguise — on Android it asks the display for its reduced *sustained* refresh rate,
     * which is how a "performance" profile ended up pinned at 60 on a 90/120 Hz panel.
     * {@code WindowManager.LayoutParams.frameRateCategory} would be the modern equivalent but is not in
     * the SDK this module compiles against. So the game window is left exactly as the system created it;
     * the only refresh-rate request in the app is the one {@code MinecraftGLSurface} already makes on the
     * game surface, and that one follows Minecraft's own maxFps setting.
     */
    public static void onResume(Activity activity) {
        if (activity == null) return;
        PerformancePolicy policy = PerformancePolicy.current();
        if (policy == null) {
            policy = PerformancePolicy.resolve(activity, LauncherPreferences.DEFAULT_PREF, null);
        }
        SchedulingPolicy.begin(activity.getApplicationContext(), policy);
        ThermalGovernor.start(activity.getApplicationContext(), policy);
        FrameSampleCollector.begin(activity.getApplicationContext(),
                Tools.LOCAL_RENDERER, policy.game == null ? null : policy.game.versionId);
    }

    /** Game backgrounded: release every request. Nothing is held while the user is not looking. */
    public static void onBackground(Context ctx) {
        FrameSampleCollector.pause();
        ThermalGovernor.stop();
        SchedulingPolicy.end();
    }

    /** Session over: write the measurement, drop the frozen policy, hand the phone back. */
    public static void onSessionEnd(Context ctx) {
        onBackground(ctx);
        FrameSampleCollector.finishAndClear(ctx);
        PerformancePolicy.clear();
    }
}
