package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single decision point of the performance engine.
 *
 * <p>One {@code PerformanceMode} plus what the device can do in and what the session is about to run
 * goes in; one immutable, fully-resolved policy comes out. Call sites are only allowed to read the
 * result — no {@code if (mode == MAXIMUM)} scattered through the launcher, no half-applied mode.
 * Anything the platform or this device cannot provide is reported as unavailable in
 * {@link #describe()} instead of being silently skipped or faked.
 *
 * <p>The policy is <b>frozen at launch</b>. Every axis except heat is fixed before the game starts,
 * because a setting that changes underneath a running JVM produces behaviour nobody can attribute to
 * a cause. Thermal state is the one live axis, applied by {@link ThermalGovernor}, and it can only
 * ever take levers away, never add them.
 */
public final class PerformancePolicy {

    /** Where GC and heap sizing come from. */
    public enum GcPlan {
        /** Leave the JVM alone: it picks its own collector for this heap (Normal). */
        ERGONOMIC,
        /** G1 with a bounded pause target — the stutter-sensitive, modded case. */
        G1_LOW_PAUSE,
        /** G1 tuned for throughput rather than pause time (Maximum, big heap). */
        G1_THROUGHPUT
    }

    /** How much work the launcher UI does while a game session is on screen. */
    public enum QuietLevel {
        /** Normal background behaviour. */
        OFF,
        /** Stop infinite decor animations and defer non-essential work while the game runs. */
        REDUCED,
        /** REDUCED plus pause sync/telemetry traffic until the session ends. */
        MINIMAL
    }

    public final PerformanceMode mode;
    public final DeviceCapability device;
    public final McContext game;

    // ── scheduler ──
    public final boolean boostGameThreads;
    public final int threadPriority;
    public final int maxBoostedThreads;
    public final boolean adpfSession;

    /** True when the player (launcher switch or options.txt) asked for vsync. Never set by the engine. */
    public final boolean playerVsync;
    /** Who asked for it, so the card and the log can say why a frame is ever waited on. */
    public final String vsyncOwner;

    // ── presentation ──

    // ── JVM ──
    public final GcPlan gcPlan;
    public final int heapBiasPercent;
    public final int maxHeapMb;
    public final boolean experimentalJvmFlags;

    // ── driver / GL ──
    public final Map<String, String> rendererEnv;

    // ── launcher UI ──
    public final QuietLevel quietLevel;

    // ── thermal ──
    public final boolean thermalShedAllowed;
    public final boolean thermalPolling;

    /** Levers this device could not be given, for the UI to state out loud. */
    public final List<String> unavailable;

    private static volatile PerformancePolicy sFrozen;

    private PerformancePolicy(Builder b) {
        mode = b.mode;
        device = b.device;
        game = b.game;
        boostGameThreads = b.boostGameThreads;
        threadPriority = b.threadPriority;
        maxBoostedThreads = b.maxBoostedThreads;
        adpfSession = b.adpfSession;
        playerVsync = b.playerVsync;
        vsyncOwner = b.vsyncOwner;
        gcPlan = b.gcPlan;
        heapBiasPercent = b.heapBiasPercent;
        maxHeapMb = b.maxHeapMb;
        experimentalJvmFlags = b.experimentalJvmFlags;
        rendererEnv = Collections.unmodifiableMap(new LinkedHashMap<>(b.rendererEnv));
        quietLevel = b.quietLevel;
        thermalShedAllowed = b.thermalShedAllowed;
        thermalPolling = b.thermalPolling;
        unavailable = Collections.unmodifiableList(new ArrayList<>(b.unavailable));
    }

    /** The policy the current launch was given, if one was resolved. Never recomputed mid-session. */
    public static PerformancePolicy current() {
        return sFrozen;
    }

    /** Resolves and freezes. Called once from the launch path, before the JVM is created. */
    public static PerformancePolicy resolveAndFreeze(Context ctx, SharedPreferences prefs, McContext game) {
        PerformancePolicy p = resolve(ctx, prefs, game);
        sFrozen = p;
        return p;
    }

    public static PerformancePolicy resolve(Context ctx, SharedPreferences prefs, McContext game) {
        PerformanceMode mode = PerformanceMode.fromPrefs(prefs);
        DeviceCapability device = DeviceCapability.current();
        return newBuilder(mode, device, game == null ? new McContext(null, null, null, 0, false, false, false) : game,
                prefs, ctx).build();
    }

    public static Builder newBuilder(PerformanceMode mode, DeviceCapability device, McContext game,
                                     SharedPreferences prefs, Context ctx) {
        Builder b = new Builder(mode, device, game);
        b.experimentalJvmFlags = mode.isExperimentalEnabled(prefs);

        // ── presentation: the launcher never asks the display to hold back.
        // Window.setSustainedPerformanceMode() sounds like "stable clocks" but on Android it is
        // implemented as a *reduced sustained refresh rate* on many panels — a frame-rate ceiling, which
        // is exactly what a performance profile must not add. It used to be defaulted on for phones this
        // launcher guessed were powerful, and the first version of this engine inherited that as the
        // balanced profile's behaviour. Both are gone: nothing here touches the display's rate, and
        // Minecraft's own vsync/FPS options are the only thing that can pace frames.
        b.playerVsync = RendererPolicy.playerAskedForVsync(game);
        b.vsyncOwner = RendererPolicy.vsyncReason(game);

        // ── scheduler
        b.boostGameThreads = mode.requestsBoost();
        b.maxBoostedThreads = mode == PerformanceMode.MAXIMUM ? 10 : 6;
        b.threadPriority = mode == PerformanceMode.MAXIMUM
                ? android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
                : android.os.Process.THREAD_PRIORITY_DISPLAY;
        // ADPF's Java performance-hint API is Android 12 (API 31); the NDK form arrived in 13.
        boolean adpfPossible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        b.adpfSession = mode == PerformanceMode.MAXIMUM && adpfPossible;
        if (mode == PerformanceMode.MAXIMUM && !adpfPossible) {
            b.unavailable.add("ADPF performance hints need Android 12");
        }

        // ── JVM: only what has a reason and a citation; sizing is clamped to what Android allows.
        int ceiling = ctx != null ? Tools.getMaximumRamAllocation(ctx) : Integer.MAX_VALUE;
        // The already-sanitized value the settings UI committed: never re-guess it here.
        int base = LauncherPreferences.PREF_RAM_ALLOCATION > 0 ? LauncherPreferences.PREF_RAM_ALLOCATION : 1024;
        int bias = mode == PerformanceMode.MAXIMUM ? 15 : (mode == PerformanceMode.PERFORMANCE ? 8 : 0);
        if (!b.experimentalJvmFlags) bias = 0; // heap headroom is not shipped unmeasured
        int wanted = base + (base * bias) / 100;
        int avail = device.availRamMb > 0 ? (int) (device.availRamMb * 0.55f) : Integer.MAX_VALUE;
        b.heapBiasPercent = bias;
        b.maxHeapMb = Math.max(256, Math.min(Math.min(wanted, ceiling), avail));
        if (device.partial) b.unavailable.add("Device profile still building — JVM sizing stays conservative");
        b.gcPlan = pickGcPlan(mode, device, game, b.experimentalJvmFlags);

        // ── launcher UI
        b.quietLevel = mode == PerformanceMode.NORMAL ? QuietLevel.OFF
                : (mode == PerformanceMode.MAXIMUM ? QuietLevel.MINIMAL : QuietLevel.REDUCED);

        // ── thermal
        b.thermalPolling = mode.requestsBoost();
        // Both boost profiles back off when the device reports heat; Normal asks for nothing, so it
        // has nothing to shed and does not even sample.
        b.thermalShedAllowed = mode.requestsBoost();

        // ── renderer env, capability-gated
        b.rendererEnv.putAll(RendererPolicy.envFor(mode, device, game, b.experimentalJvmFlags, b.unavailable));
        return b;
    }

    private static GcPlan pickGcPlan(PerformanceMode mode, DeviceCapability device, McContext game,
                                     boolean experimental) {
        if (mode == PerformanceMode.NORMAL) return GcPlan.ERGONOMIC;
        boolean heavySession = game.allocationPressureClass() >= 2;
        boolean bigHeap = device.totalRamMb >= 6144 || game.isModern() && heavySession;
        // Throughput tuning is a Maximum-Performance decision only: it trades pause time for total
        // work, which is the wrong shape for a profile whose promise is smoothness.
        if (mode == PerformanceMode.MAXIMUM && bigHeap && (experimental || heavySession)) return GcPlan.G1_THROUGHPUT;
        return GcPlan.G1_LOW_PAUSE;
    }

    public boolean isNormal() {
        return mode == PerformanceMode.NORMAL;
    }

    /** Short, human-readable account of exactly what this launch will do. Used by the settings card. */
    public String describe() {
        StringBuilder b = new StringBuilder();
        b.append(mode.headline).append('\n');
        List<String> on = new ArrayList<>();
        if (boostGameThreads) on.add("game threads at " + priorityName(threadPriority));
        if (adpfSession) on.add("ADPF hint session");
        on.add(playerVsync
                ? "vsync held by the player (" + vsyncOwner + ")"
                : "frame rate uncapped (neither the launcher nor Minecraft asked for vsync)");
        if (gcPlan != GcPlan.ERGONOMIC) on.add(gcPlan == GcPlan.G1_LOW_PAUSE ? "low-pause G1" : "throughput G1");
        if (heapBiasPercent > 0) on.add("heap +" + heapBiasPercent + "% (" + maxHeapMb + " MB cap)");
        if (!rendererEnv.isEmpty()) on.add("renderer env: " + rendererEnv.keySet());
        if (quietLevel != QuietLevel.OFF) on.add("launcher UI " + quietLevel.name().toLowerCase(java.util.Locale.ROOT));
        if (thermalShedAllowed) on.add("thermal shed on severe throttling");
        b.append(on.isEmpty() ? "Nothing requested — the platform decides" : join(on, " · "));
        if (!unavailable.isEmpty()) b.append("\nNot available here: ").append(join(unavailable, ", "));
        return b.toString();
    }

    private static String priorityName(int priority) {
        if (priority == android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) return "urgent display priority";
        if (priority == android.os.Process.THREAD_PRIORITY_DISPLAY) return "display priority";
        return "priority " + priority;
    }

    /** minSdk 21 has no String.join and the app does not enable core-library desugaring. */
    private static String join(List<String> items, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(sep);
            b.append(items.get(i));
        }
        return b.toString();
    }

    /** JVM arguments derived from this policy. Order is fixed so A/B runs are comparable. */
    public List<String> jvmFlags() {
        return JvmPolicy.flagsFor(this);
    }

    public static void clear() {
        sFrozen = null;
    }

    // ── builder ──

    public static final class Builder {
        private final PerformanceMode mode;
        private final DeviceCapability device;
        private final McContext game;
        private final List<String> unavailable = new ArrayList<>();
        private final Map<String, String> rendererEnv = new LinkedHashMap<>();

        private boolean boostGameThreads;
        private int threadPriority = android.os.Process.THREAD_PRIORITY_DEFAULT;
        private int maxBoostedThreads;
        private boolean adpfSession;
        private boolean playerVsync;
        private String vsyncOwner = "none";
        private GcPlan gcPlan = GcPlan.ERGONOMIC;
        private int heapBiasPercent;
        private int maxHeapMb;
        private boolean experimentalJvmFlags;
        private QuietLevel quietLevel = QuietLevel.OFF;
        private boolean thermalShedAllowed;
        private boolean thermalPolling;

        Builder(PerformanceMode mode, DeviceCapability device, McContext game) {
            this.mode = mode;
            this.device = device;
            this.game = game;
        }

        public PerformancePolicy build() {
            return new PerformancePolicy(this);
        }
    }
}
