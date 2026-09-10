package net.kdt.pojavlaunch.shortcuts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

/**
 * Fires when the home screen confirms that a pin request was accepted.
 *
 * <p>Previously the picker showed "Shortcut created!" the instant the request was
 * sent, so users who dismissed the system dialog were told the shortcut existed
 * when it did not. The launcher only broadcasts this callback on a real pin, so
 * confirming here is accurate.</p>
 */
public class ShortcutPinReceiver extends BroadcastReceiver {

    private static final String TAG = "ShortcutPinReceiver";

    public static final String EXTRA_SHORTCUT_ID = "cs_pinned_shortcut_id";

    /**
     * Optional UI hook so a visible picker can react to the confirmation
     * (dismiss itself, refresh a list, ...). Held as a plain static because the
     * receiver is created by the system and cannot take constructor arguments.
     * Always cleared in the fragment's {@code onDestroyView}.
     */
    @Nullable
    private static volatile PinListener sListener;

    public interface PinListener {
        /** Called on the main thread once a shortcut has really been pinned. */
        void onShortcutPinned(@NonNull String shortcutId);
    }

    public static void setListener(@Nullable PinListener listener) {
        sListener = listener;
    }

    public static void clearListener(@Nullable PinListener listener) {
        // Only clear when the current listener is the one asking, so a newer
        // screen's listener is not wiped by an older screen tearing down.
        if (sListener == listener) sListener = null;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        String shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID);
        Log.d(TAG, "Shortcut pinned: " + shortcutId);

        Context appContext = context.getApplicationContext();

        Tools.runOnUiThread(() -> {
            try {
                Toast.makeText(appContext, R.string.shortcut_created,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
                // Toast can fail if the process is being torn down.
            }

            PinListener listener = sListener;
            if (listener != null && shortcutId != null) {
                listener.onShortcutPinned(shortcutId);
            }
        });
    }
}
