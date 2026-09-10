package net.kdt.pojavlaunch.fragments;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ResourceBrowserDialog — items 3+4. Premium glass popup (NOT a stock Android
 * dialog) that browses the Modrinth catalogue and installs straight into the
 * CURRENT selected profile:
 *
 *   Tabs:  RESOURCE PACKS → <gameDir>/resourcepacks/
 *          SHADER PACKS   → <gameDir>/shaderpacks/
 *   (Mods are intentionally absent, per user directive.)
 *
 * Item-4 surface contract: rounded 20dp master card, translucent glass tint,
 * blur-behind window (API 31+; graceful dim fallback below), spring-feel open
 * and close motion (fast-out / decelerate, no overshoot, no jank), platinum
 * ripples, elevation shadow — identical to the controller-page language.
 *
 * Networking: everything off-thread on PojavApplication.sExecutorService; UI
 * updates re-marshalled onto the main thread and are lifecycle-guarded (the
 * dialog can be dismissed mid-flight without leaks).
 */
public final class ResourceBrowserDialog extends DialogFragment {

    public static final String TAG = "cs_resource_browser";

    private static final String UA = "CSLauncherPlus/1.0";
    private static final String API = "https://api.modrinth.com/v2";
    private static final int PAGE = 24;
    private static final int TYPE_PACKS = 0;
    private static final int TYPE_SHADERS = 1;

    private static final int ST_IDLE = 0, ST_BUSY = 1, ST_INSTALLED = 2, ST_ERROR = 3;

    /** One catalogue row. */
    private static final class Entry {
        String id, title, author, desc, iconUrl;
        long downloads;
        int state = ST_IDLE;
        int progress; // 0..100 while ST_BUSY
    }

