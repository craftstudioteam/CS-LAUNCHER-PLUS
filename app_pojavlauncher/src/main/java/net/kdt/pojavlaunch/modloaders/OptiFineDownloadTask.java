package net.kdt.pojavlaunch.modloaders;

import android.util.Log;

import android.app.Activity;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OptiFineDownloadTask implements Runnable, Tools.DownloaderFeedback, AsyncMinecraftDownloader.DoneListener {
    private static final Pattern sMcVersionPattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private final OptiFineUtils.OptiFineVersion mOptiFineVersion;
    private final File mDestinationFile;
    private final ModloaderDownloadListener mListener;
    private final Object mMinecraftDownloadLock = new Object();
    private Throwable mDownloaderThrowable;
    private long mExpectedBytes = -1;            // total the server promised
    private boolean mListenerNotified = false;   // exactly-once failure reporting
    private final Activity activity;
    private net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper mProgressWrapper;

    /**
     * One file per version, instead of one shared "optifine-installer.jar":
     * picking 1.20.1 then 1.16.5 used to overwrite the first download, and
     * re-picking the same version re-downloaded ~8 MB from scratch.
     */
    public OptiFineDownloadTask(OptiFineUtils.OptiFineVersion mOptiFineVersion, ModloaderDownloadListener mListener, Activity activity) {
        this.mOptiFineVersion = mOptiFineVersion;
        this.mDestinationFile = new File(Tools.DIR_CACHE,
                "optifine/" + localJarName(mOptiFineVersion) + ".jar");
        this.mListener = mListener;
        this.activity = activity;
    }

    /** The file OptiFine itself serves, e.g. OptiFine_1.21.5_HD_U_I5.jar. */
    private static String remoteFileName(OptiFineUtils.OptiFineVersion v) {
        String url = v != null ? v.downloadUrl : null;
        if (url != null) {
            int q = url.indexOf('?');
            String f = q >= 0 ? url.substring(q + 1) : url;
            int s = f.lastIndexOf('/');
            if (s >= 0) f = f.substring(s + 1);
            if (f.startsWith("f=")) f = f.substring(2);
            if (f.endsWith(".jar")) return f;
        }
        return null;
    }

    private static String localJarName(OptiFineUtils.OptiFineVersion v) {
        String n = remoteFileName(v);
        if (n == null) {
            n = v != null && v.versionName != null ? v.versionName : "optifine-unknown";
        }
        if (n.endsWith(".jar")) n = n.substring(0, n.length() - 4);
        return n.replaceAll("[^A-Za-z0-9._+-]", "_");
    }

    @Override
    public void run() {
        mProgressWrapper = new net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper(R.string.of_dl_progress, ProgressLayout.INSTALL_MODPACK);
        mProgressWrapper.extraString = mOptiFineVersion.versionName;
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.of_dl_progress, mOptiFineVersion.versionName);
        try {
            if(runCatching()) mListener.onDownloadFinished(mDestinationFile);
            else notifyUnavailableOnce();   // never fail silently
        }catch (IOException e) {
            mListener.onDownloadError(e);
        }
        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
    }

    public boolean runCatching() throws IOException {
        // Two tasks racing for the same jar used to write the same file twice at
        // once; the loser's rename landed on the winner's half-written bytes.
        String lockKey = mDestinationFile.getAbsolutePath();
        if (!sInFlight.add(lockKey)) {
            notifyUnavailableOnce();
            return false;
        }
        try {
            return runCatchingLocked();
        } finally {
            sInFlight.remove(lockKey);
        }
    }

    private boolean runCatchingLocked() throws IOException {
        String minecraftVersion = determineMinecraftVersion();
        if(minecraftVersion == null) return false;
        // Phase 11 (item 1): the vanilla version goes FIRST. The installer needs
        // <mc>/<mc>.json + jar to exist, and the downloadx token scraped from the
        // interstitial only lives ~7 minutes — scraping it before a multi-minute
        // vanilla download (the old order) is how the token expired and OptiFine
        // answered the jar request with a 19-byte "Request not found." page.
        if(!downloadMinecraft(minecraftVersion)) {
            if(mDownloaderThrowable instanceof Exception) {
                mListener.onDownloadError((Exception) mDownloaderThrowable);
            }else {
                Exception exception = new Exception(mDownloaderThrowable);
                mListener.onDownloadError(exception);
            }
            return false;
        }
        if (isUsableInstaller(mDestinationFile, -1)) {
            // Already on disk and really a jar: no network for the installer.
            // The promised size is unknown for a cached copy, so only the magic
            // bytes and the floor apply here — a truncated old download still
            // fails the check on the next real run.
            return true;
        }
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.of_dl_progress, mOptiFineVersion.versionName);
        String downloadUrl = scrapeDownloadsPage();
        if(downloadUrl == null) return false;
        // 128 KB: this is an ~8 MB jar over a mobile radio. The old 8 KB buffer
        // made the copy loop issue ~1000 read/write syscalls per megabyte.
        DownloadUtils.downloadFileMonitored(downloadUrl, mDestinationFile,
                new byte[128 * 1024], this);
        if (!isUsableInstaller(mDestinationFile, mExpectedBytes)) {
            // OptiFine answers a bad/expired session token with a tiny
            // "Invalid request." / "Request not found." page and a 200. The token
            // is short-lived, so scrape the interstitial ONCE more (fresh token,
            // same cookie jar) before giving up. The retry is attempted even when
            // the URL text is identical: a same-token re-request can succeed right
            // after a transient edge hiccup, and the re-scrape refreshes the session.
            long badLen = mDestinationFile.length();
            //noinspection ResultOfMethodCallIgnored
            mDestinationFile.delete();
            mExpectedBytes = -1;
            String retryUrl = null;
            try { retryUrl = OFDownloadPageScraper.run(mOptiFineVersion.downloadUrl); } catch (IOException ignored) {}
            if (retryUrl == null) retryUrl = downloadUrl;
            try {
                DownloadUtils.downloadFileMonitored(retryUrl, mDestinationFile, new byte[128 * 1024], this);
            } catch (IOException retryFail) {
                Log.w("OptiFineDownloadTask", "retry download failed", retryFail);
            }
            if (!isUsableInstaller(mDestinationFile, mExpectedBytes)) {
                //noinspection ResultOfMethodCallIgnored
                mDestinationFile.delete();
                throw new IOException("OptiFine did not serve the jar "
                        + "(got " + badLen + " bytes). Try again in a moment.");
            }
        }
        return true;
    }

    private static final java.util.Set<String> sInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * A real jar starts with the ZIP magic and arrives at the size the server
     * promised. The size floor matters: a socket that dies at 5 MB of 8 MB leaves
     * a file that is still a valid-looking truncated zip, and handing that to the
     * installer agent is a much worse failure than saying "download again".
     */
    private static boolean isUsableInstaller(File f, long promisedBytes) {
        if (f == null || !f.isFile() || f.length() < 64 * 1024) return false;
        if (promisedBytes > 0 && f.length() + Math.max(4096, promisedBytes / 100) < promisedBytes) {
            return false;
        }
        byte[] head = new byte[4];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            if (in.read(head) != 4) return false;
        } catch (IOException e) {
            return false;
        }
        if (head[0] == 'P' && head[1] == 'K') return true;   // zip/jar
        // An uncompressed ODA/zip variant is vanishingly unlikely here, but the
        // size floor above already rejects the error pages.
        return head[0] == 0x50 && head[1] == 0x4B;
    }

    public String scrapeDownloadsPage() throws IOException{
        // The list link (adloadx) is only an interstitial: the file itself is
        // served from the tokenised downloadx link inside it, and asking
        // downloadx without that token returns "Invalid request." — so this hop
        // is mandatory, one small page, and it is fetched with the app identity.
        String scrapeResult = OFDownloadPageScraper.run(mOptiFineVersion.downloadUrl);
        if(scrapeResult == null) notifyUnavailableOnce();
        return scrapeResult;
    }

    /** Reports "no data" exactly once so the UI can offer Retry. */
    private void notifyUnavailableOnce() {
        if (mListenerNotified) return;
        mListenerNotified = true;
        mListener.onDataNotAvailable();
    }

    public String determineMinecraftVersion() {
        Matcher matcher = sMcVersionPattern.matcher(mOptiFineVersion.minecraftVersion);
        if(matcher.find()) {
            StringBuilder mcVersionBuilder = new StringBuilder();
            mcVersionBuilder.append(matcher.group(1));
            mcVersionBuilder.append('.');
            mcVersionBuilder.append(matcher.group(2));
            String thirdGroup = matcher.group(3);
            if(thirdGroup != null && !thirdGroup.isEmpty() && !"0".equals(thirdGroup)) {
                mcVersionBuilder.append('.');
                mcVersionBuilder.append(thirdGroup);
            }
            return mcVersionBuilder.toString();
        }else{
            notifyUnavailableOnce();
            return null;
        }
    }

    public boolean downloadMinecraft(String minecraftVersion) {
        // the string is always normalized
        JMinecraftVersionList.Version minecraftJsonVersion = AsyncMinecraftDownloader.getListedVersion(minecraftVersion);
        if(minecraftJsonVersion == null) {
            // Metadata not loaded/available — surface it instead of hanging silently.
            notifyUnavailableOnce();
            return false;
        }
        try {
            synchronized (mMinecraftDownloadLock) {
                new MinecraftDownloader().start(activity, minecraftJsonVersion, minecraftVersion, this);
                mMinecraftDownloadLock.wait();
            }
        }catch (InterruptedException e) {
            e.printStackTrace();
        }
        return mDownloaderThrowable == null;
    }

    @Override
    public void updateProgress(int curr, int max) {
        if (max > 0) mExpectedBytes = max;   // remembered for the post-download check
        if (mProgressWrapper != null) mProgressWrapper.updateProgress(curr, max);
    }

    @Override
    public void onDownloadDone() {
        synchronized (mMinecraftDownloadLock) {
            mDownloaderThrowable = null;
            mMinecraftDownloadLock.notifyAll();
        }
    }

    @Override
    public void onDownloadFailed(Throwable throwable) {
        synchronized (mMinecraftDownloadLock) {
            mDownloaderThrowable = throwable;
            mMinecraftDownloadLock.notifyAll();
        }
    }
}
