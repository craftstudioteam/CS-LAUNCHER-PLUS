package net.kdt.pojavlaunch.modloaders;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Builds a modded instance from a JAR the user already has — an OptiFine-style
 * installer, a LiteLoader/OptiFine build for another Minecraft version, any
 * "install me" .jar. This is what replaced the OptiFine entry in the create
 * flow: no scraping of optifine.net, no curated version list, just "pick the
 * jar, pick the Minecraft version, we take care of the rest".
 *
 * Sequence (mirrors OptiFineDownloadTask so the installer hand-off downstream
 * is unchanged):
 *   1. stream the picked content:// jar into the cache
 *   2. download the Minecraft version files (they must exist before the
 *      installer runs, or it aborts with "version not found")
 *   3. hand the local jar back to the caller, which launches the installer
 *      agent exactly like Forge / NeoForge / OptiFine do
 */
public class CustomJarInstallTask implements Runnable,
        Tools.DownloaderFeedback, AsyncMinecraftDownloader.DoneListener {
    @NonNull
    private final Context mContext;
    @Nullable
    private final android.app.Activity mActivity;
    @NonNull
    private final Uri mJarUri;
    @Nullable
    private final String mMinecraftVersion;
    @NonNull
    private final ModloaderDownloadListener mListener;
    private final Object mMinecraftDownloadLock = new Object();
    private Throwable mDownloaderThrowable;
    private boolean mListenerNotified;
    private net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper mProgressWrapper;

    public CustomJarInstallTask(@NonNull Context context, @NonNull Uri jarUri,
                                @Nullable String minecraftVersion,
                                @NonNull ModloaderDownloadListener listener) {
        this(context, context instanceof android.app.Activity ? (android.app.Activity) context : null,
                jarUri, minecraftVersion, listener);
    }

    public CustomJarInstallTask(@NonNull Context context, @Nullable android.app.Activity activity,
                                @NonNull Uri jarUri, @Nullable String minecraftVersion,
                                @NonNull ModloaderDownloadListener listener) {
        mContext = context.getApplicationContext();
        mActivity = activity;
        mJarUri = jarUri;
        mMinecraftVersion = minecraftVersion;
        mListener = listener;
    }

    @Override
    public void run() {
        mProgressWrapper = new net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper(
                R.string.of_dl_progress, ProgressLayout.INSTALL_MODPACK);
        mProgressWrapper.extraString = "custom jar";
        ProgressKeeper.submitProgress(ProgressLayout.INSTALL_MODPACK, 0,
                R.string.of_dl_progress, "custom jar");
        try {
            File local = copyJarToLocalCache();
            if (local == null) {
                notifyUnavailableOnce();
                return;
            }
            if (mMinecraftVersion != null && !mMinecraftVersion.isEmpty()
                    && !downloadMinecraft(mMinecraftVersion)) {
                if (mDownloaderThrowable instanceof Exception) {
                    mListener.onDownloadError((Exception) mDownloaderThrowable);
                } else {
                    mListener.onDownloadError(new Exception(mDownloaderThrowable));
                }
                return;
            }
            mListener.onDownloadFinished(local);
        } catch (Exception e) {
            mListener.onDownloadError(e);
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    /**
     * The installer agent needs a real path (it runs as a JVM with -jar), so the
     * SAF document is copied once into the cache. Streams only — a 60 MB jar
     * must not be held in memory on a low-end phone.
     */
    @Nullable
    private File copyJarToLocalCache() throws IOException {
        String name = displayName();
        File dest = new File(Tools.DIR_CACHE, "custom-jars/" + name);
        long sourceLen = sourceLength();
        // Same file, already on disk: do not stream 60 MB again for nothing.
        if (sourceLen > 0 && isJar(dest) && dest.length() == sourceLen) return dest;
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return null;
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        long total = 0;
        try (InputStream in = mContext.getContentResolver().openInputStream(mJarUri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("The picked document could not be opened");
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                // Real progress: the length is known for a file:// or content://
                // document, and unknown (-1) is reported as an indeterminate run.
                updateProgress((int) Math.min(total, Integer.MAX_VALUE),
                        (int) Math.min(Math.max(sourceLen, 0), Integer.MAX_VALUE));
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();               // never leave a truncated jar behind
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Could not read the picked jar", e);
        }
        if (!isJar(tmp)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("That file is not a .jar archive — an installer jar "
                    + "starts with a ZIP header, this one does not");
        }
        //noinspection ResultOfMethodCallIgnored
        dest.delete();
        if (!tmp.renameTo(dest)) {
            try {
                org.apache.commons.io.FileUtils.copyFile(tmp, dest);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } catch (Exception e) {
                throw new IOException("Could not store the jar in the launcher cache", e);
            }
        }
        return dest;
    }

    /** A jar is a zip: the two magic bytes say more than the file extension ever could. */
    private static boolean isJar(File f) {
        if (f == null || !f.isFile() || f.length() < 1024) return false;
        byte[] head = new byte[4];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            if (in.read(head) != 4) return false;
        } catch (IOException e) {
            return false;
        }
        return head[0] == 'P' && head[1] == 'K';
    }

    /** Length of the picked document, or -1 when the provider will not say. */
    private long sourceLength() {
        try (android.os.ParcelFileDescriptor pfd =
                     mContext.getContentResolver().openFileDescriptor(mJarUri, "r")) {
            if (pfd != null) {
                long len = pfd.getStatSize();
                if (len > 0) return len;
            }
        } catch (Throwable ignored) {}
        String path = mJarUri.getPath();
        if (path != null && !path.isEmpty()) {
            java.io.File f = new java.io.File(path);
            if (f.isFile()) return f.length();
        }
        return -1;
    }

    /** The name the user picked, sanitised — so two different jars get two files. */
    private String displayName() {
        String name = null;
        try {
            android.content.ContentResolver cr = mContext.getContentResolver();
            android.database.Cursor c = cr.query(mJarUri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {}
        if (name == null || name.isEmpty()) {
            name = mJarUri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) name = "custom-installer.jar";
        name = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        if (!name.endsWith(".jar")) name = name + ".jar";
        return name.length() > 96 ? name.substring(name.length() - 96) : name;
    }

    private boolean downloadMinecraft(String minecraftVersion) {
        JMinecraftVersionList.Version minecraftJsonVersion =
                AsyncMinecraftDownloader.getListedVersion(minecraftVersion);
        if (minecraftJsonVersion == null) {
            notifyUnavailableOnce();
            return false;
        }
        try {
            synchronized (mMinecraftDownloadLock) {
                // The Activity decides local-profile vs online semantics, so it
                // is passed straight through; null simply means "assume local".
                new MinecraftDownloader().start(mActivity, minecraftJsonVersion, minecraftVersion, this);
                mMinecraftDownloadLock.wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return mDownloaderThrowable == null;
    }

    /** Reports "no data" exactly once so the UI can offer Retry, never twice. */
    private void notifyUnavailableOnce() {
        if (mListenerNotified) return;
        mListenerNotified = true;
        mListener.onDataNotAvailable();
    }

    @Override
    public void updateProgress(int curr, int max) {
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
