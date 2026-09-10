package net.kdt.pojavlaunch.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.utils.DownloadControl;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ProgressService — the download notification center of CS Launcher.
 *
 * Keeps its original purpose (foreground process anchor while tasks run)
 * but now also renders one live grouped notification PER download record:
 * name, real-time percentage, size detail, speed, ETA, plus Pause / Resume /
 * Stop / Open-launcher actions wired straight into {@link DownloadControl},
 * which the monitored copy loops already honor. When a record ends, the child
 * notification flips to a short-lived "Download complete" card; tapping it
 * (or any child) opens the launcher.
 *
 * The legacy aggregate "tasks in progress" card stays as the GROUP SUMMARY
 * so existing behavior (including its kill action) is preserved.
 */
public class ProgressService extends Service implements TaskCountListener {

    private static final String TAG = "ProgressService";
    private static final String GROUP_DOWNLOADS = "cs_downloads";
    private static final int CHILD_ID_BASE = 1000;
    private static final int COMPLETE_ID_BASE = 9000;
    private static final int FAILED_ID_BASE = 7000;
    /** Platinum accent used for icons, chronometers and the expand affordance. */
    private static final int ACCENT_COLOR = 0xFFC9CDD8;
    private static final long MIN_NOTIFY_INTERVAL_MS = 600;
    private static final long COMPLETE_TIMEOUT_MS = 5000;
    /** Heartbeat: how often the notification re-reads the truth on its own. */
    private static final long HEARTBEAT_INTERVAL_MS = 1200;
    /** How long a service start waits for the first progress record to appear. */
    private static final long QUIET_START_GRACE_MS = 700L;

    public static final String ACTION_TOGGLE_PAUSE = "net.kdt.pojavlaunch.NOTIF_TOGGLE_PAUSE";
    public static final String ACTION_STOP = "net.kdt.pojavlaunch.NOTIF_STOP";
    public static final String EXTRA_RECORD = "record";

    /** Per-record progress used to keep the group summary honest. */
    private final Map<String, Integer> mRecordProgress = new HashMap<>();
    private final Handler mHeartbeat = new Handler(Looper.getMainLooper());

