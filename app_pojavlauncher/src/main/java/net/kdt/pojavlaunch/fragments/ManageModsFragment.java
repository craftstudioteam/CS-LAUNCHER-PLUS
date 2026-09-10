package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.appcompat.app.AlertDialog;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;
import net.kdt.pojavlaunch.modloaders.ModUpdateChecker;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;

public class ManageModsFragment extends Fragment
        implements InstalledModAdapter.ModActionListener {

    public static final String TAG = "ManageModsFragment";

    private RecyclerView mRecycler;
    private View mEmptyState;
    public static final String BUNDLE_PROFILE_KEY = "profile_key";

    public ManageModsFragment() {
        super(R.layout.fragment_manage_mods);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Copy is not offered in this build, so the gesture's safety net (open the
        // profile picker when a held drag misses every card) is gone with it: left
        // installed, it could launch a copy picker from a page that has no copy
        // control at all.
        ImageButton backButton  = view.findViewById(R.id.manage_mods_back);
        ImageButton addButton   = view.findViewById(R.id.manage_mods_add);
        TextView    title       = view.findViewById(R.id.manage_mods_title);
        RecyclerView recycler   = view.findViewById(R.id.manage_mods_recycler);
        View        emptyState  = view.findViewById(R.id.manage_mods_empty);
        mRecycler    = recycler;
        mEmptyState  = emptyState;

        // Back — delegate to the activity which handles both portrait (pop activity stack)
        // and landscape two-pane (pop right pane) in one reliable place.
        backButton.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        // Resolve the active profile so we can apply version instance rules.
        MinecraftProfile profile = resolveActiveProfile();
        // Only a truly vanilla instance blocks the mod store. A custom-JAR
        // profile (was: "OptiFine profile") runs mods from its own mods folder,
        // so banning it was just wrong — and OptiFine is no longer offered.
        boolean modsBlocked = profile != null && profile.isVanilla();

        // Add → open mod store — stay in right pane if we're inside one.
        // For vanilla / OptiFine instances the mod store would crash the game,
        // so the entry point is hidden. Users can still browse packs/shaders/worlds
        // through the right-pane home mod-store tab if they need to.
        if (modsBlocked) {
            addButton.setVisibility(View.GONE);
        } else {
            addButton.setOnClickListener(v -> {
                Bundle args = new Bundle();
                args.putString(BUNDLE_PROFILE_KEY, getProfileKey());
                navigateToFragment(ModsSearchFragment.class, ModsSearchFragment.TAG, args);
            });
        }

        // Title: "ProfileName - Mods"
        String profileName = getProfileName();
        title.setText(profileName.isEmpty()
                ? getString(R.string.mcl_button_manage_mods)
                : profileName + " - Mods");

        // Build mod list — crossfade between the list and the empty state so
        // installs/removals never snap.
        File modsDir = getModsDir();
        InstalledModAdapter adapter = new InstalledModAdapter(modsDir, isEmpty -> {
            View show = isEmpty ? emptyState : recycler;
            View hide = isEmpty ? recycler : emptyState;
            if (show.getVisibility() != View.VISIBLE) {
                show.setAlpha(0f);
                show.setTranslationY(10f * getResources().getDisplayMetrics().density);
                show.setVisibility(View.VISIBLE);
                show.animate().alpha(1f).translationY(0f).setDuration(260)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                        .withEndAction(() -> { show.setAlpha(1f); show.setTranslationY(0f); })
                        .start();
            }
            if (hide.getVisibility() == View.VISIBLE) hide.setVisibility(View.GONE);
        }, this);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Smooth add/remove/change item animations for install, delete and toggle.
        androidx.recyclerview.widget.DefaultItemAnimator itemAnimator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        itemAnimator.setAddDuration(240);
        itemAnimator.setRemoveDuration(200);
        itemAnimator.setMoveDuration(240);
        itemAnimator.setChangeDuration(180);
        itemAnimator.setSupportsChangeAnimations(false); // toggle repaints in place — no ghost flicker
        recycler.setItemAnimator(itemAnimator);
        recycler.setAdapter(adapter);

        // Page entrance — header drops in, actions get springy press feedback.
        View header = view.findViewById(R.id.manage_mods_header);
        if (header != null) net.kdt.pojavlaunch.UiMotion.fadeInDown(header, 0);
        net.kdt.pojavlaunch.UiMotion.pressFeedback(backButton, addButton);
    }

    /** Resolve the active profile either via the BUNDLE_PROFILE_KEY arg or the global pref. */
    private MinecraftProfile resolveActiveProfile() {
        try {
            String key = getProfileKey();
            if (key == null || key.isEmpty()) return null;
            LauncherProfiles.load();
            return LauncherProfiles.mainProfileJson.profiles.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String getProfileName() {
        try {
            String key = getProfileKey();
            if (key == null || key.isEmpty()) return "";
            LauncherProfiles.load();
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (profile == null) return "";
            return profile.name != null ? profile.name : key;
        } catch (Exception e) {
            return "";
        }
    }

    private File getModsDir() {
        try {
            String key = getProfileKey();
            if (key != null && !key.isEmpty()) {
                LauncherProfiles.load();
                MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(key);
                if (profile != null) {
                    File gameDir = Tools.getGameDirPath(profile);
                    return new File(gameDir, "mods");
                }
            }
        } catch (Exception ignored) {}
        return new File(Tools.DIR_GAME_NEW, "mods");
    }

    private String getProfileKey() {
        Bundle args = getArguments();
        if (args != null && args.containsKey(BUNDLE_PROFILE_KEY)) {
            return args.getString(BUNDLE_PROFILE_KEY);
        }
        return LauncherPreferences.DEFAULT_PREF
                .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
    }

    /** Go back — pops the parent's child stack synchronously when inside right pane. */
    private void navigateBack() {
        Fragment parent = getParentFragment();
        if (parent != null) {
            // Synchronous pop — no race condition with the view lifecycle
            parent.getChildFragmentManager().popBackStackImmediate();
        } else {
            Tools.removeCurrentFragment(requireActivity());
        }
    }

    /** Navigate to a fragment — stays inside the right pane when running as a child fragment. */
    private void navigateToFragment(Class<? extends Fragment> fragmentClass, String tag, Bundle args) {
        Fragment parent = getParentFragment();
        if (parent != null) {
            parent.getChildFragmentManager()
                    .beginTransaction()
                    .setCustomAnimations(
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[0],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[1],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[2],
                            net.kdt.pojavlaunch.utils.animation.MotionTransitions.current()[3])
                    .setReorderingAllowed(true)
                    .replace(R.id.right_pane_container, fragmentClass, args, tag)
                    .addToBackStack(tag)
                    .commit();
        } else {
            Tools.swapFragment(requireActivity(), fragmentClass, tag, args);
        }
    }

    // ══ Per-mod actions ═════════════════════════════════════════════════════

    /**
     * COPY IS NOT OFFERED IN THIS BUILD. The adapter's badge is removed, so nothing
     * reaches here from the UI — but the interface still declares the callback, so
     * the override stays as an explicit refusal rather than a path that half-works:
     * silently falling through to the picker would resurrect the exact workflow the
     * user asked to have taken out.
     */
    @Override
    public void onCopyMod(java.io.File modFile) {
        // intentionally not openCopyTarget(modFile)
    }

    @Override
    public void onDestroyView() {
        if (net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag.onMissedDrop != null) {
            net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag.onMissedDrop = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onCopyModDragStarted(java.io.File modFile) {
        // nothing to start: the copy gesture is not offered (see onCopyMod)
    }

    /** The hold only pays off while the home profile list is on screen too. */
    @Override
    public boolean canCarryMod() {
        android.app.Activity a = getActivity();
        if (a == null) return false;
        View home = a.findViewById(net.kdt.pojavlaunch.R.id.rv_home_profiles);
        if (home == null || home.getVisibility() != View.VISIBLE || !home.isShown()) return false;
        return home.getWindowToken() != null;
    }

    /** Called from onCopyMod when a held drag ended without a drop target. */
    private void openCopyTarget(java.io.File modFile) {
        // Two ways in: a short tap, or a hold whose drag never landed on a
        // profile card. Both end on this page — the second one with the jar
        // already back on the finger, so the user can drop it here instead.
        boolean rescue = modFile != null &&
                net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag.pendingDragFallback == modFile;
        if (rescue) {
            net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag.handoffToPicker = true;
        }
        navigateToFragment(ModCopyTargetFragment.class, ModCopyTargetFragment.TAG,
                ModCopyTargetFragment.args(modFile.getAbsolutePath(), getProfileKey()));
    }

    /** Copy a jar straight into another profile (used by the home drop target). */
    public void copyModIntoProfile(java.io.File modFile, String targetProfileKey) {
        // InstalledModCopy.copy runs off the main thread and calls back there.
        final Context cbCtx = requireContext().getApplicationContext();
        java.io.File dir = net.kdt.pojavlaunch.modloaders.InstalledModCopy
                .modsDirFor(targetProfileKey);
        if (modFile == null || dir == null) {
            toast(R.string.mod_copy_failed);
            return;
        }
        final java.io.File dest = new java.io.File(dir, modFile.getName());
        net.kdt.pojavlaunch.modloaders.InstalledModCopy.copyWithProgress(
                modFile, dest, (ok, d) -> {
            android.app.Activity a = getActivity();
            if (a == null) return;
            a.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (ok) net.kdt.pojavlaunch.modloaders.InstalledModCopy.toast(cbCtx,
                                getString(R.string.mod_copy_done,
                                        profileDisplayName(targetProfileKey)));
                else toast(R.string.mod_copy_failed);
            });
        }, dup -> {
            // The profile already had this exact jar: say "already there" rather
            // than "copied", which was the little lie that made people copy the
            // same mod four times and wonder why nothing changed.
            if (dup == null) return;
            android.app.Activity a = getActivity();
            if (a == null) return;
            a.runOnUiThread(() -> {
                if (!isAdded()) return;
                net.kdt.pojavlaunch.modloaders.InstalledModCopy.toast(cbCtx,
                        getString(R.string.mod_copy_already_there,
                                profileDisplayName(targetProfileKey), dup.getName()));
            });
        });
    }

    private String profileDisplayName(String key) {
        try {
            LauncherProfiles.load();
            MinecraftProfile p = LauncherProfiles.mainProfileJson.profiles.get(key);
            if (p != null && p.name != null) return p.name;
        } catch (Throwable ignored) {}
        return key;
    }

    /** Real Modrinth lookup: hashes the jar and compares publish dates. */
    @Override
    public void onCheckUpdate(java.io.File modFile) {
        Context ctx = getContext();
        if (ctx == null) return;
        MinecraftProfile p = resolveActiveProfile();
        String mcVersion = p != null ? p.lastVersionId : null;
        String loader    = p != null ? p.type : null;

        AlertDialog wait = new AlertDialog.Builder(ctx)
                .setTitle(R.string.mod_update_checking_title)
                .setMessage(getString(R.string.mod_update_checking, modFile.getName()))
                .setCancelable(false)
                .show();

        PojavApplication.sExecutorService.execute(() ->
                ModUpdateChecker.check(modFile, mcVersion, loader, result -> {
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(() -> {
                        try { wait.dismiss(); } catch (Throwable ignored) {}
                        if (isAdded()) showUpdateResult(modFile, result);
                    });
                }));
    }

    private void showUpdateResult(java.io.File modFile, ModUpdateChecker.Result r) {
        Context ctx = getContext();
        if (ctx == null) return;
        switch (r.state) {
            case UPDATE_AVAILABLE:
                new AlertDialog.Builder(ctx)
                        .setTitle(R.string.mod_update_available_title)
                        .setMessage(getString(R.string.mod_update_available_message,
                                r.installedVersion, r.latestVersion))
                        .setNegativeButton(R.string.mod_update_later, null)
                        .setPositiveButton(R.string.mod_update_install,
                                (d, w) -> installUpdate(modFile, r))
                        .show();
                break;
            case UP_TO_DATE:
                Toast.makeText(ctx, R.string.mod_update_up_to_date, Toast.LENGTH_SHORT).show();
                break;
            case NOT_FOUND:
                Toast.makeText(ctx, R.string.mod_update_not_found, Toast.LENGTH_LONG).show();
                break;
            default:
                Toast.makeText(ctx, R.string.mod_update_error, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    private void installUpdate(java.io.File oldFile, ModUpdateChecker.Result r) {
        java.io.File dir = getModsDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            toast(R.string.mod_update_failed);
            return;
        }
        boolean wasDisabled = oldFile.getName().endsWith(".disabled");
        String name = r.fileName != null && !r.fileName.isEmpty()
                ? r.fileName : oldFile.getName();
        if (name.endsWith(".disabled")) name = name.substring(0, name.length() - ".disabled".length());
        if (wasDisabled) name = name + ".disabled";

        java.io.File target = new java.io.File(dir, name);
        java.io.File tmp    = new java.io.File(dir, name + ".part");
        final String latest = r.latestVersion;

        PojavApplication.sExecutorService.execute(() -> {
            boolean ok;
            try {
                DownloadUtils.downloadFileMonitored(r.downloadUrl, tmp, null,
                        new DownloaderProgressWrapper(
                                R.string.modpack_download_downloading_mods,
                                com.kdt.mcgui.ProgressLayout.INSTALL_MODPACK,
                                oldFile.getName(), latest, null, "mod"));
                ok = tmp.isFile() && tmp.length() > 0;
                if (ok) {
                    if (oldFile.exists() && !oldFile.getAbsolutePath().equals(target.getAbsolutePath())) {
                        //noinspection ResultOfMethodCallIgnored
                        oldFile.delete();
                    }
                    ok = tmp.renameTo(target);
                }
            } catch (Throwable t) {
                ok = false;
            }
            final boolean result = ok;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                if (result) {
                    toast(R.string.mod_update_done);
                    refreshList();
                } else {
                    toast(R.string.mod_update_failed);
                }
            });
        });
    }

    /** Rebuilds the list after an install/removal so sizes and names are current. */
    private void refreshList() {
        if (mRecycler == null) return;
        InstalledModAdapter adapter = new InstalledModAdapter(getModsDir(), isEmpty -> {
            View show = isEmpty ? mEmptyState : mRecycler;
            View hide = isEmpty ? mRecycler : mEmptyState;
            if (show != null) show.setVisibility(View.VISIBLE);
            if (hide != null) hide.setVisibility(View.GONE);
        }, this);
        mRecycler.setAdapter(adapter);
    }

    private void toast(int res) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String msg) {
        Context ctx = getContext();
        if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
}