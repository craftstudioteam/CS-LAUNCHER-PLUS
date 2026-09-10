package net.kdt.pojavlaunch.tasks;

import static net.kdt.pojavlaunch.PojavApplication.sExecutorService;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonSyntaxException;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JAssetInfo;
import net.kdt.pojavlaunch.JAssets;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.mirrors.DownloadMirror;
import net.kdt.pojavlaunch.mirrors.MirrorTamperedException;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadControl;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftClientInfo;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

public class MinecraftDownloader {
    private static final double ONE_MEGABYTE = (1024d * 1024d);
    public static final String MINECRAFT_RES = "https://resources.download.minecraft.net/";
    private static final String MAVEN_CENTRAL_REPO1 = "https://repo1.maven.org/maven2/";
    // Metadata files (version JSON / asset index) are tiny, so retrying them a few
    // times on transient network errors is cheap and saves the whole installation.
    private static final int METADATA_DOWNLOAD_ATTEMPTS = 3;
    private static final long METADATA_RETRY_DELAY_MS = 750L;
    private AtomicReference<Exception> mDownloaderThreadException;
    private ArrayList<DownloaderTask> mScheduledDownloadTasks;
    private ArrayList<File> mDeclaredNatives;
    private AtomicLong mProcessedFileCounter;
    private AtomicLong mProcessedSizeCounter; // Total bytes of processed files (passed SHA1 or downloaded)
    private AtomicLong mInternetUsageCounter; // How many bytes downloaded over Internet
    private long mTotalFileCount;
    private long mTotalSize;
    private File mSourceJarFile; // The source client JAR picked during the inheritance process
    private File mTargetJarFile; // The destination client JAR to which the source will be copied to.
    private boolean mUseFileCounter; // Whether a file counter or a size counter should be used for progress

    private static final ThreadLocal<byte[]> sThreadLocalDownloadBuffer = new ThreadLocal<>();

    private boolean isLocalProfile = false;
    private boolean isOnline;

