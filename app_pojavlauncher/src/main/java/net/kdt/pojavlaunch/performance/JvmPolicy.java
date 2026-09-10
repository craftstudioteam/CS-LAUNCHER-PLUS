package net.kdt.pojavlaunch.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a resolved {@link PerformancePolicy} into the small set of JVM arguments it earned.
 *
 * <p>Deliberately short. The old engine pushed {@code -XX:SoftRefLRUPolicyMSPerMB=16} (which evicts
 * Minecraft's soft-referenced caches about sixty times sooner than the JVM default of 1000 ms/MB — the
 * comment in that file had it backwards), {@code -XX:+DisableExplicitGC} (which removes the only
 * mechanism the launcher has for returning native memory, risking an OOM the JVM cannot help with) and
 * a launch-time {@code System.gc()}. None of those had a measurement behind them, so none of them are
 * here; {@code ExplicitGCInvokesConcurrent} is the safe form of "don't stop the world for an explicit
 * GC" and is the only behavioural change shipped by default.
 *
 * <p>Everything that is plausible but needs a device number first (code-cache sizing, tiered-compilation
 * caps, G1 region and survivor tuning, {@code AlwaysPreTouch}) sits behind
 * {@code perfExperimentalFlags}, which is off.
 */
public final class JvmPolicy {

    private JvmPolicy() {}

    public static List<String> flagsFor(PerformancePolicy policy) {
        if (policy == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();

        // ---- always (safe by construction, documented, and independent of any one SoC) ----
        // The JVM's perf-data file normally lives in /tmp, which on Android is a slow
        // tmpfs/fuse-backed write made on every GC and deoptimization.
        out.add("-XX:+PerfDisableSharedMem");

        switch (policy.gcPlan) {
            case G1_LOW_PAUSE:
                // Only pause-target and worker counts: no region sizes, no survivor ratios.
                out.add("-XX:+UseG1GC");
                out.add("-XX:MaxGCPauseMillis=50");
                out.add("-XX:ParallelGCThreads=" + gcThreads(policy, 4));
                out.add("-XX:ConcGCThreads=" + concThreads(policy));
                out.add("-XX:+ExplicitGCInvokesConcurrent");
                break;
            case G1_THROUGHPUT:
                out.add("-XX:+UseG1GC");
                out.add("-XX:MaxGCPauseMillis=120");
                out.add("-XX:ParallelGCThreads=" + gcThreads(policy, 4));
                out.add("-XX:ConcGCThreads=" + concThreads(policy));
                out.add("-XX:+ExplicitGCInvokesConcurrent");
                out.add("-XX:+ParallelRefProcEnabled");
                break;
            case ERGONOMIC:
            default:
                break;
        }

        // ---- experimental: needs the benchmark before it is allowed to be a default ----
        if (policy.experimentalJvmFlags) {
            int codeCacheMb = codeCacheMb(policy);
            if (codeCacheMb > 0) {
                out.add("-XX:ReservedCodeCacheSize=" + codeCacheMb + "m");
                out.add("-XX:+UseCodeCacheFlushing");
            }
            if (policy.mode == PerformanceMode.MAXIMUM && policy.game.isModern()) {
                // Larger young generation for allocation-heavy modern clients, expressed as a
                // percentage so it scales with whatever heap the user picked.
                out.add("-XX:G1NewSizePercent=40");
                out.add("-XX:G1MaxNewSizePercent=60");
            }
        }

        return Collections.unmodifiableList(out);
    }

    private static int gcThreads(PerformancePolicy policy, int cap) {
        int cores = policy.device.cores;
        if (cores <= 2) return 1;
        return Math.max(2, Math.min(cap, cores / 2));
    }

    private static int concThreads(PerformancePolicy policy) {
        // Concurrent marking must not compete with the render thread on a phone.
        return policy.device.cores >= 8 ? 2 : 1;
    }

    /** Bounded code cache: never more than 8% of device RAM, clamped to a sane window. */
    private static int codeCacheMb(PerformancePolicy policy) {
        int total = policy.device.totalRamMb;
        if (total <= 0) return 0; // unknown device — leave the JVM default alone
        int budget = (int) (total * 0.08f);
        if (policy.mode == PerformanceMode.MAXIMUM) budget = Math.max(budget, 192);
        else budget = Math.max(budget, 128);
        return Math.max(96, Math.min(256, budget));
    }
}