    /**
     * Download threads report through ProgressKeeper, but once the app is in the
     * background those updates can be coalesced, delayed or lost (the OEM
     * task-killer is harsher than Doze). The heartbeat is the answer to that:
     * every tick it pulls the last reported state straight out of ProgressKeeper
     * and repaints, so the percentage in the shade keeps moving even when
     * nothing in the UI thread is listening any more.
     */
    private final Runnable mBeat = new Runnable() {
        @Override public void run() {
            if (ProgressKeeper.getTaskCount() < 1) {
                stopHeartbeat();
                return;
            }
            for (String record : new ArrayList<>(mRecordListeners.keySet())) {
                RecordListener rl = mRecordListeners.get(record);
                if (rl == null) continue;
                rl.pullLastKnownState();
                refreshRecordNotification(record, false);
            }
            updateGroupSummary();
            mHeartbeat.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private void startHeartbeat() {
        mHeartbeat.removeCallbacks(mBeat);
        mHeartbeat.postDelayed(mBeat, HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        mHeartbeat.removeCallbacks(mBeat);
    }

    /**
     * Report a download that ended badly. Callers own the failure (they caught
     * the exception), so this never invents an outcome — it only publishes the
     * one that really happened.
     */
    public static void reportFailure(Context context, String record, String title, String message) {
        publishOutcome(context, record, title, message, true);
    }

    /** Report a download the user (or the system) stopped on purpose. */
    public static void reportCancelled(Context context, String record, String title) {
        publishOutcome(context, record, title, null, false);
    }

    private static void publishOutcome(Context context, String record, String title,
                                       String message, boolean failed) {
        Context app = context != null ? context.getApplicationContext() : null;
        if (app == null) return;
        try {
            Tools.buildNotificationChannel(app);
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;

            String heading = app.getString(failed
                    ? R.string.cs_notif_failed_title : R.string.cs_notif_cancelled_title);
            String body = failed
                    ? app.getString(R.string.cs_notif_failed_text,
                            title == null ? "Download" : title,
                            message == null ? "Unknown error" : message)
                    : app.getString(R.string.cs_notif_cancelled_text,
                            title == null ? "Download" : title);

            NotificationCompat.Builder b = new NotificationCompat.Builder(app,
                    app.getString(R.string.notif_channel_id))
                    .setSmallIcon(R.drawable.notif_icon)
                    .setContentTitle(heading)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .setBigContentTitle(heading)
                            .bigText(body))
                    .setProgress(0, 0, false)
                    .setAutoCancel(true)
                    .setTimeoutAfter(failed ? 15000L : 5000L)
                    .setGroup(GROUP_DOWNLOADS)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setColor(ACCENT_COLOR)
                    .setOnlyAlertOnce(true);

            Intent open = new Intent(app, net.kdt.pojavlaunch.LauncherActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("cs_open_downloads", true);
            b.setContentIntent(PendingIntent.getActivity(app, failedId(record), open,
                    Build.VERSION.SDK_INT >= 23
                            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT));

            NotificationManagerCompat.from(app).notify(failedId(record), b.build());
        } catch (Throwable t) {
            Log.w(TAG, "Could not publish download outcome", t);
        }
    }

    private static int failedId(String record) {
        return FAILED_ID_BASE + ((record == null ? "" : record).hashCode() & 0x3FFF);
    }

    private NotificationManagerCompat mNotificationManager;
    private NotificationCompat.Builder mNotificationBuilder;

    private final Map<String, RecordListener> mRecordListeners = new HashMap<>();

    /** Simple wrapper to start the service */
    public static void startService(Context context){
        Intent intent = new Intent(context, ProgressService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        mNotificationManager = NotificationManagerCompat.from(getApplicationContext());
        Intent killIntent = new Intent(getApplicationContext(), ProgressService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_PROGRESS_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        mNotificationBuilder = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setContentTitle(getString(R.string.lazy_service_default_title))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setGroup(GROUP_DOWNLOADS)
                .setGroupSummary(true)
                .setContentIntent(openLauncherIntent(null))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setColor(ACCENT_COLOR)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setNotificationSilent();
        if (Build.VERSION.SDK_INT >= 31) {
            mNotificationBuilder.setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
    }

    @SuppressLint("StringFormatInvalid")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null) {
            if(intent.getBooleanExtra("kill", false)) {
                stopSelf(); // otherwise Android tries to restart the service since it "crashed"
                Process.killProcess(Process.myPid());
                return START_NOT_STICKY;
            }
            if (ACTION_TOGGLE_PAUSE.equals(intent.getAction())) {
                String record = intent.getStringExtra(EXTRA_RECORD);
                if (record != null) {
                    DownloadControl.requestPause(record, !DownloadControl.isPaused(record));
                    refreshRecordNotification(record, true);
                }
            } else if (ACTION_STOP.equals(intent.getAction())) {
                String record = intent.getStringExtra(EXTRA_RECORD);
                if (record != null) {
                    // clear pause so the monitored loop can observe the cancel immediately
                    DownloadControl.requestPause(record, false);
                    DownloadControl.requestCancel(record);
                    cancelChild(record);
                }
            }
        }
        Log.d(TAG, "Started!");
        mNotificationBuilder.setContentText(getString(R.string.progresslayout_tasks_in_progress, ProgressKeeper.getTaskCount()));
        Notification notification = mNotificationBuilder.build();
        // startForeground FIRST, unconditionally. It used to sit behind a task-count
        // test, which is wrong twice over: the service is started with
        // startForegroundService(), so Android expects startForeground() within
        // seconds whether or not a record has landed yet (skip it and the process is
        // killed with "Context.startForegroundService() did not then call
        // Service.startForeground()); and the count is often still 0 here because the
        // submitProgress that raises it is on a worker thread that has not run yet.
        boolean promoted = promoteToForeground(notification);
        if (ProgressKeeper.getTaskCount() < 1) {
            // Nothing to report *yet* — give the first record a moment, then retire.
            // stopping immediately here was how a fast tap could kill the service
            // between "download started" and "progress submitted".
            mHeartbeat.postDelayed(mQuietStartFailsafe, QUIET_START_GRACE_MS);
        }
        ProgressKeeper.addTaskCountListener(this, false);
        resyncRecords();
        if (promoted && ProgressKeeper.getTaskCount() > 0) startHeartbeat();

        return START_NOT_STICKY;
    }

    /**
     * Put the service in the foreground, and stay honest about whether it worked.
     *
     * FOREGROUND_SERVICE_TYPE_MANIFEST is what the manifest declares (dataSync), but
     * on Android 14+ a data-sync FGS can be refused — e.g. the app was started from
     * a context the system does not consider eligible — and a throw here would kill
     * the download along with the notification. Falling back to an untyped
     * startForeground() keeps the notification (which is what the user asked for) and
     * lets the transfer finish.
     */
    private boolean promoteToForeground(@NonNull Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
            } else {
                startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Foreground promotion refused, retrying untyped", t);
            try {
                startForeground(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, notification);
                return true;
            } catch (Throwable t2) {
                Log.w(TAG, "Foreground promotion failed outright", t2);
                return false;
            }
        }
    }

    /** Retire cleanly when a start produced no work. */
    private final Runnable mQuietStartFailsafe = new Runnable() {
        @Override public void run() {
            if (ProgressKeeper.getTaskCount() > 0) {
                startHeartbeat();
                return;
            }
            stopHeartbeat();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
            stopSelf();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiping the launcher away used to take the download notification with
        // it, because the service was tied to the task. While bytes are still
        // moving we stay foreground — the last finished record stops us itself.
        if (ProgressKeeper.getTaskCount() > 0) {
            startHeartbeat();
            return;
        }
        stopHeartbeat();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopHeartbeat();
        mHeartbeat.removeCallbacks(mQuietStartFailsafe);
        ProgressKeeper.removeTaskCountListener(this);
        for (Map.Entry<String, RecordListener> e : mRecordListeners.entrySet()) {
            ProgressKeeper.removeListener(e.getKey(), e.getValue());
            mNotificationManager.cancel(childId(e.getKey()));
        }
        mRecordListeners.clear();
    }

    @Override
    public void onUpdateTaskCount(int taskCount) {
        Tools.MAIN_HANDLER.post(()->{
            if(taskCount > 0) {
                mNotificationBuilder.setContentText(getString(R.string.progresslayout_tasks_in_progress, taskCount));
                notifySafely(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, mNotificationBuilder.build());
                resyncRecords();
            }else{
                stopSelf();
            }
        });
    }

    // ------------------------------------------------------------ record wiring

    private void resyncRecords() {
        Set<String> active = ProgressKeeper.getActiveRecords();
        // attach listeners for new records
        for (String record : active) {
            if (!mRecordListeners.containsKey(record)) {
                RecordListener listener = new RecordListener(record);
                mRecordListeners.put(record, listener);
                ProgressKeeper.addListener(record, listener);
                // First paint: whatever the record last reported (may be a quiet no-op start)
                refreshRecordNotification(record, true);
            }
        }
        // records that vanished without an explicit END are treated as finished
        for (String tracked : new ArrayList<>(mRecordListeners.keySet())) {
            if (!active.contains(tracked)) completeRecord(tracked);
        }
    }

    private final class RecordListener implements ProgressListener {
        final String record;
        long lastNotifyMs;
        long startedAtMs;
        int lastProgress;
        int lastResid = -1;
        Object[] lastVa;

        RecordListener(String record) { this.record = record; }

        @Override public void onProgressStarted() {
            Tools.MAIN_HANDLER.post(() -> refreshRecordNotification(record, true));
        }

        @Override public void onProgressUpdated(int progress, int resid, Object... va) {
            lastProgress = progress;
            lastResid = resid;
            lastVa = va;
            Tools.MAIN_HANDLER.post(() -> refreshRecordNotification(record, false));
        }

        @Override public void onProgressEnded() {
            Tools.MAIN_HANDLER.post(() -> completeRecord(record));
        }

        long lastPulledBytes = -1;

        /** Re-read the record's last submitted state (what the heartbeat does). */
        void pullLastKnownState() {
            int p = ProgressKeeper.peekProgress(record);
            if (p < 0) return;                       // record finished between ticks
            int r = ProgressKeeper.peekResid(record);
            Object[] va = ProgressKeeper.peekVarArgs(record);
            boolean bytesMoved = ProgressKeeper.peekHasLiveBytePayload(record)
                    && firstNumberChanged(va, lastVa);
            if (p == lastProgress && r == lastResid && !bytesMoved) return;
            lastProgress = p;
            lastResid = r;
            lastVa = va;
        }


    }

    /** Render (or throttle-render) the child notification for one record. */
    private void refreshRecordNotification(String record, boolean force) {
        RecordListener rl = mRecordListeners.get(record);
        if (rl == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - rl.lastNotifyMs < MIN_NOTIFY_INTERVAL_MS) return;
        rl.lastNotifyMs = now;
        int shown = Math.max(0, Math.min(100, rl.lastProgress));
        // Repaint when the percent moved, and also when only the byte counters
        // moved — those are the numbers that prove the download is alive.
        boolean changed = force || shown != mRecordProgress.get(record)
                || ProgressKeeper.peekHasLiveBytePayload(record);
        mRecordProgress.put(record, shown);
        notifySafely(childId(record), buildChild(record, rl));
        // A throttled tick still has to repaint the group line: that is the number
        // the user actually reads without expanding anything. But do not re-notify
        // the summary on every heartbeat when nothing moved.
        if (changed) updateGroupSummary();
    }

    /** Group summary mirrors the real aggregate of every live download. */
    private void updateGroupSummary() {
        int n = mRecordProgress.size();
        if (n <= 0) {
            mNotificationBuilder.setContentText(
                    getString(R.string.progresslayout_tasks_in_progress, ProgressKeeper.getTaskCount()));
        } else {
            int sum = 0;
            for (int p : mRecordProgress.values()) sum += p;
            int avg = sum / n;
            mNotificationBuilder.setContentText(n == 1
                    ? getString(R.string.cs_notif_summary_one, avg)
                    : getString(R.string.cs_notif_summary_many, n, avg));
        }
        notifySafely(NotificationUtils.NOTIFICATION_ID_PROGRESS_SERVICE, mNotificationBuilder.build());
    }

    /** Cheap identity probe for the payload's leading figure (MB downloaded). */
    private static boolean firstNumberChanged(Object[] now, Object[] before) {
        double a = firstNumber(now), b = firstNumber(before);
        return a >= 0 && a != b;
    }

    private static double firstNumber(Object[] va) {
        if (va == null || va.length < 1 || !(va[0] instanceof Number)) return -1;
        return ((Number) va[0]).doubleValue();
    }

    private Notification buildChild(String record, RecordListener rl) {
        boolean paused = DownloadControl.isPaused(record);
        int progress = Math.max(0, Math.min(100, rl.lastProgress));

        ParsedStats stats = parseStats(record, rl.lastResid, rl.lastVa);
        String title = resolveTitle(record, rl.lastResid, rl.lastVa, stats);
        StringBuilder text = new StringBuilder();
        if (stats.detail != null && !stats.detail.isEmpty()) text.append(stats.detail);
        if (stats.speedMbps != null) {
            if (text.length() > 0) text.append("  •  ");
            text.append(String.format(Locale.US, "%.1f MB/s", stats.speedMbps));
        }
        if (stats.eta != null && !stats.eta.isEmpty()) {
            if (text.length() > 0) text.append("  •  ");
            text.append(stats.eta);
        }

        String meter = progressMeter(progress);
        String collapsed = meter + "  " + progress + "%"
                + (text.length() > 0 ? "   " + text : "");

        // Expanded card: everything the Download Console shows, right here.
        StringBuilder big = new StringBuilder();
        big.append(meter).append("  ").append(progress).append("%");
        if (stats.detail != null && !stats.detail.isEmpty()) {
            big.append('\n').append(stats.detail);
        }
        if (stats.speedMbps != null) {
            big.append('\n').append(String.format(Locale.US, "%.1f MB/s", stats.speedMbps));
            if (stats.eta != null && !stats.eta.isEmpty()) big.append("  •  ").append(stats.eta);
        } else if (stats.eta != null && !stats.eta.isEmpty()) {
            big.append('\n').append(stats.eta);
        }
        if (paused) big.append('\n').append(getString(R.string.cs_notif_paused));

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setSmallIcon(R.drawable.notif_icon)
                .setContentTitle(title)
                .setContentText(collapsed)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(big.toString()))
                .setSubText(paused ? getString(R.string.cs_notif_paused)
                        : getString(R.string.cs_notif_downloading))
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                // setOngoing is what actually pins it: NotificationCompat exposes no
                // setFlag(), and an ongoing progress card has no clear button anyway.
                .setOngoing(true)
                .setGroup(GROUP_DOWNLOADS)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setColor(ACCENT_COLOR)
                .setContentIntent(openLauncherIntent(record))
                .setNotificationSilent();

        if (rl != null) {
            if (rl.startedAtMs <= 0) rl.startedAtMs = System.currentTimeMillis();
            b.setWhen(rl.startedAtMs).setShowWhen(true).setUsesChronometer(!paused);
        }

        // Pause / Resume
        b.addAction(paused ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause,
                getString(paused ? R.string.cs_notif_resume : R.string.cs_notif_pause),
                serviceIntent(ACTION_TOGGLE_PAUSE, record));
        // Stop
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cs_notif_stop), serviceIntent(ACTION_STOP, record));
        // Open launcher
        b.addAction(android.R.drawable.ic_menu_view,
                getString(R.string.cs_notif_open), openLauncherIntent(record));
        return b.build();
    }

    /** 14-cell block meter — renders identically on every OEM font stack. */
    private static String progressMeter(int progress) {
        final int cells = 14;
        int filled = (int) Math.round((Math.max(0, Math.min(100, progress)) / 100.0) * cells);
        StringBuilder sb = new StringBuilder(cells);
        for (int i = 0; i < cells; i++) sb.append(i < filled ? '\u25CF' : '\u25CB');
        return sb.toString();
    }

    private void completeRecord(String record) {
        mRecordProgress.remove(record);
        RecordListener rl = mRecordListeners.remove(record);
        if (rl != null) ProgressKeeper.removeListener(record, rl);
        mNotificationManager.cancel(childId(record));
        // short-lived completion card; tapping opens the launcher
        ParsedStats stats = rl != null ? parseStats(record, rl.lastResid, rl.lastVa) : ParsedStats.EMPTY;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
                .setSmallIcon(R.drawable.notif_icon)
                .setContentTitle(getString(R.string.cs_notif_complete_title))
                .setContentText(getString(R.string.cs_notif_complete_text,
                        resolveTitle(record, rl != null ? rl.lastResid : -1, rl != null ? rl.lastVa : null, stats)))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(getString(R.string.cs_notif_complete_title))
                        .bigText(getString(R.string.cs_notif_complete_text,
                                resolveTitle(record, rl != null ? rl.lastResid : -1,
                                        rl != null ? rl.lastVa : null, stats))))
                .setProgress(0, 0, false)
                .setAutoCancel(true)
                .setTimeoutAfter(COMPLETE_TIMEOUT_MS)
                .setGroup(GROUP_DOWNLOADS)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setColor(ACCENT_COLOR)
                .setContentIntent(openLauncherIntent(record))
                .setNotificationSilent();
        notifySafely(completeId(record), b.build());
    }

