package net.kdt.pojavlaunch;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static net.kdt.pojavlaunch.Architecture.archAsStringAndroid;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;
import static net.kdt.pojavlaunch.PojavProfile.getAllProfiles;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_IGNORE_NOTCH;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_NOTCH_SIZE;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.lifecycle.LifecycleAwareAlertDialog;
import net.kdt.pojavlaunch.memory.MemoryHoleFinder;
import net.kdt.pojavlaunch.memory.SelfMapsParser;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.plugins.FFmpegPlugin;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.servers.ServerEntry;
import net.kdt.pojavlaunch.servers.ServerListSync;
import net.kdt.pojavlaunch.servers.ServerStore;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.utils.DateUtils;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GLInfoUtils;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.JSONUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.utils.OldVersionsUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.libsdl.app.SDLControllerManager;
import org.lwjgl.glfw.CallbackBridge;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressWarnings("IOStreamConstructor")
public final class Tools {
    public  static final float BYTE_TO_MB = 1024 * 1024;
    public static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    public static String APP_NAME = "CS LAUNCHER PLUS";

    public static final Gson GLOBAL_GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String URL_HOME = "https://angelauramc.dev/wiki";
    public static String NATIVE_LIB_DIR;
    public static String DIR_DATA; //Initialized later to get context
    public static File DIR_CACHE;
    public static String MULTIRT_HOME;
    public static String LOCAL_RENDERER = null;
    public static int DEVICE_ARCHITECTURE;
    public static final String LAUNCHERPROFILES_RTPREFIX = "amethyst://";

    // New since 3.3.1
    public static String DIR_ACCOUNT_NEW;
    public static String DIR_GAME_HOME = Environment.getExternalStorageDirectory().getAbsolutePath() + "/games/Amethyst";
    public static String DIR_GAME_NEW;

    // New since 3.0.0
    public static String DIRNAME_HOME_JRE = "lib";

    // New since 2.4.2
    public static String DIR_HOME_VERSION;
    public static String DIR_HOME_LIBRARY;

    public static String DIR_HOME_CRASH;

    public static String ASSETS_PATH;
    public static String OBSOLETE_RESOURCES_PATH;
    public static String CTRLMAP_PATH;
    public static String CTRLDEF_FILE;
    public static String DIR_CURSORS;
    private static RenderersList sCompatibleRenderers;
    public static int iLwjglVersion = 0;
    public static String sLwjglVersion = null;
    public static String lwjglNativesDir = null;


    public static File getPojavStorageRoot(Context ctx) {
        if(SDK_INT >= 29) {
            return ctx.getExternalFilesDir(null);
        }else{
            return new File(Environment.getExternalStorageDirectory(),"games/Amethyst");
        }
    }

    /**
     * Checks if the Pojav's storage root is accessible and read-writable
     * @param context context to get the storage root if it's not set yet
     * @return true if storage is fine, false if storage is not accessible
     */
    public static boolean checkStorageRoot(Context context) {
        File externalFilesDir = DIR_GAME_HOME  == null ? Tools.getPojavStorageRoot(context) : new File(DIR_GAME_HOME);
        //externalFilesDir == null when the storage is not mounted if it was obtained with the context call
        return externalFilesDir != null && Environment.getExternalStorageState(externalFilesDir).equals(Environment.MEDIA_MOUNTED);
    }

    /**
     * Checks if the Pojav's storage root is accessible and read-writable. If it's not, starts
     * the MissingStorageActivity and finishes the supplied activity.
     * @param context the Activity that checks for storage availability
     * @return whether the storage is available or not.
     */
    public static boolean checkStorageInteractive(Activity context) {
        if(!Tools.checkStorageRoot(context)) {
            context.startActivity(new Intent(context, MissingStorageActivity.class));
            context.finish();
            return false;
        }
        return true;
    }