    /**
     * Start the game version download process on the global executor service.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param version The JMinecraftVersionList.Version from the version list, if available
     * @param realVersion The version ID (necessary)
     * @param listener The download status listener
     */
    public void start(@Nullable Activity activity, @Nullable JMinecraftVersionList.Version version,
                      @NonNull String realVersion,
                      @NonNull AsyncMinecraftDownloader.DoneListener listener) {
        if(activity != null){
            isLocalProfile = Tools.isLocalProfile(activity);
            isOnline = Tools.isOnline(activity);
            Tools.switchDemo(Tools.isDemoProfile(activity));

        } else {
            isLocalProfile = true;
            Tools.switchDemo(true);
        }

        sExecutorService.execute(() -> {
            try {
                // Launch semantics: verification phase — the Download Console
                // stays down unless real bytes start flowing (see reportProgress*).
                net.kdt.pojavlaunch.launch.LaunchTracker.verifying();
                if(isLocalProfile || !isOnline) {
                    String versionMessage = realVersion; // Use provided version unless we find its a modded instance

                    // See if provided version is a modded version and if that version depends on another jar, check for presence of both jar's .json.
                    try {
                        // This reads the .json associated with the provided version. If it fails, we can assume it's not installed.
                        File providedJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + realVersion + "/" + realVersion + ".json");
                        JMinecraftVersionList.Version providedJson = Tools.GLOBAL_GSON.fromJson(Tools.read(providedJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);

                        // This checks if running modded version that depends on other jars, so we use that for the error message.
                        File vanillaJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + providedJson.inheritsFrom + "/" + providedJson.inheritsFrom + ".json");
                        versionMessage = providedJson.inheritsFrom != null ? providedJson.inheritsFrom : versionMessage;

                        // Ensure they're both not some 0 byte corrupted json
                        if (providedJsonFile.length() == 0 || vanillaJsonFile.exists() && vanillaJsonFile.length() == 0){
                            throw new RuntimeException("Minecraft "+versionMessage+ " is needed by " +realVersion); }

                        listener.onDownloadDone();
                    } catch (Exception e) {
                        net.kdt.pojavlaunch.launch.LaunchTracker.fail();
                        String tryagain = !isOnline ? "Please ensure you have an internet connection" : "Please try again on your Microsoft Account";
                        Tools.showErrorRemote(versionMessage + " is not currently installed. "+ tryagain, e);
                    }
                }else {
                downloadGame(activity, version, realVersion);
                listener.onDownloadDone();
                }
            }catch (Exception e) {
                if (DownloadControl.isCancellation(e)) {
                    // User pressed STOP on the download console — wind down quietly.
                    Log.i("MinecraftDownloader", "Download stopped by user");
                } else {
                    listener.onDownloadFailed(e);
                }
            }
            ProgressLayout.clearProgress(ProgressLayout.DOWNLOAD_MINECRAFT);
        });
    }

    /**
     * Download the game version.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @throws Exception when an exception occurs in the function body or in any of the downloading threads.
     */
    private void downloadGame(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws Exception {
        // Put up a dummy progress line, for the activity to start the service and do all the other necessary
        // work to keep the launcher alive. We will replace this line when we will start downloading stuff.
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0, R.string.newdl_starting);
        SpeedCalculator speedCalculator = new SpeedCalculator();

        mTargetJarFile = createGameJarPath(versionName);
        mScheduledDownloadTasks = new ArrayList<>();
        mDeclaredNatives = new ArrayList<>();
        mProcessedFileCounter = new AtomicLong(0);
        mProcessedSizeCounter = new AtomicLong(0);
        mInternetUsageCounter = new AtomicLong(0);
        mDownloaderThreadException = new AtomicReference<>(null);
        mUseFileCounter = false;

        downloadAndProcessMetadata(activity, verInfo, versionName);

        ArrayBlockingQueue<Runnable> taskQueue =
                new ArrayBlockingQueue<>(mScheduledDownloadTasks.size(), false);
        // TURBO: saturate the pipe — mobile radios only reach full speed with
        // many parallel HTTP streams (this is exactly what dedicated download
        // managers do). IO-bound, so thread count can exceed core count.
        // The pool is IO-bound, so cores are the wrong ceiling: what matters is how
        // many sockets the radio and the CDN tolerate. Mojang's assets host happily
        // serves a dozen; the previous cap of 10 left small-file latency as the
        // bottleneck (each asset costs a connect + a response header before a single
        // byte moves), so a wider pool is what actually shortens a version install.
        int turboThreads = Math.min(14, Math.max(8, Runtime.getRuntime().availableProcessors() * 2));
        ThreadPoolExecutor downloaderPool =
                new ThreadPoolExecutor(turboThreads, turboThreads, 500, TimeUnit.MILLISECONDS, taskQueue);

        // I have tried pre-filling the queue directly instead of doing this, but it didn't work.
        // What a shame.
        for(DownloaderTask scheduledTask : mScheduledDownloadTasks) downloaderPool.execute(scheduledTask);
        downloaderPool.shutdown();

        try {
            while (mDownloaderThreadException.get() == null &&
                    !downloaderPool.awaitTermination(33, TimeUnit.MILLISECONDS)) {
                double speed = speedCalculator.feed(mInternetUsageCounter.get()) / ONE_MEGABYTE;
                if(mUseFileCounter) reportProgressFileCounter(speed);
                else reportProgressSizeCounter(speed);
            }
            Exception thrownException = mDownloaderThreadException.get();
            if(thrownException != null) {
                throw thrownException;
            } else if (DownloadControl.consumeCancelledNote(ProgressLayout.DOWNLOAD_MINECRAFT)) {
                // Every cancelled pool thread swallowed its exception (skippable files),
                // but the user still stopped this transfer — never continue to launch.
                throw new DownloadControl.DownloadCancelledException(ProgressLayout.DOWNLOAD_MINECRAFT);
            } else {
                ensureJarFileCopy();
                extractNatives(versionName);
            }
        }catch (InterruptedException e) {
            // Interrupted while waiting, which means that the download was cancelled.
            // Kill all downloading threads immediately, and ignore any exceptions thrown by them
            downloaderPool.shutdownNow();
        }
    }

    private void reportProgressFileCounter(double speed) {
        // Real bytes are flowing → launch overlay yields to the Download Console.
        if (mInternetUsageCounter.get() > 0) net.kdt.pojavlaunch.launch.LaunchTracker.downloading();
        long dlFileCounter = mProcessedFileCounter.get();
        int progress = (int)((dlFileCounter * 100L) / mTotalFileCount);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files, dlFileCounter,
                mTotalFileCount, speed);
    }

    private void reportProgressSizeCounter(double speed) {
        // Real bytes are flowing → launch overlay yields to the Download Console.
        if (mInternetUsageCounter.get() > 0) net.kdt.pojavlaunch.launch.LaunchTracker.downloading();
        long dlFileSize = mProcessedSizeCounter.get();
        double dlSizeMegabytes = (double) dlFileSize / ONE_MEGABYTE;
        double dlTotalMegabytes = (double) mTotalSize / ONE_MEGABYTE;
        int progress = (int)((dlFileSize * 100L) / mTotalSize);
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, progress,
                R.string.newdl_downloading_game_files_size, dlSizeMegabytes, dlTotalMegabytes, speed);
    }

    private File createGameJsonPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".json");
    }

    private File createGameJarPath(String versionId) {
        return new File(Tools.DIR_HOME_VERSION, versionId + File.separator + versionId + ".jar");
    }

    /**
     * Ensure that there is a copy of the client JAR file in the version folder, if a copy is
     * needed.
     * @throws IOException if the copy fails
     */
    private void ensureJarFileCopy() throws IOException {
        if(mSourceJarFile == null) return;
        if(mSourceJarFile.equals(mTargetJarFile)) return;
        if(mTargetJarFile.exists()) return;
        FileUtils.ensureParentDirectory(mTargetJarFile);
        Log.i("NewMCDownloader", "Copying " + mSourceJarFile.getName() + " to "+mTargetJarFile.getAbsolutePath());
        org.apache.commons.io.FileUtils.copyFile(mSourceJarFile, mTargetJarFile, false);
    }

    private File nativeManifest(File directory) { return new File(directory, ".native-cache-manifest.json"); }

    private JSONArray nativeSourceFingerprint() throws Exception {
        JSONArray array=new JSONArray();
        java.util.ArrayList<File> sorted=new java.util.ArrayList<>(mDeclaredNatives);
        sorted.sort((a,b)->a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        for(File source:sorted){JSONObject o=new JSONObject();o.put("path",source.getAbsolutePath());o.put("size",source.length());o.put("mtime",source.lastModified());array.put(o);}return array;
    }

    private boolean isNativeCacheValid(File target,String versionName){
        File manifest=nativeManifest(target);if(!manifest.isFile())return false;
        try{JSONObject o=new JSONObject(Tools.read(manifest.getAbsolutePath()));
            if(o.optInt("schema")!=1||o.optInt("build")!=BuildConfig.VERSION_CODE||!BuildConfig.VERSION_NAME.equals(o.optString("buildName")))return false;
            if(!versionName.equals(o.optString("minecraft")))return false;
            if(o.optInt("arch")!=Tools.DEVICE_ARCHITECTURE)return false;
            if(!nativeSourceFingerprint().toString().equals(o.optJSONArray("sources").toString()))return false;
            JSONArray files=o.optJSONArray("files");if(files==null||files.length()==0)return false;
            for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);File out=new File(target,f.getString("name"));if(!out.isFile()||out.length()!=f.getLong("size")||out.length()==0)return false;}
            return true;
        }catch(Throwable ignored){return false;}
    }

    private void writeNativeManifest(File target,String versionName) throws Exception{
        JSONObject o=new JSONObject();o.put("schema",1);o.put("build",BuildConfig.VERSION_CODE);o.put("buildName",BuildConfig.VERSION_NAME);o.put("minecraft",versionName);o.put("arch",Tools.DEVICE_ARCHITECTURE);o.put("sources",nativeSourceFingerprint());
        JSONArray files=new JSONArray();File[] outputs=target.listFiles(f->f.isFile()&&!f.getName().startsWith(".native-cache"));if(outputs!=null){java.util.Arrays.sort(outputs,(a,b)->a.getName().compareTo(b.getName()));for(File output:outputs){JSONObject f=new JSONObject();f.put("name",output.getName());f.put("size",output.length());files.put(f);}}
        if(files.length()==0)throw new IOException("Native extraction produced no files");o.put("files",files);Tools.write(nativeManifest(target).getAbsolutePath(),o.toString());
    }

    private void extractNatives(String versionName) throws IOException {
        if(mDeclaredNatives.isEmpty()) return;
        int totalCount=mDeclaredNatives.size();
        File targetDirectory=new File(Tools.DIR_CACHE,"natives/"+versionName);
        if(isNativeCacheValid(targetDirectory,versionName)){
            Log.i("NativeCache","Verified cache hit for "+versionName);return;
        }
        ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT,0,R.string.newdl_extracting_native_libraries,0,totalCount);
        File parent=targetDirectory.getParentFile();FileUtils.ensureDirectory(parent);
        File tempDirectory=new File(parent,"."+versionName+".extracting");
        File backupDirectory=new File(parent,"."+versionName+".backup");
        org.apache.commons.io.FileUtils.deleteDirectory(tempDirectory);FileUtils.ensureDirectory(tempDirectory);
        try{
            NativesExtractor extractor=new NativesExtractor(tempDirectory);int count=0;
            for(File source:mDeclaredNatives){extractor.extractFromAar(source);count++;ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT,count*100/totalCount,R.string.newdl_extracting_native_libraries,count,totalCount);}
            writeNativeManifest(tempDirectory,versionName);
            org.apache.commons.io.FileUtils.deleteDirectory(backupDirectory);
            if(targetDirectory.exists()&&!targetDirectory.renameTo(backupDirectory))throw new IOException("Could not stage old native cache");
            if(!tempDirectory.renameTo(targetDirectory)){
                org.apache.commons.io.FileUtils.copyDirectory(tempDirectory,targetDirectory);org.apache.commons.io.FileUtils.deleteDirectory(tempDirectory);
            }
            org.apache.commons.io.FileUtils.deleteDirectory(backupDirectory);
        }catch(Throwable error){
            org.apache.commons.io.FileUtils.deleteDirectory(tempDirectory);
            if(backupDirectory.exists()){
                org.apache.commons.io.FileUtils.deleteDirectory(targetDirectory);
                backupDirectory.renameTo(targetDirectory);
            }
            if(error instanceof IOException)throw(IOException)error;throw new IOException("Native extraction cache update failed",error);
        }
    }

    private File downloadGameJson(JMinecraftVersionList.Version verInfo) throws IOException, MirrorTamperedException {
        File targetFile = createGameJsonPath(verInfo.id);
        // A 0-byte / unreadable leftover from an interrupted download must never be
        // treated as "already on disk": it used to poison every future install of the
        // version (ensureSha1 with an unknown hash skips existing files). Purge it.
        if(targetFile.exists() && !isVersionJsonUsable(targetFile)) {
            Log.w("MinecraftDownloader", "Deleting damaged cached metadata: " + targetFile.getAbsolutePath());
            if(!targetFile.delete())
                Log.w("MinecraftDownloader", "Could not delete damaged metadata file, download will overwrite it");
        }
        if(verInfo.sha1 == null && targetFile.canRead() && targetFile.isFile())
            return targetFile;
        FileUtils.ensureParentDirectory(targetFile);
        try {
            DownloadUtils.ensureSha1(targetFile, LauncherPreferences.PREF_VERIFY_MANIFEST ? verInfo.sha1 : null, () -> {
                ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                        R.string.newdl_downloading_metadata, targetFile.getName());
                downloadMetadataWithRetry(DownloadMirror.DOWNLOAD_CLASS_METADATA, verInfo.url, targetFile);
                return null;
            });
        }catch (DownloadUtils.SHA1VerificationException e) {
            if(DownloadMirror.isMirrored()) throw new MirrorTamperedException();
            else throw e;
        }
        // Guarantee the contract of this method: callers get a usable file, or an exception.
        if(!isVersionJsonUsable(targetFile))
            throw new IOException("Metadata for version " + verInfo.id
                    + " is missing or empty after download (source: " + verInfo.url + ")");
        return targetFile;
    }

    private JAssets downloadAssetsIndex(JMinecraftVersionList.Version verInfo) throws IOException{
        JMinecraftVersionList.AssetIndex assetIndex = verInfo.assetIndex;
        if(assetIndex == null || verInfo.assets == null) return null;
        File targetFile = new File(Tools.ASSETS_PATH, "indexes"+ File.separator + verInfo.assets + ".json");
        FileUtils.ensureParentDirectory(targetFile);
        DownloadUtils.ensureSha1(targetFile, assetIndex.sha1, ()-> {
            ProgressLayout.setProgress(ProgressLayout.DOWNLOAD_MINECRAFT, 0,
                    R.string.newdl_downloading_metadata, targetFile.getName());
            downloadMetadataWithRetry(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return null;
        });
        try {
            return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
        }catch (IOException | JsonSyntaxException e) {
            // The cached asset index is corrupt - re-fetch it once instead of
            // crashing the whole installation with an unchecked exception.
            Log.w("MinecraftDownloader", "Asset index " + targetFile.getName() + " is corrupt, re-downloading", e);
            targetFile.delete();
            downloadMetadataWithRetry(DownloadMirror.DOWNLOAD_CLASS_METADATA, assetIndex.url, targetFile);
            return Tools.GLOBAL_GSON.fromJson(Tools.read(targetFile), JAssets.class);
        }
    }

    /**
     * Download a small metadata document, retrying transient failures a few times
     * before giving up. The mirrored helper already fails over from the mirror to the
     * official source per call, so one full retry round still stays cheap.
     */
    private void downloadMetadataWithRetry(int downloadClass, String url, File outputFile) throws IOException {
        IOException lastFailure = null;
        for(int attempt = 1; attempt <= METADATA_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                DownloadMirror.downloadFileMirrored(downloadClass, url, outputFile);
                return;
            }catch (IOException e) {
                lastFailure = e;
                Log.w("MinecraftDownloader", "Metadata download attempt " + attempt + "/"
                        + METADATA_DOWNLOAD_ATTEMPTS + " failed for " + url + " (" + e + ")");
                if(attempt < METADATA_DOWNLOAD_ATTEMPTS) {
                    try {
                        Thread.sleep(METADATA_RETRY_DELAY_MS);
                    }catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while downloading " + url, lastFailure);
                    }
                }
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("Unable to download from " + url);
    }
    
    private MinecraftClientInfo getClientInfo(JMinecraftVersionList.Version verInfo) {
        Map<String, MinecraftClientInfo> downloads = verInfo.downloads;
        if(downloads == null) return null;
        return downloads.get("client");
    }

    /**
     * Download (if necessary) and process a version's metadata, scheduling all downloads that this
     * version needs.
     * @param activity Activity, used for automatic installation of JRE 17 if needed
     * @param verInfo The JMinecraftVersionList.Version from the version list, if available
     * @param versionName The version ID (necessary)
     * @return false if JRE17 installation failed, true otherwise
     * @throws IOException if the download of any of the metadata files fails
     */
    private boolean downloadAndProcessMetadata(Activity activity, JMinecraftVersionList.Version verInfo, String versionName) throws IOException, MirrorTamperedException {
        File versionJsonFile;
        JMinecraftVersionList.Version manifestEntry = verInfo;
        if(manifestEntry != null) {
            versionJsonFile = downloadGameJson(manifestEntry);
        } else {
            manifestEntry = AsyncMinecraftDownloader.getListedVersion(versionName);
            if(manifestEntry == null) {
                // The in-memory copy of the version list can legitimately miss this
                // version: cold shortcut starts skip the refresh entirely, the on-disk
                // cache is refreshed only once a day, and a same-day release is absent
                // from any cache written before it. Self-heal via a forced manifest
                // refresh instead of declaring the version "not installed".
                manifestEntry = refreshVersionListAndFind(versionName);
            }
            if(manifestEntry != null) {
                versionJsonFile = downloadGameJson(manifestEntry);
            } else {
                // Not an official version - treat it as a locally installed
                // custom/modded one and look at its on-disk metadata.
                versionJsonFile = createGameJsonPath(versionName);
            }
        }
        Log.d("CS_LAUNCHER", "Version JSON path: " + versionJsonFile.getAbsolutePath());
        if(!isVersionJsonUsable(versionJsonFile)) {
            // Explain the real reason instead of the blanket "re-install" advice.
            String detail;
            if(manifestEntry != null) {
                detail = "the metadata download failed despite retries - check your internet connection";
            } else if(!isOnline) {
                detail = "no internet connection and no local installation found";
            } else {
                detail = "it was not found in Mojang's version list and has no valid local installation - please re-install the version";
            }
            String message = "Failed to load version: " + versionName + " (" + detail + ").";
            Log.e("CS_LAUNCHER", message);
            new Handler(Looper.getMainLooper()).post(() -> {
                if(activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            });
            throw new IOException(message);
        }
        // Parse the metadata, transparently re-downloading the document once if the
        // cached copy turns out to be damaged (truncated write, killed process...).
        verInfo = readVersionJson(versionJsonFile, versionName, manifestEntry);

        // Runtime resolution: no-op when up to date. NewJREUtil flips the
        // launch phase to RUNTIME itself only when a real install begins,
        // so the launch overlay never flickers on the fast path.
        if(activity != null && !NewJREUtil.installNewJreIfNeeded(activity, verInfo)){
            net.kdt.pojavlaunch.launch.LaunchTracker.fail();
            throw new RuntimeException(activity.getString(R.string.exception_failed_to_unpack_jre17));
        }

        JAssets assets = downloadAssetsIndex(verInfo);
        if(assets != null) scheduleAssetDownloads(assets);


        MinecraftClientInfo minecraftClientInfo = getClientInfo(verInfo);
        if(minecraftClientInfo != null) scheduleGameJarDownload(minecraftClientInfo, versionName);

        if(verInfo.libraries != null) scheduleLibraryDownloads(verInfo.libraries);

        if(verInfo.logging != null) scheduleLoggingAssetDownloadIfNeeded(verInfo.logging);

        if(Tools.isValidString(verInfo.inheritsFrom)) {
            JMinecraftVersionList.Version inheritedVersion = AsyncMinecraftDownloader.getListedVersion(verInfo.inheritsFrom);
            // Infinite inheritance !?! :noway:
            return downloadAndProcessMetadata(activity, inheritedVersion, verInfo.inheritsFrom);
        }
        return true;
    }

    /**
     * @return true when the file looks like a usable cached metadata document
     * (present, readable, non-empty). Zero-byte leftovers from interrupted
     * downloads fail this check on purpose.
     */
    private static boolean isVersionJsonUsable(File file) {
        return file != null && file.isFile() && file.canRead() && file.length() > 0;
    }

    /**
     * Force-refresh the version manifest and look the given version up in the fresh
     * copy. This is the self-healing counterpart of the throttled/occasionally
     * absent cached list.
     * <p>
     * The refresh is deliberately skipped when the device is offline, or when the
     * version already has usable local metadata: custom/modded versions always
     * carry their own JSON and are never part of Mojang's manifest, so a refresh
     * would only waste a network round-trip on a guaranteed miss.
     *
     * @return the manifest entry for the version, or null when unavailable
     */
    @Nullable
    private JMinecraftVersionList.Version refreshVersionListAndFind(String versionName) {
        if(!isOnline) return null;
        if(isVersionJsonUsable(createGameJsonPath(versionName))) return null;
        Log.i("MinecraftDownloader", "Version " + versionName
                + " not found in the cached version list - refreshing the manifest");
        JMinecraftVersionList freshList = AsyncVersionList.fetchFreshVersionListBlocking();
        if(freshList == null) {
            Log.w("MinecraftDownloader", "Version list refresh failed; cannot resolve " + versionName);
            return null;
        }
        return AsyncMinecraftDownloader.getListedVersion(freshList, versionName);
    }

    /**
     * Parse a version JSON document from disk. Every corruption flavour (truncated
     * document, empty file, missing id...) surfaces as a JsonSyntaxException so
     * callers can handle all of them with a single recovery path.
     */
    @NonNull
    private static JMinecraftVersionList.Version parseVersionJson(File versionJsonFile) throws IOException {
        String rawJson = Tools.read(versionJsonFile.getAbsolutePath());
        if(rawJson.trim().isEmpty())
            throw new JsonSyntaxException("Version JSON is empty: " + versionJsonFile.getAbsolutePath());
        JMinecraftVersionList.Version parsed = Tools.GLOBAL_GSON.fromJson(rawJson, JMinecraftVersionList.Version.class);
        if(parsed == null || !Tools.isValidString(parsed.id))
            throw new JsonSyntaxException("Version JSON has no valid id: " + versionJsonFile.getAbsolutePath());
        return parsed;
    }

    /**
     * Read and parse the version JSON, healing a damaged on-disk copy by deleting
     * and re-downloading it once. An IOException carrying the actual reason is only
     * thrown when the freshly downloaded document fails as well (or no official
     * download source exists for this version at all).
     */
    @NonNull
    private JMinecraftVersionList.Version readVersionJson(File versionJsonFile, String versionName,
                                                          @Nullable JMinecraftVersionList.Version manifestEntry)
            throws IOException, MirrorTamperedException {
        try {
            return parseVersionJson(versionJsonFile);
        }catch (IOException | JsonSyntaxException firstFailure) {
            Log.w("MinecraftDownloader", "Cached version JSON for " + versionName
                    + " is unreadable/corrupt; deleting and re-downloading it", firstFailure);
            if(!versionJsonFile.delete())
                Log.w("MinecraftDownloader", "Could not delete the corrupt JSON: " + versionJsonFile.getAbsolutePath());
            // Find a source to re-download from: the manifest entry we already have,
            // the in-memory release table, or as a last resort a fresh manifest.
            JMinecraftVersionList.Version source = manifestEntry;
            if(source == null) source = AsyncMinecraftDownloader.getListedVersion(versionName);
            if(source == null) source = refreshVersionListAndFind(versionName);
            if(source == null) {
                throw new IOException("Failed to load version: " + versionName
                        + " (the local metadata is damaged and this is not an official"
                        + " version that can be re-downloaded - please re-install the version).",
                        firstFailure);
            }
            File reDownloadedFile = downloadGameJson(source);
            try {
                return parseVersionJson(reDownloadedFile);
            }catch (IOException | JsonSyntaxException secondFailure) {
                throw new IOException("Failed to load version: " + versionName
                        + " (freshly downloaded metadata could not be parsed: "
                        + secondFailure.getMessage() + ").", secondFailure);
            }
        }
    }

    private void growDownloadList(int addedElementCount) {
        mScheduledDownloadTasks.ensureCapacity(mScheduledDownloadTasks.size() + addedElementCount);
    }

    private void scheduleDownload(File targetFile, int downloadClass, String url, String sha1,
                                  long size, boolean skipIfFailed) throws IOException {
        FileUtils.ensureParentDirectory(targetFile);
        mTotalFileCount++;
        // Only attempt to check size if we still use the size counter and didn't switch to file counter.
        if(size <= 0 && !mUseFileCounter) {
            size = DownloadMirror.getContentLengthMirrored(downloadClass, url);
        }
        if(size < 0) {
            // If we were unable to get the content length ourselves, we automatically fall back
            // to tracking the progress using the file counter.
            size = 0;
            mUseFileCounter = true;
            Log.i("MinecraftDownloader", "Failed to determine size of "+targetFile.getName()+", switching to file counter");
        }else {
            mTotalSize += size;
        }
        mScheduledDownloadTasks.add(
                new DownloaderTask(targetFile, downloadClass, url, sha1, size, skipIfFailed)
        );
    }

    /**
     * Schedule the download of an AAR library containing the required natives, for later extraction
     * and adding to the library path.
     * @param baseRepository the source Maven repository to download from.
     * @param dependentLibrary the DependentLibrary to get the path from
     * @throws IOException in case if download scheduling fails.
     */
    private void scheduleNativeLibraryDownload(String baseRepository, DependentLibrary dependentLibrary) throws IOException {
        String path = FileUtils.removeExtension(Tools.artifactToPath(dependentLibrary)) + ".aar";
        String downloadUrl = baseRepository + path;
        File targetPath = new File(Tools.DIR_HOME_LIBRARY, path);
        mDeclaredNatives.add(targetPath);
        scheduleDownload(targetPath, DownloadMirror.DOWNLOAD_CLASS_LIBRARIES, downloadUrl, null, 0, true);
    }

    private void scheduleLibraryDownloads(DependentLibrary[] dependentLibraries) throws IOException {
        Tools.preProcessLibraries(dependentLibraries);
        growDownloadList(dependentLibraries.length);
        for(DependentLibrary dependentLibrary : dependentLibraries) {
            // Don't download lwjgl, we have our own bundled in.
            if(dependentLibrary.name.startsWith("org.lwjgl")) continue;
            // Special handling for JNA Android natives
            if(dependentLibrary.name.startsWith("net.java.dev.jna:jna:")) {
                scheduleNativeLibraryDownload(MAVEN_CENTRAL_REPO1, dependentLibrary);
            }
            String libArtifactPath = Tools.artifactToPath(dependentLibrary);
            String sha1 = null, url = null;
            long size = 0;
            boolean skipIfFailed = false;
            if(dependentLibrary.downloads != null) {
                if(dependentLibrary.downloads.artifact != null) {
                    MinecraftLibraryArtifact artifact = dependentLibrary.downloads.artifact;
                    sha1 = artifact.sha1;
                    url = artifact.url;
                    size = artifact.size;
                } else {
                    // If the library has a downloads section but doesn't have an artifact in
                    // it, it is likely natives-only, which means it can be skipped.
                    Log.i("NewMCDownloader", "Skipped library " + dependentLibrary.name + " due to lack of artifact");
                    continue;
                }
            }
            if(url == null) {
                url = (dependentLibrary.url == null
                        ? "https://libraries.minecraft.net/"
                        : dependentLibrary.url.replace("http://","https://")) + libArtifactPath;
                skipIfFailed = true;
            }
            if(!LauncherPreferences.PREF_CHECK_LIBRARY_SHA) sha1 = null;
            scheduleDownload(new File(Tools.DIR_HOME_LIBRARY, libArtifactPath),
                    DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                    url, sha1, size, skipIfFailed
            );
        }
    }
    
    private void scheduleAssetDownloads(JAssets assets) throws IOException {
        Map<String, JAssetInfo> assetObjects = assets.objects;
        if(assetObjects == null) return;
        Set<String> assetNames = assetObjects.keySet();
        growDownloadList(assetNames.size());
        for(String asset : assetNames) {
            JAssetInfo assetInfo = assetObjects.get(asset);
            if(assetInfo == null) continue;
            File targetFile;
            String hashedPath = assetInfo.hash.substring(0, 2) + File.separator + assetInfo.hash;
            String basePath = assets.mapToResources ? Tools.OBSOLETE_RESOURCES_PATH : Tools.ASSETS_PATH;
            if(assets.virtual || assets.mapToResources) {
                targetFile = new File(basePath, asset);
            } else {
                targetFile = new File(basePath, "objects" + File.separator + hashedPath);
            }
            String sha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ? assetInfo.hash : null;
            scheduleDownload(targetFile,
                    DownloadMirror.DOWNLOAD_CLASS_ASSETS,
                    MINECRAFT_RES + hashedPath,
                    sha1,
                    assetInfo.size,
                    false);
        }
    }

    private void scheduleLoggingAssetDownloadIfNeeded(JMinecraftVersionList.LoggingConfig loggingConfig) throws IOException {
        if(loggingConfig.client == null || loggingConfig.client.file == null) return;
        JMinecraftVersionList.FileProperties loggingFileProperties = loggingConfig.client.file;
        File internalLoggingConfig = new File(Tools.DIR_DATA + File.separator + "security",
                loggingFileProperties.id.replace("client", "log4j-rce-patch"));
        if(internalLoggingConfig.exists()) return;
        File destination = new File(Tools.DIR_GAME_NEW, loggingFileProperties.id);
        scheduleDownload(destination,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                loggingFileProperties.url,
                loggingFileProperties.sha1,
                loggingFileProperties.size,
                false);
    }

    private void scheduleGameJarDownload(MinecraftClientInfo minecraftClientInfo, String versionName) throws IOException {
        File clientJar = createGameJarPath(versionName);
        String clientSha1 = LauncherPreferences.PREF_CHECK_LIBRARY_SHA ?
                minecraftClientInfo.sha1 : null;
        growDownloadList(1);
        scheduleDownload(clientJar,
                DownloadMirror.DOWNLOAD_CLASS_LIBRARIES,
                minecraftClientInfo.url,
                clientSha1,
                minecraftClientInfo.size,
                false
        );
        // Store the path of the JAR to copy it into our new version folder later.
        mSourceJarFile = clientJar;
    }

    private static byte[] getLocalBuffer() {
        byte[] tlb = sThreadLocalDownloadBuffer.get();
        if(tlb != null) return tlb;
        tlb = new byte[131072]; // TURBO: 128K per-thread copy buffer (fewer syscalls, faster flash writes)
        sThreadLocalDownloadBuffer.set(tlb);
        return tlb;
    }

    private final class DownloaderTask implements Runnable, Tools.DownloaderFeedback, DownloadControl.KeyedFeedback {
        private final File mTargetPath;
        private final String mTargetUrl;
        private String mTargetSha1;
        private final int mDownloadClass;
        private final boolean mSkipIfFailed;
        private int mLastCurr;
        private final long mDownloadSize;

        /** Lets the monitored copy loop apply deck pause/stop to game-file transfers. */
        @Override
        public String getControlKey() {
            return ProgressLayout.DOWNLOAD_MINECRAFT;
        }

        DownloaderTask(File targetPath, int downloadClass, String targetUrl, String targetSha1,
                       long downloadSize, boolean skipIfFailed) {
            this.mTargetPath = targetPath;
            this.mTargetUrl = targetUrl;
            this.mTargetSha1 = targetSha1;
            this.mDownloadClass = downloadClass;
            this.mDownloadSize = downloadSize;
            this.mSkipIfFailed = skipIfFailed;
        }

        private String downloadSha1() throws IOException {
            String downloadedHash = DownloadMirror.downloadStringMirrored(
                    mDownloadClass, mTargetUrl + ".sha1"
            );
            if(!Tools.isValidString(downloadedHash)) return null;
            // Ensure that we don't have leading/trailing whitespaces before checking hash length
            downloadedHash = downloadedHash.trim();
            // SHA1 is made up of 20 bytes, which means 40 hexadecimal digits, which means 40 chars
            if(downloadedHash.length() != 40) return null;
            return downloadedHash;
        }

        /*
         * Maven repositories usually have the hash of a library near it, like:
         * .../libraryName-1.0.jar
         * .../libraryName.1.0.jar.sha1
         * Since Minecraft libraries are stored in maven repositories, try to use
         * this when downloading libraries without hashes in the json.
         */
        private void tryGetLibrarySha1() throws IOException {
            File sha1CacheDir = new File(Tools.DIR_CACHE + "/sha1hashes");
            File cacheFile = new File(sha1CacheDir.getAbsolutePath() + FileUtils.getFileName(mTargetUrl) + ".sha");

            // Only use cache when its offline. No point in having cache invalidation now!
            if (!isOnline || !LauncherPreferences.PREF_CHECK_LIBRARY_SHA) { // Well not only offlines..this setting speeds up launch times at least!
                try (BufferedReader cacheFileReader = new BufferedReader(new FileReader(cacheFile))) {
                    mTargetSha1 = cacheFileReader.readLine();
                    if (mTargetSha1 != null) {
                        Log.i("MinecraftDownloader", "Reading Hash from cache: " + mTargetSha1 + " from " + cacheFile);
                    } else if (cacheFile.exists()) {
                        Log.i("MinecraftDownloader", "Deleting invalid hash from cache: " + cacheFile);
                        cacheFile.delete();
                    }
                } catch (FileNotFoundException ignored) {
                    mTargetSha1 = null;
                    Log.w("MinecraftDownloader", "Failed to read hash for " + cacheFile);
                }
                return;
            }

            String resultHash = null;
            try {
                resultHash = downloadSha1();
                // The hash is a 40-byte download.
                mInternetUsageCounter.getAndAdd(40);
            } catch (IOException e) {
                Log.i("MinecraftDownloader", "Failed to download hash", e);
                if (cacheFile.exists() && new BufferedReader(new FileReader(cacheFile)).readLine() == null) {
                    Log.i("MinecraftDownloader", "Deleting failed hash download from cache: " + cacheFile);
                    cacheFile.delete();
                }
            }
            if (resultHash != null) {
                Log.i("MinecraftDownloader", "Got hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl));
                mTargetSha1 = resultHash;
                if (!sha1CacheDir.exists()) {
                    sha1CacheDir.mkdir(); // If mkdir() fails, something went wrong with initializing /data/data/. mkdirs() isn't used on purpose
                }
                try (FileWriter writeHash = new FileWriter(cacheFile)) {
                    Log.i("MinecraftDownloader", "Saving hash: " + resultHash + " for " + FileUtils.getFileName(mTargetUrl) + " to " + cacheFile);
                    writeHash.write(resultHash);
                }
            }
        }

        @Override
        public void run() {
            try {
                runCatching();
            }catch (Exception e) {
                mDownloaderThreadException.set(e);
            }
        }

        private void runCatching() throws Exception {
            if(mDownloadClass == DownloadMirror.DOWNLOAD_CLASS_LIBRARIES && !Tools.isValidString(mTargetSha1)) {
                // If we're downloading a library, try to get sha1 since it might be available as a file
                tryGetLibrarySha1();
            }
            if(Tools.isValidString(mTargetSha1)) {
                verifyFileSha1();
            }else {
                mTargetSha1 = null; // Nullify SHA1 as DownloadUtils.ensureSha1 only checks for null,
                                    // not for string validity
                if(mTargetPath.exists()) finishWithoutDownloading();
                else downloadFile();
            }
        }
        
        private void verifyFileSha1() throws Exception {
            if(mTargetPath.isFile() && mTargetPath.canRead() && Tools.compareSHA1(mTargetPath, mTargetSha1)) {
                finishWithoutDownloading();
            } else {
                // Rely on the download function to throw an IOE in case if the file is not
                // writable/not a file/etc...
                downloadFile();
            }
        }
        
        private void downloadFile() throws Exception {
            try {
                DownloadUtils.ensureSha1(mTargetPath, mTargetSha1, () -> {
                    DownloadMirror.downloadFileMirrored(mDownloadClass, mTargetUrl, mTargetPath,
                            getLocalBuffer(), this);
                    return null;
                });
            }catch (Exception e) {
                if(!mSkipIfFailed) throw e;
            }
            mProcessedFileCounter.incrementAndGet();
        }

        private void finishWithoutDownloading() {
            mProcessedFileCounter.incrementAndGet();
            mProcessedSizeCounter.addAndGet(mDownloadSize);
        }

        @Override
        public void updateProgress(int curr, int max) {
            int delta = curr - mLastCurr;
            mProcessedSizeCounter.addAndGet(delta);
            mInternetUsageCounter.addAndGet(delta);
            mLastCurr = curr;
        }
    }
}
