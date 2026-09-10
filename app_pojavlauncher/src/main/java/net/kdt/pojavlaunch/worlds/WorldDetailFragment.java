package net.kdt.pojavlaunch.worlds;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.fragments.DatapackBrowserFragment;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/** Complete full-screen World Studio. No popup shell; all original actions retained. */
public class WorldDetailFragment extends Fragment {
    public static final String TAG = "WORLD_DETAIL_FRAGMENT";
    public static final String ARG_WORLD = "world_path";
    public static final String ARG_GAME_DIR = "world_game_dir";
    public static final String ARG_PROFILE_KEY = "world_profile_key";
    public static final String ARG_PROFILE_NAME = "world_profile_name";

    private WorldEntry world;
    private File gameDir;
    private String profileKey, profileName;
    private ImageView icon;
    private EditText name;
    private TextView title, subtitle, status, identity, identityMeta;
    private TextView version, size, seed, backups, lastPlayed, path;
    private ProgressBar progress;
    private WorldEntry pendingExport;

    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::changeWorldIcon);
    private final ActivityResultLauncher<String> exportPicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri != null && pendingExport != null) {
                    setBusy(true, "EXPORTING");
                    WorldOps.exportWorld(requireContext(), pendingExport, uri, callback(false, true));
                }
                pendingExport = null;
            });

    public WorldDetailFragment() { super(R.layout.fragment_world_detail); }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Bundle args = getArguments();
        String p = args != null ? args.getString(ARG_WORLD) : null;
        world = new WorldEntry(new File(p == null ? "" : p));
        String gd = args != null ? args.getString(ARG_GAME_DIR) : null;
        gameDir = new File(gd == null ? world.folder.getParentFile().getParent() : gd);
        profileKey = args != null ? args.getString(ARG_PROFILE_KEY) : null;
        profileName = args != null ? args.getString(ARG_PROFILE_NAME) : null;
    }

    @Override public void onViewCreated(@NonNull View root, @Nullable Bundle state) {
        icon = root.findViewById(R.id.world_studio_icon);
        name = root.findViewById(R.id.world_studio_name);
        title = root.findViewById(R.id.world_studio_title);
        subtitle = root.findViewById(R.id.world_studio_subtitle);
        status = root.findViewById(R.id.world_studio_status);
        identity = root.findViewById(R.id.world_studio_identity);
        identityMeta = root.findViewById(R.id.world_studio_identity_meta);
        version = root.findViewById(R.id.world_studio_version);
        size = root.findViewById(R.id.world_studio_size);
        seed = root.findViewById(R.id.world_studio_seed);
        backups = root.findViewById(R.id.world_studio_backup_count);
        lastPlayed = root.findViewById(R.id.world_studio_lastplayed);
        path = root.findViewById(R.id.world_studio_path);
        progress = root.findViewById(R.id.world_studio_progress);

        root.findViewById(R.id.world_studio_back).setOnClickListener(v -> back());
        root.findViewById(R.id.world_studio_change_image).setOnClickListener(v -> imagePicker.launch("image/*"));
        root.findViewById(R.id.world_studio_play).setOnClickListener(v -> playWorld());
        root.findViewById(R.id.world_studio_datapacks).setOnClickListener(v -> openDatapacks());
        root.findViewById(R.id.world_studio_open_folder).setOnClickListener(v -> openFolder());
        root.findViewById(R.id.world_studio_save_name).setOnClickListener(v -> rename());
        root.findViewById(R.id.world_studio_backup).setOnClickListener(v -> backup());
        root.findViewById(R.id.world_studio_restore).setOnClickListener(v -> restore());
        root.findViewById(R.id.world_studio_export).setOnClickListener(v -> export());
        root.findViewById(R.id.world_studio_duplicate).setOnClickListener(v -> duplicate());
        root.findViewById(R.id.world_studio_compress).setOnClickListener(v -> compress());
        root.findViewById(R.id.world_studio_copy_path).setOnClickListener(v -> copyPath());
        root.findViewById(R.id.world_studio_delete).setOnClickListener(v -> delete());

        UiMotion.pressFeedback(root.findViewById(R.id.world_studio_back),
                root.findViewById(R.id.world_studio_change_image), root.findViewById(R.id.world_studio_play),
                root.findViewById(R.id.world_studio_datapacks), root.findViewById(R.id.world_studio_open_folder),
                root.findViewById(R.id.world_studio_save_name), root.findViewById(R.id.world_studio_backup),
                root.findViewById(R.id.world_studio_restore), root.findViewById(R.id.world_studio_export),
                root.findViewById(R.id.world_studio_duplicate), root.findViewById(R.id.world_studio_compress),
                root.findViewById(R.id.world_studio_copy_path), root.findViewById(R.id.world_studio_delete));
        loadWorld();
        UiMotion.revealScreen(root);
    }

    private void loadWorld() {
        setBusy(true, "SCANNING");
        PojavApplication.sExecutorService.execute(() -> {
            WorldOps.enrich(Collections.singletonList(world));
            List<File> saved = WorldOps.listBackups(world, gameDir);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                title.setText("WORLD STUDIO");
                subtitle.setText(world.displayName + (profileName == null ? "" : "  •  " + profileName));
                identity.setText(world.displayName);
                identityMeta.setText(world.folderName + "  •  " + WorldOps.formatLastPlayed(world.lastPlayedMs));
                name.setText(world.displayName);
                version.setText("VERSION\n" + val(world.versionName));
                size.setText("SIZE\n" + WorldOps.formatSize(world.sizeBytes));
                seed.setText("SEED\n" + (world.hasSeed ? String.valueOf(world.seed) : "—"));
                backups.setText("BACKUPS\n" + saved.size());
                lastPlayed.setText("Last played  " + WorldOps.formatLastPlayed(world.lastPlayedMs)
                        + "   •   Datapacks  " + Math.max(0, world.datapackCount));
                path.setText(world.folder.getAbsolutePath());
                loadIcon(); setBusy(false, "READY");
            });
        });
    }

    private void loadIcon() {
        File f = world.iconFile();
        Bitmap b = f != null ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b != null) { icon.clearColorFilter(); icon.setImageBitmap(b); }
        else { icon.setImageResource(R.drawable.ic_nav_worlds); icon.setColorFilter(0xFF777984); }
    }

    private void rename() {
        String next = name.getText() == null ? "" : name.getText().toString().trim();
        if (next.isEmpty() || next.equals(world.displayName)) return;
        setBusy(true, "RENAMING");
        WorldOps.renameWorld(world, next, callback(true, true));
    }
    private void backup() { setBusy(true, "BACKING UP"); WorldOps.backupWorld(world, gameDir, callback(false, true)); }
    private void duplicate() { setBusy(true, "DUPLICATING"); WorldOps.duplicateWorld(world, callback(false, false)); }
    private void export() { pendingExport = world; exportPicker.launch(world.folderName + ".zip"); }

    private void restore() {
        List<File> files = WorldOps.listBackups(world, gameDir);
        if (files.isEmpty()) { Toast.makeText(requireContext(), R.string.cs_world_no_backups, Toast.LENGTH_LONG).show(); return; }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) labels[i] = files.get(i).getName() + "  •  " + WorldOps.formatSize(files.get(i).length());
        new AlertDialog.Builder(requireContext()).setTitle(R.string.cs_world_restore)
                .setItems(labels, (d, which) -> { setBusy(true, "RESTORING"); WorldOps.restoreBackup(files.get(which), world.folder.getParentFile(), callback(false, true)); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void compress() {
        String[] choices = { getString(R.string.cs_world_compress_keep), getString(R.string.cs_world_compress_remove) };
        new AlertDialog.Builder(requireContext()).setTitle(R.string.cs_world_compress)
                .setItems(choices, (d, which) -> { setBusy(true, "COMPRESSING"); WorldOps.compressWorld(world, gameDir, which == 1, callback(which == 1, false)); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void delete() {
        new AlertDialog.Builder(requireContext()).setTitle(R.string.cs_world_delete)
                .setMessage(getString(R.string.cs_world_delete_confirm, world.displayName))
                .setPositiveButton(R.string.global_delete, (d, w) -> { setBusy(true, "DELETING"); WorldOps.deleteWorld(world, callback(true, false)); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openDatapacks() {
        Bundle b = new Bundle();
        b.putString(DatapackBrowserFragment.BUNDLE_WORLD_DIR, world.folder.getAbsolutePath());
        b.putString(DatapackBrowserFragment.BUNDLE_WORLD_NAME, world.displayName);
        b.putString(DatapackBrowserFragment.BUNDLE_WORLD_FOLDER, world.folderName);
        b.putString(DatapackBrowserFragment.BUNDLE_PROFILE_KEY, profileKey);
        Tools.swapFragment(requireActivity(), DatapackBrowserFragment.class, DatapackBrowserFragment.TAG, b);
    }

    private void openFolder() {
        try { Tools.openPath(requireContext(), world.folder, false); }
        catch (Throwable t) { copyPath(); }
    }

    private void copyPath() {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("world", world.folder.getAbsolutePath()));
        Toast.makeText(requireContext(), R.string.cs_path_copied, Toast.LENGTH_SHORT).show();
    }

    private void playWorld() {
        Activity act = getActivity(); if (act == null) return;
        try {
            if (profileKey != null) LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey).commit();
            LauncherProfiles.load();
            MinecraftProfile profile = profileKey == null ? null : LauncherProfiles.mainProfileJson.profiles.get(profileKey);
            if (profile == null) { Toast.makeText(requireContext(), R.string.cs_world_play_no_profile, Toast.LENGTH_LONG).show(); return; }
            String normalized = AsyncMinecraftDownloader.normalizeVersionId(profile.lastVersionId);
            JMinecraftVersionList.Version listed = AsyncMinecraftDownloader.getListedVersion(normalized);
            new MinecraftDownloader().start(act, listed, normalized, new ContextAwareDoneListener(act, normalized));
        } catch (Throwable t) { Tools.showError(requireContext(), t); }
    }

    private void changeWorldIcon(Uri uri) {
        if (uri == null) return; setBusy(true, "SAVING IMAGE");
        PojavApplication.sExecutorService.execute(() -> {
            boolean ok = false;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                Bitmap src = BitmapFactory.decodeStream(in); if (src == null) throw new Exception();
                int side = Math.min(src.getWidth(), src.getHeight()); int sx = (src.getWidth()-side)/2, sy=(src.getHeight()-side)/2;
                Bitmap outBmp = Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888);
                new Canvas(outBmp).drawBitmap(src,new Rect(sx,sy,sx+side,sy+side),new Rect(0,0,128,128),null);
                try(FileOutputStream out=new FileOutputStream(new File(world.folder,"icon.png"))){outBmp.compress(Bitmap.CompressFormat.PNG,100,out);} ok=true;
            } catch(Throwable ignored){}
            boolean saved=ok; if(!isAdded())return; requireActivity().runOnUiThread(()->{setBusy(false,saved?"IMAGE SAVED":"FAILED");if(saved)loadIcon();Toast.makeText(requireContext(),saved?"World image changed":"Unsupported image",Toast.LENGTH_SHORT).show();});
        });
    }

    private WorldOps.OpCallback callback(boolean leave, boolean reload) {
        return new WorldOps.OpCallback() {
            @Override public void onProgress(int pct, @Nullable String msg) { if (isAdded()) status.setText(msg == null ? "WORKING" : msg.toUpperCase()); }
            @Override public void onDone(boolean ok, @NonNull String msg) {
                if (!isAdded()) return; setBusy(false, ok ? "SAVED" : "FAILED"); Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show();
                if (ok && leave) back(); else if (ok && reload) loadWorld();
            }
        };
    }

    private void setBusy(boolean busy, String label) { if(progress!=null)progress.setVisibility(busy?View.VISIBLE:View.GONE);if(status!=null)status.setText(label); }
    private void back() { requireActivity().getSupportFragmentManager().popBackStack(); }
    private static String val(String s) { return s == null || s.isEmpty() ? "—" : s; }
}
