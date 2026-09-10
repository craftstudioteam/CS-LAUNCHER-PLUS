package net.kdt.pojavlaunch.remote;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import java.util.Map;

/**
 * CS Launcher Plus — Firebase Cloud Messaging (FCM) push notifications service.
 *
 * Handles registration token acquisition, automatic topic subscriptions
 * ("launcher_updates", "launcher_announcements", "server_news", "maintenance"),
 * Android 8.0+ notification channels, Android 13+ permission compliance, and
 * notification click navigation to existing Launcher screens without creating
 * duplicate announcement or update systems.
 */
public class CsFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "FCM_CSLauncher";

    public static final String CHANNEL_UPDATES_ID = "cs_launcher_updates";
    public static final String CHANNEL_ANNOUNCEMENTS_ID = "cs_launcher_announcements";

    private static final String TOPIC_UPDATES = "launcher_updates";
    private static final String TOPIC_ANNOUNCEMENTS = "launcher_announcements";
    private static final String TOPIC_SERVER_NEWS = "server_news";
    private static final String TOPIC_MAINTENANCE = "maintenance";

    private static volatile String sCachedToken = null;

    /**
     * Creates required Android 8.0+ Notification Channels.
     */
    public static void createNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;

            NotificationChannel updatesChannel = new NotificationChannel(
                    CHANNEL_UPDATES_ID,
                    "Launcher Updates",
                    NotificationManager.IMPORTANCE_HIGH
            );
            updatesChannel.setDescription("Notifications for CS LAUNCHER PLUS application updates");

            NotificationChannel announcementsChannel = new NotificationChannel(
                    CHANNEL_ANNOUNCEMENTS_ID,
                    "Announcements & News",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            announcementsChannel.setDescription("Important announcements, server news, and maintenance alerts");

            manager.createNotificationChannel(updatesChannel);
            manager.createNotificationChannel(announcementsChannel);
        }
    }

    /**
     * Initializes FCM channels, obtains token (logged in debug builds only),
     * and automatically subscribes to initial topics.
     */
    public static void initFcm(Context context) {
        createNotificationChannels(context);
        if (net.kdt.pojavlaunch.performance.GameSessionState.isGameActive()) return;

        FirebaseMessaging messaging = FirebaseMessaging.getInstance();

        // Subscribe to required topics automatically
        messaging.subscribeToTopic(TOPIC_UPDATES);
        messaging.subscribeToTopic(TOPIC_ANNOUNCEMENTS);
        messaging.subscribeToTopic(TOPIC_SERVER_NEWS);
        messaging.subscribeToTopic(TOPIC_MAINTENANCE);

        // Obtain token for testing in debug mode (never exposed in normal UI)
        try {
            messaging.getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) return;
                String token = task.getResult();
                sCachedToken = token;
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "FCM Registration Token: " + token);
                }
            });
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to get FCM token", t);
            }
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        sCachedToken = token;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "New FCM Token: " + token);
        }
        // During gameplay retain only the lightweight token/notification path.
        if (net.kdt.pojavlaunch.performance.GameSessionState.isGameActive()) return;
        // Re-subscribe to required topics automatically on token refresh
        try {
            FirebaseMessaging messaging = FirebaseMessaging.getInstance();
            messaging.subscribeToTopic(TOPIC_UPDATES);
            messaging.subscribeToTopic(TOPIC_ANNOUNCEMENTS);
            messaging.subscribeToTopic(TOPIC_SERVER_NEWS);
            messaging.subscribeToTopic(TOPIC_MAINTENANCE);
        } catch (Throwable ignored) {}
    }

    /**
     * Temporary testing / debug feature: Displays the FCM Registration Token
     * modal dialog with a "Copy Token" button. Accessed via 5-tap gesture on
     * the App Version chip in the About screen so regular users never see it.
     */
    public static void showFcmTokenDebugDialog(android.app.Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(act);
        builder.setTitle("FCM Push Token (Testing Only)");

        final android.widget.TextView tvMessage = new android.widget.TextView(act);
        tvMessage.setPadding(48, 36, 48, 24);
        tvMessage.setTextIsSelectable(true);
        tvMessage.setTextSize(13.5f);
        tvMessage.setTextColor(0xFFEDEDF2);
        builder.setView(tvMessage);

        String initialToken = sCachedToken;
        if (initialToken != null && !initialToken.trim().isEmpty()) {
            tvMessage.setText("Status: Generated (Ready)\n\nFCM Token:\n" + initialToken);
        } else {
            tvMessage.setText("Status: Token is not generated yet (fetching from Firebase Cloud Messaging...)");
        }

        builder.setPositiveButton("Copy Token", (d, w) -> {
            String toCopy = sCachedToken;
            if (toCopy == null || toCopy.trim().isEmpty()) {
                android.widget.Toast.makeText(act, "No token generated yet to copy!", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("FCM Token", toCopy));
                        android.widget.Toast.makeText(act, "FCM Token copied to clipboard!", android.widget.Toast.LENGTH_LONG).show();
                    }
                } catch (Throwable t) {
                    android.widget.Toast.makeText(act, "Failed to copy token", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Close", null);

        final android.app.AlertDialog dialog = builder.create();
        dialog.show();

        // Dynamically fetch or refresh token in case it wasn't cached yet
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if (!task.isSuccessful() || task.getResult() == null) {
                    act.runOnUiThread(() -> {
                        if (sCachedToken == null && dialog.isShowing()) {
                            tvMessage.setText("Status: Not generated yet\n\nError: "
                                    + (task.getException() != null ? task.getException().getMessage() : "Unknown FCM error"));
                        }
                    });
                    return;
                }
                String token = task.getResult();
                sCachedToken = token;
                act.runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        tvMessage.setText("Status: Generated (Ready)\n\nFCM Token:\n" + token);
                    }
                });
            });
        } catch (Throwable t) {
            act.runOnUiThread(() -> {
                if (sCachedToken == null && dialog.isShowing()) {
                    tvMessage.setText("Status: Not generated yet\n\nError: " + t.getMessage());
                }
            });
        }
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Map<String, String> data = remoteMessage.getData();
        RemoteMessage.Notification notif = remoteMessage.getNotification();

        String type = data.get("type");
        String title = data.get("title");
        String message = data.get("message");
        String version = data.get("version");
        String url = data.get("url");
        String announcementId = data.get("announcementId");

        if ((title == null || title.trim().isEmpty()) && notif != null) {
            title = notif.getTitle();
        }
        if ((message == null || message.trim().isEmpty()) && notif != null) {
            message = notif.getBody();
        }
        if (title == null || title.trim().isEmpty()) {
            title = "CS LAUNCHER PLUS";
        }
        if (message == null) {
            message = "";
        }
        if (type == null || type.trim().isEmpty()) {
            if (version != null || url != null) {
                type = "update";
            } else {
                type = "announcement";
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "FCM received - type=" + type + ", title=" + title + ", message=" + message);
        }

        showPushNotification(this, type, title, message, version, url, announcementId);
    }

    private void showPushNotification(Context context, String type, String title,
                                      String message, String version,
                                      String url, String announcementId) {
        // Android 13+ POST_NOTIFICATIONS permission check
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted; skipping notification display.");
            }
            return;
        }

        createNotificationChannels(context);

        Intent intent = new Intent(context, LauncherActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("fcm_type", type);
        intent.putExtra("fcm_title", title);
        intent.putExtra("fcm_message", message);
        if (version != null) intent.putExtra("fcm_version", version);
        if (url != null) intent.putExtra("fcm_url", url);
        if (announcementId != null) intent.putExtra("fcm_announcementId", announcementId);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, (int) (System.currentTimeMillis() % 100000), intent, flags);

        boolean isUpdate = "update".equalsIgnoreCase(type)
                || "launcher_updates".equalsIgnoreCase(type);
        String channelId = isUpdate ? CHANNEL_UPDATES_ID : CHANNEL_ANNOUNCEMENTS_ID;
        int priority = isUpdate ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_DEFAULT;

        int iconRes = R.mipmap.ic_launcher;
        try {
            if (iconRes == 0) iconRes = android.R.drawable.ic_dialog_info;
        } catch (Throwable ignored) {}

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(priority)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            manager.notify((int) (System.currentTimeMillis() % 100000), builder.build());
        } catch (Throwable t) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to display FCM notification", t);
            }
        }
    }
}
