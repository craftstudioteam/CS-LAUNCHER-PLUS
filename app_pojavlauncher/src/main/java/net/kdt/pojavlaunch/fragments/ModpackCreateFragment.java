package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.MineButton;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;

public class ModpackCreateFragment extends Fragment {
    public static final String TAG = "ModpackCreateFragment";
    public ModpackCreateFragment() {
        super(R.layout.fragment_create_modpack_profile);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        view.findViewById(R.id.button_browse_modpacks).setOnClickListener(v -> {
            tryInstall(SearchModFragment.class, SearchModFragment.TAG);
        });
        view.findViewById(R.id.button_import_modpack).setOnClickListener(v -> {
            Activity launcheractivity = requireActivity();
            if (!(launcheractivity instanceof LauncherActivity))
                    throw new IllegalStateException("Cannot import modpack without LauncherActivity");
            ((LauncherActivity) launcheractivity).modpackImportLauncher.launch(null);
        });

        // Fix (user report): this page had NO back button at all.
        View backButton = view.findViewById(R.id.mp_back_button);
        if (backButton != null) {
            net.kdt.pojavlaunch.UiMotion.pressFeedback(backButton);
            backButton.setOnClickListener(v -> navigateBack());
        }
        // System back key takes the same path.
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { navigateBack(); }
                });

        net.kdt.pojavlaunch.UiMotion.pressFeedback(
                view.findViewById(R.id.button_browse_modpacks),
                view.findViewById(R.id.button_import_modpack));
        net.kdt.pojavlaunch.UiMotion.revealScreen(view);
        // Create-profile choreography: header settles, both option cards rise in
        // sequence and their icon wells jelly-pop as the focal points.
        view.post(() -> {
            net.kdt.pojavlaunch.UiMotion.fadeInDown(view.findViewById(R.id.profile_header), 40);
            net.kdt.pojavlaunch.UiMotion.heroIn(view.findViewById(R.id.browse_icon));
            net.kdt.pojavlaunch.UiMotion.heroIn(view.findViewById(R.id.import_icon));
            net.kdt.pojavlaunch.UiMotion.slideIn(view.findViewById(R.id.button_browse_modpacks), 1, 90);
            net.kdt.pojavlaunch.UiMotion.slideIn(view.findViewById(R.id.button_import_modpack), 1, 170);
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

    private void tryInstall(Class<? extends Fragment> fragmentClass, String tag){
        if(!hasOnlineProfile()){
            hasNoOnlineProfileDialog(requireActivity());
        } else {
            // Walk up to find MainMenuFragment
            Fragment parent = getParentFragment();
            while (parent != null && !(parent instanceof MainMenuFragment)) {
                parent = parent.getParentFragment();
            }
            if (parent instanceof MainMenuFragment) {
                ((MainMenuFragment) parent).openChildPane(fragmentClass, tag, null);
            } else {
                Tools.swapFragment(requireActivity(), fragmentClass, tag, null);
            }
        }
    }
}