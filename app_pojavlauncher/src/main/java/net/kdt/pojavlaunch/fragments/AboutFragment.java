package net.kdt.pojavlaunch.fragments;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.widget.Toast;
import net.kdt.pojavlaunch.BuildConfig;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * About page — premium animated page with the launcher story, credits to
 * PojavLauncher and Amethyst, community links and the GPL v3 legal notice.
 */
public class AboutFragment extends Fragment {

    public static final String TAG = "AboutFragment";

    // One source of truth (Phase 8): the home brand cluster uses the same links.
    private static final String URL_DISCORD = net.kdt.pojavlaunch.CsLinks.DISCORD;
    private static final String URL_WEBSITE = net.kdt.pojavlaunch.CsLinks.WEBSITE;
    private static final String URL_GITHUB = net.kdt.pojavlaunch.CsLinks.GITHUB;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public AboutFragment() {
        super(R.layout.fragment_about);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Version chip
        TextView versionChip = view.findViewById(R.id.about_version_chip);
        if (versionChip != null) {
            versionChip.setText("v1.0.0");
            // Temporary debug/testing feature: tap version chip 20 times to unlock & view FCM token
            versionChip.setOnClickListener(new View.OnClickListener() {
                private int clicks = 0;
                @Override
                public void onClick(View v) {
                    clicks++;
                    if (clicks >= 20) {
                        clicks = 0;
                        Toast.makeText(requireContext(), "FCM Debug Token Unlocked!", Toast.LENGTH_SHORT).show();
                        net.kdt.pojavlaunch.remote.CsFirebaseMessagingService.showFcmTokenDebugDialog(requireActivity());
                    }
                }
            });
        }

        // Back
        View back = view.findViewById(R.id.about_back_button);
        if (back != null) {
            UiMotion.pressFeedback(back);
            back.setOnClickListener(v -> navigateBack());
        }
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { navigateBack(); }
                });

        // Links
        wireLink(view, R.id.about_link_discord, URL_DISCORD);
        wireLink(view, R.id.about_link_website, URL_WEBSITE);
        wireLink(view, R.id.about_link_github, URL_GITHUB);

        // ── Entrance choreography (best & fast; no-op when animations Off) ──
        if (MotionSpeed.isEnabled()) {
            view.post(() -> {
                if (!isAdded() || isRemoving()) return;
                // Welcome animation
                View heroCard = view.findViewById(R.id.about_hero_card);
                if (heroCard != null) {
                    heroCard.setAlpha(0f);
                    heroCard.setScaleX(0.9f);
                    heroCard.setScaleY(0.9f);
                    heroCard.animate().alpha(1f).scaleX(1f).scaleY(1f)
                            .setDuration((long)(400 * MotionSpeed.factor()))
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                            .start();
                }
                // Hero logo pops in with a jelly overshoot
                View logo = view.findViewById(R.id.about_cs_logo);
                if (logo != null) UiMotion.popIn(logo);

                // Staggered cascade: hero → credits → links → legal
                cascade(view.findViewById(R.id.about_credits_heading), 100);
                cascade(view.findViewById(R.id.about_pojav_card), 170);
                cascade(view.findViewById(R.id.about_amethyst_card), 240);
                cascade(view.findViewById(R.id.about_links_heading), 310);
                cascade(view.findViewById(R.id.about_link_discord), 370);
                cascade(view.findViewById(R.id.about_link_website), 420);
                cascade(view.findViewById(R.id.about_link_github), 470);
                cascade(view.findViewById(R.id.about_legal_heading), 530);
                cascade(view.findViewById(R.id.about_legal_card), 590);

                // Legal notice — TYPEWRITER reveal (user req: text appears as
                // if being written), starts right after the legal card lands.
                mHandler.postDelayed(() -> typewriter(
                        view.findViewById(R.id.about_legal_text),
                        getString(R.string.cs_about_legal_text)), 950);
            });
        } else {
            // Animations off → show the legal text instantly.
            TextView legal = view.findViewById(R.id.about_legal_text);
            if (legal != null) legal.setText(getString(R.string.cs_about_legal_text));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHandler.removeCallbacksAndMessages(null);
    }

    /** Reveals the legal notice word-by-word like it is being typed. */
    private void typewriter(@Nullable TextView tv, @NonNull String fullText) {
        if (tv == null || !isAdded()) return;
        final String[] words = fullText.split(" ");
        tv.setText("");
        tv.setVisibility(View.VISIBLE);
        final long stepMs = Math.max(8L, MotionSpeed.scale(18L)); // per word
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            final int idx = i;
            mHandler.postDelayed(() -> {
                if (!isAdded()) return;
                if (idx > 0) sb.append(" ");
                sb.append(words[idx]);
                tv.setText(sb.toString());
            }, stepMs * (idx + 1));
        }
    }

    private void cascade(@Nullable View v, long delayMs) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(18f * getResources().getDisplayMetrics().density);
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(MotionSpeed.scale(delayMs))
                .setDuration(MotionSpeed.scale(300L))
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
    }

    private void wireLink(@NonNull View root, int id, final String url) {
        View row = root.findViewById(id);
        if (row == null) return;
        UiMotion.pressFeedback(row);
        row.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Throwable t) {
                Tools.showError(requireContext(), t);
            }
        });
    }

    private void navigateBack() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).refreshHomeState();
        } else if (parent != null) {
            parent.getChildFragmentManager().popBackStackImmediate();
        } else {
            Tools.removeCurrentFragment(requireActivity());
        }
    }
}
