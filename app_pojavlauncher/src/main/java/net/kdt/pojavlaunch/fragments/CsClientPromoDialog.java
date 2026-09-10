package net.kdt.pojavlaunch.fragments;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

/**
 * CS Launcher Plus — CS Client Promo Dialog.
 *
 * Displays the official English promo for CS Client ("80% completed — complete 1,000 subscribers
 * on our YouTube channel to launch immediately!").
 */
public final class CsClientPromoDialog extends DialogFragment {

    public static final String TAG = "CS_CLIENT_PROMO_DIALOG";
    private static final String YOUTUBE_URL = "https://youtube.com/@craft-studio-official?si=WmZNedIAnp4QcToO";

    public static void show(@NonNull FragmentActivity activity) {
        new CsClientPromoDialog().show(activity.getSupportFragmentManager(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.65f);
        }
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_cs_client_promo, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        View card = view.findViewById(R.id.cs_promo_card);
        if (card != null) {
            card.setAlpha(0f);
            card.setScaleX(0.88f);
            card.setScaleY(0.88f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f))
                    .start();
        }

        View btnYoutube = view.findViewById(R.id.btn_cs_promo_youtube);
        if (btnYoutube != null) {
            btnYoutube.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_URL)));
                } catch (Throwable ignored) {}
            });
        }

        View btnClose = view.findViewById(R.id.btn_cs_promo_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }
    }
}
