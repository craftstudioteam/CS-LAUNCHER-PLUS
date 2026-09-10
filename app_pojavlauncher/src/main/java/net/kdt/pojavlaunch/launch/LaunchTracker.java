package net.kdt.pojavlaunch.launch;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * LaunchTracker — the launch-phase state machine.
 *
 * Downloads and launches used to be the SAME signal: ProgressKeeper task
 * records. That is why pressing PLAY on an installed profile felt like
 * "another download": verification opens the very same DOWNLOAD_MINECRAFT
 * record that real installs use, so the Download Console popped up for pure
 * launches.
 *
 * ProgressKeeper stays the UNTouched owner of real download progress
 * (the download animation is perfect and must not change). LaunchTracker adds
 * a separate semantic channel for the LAUNCH sequence:
 *
 *   PREPARING → VERIFYING → RUNTIME (only when a JRE must be installed)
 *             → DOWNLOADING (only when real bytes start flowing)
 *             → STARTING → (game process takes over)
 *   failure   → FAILED → IDLE (auto-clears shortly after)
 *
 * Ordering guarantees: all listener notifications happen on the main thread;
 * phases only move forward while a launch is ACTIVE, so stale worker threads
 * can never resurrect an old sequence.
 */
public final class LaunchTracker {

    public enum Phase { IDLE, PREPARING, VERIFYING, RUNTIME, DOWNLOADING, STARTING, FAILED }

    public interface PhaseListener { void onLaunchPhase(@NonNull Phase phase); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<PhaseListener> sListeners = new ArrayList<>();
    private static volatile Phase sPhase = Phase.IDLE;
    private static volatile String sProfileName;
    private static volatile String sVersionId;
    /**
     * Bumped on every {@link #begin}. The STARTING auto-idle timer carries the
     * sequence it was armed for, so a stale timer can never release a NEWER
     * launch sequence early.
     */
    private static int sLaunchSeq;
    /** Grace window for the game handoff before the tracker self-heals to IDLE. */
    private static final long STARTING_AUTO_IDLE_MS = 1500L;

    private LaunchTracker() {}

    /** Begin a fresh launch sequence (called right before MinecraftDownloader.start). */
    public static synchronized void begin(@Nullable String profileName, @Nullable String versionId) {
        sLaunchSeq++;
        sProfileName = profileName;
        sVersionId = versionId;
        setPhase(Phase.PREPARING);
    }

    /** Game files + natives are being verified. */
    public static void verifying() { advance(Phase.VERIFYING); }
    /** A runtime needs installing — console takes over the chrome. */
    public static void runtime() { advance(Phase.RUNTIME); }
    /** Real download bytes started flowing — console takes over the chrome. */
    public static void downloading() { advance(Phase.DOWNLOADING); }
    /** Everything checked out — the game process is about to start. */
    public static void starting() {
        advance(Phase.STARTING);
        // Self-healing handoff (item-1/6 root fix): STARTING is terminal for the
        // SEQUENCE, not for the tracker. Previously nothing ever returned the
        // tracker to IDLE after a successful launch — the phase wedged at
        // STARTING forever, which (a) kept the launch overlay up and swallowing
        // every navigation tap when the launcher process survived the handoff,
        // and (b) kept suppressesDownloadConsole() true so later real downloads
        // could never surface the console. Arm an auto-idle for THIS sequence.
        final int seq;
        synchronized (LaunchTracker.class) { seq = sLaunchSeq; }
        MAIN.postDelayed(() -> {
            synchronized (LaunchTracker.class) {
                if (seq == sLaunchSeq && sPhase == Phase.STARTING) setPhase(Phase.IDLE);
            }
        }, STARTING_AUTO_IDLE_MS);
    }

    /** Launch aborted (error or user cancel). Auto-clears to IDLE shortly. */
    public static void fail() {
        setPhase(Phase.FAILED);
        MAIN.postDelayed(() -> {
            synchronized (LaunchTracker.class) {
                if (sPhase == Phase.FAILED) setPhase(Phase.IDLE);
            }
        }, 600);
    }

    /** Hard reset (e.g. activity destroyed mid-launch). */
    public static void idle() { setPhase(Phase.IDLE); }

    @NonNull public static Phase getPhase() { return sPhase; }
    @Nullable public static String getProfileName() { return sProfileName; }
    @Nullable public static String getVersionId() { return sVersionId; }

    /**
     * While the launch sequence owns the chrome, the Download Console must
     * stay hidden. Only phases where REAL downloads happen (RUNTIME /
     * DOWNLOADING / IDLE) let the console surface — verification never does.
     */
    public static boolean suppressesDownloadConsole() {
        Phase p = sPhase;
        return p == Phase.PREPARING || p == Phase.VERIFYING || p == Phase.STARTING;
    }

    private static void advance(@NonNull Phase next) {
        synchronized (LaunchTracker.class) {
            if (!isActiveLocked()) return; // never resurrect an idle/dead sequence
            // STARTING is terminal; FAILED is terminal.
            if (sPhase == Phase.STARTING) return;
            if (sPhase == next) return;
            setPhase(next);
        }
    }

    private static boolean isActiveLocked() {
        return sPhase == Phase.PREPARING || sPhase == Phase.VERIFYING
                || sPhase == Phase.RUNTIME || sPhase == Phase.DOWNLOADING;
    }

    /**
     * Real launch phases are mirrored into the notification system — no faked
     * states, every card reflects an actual transition of the launch pipeline.
     */
    private static void notifyPhase(@NonNull Phase phase) {
        String profile = sProfileName == null ? "Minecraft" : sProfileName;
        switch (phase) {
            case PREPARING:
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Preparing launch", profile); break;
            case VERIFYING:
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Verifying files", profile); break;
            case RUNTIME:
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Loading runtime", profile); break;
            case DOWNLOADING:
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Downloading game files", profile); break;
            case STARTING:
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Starting Minecraft", profile); break;
            case FAILED:
                net.kdt.pojavlaunch.notifications.CsNotifier.error("Launch failed", profile,
                        "View log", () -> {}); break;
            default: break;
        }
    }

    private static void setPhase(@NonNull Phase phase) {
        final List<PhaseListener> snapshot;
        final Phase previous;
        synchronized (LaunchTracker.class) {
            if (sPhase == phase) return;
            previous = sPhase;
            sPhase = phase;
            snapshot = new ArrayList<>(sListeners);
        }
        MAIN.post(() -> {
            for (int i = 0; i < snapshot.size(); i++) snapshot.get(i).onLaunchPhase(phase);
            // STARTING → IDLE (not FAILED) means the game actually came up.
            if (phase == Phase.IDLE && previous == Phase.STARTING) {
                net.kdt.pojavlaunch.notifications.CsNotifier.success(
                        "Minecraft started", sProfileName == null ? "Have fun!" : sProfileName);
            } else if (phase != Phase.IDLE) {
                notifyPhase(phase);
            }
        });
    }

    public static void addListener(@NonNull PhaseListener listener) {
        synchronized (LaunchTracker.class) {
            if (!sListeners.contains(listener)) sListeners.add(listener);
        }
    }

    public static void removeListener(@NonNull PhaseListener listener) {
        synchronized (LaunchTracker.class) {
            sListeners.remove(listener);
        }
    }
}
