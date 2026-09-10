package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.MainActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.NotificationUtils;

import java.lang.ref.WeakReference;

public class GameService extends Service {
    private static final WeakReference<Service> sGameService = new WeakReference<>(null);
    private final LocalBinder mLocalBinder = new LocalBinder();

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.getBooleanExtra("kill", false)) {
            net.kdt.pojavlaunch.performance.GameSessionState.clear();
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }
        Intent killIntent = new Intent(getApplicationContext(), GameService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this, NotificationUtils.PENDINGINTENT_CODE_KILL_GAME_SERVICE
                , killIntent, Build.VERSION.SDK_INT >=23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                 PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle(getString(R.string.cs_notif_game_title))
                .setContentText(getString(R.string.cs_notif_game_text))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(getString(R.string.cs_notif_game_title))
                        .bigText(getString(R.string.cs_notif_game_big)))
                .setSubText(getString(R.string.lazy_service_default_title))
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_view, getString(R.string.cs_notif_open_launcher), contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,  getString(R.string.notification_terminate), pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setColor(0xFFC9CDD8)
                .setOngoing(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setNotificationSilent();

        Notification notification = notificationBuilder.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_GAME_SERVICE, notification);
        }
        return START_NOT_STICKY; // non-sticky so android wont try restarting the game after the user uses the "Quit" button
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        net.kdt.pojavlaunch.performance.GameSessionState.clear();
        //At this point in time  only the game runs and the user poofed the window, time to die
        stopSelf();
        Process.killProcess(Process.myPid());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mLocalBinder;
    }

    public static class LocalBinder extends Binder {
        public boolean isActive;
    }
}
