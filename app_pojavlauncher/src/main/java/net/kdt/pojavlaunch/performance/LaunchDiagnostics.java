package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Map;

/**
 * One block of text, written once per launch, that names every place a frame can be waited on.
 *
 * <p>This exists because "why is the game at 60?" is not answerable from the launcher UI: the cap can
 * live in Minecraft's {@code options.txt}, in the launcher's Force VSync switch, in the swap-interval
 * environment variables the native bridges read, in the display mode the panel picked, or in a sustained
 * performance flag on the window. Each of those is owned by different code, so the only honest way to
 * show the state is to print all of it together at the moment it is finally committed — right before the
 * JVM is started, on the launch thread, which is why no UI thread ever calls in here.
 *
 * <p>Nothing in this class changes anything. It reads, it logs, and it leaves one file behind
 * ({@code <data>/performance/launch_diagnostics.txt}) for a bug report.
 */
public final class LaunchDiagnostics {
    /** Shared logcat tag for the engine; {@code adb logcat -s CSPerf} gets the whole story. */
    public static final String TAG = "CSPerf";

    private LaunchDiagnostics() {
    }

    public static void write(Context ctx, Map<String, String> env, PerformancePolicy policy) {
        String text = build(ctx, env, policy);
        Log.i(TAG, text);
        if (ctx == null) return;
        FileOutputStream out = null;
        try {
            File dir = new File(net.kdt.pojavlaunch.Tools.DIR_DATA, "performance");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, "launch_diagnostics.txt");
            out = new FileOutputStream(file);
            OutputStreamWriter w = new OutputStreamWriter(out, "UTF-8");
            w.write(text);
            w.write('\n');
            w.flush();
            // Deliberately not closing the FileOutputStream separately: closing the stream is enough,
            // and this path must not throw if the partition is full.
        } catch (Throwable t) {
            Log.d(TAG, "diagnostics file not written: " + t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static String build(Context ctx, Map<String, String> env, PerformancePolicy policy) {
        StringBuilder sb = new StringBuilder(768);
        sb.append("=== CS presentation chain ===\n");
        sb.append("mode=")
                .append(policy == null ? "unresolved" : policy.mode.key)
                .append("  device=")
                .append(policy == null ? "?" : (policy.device.socModel + ", " + policy.device.cores + " cores"))
                .append('\n');

        // ── what the player asked for: these are the only legitimate reasons to wait for a frame ──
        McContext game = policy == null ? null : policy.game;
        sb.append("mc options: vsync=").append(game == null ? "unknown" : String.valueOf(game.mcVsyncEnabled()))
                .append("  maxFps=").append(fpsLabel(game)).append('\n');
        boolean launcherVsync = false;
        try {
            launcherVsync = net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_FORCE_VSYNC;
        } catch (Throwable ignored) {
        }
        sb.append("launcher: force_vsync=").append(launcherVsync)
                .append("  sustained_performance_mode=false (the launcher never requests it; on Android")
                .append(" that flag means \"use the reduced sustained refresh rate\", i.e. a ceiling)\n");

        // ── what the native layer will actually read ──
        sb.append("env: FORCE_VSYNC=").append(value(env, "FORCE_VSYNC"))
                .append("  POJAV_VSYNC_IN_ZINK=").append(present(env, "POJAV_VSYNC_IN_ZINK"))
                .append("  MESA_VK_WSI_PRESENT_MODE=").append(present(env, "MESA_VK_WSI_PRESENT_MODE"))
                .append('\n');
        sb.append("  gl4es path: swap interval is clamped to 1 only when FORCE_VSYNC=true;\n");
        sb.append("  osmesa path: with POJAV_VSYNC_IN_ZINK unset the driver keeps its own lock;\n");
        sb.append("  Zink/Mesa: present mode mailbox = no wait, fifo = vsync at the panel rate.\n");

        // ── fidelity, so an A/B run can be shown to be comparing like with like ──
        // Read straight out of the profile's options.txt and the launcher's scale factor. These are the
        // numbers a benchmark has to hold constant, and the reason they are in the same block as the
        // pacing state is that a frame-rate claim is only meaningful when nothing else moved.
        if (game != null) {
            sb.append("fidelity: renderDistance=").append(orUnknown(game.mcOption("renderDistance")))
                    .append("  graphicsMode=").append(orUnknown(game.mcOption("graphicsMode")))
                    .append("  particles=").append(orUnknown(game.mcOption("particleSettings")))
                    .append("  entityDistance=").append(orUnknown(game.mcOption("entityDistanceScaling")))
                    .append("  launcherScale=")
                    .append(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SCALE_FACTOR)
                    .append('\n');
        }

        // ── the panel: the highest rate available is the goal, not the rate currently in use ──
        sb.append("display: ").append(displaySummary(ctx)).append('\n');

        // ── scheduler / thermal: can only add capacity, never take frames away ──
        sb.append("adpf: ").append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "available (session target = highest supported refresh, not the active mode)"
                : "needs Android 12").append('\n');
        sb.append("thermal: ").append(ThermalGovernor.describe(ctx)).append('\n');
        sb.append("note: no per-frame work, no sleeps, no frame limiter, no resolution change").append('\n');

        if (policy != null) {
            sb.append("policy levers:\n").append(indent(policy.describe())).append('\n');
            if (!policy.unavailable.isEmpty()) {
                sb.append("not applied: ").append(join(policy.unavailable)).append('\n');
            }
        }
        sb.append("verdict: ").append(verdict(launcherVsync, game, env));
        return sb.toString();
    }

    private static String fpsLabel(McContext game) {
        if (game == null) return "unknown";
        int v = game.mcMaxFps();
        if (v < 0) return "unreadable (options.txt missing or unreadable)";
        if (v == 0) return "Unlimited";
        return String.valueOf(v);
    }

    private static String indent(String text) {
        return text == null ? "" : text.replace("\n", "\n  ");
    }

    private static String join(java.util.List<String> items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(", ");
            b.append(items.get(i));
        }
        return b.toString();
    }