    /** Exact project icons, off-thread — 64-entry LRU + disk cache. */
    private final java.util.LinkedHashMap<String, android.graphics.Bitmap> mIconCache =
            new java.util.LinkedHashMap<String, android.graphics.Bitmap>(48, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, android.graphics.Bitmap> eldest) {
                    return size() > 64;
                }
            };

    private final List<Entry> mEntries = new ArrayList<>();
    private ResourceAdapter mAdapter;

    private View mContent;
    private TextView mSubtitle, mVersionNote, mTabPacks, mTabShaders, mEmpty;
    private ProgressBar mLoading;
    private RecyclerView mList;

    private int mType = TYPE_PACKS;
    private String mSearchQuery = "";
    private int mOffset;
    private boolean mFetching, mEndReached, mClosing, mClosed;
    private int mFetchEpoch; // stale-response guard for tab switches

    @Nullable private MinecraftProfile mProfile;
    @Nullable private File mGameDir;
    @Nullable private String mMcVersion;

    public static void show(@NonNull FragmentActivity activity) {
        new ResourceBrowserDialog().show(activity.getSupportFragmentManager(), TAG);
    }

    // ───────────────────────── dialog + glass surface ─────────────────────────

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        resolveCurrentProfile();
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mContent = LayoutInflater.from(dialog.getContext())
                .inflate(R.layout.dialog_resource_browser, null);
        dialog.setContentView(mContent);
        bindViews(mContent);
        bindInteractions();
        applyTabVisuals();
        startFetch(true);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d == null || d.getWindow() == null) return;
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.dimAmount = 0.55f;
        // Blur behind — effective on Android 12+/API 31+ (setter is 31+);
        // dim is the graceful fallback everywhere below.
        lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lp.setBlurBehindRadius(16);
        }
        w.setAttributes(lp);
        if (mContent != null) {
            // Item-4: soft shadow ring around the rounded glass card.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mContent.setElevation(dp(18));
            }
            playEnterAnimation(mContent);
        }
    }

    /** Spring-feel open: scale 0.94→1 + rise 14dp + fade, fast-out settle. */
    private void playEnterAnimation(@NonNull View v) {
        v.setAlpha(0f);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.setTranslationY(dp(14));
        v.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(300)
                .setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f))
                .start();
    }

    /** Smooth close mirrors the open (short accelerate-in, no snap). */
    @Override
    public void dismiss() {
        if (mClosing) return;
        View v = mContent;
        if (v == null || !v.isAttachedToWindow() || getDialog() == null
                || getDialog().getWindow() == null) {
            super.dismissAllowingStateLoss();
            return;
        }
        mClosing = true;
        v.animate().cancel();
        v.animate()
                .alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(8))
                .setDuration(150)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    try { ResourceBrowserDialog.super.dismissAllowingStateLoss(); }
                    catch (Throwable ignored) {}
                })
                .start();
    }

    @Override
    public void onDestroyView() {
        mClosed = true;
        if (mContent != null) mContent.animate().cancel();
        mContent = null; mAdapter = null; mList = null;
        mSubtitle = null; mVersionNote = null; mTabPacks = null; mTabShaders = null;
        mEmpty = null; mLoading = null;
        super.onDestroyView();
    }

    // ───────────────────────── views + interactions ─────────────────────────

    private void bindViews(@NonNull View root) {
        mSubtitle = root.findViewById(R.id.cs_rb_subtitle);
        mVersionNote = root.findViewById(R.id.cs_rb_version_note);
        mTabPacks = root.findViewById(R.id.cs_rb_tab_packs);
        mTabShaders = root.findViewById(R.id.cs_rb_tab_shaders);
        mEmpty = root.findViewById(R.id.cs_rb_empty);
        mLoading = root.findViewById(R.id.cs_rb_loading);
        mList = root.findViewById(R.id.cs_rb_list);

        String prof = mProfile != null && mProfile.name != null ? mProfile.name : "Instance";
        String ver = mProfile != null && mProfile.lastVersionId != null ? mProfile.lastVersionId : "";
        mSubtitle.setText("Installing into: " + prof + (ver.isEmpty() ? "" : "  •  " + ver));
        mVersionNote.setText(mMcVersion != null
                ? "Filtered for Minecraft " + mMcVersion
                : "Showing packs for all game versions");

        mAdapter = new ResourceAdapter();
        mList.setLayoutManager(new LinearLayoutManager(getContext()));
        mList.setAdapter(mAdapter);
        mList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || mFetching || mEndReached) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                if (lm.findLastVisibleItemPosition() >= mAdapter.getItemCount() - 4) {
                    startFetch(false);
                }
            }
        });
    }

    private void bindInteractions() {
        View close = mContent.findViewById(R.id.cs_rb_close);
        if (close != null) close.setOnClickListener(v -> dismiss());
        mTabPacks.setOnClickListener(v -> selectTab(TYPE_PACKS));
        mTabShaders.setOnClickListener(v -> selectTab(TYPE_SHADERS));

        final android.widget.EditText searchInput = mContent.findViewById(R.id.cs_rb_search_input);
        final View searchClear = mContent.findViewById(R.id.cs_rb_search_clear);
        final View searchBtn = mContent.findViewById(R.id.cs_rb_search_btn);
        if (searchClear != null && searchInput != null) {
            searchClear.setOnClickListener(v -> {
                searchInput.setText("");
                mSearchQuery = "";
                searchClear.setVisibility(View.GONE);
                startFetch(true);
            });
            searchInput.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    if (searchClear != null) searchClear.setVisibility(s != null && s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            });
            searchInput.setOnEditorActionListener((v, actionId, event) -> {
                mSearchQuery = searchInput.getText().toString();
                searchInput.clearFocus();
                startFetch(true);
                return true;
            });
        }
        if (searchBtn != null && searchInput != null) {
            searchBtn.setOnClickListener(v -> {
                mSearchQuery = searchInput.getText().toString();
                searchInput.clearFocus();
                startFetch(true);
            });
        }
    }

    private void selectTab(int type) {
        if (mType == type && !mEntries.isEmpty()) return;
        mType = type;
        mEntries.clear();
        mAdapter.notifyDataSetChanged();
        mOffset = 0;
        mEndReached = false;
        mFetchEpoch++;
        // Force-clear: a stale in-flight fetch from the old tab must NOT block
        // this tab's fetch (its completion is epoch-guarded and drops safely).
        mFetching = false;
        applyTabVisuals();
        startFetch(true);
    }

    private void applyTabVisuals() {
        if (mTabPacks == null || mTabShaders == null) return;
        boolean packs = mType == TYPE_PACKS;
        mTabPacks.setBackgroundResource(packs ? R.drawable.bg_cs_tab_pill_active : R.drawable.bg_cs_tab_pill_idle);
        mTabShaders.setBackgroundResource(packs ? R.drawable.bg_cs_tab_pill_idle : R.drawable.bg_cs_tab_pill_active);
        mTabPacks.setTextColor(packs ? 0xFF151518 : 0xFFD0D0D0);
        mTabShaders.setTextColor(packs ? 0xFFD0D0D0 : 0xFF151518);
    }

    // ───────────────────────── Modrinth catalogue ─────────────────────────

    private void startFetch(final boolean reset) {
        if (mFetching) return;
        mFetching = true;
        final int epoch = ++mFetchEpoch;
        final int offset = reset ? 0 : mOffset;
        final String projectType = mType == TYPE_PACKS ? "resourcepack" : "shader";
        if (mLoading != null) mLoading.setVisibility(View.VISIBLE);
        if (mEmpty != null) mEmpty.setVisibility(View.GONE);

        PojavApplication.sExecutorService.execute(() -> {
            final List<Entry> page = new ArrayList<>();
            String error = null;
            try {
                StringBuilder facets = new StringBuilder("[[\"project_type:" + projectType + "\"]");
                if (mMcVersion != null) facets.append(",[\"versions:").append(mMcVersion).append("\"]");
                facets.append("]");
                String url = API + "/search?limit=" + PAGE + "&offset=" + offset
                        + "&index=downloads&facets=" + URLEncoder.encode(facets.toString(), "UTF-8");
                if (mSearchQuery != null && !mSearchQuery.trim().isEmpty()) {
                    url += "&query=" + URLEncoder.encode(mSearchQuery.trim(), "UTF-8");
                }
                JSONObject root = new JSONObject(httpGet(url));
                JSONArray hits = root.optJSONArray("hits");
                if (hits != null) {
                    for (int i = 0; i < hits.length(); i++) {
                        JSONObject h = hits.optJSONObject(i);
                        if (h == null) continue;
                        Entry e = new Entry();
                        e.id = h.optString("project_id");
                        e.title = h.optString("title");
                        e.author = h.optString("author");
                        e.desc = h.optString("description");
                        e.iconUrl = h.optString("icon_url");
                        e.downloads = h.optLong("downloads");
                        if (!e.id.isEmpty() && !e.title.isEmpty()) page.add(e);
                    }
                }
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String err = error;
            Tools.MAIN_HANDLER.post(() -> {
                if (mClosed || mAdapter == null || epoch != mFetchEpoch) return;
                mFetching = false;
                if (mLoading != null) mLoading.setVisibility(View.GONE);
                if (err != null) {
                    if (mEntries.isEmpty() && mEmpty != null) {
                        mEmpty.setText("Couldn't reach Modrinth.\nCheck your connection and reopen.");
                        mEmpty.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                if (reset) { mEntries.clear(); }
                int start = mEntries.size();
                mEntries.addAll(page);
                mOffset = mEntries.size();
                if (page.size() < PAGE) mEndReached = true;
                if (reset) mAdapter.notifyDataSetChanged();
                else mAdapter.notifyItemRangeInserted(start, page.size());
                if (mEntries.isEmpty() && mEmpty != null) {
                    mEmpty.setText("Nothing found for this game version.");
                    mEmpty.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    // ───────────────────────── install engine ─────────────────────────

    /**
     * Install the selected pack through the LAUNCHER'S EXISTING download
     * system ({@link ModDownloadHelper} → android DownloadManager with the
     * standard notification/toast flow) into the current profile's folder —
     * per user directive we reuse the proven pipeline instead of a bespoke
     * inline downloader.
     */
    private void install(@NonNull Entry e) {
        if (e.state == ST_BUSY || e.state == ST_INSTALLED || mGameDir == null) return;
        e.state = ST_BUSY; e.progress = 0;
        mAdapter.notifyChanged(e);
        final int epoch = mFetchEpoch;
        final String projectId = e.id;
        final String title = e.title;

        PojavApplication.sExecutorService.execute(() -> {
            String fileUrl = null;
            Throwable failure = null;
            try {
                String vurl = API + "/project/" + projectId + "/version";
                if (mMcVersion != null) {
                    vurl += "?game_versions=" + URLEncoder.encode("[\"" + mMcVersion + "\"]", "UTF-8");
                }
                JSONArray versions = new JSONArray(httpGet(vurl));
                JSONObject file = null;
                for (int i = 0; i < versions.length() && file == null; i++) {
                    JSONObject v = versions.optJSONObject(i);
                    JSONArray files = v != null ? v.optJSONArray("files") : null;
                    if (files == null || files.length() == 0) continue;
                    for (int f = 0; f < files.length(); f++) {
                        JSONObject fo = files.optJSONObject(f);
                        if (fo != null && fo.optBoolean("primary", false)) { file = fo; break; }
                    }
                    if (file == null) file = files.optJSONObject(0);
                }
                if (file == null) throw new IOException("no downloadable file");
                fileUrl = file.getString("url");
            } catch (Throwable t) {
                failure = t;
            }
            final String furl = fileUrl;
            final Throwable err = failure;
            Tools.MAIN_HANDLER.post(() -> {
                if (mClosed || mAdapter == null || epoch != mFetchEpoch) return;
                if (err != null) {
                    e.state = ST_ERROR;
                    mAdapter.notifyChanged(e);
                    return;
                }
                String contentType = mType == TYPE_PACKS ? "resourcepack" : "shader";
                String profileKey = currentProfileKey();
                // Already on disk? (same filename the existing system uses)
                try {
                    File dir = ModDownloadHelper.getDestinationDir(getContext(), contentType, profileKey);
                    File expected = new File(dir, ModDownloadHelper.sanitizeName(title)
                            + ModDownloadHelper.getFileExtension(contentType));
                    if (expected.exists() && expected.length() > 0) {
                        e.state = ST_INSTALLED;
                        mAdapter.notifyChanged(e);
                        return;
                    }
                } catch (Throwable ignored) {}
                try {
                    ModDownloadHelper.downloadAndExtract(getContext(), title, furl, contentType, profileKey);
                    e.state = ST_INSTALLED;
                } catch (Throwable t) {
                    e.state = ST_ERROR;
                }
                mAdapter.notifyChanged(e);
            });
        });
    }

    /** currentProfile key, DEFAULT_PREF-null robust (game process edge). */
    @Nullable
    private String currentProfileKey() {
        try {
            android.content.SharedPreferences p = net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF;
            if (p == null && getContext() != null) {
                p = getContext().getSharedPreferences("cslauncher_settings", android.content.Context.MODE_PRIVATE);
            }
            return p != null ? p.getString(
                    net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────── adapter ─────────────────────────

    private final class ResourceAdapter extends RecyclerView.Adapter<ResourceAdapter.RowVH> {

        void notifyChanged(Entry e) {
            int idx = mEntries.indexOf(e);
            if (idx >= 0) notifyItemChanged(idx);
        }

        @NonNull
        @Override
        public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_resource_row, parent, false);
            return new RowVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RowVH h, int position) {
            Entry e = mEntries.get(position);
            h.title.setText(e.title);
            h.meta.setText((e.author == null || e.author.isEmpty() ? "Unknown" : e.author)
                    + "  •  " + formatDownloads(e.downloads) + " downloads");
            h.desc.setText(e.desc == null ? "" : e.desc);
            bindIcon(h.icon, e);
            bindInstallPill(h, e);
            h.install.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos < 0 || pos >= mEntries.size()) return;
                install(mEntries.get(pos));
            });
        }

        private void bindInstallPill(@NonNull RowVH h, @NonNull Entry e) {
            switch (e.state) {
                case ST_BUSY:
                    h.install.setEnabled(false);
                    h.install.setText(e.progress > 0 ? e.progress + "%" : "…");
                    h.install.setBackgroundResource(R.drawable.bg_cs_tab_pill_idle);
                    h.install.setTextColor(0xFFD0D0D0);
                    break;
                case ST_INSTALLED:
                    h.install.setEnabled(false);
                    h.install.setText("INSTALLED");
                    h.install.setBackgroundResource(R.drawable.bg_cs_installed_pill);
                    h.install.setTextColor(0xFF7BE3A8);
                    break;
                case ST_ERROR:
                    h.install.setEnabled(true);
                    h.install.setText("RETRY");
                    h.install.setBackgroundResource(R.drawable.bg_cs_tab_pill_active);
                    h.install.setTextColor(0xFF151518);
                    break;
                default:
                    h.install.setEnabled(true);
                    h.install.setText("INSTALL");
                    h.install.setBackgroundResource(R.drawable.bg_cs_tab_pill_active);
                    h.install.setTextColor(0xFF151518);
            }
        }

        @Override
        public int getItemCount() { return mEntries.size(); }

        final class RowVH extends RecyclerView.ViewHolder {
            final TextView title, meta, desc, install;
            final android.widget.ImageView icon;
            RowVH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.row_rb_icon);
                title = itemView.findViewById(R.id.row_rb_title);
                meta = itemView.findViewById(R.id.row_rb_meta);
                desc = itemView.findViewById(R.id.row_rb_desc);
                install = itemView.findViewById(R.id.row_rb_install);
            }
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private void resolveCurrentProfile() {
        try {
            mProfile = LauncherProfiles.getCurrentProfile();
            if (mProfile != null) {
                mGameDir = Tools.getGameDirPath(mProfile);
                mMcVersion = extractMcVersion(mProfile.lastVersionId);
            }
        } catch (Throwable t) {
            mProfile = null; mGameDir = null; mMcVersion = null;
        }
    }

    /** Extract a release-looking version ("1.20.1") out of any version id. */
    @Nullable
    private static String extractMcVersion(@Nullable String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("1\\.\\d{1,2}(\\.\\d{1,2})?").matcher(raw);
        return m.find() ? m.group() : null;
    }

    /** Binds the Modrinth project icon: memory cache → disk cache → network (bg thread). */
    private void bindIcon(@NonNull android.widget.ImageView target, @NonNull Entry e) {
        final String iconUrl = e.iconUrl;
        if (iconUrl == null || iconUrl.isEmpty()) {
            target.setTag(null);
            target.setImageDrawable(null);
            return;
        }
        android.graphics.Bitmap cached;
        synchronized (mIconCache) { cached = mIconCache.get(iconUrl); }
        if (cached != null && !cached.isRecycled()) {
            target.setTag(null);
            target.setImageBitmap(cached);
            return;
        }
        target.setImageDrawable(null);
        target.setTag(iconUrl); // stale-response guard for recycled rows
        PojavApplication.sExecutorService.execute(() -> {
            android.graphics.Bitmap bmp = null;
            try {
                File cacheDir = new File(getContextSafeCacheDir(), "rb_icons");
                File f = new File(cacheDir, md5(iconUrl) + ".png");
                if (f.isFile() && f.length() > 0) {
                    bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                }
                if (bmp == null) {
                    byte[] data = httpGetBytes(iconUrl);
                    if (data != null && data.length > 0) {
                        bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bmp != null) {
                            try {
                                if (!cacheDir.isDirectory()) cacheDir.mkdirs();
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                                    fos.write(data);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (bmp == null) return;
            synchronized (mIconCache) { mIconCache.put(iconUrl, bmp); }
            final android.graphics.Bitmap finalBmp = bmp;
            target.post(() -> {
                if (mClosed) return;
                if (iconUrl.equals(target.getTag())) target.setImageBitmap(finalBmp);
            });
        });
    }

    private File getContextSafeCacheDir() {
        try {
            android.content.Context c = getContext();
            if (c != null) return c.getCacheDir();
        } catch (Throwable ignored) {}
        return new File(requireActivity().getCacheDir().getAbsolutePath());
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest d = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static byte[] httpGetBytes(String url) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String formatDownloads(long n) {
        if (n >= 1_000_000) return (n / 100_000) / 10f + "M";
        if (n >= 1_000) return (n / 100) / 10f + "K";
        return String.valueOf(n);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