    /**
     * Initialize context constants most necessary for launcher's early startup phase
     * that are not dependent on user storage.
     * All values that depend on DIR_DATA and are not dependent on DIR_GAME_HOME must
     * be initialized here.
     * @param ctx the context for initialization.
     */
    public static void initEarlyConstants(Context ctx) {
        DIR_CACHE = ctx.getCacheDir();
        DIR_DATA = ctx.getFilesDir().getParent();
        MULTIRT_HOME = DIR_DATA + "/runtimes";
        DIR_ACCOUNT_NEW = DIR_DATA + "/accounts";
        NATIVE_LIB_DIR = ctx.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * Initialize context constants that depend on user storage.
     * Any value (in)directly dependent on DIR_GAME_HOME should be set only here.
     * You ABSOLUTELY MUST check for storage presence using checkStorageRoot() before calling this.
     */
    public static void initStorageConstants(Context ctx){
        initEarlyConstants(ctx);
        DIR_GAME_HOME = getPojavStorageRoot(ctx).getAbsolutePath();
        DIR_GAME_NEW = DIR_GAME_HOME + "/.minecraft";
        DIR_HOME_VERSION = DIR_GAME_NEW + "/versions";
        DIR_HOME_LIBRARY = DIR_GAME_NEW + "/libraries";
        DIR_HOME_CRASH = DIR_GAME_NEW + "/crash-reports";
        ASSETS_PATH = DIR_GAME_NEW + "/assets";
        OBSOLETE_RESOURCES_PATH = DIR_GAME_NEW + "/resources";
        CTRLMAP_PATH = DIR_GAME_HOME + "/controlmap";
        CTRLDEF_FILE = DIR_GAME_HOME + "/controlmap/default.json";
        DIR_CURSORS = DIR_GAME_HOME + "/cursors";
    }

    @SuppressLint("PrivateApi")
    private static String systemPropertiesGet(String systemProperty) throws NoSuchMethodException, ClassNotFoundException, InvocationTargetException, IllegalAccessException {
        Class<?> cSystemProperties = Class.forName("android.os.SystemProperties");
        Method get = cSystemProperties.getMethod("get", String.class);
        return (String) get.invoke(null, systemProperty);
    }

    private static boolean isAdreno740(){
        try {
            BufferedReader br = new BufferedReader(
                    new FileReader("/sys/class/kgsl/kgsl-3d0/gpu_model")
            );
            String gpuRenderer = br.readLine();
            return gpuRenderer != null &&
                    gpuRenderer.toLowerCase().contains("adreno") &&
                    gpuRenderer.contains("740");
        } catch (IOException e) {
            // If it doesn't exist, we definitely aren't on 740
            return false;
        }
    }

    /**
     * Detects whether or not you are on OneUI and using Adreno 740
     * <a href="https://gitlab.freedesktop.org/mesa/mesa/-/blob/main/src/freedreno/common/freedreno_devices.py?ref_type=heads#L1007-L1009">
     *     Mesa sets it to 0 by default due to vendor quirks
     * </a>
     * It is possible that OneUI simply deviates from this commonality, hence why
     * <a href="https://github.com/K11MCH1/AdrenoToolsDrivers/releases/tag/v26.0.0-rc07">
     *     this is a common fix
     * </a>
     * @return Whether or not to export FD_DEV_FEATURES=enable_ubwc_flag_hint=1
     */
    public static boolean shouldUseUBWC() {
        try {
            boolean isSamsung = Build.MANUFACTURER.equalsIgnoreCase("samsung");
            boolean isOneUI = !systemPropertiesGet("ro.build.version.oneui").isBlank();
            return isOneUI && isSamsung && isAdreno740();
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * @return The selected "Custom path" of the current profile
     */
    @NonNull
    private static File getGameDir() {
        return getGameDirPath(LauncherProfiles.getCurrentProfile());
    }

    /**
     * Searches for mod in mods directory of current selected profile
     * Not case-sensitive
     * @param filenames Filename(s) of the .jar mod(s)
     * @return Whether or not the .jar is found
     */
    private static volatile net.kdt.pojavlaunch.performance.LaunchSnapshot sLaunchSnapshot;

    public static boolean hasMods(String... filenames) {
        File gameDir = getGameDir();
        net.kdt.pojavlaunch.performance.LaunchSnapshot snapshot = sLaunchSnapshot;
        if (snapshot != null && snapshot.matchesGameDirectory(gameDir)) return snapshot.containsAny(filenames);
        File modsDir = new File(gameDir, "mods");
        File[] modFiles = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if (modFiles == null) return false;
        for (File file : modFiles) {
            for (String filename : filenames)
                if (file.getName().toLowerCase().contains(filename.toLowerCase())) return true;
        }
        return false;
    }

    /**
     * Search for TouchController mod to automatically enable TouchController mod support.
     *
     * @param gameDir current game directory
     * @return whether TouchController is found
     */
    public static boolean hasTouchController(File gameDir) {
        File modsDir = new File(gameDir, "mods");
        File[] mods = modsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if (mods == null) {
            return false;
        }
        for (File file : mods) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.contains("touchcontroller")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Initialize OpenGL and do checks to see if the GPU of the device is affected by the render
     * distance issue.

     * Currently only checks whether the user has an Adreno GPU capable of OpenGL ES 3.

     * This issue is caused by a very severe limit on the amount of GL buffer names that could be allocated
     * by the Adreno properietary GLES driver.

     * @return whether the GPU is affected by the Large Thin Wrapper render distance issue on vanilla
     */
    private static boolean affectedByRenderDistanceIssue() {
        GLInfoUtils.GLInfo info = GLInfoUtils.getGlInfo();
        return info.isAdreno() && info.glesMajorVersion >= 3;
    }

    private static String[] sodiumMods = {"sodium", "embeddium", "rubidium", "xenon"};

    private static boolean affectedByLTWRenderDistanceIssue() {
        if(!"opengles3_ltw".equals(Tools.LOCAL_RENDERER)) return false;
        if(!affectedByRenderDistanceIssue()) return false;
        if(hasMods(sodiumMods)) return false;

        int renderDistance;
        try {
            MCOptionUtils.load();
            String renderDistanceString = MCOptionUtils.get("renderDistance");
            renderDistance = Integer.parseInt(renderDistanceString);
        }catch (Exception e) {
            // CS fix: a missing/unparseable key must NEVER trigger a rewrite of
            // the user's options.txt (assuming "12" could force renderDistance=7
            // onto a profile that never asked for it — user report: render
            // distance keeps changing). No key → not affected → do not touch.
            Log.e("Tools", "Failed to check render distance", e);
            return false;
        }
        // 7 is the render distance "magic number" above which MC creates too many buffers
        // for Adreno's OpenGL ES implementation
        return renderDistance > 7;
    }

    public static void launchMinecraft(final AppCompatActivity activity, MinecraftAccount minecraftAccount,
                                       MinecraftProfile minecraftProfile, String versionId, int versionJavaRequirement) throws Throwable {
        int freeDeviceMemory = getFreeDeviceMemory(activity);
        int localeString;
        int freeAddressSpace = Architecture.is32BitsDevice() ? getMaxContinuousAddressSpaceSize() : -1;
        Log.i("MemStat", "Free RAM: " + freeDeviceMemory + " Addressable: " + freeAddressSpace);
        if(freeDeviceMemory > freeAddressSpace && freeAddressSpace != -1) {
            freeDeviceMemory = freeAddressSpace;
            localeString = R.string.address_memory_warning_msg;
        } else {
            localeString = R.string.memory_warning_msg;
        }

        if(LauncherPreferences.PREF_RAM_ALLOCATION > freeDeviceMemory) {
            int finalDeviceMemory = freeDeviceMemory;
            int requestedMemory = LauncherPreferences.PREF_RAM_ALLOCATION;
            int suggestedMemory = Math.max(256, (Math.max(256, finalDeviceMemory - 256) / 128) * 128);
            java.util.concurrent.atomic.AtomicBoolean useSuggested = new java.util.concurrent.atomic.AtomicBoolean(false);
            LifecycleAwareAlertDialog.DialogCreator dialogCreator = (dialog, builder) ->
                builder.setTitle("Minecraft RAM warning")
                        .setMessage(activity.getString(localeString, finalDeviceMemory, requestedMemory)
                                + "\n\nSelected JVM heap: -Xmx" + requestedMemory + "M"
                                + "\nSuggested safe value: -Xmx" + suggestedMemory + "M")
                        .setPositiveButton("Launch with " + requestedMemory + " MB", (d, w)->{})
                        .setNegativeButton("Use " + suggestedMemory + " MB", (d, w)->useSuggested.set(true));

            if(LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator)) {
                return;
            }
            if (useSuggested.get()) {
                LauncherPreferences.PREF_RAM_ALLOCATION = suggestedMemory;
                LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", suggestedMemory).commit();
                // This runs in the game process; mirror it so the launcher does not overwrite it.
                net.kdt.pojavlaunch.prefs.SharedSettings.mirror(activity, "allocation", suggestedMemory);
            }
        }
        LauncherProfiles.load();
        File gamedir = Tools.getGameDirPath(minecraftProfile);
        String runtimeName = Tools.pickRuntime(minecraftProfile, versionJavaRequirement);
        Runtime runtime = MultiRTUtils.forceReread(runtimeName);
        File controlFile = minecraftProfile.controlFile == null ? new File(Tools.CTRLDEF_FILE)
                : new File(Tools.CTRLMAP_PATH, minecraftProfile.controlFile);
        sLaunchSnapshot = net.kdt.pojavlaunch.performance.LaunchSnapshot.create(
                minecraftProfile, gamedir, versionId, runtimeName, Tools.LOCAL_RENDERER,
                minecraftAccount, controlFile);
        Log.i("LaunchSnapshot", "mods=" + sLaunchSnapshot.modFileNames.size()
                + " fingerprint=" + sLaunchSnapshot.modDirectoryFingerprint);
        // CS PERFORMANCE ENGINE: one decision for the whole launch. The snapshot already knows the
        // version, the runtime, the renderer and the mod set, so the policy is resolved from it and
        // frozen here — before the JVM is created — and read back by the JVM, the driver-environment
        // and the session lifecycle. Nothing else gets to re-decide mid-flight.
        net.kdt.pojavlaunch.performance.PerformancePolicy.resolveAndFreeze(activity,
                LauncherPreferences.DEFAULT_PREF,
                net.kdt.pojavlaunch.performance.McContext.from(sLaunchSnapshot));
        startControllableMitigation(activity, gamedir);
        startOldLegacy4JMitigation(activity, gamedir);
        if(affectedByLTWRenderDistanceIssue()) {
            LifecycleAwareAlertDialog.DialogCreator dialogCreator = ((alertDialog, dialogBuilder) ->
                    dialogBuilder.setMessage(activity.getString(R.string.ltw_render_distance_warning_msg))
                            .setPositiveButton(android.R.string.ok, (d, w)->{}));
            if(LifecycleAwareAlertDialog.haltOnDialog(activity.getLifecycle(), activity, dialogCreator)) {
                return;
            }
            // Warning only: never alter the player's render distance automatically.
            // The user can explicitly choose a different renderer or setting.
        }


        JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(versionId);


        // Pre-process specific files
        disableSplash(gamedir);
        String[] launchArgs = getMinecraftClientArgs(minecraftAccount, versionInfo, gamedir);
        // ── Plus V1.0 Server Hub: 1-click auto-join injection ──
        // Two things happen here, and both used to be missing:
        //  1. servers.dat is re-synced right before the game starts, so hub servers really do
        //     appear in the in-game Multiplayer list (the game dir may not have existed when
        //     the server was added).
        //  2. the join flag is chosen per game version. --server/--port were REMOVED in 23w14a
        //     (1.20); modern versions only understand --quickPlayMultiplayer <host:port>.
        //     Sending the old flags to 1.20+ is silently ignored, which is exactly why
        //     auto-join "did nothing" on modern versions.
        try {
            String curProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
            ServerListSync.syncToGameDir(activity, curProfile, gamedir);
            String pending = ServerStore.getPending(activity, curProfile);
            if (pending != null && !pending.isEmpty()) {
                ServerEntry se = new ServerEntry(pending);
                String host = se.getHost().replace("[", "").replace("]", "");
                int port = se.getPort();
                List<String> tmp = new ArrayList<>(Arrays.asList(launchArgs));
                if (supportsQuickPlay(versionInfo)) {
                    String target = port == 25565 ? host : host + ":" + port;
                    // Never inject twice if the profile already carries the flag.
                    if (!tmp.contains("--quickPlayMultiplayer")) {
                        tmp.add("--quickPlayMultiplayer");
                        tmp.add(target);
                    }
                    Log.i("ServerHub", "Auto-join (Quick Play): --quickPlayMultiplayer " + target);
                } else {
                    if (!tmp.contains("--server")) {
                        tmp.add("--server"); tmp.add(host);
                        tmp.add("--port"); tmp.add(String.valueOf(port));
                    }
                    Log.i("ServerHub", "Auto-join (legacy): --server " + host + " --port " + port);
                }
                launchArgs = tmp.toArray(new String[0]);
                // One-shot: the next manual PLAY must not silently drag the player back in.
                ServerStore.clearAllPending(activity);
            }
        } catch (Exception e) { Log.w("ServerHub", "auto-join injection failed", e); }

        // Select the appropriate openGL version
        OldVersionsUtils.selectOpenGlVersion(versionInfo);


        String launchClasspath = generateLaunchClasspath(versionInfo, versionId);

        List<String> javaArgList = new ArrayList<>();

        getCacioJavaArgs(javaArgList, runtime.javaVersion == 8, activity);

        if (versionInfo.logging != null) {
            String configFile = Tools.DIR_DATA + "/security/" + versionInfo.logging.client.file.id.replace("client", "log4j-rce-patch");
            if (!new File(configFile).exists()) {
                configFile = Tools.DIR_GAME_NEW + "/" + versionInfo.logging.client.file.id;
            }
            javaArgList.add("-Dlog4j.configurationFile=" + configFile);
        }

        File versionSpecificNativesDir = new File(Tools.DIR_CACHE, "natives/"+versionId);
        StringBuilder javaLibraryPath = new StringBuilder();

        // Add which lwjgl natives to use into classpath
        javaLibraryPath.append(lwjglNativesDir).append(":");

        // Add JNA native if needed
        javaLibraryPath.append(Tools.NATIVE_LIB_DIR).append(":");
        if(versionSpecificNativesDir.exists()) {
            String dirPath = versionSpecificNativesDir.getAbsolutePath();
            javaLibraryPath.append(dirPath).append(":");
            javaArgList.add("-Djna.boot.library.path="+dirPath);
        }
        javaArgList.add("-Djava.library.path="+javaLibraryPath);

        javaArgList.addAll(Arrays.asList(getMinecraftJVMArgs(versionId, gamedir)));
        javaArgList.add("-cp"); javaArgList.add(launchClasspath);

        // Some modloaders (babric) don't fully respect java.libary.path and only use the native lib dir
        // This arg makes them use it. LWJGL prioritizes this path during native loading as well.
        javaArgList.add("-Dorg.lwjgl.librarypath="+lwjglNativesDir);

        // Forge 1.6.4 crash mitigation
        // https://github.com/MinecraftForge/FML/blob/f1b3381e61fac1a0ae90f521223c6bc613eb4888/common/cpw/mods/fml/common/asm/FMLSanityChecker.java#L192-L208
        // It for some reason fails certification and crashes because it thinks Minecraft is corrupted.
        // This also has no loading screen as a result.
        javaArgList.add("-Dfml.ignoreInvalidMinecraftCertificates=true");

        // imgui-java set library name to use. This because Axiom uses a fork with different library naming
        // logic that doesn't seem to appear in the main repository. I'm not gonna work with that.
        javaArgList.add("-Dimgui.library.name=imgui-java");
        // We use an abomination to support all DH versions with a single library.
        javaArgList.add("-DZstdNativePath="+Tools.NATIVE_LIB_DIR+"/libzstd-jni-1.5.7-6-dhcompat.so");
        // Snapshot-backed compatibility detection: scan /mods once per launch.
        boolean hasSodiumMod = hasMods(sodiumMods);
        if (hasSodiumMod) {
            for (String modName : sodiumMods) {
                File mixinPropertiesConfigFile = new File(getGameDir(), "config/" + modName + "-mixins.properties");
                if (!mixinPropertiesConfigFile.isFile()) continue;
                String[] propertiesToAdd = {
                        "mixin.features.buffer_builder.intrinsics=false",
                        "mixin.features.chunk_rendering=false"
                };
                List<String> lines;
                try { lines = org.apache.commons.io.FileUtils.readLines(mixinPropertiesConfigFile, "UTF-8"); }
                catch (IOException ignored) { continue; }
                boolean changed = false;
                for (String required : propertiesToAdd) {
                    if (!lines.contains(required)) { lines.add(required); changed = true; }
                }
                if (changed) {
                    try { org.apache.commons.io.FileUtils.writeLines(mixinPropertiesConfigFile, lines); }
                    catch (IOException ignored) {}
                }
            }
        }
        // We use a janky lwjgl setup. We don't want more people complaining it crashes.
        if (hasSodiumMod) javaArgList.add("-Dsodium.checks.issue2561=false");
        javaArgList.add(versionInfo.mainClass);
        javaArgList.addAll(Arrays.asList(launchArgs));
        // ctx.appendlnToLog("full args: "+javaArgList.toString());
        String args = LauncherPreferences.PREF_CUSTOM_JAVA_ARGS;
        if(Tools.isValidString(minecraftProfile.javaArgs)) args = minecraftProfile.javaArgs;
        FFmpegPlugin.discover(activity);
        try {
            JREUtils.launchJavaVM(activity, runtime, gamedir, javaArgList, args, minecraftAccount);
        } finally {
            stopControllableMitigation();
            sLaunchSnapshot = null;
        }
        // If we returned, this means that the JVM exit dialog has been shown and we don't need to be active anymore.
        // We never return otherwise. The process will be killed anyway, and thus we will become inactive
    }
    private static Logger.eventLogListener controllableMitigationLogListener;
    private static volatile Thread controllableMitigationThread;
    private static volatile android.os.FileObserver controllableMitigationObserver;

    /** Stop and detach every Controllable mitigation hook. Safe to call repeatedly. */
    private static synchronized void stopControllableMitigation() {
        Thread thread = controllableMitigationThread;
        controllableMitigationThread = null;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        android.os.FileObserver observer = controllableMitigationObserver;
        controllableMitigationObserver = null;
        if (observer != null) {
            try { observer.stopWatching(); } catch (Throwable ignored) {}
        }
        if (controllableMitigationLogListener != null) {
            Logger.removeLogListener(controllableMitigationLogListener);
            controllableMitigationLogListener = null;
        }
    }

    /** Locate Controllable's extracted SDL library using one bounded directory snapshot per level. */
    private static File findControllableSdlLibrary(File root) {
        File[] versions = root.listFiles();
        if (versions == null) return null;
        for (File version : versions) {
            if (version == null || !version.isDirectory()) continue;
            File[] libraries = version.listFiles();
            if (libraries == null) continue;
            for (File library : libraries) {
                if (library != null && library.isFile() && library.exists()) return library;
            }
        }
        return null;
    }

    /*
     * Controllable extracts an incompatible SDL library immediately before loading it.
     * Preserve the compatibility race, but never busy-spin: FileObserver wakes a bounded
     * 50 ms fallback poll and an absolute timeout guarantees cleanup on failed launches.
     */
    private static void startControllableMitigation(Activity activity, File gamedir) {
        final String TAG = "ControllableMitigation";
        final File deleted = new File(gamedir, "controllable_natives/SDL");
        if (!hasMods("controllable")) return;

        stopControllableMitigation();
        Tools.runOnUiThread(() -> Tools.dialog(activity,
                activity.getString(R.string.global_warning), activity.getString(R.string.controllableFound)));

        final Object wakeSignal = new Object();
        File observeRoot = new File(gamedir, "controllable_natives");
        if (!observeRoot.isDirectory()) observeRoot = gamedir;
        try {
            controllableMitigationObserver = new android.os.FileObserver(observeRoot.getAbsolutePath(),
                    android.os.FileObserver.CREATE | android.os.FileObserver.MOVED_TO
                            | android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.MODIFY) {
                @Override public void onEvent(int event, String path) {
                    synchronized (wakeSignal) { wakeSignal.notifyAll(); }
                }
            };
            controllableMitigationObserver.startWatching();
        } catch (Throwable observerError) {
            Log.w(TAG, "FileObserver unavailable; bounded polling fallback remains active", observerError);
            controllableMitigationObserver = null;
        }

        Thread mitigationThread = new Thread(() -> {
            final long deadline = android.os.SystemClock.uptimeMillis() + 30_000L;
            boolean removed = false;
            try {
                org.apache.commons.io.FileUtils.deleteDirectory(deleted);
                while (!Thread.currentThread().isInterrupted()
                        && android.os.SystemClock.uptimeMillis() < deadline) {
                    File candidate = deleted.isDirectory() ? findControllableSdlLibrary(deleted) : null;
                    if (candidate != null) {
                        removed = candidate.delete() || !candidate.exists();
                        break;
                    }
                    long remaining = deadline - android.os.SystemClock.uptimeMillis();
                    if (remaining <= 0) break;
                    synchronized (wakeSignal) {
                        try { wakeSignal.wait(Math.min(50L, remaining)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "Controllable mitigation ended with an error", error);
            } finally {
                Log.i(TAG, removed ? "Compatibility library removed; mitigation complete"
                        : "Mitigation stopped or timed out without a library removal");
                stopControllableMitigation();
            }
        }, "Controllable-Mitigation");
        controllableMitigationThread = mitigationThread;
        controllableMitigationLogListener = loggedLine -> {
            if (loggedLine.contains("Sound engine started")) {
                Log.i(TAG, "Launch stage passed; cancelling remaining mitigation work");
                mitigationThread.interrupt();
                synchronized (wakeSignal) { wakeSignal.notifyAll(); }
            }
        };
        Logger.addLogListener(controllableMitigationLogListener);
        mitigationThread.start();
    }

    private static Logger.eventLogListener oldL4JMitigationLogListener;
    /// TODO: Remove when the time is right
    /**
     * Legacy4J for a long time had broken SDL detection for android, we need to check and
     * accommodate this for now. At least until the broken logic are on versions considered
     * obsolete.
     * <p>
     * This is of course, very jank, it does not work for anything below 1.7.5 but why is anyone
     * on that version anyway? Legacy4J has LTS for like all the versions.
     */
    private static void startOldLegacy4JMitigation(Activity activity, File gamedir) {
        boolean hasLegacy4J = hasMods("legacy4j");
        if (hasLegacy4J) {
            String TAG = "OldLegacy4JMitigation";
            Log.i(TAG, "Legacy4J detected!");
            oldL4JMitigationLogListener = loggedLine -> {
                if (LauncherPreferences.PREF_GAMEPAD_SDL_PASSTHRU && loggedLine.contains("literal{SDL3 (isXander's libsdl4j)} isn't supported in this system. GLFW will be used instead.")) {
                    Log.i(TAG, "Old version of Legacy4J detected! Force enabling SDL");
                    Tools.SDL.initializeControllerSubsystems();
                    Tools.runOnUiThread(() -> {
                        Tools.dialog(activity, activity.getString(R.string.global_warning), activity.getString(R.string.oldL4JFound));
                    });
                    Logger.removeLogListener(oldL4JMitigationLogListener);
                } else if (LauncherPreferences.PREF_GAMEPAD_SDL_PASSTHRU && loggedLine.contains("Added SDL Controller Mappings")) {
                    Log.i(TAG, "Fixed version of Legacy4J detected! Have fun!");
                    Logger.removeLogListener(oldL4JMitigationLogListener);
                }
            };
            Logger.addLogListener(oldL4JMitigationLogListener);
        }
    }

    public static File getGameDirPath(@NonNull MinecraftProfile minecraftProfile){
        if(minecraftProfile.gameDir != null){
            if(minecraftProfile.gameDir.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX))
                return new File(minecraftProfile.gameDir.replace(Tools.LAUNCHERPROFILES_RTPREFIX,Tools.DIR_GAME_HOME+"/"));
            else
                return new File(Tools.DIR_GAME_HOME,minecraftProfile.gameDir);
        }
        return new File(Tools.DIR_GAME_NEW);
    }

    public static void buildNotificationChannel(Context context){
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                context.getString(R.string.notif_channel_id),
                context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(channel);
    }
    public static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if(FileUtils.ensureDirectorySilently(configDir)) {
            File forgeSplashFile = new File(dir, "config/splash.properties");
            String forgeSplashContent = "enabled=true";
            try {
                if (forgeSplashFile.exists()) {
                    forgeSplashContent = Tools.read(forgeSplashFile.getAbsolutePath());
                }
                if (forgeSplashContent.contains("enabled=true")) {
                    Tools.write(forgeSplashFile.getAbsolutePath(),
                            forgeSplashContent.replace("enabled=true", "enabled=false"));
                }
            } catch (IOException e) {
                Log.w(Tools.APP_NAME, "Could not disable Forge 1.12.2 and below splash screen!", e);
            }
        } else {
            Log.w(Tools.APP_NAME, "Failed to create the configuration directory");
        }
    }

    public static void getCacioJavaArgs(List<String> javaArgList, boolean isJava8, Activity activity) {
        // Caciocavallo config AWT-enabled version
        javaArgList.add("-Djava.awt.headless=false");
        javaArgList.add("-Dcacio.managed.screensize=" + AWTCanvasView.AWT_CANVAS_WIDTH + "x" + AWTCanvasView.AWT_CANVAS_HEIGHT);
        javaArgList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager");
        javaArgList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler");
        javaArgList.add("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel");
        if (isJava8) {
            javaArgList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit");
            javaArgList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment");
        } else {
            File caciocavavallo17Dir = new File(Tools.DIR_GAME_HOME, "caciocavallo17");
            File[] caciocavallo17Jars = caciocavavallo17Dir.listFiles((f, s) ->s.contains("cacio-tta"));
            if(caciocavallo17Jars == null || caciocavallo17Jars.length < 1) {
            // We wanna avoid the launch being interrupted so we extract again if it isn't found
                AsyncAssetManager.unpackComponents(activity);
                caciocavallo17Jars = caciocavavallo17Dir.listFiles((f, s) ->s.contains("cacio-tta"));
                if(caciocavallo17Jars == null || caciocavallo17Jars.length < 1)
                    throw new RuntimeException("Failed to extract required assets!");
            }
            javaArgList.add("-javaagent:"+caciocavallo17Jars[0].getAbsolutePath());
            javaArgList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
            javaArgList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
            // This approach breaks kilt so we use an agent instead
//          javaArgList.add("-Djava.system.class.loader=com.github.caciocavallosilano.cacio.ctc.CTCPreloadClassLoader");
            javaArgList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED");
            javaArgList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED");
            javaArgList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");

            // Opens the java.net package to Arc DNS injector on Java 9+
            javaArgList.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        }

        StringBuilder cacioClasspath = new StringBuilder();
        cacioClasspath.append("-Xbootclasspath/").append(isJava8 ? "p" : "a");
        File cacioDir = new File(DIR_GAME_HOME + "/caciocavallo" + (isJava8 ? "" : "17"));
        File[] cacioFiles = cacioDir.listFiles();
        if (cacioFiles != null) {
            for (File file : cacioFiles) {
                if (file.getName().endsWith(".jar")) {
                    cacioClasspath.append(":").append(file.getAbsolutePath());
                }
            }
        }
        javaArgList.add(cacioClasspath.toString());
    }

    public static String[] getMinecraftJVMArgs(String versionName, File gameDir) {
        JMinecraftVersionList.Version versionInfo = Tools.getVersionInfo(versionName, true);
        if (versionInfo.arguments == null || versionInfo.arguments.jvm == null)
            return new String[0];

        Map<String, String> varArgMap = new ArrayMap<>();
        varArgMap.put("classpath_separator", ":");
        varArgMap.put("library_directory", DIR_HOME_LIBRARY);
        varArgMap.put("version_name", versionInfo.id);
        varArgMap.put("natives_directory", Tools.DIR_CACHE.getAbsolutePath());

        List<String> minecraftArgs = new ArrayList<>();
        for (Object arg : versionInfo.arguments.jvm) {
            if (arg instanceof String) {
                // These are defined later on
                if (((String) arg).contains("java.library.path")) {
                    continue;
                }
                if (arg.equals("-cp")) {
                    continue;
                }
                if (arg.equals("${classpath}")){
                    continue;
                }
                // Should fix Forge 1.17.1-37.0.12 and older from crashing
                // Fixed in forge on https://github.com/MinecraftForge/MinecraftForge/pull/7919
                // Released as Forge 1.17.1-37.0.13 in https://maven.minecraftforge.net/net/minecraftforge/forge/1.17.1-37.0.13/forge-1.17.1-37.0.13-changelog.txt
                // yes this duplicates it, it's fine.
                // FIXME: Workaround old bootstraplauncher <0.1.17 buggy behaviour. See FCL workaround
                //  https://github.com/FCL-Team/FoldCraftLauncher/blob/00e96bcf8ddc8a550e9aba6091a73d5bee973b54/FCLCore/src/main/java/com/tungsten/fclcore/download/MaintainTask.java#L198-L200
                if (((String) arg).startsWith("-DignoreList=")){
                    minecraftArgs.add(arg+",${version_name}.jar");
                    continue;
                }

                // TODO: Implement adding launcher brand and version
                if (((String) arg).contains("minecraft.launcher.brand") ||
                    ((String) arg).contains("minecraft.launcher.version")) {
                    continue;
                }

                minecraftArgs.add((String) arg);
            } //TODO: implement (?maybe?)
        }
        return JSONUtils.insertJSONValueList(minecraftArgs.toArray(new String[0]), varArgMap);
    }

    /**
     * Quick Play (--quickPlayMultiplayer) replaced --server/--port in snapshot 23w14a, released
     * 2023-04-05 and shipped with 1.20. Detection is done twice for safety:
     * first by looking for the quick-play entries the version JSON itself declares (this also
     * covers Forge/Fabric/Quilt profiles that inherit a 1.20+ client.json), then by the original
     * release date of the inherited vanilla version.
     */
    public static boolean supportsQuickPlay(JMinecraftVersionList.Version versionInfo) {
        try {
            if (versionInfo.arguments != null && versionInfo.arguments.game != null) {
                for (Object arg : versionInfo.arguments.game) {
                    if (arg != null && arg.toString().contains("quickPlay")) return true;
                }
            }
        } catch (Exception ignored) {}
        try {
            Date released = DateUtils.getOriginalReleaseDate(versionInfo);
            // dateBefore() takes a zero-based month: 3 == April.
            if (released != null) return !DateUtils.dateBefore(released, 2023, 3, 5);
        } catch (Exception ignored) {}
        return false;
    }

    public static String[] getMinecraftClientArgs(MinecraftAccount profile, JMinecraftVersionList.Version versionInfo, File gameDir) {
        String username = profile.username;
        String versionName = versionInfo.id;
        if (versionInfo.inheritsFrom != null) {
            versionName = versionInfo.inheritsFrom;
        }

        // Match upstream PojavLauncher exactly. userType is a game-version
        // compatibility switch, not an account-type switch. 22w43a+ requires
        // MSA semantics for profile-key / signed texture handling, including
        // NPC and online-player GameProfile properties.
        String userType = "mojang";
        try {
            Date creationDate = DateUtils.getOriginalReleaseDate(versionInfo);
            if (creationDate != null && !DateUtils.dateBefore(creationDate, 2022, 9, 26)) {
                userType = "msa";
            }
        } catch (ParseException e) {
            Log.e("CheckForProfileKey", "Failed to determine profile creation date, using mojang", e);
        }


        Map<String, String> varArgMap = new ArrayMap<>();
        varArgMap.put("auth_session", profile.accessToken); // For legacy versions of MC
        varArgMap.put("auth_access_token", profile.accessToken);
        varArgMap.put("auth_player_name", username);
        varArgMap.put("auth_uuid", profile.profileId.replace("-", ""));
        varArgMap.put("auth_xuid", profile.xuid);
        varArgMap.put("assets_root", Tools.ASSETS_PATH);
        varArgMap.put("assets_index_name", versionInfo.assets);
        varArgMap.put("game_assets", Tools.ASSETS_PATH);
        varArgMap.put("game_directory", gameDir.getAbsolutePath());
        varArgMap.put("user_properties", "{}");
        varArgMap.put("user_type", userType);
        varArgMap.put("version_name", versionName);
        varArgMap.put("version_type", versionInfo.type);

        List<String> minecraftArgs = new ArrayList<>();
        if (versionInfo.arguments != null) {
            // Support Minecraft 1.13+
            for (Object arg : versionInfo.arguments.game) {
                if (arg instanceof String) {
                    minecraftArgs.add((String) arg);
                } //TODO: implement else clause
            }
        }

        return JSONUtils.insertJSONValueList(
                splitAndFilterEmpty(
                        versionInfo.minecraftArguments == null ?
                                fromStringArray(minecraftArgs.toArray(new String[0])):
                                versionInfo.minecraftArguments
                ), varArgMap
        );
    }

    public static String fromStringArray(String[] strArr) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strArr.length; i++) {
            if (i > 0) builder.append(" ");
            builder.append(strArr[i]);
        }

        return builder.toString();
    }

    private static String[] splitAndFilterEmpty(String argStr) {
        List<String> strList = new ArrayList<>();
        for (String arg : argStr.split(" ")) {
            if (!arg.isEmpty()) {
                strList.add(arg);
            }
        }
        //strList.add("--fullscreen");
        return strList.toArray(new String[0]);
    }

    public static String artifactToPath(DependentLibrary library) {
        if (library.downloads != null &&
            library.downloads.artifact != null &&
            library.downloads.artifact.path != null)
            return library.downloads.artifact.path;
        String[] libInfos = library.name.split(":");
        return libInfos[0].replaceAll("\\.", "/") + "/" + libInfos[1] + "/" + libInfos[2] + "/" + libInfos[1] + "-" + libInfos[2] + (libInfos.length == 4 ? "-" + libInfos[3] : "") + ".jar";
    }

    private static String getLibClasspath(JMinecraftVersionList.Version info){
        StringBuilder libClasspath = new StringBuilder();
        String[] classpath = generateLibClasspath(info);
        for (String jarFile : classpath) {
            libClasspath.append(jarFile).append(":");
        }
        // Remove the ':' at the end
        libClasspath.setLength(libClasspath.length() - 1);
        return libClasspath.toString();
    }

    public static String getClientClasspath(String version) {
        return DIR_HOME_VERSION + "/" + version + "/" + version + ".jar";
    }
    public static String generateLaunchClasspath(JMinecraftVersionList.Version info, String actualname) {
        StringBuilder launchClasspath = new StringBuilder(); //versnDir + "/" + version + "/" + version + ".jar:";
        String libClasspath = getLibClasspath(info); // Sets lwjglVersion, janky, but we can't get it any simpler
        // Match the CS Launcher packaged bridge selection: 3.4.1 is the newest
        // bundled component and serves current modern snapshots.
        String internalLwjglVersion = iLwjglVersion >= 341 ? "3.4.1" : "3.3.3";
        File lwjgl3Folder = new File(Tools.DIR_GAME_HOME, "lwjgl3/"+internalLwjglVersion);
        String lwjglCore = lwjgl3Folder.getAbsolutePath() + "/lwjgl.jar";
        String lwjglMerged = lwjgl3Folder.getAbsolutePath() + "/lwjgl-"+internalLwjglVersion+"-merged-modules.jar";
        String lwjglxFile = lwjgl3Folder + "/lwjgl-lwjglx.jar";

        if (!new File(lwjglCore).exists() || !new File(lwjglMerged).exists() || !new File(lwjglxFile).exists()) {
            try { // Delete the folder so on restart will re-extract them.
                if (lwjgl3Folder.exists())
                    org.apache.commons.io.FileUtils.deleteDirectory(lwjgl3Folder);
            } catch (IOException ignored) {}
            throw new RuntimeException("LWJGL jars incomplete, restart the app to reextract them.");
        }
        launchClasspath.append(lwjglCore).append(":");
        // 2nd in priority in case we need to merge lwjgl.jar again for testing
        launchClasspath.append(lwjglMerged).append(":");

        File[] lwjglModules = lwjgl3Folder.listFiles(pathname ->
                pathname.getName().endsWith(".jar") &&
            // Exclude jars that are added explicitly before/after the module list
                !pathname.getName().equals("lwjgl.jar") &&
                !pathname.getName().endsWith("lwjglx.jar") &&
                !pathname.getName().endsWith("merged-modules.jar"));

        if (lwjglModules != null) {
            for (File lwjglModule : lwjglModules)
                launchClasspath.append(lwjglModule.getAbsolutePath()).append(":");
        } else Log.e("generateLaunchClasspath", "lwjgl modules are missing from components!");

        launchClasspath.append(libClasspath).append(":");
        launchClasspath.append(getClientClasspath(actualname));
        // Anything LWJGL2 gets LWJGLX
        if (iLwjglVersion <= 299) launchClasspath.append(":").append(lwjglxFile);
        return launchClasspath.toString();
    }

    public static DisplayMetrics getDisplayMetrics(Activity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();

        if(SDK_INT >= Build.VERSION_CODES.N && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())){
            //For devices with free form/split screen, we need window size, not screen size.
            displayMetrics = activity.getResources().getDisplayMetrics();
        }else{
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else { // Removed the clause for devices with unofficial notch support, since it also ruins all devices with virtual nav bars before P
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
            }
            if(!PREF_IGNORE_NOTCH){
                //Remove notch width when it isn't ignored.
                if(activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
                    displayMetrics.heightPixels -= PREF_NOTCH_SIZE;
                else
                    displayMetrics.widthPixels -= PREF_NOTCH_SIZE;
            }
        }
        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    public static void setFullscreen(Activity activity, boolean fullscreen) {
        final View decorView = activity.getWindow().getDecorView();
        View.OnSystemUiVisibilityChangeListener visibilityChangeListener = visibility -> {
            boolean multiWindowMode = SDK_INT >= 24 && activity.isInMultiWindowMode();
            // When in multi-window mode, asking for fullscreen makes no sense (cause the launcher runs in a window)
            // So, ignore the fullscreen setting when activity is in multi window mode
            if(fullscreen && !multiWindowMode){
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                }
            }else{
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

        };
        decorView.setOnSystemUiVisibilityChangeListener(visibilityChangeListener);
        visibilityChangeListener.onSystemUiVisibilityChange(decorView.getSystemUiVisibility()); //call it once since the UI state may not change after the call, so the activity wont become fullscreen
    }

    public static DisplayMetrics currentDisplayMetrics;

    public static void updateWindowSize(Activity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);

        View dimensionView = activity.findViewById(R.id.dimension_tracker);

        if(dimensionView != null) {
            int width = dimensionView.getWidth();
            int height = dimensionView.getHeight();
            if(width != 0 && height != 0) {
                Log.i("Tools", "Using dimension_tracker for display dimensions; W="+width+" H="+height);
                CallbackBridge.physicalWidth = width;
                CallbackBridge.physicalHeight = height;
                return;
            }else{
                Log.e("Tools","Dimension tracker detected but dimensions out of date. Please check usage.", new Exception());
            }
        }

        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    public static float dpToPx(float dp) {
        //Better hope for the currentDisplayMetrics to be good
        return dp * currentDisplayMetrics.density;
    }

    public static float pxToDp(float px){
        //Better hope for the currentDisplayMetrics to be good
        return px / currentDisplayMetrics.density;
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, boolean overwrite) throws IOException {
        copyAssetFile(ctx, fileName, output, new File(fileName).getName(), overwrite);
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, String outputName, boolean overwrite) throws IOException {
        File parentFolder = new File(output);
        FileUtils.ensureDirectory(parentFolder);
        File destinationFile = new File(output, outputName);
        if(!destinationFile.exists() || overwrite){
            try(InputStream inputStream = ctx.getAssets().open(fileName)) {
                try (OutputStream outputStream = new FileOutputStream(destinationFile)){
                    IOUtils.copy(inputStream, outputStream);
                }
            }
        }
    }

    public static String printToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.close();
        return stringWriter.toString();
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }

    public static void showError(final Context ctx, final Throwable e, final boolean exitIfOk) {
        showError(ctx, R.string.global_error, null ,e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int rolledMessage, final Throwable e) {
        showError(ctx, R.string.global_error, ctx.getString(rolledMessage), e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e) {
        showError(ctx, R.string.global_error, rolledMessage, e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e, boolean exitIfOk) {
        showError(ctx, R.string.global_error, rolledMessage, e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int titleId, final Throwable e, final boolean exitIfOk) {
        showError(ctx, titleId, null, e, exitIfOk, false);
    }

    private static void showError(final Context ctx, final int titleId, final String rolledMessage, final Throwable e, final boolean exitIfOk, final boolean showMore) {
        if(e instanceof ContextExecutorTask) {
            ContextExecutor.execute((ContextExecutorTask) e);
            return;
        }
        e.printStackTrace();

        Runnable runnable = () -> {
            final String errMsg = showMore ? printToString(e) : rolledMessage != null ? rolledMessage : e.getMessage();
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                        if(exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                fullyExit();
                            } else if (ctx instanceof Activity) {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setNegativeButton(showMore ? R.string.error_show_less : R.string.error_show_more, (p1, p2) -> showError(ctx, titleId, rolledMessage, e, exitIfOk, !showMore))
                    .setNeutralButton(android.R.string.copy, (p1, p2) -> {
                        ClipboardManager mgr = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        mgr.setPrimaryClip(ClipData.newPlainText("error", printToString(e)));
                        if(exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                fullyExit();
                            } else {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setCancelable(!exitIfOk);
            try {
                builder.show();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        };

        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Show the error remotely in a context-aware fashion. Has generally the same behaviour as
     * Tools.showError when in an activity, but when not in one, sends a notification that opens an
     * activity and calls Tools.showError().
     * NOTE: If the Throwable is a ContextExecutorTask and when not in an activity,
     * its executeWithApplication() method will never be called.
     * @param e the error (throwable)
     */
    public static void showErrorRemote(Throwable e) {
        showErrorRemote(null, e);
    }
    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        showErrorRemote(context.getString(rolledMessage), e);
    }
    public static void showErrorRemote(String rolledMessage, Throwable e) {
        // I WILL embrace layer violations because Android's concept of layers is STUPID
        // We live in the same process anyway, why make it any more harder with this needless
        // abstraction?

        // Add your Context-related rage here
        ContextExecutor.execute(new ShowErrorActivity.RemoteErrorTask(e, rolledMessage));
    }



    public static void dialogOnUiThread(final Activity activity, final CharSequence title, final CharSequence message) {
        activity.runOnUiThread(()->dialog(activity, title, message));
    }
    public static void dialogOnUiThread(final Activity activity, final int title, final int message) {
        dialogOnUiThread(activity, activity.getString(title), activity.getString(message));
    }

    public static void dialog(final Context context, final CharSequence title, final CharSequence message) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static void openURL(Activity act, String url) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        act.startActivity(browserIntent);
    }

    private static boolean checkRules(JMinecraftVersionList.Arguments.ArgValue.ArgRules[] rules) {
        if(rules == null) return true; // always allow
        for (JMinecraftVersionList.Arguments.ArgValue.ArgRules rule : rules) {
            if (rule.action.equals("allow") && rule.os != null && rule.os.name.equals("osx")) {
                return false; //disallow
            }
        }
        return true; // allow if none match
    }

    public static void preProcessLibraries(DependentLibrary[] libraries) {
        for (int i = 0; i < libraries.length; i++) {
            DependentLibrary libItem = libraries[i];
            String[] version = libItem.name.split(":")[2].split("\\.");
            if (libItem.name.startsWith("net.java.dev.jna:jna:")) {
                // Special handling for LabyMod 1.8.9, Forge 1.12.2(?) and oshi
                // we have libjnidispatch 5.13.0 in jniLibs directory
                if (Integer.parseInt(version[0]) >= 5 && Integer.parseInt(version[1]) >= 13) continue;
                Log.d(APP_NAME, "Library " + libItem.name + " has been changed to version 5.13.0");
                createLibraryInfo(libItem);
                libItem.name = "net.java.dev.jna:jna:5.13.0";
                libItem.downloads.artifact.path = "net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
                libItem.downloads.artifact.sha1 = "1200e7ebeedbe0d10062093f32925a912020e747";
                libItem.downloads.artifact.url = "https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar";
            } else if (libItem.name.startsWith("com.github.oshi:oshi-core:")) {
                //if (Integer.parseInt(version[0]) >= 6 && Integer.parseInt(version[1]) >= 3) return;
                // FIXME: ensure compatibility

                if (Integer.parseInt(version[0]) != 6 || Integer.parseInt(version[1]) != 2) continue;
                Log.d(APP_NAME, "Library " + libItem.name + " has been changed to version 6.3.0");
                createLibraryInfo(libItem);
                libItem.name = "com.github.oshi:oshi-core:6.3.0";
                libItem.downloads.artifact.path = "com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
                libItem.downloads.artifact.sha1 = "9e98cf55be371cafdb9c70c35d04ec2a8c2b42ac";
                libItem.downloads.artifact.url = "https://repo1.maven.org/maven2/com/github/oshi/oshi-core/6.3.0/oshi-core-6.3.0.jar";
            } else if (libItem.name.startsWith("org.ow2.asm:asm-all:")) {
                // Early versions of the ASM library get repalced with 5.0.4 because Pojav's LWJGL is compiled for
                // Java 8, which is not supported by old ASM versions. Mod loaders like Forge, which depend on this
                // library, often include lwjgl in their class transformations, which causes errors with old ASM versions.
                if(Integer.parseInt(version[0]) >= 5) continue;
                Log.d(APP_NAME, "Library " + libItem.name + " has been changed to version 5.0.4");
                createLibraryInfo(libItem);
                libItem.name = "org.ow2.asm:asm-all:5.0.4";
                libItem.url = null;
                libItem.downloads.artifact.path = "org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
                libItem.downloads.artifact.sha1 = "e6244859997b3d4237a552669279780876228909";
                libItem.downloads.artifact.url = "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.4/asm-all-5.0.4.jar";
            }
        }
    }

    private static void createLibraryInfo(DependentLibrary library) {
        if(library.downloads == null || library.downloads.artifact == null)
            library.downloads = new DependentLibrary.LibraryDownloads(new MinecraftLibraryArtifact());
    }

    public static String[] generateLibClasspath(JMinecraftVersionList.Version info) {
        List<String> libDir = new ArrayList<>();
        for (DependentLibrary libItem: info.libraries) {
            // Look for LWJGL version
            int libItemVersionStringOffset = 0;
            if(libItem.name.startsWith("org.lwjgl.lwjgl:lwjgl:")) {
                libItemVersionStringOffset = "org.lwjgl.lwjgl:lwjgl:".length();
            } else if (libItem.name.startsWith("org.lwjgl:lwjgl:")) {
                libItemVersionStringOffset = "org.lwjgl:lwjgl:".length();
            }
            // If we already have a valid LWJGL version, skip this block
            if (libItemVersionStringOffset != 0 && (iLwjglVersion < 200 || iLwjglVersion > 999)) {
                while (libItemVersionStringOffset < libItem.name.length()) {
                    char c = libItem.name.charAt(libItemVersionStringOffset);
                    if (c >= '0' && c <= '9') {
                        iLwjglVersion = iLwjglVersion * 10 + (c - '0');
                    } else if (c == '.') {
                        // skip dots
                    } else {
                        break; // If the dots and numbers stop then its time to finish
                    }
                    libItemVersionStringOffset++;
                }
            }

            String libPath = Tools.DIR_HOME_LIBRARY + "/" + artifactToPath(libItem);
            if (!FileUtils.exists(libPath)) {
                Log.d(APP_NAME, "Ignored non-exists file: " + libPath);
                continue;
            }
            if(!checkRules(libItem.rules)) continue;
            libDir.add(libPath);
            // Mitigation: Babric doesn't use asm-all for some reason so it does a classpath conflict
            if (libItem.name.startsWith("org.ow2.asm:asm") && !libItem.name.startsWith("org.ow2.asm:asm-all:")){
                libDir.remove(Tools.DIR_HOME_LIBRARY + "/" + artifactToPath(new DependentLibrary(){{
                    name = "org.ow2.asm:asm-all:5.0.4";
                }} ));
            }
        }
        // Scary message, but we aren't getting LWJGL 1.9.9 or 3.10.100 any time soon
        if (iLwjglVersion < 200 || iLwjglVersion > 999) throw new RuntimeException("Unable to determine LWJGL version, JSON may be corrupt.");
        sLwjglVersion = iLwjglVersion >= 341 ? "3.4.1" : "3.3.3";
        lwjglNativesDir = String.format("%s/lwjgl-%s-natives/%s", Tools.DIR_DATA, sLwjglVersion, archAsStringAndroid(getDeviceArchitecture()));
        // Validate that the resolved native library directory actually contains the
        // essential .so files. If it doesn't, throw a clear error so the user knows
        // to restart the launcher (which triggers AsyncAssetManager re-extraction).
        File lwjglNativesFile = new File(lwjglNativesDir);
        if (!lwjglNativesFile.isDirectory() || !new File(lwjglNativesFile, "liblwjgl.so").exists()) {
            Logger.appendToLog("CRITICAL: LWJGL native library directory " + lwjglNativesDir
                    + " is missing or incomplete. Restart the launcher to trigger re-extraction.");
            // We can't recover here (no Context to re-extract), but at least log clearly.
        }
        return libDir.toArray(new String[0]);
    }

    public static JMinecraftVersionList.Version getVersionInfo(String versionName) {
        return getVersionInfo(versionName, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static JMinecraftVersionList.Version getVersionInfo(String versionName, boolean skipInheriting) {
        try {
            JMinecraftVersionList.Version customVer = Tools.GLOBAL_GSON.fromJson(read(DIR_HOME_VERSION + "/" + versionName + "/" + versionName + ".json"), JMinecraftVersionList.Version.class);
            if (skipInheriting || customVer.inheritsFrom == null || customVer.inheritsFrom.equals(customVer.id)) {
                preProcessLibraries(customVer.libraries);
            } else {
                JMinecraftVersionList.Version inheritsVer;
                //If it won't download, just search for it
                try{
                    inheritsVer = Tools.GLOBAL_GSON.fromJson(read(DIR_HOME_VERSION + "/" + customVer.inheritsFrom + "/" + customVer.inheritsFrom + ".json"), JMinecraftVersionList.Version.class);
                }catch(IOException e) {
                    throw new RuntimeException("Can't find the source version for "+ versionName +" (req version="+customVer.inheritsFrom+")");
                }
                //inheritsVer.inheritsFrom = inheritsVer.id;
                insertSafety(inheritsVer, customVer,
                        "assetIndex", "assets", "id",
                        "mainClass", "minecraftArguments",
                        "releaseTime", "time", "type", "inheritsFrom"
                );

                // Go through the libraries, remove the ones overridden by the custom version
                List<DependentLibrary> inheritLibraryList = new ArrayList<>(Arrays.asList(inheritsVer.libraries));
                outer_loop:
                for(DependentLibrary library : customVer.libraries){
                    // Clean libraries overridden by the custom version
                    String libName = library.name.substring(0, library.name.lastIndexOf(":"));

                    for(DependentLibrary inheritLibrary : inheritLibraryList) {
                        String inheritLibName = inheritLibrary.name.substring(0, inheritLibrary.name.lastIndexOf(":"));

                        if(libName.equals(inheritLibName)){
                            Log.d(APP_NAME, "Library " + libName + ": Replaced version " +
                                    libName.substring(libName.lastIndexOf(":") + 1) + " with " +
                                    inheritLibName.substring(inheritLibName.lastIndexOf(":") + 1));

                            // Remove the library , superseded by the overriding libs
                            inheritLibraryList.remove(inheritLibrary);
                            continue outer_loop;
                        }
                    }
                }

                // Fuse libraries
                inheritLibraryList.addAll(Arrays.asList(customVer.libraries));
                inheritsVer.libraries = inheritLibraryList.toArray(new DependentLibrary[0]);
                preProcessLibraries(inheritsVer.libraries);


                // Inheriting Minecraft 1.13+ with append custom args
                if (inheritsVer.arguments != null && customVer.arguments != null) {
                    List totalArgList = new ArrayList(Arrays.asList(inheritsVer.arguments.game));

                    int nskip = 0;
                    for (int i = 0; i < customVer.arguments.game.length; i++) {
                        if (nskip > 0) {
                            nskip--;
                            continue;
                        }

                        Object perCustomArg = customVer.arguments.game[i];
                        if (perCustomArg instanceof String) {
                            String perCustomArgStr = (String) perCustomArg;
                            // Check if there is a duplicate argument on combine
                            if (perCustomArgStr.startsWith("--") && totalArgList.contains(perCustomArgStr)) {
                                perCustomArg = customVer.arguments.game[i + 1];
                                if (perCustomArg instanceof String) {
                                    perCustomArgStr = (String) perCustomArg;
                                    // If the next is argument value, skip it
                                    if (!perCustomArgStr.startsWith("--")) {
                                        nskip++;
                                    }
                                }
                            } else {
                                totalArgList.add(perCustomArgStr);
                            }
                        } else if (!totalArgList.contains(perCustomArg)) {
                            totalArgList.add(perCustomArg);
                        }
                    }

                    inheritsVer.arguments.game = totalArgList.toArray(new Object[0]);
                }

                customVer = inheritsVer;
            }

            // LabyMod 4 sets version instead of majorVersion
            if (customVer.javaVersion != null && customVer.javaVersion.majorVersion == 0) {
                customVer.javaVersion.majorVersion = customVer.javaVersion.version;
            }
            return customVer;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // Prevent NullPointerException
    private static void insertSafety(JMinecraftVersionList.Version targetVer, JMinecraftVersionList.Version fromVer, String... keyArr) {
        for (String key : keyArr) {
            Object value = null;
            try {
                Field fieldA = fromVer.getClass().getDeclaredField(key);
                value = fieldA.get(fromVer);
                if (((value instanceof String) && !((String) value).isEmpty()) || value != null) {
                    Field fieldB = targetVer.getClass().getDeclaredField(key);
                    fieldB.set(targetVer, value);
                }
            } catch (Throwable th) {
                Log.w(Tools.APP_NAME, "Unable to insert " + key + "=" + value, th);
            }
        }
    }

    public static String read(InputStream is) throws IOException {
        String readResult = IOUtils.toString(is, StandardCharsets.UTF_8);
        is.close();
        return readResult;
    }

    public static String read(String path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static String read(File path) throws IOException {
        return read(new FileInputStream(path));
    }

    public static void write(String path, String content) throws IOException {
        File file = new File(path);
        FileUtils.ensureParentDirectory(file);
        try(FileOutputStream outStream = new FileOutputStream(file)) {
            IOUtils.write(content, outStream);
        }
    }

    public static void downloadFile(String urlInput, String nameOutput) throws IOException {
        File file = new File(nameOutput);
        DownloadUtils.downloadFile(urlInput, file);
    }

    public static boolean isAndroid8OrHigher() {
        return SDK_INT >= 26;
    }

    public static void fullyExit() {
        net.kdt.pojavlaunch.performance.GameSessionState.clear();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public static void printLauncherInfo(String gameVersion, String javaArguments, int deviceRam) {
        Logger.appendToLog("Info: Launcher version: " + BuildConfig.VERSION_NAME);
        Logger.appendToLog("Info: Architecture: " + Architecture.archAsString(DEVICE_ARCHITECTURE));
        Logger.appendToLog("Info: Device model: " + Build.MANUFACTURER + " " +Build.MODEL);
        Logger.appendToLog(String.format("Info: Total RAM: %s MB", deviceRam != 0 ? deviceRam : "unavailable"));
        Logger.appendToLog("Info: Allocated RAM: " + LauncherPreferences.PREF_RAM_ALLOCATION + "MB");
        Logger.appendToLog("Info: API version: " + SDK_INT);
        Logger.appendToLog("Info: Selected Minecraft version: " + gameVersion);
        Logger.appendToLog("Info: Custom Java arguments: \"" + javaArguments + "\"");
        GLInfoUtils.GLInfo info = GLInfoUtils.getGlInfo();
        Logger.appendToLog("Info: Graphics device: "+info.vendor+ " "+info.renderer+" (OpenGL ES "+info.glesMajorVersion+")");
    }

    public interface DownloaderFeedback {
        void updateProgress(int curr, int max);
    }


    public static boolean compareSHA1(File f, String sourceSHA) {
        try {
            String sha1_dst;
            try (InputStream is = new FileInputStream(f)) {
                sha1_dst = new String(Hex.encodeHex(org.apache.commons.codec.digest.DigestUtils.sha1(is)));
            }
            if(sourceSHA != null) {
                return sha1_dst.equalsIgnoreCase(sourceSHA);
            } else{
                return true; // fake match
            }
        }catch (IOException e) {
            Log.i("SHA1","Fake-matching a hash due to a read error",e);
            return true;
        }
    }

    public static void ignoreNotch(boolean shouldIgnore, Activity ctx){
        if (SDK_INT >= P) {
            if (shouldIgnore) {
                ctx.getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            } else {
                ctx.getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            }
            ctx.getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            Tools.updateWindowSize(ctx);
        }
    }

    public static int getTotalDeviceMemory(Context ctx){
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.totalMem / 1048576L);
    }

    /**
     * The one RAM safety policy shared by Launcher Settings, profile editing and
     * the JVM launch path. Keeping one calculation prevents a profile from
     * displaying an allocation that Android cannot safely start.
     */
    public static int getMaximumRamAllocation(Context ctx) {
        int deviceRam = getTotalDeviceMemory(ctx);
        if (Architecture.is32BitsDevice() || deviceRam < 2048) {
            return Math.max(256, Math.min(1024, deviceRam));
        }
        return Math.max(256, deviceRam - (deviceRam < 3064 ? 800 : 1024));
    }

    /** Clamps and aligns an allocation to the same 128 MB steps as Settings. */
    public static int sanitizeRamAllocation(Context ctx, int requestedMb) {
        int bounded = Math.max(256, Math.min(requestedMb, getMaximumRamAllocation(ctx)));
        return Math.max(256, (bounded / 128) * 128);
    }

    public static int getFreeDeviceMemory(Context ctx){
        ActivityManager actManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        return (int) (memInfo.availMem / 1048576L);
    }

    private static int internalGetMaxContinuousAddressSpaceSize() throws Exception{
        MemoryHoleFinder memoryHoleFinder = new MemoryHoleFinder();
        new SelfMapsParser(memoryHoleFinder).run();
        long largestHole = memoryHoleFinder.getLargestHole();
        if(largestHole == -1) return -1;
        else return (int)(largestHole / 1048576L);
    }

    public static int getMaxContinuousAddressSpaceSize() {
        try {
            return internalGetMaxContinuousAddressSpaceSize();
        }catch (Exception e){
            Log.w("Tools", "Failed to find the largest uninterrupted address space");
            return -1;
        }
    }

    public static int getDisplayFriendlyRes(int displaySideRes, float scaling){
        displaySideRes *= scaling;
        if(displaySideRes % 2 != 0) displaySideRes --;
        return displaySideRes;
    }

    public static String getFileName(Context ctx, Uri uri) {
        Cursor c = ctx.getContentResolver().query(uri, null, null, null, null);
        if(c == null) return uri.getLastPathSegment(); // idk myself but it happens on asus file manager
        c.moveToFirst();
        int columnIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if(columnIndex == -1) return uri.getLastPathSegment();
        String fileName = c.getString(columnIndex);
        c.close();
        return fileName;
    }

    /** Swap the main fragment with another */
    public static void swapFragment(FragmentActivity fragmentActivity , Class<? extends Fragment> fragmentClass,
                                    @Nullable String fragmentTag, @Nullable Bundle bundle) {
        // When people tab out, it might happen
        fragmentActivity.getSupportFragmentManager().beginTransaction()
                // Every screen change uses the shared motion spec, so the whole launcher
                // navigates with one personality and one speed setting.
                .setCustomAnimations(net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[0],
                        net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[1],
                        net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[2],
                        net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[3])
                .setReorderingAllowed(true)
                .addToBackStack(fragmentClass.getName())
                .replace(R.id.container_fragment, fragmentClass, bundle, fragmentTag).commit();
    }

    /** Swap the main fragment with another with custom animations */
    public static void swapFragment(FragmentActivity fragmentActivity , Class<? extends Fragment> fragmentClass,
                                    @Nullable String fragmentTag, @Nullable Bundle bundle,
                                    int enterAnim, int exitAnim, int popEnterAnim, int popExitAnim) {
        fragmentActivity.getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(enterAnim, exitAnim, popEnterAnim, popExitAnim)
                .setReorderingAllowed(true)
                .addToBackStack(fragmentClass.getName())
                .replace(R.id.container_fragment, fragmentClass, bundle, fragmentTag).commit();
    }

    public static void backToMainMenu(FragmentActivity fragmentActivity) {
        fragmentActivity.getSupportFragmentManager()
                .popBackStack("ROOT", 0);
    }

    /** Remove the current fragment */
    public static void removeCurrentFragment(FragmentActivity fragmentActivity){
        fragmentActivity.getSupportFragmentManager().popBackStack();
    }

    public static void installMod(Activity activity, boolean customJavaArgs) {
        if (MultiRTUtils.getExactJreName(8) == null) {
            Toast.makeText(activity, R.string.multirt_nojava8rt, Toast.LENGTH_LONG).show();
            return;
        }

        if(!customJavaArgs){ // Launch the intent to get the jar file
            if(!(activity instanceof LauncherActivity))
                throw new IllegalStateException("Cannot start Mod Installer without LauncherActivity");
            LauncherActivity launcherActivity = (LauncherActivity)activity;
            launcherActivity.modInstallerLauncher.launch(null);
            return;
        }

        // install mods with custom arguments
        final EditText editText = new EditText(activity);
        editText.setSingleLine();
        editText.setHint("-jar/-cp /path/to/file.jar ...");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.alerttitle_installmod)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(editText)
                .setPositiveButton(android.R.string.ok, (di, i) -> {
                    Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
                    intent.putExtra("javaArgs", editText.getText().toString());
                    activity.startActivity(intent);
                });
        builder.show();
    }

    /** Display and return a progress dialog, instructing to wait */
    public static ProgressDialog getWaitingDialog(Context ctx, int message){
        final ProgressDialog barrier = new ProgressDialog(ctx);
        barrier.setMessage(ctx.getString(message));
        barrier.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        barrier.setCancelable(false);
        barrier.show();

        return barrier;
    }

    /** Launch the mod installer activity. The Uri must be from our own content provider or
     * from ACTION_OPEN_DOCUMENT
     */
    public static void launchModInstaller(Activity activity, @NonNull Uri uri){
        Intent intent = new Intent(activity, JavaGUILauncherActivity.class);
        intent.putExtra("modUri", uri);
        activity.startActivity(intent);
    }


    public static void installRuntimeFromUri(Context context, Uri uri){
        sExecutorService.execute(() -> {
            try {
                String name = getFileName(context, uri);
                MultiRTUtils.installRuntimeNamed(
                        NATIVE_LIB_DIR,
                        context.getContentResolver().openInputStream(uri),
                        name);

                MultiRTUtils.postPrepare(name);
            } catch (IOException e) {
                Tools.showError(context, e);
            }
        });
    }

    public static String extractUntilCharacter(String input, String whatFor, char terminator) {
        int whatForStart = input.indexOf(whatFor);
        if(whatForStart == -1) return null;
        whatForStart += whatFor.length();
        int terminatorIndex = input.indexOf(terminator, whatForStart);
        if(terminatorIndex == -1) return null;
        return input.substring(whatForStart, terminatorIndex);
    }

    public static boolean isValidString(String string) {
        return string != null && !string.isEmpty();
    }

    public static String getRuntimeName(String prefixedName) {
        if(prefixedName == null) return prefixedName;
        if(!prefixedName.startsWith(Tools.LAUNCHERPROFILES_RTPREFIX)) return null;
        return prefixedName.substring(Tools.LAUNCHERPROFILES_RTPREFIX.length());
    }

    public static String getSelectedRuntime(MinecraftProfile minecraftProfile) {
        String runtime = LauncherPreferences.PREF_DEFAULT_RUNTIME;
        String profileRuntime = getRuntimeName(minecraftProfile.javaDir);
        if(profileRuntime != null) {
            if(MultiRTUtils.forceReread(profileRuntime).versionString != null) {
                runtime = profileRuntime;
            }
        }
        return runtime;
    }

    public static void runOnUiThread(Runnable runnable) {
        MAIN_HANDLER.post(runnable);
    }

    public static @NonNull String pickRuntime(MinecraftProfile minecraftProfile, int targetJavaVersion) {
        String runtime = getSelectedRuntime(minecraftProfile);
        String profileRuntime = getRuntimeName(minecraftProfile.javaDir);
        Runtime pickedRuntime = MultiRTUtils.read(runtime);
        if(runtime == null || pickedRuntime.javaVersion == 0 || pickedRuntime.javaVersion < targetJavaVersion) {
            String preferredRuntime = MultiRTUtils.getNearestJreName(targetJavaVersion);
            if(preferredRuntime == null) throw new RuntimeException("Failed to autopick runtime!");
            if(profileRuntime != null) minecraftProfile.javaDir = Tools.LAUNCHERPROFILES_RTPREFIX+preferredRuntime;
            runtime = preferredRuntime;
        }
        return runtime;
    }

    /** Shares the single latest log belonging to the currently selected profile. */
    public static void shareLog(Context context){
        openPath(context,
                net.kdt.pojavlaunch.profiles.ProfileLogStore.forCurrentProfile(context), true);
    }

    /**
     * Routes the user to the dedicated crash screen instead of killing the whole app.
     * The crash log (last_crash.log) is passed along so the screen can display it.
     * The current (game) process is then terminated after the screen is launched.
     */
    public static void routeToCrashScreen(androidx.fragment.app.FragmentActivity activity, int exitCode) {
        try {
            File latest = net.kdt.pojavlaunch.profiles.ProfileLogStore
                    .forCurrentProfile(activity);
            String logPath = latest.getAbsolutePath();

            Intent crashIntent = new Intent(activity, CrashActivity.class);
            crashIntent.putExtra(CrashActivity.EXTRA_LOG_PATH, logPath);
            crashIntent.putExtra(CrashActivity.EXTRA_EXIT_CODE, exitCode);
            crashIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(crashIntent);
        } catch (Throwable ignored) {
            // Fall back to the legacy dialog if the crash screen can't start
            Logger.appendToLog("Failed to route to crash screen, using fallback dialog");
        } finally {
            // Kill the game process so the surface/EGL context is released.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> android.os.Process.killProcess(android.os.Process.myPid()), 800);
        }
    }

    /** Holds the path to the most recent launch log, used to capture crash logs */
    public static final String LATEST_LOG_NAME = "latestlog.txt";
    public static final String LAST_CRASH_LOG_NAME = "last_crash.log";

    /**
     * Compatibility hook used by the crash flow. The active profile logger is
     * already writing directly to its durable latestlog.txt, so creating a
     * second crash copy would violate the one-log-per-profile storage policy.
     */
    public static void captureCrashLog() {
        // Intentionally no duplicate file.
    }

    /** Shares the current profile's latest session, including a failed session. */
    public static void shareLastCrashLog(Context context) {
        shareLog(context);
    }

    /**
     * Determine the MIME type of a File.
     * @param file The file to determine the type of
     * @return the type, or the default value *slash* if cannot be determined
     */
    public static String getMimeType(File file) {
        if(file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String mimeType = null;
        try (FileInputStream fileInputStream = new FileInputStream(file)){
            // Theoretically we don't even need the buffer since we don't care about the
            // contents of the file after the guess, but mark-supported streams
            // are a requirement of URLConnection.guessContentTypeFromStream()
            try(BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream)) {
                mimeType = URLConnection.guessContentTypeFromStream(bufferedInputStream);
            }
        }catch (IOException e) {
            Log.w("FileMimeType", "Failed to determine MIME type by stream", e);
        }
        if(mimeType != null) return mimeType;
        mimeType = URLConnection.guessContentTypeFromName(file.getName());
        if(mimeType != null) return mimeType;
        return "*/*";
    }

    /**
     * Open the path specified by a File in a file explorer or in a relevant application.
     * @param context the current Context
     * @param file the File to open
     * @param share whether to open a "Share" or an "Open" dialog.
     */
    public static void openPath(Context context, File file, boolean share) {
        Uri contentUri = DocumentsContract.buildDocumentUri(context.getString(R.string.storageProviderAuthorities), file.getAbsolutePath());
        String mimeType = getMimeType(file);
        Intent intent = new Intent();
        if(share) {
            intent.setAction(Intent.ACTION_SEND);
            intent.setType(getMimeType(file));
            intent.putExtra(Intent.EXTRA_STREAM, contentUri);
        }else {
            intent.setAction(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, mimeType);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent chooserIntent = Intent.createChooser(intent, file.getName());
        context.startActivity(chooserIntent);
    }

    /** Mesure the textview height, given its current parameters */
    public static int mesureTextviewHeight(TextView t) {
        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(t.getWidth(), View.MeasureSpec.AT_MOST);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        t.measure(widthMeasureSpec, heightMeasureSpec);
        return t.getMeasuredHeight();
    }

    /**
     * Check if the device is one of the devices that may be affected by the hanging linker issue.
     * The device is affected if the linker causes the process to lock up when dlopen() is called within
     * dl_iterate_phdr().
     * For now, the only affected firmware that I know of is Android 5.1, EMUI 3.1 on MTK-based Huawei
     * devices.
     * @return if the device is affected by the hanging linker issue.
     */
    public static boolean deviceHasHangingLinker() {
        // Android Oreo and onwards have GSIs and most phone firmwares at that point were not modified
        // *that* intrusively. So assume that we are not affected.
        if(SDK_INT >= Build.VERSION_CODES.O) return false;
        // Since the affected function in LWJGL is rarely used (and when used, it's mainly for debug prints)
        // we can make the search scope a bit more broad and check if we are running on a Huawei device.
        return Build.MANUFACTURER.toLowerCase(Locale.ROOT).contains("huawei");
    }

    public static class RenderersList {
        public final List<String> rendererIds;
        public final String[] rendererDisplayNames;

        public RenderersList(List<String> rendererIds, String[] rendererDisplayNames) {
            this.rendererIds = rendererIds;
            this.rendererDisplayNames = rendererDisplayNames;
        }
    }

    public static boolean checkVulkanSupport(PackageManager packageManager) {
        if(SDK_INT >= Build.VERSION_CODES.N) {
            return packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) &&
                    packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION);
        }
        return false;
    }

    public static <T> T getWeakReference(WeakReference<T> weakReference) {
        if(weakReference == null) return null;
        return weakReference.get();
    }

    /** Return the renderers that are compatible with this device */
    public static RenderersList getCompatibleRenderers(Context context) {
        if(sCompatibleRenderers != null) return sCompatibleRenderers;
        Resources resources = context.getResources();
        String[] defaultRenderers = resources.getStringArray(R.array.renderer_values);
        String[] defaultRendererNames = resources.getStringArray(R.array.renderer);
        boolean deviceHasVulkan = checkVulkanSupport(context.getPackageManager());
        boolean deviceHasOpenGLES3 = JREUtils.getDetectedVersion() >= 3;
        File nativeDir = new File(Tools.NATIVE_LIB_DIR);
        boolean appHasZink = new File(nativeDir, "libOSMesa.so").exists()
                && new File(nativeDir, "libEGL_mesa.so").exists()
                && new File(nativeDir, "libglxshim.so").exists();
        boolean appHasMobileGlues = new File(nativeDir, "libmobileglues.so").exists();
        boolean appHasLtw = new File(nativeDir, "libltw.so").exists();
        boolean appHasKrypton = new File(nativeDir, "libng_gl4es.so").exists();
        boolean appHasGl4es = new File(nativeDir, "libgl4es_115.so").exists();
        GLInfoUtils.GLInfo gpuInfo = GLInfoUtils.getGlInfo();
        boolean isAdreno = gpuInfo.isAdreno();
        boolean isMali = gpuInfo.renderer != null
                && gpuInfo.renderer.toLowerCase(Locale.ROOT).contains("mali");
        // CS Task 5: OSMesa gallium renderers (Freedreno / VirGL / Panfrost).
        // The binaries ship in arm64-v8a only, so the file check doubles as an
        // ABI gate — on 32-bit/x86 builds these entries never show up.
        boolean appHasFreedreno = new File(Tools.NATIVE_LIB_DIR, "libOSMesa_8.so").exists();
        boolean appHasPanfrost = new File(Tools.NATIVE_LIB_DIR, "libOSMesa_2300d.so").exists();
        List<String> rendererIds = new ArrayList<>(defaultRenderers.length);
        List<String> rendererNames = new ArrayList<>(defaultRendererNames.length);
        for(int i = 0; i < defaultRenderers.length; i++) {
            String rendererId = defaultRenderers[i];
            // Migrate every historical Zink alias to the one route that sets
            // Mesa EGL + Kopper correctly.
            if ("vulkan_zink".equals(rendererId)
                    || "opengles3_desktopgl_zink".equals(rendererId)) {
                rendererId = "opengles3_desktopgl_zink_kopper";
            }
            if (rendererId.contains("zink") && (!deviceHasVulkan || !appHasZink)) continue;
            if (rendererId.equals("opengles_mobileglues") && !appHasMobileGlues) continue;
            if (rendererId.contains("ltw") && (!deviceHasOpenGLES3 || !appHasLtw)) continue;
            if (rendererId.equals("opengles3_KW") && !appHasKrypton) continue;
            if (rendererId.equals("opengles2") && !appHasGl4es) continue;
            if (rendererId.equals("gallium_freedreno") && (!appHasFreedreno || !isAdreno)) continue;
            if (rendererId.equals("gallium_panfrost") && (!appHasPanfrost || !isMali)) continue;
            rendererIds.add(rendererId);
            rendererNames.add(defaultRendererNames[i]);
        }
        sCompatibleRenderers = new RenderersList(rendererIds,
                rendererNames.toArray(new String[0]));

        return sCompatibleRenderers;
    }

    /** Checks if the renderer Id is compatible with the current device */
    public static boolean checkRendererCompatible(Context context, String rendererName) {
         return getCompatibleRenderers(context).rendererIds.contains(rendererName);
    }

    /** Releases the cache of compatible renderers. */
    public static void releaseRenderersCache() {
        sCompatibleRenderers = null;
    }

    public static boolean deviceSupportsGyro(@NonNull Context context) {
        return ((SensorManager)context.getSystemService(Context.SENSOR_SERVICE)).getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;

    }

    public static void dialogForceClose(Context ctx) {
        // Phase 9: a real confirm card, and QUIT lands the user back in the
        // launcher instead of leaving a dead black task behind.
        try {
            new net.kdt.pojavlaunch.ui.CsConfirmDialog(ctx)
                    .kicker("QUIT GAME")
                    .title("Leave this session?")
                    .message("Minecraft closes immediately — unsaved world progress since the last autosave is lost. You will land back in CS Launcher Plus.")
                    .detail("force close  \u00b7  back to launcher")
                    .danger("QUIT TO LAUNCHER", () -> quitToLauncher(ctx))
                    .show();
        } catch (Throwable t) {
            new android.app.AlertDialog.Builder(ctx)
                    .setMessage(R.string.mcn_exit_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> quitToLauncher(ctx)).show();
        }
    }

    /**
     * Kick the user out of the game and straight into the launcher.
     * <p>The game runs in the {@code :game} process and the launcher process
     * killed itself when the game started, so plainly killing this process
     * left nothing on screen. We start {@link LauncherActivity} in a fresh task
     * (Android spawns the {@code :launcher} process again), give the transition
     * a moment to take the foreground, then kill the game process so the JVM,
     * EGL surface and audio are released for real.
     */
    public static void quitToLauncher(Context ctx) {
        try {
            Intent home = new Intent(ctx, LauncherActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(home);
            if (ctx instanceof Activity) {
                try { ((Activity) ctx).finishAffinity(); } catch (Throwable ignored) {}
            }
        } catch (Throwable th) {
            Log.w(Tools.APP_NAME, "Could not relaunch the launcher before exiting", th);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                Tools.fullyExit();
            } catch (Throwable th) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 260);
    }

    public static void switchDemo(boolean isDemo){
        DIR_GAME_NEW = DIR_GAME_HOME + "/.minecraft";
        DIR_HOME_VERSION = DIR_GAME_NEW + "/versions";
        DIR_HOME_LIBRARY = DIR_GAME_NEW + "/libraries";
        ASSETS_PATH = DIR_GAME_NEW + "/assets";
        OBSOLETE_RESOURCES_PATH = DIR_GAME_NEW + "/resources";
    }

    private static NetworkInfo getActiveNetworkInfo(Context ctx) {
        ConnectivityManager connMgr = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return networkInfo; // This can return null when there is no wifi or data connected
    }

    public static boolean isOnline(Context ctx) {
        NetworkInfo info = getActiveNetworkInfo(ctx);
        if(info == null) return false;
        return (info.isConnected());
    }

    public static boolean isDemoProfile(Context ctx){
        return false;
    }

    public static boolean isLocalProfile(Context ctx){
        MinecraftAccount currentProfile = PojavProfile.getCurrentProfileContent(ctx, null);
        return currentProfile == null || currentProfile.isLocal();
    }
    public static boolean hasOnlineProfile(){
        return true;
    }

    public static void hasNoOnlineProfileDialog(Activity activity, @Nullable Runnable run, @Nullable String customTitle, @Nullable String customMessage){
        if (hasOnlineProfile() && !Tools.isDemoProfile(activity)){
            if (run != null) { // Demo profile handling should be using customTitle and customMessage
                run.run();
            }
        } else { // If there is no online profile, show a dialog
            customTitle = customTitle == null ? activity.getString(R.string.no_minecraft_account_found) : customTitle;
            customMessage = customMessage == null ? activity.getString(R.string.feature_requires_java_account) : customMessage;
            dialogOnUiThread(activity, customTitle, customMessage);
        }
    }

    // Some boilerplate to reduce boilerplate elsewhere
    public static void hasNoOnlineProfileDialog(Activity activity){
        hasNoOnlineProfileDialog(activity, null, null, null);
    }
    public static void hasNoOnlineProfileDialog(Activity activity, Runnable run){
        hasNoOnlineProfileDialog(activity, run, null, null);
    }
    public static void hasNoOnlineProfileDialog(Activity activity, String customTitle, String customMessage){
        hasNoOnlineProfileDialog(activity, null, customTitle, customMessage);
    }

    public static String getSelectedVanillaMcVer(){
        String selectedProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        MinecraftProfile selected = LauncherProfiles.mainProfileJson.profiles.get(selectedProfile);
        if (selected == null) { // This should NEVER happen.
            throw new RuntimeException("No profile selected, how did you reach this? Go ask in the discord or github");
        }
        String currentMCVersion = selected.lastVersionId;
        String vanillaVersion = currentMCVersion;
        File providedJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + currentMCVersion + "/" + currentMCVersion + ".json");
        JMinecraftVersionList.Version providedJsonVersion = null;
        try {
            providedJsonVersion = Tools.GLOBAL_GSON.fromJson(Tools.read(providedJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            vanillaVersion = providedJsonVersion.inheritsFrom != null ? providedJsonVersion.inheritsFrom : vanillaVersion;
        } catch (NullPointerException e) {
            throw new RuntimeException(e);
        }
        return vanillaVersion;
    }

    public static Integer mcVersiontoInt(String mcVersion){
        String[] sVersionArray = mcVersion.split("\\.");
        String[] iVersionArray = new String[3];
        // Make sure this is actually a version string
        for (int i = 0; i < iVersionArray.length; i++) {
            try {
                // Ensure there's padding
                sVersionArray[i] =  String.format("%3s", sVersionArray[i]).replace(' ', '0');
                // Grab only the last 3, MCJE 999.999.999 isnt coming soon anyway
                sVersionArray[i] = sVersionArray[i].substring(sVersionArray[i].length() - 3);
            } catch (ArrayIndexOutOfBoundsException ignored){
                // If we don't get 3 a third array, pad with 0s because it's probably 1.21 or something
                iVersionArray[i] = "000";
                continue;
            }
            try {
                // Verify its a real deal, legit number
                Integer.parseInt(sVersionArray[i]);
                iVersionArray[i] = sVersionArray[i];
            } catch (NumberFormatException e) {
                throw new RuntimeException("Tools(mcVersiontoInt): Invalid version string");
            }
        }
        return Integer.parseInt(iVersionArray[0] + iVersionArray[1] + iVersionArray[2]);
    }

    public static boolean isPointerDeviceConnected() {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    || (sources & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                    || (sources & InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL) {
                return true;
            }
        }
        return false;
    }

    public static Object runMethodbyReflection(String className, String methodName) throws ReflectiveOperationException{
        Class<?> clazz = Class.forName(className);
        Method method = clazz.getDeclaredMethod(methodName);
        method.setAccessible(true);
        Object motionListener = method.invoke(null);
        assert motionListener != null;
        return motionListener;
    }

    static class SDL {
        /**
         * Initializes gamepad, joystick, and event subsystems.
         * This triggers {@link SDLControllerManager#pollInputDevices()} and subsequently disables
         * the emulated gamepad implementation.
         */
        public static native void initializeControllerSubsystems();
    }
}