    private static String orUnknown(String v) {
        return v == null || v.isEmpty() ? "unset" : v;
    }

    private static String value(Map<String, String> env, String key) {
        return env == null ? "n/a" : String.valueOf(env.get(key));
    }

    private static String present(Map<String, String> env, String key) {
        if (env == null) return "n/a";
        String v = env.get(key);
        return v == null ? "absent" : v;
    }

    /**
     * Supported modes, highest first-of-all, plus the mode the panel is in right now. Only API 30+
     * exposes the mode list, so older devices report the single rate they have.
     */
    private static String displaySummary(Context ctx) {
        try {
            Display d = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                d = ctx == null ? null : ctx.getDisplay();
            } else if (ctx instanceof android.app.Activity) {
                d = ((android.app.Activity) ctx).getWindowManager().getDefaultDisplay();
            }
            if (d == null) return "no display handle (launcher not foregrounded)";
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return "active " + Math.round(d.getRefreshRate() * 10f) / 10f + " Hz"
                        + " (mode list needs Android 11)";
            }
            Display.Mode[] modes = d.getSupportedModes();
            float maxHz = 0f;
            for (Display.Mode m : modes) {
                maxHz = Math.max(maxHz, m.getRefreshRate());
            }
            StringBuilder s = new StringBuilder();
            s.append("active ").append(Math.round(d.getRefreshRate() * 10f) / 10f).append(" Hz")
                    .append(", highest supported ").append(Math.round(maxHz * 10f) / 10f)
                    .append(" Hz over ").append(modes.length).append(" mode(s): ");
            for (int i = 0; i < modes.length; i++) {
                if (i > 0) s.append('/');
                s.append(Math.round(modes[i].getRefreshRate() * 10f) / 10f);
            }
            return s.toString();
        } catch (Throwable t) {
            return "unavailable (" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * The one-line answer the report needs. It is a deduction from the levers above, not a measurement:
     * the launcher cannot know what Minecraft's render loop decides, so an unlimited verdict here still
     * has to be confirmed with on-device FPS numbers.
     */
    private static String verdict(boolean launcherVsync, McContext game, Map<String, String> env) {
        if (launcherVsync) {
            return "FRAME RATE HELD BY THE LAUNCHER'S FORCE VSYNC SWITCH — turn it off to exceed the"
                    + " panel rate";
        }
        if (game != null && game.mcVsyncEnabled()) {
            return "paced by Minecraft's own VSync setting (Video Settings → Use VSync) — that is the"
                    + " game's choice and the launcher will not override it";
        }
        int fps = game == null ? -1 : game.mcMaxFps();
        if (fps > 0) {
            return "capped at " + fps + " fps by Minecraft's FPS slider (options.txt maxFps)";
        }
        if ("1".equals(env == null ? null : env.get("POJAV_VSYNC_IN_ZINK"))
                || "mailbox".equals(env == null ? null : env.get("MESA_VK_WSI_PRESENT_MODE"))) {
            return "UNCAPPED — no launcher or driver-side wait is in effect; anything below the panel's"
                    + " peak is a GPU/CPU limit or a system thermal limit";
        }
        return "no launcher-side cap found, but no present-mode unlock was applied either — check the"
                + " renderer-specific lines above";
    }
}
