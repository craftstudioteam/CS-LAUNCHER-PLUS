package net.kdt.pojavlaunch.tasks;


import static net.kdt.pojavlaunch.Architecture.archAsString;
import static net.kdt.pojavlaunch.Architecture.archAsStringAndroid;
import static net.kdt.pojavlaunch.Architecture.getDeviceArchitecture;
import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

public class AsyncAssetManager {

    private AsyncAssetManager(){}

    /**
     * Attempt to install the java 8 runtime, if necessary
     * @param am App context
     */
    public static void unpackRuntime(AssetManager am) {
        /* Check if JRE is included */
        String rt_version = null;
        String current_rt_version = MultiRTUtils.readInternalRuntimeVersion("Internal");
        try {
            rt_version = Tools.read(am.open("components/jre/version"));
        } catch (IOException e) {
            Log.e("JREAuto", "JRE was not included on this APK.", e);
        }
        String exactJREName = MultiRTUtils.getExactJreName(8);
        if(current_rt_version == null && exactJREName != null && !exactJREName.equals("Internal")/*this clause is for when the internal runtime is goofed*/) return;
        if(rt_version == null) return;
        if(rt_version.equals(current_rt_version)) return;

        // Install the runtime in an async manner, hope for the best
        String finalRt_version = rt_version;
        sExecutorService.execute(() -> {

            try {
                MultiRTUtils.installRuntimeNamedBinpack(
                        am.open("components/jre/universal.tar.xz"),
                        am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
                        "Internal", finalRt_version);
                MultiRTUtils.postPrepare("Internal");
            }catch (IOException e) {
                Log.e("JREAuto", "Internal JRE unpack failed", e);
            }
        });
    }

    /** Unpack single files, with no regard to version tracking */
    public static void unpackSingleFiles(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
        sExecutorService.execute(() -> {
            try {
                // Minecraft owns options.txt and its graphics defaults. Never seed
                // launcher-chosen render/FPS/VSync/quality values into a new profile.
                Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, false);

                Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
                Tools.copyAssetFile(ctx,"resolv.conf",Tools.DIR_DATA, false);
            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed to unpack critical components !");
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
        });
    }

