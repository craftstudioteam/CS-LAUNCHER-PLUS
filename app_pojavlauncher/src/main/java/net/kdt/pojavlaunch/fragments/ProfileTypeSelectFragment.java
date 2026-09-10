package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;

import android.animation.LayoutTransition;
import android.os.Build;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import androidx.core.view.ViewCompat;
import android.os.Bundle;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;


public class ProfileTypeSelectFragment extends Fragment {
    public static final String TAG = "ProfileTypeSelectFragment";

    private LinearLayout mContent;

    public ProfileTypeSelectFragment() {
        super(R.layout.fragment_profile_type);
    }
    public ProfileTypeSelectFragment(int layout) {
        super(layout);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mContent = view.findViewById(R.id.setup_hub_content);

        // Hardware acceleration
        if (getActivity() != null) {
            getActivity().getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }

        // Smooth layout transitions
        if (mContent != null) {
            LayoutTransition transition = new LayoutTransition();
            transition.enableTransitionType(LayoutTransition.CHANGING);
            transition.setDuration(LayoutTransition.APPEARING, 300);
            transition.setDuration(LayoutTransition.CHANGE_APPEARING, 300);
            transition.setInterpolator(LayoutTransition.APPEARING,
                    new DecelerateInterpolator());
            mContent.setLayoutTransition(transition);
        }

        // Cascading entrance: header → section blocks → per-card stagger
        view.post(() -> animateEntrance(view));

        wireButton(view);
    }

    private void animateEntrance(@NonNull View view) {
        float rise = 20f * getResources().getDisplayMetrics().density;
        animateBlock(view.findViewById(R.id.setup_hub_header), 0, rise);
        // Portrait rails expose section wrappers; the landscape grid does not.
        animateBlock(view.findViewById(R.id.section_vanilla), 90, rise);
        animateBlock(view.findViewById(R.id.section_loaders), 170, rise);
        animateBlock(view.findViewById(R.id.section_more), 250, rise);

        final int[] cards = {
                R.id.modded_profile_bta, R.id.vanilla_profile, R.id.jar_import_profile,
                R.id.modded_profile_fabric, R.id.modded_profile_quilt,
                R.id.modded_profile_forge, R.id.modded_profile_neoforge,
                R.id.modded_profile_modpack
        };
        long delay = 150L;
        for (int id : cards) {
            animateBlock(view.findViewById(id), delay, rise * 0.7f);
            delay += 70L;
        }
    }

    private void animateBlock(@Nullable View v, long delayMs, float rise) {
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

    private void wireButton(@NonNull View view) {
        View b;
        b = view.findViewById(R.id.vanilla_profile);
        b.setOnClickListener(v -> navigateTo(ProfileEditorFragment.class, ProfileEditorFragment.TAG, new Bundle(1)));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.jar_import_profile);
        b.setOnClickListener(v -> net.kdt.pojavlaunch.Tools.installMod(requireActivity(), false));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.modded_profile_fabric);
        b.setOnClickListener(v -> tryInstall(FabricInstallFragment.class, FabricInstallFragment.TAG));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.modded_profile_forge);
        b.setOnClickListener(v -> tryInstall(ForgeInstallFragment.class, ForgeInstallFragment.TAG));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.modded_profile_neoforge);
        b.setOnClickListener(v -> tryInstall(NeoForgeInstallFragment.class, NeoForgeInstallFragment.TAG));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.modded_profile_modpack);
        b.setOnClickListener(v -> tryInstall(ModpackCreateFragment.class, ModpackCreateFragment.TAG));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.modded_profile_quilt);
        b.setOnClickListener(v -> tryInstall(QuiltInstallFragment.class, QuiltInstallFragment.TAG));
        setupTouchAnimation(b);

        b = view.findViewById(R.id.modded_profile_bta);
        if (b != null) {
            b.setOnClickListener(v -> navigateTo(CsClientVersionsFragment.class, CsClientVersionsFragment.TAG, null));
            setupTouchAnimation(b);
        }
    }

    /** Scale-press micro-interaction for all interactive buttons */
    private void setupTouchAnimation(View button) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f)
                            .setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(new OvershootInterpolator(2f))
                            .start();
                    break;
            }
            return false;
        });
    }
    /** Navigate within right pane if inside MainMenuFragment, otherwise full-screen swap. */
    protected void navigateTo(Class<? extends Fragment> cls, String tag, Bundle args) {
        // Walk up to find MainMenuFragment (could be grandparent if nested)
        Fragment parent = getParentFragment();
        while (parent != null && !(parent instanceof MainMenuFragment)) {
            parent = parent.getParentFragment();
        }
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(cls, tag, args);
        } else {
            Tools.swapFragment(requireActivity(), cls, tag, args);
        }
    }

    private void tryInstall(Class<? extends Fragment> fragmentClass, String tag){
        if(!hasOnlineProfile()){
            hasNoOnlineProfileDialog(requireActivity());
        } else {
            navigateTo(fragmentClass, tag, null);
        }
    }
}

