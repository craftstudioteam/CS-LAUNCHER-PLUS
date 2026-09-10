package net.kdt.pojavlaunch.utils;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.kdt.pojavlaunch.R;

/**
 * Modern animated popup — replaces old-style Toasts with a premium glass
 * card that slides up + fades in above the bottom edge, holds briefly and
 * fades out. One reusable helper so every "saved / done / removed" notice
 * in the launcher looks and moves the same (user req).
 *
 * Safe to call from any thread: posts to the main handler internally.
 */
public final class CsPopup {

    private CsPopup() { }

    public static void show(Context context, String message) {
        show(context, message, null);
    }

    public static void show(Context context, String message, Integer iconRes) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> buildAndShow(app, message, iconRes));
    }

    private static void buildAndShow(Context context, String message, Integer iconRes) {
        try {
            ViewGroup decor = null;
            if (context instanceof android.app.Activity) {
                decor = (ViewGroup) ((android.app.Activity) context).getWindow().getDecorView();
            } else {
                return; // only attach to activity contexts; callers pass activity
            }
            if (decor == null) return;

            final float density = context.getResources().getDisplayMetrics().density;

            // Glass card
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.bg_cs_popup);
            card.setElevation(16f * density);
            card.setPadding((int) (16 * density), (int) (10 * density),
                    (int) (18 * density), (int) (10 * density));
            card.setClickable(false);
            card.setFocusable(false);

            if (iconRes != null) {
                ImageView icon = new ImageView(context);
                icon.setImageResource(iconRes);
                icon.setColorFilter(0xFF8FEBBC);
                int s = (int) (18 * density);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
                ilp.setMarginEnd((int) (10 * density));
                card.addView(icon, ilp);
            }

            TextView text = new TextView(context);
            text.setText(message);
            text.setTextColor(0xFFF0F0F3);
            text.setTextSize(12.5f);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setIncludeFontPadding(false);
            card.addView(text);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = (int) (42 * density);
            decor.addView(card, lp);

            card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            card.setAlpha(0f);
            card.setTranslationY((int) (24 * density));
            card.animate().alpha(1f).translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator(1.4f))
                    .withEndAction(() -> card.animate()
                            .alpha(0f).translationY((int) (16 * density))
                            .setStartDelay(1400)
                            .setDuration(200)
                            .withEndAction(() -> {
                                ViewGroup parent = (ViewGroup) card.getParent();
                                if (parent != null) parent.removeView(card);
                            })
                            .start())
                    .start();
        } catch (Throwable ignored) {
            // Never crash the launcher over a cosmetic popup.
        }
    }
}