    public static void unpackComponents(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
        sExecutorService.execute(() -> {
            try {
                CompletableFuture<?>[] futures = new CompletableFuture<?>[]{
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "caciocavallo", false); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "caciocavallo17", false); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackLwjglNatives(ctx); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "lwjgl3/3.3.3", false); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "lwjgl3/3.4.1", false); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "security", true); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "arc_dns_injector", true); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "methods_injector_agent", true); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "forge_installer", true); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                        CompletableFuture.runAsync(() -> { try { unpackComponent(ctx, "authlib-injector", true); } catch (IOException e) { throw new RuntimeException(e); } }, sExecutorService),
                };
                CompletableFuture.allOf(futures).join();
            } catch (Exception e) {
                Log.e("AsyncAssetManager", "Failed to unpack components !",e );
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
        });
    }
    // The natives keep their own marker file (.natives-version). They must not share lwjgl3/<ver>/version
    // with the Java jars: unpackComponent() treats that file as "this folder is up to date". When both tasks
    // shared it, whichever one finished first made the other skip its update, so a new native library could
    // run next to old Java jars (or the other way round). LWJGL reports that as
    // "Incompatible Java and native library versions".
    private static void unpackLwjglNatives(Context ctx) throws IOException {
        AssetManager am = ctx.getAssets();
        String rootDir = Tools.DIR_DATA;
        String sArch = archAsStringAndroid(getDeviceArchitecture());

        String[] lwjglVersions = {"3.3.3", "3.4.1"};
        for (String lwjglVer : lwjglVersions) {
            String pathToLwjglNatives = String.format("lwjgl-%s-natives/", lwjglVer) + sArch;
            File nativesTargetDir = new File(rootDir, pathToLwjglNatives);
            File nativesMarker = new File(nativesTargetDir, ".natives-version");
            File sentinelFile = new File(nativesTargetDir, "liblwjgl.so");

            String assetVersion;
            try (InputStream is = am.open("components/lwjgl3/" + lwjglVer + "/version")) {
                assetVersion = Tools.read(is);
            }

            // Up to date only if the marker matches the bundled version and the sentinel library is present.
            boolean upToDate = false;
            if (nativesMarker.isFile() && sentinelFile.isFile() && sentinelFile.length() > 0) {
                try (FileInputStream fis = new FileInputStream(nativesMarker)) {
                    upToDate = assetVersion.equals(Tools.read(fis));
                }
            }
            if (upToDate) {
                Log.i("UnpackLwjgl", lwjglVer + " natives are up-to-date with the launcher, continuing...");
                continue;
            }

            String[] fileList = am.list("components/" + pathToLwjglNatives);
            if (fileList == null || fileList.length == 0) {
                Log.w("UnpackLwjgl", lwjglVer + " has no natives bundled for " + sArch + ", skipping.");
                continue;
            }

            Log.i("UnpackLwjgl", lwjglVer + " natives are missing or outdated, unpacking new...");
            try {
                // Start from an empty folder. A .so that the new build no longer ships would otherwise stay
                // on java.library.path and could still be loaded next to the new Java classes.
                FileUtils.deleteDirectory(nativesTargetDir);
                for (String fileName : fileList) {
                    Tools.copyAssetFile(ctx, "components/" + pathToLwjglNatives + "/" + fileName,
                            nativesTargetDir.getAbsolutePath(), true);
                }
                if (!sentinelFile.isFile() || sentinelFile.length() == 0) {
                    throw new IOException("liblwjgl.so was not extracted for " + lwjglVer);
                }
                // Written last, so an interrupted extraction is retried on the next launch.
                FileUtils.writeStringToFile(nativesMarker, assetVersion, "UTF-8");
            } catch (IOException e) {
                Log.e("UnpackLwjgl", "Failed to unpack " + lwjglVer + " natives", e);
            }
        }
    }

    // Copies every file of a component except its version marker. The marker is copied last, so a copy that
    // dies halfway is retried on the next launch instead of being treated as complete.
    private static void copyComponentFiles(Context ctx, AssetManager am, String component, String rootDir) throws IOException {
        String[] fileList = am.list("components/" + component);
        for (String fileName : fileList) {
            if ("version".equals(fileName)) continue;
            Tools.copyAssetFile(ctx, "components/" + component + "/" + fileName, rootDir + "/" + component, true);
        }
        Tools.copyAssetFile(ctx, "components/" + component + "/version", rootDir + "/" + component, true);
    }

    private static void unpackComponent(Context ctx, String component, boolean privateDirectory) throws IOException {
        AssetManager am = ctx.getAssets();
        String rootDir = privateDirectory ? Tools.DIR_DATA : Tools.DIR_GAME_HOME;

        File versionFile = new File(rootDir + "/" + component + "/version");
        try (InputStream is = am.open("components/" + component + "/version")) {
            if (!versionFile.exists()) {
                if (versionFile.getParentFile().exists() && versionFile.getParentFile().isDirectory()) {
                    FileUtils.deleteDirectory(versionFile.getParentFile());
                }
                versionFile.getParentFile().mkdir();

                Log.i("UnpackPrep", component + ": Pack was installed manually, or does not exist, unpacking new...");
                copyComponentFiles(ctx, am, component, rootDir);
            } else {
                try (FileInputStream fis = new FileInputStream(versionFile)) {
                    String release1 = Tools.read(is);
                    String release2 = Tools.read(fis);
                    if (!release1.equals(release2)) {
                        if (versionFile.getParentFile().exists() && versionFile.getParentFile().isDirectory()) {
                            FileUtils.deleteDirectory(versionFile.getParentFile());
                        }
                        versionFile.getParentFile().mkdir();
                        copyComponentFiles(ctx, am, component, rootDir);
                    } else {
                        Log.i("UnpackPrep", component + ": Pack is up-to-date with the launcher, continuing...");
                    }
                }
            }
        }
    }
}
