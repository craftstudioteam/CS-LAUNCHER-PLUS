package net.kdt.pojavlaunch.worlds;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.fragments.DatapackBrowserFragment;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.lifecycle.ContextAwareDoneListener;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncMinecraftDownloader;
import net.kdt.pojavlaunch.tasks.MinecraftDownloader;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;

/**
 * WORLD MANAGER (Phase 2) — manage every Minecraft world of a profile/game
 * directory from inside the launcher: list, search, sort, full world info
 * (level.dat NBT), play, rename, duplicate, backup / restore, export, import,
 * compress, delete, open-folder, and a Modrinth-powered Datapack Browser per
 * world.
 */
public class WorldManagerFragment extends Fragment implements WorldListAdapter.Listener {

    public static final String TAG = "WORLD_MANAGER_FRAGMENT";
    public static final String BUNDLE_GAME_DIR = "wm_game_dir";
    public static final String BUNDLE_PROFILE_KEY = "wm_profile_key";
    public static final String BUNDLE_PROFILE_NAME = "wm_profile_name";

    private File mGameDir;
    private File mSavesDir;
    private String mProfileKey;
    private String mProfileName;

    private WorldListAdapter mAdapter;
    private RecyclerView mList;
    private View mEmptyState;
    private TextView mEmptyText;
    private TextView mCountChip;
    private TextView mSubtitle;
    private EditText mSearch;
    private TextView[] mSortChips = new TextView[4];

    // ── Loading / refresh / race-proof scanning (Phase 4 fix) ──
    private View mLoadingState;
    private View mRefreshButton;
    private android.widget.ImageView mRefreshIcon;
    private final android.os.Handler mHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mScanInFlight;
    private boolean mScanPending;
    private int mScanGeneration;
    private static final String PREF_WORLD_SORT = "world_sort_mode";
    /** Search debounce: DiffUtil runs 150ms after the last keystroke, not per key. */
    private final Runnable mSearchDebounce = () -> {
        if (mAdapter != null && mSearch != null) {
            mAdapter.setQuery(mSearch.getText() != null ? mSearch.getText().toString() : "");
        }
    };

    private AlertDialog mProgressDialog;
    private ProgressBar mProgressBar;
    private TextView mProgressText;

