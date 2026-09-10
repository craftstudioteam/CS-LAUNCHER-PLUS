package net.kdt.pojavlaunch.sponsor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.Tools;

/**
 * Central hub for the Infrawire Official Partner integration.
 *
 * Single source of truth for partner branding, links and the "explore partner"
 * navigation so every placement (home card, partners page, downloads badge,
 * settings section, first-launch dialog) stays consistent and non-intrusive.
 */
public final class InfrawirePartner {

    public static final String NAME = "Infrawire";
    public static final String TAGLINE = "High-Performance VPS & Cloud Hosting";
    public static final String SUB_TEXT = "Official Cloud Hosting Partner of CS LAUNCHER PLUS";
    public static final String DESCRIPTION =
            "Infrawire is the Official Hosting Partner of CS LAUNCHER PLUS, providing "
                    + "high-performance VPS and cloud infrastructure on latest-generation "
                    + "hardware with NVMe storage, DDR4 memory and a 10 Gbps global network.";

    // ── Official links ──
    public static final String URL_WEBSITE    = "https://infrawire.net";
    public static final String URL_VPS        = "https://infrawire.net/vps";
    public static final String URL_CLOUD      = "https://infrawire.net/cloud";
    public static final String URL_PROMOTIONS = "https://infrawire.net/promotions";
    public static final String URL_SUPPORT    = "https://infrawire.net/contact";
    public static final String URL_DOCS       = "https://infrawire.net/learn";

    private static final String PREFS = "infrawire_partner_prefs";
    private static final String KEY_WELCOME_SHOWN = "welcome_dialog_shown";

    private InfrawirePartner() { /* no instances */ }

    /** Open an external partner link in the device browser (never a WebView). */
    public static void openLink(@NonNull Context context, @NonNull String url) {
        if (context instanceof Activity) {
            Tools.openURL((Activity) context, url);
        } else {
            Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /** Navigate to the full Official Partners page inside the launcher. */
    public static void openPartnerPage(@NonNull FragmentActivity activity) {
        Tools.swapFragment(activity, InfrawirePartnerFragment.class,
                InfrawirePartnerFragment.TAG, null);
    }

    /** First-launch welcome dialog: shown exactly once, never again. */
    public static boolean wasWelcomeShown(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_WELCOME_SHOWN, false);
    }

    public static void markWelcomeShown(@NonNull Context context) {
        prefs(context).edit().putBoolean(KEY_WELCOME_SHOWN, true).apply();
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Soft press-scale animation used across all Infrawire touchables.
     * Returns false so the event keeps propagating to click handlers.
     */
    public static void applyPressAnimation(@Nullable View target) {
        if (target == null) return;
        target.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).start();
                    v.animate().alpha(0.88f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.animate().alpha(1f).setDuration(150).start();
                    break;
            }
            return false;
        });
    }

    /** Gentle fade-in + rise animation for partner surfaces. */
    public static void fadeIn(@Nullable View target, long delayMs) {
        if (target == null) return;
        target.setAlpha(0f);
        target.setTranslationY(18f);
        target.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(340)
                .start();
    }
}
