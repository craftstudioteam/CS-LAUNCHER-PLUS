package net.kdt.pojavlaunch.performance;

import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Driver-environment decisions, and the only place that decides them.
 *
 * <p>Two problems with the previous code, both fixed here: Mesa knobs were written for whichever
 * renderer happened to be selected — including drivers that are not Mesa at all — with a 512 MB
 * shader-cache budget, and the mailbox present mode that unlocked the frame rate was tied to a
 * "Performance Boost" switch instead of the player's vsync choice. So a player who turned the boost off
 * silently inherited a 60 FPS driver lock, while a player who had vsync ON in Minecraft was overridden
 * anyway. Now the pacing decision belongs to the player's own settings in both directions, the
 * driver-specific variables are gated on the driver family that actually reads them, and the cache is
 * sized from device RAM.
 *
 * <p>Normal keeps the pacing unlock (it is not an accelerator, it is the absence of a lock) and skips
 * the rest of the driver retuning.
 */
public final class RendererPolicy {

    private RendererPolicy() {}

    /**
     * The player's own pacing choices, in the order they are meant to win: the launcher's explicit
     * "Force VSync" switch first, then Minecraft's own vsync option. Unknown is treated as "not asked
     * for", because a launcher-side ceiling is the failure mode being fixed here — an absent answer
     * must never become a forced wait.
     */
    public static boolean playerAskedForVsync(McContext game) {
        try {
            if (net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_FORCE_VSYNC) return true;
        } catch (Throwable ignored) {
        }
        return game != null && game.mcVsyncEnabled();
    }

    /** Which of the two possible owners is asking for vsync, for the diagnostics line. */
    public static String vsyncReason(McContext game) {
        boolean launcherSwitch = false;
        try {
            launcherSwitch = net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_FORCE_VSYNC;
        } catch (Throwable ignored) {
        }
        if (launcherSwitch) return "launcher Force VSync switch";
        if (game != null && game.mcVsyncEnabled()) return "Minecraft options.txt vsync:true";
        return "none";
    }

