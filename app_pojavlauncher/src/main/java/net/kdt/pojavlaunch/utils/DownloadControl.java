package net.kdt.pojavlaunch.utils;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User-facing pause/stop control channel for active downloads.
 *
 * The download deck (ProgressLayout) requests pause/cancel per progress
 * record; the copy loop inside DownloadUtils.downloadFileMonitored()
 * consults checkpoint() between chunk reads, so pausing freezes the
 * transfer in place and cancelling aborts it cleanly.
 */
public final class DownloadControl {

    /** Thrown out of the copy loop when the user stops the download. */
    public static class DownloadCancelledException extends IOException {
        public DownloadCancelledException(String key) {
            super("Download stopped by user (" + key + ")");
        }
    }

    /**
     * Implemented by downloader feedback objects that are not
     * DownloaderProgressWrapper but still target a ProgressKeeper record
     * (e.g. MinecraftDownloader / ModDownloader per-file tasks), so the
     * monitored copy loop can resolve their pause/stop control key.
     */
    public interface KeyedFeedback {
        String getControlKey();
    }

    private static final class State {
        volatile boolean pauseRequested;
        volatile boolean cancelRequested;
    }

    private static final ConcurrentHashMap<String, State> sStates = new ConcurrentHashMap<>();
    /** Records cancelled very recently, so UI layers can soften the resulting error. */
    private static final Set<String> sCancelledRecords = ConcurrentHashMap.newKeySet();

    private DownloadControl() {}

    private static State stateFor(String key) {
        State s = sStates.get(key);
        if (s == null) {
            s = new State();
            State prev = sStates.putIfAbsent(key, s);
            if (prev != null) s = prev;
        }
        return s;
    }

    /** Fresh download attempt for this record: clears any old pause/cancel flags & notes. */
    public static void reset(String key) {
        if (key == null) return;
        State s = stateFor(key);
        s.pauseRequested = false;
        s.cancelRequested = false;
        sCancelledRecords.remove(key);
    }

    public static void requestPause(String key, boolean pause) {
        stateFor(key).pauseRequested = pause;
    }

    public static boolean isPaused(String key) {
        State s = sStates.get(key);
        return s != null && s.pauseRequested;
    }

    public static void requestCancel(String key) {
        stateFor(key).cancelRequested = true;
        // also release any paused waiters so the loop can exit immediately
        stateFor(key).pauseRequested = false;
    }

    /** Mark that this record ended because the user stopped it. */
    public static void noteCancelled(String key) {
        sCancelledRecords.add(key);
        sStates.remove(key);
    }

    /** Consumes the "recently cancelled" marker for a record. */
    public static boolean consumeCancelledNote(String key) {
        return sCancelledRecords.remove(key);
    }

    /** True when the throwable chain contains a user-stop event. */
    public static boolean isCancellation(Throwable t) {
        while (t != null) {
            if (t instanceof DownloadCancelledException) return true;
            t = t.getCause();
        }
        return false;
    }

    /**
     * Called from the monitored copy loop between chunk reads.
     * @throws DownloadCancelledException when the user stopped this transfer
     */
    public static void checkpoint(String key) throws DownloadCancelledException {
        if (key == null) return;
        State s = sStates.get(key);
        if (s == null) {
            // State is removed by noteCancelled() — but a parallel pool thread of the
            // same record must still stop instead of continuing the transfer.
            if (sCancelledRecords.contains(key)) {
                throw new DownloadCancelledException(key);
            }
            return;
        }
        if (s.cancelRequested) {
            noteCancelled(key);
            throw new DownloadCancelledException(key);
        }
        // Paused: park this thread until resumed or cancelled. The loop never
        // issues a read() while parked, so no socket timeout can fire.
        while (s.pauseRequested) {
            try {
                Thread.sleep(140);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                noteCancelled(key);
                throw new DownloadCancelledException(key);
            }
            if (s.cancelRequested) {
                noteCancelled(key);
                throw new DownloadCancelledException(key);
            }
        }
    }
}
