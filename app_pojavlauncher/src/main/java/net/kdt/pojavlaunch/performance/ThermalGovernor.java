package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.io.File;
import java.util.Locale;

/**
 * Reads the platform thermal signals and lets {@link SchedulingPolicy} back off.
 *
 * <p>Two sources, both Android APIs where one exists: {@link PowerManager#getCurrentThermalStatus()}
 * (Android 10+) and {@link PowerManager#getThermalHeadroom(float)} (Android 11+; headroom of 1.0 means
 * the device is throttling hard, {@code NaN} means either unsupported or polled faster than once a
 * second). CPU pressure from {@code /proc/pressure/cpu} is read on the same tick as a supplement for
 * the OEMs that never populate the thermal HAL — that is a read, of our own cgroup's file, on a
 * background thread at 1 Hz, and it is the only {@code /proc} path left in the engine.
 *
 * <p>The governor has one direction of travel: it can remove a request, never add one. Heat therefore
 * lowers the engine's ambition instead of being "pushed through", and it is the reason
 * {@code Maximum Performance} is described as maximum <i>sustainable</i>.
 */
public final class ThermalGovernor {

    public enum Level {
        UNKNOWN, COOL, MILD, MODERATE, SEVERE;

        public boolean atLeast(Level other) {
            return ordinal() >= other.ordinal();
        }

        public String label() {
            switch (this) {
                case COOL: return "cool";
                case MILD: return "warm";
                case MODERATE: return "hot — throttling";
                case SEVERE: return "severe — hard throttle";
                default: return "not reported by this device";
            }
        }
    }

    private static volatile Level sLevel = Level.UNKNOWN;
    private static volatile float sHeadroom = Float.NaN; // NaN = no signal from this device
    private static volatile long sHeadroomAt;
    private static volatile long sCoolSince;
    private static volatile boolean sShed;
    private static Runnable sTick;
    private static PowerManager sPowerManager;

    private ThermalGovernor() {}

    public static Level current() {
        return sLevel;
    }

    /** One-line status for the settings card and the benchmark log. */
    public static String describe(Context ctx) {
        String headroom = Float.isNaN(sHeadroom) ? "n/a"
                : String.format(Locale.ROOT, "%.2f (%ds ago)", sHeadroom,
                Math.max(0L, (System.currentTimeMillis() - sHeadroomAt) / 1000L));
        return "Thermal: " + sLevel.label() + " · headroom " + headroom
                + " · shed " + (sShed ? "active" : "none")
                + (sHeadroomAt == 0L ? " (observe-only; the engine samples while a boosted session runs)" : "");
    }

    /**
     * Attach to the engine's heartbeat. Nothing runs for the balanced profile, which asks for nothing
     * and therefore has nothing to protect: it simply is not called.
     */
    public static void start(final Context ctx, PerformancePolicy policy) {
        stop();
        if (ctx == null || policy == null || !policy.thermalPolling) return;
        final Context app = ctx.getApplicationContext();
        try {
            sPowerManager = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        } catch (Throwable t) {
            sPowerManager = null;
        }
        sTick = new Runnable() {
            @Override public void run() {
                sample(app);
            }
        };
        SchedulingPolicy.addTickListener(sTick);
        // First read is immediate so the very first tick has a level, not UNKNOWN.
        sample(app);
    }

    public static void stop() {
        Runnable r = sTick;
        sTick = null;
        if (r != null) SchedulingPolicy.removeTickListener(r);
        sShed = false;
    }

    private static void sample(Context ctx) {
        Level level = Level.UNKNOWN;
        float headroom = Float.NaN;
        PowerManager pm = sPowerManager;
        if (pm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    level = fromStatus(pm.getCurrentThermalStatus());
                } catch (Throwable ignored) {
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // getThermalHeadroom(int forecastSeconds) — API 31 on the Java side, and the value is
                // only meaningful once per second at most. 0 means NaN or "unsupported" per the
                // platform docs, which is treated as no signal rather than as "wide open".
                try {
                    float v = pm.getThermalHeadroom(0);
                    if (!Float.isNaN(v) && v > 0f) {
                        headroom = v;
                        sHeadroomAt = System.currentTimeMillis();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (level == Level.UNKNOWN && psiSome()) level = Level.MILD;
        if (!Float.isNaN(headroom)) {
            // Headroom is the signal OEMs actually populate; the platform documents the mapping
            // (>1.0 severe, >0.95 moderate, >0.85 light), so those are the thresholds used here
            // rather than an invented one. Folded in as a floor, never as a ceiling.
            Level fromHeadroom = headroom > 1.0f ? Level.SEVERE
                    : headroom > 0.95f ? Level.MODERATE : headroom > 0.85f ? Level.MILD : level;
            if (fromHeadroom.atLeast(level)) level = fromHeadroom;
        }
        sLevel = level;
        if (!Float.isNaN(headroom)) sHeadroom = headroom;

        boolean shouldShed = policyAllowsShed() && level.atLeast(Level.MODERATE);
        if (shouldShed) {
            sCoolSince = 0L;
            if (!sShed) {
                sShed = true;
                SchedulingPolicy.setThermalShed(true);
            }
        } else if (sShed) {
            long now = System.currentTimeMillis();
            if (sCoolSince == 0L) {
                sCoolSince = now;
            } else if (now - sCoolSince > 60_000L) {
                // Hysteresis: hold the shed for a minute of genuinely cool readings before
                // re-escalating, otherwise a device sitting on the boundary oscillates visibly.
                sShed = false;
                sCoolSince = 0L;
                SchedulingPolicy.setThermalShed(false);
            }
        } else {
            sCoolSince = 0L;
        }
    }

    private static boolean policyAllowsShed() {
        PerformancePolicy p = PerformancePolicy.current();
        return p != null && p.thermalShedAllowed;
    }

    private static Level fromStatus(int status) {
        switch (status) {
            case 0: return Level.COOL;         // THERMAL_STATUS_NONE
            case 1:
            case 2: return Level.MILD;         // LIGHT, MODERATE
            case 3:
            case 4: return Level.MODERATE;     // SEVERE, SUSTAINED_SEVERE
            case 5: return Level.SEVERE;       // CRITICAL
            case 6: return Level.SEVERE;       // EMERGENCY
            case 7: return Level.SEVERE;       // SHUTDOWN
            default: return Level.UNKNOWN;
        }
    }

    /** Pressure-stall average from our own cgroup; -1 when the kernel does not expose it. */
    private static float psiCpuAvg10() {
        try {
            String line = firstLine(new File("/proc/pressure/cpu"));
            if (line == null) return -1f;
            int avgIdx = line.indexOf("avg10=");
            if (avgIdx < 0) return -1f;
            String digits = line.substring(avgIdx + 6);
            int end = 0;
            while (end < digits.length() && (Character.isDigit(digits.charAt(end)) || digits.charAt(end) == '.')) end++;
            return Float.parseFloat(digits.substring(0, end));
        } catch (Throwable ignored) {
            return -1f;
        }
    }

    private static boolean psiSome() {
        return psiCpuAvg10() > 20f;
    }

    public static int peakLevelOrdinal() {
        return sLevel.ordinal();
    }

    private static String firstLine(File f) throws java.io.IOException {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(f), 128);
            String s = r.readLine();
            // "some avg10=12.34 avg60=..." — the aggregate line, not per-resource
            if (s != null && !s.startsWith("some")) {
                String second = r.readLine();
                return second != null ? second : s;
            }
            return s;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
