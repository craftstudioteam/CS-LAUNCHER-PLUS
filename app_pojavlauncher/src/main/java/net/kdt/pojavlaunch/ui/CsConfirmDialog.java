package net.kdt.pojavlaunch.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;

/**
 * Phase 8 — the launcher's own yes/no popup ({@code dialog_cs_confirm.xml}).
 *
 * <p>Replaces the stock {@link android.app.AlertDialog} for destructive and
 * important questions (first user: removing an account). Graphite card, icon
 * well, message, optional detail chip, ghost CANCEL + solid action button.
 * Motion: card outBack scale-in, icon ring pops, buttons fade-up; on the
 * destructive action the card shakes once (haptic) before closing.
 *
 * <pre>
 * new CsConfirmDialog(ctx)
 *     .title("Remove this account?")
 *     .message("Steve will be signed out…")
 *     .detail("Steve  ·  LOCAL")
 *     .danger("Remove", () -> removeAccount(pos))
 *     .show();
 * </pre>
 */
public final class CsConfirmDialog {

    private final Context mContext;
    private CharSequence mTitle, mMessage, mDetail, mKicker;
    private CharSequence mConfirmLabel, mCancelLabel;
    private boolean mDanger = true;
    @DrawableRes private int mIcon = R.drawable.ic_menu_delete_forever;
    private Runnable mOnConfirm, mOnCancel;
    private boolean mClosing;
    private Dialog mDialog;

    public CsConfirmDialog(@NonNull Context context) { mContext = context; }

    public CsConfirmDialog kicker(CharSequence kicker) { mKicker = kicker; return this; }
    public CsConfirmDialog title(CharSequence title) { mTitle = title; return this; }
    public CsConfirmDialog message(CharSequence message) { mMessage = message; return this; }
    public CsConfirmDialog detail(@Nullable CharSequence detail) { mDetail = detail; return this; }
    public CsConfirmDialog icon(@DrawableRes int icon) { mIcon = icon; return this; }
    public CsConfirmDialog cancelLabel(CharSequence label) { mCancelLabel = label; return this; }
    public CsConfirmDialog onCancel(Runnable r) { mOnCancel = r; return this; }

    /** Red action — destructive. */
    public CsConfirmDialog danger(CharSequence label, Runnable onConfirm) {
        mDanger = true; mConfirmLabel = label; mOnConfirm = onConfirm; return this;
    }

    /** Silver action — neutral confirmation. */
    public CsConfirmDialog primary(CharSequence label, Runnable onConfirm) {
        mDanger = false; mConfirmLabel = label; mOnConfirm = onConfirm; return this;
    }

    public Dialog show() {
        final Dialog dialog = new Dialog(mContext);
        mDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_cs_confirm);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.64f);
            w.setWindowAnimations(0);
        }

        final View card = dialog.findViewById(R.id.cfm_card);
        final View well = dialog.findViewById(R.id.cfm_icon_well);
        final ImageView icon = dialog.findViewById(R.id.cfm_icon);
        final TextView kicker = dialog.findViewById(R.id.cfm_kicker);
        final TextView title = dialog.findViewById(R.id.cfm_title);
        final TextView message = dialog.findViewById(R.id.cfm_message);
        final TextView detail = dialog.findViewById(R.id.cfm_detail);
        final TextView cancel = dialog.findViewById(R.id.cfm_cancel);
        final TextView confirm = dialog.findViewById(R.id.cfm_confirm);
        final View buttons = dialog.findViewById(R.id.cfm_buttons);

        if (mKicker != null) kicker.setText(mKicker);
        title.setText(mTitle != null ? mTitle : "");
        if (mMessage == null || mMessage.length() == 0) message.setVisibility(View.GONE);
        else message.setText(mMessage);
        if (mDetail != null && mDetail.length() > 0) { detail.setVisibility(View.VISIBLE); detail.setText(mDetail); }
        if (mCancelLabel != null) cancel.setText(mCancelLabel);
        if (mConfirmLabel != null) confirm.setText(mConfirmLabel);
        icon.setImageResource(mIcon);
        if (mDanger) {
            well.setBackgroundResource(R.drawable.cfm_icon_ring);
            icon.setColorFilter(0xFFF08A92);
            confirm.setBackgroundResource(R.drawable.cfm_btn_danger);
            confirm.setTextColor(0xFFFFFFFF);
        } else {
            well.setBackgroundResource(R.drawable.cfm_icon_ring_neutral);
            icon.setColorFilter(0xFFD2D6DE);
            confirm.setBackgroundResource(R.drawable.cfm_btn_primary);
            confirm.setTextColor(0xFF141519);
        }

        UiMotion.pressFeedback(cancel, confirm);
        cancel.setOnClickListener(v -> close(card, false));
        confirm.setOnClickListener(v -> {
            if (mClosing) return;
            if (mDanger) {
                Anime.shake(card);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                card.postDelayed(() -> close(card, true), 240);
            } else {
                Anime.pulse(confirm);
                close(card, true);
            }
        });
        dialog.setOnCancelListener(d -> { if (mOnCancel != null) mOnCancel.run(); });

        dialog.show();
        if (w != null) {
            float d = mContext.getResources().getDisplayMetrics().density;
            int width = (int) Math.min(400 * d, mContext.getResources().getDisplayMetrics().widthPixels - 40 * d);
            w.setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }

        // Entrance timeline (anime.js style): card outBack → ring pop → text → buttons.
        card.setAlpha(0f); card.setScaleX(0.88f); card.setScaleY(0.88f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(Anime.OUT_BACK).withLayer().start();
        Anime.in(well, Anime.Fx.POP, 120, 520, Anime.OUT_BACK);
        Anime.in(title, Anime.Fx.FADE_LEFT, 160, 460, Anime.OUT_EXPO);
        Anime.in(message, Anime.Fx.FADE_UP, 220, 460, Anime.OUT_EXPO);
        if (detail.getVisibility() == View.VISIBLE) Anime.in(detail, Anime.Fx.FADE_UP, 260, 460, Anime.OUT_EXPO);
        Anime.in(buttons, Anime.Fx.FADE_UP, 320, 480, Anime.OUT_EXPO);
        return dialog;
    }

    private void close(View card, boolean confirmed) {
        if (mClosing) return;
        mClosing = true;
        float d = mContext.getResources().getDisplayMetrics().density;
        card.animate().cancel();
        card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).translationY(16f * d)
                .setDuration(200).setInterpolator(Anime.IN_BACK)
                .withEndAction(() -> {
                    try { mDialog.dismiss(); } catch (Throwable ignored) {}
                    if (confirmed) { if (mOnConfirm != null) mOnConfirm.run(); }
                    else if (mOnCancel != null) mOnCancel.run();
                }).start();
    }
}