    // ── SAF: import a world zip ──
    private final ActivityResultLauncher<Object> mImportLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("zip"), uri -> {
                if (uri != null && mSavesDir != null && getContext() != null) {
                    showProgress(getString(R.string.cs_world_import));
                    WorldOps.importWorld(requireContext(), uri, mSavesDir, opCallback());
                }
            });

    // ── SAF: export target (world remembered between pick + operation) ──
    private WorldEntry mPendingExportWorld;
    private WorldEntry mPendingIconWorld;
    private final ActivityResultLauncher<String> mWorldIconLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null && mPendingIconWorld != null) saveWorldIcon(uri, mPendingIconWorld);
                mPendingIconWorld = null;
            });
    private final ActivityResultLauncher<String> mExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
                if (uri != null && mPendingExportWorld != null && getContext() != null) {
                    showProgress(getString(R.string.cs_world_export));
                    WorldOps.exportWorld(requireContext(), mPendingExportWorld, uri, opCallback());
                }
                mPendingExportWorld = null;
            });

    public WorldManagerFragment() {
        super(R.layout.fragment_world_manager);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        String gameDirPath = args != null ? args.getString(BUNDLE_GAME_DIR) : null;
        mGameDir = gameDirPath != null ? new File(gameDirPath) : new File(Tools.DIR_GAME_NEW);
        mSavesDir = new File(mGameDir, "saves");
        mProfileKey = args != null ? args.getString(BUNDLE_PROFILE_KEY) : null;
        mProfileName = args != null ? args.getString(BUNDLE_PROFILE_NAME) : null;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mSubtitle = view.findViewById(R.id.world_subtitle);
        mCountChip = view.findViewById(R.id.world_count_chip);
        mEmptyState = view.findViewById(R.id.world_empty_state);
        mEmptyText = view.findViewById(R.id.world_empty_text);
        mSearch = view.findViewById(R.id.world_search_input);
        mList = view.findViewById(R.id.world_list);

        mSubtitle.setText(mProfileName != null && !mProfileName.isEmpty()
                ? getString(R.string.cs_world_subtitle_profile, mProfileName)
                : mSavesDir.getAbsolutePath());

        UiMotion.pressFeedback(view.findViewById(R.id.world_back_button),
                view.findViewById(R.id.world_import_button));
        view.findViewById(R.id.world_back_button).setOnClickListener(v -> navigateBack());

        view.findViewById(R.id.world_import_button).setOnClickListener(v -> {
            try { mImportLauncher.launch(null); } catch (Throwable t) { Tools.showError(getContext(), t); }
        });

        // RecyclerView: 2-col grid portrait, 2-3 col landscape, recycling on.
        // Grid = far more worlds visible per screen (user req: list too small
        // at the bottom on phones).
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        int cols = landscape
                ? (getResources().getConfiguration().screenWidthDp >= 960 ? 3 : 2)
                : 2;
        mList.setLayoutManager(new GridLayoutManager(getContext(), cols));
        mList.setHasFixedSize(false);
        mList.setItemViewCacheSize(10);
        mAdapter = new WorldListAdapter(this);
        // Restore the user's last sort choice (prefers persisted mode over default).
        mAdapter.setSortMode(LauncherPreferences.DEFAULT_PREF
                .getInt(PREF_WORLD_SORT, WorldListAdapter.SORT_LAST_PLAYED));
        mList.setAdapter(mAdapter);

        setupSortChips(view);

        // Loading veil + manual refresh button
        mLoadingState = view.findViewById(R.id.world_loading_state);
        mRefreshButton = view.findViewById(R.id.world_refresh_button);
        mRefreshIcon = view.findViewById(R.id.world_refresh_icon);
        if (mRefreshButton != null) {
            UiMotion.pressFeedback(mRefreshButton);
            mRefreshButton.setOnClickListener(v -> reloadWorlds(false));
        }

        // Debounced search: coalesce keystrokes, then run one DiffUtil pass.
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mHandler.removeCallbacks(mSearchDebounce);
                mHandler.postDelayed(mSearchDebounce, 150);
            }
        });

        reloadWorlds(true);
        refreshStorageCard(view);
        UiMotion.revealScreen(view);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Worlds may have been created/deleted in-game or in another page.
        reloadWorlds(false);
    }

    @Override
    public void onDestroyView() {
        if (mProgressDialog != null) mProgressDialog.dismiss();
        mHandler.removeCallbacks(mSearchDebounce);
        stopRefreshSpin();
        mList.setAdapter(null);
        mList = null;
        mAdapter = null;
        mLoadingState = null;
        mRefreshButton = null;
        mRefreshIcon = null;
        super.onDestroyView();
    }

    // ══════════════════════ DATA ══════════════════════

    /**
     * Scan + enrich worlds off-thread with a generation gate.
     * Out-of-order completions (double resume, op-finished callbacks) used to
     * let STALE data overwrite FRESH state — e.g. a deleted world reappeared.
     * Now every call bumps a generation; only the newest scan may touch the UI,
     * and at most one re-scan is queued while one is in flight.
     */
    private void reloadWorlds(boolean firstLoad) {
        mScanGeneration++;
        if (mScanInFlight) {
            mScanPending = true;
            return;
        }
        mScanInFlight = true;
        final int generation = mScanGeneration;
        final boolean showLoading = firstLoad || mAdapter == null || mAdapter.getItemCount() == 0;
        if (showLoading) setLoadingVisible(true);
        startRefreshSpin();
        final File saves = mSavesDir;
        final long startedAt = android.os.SystemClock.elapsedRealtime();
        PojavApplication.sExecutorService.execute(() -> {
            List<WorldEntry> worlds;
            try {
                worlds = WorldOps.scanWorlds(saves);
                if (worlds.isEmpty()) {
                    // Req-15 fallback sweep: the profile the user played may differ
                    // from the profile/directory this manager was opened for. Try
                    // the default instance dir and every known profile game dir,
                    // so real worlds never present as an empty list.
                    worlds = scanFallbackLocations(saves);
                }
                WorldOps.enrich(worlds);
            } catch (Throwable t) {
                android.util.Log.e(TAG, "world scan crashed", t);
                worlds = new java.util.ArrayList<>();
            }
            long elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt;
            android.util.Log.i(TAG, "World scan: " + worlds.size()
                    + " worlds in " + elapsedMs + "ms from " + saves.getAbsolutePath());
            if (!isAdded()) return;
            final List<WorldEntry> finalWorlds = worlds;
            requireActivity().runOnUiThread(() -> {
                mScanInFlight = false;
                stopRefreshSpin();
                if (!isAdded()) return;
                if (generation != mScanGeneration) {
                    // A newer scan superseded this one — drop stale data entirely.
                    runPendingScan();
                    return;
                }
                if (mAdapter != null) mAdapter.submit(finalWorlds);
                setLoadingVisible(false);
                updateCountAndEmpty(mAdapter != null ? mAdapter.getItemCount() : finalWorlds.size());
                refreshStorageCard(requireView());
                runPendingScan();
            });
        });
    }

    private void runPendingScan() {
        if (mScanPending) {
            mScanPending = false;
            reloadWorlds(false);
        }
    }

    /**
     * Req-15: when the primary saves/ dir has no worlds, search every other
     * plausible location before declaring the list empty:
     *  1. the default shared instance dir (DIR_GAME_NEW/saves)
     *  2. every profile's own game dir (custom instances)
     * Results are de-duplicated by absolute path and merged back with the
     * primary location first. Runs off the UI thread (call from executor).
     */
    @NonNull
    private List<WorldEntry> scanFallbackLocations(@NonNull File primarySaves) {
        java.util.LinkedHashMap<String, WorldEntry> merged = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<File> candidates = new java.util.LinkedHashSet<>();
        candidates.add(primarySaves);
        try {
            candidates.add(new File(new File(Tools.DIR_GAME_NEW), "saves"));
        } catch (Throwable ignored) {}
        try {
            if (LauncherProfiles.mainProfileJson != null
                    && LauncherProfiles.mainProfileJson.profiles != null) {
                for (MinecraftProfile profile : LauncherProfiles.mainProfileJson.profiles.values()) {
                    try {
                        File gd = profile != null ? profile.resolveGameDir() : null;
                        if (gd != null) candidates.add(new File(gd, "saves"));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        for (File saves : candidates) {
            try {
                List<WorldEntry> found = WorldOps.scanWorlds(saves);
                android.util.Log.i(TAG, "World scan sweep: " + found.size()
                        + " worlds in " + saves.getAbsolutePath());
                for (WorldEntry w : found) {
                    merged.putIfAbsent(w.stableKey(), w);
                }
                if (!merged.isEmpty()) break; // first non-empty location wins
            } catch (Throwable t) {
                android.util.Log.w(TAG, "sweep failed for " + saves, t);
            }
        }
        return new java.util.ArrayList<>(merged.values());
    }

    private void setLoadingVisible(boolean visible) {
        if (mLoadingState != null) mLoadingState.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && mEmptyState != null) mEmptyState.setVisibility(View.GONE);
    }

    private void startRefreshSpin() {
        if (mRefreshIcon == null || mRefreshIcon.getAnimation() != null) return;
        mRefreshIcon.startAnimation(android.view.animation.AnimationUtils
                .loadAnimation(mRefreshIcon.getContext(), R.anim.world_refresh_spin));
    }

    private void stopRefreshSpin() {
        if (mRefreshIcon != null) mRefreshIcon.clearAnimation();
    }

    private void updateCountAndEmpty(int count) {
        mCountChip.setText(getResources().getQuantityString(
                R.plurals.cs_world_count, count, count));
        boolean empty = count == 0;
        mEmptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            String q = mSearch.getText() != null ? mSearch.getText().toString().trim() : "";
            mEmptyText.setText(q.isEmpty()
                    ? getString(R.string.cs_world_empty)
                    : getString(R.string.cs_world_empty_search, q));
        }
    }

    private void refreshStorageCard(@NonNull View root) {
        PojavApplication.sExecutorService.execute(() -> {
            long[] stats = WorldOps.storageStats(mSavesDir);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                TextView used = root.findViewById(R.id.world_storage_used);
                TextView summary = root.findViewById(R.id.world_storage_summary);
                TextView free = root.findViewById(R.id.world_storage_free);
                TextView total = root.findViewById(R.id.world_storage_total);
                ProgressBar bar = root.findViewById(R.id.world_storage_bar);
                used.setText(WorldOps.formatSize(stats[0]));
                int count = mAdapter != null ? mAdapter.getItemCount() : 0;
                summary.setText(getString(R.string.cs_world_storage_summary,
                        getResources().getQuantityString(R.plurals.cs_world_count, count, count)));
                free.setText(getString(R.string.cs_world_storage_free, WorldOps.formatSize(stats[1])));
                if (stats[2] > 0) {
                    total.setText(getString(R.string.cs_world_storage_total, WorldOps.formatSize(stats[2])));
                    int pct = (int) Math.min(100, stats[0] * 100 / stats[2]);
                    if (android.os.Build.VERSION.SDK_INT >= 24) bar.setProgress(pct, true);
                    else bar.setProgress(pct);
                } else {
                    total.setText("");
                    bar.setProgress(0);
                }
            });
        });
    }

    // ══════════════════════ SORT ══════════════════════

    private void setupSortChips(@NonNull View view) {
        mSortChips[WorldListAdapter.SORT_LAST_PLAYED] = view.findViewById(R.id.world_sort_last_played);
        mSortChips[WorldListAdapter.SORT_NAME] = view.findViewById(R.id.world_sort_name);
        mSortChips[WorldListAdapter.SORT_SIZE] = view.findViewById(R.id.world_sort_size);
        mSortChips[WorldListAdapter.SORT_VERSION] = view.findViewById(R.id.world_sort_version);
        for (int i = 0; i < mSortChips.length; i++) {
            if (mSortChips[i] == null) continue;
            final int mode = i;
            mSortChips[i].setOnClickListener(v -> {
                if (mAdapter != null) mAdapter.setSortMode(mode);
                LauncherPreferences.DEFAULT_PREF.edit().putInt(PREF_WORLD_SORT, mode).apply();
                updateSortChipStyles();
            });
            UiMotion.pressFeedback(mSortChips[i]);
        }
        updateSortChipStyles();
    }

    private void updateSortChipStyles() {
        int active = mAdapter != null ? mAdapter.getSortMode() : WorldListAdapter.SORT_LAST_PLAYED;
        for (int i = 0; i < mSortChips.length; i++) {
            TextView chip = mSortChips[i];
            if (chip == null) continue;
            boolean sel = i == active;
            chip.setBackgroundResource(sel ? R.drawable.bg_cs_pill_active : R.drawable.bg_cs_pill_idle);
            chip.setTextColor(sel ? 0xFF0D0D0D : 0xFF9CA3AF);
        }
    }

    // ══════════════════════ LIST EVENTS ══════════════════════

    @Override
    public void onWorldClick(@NonNull WorldEntry world) {
        openWorldStudio(world);
    }

    @Override
    public void onWorldMenu(@NonNull WorldEntry world, @NonNull View anchor) {
        openWorldStudio(world);
    }

    private void openWorldStudio(@NonNull WorldEntry world) {
        Bundle args = new Bundle();
        args.putString(WorldDetailFragment.ARG_WORLD, world.folder.getAbsolutePath());
        args.putString(WorldDetailFragment.ARG_GAME_DIR, mGameDir.getAbsolutePath());
        args.putString(WorldDetailFragment.ARG_PROFILE_KEY, mProfileKey);
        args.putString(WorldDetailFragment.ARG_PROFILE_NAME, mProfileName);
        Tools.swapFragment(requireActivity(), WorldDetailFragment.class, WorldDetailFragment.TAG, args);
    }

    // ══════════════════════ DETAILS ══════════════════════

    private void showWorldDetails(@NonNull WorldEntry w) {
        if (getContext() == null) return;
        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_world_details, null, false);

        ((TextView) content.findViewById(R.id.wdd_name)).setText(w.displayName);
        ((TextView) content.findViewById(R.id.wdd_folder)).setText(w.folderName);
        ((TextView) content.findViewById(R.id.wdd_version))
                .setText(w.versionName != null ? w.versionName : "—");
        ((TextView) content.findViewById(R.id.wdd_last_played))
                .setText(WorldOps.formatLastPlayed(w.lastPlayedMs));
        ((TextView) content.findViewById(R.id.wdd_size)).setText(WorldOps.formatSize(w.sizeBytes));
        ((TextView) content.findViewById(R.id.wdd_seed))
                .setText(w.hasSeed ? String.valueOf(w.seed) : "—");
        ((TextView) content.findViewById(R.id.wdd_datapacks))
                .setText(w.datapackCount >= 0 ? String.valueOf(w.datapackCount) : "…");
        ((TextView) content.findViewById(R.id.wdd_path)).setText(w.folder.getAbsolutePath());

        android.widget.ImageView icon = content.findViewById(R.id.wdd_icon);
        File iconFile = w.iconFile();
        if (iconFile != null) {
            Bitmap bmp = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
            if (bmp != null) icon.setImageBitmap(bmp);
        } else {
            icon.setImageResource(R.drawable.ic_nav_worlds);
            icon.setColorFilter(0xFF6B7280);
        }

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(content)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_cs_glass_card);
        }

        View.OnClickListener al = v -> {
            int id = v.getId();
            if (id == R.id.wdd_change_image) {
                mPendingIconWorld = w;
                try { mWorldIconLauncher.launch("image/*"); } catch (Throwable t) { Tools.showError(getContext(), t); }
            }
            else if (id == R.id.wdd_play) { dialog.dismiss(); playWorld(w); }
            else if (id == R.id.wdd_datapacks_browse) { dialog.dismiss(); openDatapackBrowser(w); }
            else if (id == R.id.wdd_rename) { dialog.dismiss(); showRenameDialog(w); }
            else if (id == R.id.wdd_duplicate) {
                dialog.dismiss();
                showProgress(getString(R.string.cs_world_duplicate));
                WorldOps.duplicateWorld(w, opCallback());
            }
            else if (id == R.id.wdd_backup) {
                dialog.dismiss();
                showProgress(getString(R.string.cs_world_backup));
                WorldOps.backupWorld(w, mGameDir, opCallback());
            }
            else if (id == R.id.wdd_restore) { dialog.dismiss(); showRestoreDialog(w); }
            else if (id == R.id.wdd_export) {
                dialog.dismiss();
                mPendingExportWorld = w;
                try { mExportLauncher.launch(w.folderName + ".zip"); }
                catch (Throwable t) { Tools.showError(getContext(), t); }
            }
            else if (id == R.id.wdd_compress) { dialog.dismiss(); showCompressDialog(w); }
            else if (id == R.id.wdd_open_folder) { showFolderDialog(w); }
            else if (id == R.id.wdd_delete) { dialog.dismiss(); showDeleteDialog(w); }
        };
        int[] ids = {R.id.wdd_change_image, R.id.wdd_play, R.id.wdd_datapacks_browse, R.id.wdd_rename,
                R.id.wdd_duplicate, R.id.wdd_backup, R.id.wdd_restore, R.id.wdd_export,
                R.id.wdd_compress, R.id.wdd_open_folder, R.id.wdd_delete};
        for (int id : ids) {
            View row = content.findViewById(id);
            if (row != null) {
                row.setOnClickListener(al);
                UiMotion.pressFeedback(row);
            }
        }
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            // Normal launcher dialog — NOT a full-screen takeover. Rounded card
            // background, capped width, wrap height (content scrolls inside).
            window.setBackgroundDrawableResource(R.drawable.bg_cs_dialog);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxW = (int) (520 * dm.density);
            int dlgW = Math.min(maxW, (int) (dm.widthPixels * 0.86f));
            window.setLayout(dlgW, ViewGroup.LayoutParams.WRAP_CONTENT);
            // Keep the immersive system-bar state so bars don't flash in.
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        content.setAlpha(0f);
        content.setTranslationY(18f * getResources().getDisplayMetrics().density);
        content.animate().alpha(1f).translationY(0f).setDuration(260).start();
    }

    private void saveWorldIcon(@NonNull Uri uri, @NonNull WorldEntry target) {
        showProgress("Change World Image");
        PojavApplication.sExecutorService.execute(() -> {
            boolean ok = false;
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                Bitmap source = BitmapFactory.decodeStream(in);
                if (source == null) throw new Exception("Unsupported image");
                int side = Math.min(source.getWidth(), source.getHeight());
                int sx = (source.getWidth() - side) / 2, sy = (source.getHeight() - side) / 2;
                Bitmap output = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(output);
                canvas.drawBitmap(source, new android.graphics.Rect(sx, sy, sx + side, sy + side),
                        new android.graphics.Rect(0, 0, 128, 128), null);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(target.folder, "icon.png"))) {
                    output.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                ok = true;
            } catch (Throwable ignored) {}
            final boolean saved = ok;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (mProgressDialog != null) mProgressDialog.dismiss();
                Toast.makeText(requireContext(), saved ? "World image changed" : "Could not use this image", Toast.LENGTH_LONG).show();
                reloadWorlds(false);
            });
        });
    }

    // ══════════════════════ ACTION DIALOGS ══════════════════════

    private void showRenameDialog(@NonNull WorldEntry w) {
        if (getContext() == null) return;
        final EditText input = new EditText(getContext());
        input.setText(w.displayName);
        input.setSelection(input.getText().length());
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF6B7280);
        input.setBackgroundResource(R.drawable.bg_cs_input_field);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setPadding(pad, pad, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cs_world_rename)
                .setView(wrap)
                .setPositiveButton(R.string.cs_rename, (d, which) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) return;
                    showProgress(getString(R.string.cs_world_rename));
                    WorldOps.renameWorld(w, name, opCallback());
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    private void showDeleteDialog(@NonNull WorldEntry w) {
        if (getContext() == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cs_world_delete)
                .setMessage(getString(R.string.cs_world_delete_confirm, w.displayName))
                .setPositiveButton(R.string.global_delete, (d, which) -> {
                    showProgress(getString(R.string.cs_world_delete));
                    WorldOps.deleteWorld(w, opCallback());
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    private void showCompressDialog(@NonNull WorldEntry w) {
        if (getContext() == null) return;
        String[] options = {
                getString(R.string.cs_world_compress_keep),
                getString(R.string.cs_world_compress_remove)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cs_world_compress)
                .setItems(options, (d, which) -> {
                    showProgress(getString(R.string.cs_world_compress));
                    WorldOps.compressWorld(w, mGameDir, which == 1, opCallback());
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    private void showRestoreDialog(@NonNull WorldEntry w) {
        if (getContext() == null) return;
        java.util.List<File> backups = WorldOps.listBackups(w, mGameDir);
        if (backups.isEmpty()) {
            Toast.makeText(getContext(), R.string.cs_world_no_backups, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) {
            names[i] = backups.get(i).getName() + "   (" + WorldOps.formatSize(backups.get(i).length()) + ")";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cs_world_restore)
                .setItems(names, (d, which) -> {
                    showProgress(getString(R.string.cs_world_restore));
                    WorldOps.restoreBackup(backups.get(which), mSavesDir, opCallback());
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    private void showFolderDialog(@NonNull WorldEntry w) {
        if (getContext() == null) return;
        String path = w.folder.getAbsolutePath();
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cs_world_open_folder)
                .setMessage(path)
                .setPositiveButton(R.string.cs_copy_path, (d, which) -> {
                    ClipboardManager cm = (ClipboardManager) requireContext()
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("world_path", path));
                    Toast.makeText(getContext(), R.string.cs_path_copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cs_cancel, null)
                .show();
    }

    // ══════════════════════ PLAY ══════════════════════

    /**
     * Launch the profile that owns this world. Uses the exact same pipeline
     * as the launcher PLAY button (MinecraftDownloader → ContextAwareDoneListener
     * → MainActivity) so accounting, offline rules and JRE selection stay
     * identical. The world then appears first in the in-game Singleplayer list.
     */
    private void playWorld(@NonNull WorldEntry w) {
        final Activity act = getActivity();
        if (act == null) return;
        try {
            if (mProfileKey != null && !mProfileKey.isEmpty()) {
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, mProfileKey)
                        .commit();
            }
            LauncherProfiles.load();
            MinecraftProfile prof = mProfileKey != null
                    ? LauncherProfiles.mainProfileJson.profiles.get(mProfileKey) : null;
            if (prof == null) {
                Toast.makeText(getContext(), R.string.cs_world_play_no_profile, Toast.LENGTH_LONG).show();
                return;
            }
            String normalized = AsyncMinecraftDownloader.normalizeVersionId(prof.lastVersionId);
            JMinecraftVersionList.Version mcVersion = AsyncMinecraftDownloader.getListedVersion(normalized);
            Toast.makeText(getContext(),
                    getString(R.string.cs_world_launching, w.displayName),
                    Toast.LENGTH_SHORT).show();
            new MinecraftDownloader().start(act, mcVersion, normalized,
                    new ContextAwareDoneListener(act, normalized));
        } catch (Throwable t) {
            Tools.showError(getContext(), t);
        }
    }

    // ══════════════════════ DATAPACKS ══════════════════════

    private void openDatapackBrowser(@NonNull WorldEntry w) {
        Bundle args = new Bundle();
        args.putString(DatapackBrowserFragment.BUNDLE_WORLD_DIR, w.folder.getAbsolutePath());
        args.putString(DatapackBrowserFragment.BUNDLE_WORLD_NAME, w.displayName);
        args.putString(DatapackBrowserFragment.BUNDLE_WORLD_FOLDER, w.folderName);
        args.putString(DatapackBrowserFragment.BUNDLE_PROFILE_KEY, mProfileKey);

        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(
                    DatapackBrowserFragment.class, DatapackBrowserFragment.TAG, args);
        } else {
            Tools.swapFragment(getActivity(),
                    DatapackBrowserFragment.class, DatapackBrowserFragment.TAG, args);
        }
    }

    // ══════════════════════ PROGRESS ══════════════════════

    private void showProgress(@NonNull String title) {
        if (getContext() == null) return;
        if (mProgressDialog != null && mProgressDialog.isShowing()) return;
        View content = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_world_progress, null, false);
        mProgressBar = content.findViewById(R.id.dwp_bar);
        mProgressText = content.findViewById(R.id.dwp_text);
        ((TextView) content.findViewById(R.id.dwp_title)).setText(title);
        mProgressBar.setIndeterminate(true);
        mProgressText.setText("…");
        mProgressDialog = new AlertDialog.Builder(requireContext())
                .setView(content)
                .setCancelable(false)
                .create();
        if (mProgressDialog.getWindow() != null) {
            mProgressDialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_cs_glass_card);
        }
        mProgressDialog.show();
    }

    @NonNull
    private WorldOps.OpCallback opCallback() {
        return new WorldOps.OpCallback() {
            @Override
            public void onProgress(int pct, @Nullable String message) {
                if (mProgressBar == null || mProgressText == null) return;
                if (!isAdded()) return;
                if (pct < 0) {
                    mProgressBar.setIndeterminate(true);
                } else {
                    mProgressBar.setIndeterminate(false);
                    if (android.os.Build.VERSION.SDK_INT >= 24) mProgressBar.setProgress(pct, true);
                    else mProgressBar.setProgress(pct);
                }
                mProgressText.setText(message != null ? message : "");
            }

            @Override
            public void onDone(boolean ok, @NonNull String message) {
                if (mProgressDialog != null) mProgressDialog.dismiss();
                if (!isAdded() || getContext() == null) return;
                Toast.makeText(getContext(), message, ok ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
                reloadWorlds(false);
            }
        };
    }

    private void navigateBack() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).clearRightPane();
        } else if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }
}
