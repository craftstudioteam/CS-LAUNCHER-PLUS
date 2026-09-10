package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Copy mod to another profile" — picks a target profile and copies the jar on
 * disk into that profile's mods folder. Nothing is downloaded again, and the
 * disabled state (the ".disabled" suffix) travels with the file.
 */
public class ModCopyTargetFragment extends Fragment {

    public static final String TAG = "ModCopyTargetFragment";
    public static final String ARG_MOD_PATH = "mod_path";
    public static final String ARG_SOURCE_PROFILE = "source_profile";

    private final List<Target> mTargets = new ArrayList<>();
    private File mModFile;
    private String mSourceKey;

    public ModCopyTargetFragment() { super(R.layout.fragment_mod_copy_target); }

    public static Bundle args(String modPath, String sourceProfileKey) {
        Bundle b = new Bundle(2);
        b.putString(ARG_MOD_PATH, modPath);
        b.putString(ARG_SOURCE_PROFILE, sourceProfileKey);
        return b;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle a = getArguments();
        String path = a != null ? a.getString(ARG_MOD_PATH) : null;
        mSourceKey = a != null ? a.getString(ARG_SOURCE_PROFILE) : null;
        mModFile = path != null ? new File(path) : null;

        ImageButton back = view.findViewById(R.id.copy_target_back);
        TextView title = view.findViewById(R.id.copy_target_title);
        TextView subtitle = view.findViewById(R.id.copy_target_subtitle);
        RecyclerView recycler = view.findViewById(R.id.copy_target_recycler);
        TextView empty = view.findViewById(R.id.copy_target_empty);

        back.setOnClickListener(v -> requireActivity()
                .getOnBackPressedDispatcher().onBackPressed());
        net.kdt.pojavlaunch.UiMotion.pressFeedback(back);

        if (mModFile != null) {
            subtitle.setText(mModFile.getName() + "  ·  tap a profile to copy");
        }

        loadTargets();
        title.setText(getString(R.string.mod_copy_title));

        if (mTargets.isEmpty()) {
            recycler.setVisibility(View.GONE);
            empty.setVisibility(View.VISIBLE);
        } else {
            recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
            recycler.setAdapter(new TargetAdapter());
            attachCarryTarget(recycler);
        }

        View header = view.findViewById(R.id.copy_target_header);
        if (header != null) net.kdt.pojavlaunch.UiMotion.fadeInDown(header, 0);

        // Rescue path: the user HELD copy, dragged, and released on nothing —
        // the picker is how they still finish it. Re-arm the carry on this page
        // so they can drop onto a row here, exactly as they tried to do outside.
        if (net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag.handoffToPicker) {
            net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag.handoffToPicker = false;
            final android.view.View host = recycler;
            host.post(() -> {
                if (mModFile == null || !isAdded() || !host.isAttachedToWindow()) return;
                net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag
                        .pendingDragFallback = mModFile;
                net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag
                        .start(host, mModFile, null);
                if (subtitle != null) {
                    subtitle.setText(mModFile.getName()
                            + "  ·  drop on a profile, or tap it");
                }
            });
        }
    }

    /** Rows light up green under a carried jar; a drop copies into that profile. */
    private void attachCarryTarget(RecyclerView recycler) {
        net.kdt.pojavlaunch.modloaders.modpacks.ModCopyDrag
                .attachDropTarget(recycler, (row, file) -> {
                    int pos = recycler.getChildAdapterPosition(row);
                    if (pos < 0 || pos >= mTargets.size()) return;
                    copyTo(mTargets.get(pos));
                });
    }

    // ── Data ────────────────────────────────────────────────────────────────

    private void loadTargets() {
        mTargets.clear();
        try {
            LauncherProfiles.load();
            Map<String, MinecraftProfile> profiles = LauncherProfiles.mainProfileJson.profiles;
            if (profiles == null) return;
            for (Map.Entry<String, MinecraftProfile> e : profiles.entrySet()) {
                String key = e.getKey();
                MinecraftProfile p = e.getValue();
                if (key == null || p == null) continue;
                if (key.equals(mSourceKey)) continue;                  // already here
                String reason = null;
                if (p.isVanilla()) reason = getString(R.string.mod_copy_blocked_vanilla);
                else if (p.isOptiFine()) reason = getString(R.string.mod_copy_blocked_optifine);
                mTargets.add(new Target(key, p, reason));
            }
            mTargets.sort((x, y) -> {
                if (x.blockedReason != null && y.blockedReason == null) return 1;
                if (x.blockedReason == null && y.blockedReason != null) return -1;
                return safe(x.profile.name).compareToIgnoreCase(safe(y.profile.name));
            });
        } catch (Throwable ignored) {}
    }

    private static String safe(String s) { return s == null ? "" : s; }

    // ── Copy ────────────────────────────────────────────────────────────────

