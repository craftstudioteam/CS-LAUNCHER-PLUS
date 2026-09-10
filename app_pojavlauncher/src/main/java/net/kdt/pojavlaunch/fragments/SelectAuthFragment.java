package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        View mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        View mElybyButton = view.findViewById(R.id.button_elyby_authentication);
        View mLocalButton = view.findViewById(R.id.button_local_authentication);

        mMicrosoftButton.setOnClickListener(v -> navigateTo(MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null));

        mElybyButton.setOnClickListener(v -> navigateTo(ElybyLoginFragment.class, ElybyLoginFragment.TAG, null));

        mLocalButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity(),
                () -> navigateTo(LocalLoginFragment.class, LocalLoginFragment.TAG, null)));

        // Staggered rise-in: header first, then the three cards, then the note
        float rise = 18f * getResources().getDisplayMetrics().density;
        animateIn(view.findViewById(R.id.auth_header), 0, rise);
        animateIn(mMicrosoftButton, 90, rise);
        animateIn(mElybyButton, 180, rise);
        animateIn(mLocalButton, 270, rise);
        animateIn(view.findViewById(R.id.auth_footnote), 360, rise);
    }

    private void animateIn(@Nullable View v, long delayMs, float rise) {
        if (v == null) return;
        v.setAlpha(0f);
        v.setTranslationY(rise);
        v.setScaleX(0.95f);
        v.setScaleY(0.95f);
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(430)
                .setInterpolator(net.kdt.pojavlaunch.utils.animation.MotionCurves.SPRING_SOFT)
                .start();
    }

    /** Navigate within right pane if inside MainMenuFragment, otherwise full-screen swap. */
    private void navigateTo(Class<? extends Fragment> cls, String tag, android.os.Bundle args) {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(cls, tag, args);
        } else {
            Tools.swapFragment(requireActivity(), cls, tag, args);
        }
    }
}