    /**
     * @param mode the requested profile; only affects the driver-specific tuning, never the pacing
     */
    public static Map<String, String> envFor(PerformanceMode mode, DeviceCapability device,
                                             McContext game, boolean experimental,
                                             List<String> unavailableOut) {
        Map<String, String> env = new LinkedHashMap<>();
        String renderer = game == null ? "" : game.rendererId.toLowerCase(Locale.ROOT);
        boolean mesaBacked = renderer.contains("zink") || renderer.contains("gallium");

        // ── pacing: the launcher must never be the reason a frame is late ──
        // Two locks exist and both are only ever there because of a vsync choice. If the player did not
        // make one, neither is allowed to stand: POJAV_VSYNC_IN_ZINK lets the game's swap interval reach
        // the native window on the osmesa path, and mailbox stops Mesa's Vulkan WSI from waiting. This
        // applies to every profile — the old engine only did it while "Performance Boost" was on, so
        // turning that switch off (or dropping it, as this round did) silently reinstated a 60 FPS lock.
        // Conversely, when the player *did* pick vsync, the launcher now stays out of it instead of
        // overriding them with mailbox the way the old unconditional version did.
        if (!playerAskedForVsync(game)) {
            // Mesa/Vulkan WSI: mailbox is what stops the driver waiting for a page flip before it will
            // hand the buffer back. It applies to every profile because it is not an accelerator, it is
            // the removal of a wait — the previous engine tied it to a "Performance Boost" switch, so a
            // player who had that switch off (or a preset that had written vsync_in_zink=false) silently
            // inherited a 60 FPS lock in the balanced profile.
            if (mesaBacked) env.put("MESA_VK_WSI_PRESENT_MODE", "mailbox");
            // The osmesa bridge (vulkan_zink) reads POJAV_VSYNC_IN_ZINK as a gate on whether the game's
            // own eglSwapInterval(0) is allowed to reach the native window. With the gate closed the
            // driver keeps its default lock. Defaulting the switch is on, so this only fires for installs
            // where somebody — usually the old "Maximum FPS" preset, not a crash report — turned it off;
            // overriding it is therefore limited to the two profiles whose whole job is to not hold back,
            // and it is written into the card as a note instead of happening invisibly.
            if (mode != null && mode.requestsBoost() && !LauncherPreferences.PREF_VSYNC_IN_ZINK) {
                env.put("POJAV_VSYNC_IN_ZINK", "1");
                // Not a card lever (nothing was withheld), but it must not be invisible either: it is a
                // reversal of a stored setting, so it is logged and it shows up in the launch chain.
                android.util.Log.i("CSPerf", "Zink swap-interval gate reopened for the " + mode.key
                        + " profile (the launcher's VSync-in-Zink switch was off; the switch's own"
                        + " warning still applies — turn it back on if a driver update makes Zink crash)");
            }
        }

        if (mode == null || mode == PerformanceMode.NORMAL) {
            // Balanced profile: no driver retuning beyond the pacing above.
            return env;
        }

        if (!mesaBacked) {
            // GL4ES, Angle, MobileGlues and the Vulkan-native paths are not Mesa: writing MESA_* or
            // TU_* variables there is a no-op at best. Say so instead of pretending a lever was pulled.
            if (unavailableOut != null) {
                unavailableOut.add("no driver tuning for the selected renderer ("
                        + (renderer.isEmpty() ? "not chosen yet" : renderer) + ")");
            }
            return env;
        }

        // Shader cache: real win (fewer recompiles while moving), sized to the device instead of the
        // fixed 512 MB the old engine asked for on phones with 3 GB of RAM.
        int totalRamMb = device == null ? 0 : device.totalRamMb;
        String cacheSize = totalRamMb >= 6144 ? "256M" : (totalRamMb >= 3584 ? "128M" : "64M");
        env.put("MESA_SHADER_CACHE_DISABLE", "false");
        env.put("MESA_SHADER_CACHE_MAX_SIZE", cacheSize);

        if (renderer.contains("zink")) {
            // Deferred descriptor updates: the documented Zink fast path.
            env.put("ZINK_DESCRIPTORS", "lazy");
            if (device != null && device.isAdreno()) env.put("TU_DEBUG", "noconform");
        }

        if (experimental) {
            // Documented but driver-sensitive: threaded command submission has been implicated in
            // crashes on some builds, so it stays opt-in until there is a number for it.
            env.put("mesa_glthread", "true");
            env.put("ZINK_DEBUG", "compact");
        }

        return env;
    }

    /**
     * Device-specific renderer recommendation. Never a hardcoded "X is fastest": it reads the
     * capability gate for what this phone can load, then the launcher's own measured frame times for
     * what this phone was actually fast at. No samples means no claim — the text says so.
     */
    public static String recommendationText(android.content.Context ctx, DeviceCapability device) {
        List<String> options = new ArrayList<>();
        try {
            Tools.RenderersList compatible = Tools.getCompatibleRenderers(ctx);
            if (compatible != null && compatible.rendererIds != null) {
                options.addAll(compatible.rendererIds);
            }
        } catch (Throwable ignored) {
        }
        if (options.isEmpty()) return "Renderer: nothing compatible is installed on this device.";

        Map<String, Float> measured = SessionStats.bestMedianFpsByRenderer(ctx);
        String best = null;
        float bestFps = 0f;
        for (String id : options) {
            Float fps = measured.get(id);
            if (fps != null && fps > bestFps) {
                bestFps = fps;
                best = id;
            }
        }

        StringBuilder b = new StringBuilder("Renderer options on this phone: ");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) b.append(", ");
            Float fps = measured.get(options.get(i));
            b.append(options.get(i));
            if (fps != null) b.append(" (").append((int) (float) fps).append(" fps measured)");
        }
        if (best == null) {
            b.append("\nNo measured sessions yet — leave the renderer on Automatic; ")
                    .append("recommendations appear once this device has played on more than one option.");
        } else {
            b.append("\nMeasured fastest here: ").append(best).append(" (")
                    .append((int) bestFps).append(" fps median). Device-specific — not a general rule.");
        }
        return b.toString();
    }
}