    private void copyTo(Target target) {
        if (mModFile == null || !mModFile.isFile()) return;
        if (target.blockedReason != null) {
            toast(target.blockedReason);
            return;
        }
        File dir = new File(Tools.getGameDirPath(target.profile), "mods");
        final File dest = new File(dir, mModFile.getName());

        if (dest.exists()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mod_copy_exists_title)
                    .setMessage(getString(R.string.mod_copy_exists_message,
                            target.profile.name, dest.getName()))
                    .setNegativeButton(R.string.mod_copy_skip, null)
                    .setPositiveButton(R.string.mod_copy_replace, (d, w) -> doCopy(target, dest))
                    .show();
        } else {
            doCopy(target, dest);
        }
    }

    /**
     * The one copy path. It used to be a second, hand-written File-to-File loop
     * here — same job as InstalledModCopy.copy, but without the .part + rename,
     * without the size check, and without progress, so a big modpack jar could
     * land half-copied and the shade said nothing while it happened.
     */
    private void doCopy(Target target, File dest) {
        File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            toast(R.string.mod_copy_failed);
            return;
        }
        net.kdt.pojavlaunch.modloaders.InstalledModCopy.copyWithProgress(
                mModFile, dest, (ok, d) -> {
            android.app.Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (ok) {
                    toast(getString(R.string.mod_copy_done, target.profile.name));
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                } else {
                    toast(R.string.mod_copy_failed);
                }
            });
        }, dup -> {
            android.app.Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (dup != null) {
                    toast(getString(R.string.mod_copy_already_there,
                            target.profile.name, dup.getName()));
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            });
        });
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(requireContext(), msg,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void toast(int res) { toast(getString(res)); }

    // ── Adapter ─────────────────────────────────────────────────────────────

    private class TargetAdapter extends RecyclerView.Adapter<TargetAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_copy_target_profile, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Target t = mTargets.get(pos);
            h.name.setText(safe(t.profile.name));
            String mc = t.profile.lastVersionId == null ? "—" : t.profile.lastVersionId;
            String type = t.profile.type == null ? "" : t.profile.type;
            if (t.blockedReason != null) {
                h.meta.setText(mc + " • " + t.blockedReason);
                h.meta.setTextColor(0xFFFF8A8A);
                h.itemView.setAlpha(0.45f);
                h.itemView.setOnClickListener(v -> toast(t.blockedReason));
            } else {
                String loader = loaderLabel(t.profile.lastVersionId);
                StringBuilder meta = new StringBuilder();
                meta.append("Minecraft ").append(mc);
                if (!type.isEmpty()) meta.append(" • ").append(type);
                if (loader != null) meta.append(" • ").append(loader);
                ModDirInfo info = inspectDir(t);
                if (info != null) {
                    meta.append(info.modCount > 0
                            ? " • " + info.modCount + " mods installed"
                            : " • no mods yet");
                }
                h.meta.setText(meta);
                h.meta.setTextColor(0xFF8D93A1);
                h.itemView.setAlpha(1f);
                if (info != null && info.alreadyHasCurrent) {
                    // The warning the page was missing: "I copied it and nothing
                    // happened" is almost always this, said out loud.
                    h.meta.append("\n• already contains " + mModFile.getName());
                    h.meta.setTextColor(0xFFF0B26B);
                }
                h.itemView.setOnClickListener(v -> copyTo(t));
            }
            net.kdt.pojavlaunch.UiMotion.pressFeedback(h.itemView);
        }

        @Override public int getItemCount() { return mTargets.size(); }

        private ModDirInfo inspectDir(Target t) {
            if (mModFile == null) return null;
            try {
                File dir = new File(Tools.getGameDirPath(t.profile), "mods");
                if (!dir.isDirectory()) return new ModDirInfo(0, false);
                File[] kids = dir.listFiles();
                int count = 0;
                boolean has = false;
                if (kids != null) {
                    for (File f : kids) {
                        if (f == null || !f.isFile()) continue;
                        String n = f.getName();
                        if (n.endsWith(".part")) continue;
                        count++;
                        if (n.equals(mModFile.getName())
                                || n.equals(mModFile.getName() + ".disabled")) has = true;
                    }
                }
                return new ModDirInfo(count, has);
            } catch (Throwable e) {
                return null;
            }
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name, meta;
            VH(@NonNull View v) {
                super(v);
                name = v.findViewById(R.id.copy_row_name);
                meta = v.findViewById(R.id.copy_row_meta);
            }
        }
    }

    /** "Fabric 0.15" / "Forge" from the version id the profile actually runs. */
    private static String loaderLabel(String versionId) {
        if (versionId == null) return null;
        String v = versionId.toLowerCase();
        if (v.contains("neoforge")) return "NeoForge";
        if (v.contains("fabric")) return "Fabric";
        if (v.contains("forge")) return "Forge";
        if (v.contains("optifine")) return "OptiFine";
        if (v.contains("quilt")) return "Quilt";
        return null;
    }

    private static final class ModDirInfo {
        final int modCount;
        final boolean alreadyHasCurrent;
        ModDirInfo(int modCount, boolean alreadyHasCurrent) {
            this.modCount = modCount;
            this.alreadyHasCurrent = alreadyHasCurrent;
        }
    }

    private static final class Target {
        final String key;
        final MinecraftProfile profile;
        final String blockedReason;
        Target(String key, MinecraftProfile profile, String blockedReason) {
            this.key = key;
            this.profile = profile;
            this.blockedReason = blockedReason;
        }
    }
}
