package net.kdt.pojavlaunch.fragments;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;

/**
 * Creation-type chooser — the single popup behind the floating "+" button.
 *
 *   Normal Version → {@link VersionCreateFragment} (full-screen guided creation)
 *   Client         → {@link CsClientVersionsFragment} (existing CS Client flow)
 *   Mod Pack       → {@link ModpackCreateFragment} (existing browse/import flow)
 *
 * Presentation: a centred graphite card, 460dp wide max and ≈300dp tall, so all
 * three options are always on screen in landscape while staying comfortably
 * tappable (64dp rows). Motion follows an anime.js-style timeline:
 * card outBack scale-in → rows stagger(70) from the right with outExpo →
 * medallion icons outElastic pop → FEATURED badge pop → footer fade. Picking a
 * row scales it up while the others recede, then the card exits with inBack.
 *
 * Stays inside the right pane when launched from {@link MainMenuFragment},
 * otherwise swaps the activity's main container — identical routing rules to
 * every other creation fragment, so no parallel navigation system is created.
 */
public class CreationTypeDialog extends DialogFragment {

    public static final String TAG = "CreationTypeDialog";

    private static final int MAX_WIDTH_DP = 460;
    private static final int H_MARGIN_DP  = 20;

    private View mSheet;
    private boolean mClosing;

    public static void show(@NonNull Fragment host) {
        CreationTypeDialog dialog = new CreationTypeDialog();
        // Hosted on the parent fragment manager so navigate() can walk up the
        // fragment chain (right pane inside MainMenuFragment / full-screen home).
        dialog.show(host.getParentFragmentManager(), TAG);
    }

    public static void show(@NonNull FragmentActivity activity) {
        CreationTypeDialog dialog = new CreationTypeDialog();
        dialog.show(activity.getSupportFragmentManager(), TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_creation_type);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.64f);
        }

        mSheet = dialog.findViewById(R.id.creation_sheet);
        final View optNormal  = dialog.findViewById(R.id.creation_opt_normal);
        final View optClient  = dialog.findViewById(R.id.creation_opt_client);
        final View optModpack = dialog.findViewById(R.id.creation_opt_modpack);
        final View close      = dialog.findViewById(R.id.creation_close);
        final View badge      = dialog.findViewById(R.id.creation_featured_badge);
        final View ring       = dialog.findViewById(R.id.creation_ring);
        final View footer     = dialog.findViewById(R.id.creation_footer);
        final View title      = dialog.findViewById(R.id.creation_title);
        final View[] icons = {
                dialog.findViewById(R.id.creation_icon_normal),
                dialog.findViewById(R.id.creation_icon_client),
                dialog.findViewById(R.id.creation_icon_modpack)};

        UiMotion.pressFeedback(optNormal, optClient, optModpack, close);

        // ── timeline ───────────────────────────────────────────────────────
        // t=0     card: scale .9→1, y 24→0, outBack
        Anime.in(mSheet, Anime.Fx.SCALE_IN, 0, 380, Anime.OUT_BACK);
        if (title instanceof android.widget.TextView) Anime.tightenTitle((android.widget.TextView) title, 60);
        // t=120   rows: stagger(70) from the right, outExpo
        final View[] rows = {optNormal, optClient, optModpack};
        for (int i = 0; i < rows.length; i++) Anime.in(rows[i], Anime.Fx.FADE_LEFT, 120 + i * 70L, 560, Anime.OUT_EXPO);
        // t=260   medallion icons: outElastic pop, same stagger
        for (int i = 0; i < icons.length; i++) {
            final View ic = icons[i];
            if (ic == null) continue;
            ic.setAlpha(0f); ic.setScaleX(0.4f); ic.setScaleY(0.4f);
            ic.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(260 + i * 70L).setDuration(620)
                    .setInterpolator(Anime.OUT_ELASTIC).start();
        }
        // t=520   FEATURED badge pops; ring starts breathing; footer fades in
        if (badge != null) badge.postDelayed(() -> Anime.pop(badge), 520);
        if (ring != null) { ring.setAlpha(0f); ring.animate().alpha(0.6f).setStartDelay(600).setDuration(400)
                .withEndAction(() -> Anime.breathe(ring, 0.25f, 0.85f, 1400)).start(); }
        Anime.in(footer, Anime.Fx.FADE_UP, 640, 480, Anime.OUT_EXPO);
        if (close != null) Anime.in(close, Anime.Fx.POP, 300, 420, Anime.OUT_BACK);

        // ── actions ────────────────────────────────────────────────────────
        if (close != null) close.setOnClickListener(v -> closeAnimated(null));

        if (optNormal != null) optNormal.setOnClickListener(v -> pickAndGo(v, rows, () ->
                navigate(VersionCreateFragment.class, VersionCreateFragment.TAG)));

        if (optClient != null) optClient.setOnClickListener(v -> pickAndGo(v, rows, () ->
                navigate(CsClientVersionsFragment.class, CsClientVersionsFragment.TAG)));

        if (optModpack != null) optModpack.setOnClickListener(v -> pickAndGo(v, rows, () -> {
            if (!Tools.hasOnlineProfile()) {
                FragmentActivity a = getActivity();
                if (a != null) Tools.hasNoOnlineProfileDialog(a);
                return;
            }
            navigate(ModpackCreateFragment.class, ModpackCreateFragment.TAG);
        }));

        return dialog;
    }

    /** Chosen row grows and brightens while the others recede, then route. */
    private void pickAndGo(View chosen, View[] rows, Runnable action) {
        if (mClosing) return;
        mClosing = true;
        for (View o : rows) {
            if (o == null || o == chosen) continue;
            o.animate().cancel();
            o.animate().alpha(0.3f).scaleX(0.97f).scaleY(0.97f).translationX(-6f * getResources().getDisplayMetrics().density)
                    .setDuration(180).setInterpolator(Anime.OUT_QUART).start();
        }
        chosen.animate().cancel();
        chosen.animate().scaleX(1.035f).scaleY(1.035f).alpha(1f)
                .setDuration(160)
                .setInterpolator(Anime.OUT_BACK)
                .withEndAction(() -> closeAnimated(action))
                .start();
    }

    /** inBack shrink-and-fade exit, then dismiss and (optionally) navigate. */
    private void closeAnimated(@Nullable Runnable after) {
        if (mSheet == null) {
            dismissAllowingStateLoss();
            if (after != null) after.run();
            return;
        }
        Anime.out(mSheet, Anime.Fx.SCALE_IN, 200, () -> {
            if (!isAdded()) return;
            dismissAllowingStateLoss();
            if (after != null) after.run();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window w = dialog.getWindow();
            float d = getResources().getDisplayMetrics().density;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int width = Math.min((int) (MAX_WIDTH_DP * d), screenW - (int) (2 * H_MARGIN_DP * d));
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setWindowAnimations(0); // motion driven in code
        }
    }

    /** Route exactly like the rest of the launcher: right-pane inside MainMenuFragment,
     *  full-screen swap everywhere else (e.g. the FastClient home). */
    private void navigate(Class<? extends Fragment> cls, String tag) {
        FragmentActivity activity = getActivity();
        if (activity == null) return;

        // Walk up the hosting fragments to find MainMenuFragment (classic layout).
        Fragment host = getParentFragment();
        while (host != null && !(host instanceof MainMenuFragment)) {
            host = host.getParentFragment();
        }
        if (host instanceof MainMenuFragment) {
            ((MainMenuFragment) host).openChildPane(cls, tag, null);
        } else {
            Tools.swapFragment(activity, cls, tag, null);
        }
    }
}
