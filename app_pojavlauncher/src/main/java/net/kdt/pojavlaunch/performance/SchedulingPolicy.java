package net.kdt.pojavlaunch.performance;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The only scheduler the game process talks to.
 *
 * <p>What it does, and does not, do:
 * <ul>
 *   <li>It raises the OS priority of the game's own threads with the public
 *   {@link Process#setThreadPriority(int, int)} — for {@code Performance} and
 *   {@code Maximum Performance} only, from a background thread, and only for threads whose name says
 *   they render, present or tick the client.</li>
 *   <li>On Android 12+ {@code Maximum} additionally opens an {@link
   android.os.PerformanceHintManager} hint session over the boosted thread ids, carrying
   the frame budget. That is the supported way to ask the scheduler to keep threads on capable
   cores: the app never requests a clock or a core itself.</li>
 *   <li>It never touches CPU affinity. Android pins app threads through cpusets; per-thread affinity
 *   calls return success and do nothing, and the cluster-frequency files an app used to read for
 *   "big cores" are closed to untrusted processes. The old engine paid for that dead end on the main
 *   thread every four seconds.</li>
 *   <li>It never writes to {@code /sys}, never asks for more than the OS is willing to give, and
 *   stops all work the moment the session is not in the foreground.</li>
 * </ul>
 *
 * <p>The thread scan runs at most once a second, on its own {@link HandlerThread}, and only visits
 * thread ids it has not seen before. {@link #tickListeners} lets the thermal governor and the frame
 * sampler share that one heartbeat instead of each starting their own.
 */
public final class SchedulingPolicy {

    /** Thread names worth boosting on a Minecraft client; matched case-insensitively. */
    private static final String[] RENDER_THREAD_NAMES = {
            "render thread", "client main thread", "main", "sodium", "egl", "gl4es", "zink",
            "lwjgl", "server thread"
    };

    private static volatile SchedulingPolicy sActive;

    private final Context appContext;
    private final PerformancePolicy policy;
    private final HandlerThread thread;
    private final Handler handler;
    private final Set<Integer> touched = new HashSet<>();
    private final List<Runnable> tickListeners = new ArrayList<>();

    private volatile boolean shedByThermal;
    private android.os.PerformanceHintManager.Session hintSession;
    private long frameBudgetNanos;


    private SchedulingPolicy(Context ctx, PerformancePolicy policy) {
        this.appContext = ctx.getApplicationContext();
        this.policy = policy;
        this.thread = new HandlerThread("CS-PerfEngine", Process.THREAD_PRIORITY_BACKGROUND);
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /** Start the session. Cheap and correct even when the mode asks for nothing. */
    public static void begin(Context ctx, PerformancePolicy policy) {
        end();
        if (ctx == null || policy == null) return;
        if (!policy.boostGameThreads) return; // Normal: no thread is touched, no scan is scheduled
        SchedulingPolicy s;
        try {
            s = new SchedulingPolicy(ctx, policy);
        } catch (Throwable t) {
            return;
        }
        sActive = s;
        s.handler.post(new Runnable() {
            @Override public void run() {
                s.scanThreads();
                s.openHintSession();
                s.handler.postDelayed(s.tick, TICK_MS);
            }
        });
    }

    public static void end() {
        SchedulingPolicy s = sActive;
        sActive = null;
        if (s == null) return;
        try {
            s.handler.removeCallbacksAndMessages(null);
            s.closeHintSession();
            s.thread.quitSafely();
        } catch (Throwable ignored) {
        }
    }

    /** True while a boosted session owns the heartbeat; other components use this to stay quiet. */
    public static boolean isActive() {
        return sActive != null;
    }

    /**
     * Runs on the engine's 1 Hz tick. Used by {@link ThermalGovernor} and
     * {@link FrameSampleCollector} so one thread serves the whole session.
     */
    public static void addTickListener(Runnable r) {
        SchedulingPolicy s = sActive;
        if (s == null || r == null) return;
        synchronized (s.tickListeners) {
            if (!s.tickListeners.contains(r)) s.tickListeners.add(r);
        }
    }

    public static void removeTickListener(Runnable r) {
        SchedulingPolicy s = sActive;
        if (s == null || r == null) return;
        synchronized (s.tickListeners) {
            s.tickListeners.remove(r);
        }
    }

    /**
     * Called by {@link ThermalGovernor}: the engine sheds its own requests when the device reports
     * heat. Shedding only ever lowers what is asked for; recovery is a separate, slower decision.
     */
    public static void setThermalShed(boolean shed) {
        SchedulingPolicy s = sActive;
        if (s == null || !s.policy.thermalShedAllowed || s.shedByThermal == shed) return;
        s.shedByThermal = shed;
        s.handler.post(new Runnable() {
            @Override public void run() {
                s.applyPriorities();
                s.updateHintTarget();
            }
        });
        android.util.Log.i("CSPerf", shed
                ? "thermal: shedding boost (ADPF target relaxed, thread priorities lowered)"
                : "thermal: boost restored");
    }

    private static final long TICK_MS = 1000L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            scanThreads();
            List<Runnable> copy;
            synchronized (tickListeners) {
                copy = new ArrayList<>(tickListeners);
            }
            for (int i = 0; i < copy.size(); i++) {
                try {
                    copy.get(i).run();
                } catch (Throwable ignored) {
                }
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ── thread priorities ──

    /**
     * One pass over this process's own threads. Only unknown tids are examined and only names that are
     * clearly render/present/tick are raised, so a mod that opens a hundred worker threads cannot turn
     * this into a priority storm.
     */
    private void scanThreads() {
        try {
            File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks == null) return;
            int raised = 0;
            for (File t : tasks) {
                int tid;
                try {
                    tid = Integer.parseInt(t.getName());
                } catch (NumberFormatException ignored) {
                    continue;
                }
                if (touched.contains(tid)) continue;
                String name = readComm(tid);
                if (name == null || !isRenderThread(name)) continue;
                touched.add(tid);
                if (raised >= policy.maxBoostedThreads) continue;
                applyPriority(tid);
                raised++;
            }
        } catch (Throwable ignored) {
            // /proc/self/task is readable for our own process, but OEM kernels occasionally deny it.
        }
    }

    private void applyPriorities() {
        for (Integer tid : touched) applyPriority(tid);
    }

    private void applyPriority(int tid) {
        int target = shedByThermal ? Process.THREAD_PRIORITY_DEFAULT : policy.threadPriority;
        try {
            Process.setThreadPriority(tid, target);
        } catch (Throwable ignored) {
            // thread vanished mid-pass, or the OS declined: nothing to recover
        }
    }

    private static boolean isRenderThread(String comm) {
        String name = comm.toLowerCase(Locale.ROOT);
        for (String needle : RENDER_THREAD_NAMES) {
            if (name.contains(needle)) return true;
        }
        return false;
    }

    private static String readComm(int tid) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(
                    new File(new File("/proc/self/task", String.valueOf(tid)), "comm")), 64);
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ── ADPF: android.os.PerformanceHintManager, added in API 31 (Android 12) ──
    // Java side of ADPF: a hint session over a group of thread ids with a target work duration per
    // cycle. The system then decides core placement and frequency for that group; the app never asks
    // for a clock directly, which is why this replaces the affinity code it deletes.

    private void openHintSession() {
        if (!policy.adpfSession || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            android.os.PerformanceHintManager mgr = appContext
                    .getSystemService(android.os.PerformanceHintManager.class);
            if (mgr == null) {
                note("ADPF manager unavailable on this device");
                return;
            }
            int[] tids = boostedThreadIds();
            if (tids.length == 0) {
                note("ADPF session skipped: no game threads identified yet");
                return;
            }
            frameBudgetNanos = measureFrameBudgetNanos();
            // Deliberately a target-only session. reportActualWorkDuration() would need the game's real
            // per-frame duration, and the launcher does not measure the render loop — inventing a number
            // to feed the scheduler is worse than saying nothing, because the scheduler would then tune
            // itself around a fiction. The target above is a request, not a promise.
            hintSession = mgr.createHintSession(tids, frameBudgetNanos);
            note("ADPF hint session opened over " + tids.length + " thread(s) with a "
                    + (frameBudgetNanos / 1000L) + " us target");
        } catch (Throwable t) {
            hintSession = null;
            note("ADPF unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void updateHintTarget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        android.os.PerformanceHintManager.Session session = session();
        if (session == null) return;
        long budget = measureFrameBudgetNanos();
        if (shedByThermal) budget = (long) (budget * 1.5f); // ask for less, per the platform guidance
        try {
            session.updateTargetWorkDuration(budget);
        } catch (Throwable ignored) {
        }
    }

    private void closeHintSession() {
        android.os.PerformanceHintManager.Session session = session();
        if (session == null) return;
        try {
            session.close();
        } catch (Throwable ignored) {
        }
        hintSession = null;
    }

    private android.os.PerformanceHintManager.Session session() {
        return hintSession;
    }

    /** The tids the session covers: the game threads this policy actually raised, plus our main. */
    private int[] boostedThreadIds() {
        java.util.List<Integer> ids = new ArrayList<>(touched);
        int self = Process.myTid();
        if (!ids.contains(self)) ids.add(self);
        int[] out = new int[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    /**
     * The frame budget we intend to hit. Deliberately the *highest* rate the panel can do, not the rate
     * it happens to be running at: on a 90/120 Hz phone that is currently sitting in a 60 Hz mode, a
     * target built from {@code getRefreshRate()} tells the scheduler that 16.7 ms is the goal and lets it
     * downclock accordingly — which is how a boost profile ends up pinned near 60. If Minecraft's own
     * slider asks for fewer frames, that number wins, because then a longer budget is what the player
     * chose and chasing 120 would only burn battery.
     */
    private long measureFrameBudgetNanos() {
        float hz = 60f;
        try {
            android.view.Display d = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                d = appContext.getDisplay();
            } else if (appContext instanceof Activity) {
                d = ((Activity) appContext).getWindowManager().getDefaultDisplay();
            }
            if (d != null) {
                float best = 0f;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.view.Display.Mode[] modes = d.getSupportedModes();
                    for (android.view.Display.Mode m : modes) {
                        float r = m.getRefreshRate();
                        if (r > best) best = r;
                    }
                }
                if (best <= 0f) best = d.getRefreshRate();
                if (best >= 24f && best <= 240f) hz = best;
            }
        } catch (Throwable ignored) {
        }
        try {
            net.kdt.pojavlaunch.performance.PerformancePolicy policy =
                    net.kdt.pojavlaunch.performance.PerformancePolicy.current();
            if (policy != null && policy.game != null) {
                int wanted = policy.game.mcMaxFps();
                if (wanted > 0 && wanted < hz) hz = wanted;
            }
        } catch (Throwable ignored) {
        }
        return (long) (1_000_000_000L / hz);
    }

    private static void note(String message) {
        android.util.Log.i("CSPerf", message);
    }

    /** Reported by the settings card so an unavailable lever is visible rather than imaginary. */
    public static String lastEngineNote() {
        return "ADPF: " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "available" : "needs Android 12")
                + " · priorities: " + (isActive() ? "active this session" : "idle");
    }

    /** Reserved for tests/diagnostics: whether the engine believes it is boosting right now. */
    public static boolean isShedActive() {
        SchedulingPolicy s = sActive;
        return s != null && s.shedByThermal;
    }

}
