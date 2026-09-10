package net.kdt.pojavlaunch.performance;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.GLInfoUtils;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What this phone actually is, measured once and cached.
 *
 * <p>Everything in here is a public API — {@link ActivityManager} for memory, {@link Build} for the
 * SoC, {@link PackageManager} feature flags for Vulkan, the existing GL probe for the GPU string and
 * the GLES level, and {@link Tools#getCompatibleRenderers(Context)} for which renderer libraries are
 * really present. Nothing reads {@code /sys} and nothing scans {@code /proc}: cluster topology is not
 * a thing an app is allowed to know on modern Android, so it is not assumed here either — the policy
 * engine gets cores and RAM, and the scheduler decides where to put work.
 *
 * <p>Building it creates a throwaway EGL context, so it is assembled on one background thread and
 * only when no game session is running. {@link #current()} never blocks and never returns null:
 * callers get a {@code partial} snapshot (safe defaults, "unknown" strings) until the real one lands.
 */
public final class DeviceCapability {

    /** App-private storage, like the server store and the session marker: always readable. */
    private static final String DIR = "performance";
    private static final String FILE = "device.json";

    private static volatile DeviceCapability sCurrent = new DeviceCapability();
    private static volatile boolean sBuilding;

    // ── identity ──
    public final String socModel;
    public final String gpuVendor;
    public final String gpuRenderer;
    public final String abi;
    public final int sdkInt;

    // ── capacity ──
    public final int cores;
    public final int totalRamMb;
    public final int availRamMb;
    public final boolean lowMemoryNow;
    public final boolean is64Bit;
    public final boolean vulkanSupported;
    public final int glesMajorVersion;

    /** Renderer ids the app can actually load on this device, in launcher order. */
    public final List<String> availableRenderers;

    /** True until the background build replaces it — the policy then runs on real numbers. */
    public final boolean partial;

    /** Everything from a device with no data: safe, unassuming, and honest about being unknown. */
    private DeviceCapability() {
        socModel = "unknown";
        gpuVendor = "unknown";
        gpuRenderer = "unknown";
        abi = primaryAbi();
        sdkInt = Build.VERSION.SDK_INT;
        cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        totalRamMb = 0;
        availRamMb = 0;
        lowMemoryNow = false;
        is64Bit = abi.contains("64");
        vulkanSupported = false;
        glesMajorVersion = 0;
        availableRenderers = Collections.emptyList();
        partial = true;
    }

    private DeviceCapability(String soc, String vendor, String renderer, String abi, int cores,
                             int totalRam, int availRam, boolean lowMem, boolean vulkan, int glesMajor,
                             List<String> renderers) {
        this.socModel = soc;
        this.gpuVendor = vendor;
        this.gpuRenderer = renderer;
        this.abi = abi;
        this.sdkInt = Build.VERSION.SDK_INT;
        this.cores = Math.max(1, cores);
        this.totalRamMb = totalRam;
        this.availRamMb = availRam;
        this.lowMemoryNow = lowMem;
        this.is64Bit = abi.contains("64");
        this.vulkanSupported = vulkan;
        this.glesMajorVersion = glesMajor;
        this.availableRenderers = Collections.unmodifiableList(new ArrayList<>(renderers));
        this.partial = false;
    }

    public static DeviceCapability current() {
        return sCurrent;
    }

    public boolean isAdreno() {
        return gpuRenderer != null && gpuRenderer.toLowerCase().contains("adreno");
    }

    public boolean isMali() {
        return gpuRenderer != null && gpuRenderer.toLowerCase().contains("mali");
    }

    /** Vulkan is only worth asking about where the driver is known to be usable for Zink. */
    public boolean hasMatureVulkan() {
        return vulkanSupported && (isAdreno() || isMali()) && glesMajorVersion >= 3;
    }

    /** A "low-end" device for policy purposes: little RAM and/or a narrow, 32-bit core set. */
    public boolean isLowEnd() {
        if (totalRamMb <= 0) return false; // unknown is not the same as weak
        return totalRamMb < 3072 || (cores <= 4 && !is64Bit);
    }

    // ── build & cache ──

    /** Kick off a rebuild if we have nothing usable yet. Never blocks, never throws. */
    public static void ensureAsync(final Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (!sCurrent.partial) {
            // Re-read the free-memory numbers cheaply instead of rebuilding the whole profile.
            sCurrent = sCurrent.withLiveMemory(app);
            return;
        }
        if (sBuilding || GameSessionState.isGameActiveCached()) return;
        sBuilding = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    DeviceCapability cached = readCache(app);
                    if (cached != null) {
                        sCurrent = cached.withLiveMemory(app);
                        return;
                    }
                    DeviceCapability built = build(app);
                    if (built != null) {
                        sCurrent = built;
                        writeCache(app, built);
                    }
                } catch (Throwable ignored) {
                    // stay on the partial snapshot; a missing profile must not break settings
                } finally {
                    sBuilding = false;
                }
            }
        }, "CS-DeviceCap").start();
    }

    private DeviceCapability withLiveMemory(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return this;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return new DeviceCapability(socModel, gpuVendor, gpuRenderer, abi, cores,
                    Math.max(totalRamMb, (int) (mi.totalMem / 1048576L)),
                    (int) (mi.availMem / 1048576L), mi.lowMemory, vulkanSupported,
                    glesMajorVersion, availableRenderers);
        } catch (Throwable ignored) {
            return this;
        }
    }

    private static DeviceCapability build(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);

            boolean vulkan;
            try {
                vulkan = Tools.checkVulkanSupport(app.getPackageManager());
            } catch (Throwable t) {
                vulkan = false;
            }

            String vendor = "unknown", renderer = "unknown";
            int gles = 0;
            try {
                GLInfoUtils.GLInfo info = GLInfoUtils.getGlInfo();
                if (info != null) {
                    vendor = info.vendor != null ? info.vendor : vendor;
                    renderer = info.renderer != null ? info.renderer : renderer;
                    gles = info.glesMajorVersion;
                }
            } catch (Throwable ignored) {
                // no GL on this thread/device — the rest of the profile is still useful
            }

            List<String> renderers = new ArrayList<>();
            try {
                Tools.RenderersList list = Tools.getCompatibleRenderers(app);
                if (list != null && list.rendererIds != null) renderers.addAll(list.rendererIds);
            } catch (Throwable ignored) {
            }

            return new DeviceCapability(socModel(), vendor, renderer, primaryAbi(),
                    Math.max(1, Runtime.getRuntime().availableProcessors()),
                    (int) (mi.totalMem / 1048576L), (int) (mi.availMem / 1048576L), mi.lowMemory,
                    vulkan, gles, renderers);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String socModel() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String soc = Build.SOC_MODEL;
                if (soc != null && !soc.isEmpty()) {
                    String maker = Build.SOC_MANUFACTURER;
                    return maker == null || maker.isEmpty() ? soc : maker + " " + soc;
                }
            }
        } catch (Throwable ignored) {
            // Build.SOC_* is API 31; older builds keep using HARDWARE below
        }
        return Build.HARDWARE != null ? Build.HARDWARE : "unknown";
    }

    private static String primaryAbi() {
        try {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) return abis[0];
        } catch (Throwable ignored) {
        }
        return Build.CPU_ABI != null ? Build.CPU_ABI : "unknown";
    }

    /** A cache is only valid for the device it was measured on. */
    private static String deviceKey() {
        return Build.FINGERPRINT + "|" + Build.BOARD + "|"
                + Runtime.getRuntime().availableProcessors() + "|" + Build.VERSION.SDK_INT;
    }

    private static File cacheFile(Context ctx) {
        return new File(new File(Tools.DIR_DATA, DIR), FILE);
    }

    private static void writeCache(Context app, DeviceCapability cap) {
        try {
            File out = cacheFile(app);
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            JSONObject o = new JSONObject();
            o.put("key", deviceKey());
            o.put("soc", cap.socModel);
            o.put("vendor", cap.gpuVendor);
            o.put("renderer", cap.gpuRenderer);
            o.put("abi", cap.abi);
            o.put("cores", cap.cores);
            o.put("totalRamMb", cap.totalRamMb);
            o.put("vulkan", cap.vulkanSupported);
            o.put("glesMajor", cap.glesMajorVersion);
            o.put("renderers", cap.availableRenderers);
            o.put("writtenAt", System.currentTimeMillis());
            Tools.write(out.getAbsolutePath(), o.toString());
        } catch (Throwable ignored) {
        }
    }

    private static DeviceCapability readCache(Context app) {
        try {
            File f = cacheFile(app);
            if (!f.isFile()) return null;
            JSONObject o = new JSONObject(Tools.read(f));
            if (!deviceKey().equals(o.optString("key", ""))) return null;
            List<String> renderers = new ArrayList<>();
            org.json.JSONArray arr = o.optJSONArray("renderers");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) renderers.add(arr.optString(i, ""));
            }
            return new DeviceCapability(o.optString("soc", "unknown"),
                    o.optString("vendor", "unknown"), o.optString("renderer", "unknown"),
                    o.optString("abi", primaryAbi()), o.optInt("cores", 1),
                    o.optInt("totalRamMb", 0), 0, false, o.optBoolean("vulkan", false),
                    o.optInt("glesMajor", 0), renderers).withLiveMemory(app);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── presentation ──

    /** The line the settings card shows, so the user can see what the engine thinks this phone is. */
    public String describe() {
        if (partial) return "Device profile: building…";
        StringBuilder b = new StringBuilder("Device profile: ").append(socModel)
                .append(" · ").append(cores).append(" cores")
                .append(" · ").append(totalRamMb).append(" MB RAM");
        if (!"unknown".equals(gpuRenderer)) b.append(" · ").append(gpuRenderer);
        if (glesMajorVersion > 0) b.append(" · GLES ").append(glesMajorVersion);
        b.append(vulkanSupported ? " · Vulkan" : " · no Vulkan");
        if (availRamMb > 0) b.append(" · ").append(availRamMb).append(" MB free");
        if (!availableRenderers.isEmpty()) b.append("\nRenderers present: ").append(
                Arrays.toString(availableRenderers.toArray(new String[0]))
                        .replace("[", "").replace("]", ""));
        return b.toString();
    }

    @Override public String toString() {
        return describe();
    }
}