    private void cancelChild(String record) {
        mRecordProgress.remove(record);
        RecordListener rl = mRecordListeners.remove(record);
        if (rl != null) ProgressKeeper.removeListener(record, rl);
        mNotificationManager.cancel(childId(record));
    }

    // ------------------------------------------------------------ stats parsing

    private static final class ParsedStats {
        static final ParsedStats EMPTY = new ParsedStats();
        String detail;
        Double speedMbps;
        String eta;
        String contentName;
        String contentType;
    }

    /** Mirrors ProgressLayout's payload decoding so notifications show the exact same numbers. */
    private ParsedStats parseStats(String record, int resid, Object[] va) {
        ParsedStats stats = new ParsedStats();
        if (va == null) return stats;
        try {
            if (va.length >= 9) {
                // Rich mod/modpack download payload
                stats.contentName = (String) va[5];
                stats.contentType = (String) va[8];
                double currentMB = ((Number) va[1]).doubleValue();
                double totalMB = ((Number) va[2]).doubleValue();
                double speed = ((Number) va[3]).doubleValue();
                double remainingSec = ((Number) va[4]).doubleValue();
                if (totalMB > 0) stats.detail = String.format(Locale.US, "%.1f / %.1f MB", currentMB, totalMB);
                if (speed > 0) stats.speedMbps = speed;
                if (remainingSec >= 0) stats.eta = formatRemainingTime(remainingSec);
            } else if (resid == R.string.newdl_downloading_game_files_size && va.length >= 3) {
                double currentMB = ((Number) va[0]).doubleValue();
                double totalMB = ((Number) va[1]).doubleValue();
                double speed = ((Number) va[2]).doubleValue();
                stats.detail = String.format(Locale.US, "%.1f / %.1f MB", currentMB, totalMB);
                if (speed > 0) {
                    stats.speedMbps = speed;
                    double remainingMB = Math.max(0, totalMB - currentMB);
                    stats.eta = formatRemainingTime(remainingMB / speed);
                }
            } else if (resid == R.string.newdl_downloading_game_files && va.length >= 2) {
                long currentFiles = ((Number) va[0]).longValue();
                long totalFiles = ((Number) va[1]).longValue();
                stats.detail = currentFiles + " / " + totalFiles + " files";
                if (va.length >= 3) {
                    double filesPerSec = ((Number) va[2]).doubleValue();
                    if (filesPerSec > 0) {
                        stats.eta = formatRemainingTime(Math.max(0, totalFiles - currentFiles) / filesPerSec);
                    }
                }
            } else if (va.length >= 2 && va[0] instanceof Number && va[1] instanceof Number) {
                double currentMB = ((Number) va[0]).doubleValue();
                double totalMB = ((Number) va[1]).doubleValue();
                if (totalMB > 0) stats.detail = String.format(Locale.US, "%.1f / %.1f MB", currentMB, totalMB);
                if (va.length >= 3) {
                    double speed = ((Number) va[2]).doubleValue();
                    if (speed > 0) {
                        stats.speedMbps = speed;
                        double remainingMB = Math.max(0, totalMB - currentMB);
                        stats.eta = formatRemainingTime(remainingMB / speed);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Malformed payloads must degrade to a plain progress bar, never a crash.
        }
        return stats;
    }

    /**
     * The shade card's title. It delegates to the same builder the Download Console
     * row uses (com.kdt.mcgui.ProgressTitles) — the second copy of those rules that
     * used to live here is what let the in-app row and the notification disagree
     * about the very same download ("Unpacking Runtime" vs "Downloading Java
     * Runtime"), which is exactly the "the notification is decoration, not truth"
     * complaint. Only the resource-string fallback stays here, because resolving a
     * string needs a Context and the shared builder must not have one.
     */
    private String resolveTitle(String record, int resid, Object[] va, ParsedStats stats) {
        com.kdt.mcgui.ProgressTitles.init(getResources());
        String shared = com.kdt.mcgui.ProgressTitles.title(record, resid, va,
                stats.contentType, stats.contentName);
        if (shared != null) return shared;
        if (resid > 0) {
            try {
                return getString(resid);
            } catch (Throwable ignored) {}
        }
        if (va != null && va.length > 0 && va[0] instanceof String) return (String) va[0];
        return "Downloading…";
    }


    private static String formatRemainingTime(double seconds) {
        if (seconds < 0) return "";
        long total = (long) seconds;
        long hours = total / 3600, minutes = (total % 3600) / 60, secs = total % 60;
        if (hours > 0) return String.format(Locale.US, "%dh %dm left", hours, minutes);
        if (minutes > 0) return String.format(Locale.US, "%dm %ds left", minutes, secs);
        return String.format(Locale.US, "%ds left", secs);
    }

    // ------------------------------------------------------------ plumbing

    private PendingIntent serviceIntent(String action, String record) {
        Intent intent = new Intent(this, ProgressService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_RECORD, record);
        return PendingIntent.getService(this, childId(record), intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openLauncherIntent(@Nullable String record) {
        Intent intent = new Intent(this, net.kdt.pojavlaunch.LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (record != null) intent.putExtra("cs_open_downloads", true);
        return PendingIntent.getActivity(this, record != null ? childId(record) : 0, intent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void notifySafely(int id, Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return;
            mNotificationManager.notify(id, notification);
        } catch (SecurityException se) {
            Log.w(TAG, "Notification blocked (no permission)", se);
        }
    }

    private static int childId(String record) {
        return CHILD_ID_BASE + (record.hashCode() & 0x3FFF);
    }

    private static int completeId(String record) {
        return COMPLETE_ID_BASE + (record.hashCode() & 0x3FFF);
    }
}
