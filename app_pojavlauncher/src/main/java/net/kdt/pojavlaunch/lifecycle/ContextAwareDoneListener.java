package net.kdt.pojavlaunch.lifecycle;

import static net.kdt.pojavlaunch.MainActivity.INTENT_MINECRAFT_VERSION;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.NotificationUtils;

public class ContextAwareDoneListener implements AsyncMinecraftDownloader.DoneListener, ContextExecutorTask {
    private final String mErrorString;
    private final String mNormalizedVersionid;
    private final String mProfileKey;
    private final String mAccountName;

    public ContextAwareDoneListener(Context baseContext, String versionId) {
        this.mErrorString = baseContext.getString(R.string.mc_download_failed);
        this.mNormalizedVersionid = versionId;
        this.mProfileKey = LauncherPreferences.DEFAULT_PREF.getString(
                LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "default");
        this.mAccountName = net.kdt.pojavlaunch.PojavProfile.getCurrentProfileName(baseContext);
    }

    private Intent createGameStartIntent(Context context) {
        Intent mainIntent = new Intent(context, MainActivity.class);
        mainIntent.putExtra(INTENT_MINECRAFT_VERSION, mNormalizedVersionid);
        mainIntent.putExtra(MainActivity.EXTRA_PROFILE_KEY, mProfileKey);
        mainIntent.putExtra(MainActivity.EXTRA_ACCOUNT_NAME, mAccountName);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // Shortcut launches asked for the launch-log screen on arrival. The flag
        // is one-shot and consumed here so manual launches stay unaffected.
        if (MainActivity.sAutoShowLogsOnce) {
            MainActivity.sAutoShowLogsOnce = false;
            mainIntent.putExtra(MainActivity.EXTRA_AUTO_SHOW_LOGS, true);
        }
        return mainIntent;
    }

    @Override
    public void onDownloadDone() {
        ProgressKeeper.waitUntilDone(() -> {
            // Verification + any required downloads all cleared — STARTING beat.
            net.kdt.pojavlaunch.launch.LaunchTracker.starting();
            ContextExecutor.execute(this);
        });
    }

    @Override
    public void onDownloadFailed(Throwable throwable) {
        net.kdt.pojavlaunch.launch.LaunchTracker.fail();
        Tools.showErrorRemote(mErrorString, throwable);
    }

    @Override
    public void executeWithActivity(Activity activity) {
        try {
            Intent gameStartIntent = createGameStartIntent(activity);
            activity.startActivity(gameStartIntent);
            activity.finish();
            android.os.Process.killProcess(android.os.Process.myPid()); //You should kill yourself, NOW!
        } catch (Throwable e) {
            Tools.showError(activity.getBaseContext(), e);
        }
    }

    @Override
    public void executeWithApplication(Context context) {
        Intent gameStartIntent = createGameStartIntent(context);
        // Since the game is a separate process anyway, it does not matter if it gets invoked
        // from somewhere other than the launcher activity.
        // The only problem may arise if the launcher starts doing something when the user starts the notification.
        // So, the notification is automatically removed once there are tasks ongoing in the ProgressKeeper
        NotificationUtils.sendBasicNotification(context,
                R.string.notif_download_finished,
                R.string.notif_download_finished_desc,
                gameStartIntent,
                NotificationUtils.PENDINGINTENT_CODE_GAME_START,
                NotificationUtils.NOTIFICATION_ID_GAME_START
        );
        // You should keep yourself safe, NOW!
        // otherwise android does weird things...
    }
}
