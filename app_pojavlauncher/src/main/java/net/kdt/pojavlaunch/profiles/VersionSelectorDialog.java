package net.kdt.pojavlaunch.profiles;

import static net.kdt.pojavlaunch.extra.ExtraCore.getValue;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Phase 10 — the profile "Select version" popup as a real XML sheet
 * ({@code dialog_version_selector.xml}) with the Minecraft pixel font
 * (Minecraftia — the classic "mini" Minecraft face) instead of an
 * AlertDialog wrapping a stock ExpandableListView.
 *
 * <p>Same public entry point as before: {@link #open(Context, boolean, VersionSelectorListener)}.
 * Channels: Installed · Release · Snapshot · Beta · Alpha; a search box
 * filters the current channel; the launcher's pinned default
 * ({@link MinecraftProfile#DEFAULT_VERSION}) is flagged LATEST and pinned first.
 */
public class VersionSelectorDialog {

    private static final int TAB_INSTALLED = 0, TAB_RELEASE = 1, TAB_SNAPSHOT = 2, TAB_BETA = 3, TAB_ALPHA = 4;

    public static void open(Context context, boolean hideCustomVersions, VersionSelectorListener listener) {
        new VersionSelectorDialog(context, hideCustomVersions, listener).show();
    }

    private final Context mContext;
    private final boolean mHideCustom;
    private final VersionSelectorListener mListener;
    private final List<List<Row>> mChannels = new ArrayList<>();
    private final List<TextView> mTabs = new ArrayList<>();
    private final List<Row> mVisible = new ArrayList<>();
    private int mTab = TAB_RELEASE;
    private String mQuery = "";
    private Dialog mDialog;
    private RowAdapter mAdapter;
    private TextView mCount, mSubtitle, mEmpty;

    private static final class Row {
        final String id; final String type; final boolean latest; final boolean installed;
        Row(String id, String type, boolean latest, boolean installed) {
            this.id = id; this.type = type; this.latest = latest; this.installed = installed;
        }
    }

    private VersionSelectorDialog(Context context, boolean hideCustomVersions, VersionSelectorListener listener) {
        mContext = context;
        mHideCustom = hideCustomVersions;
        mListener = listener;
        buildChannels();
    }

    private void buildChannels() {
        JMinecraftVersionList list = (JMinecraftVersionList) getValue(ExtraConstants.RELEASE_TABLE);
        JMinecraftVersionList.Version[] all = list == null || list.versions == null
                ? new JMinecraftVersionList.Version[0] : list.versions;

        // installed
        List<Row> installed = new ArrayList<>();
        java.util.HashSet<String> installedIds = new java.util.HashSet<>();
        if (!mHideCustom) {
            try {
                String[] dirs = new File(Tools.DIR_GAME_NEW + "/versions").list();
                if (dirs != null) {
                    Arrays.sort(dirs);
                    for (String d : dirs) { installed.add(new Row(d, "INSTALLED", false, true)); installedIds.add(d); }
                }
            } catch (Throwable ignored) {}
        }
        List<Row> release = new ArrayList<>(), snapshot = new ArrayList<>(), beta = new ArrayList<>(), alpha = new ArrayList<>();
        Row pinned = null;
        for (JMinecraftVersionList.Version v : all) {
            if (v == null || v.id == null || v.type == null) continue;
            boolean isDefault = MinecraftProfile.DEFAULT_VERSION.equals(v.id);
            Row r = new Row(v.id, v.type.replace("old_", "").toUpperCase(Locale.ROOT), isDefault, installedIds.contains(v.id));
            switch (v.type) {
                case "release": if (isDefault) pinned = r; else release.add(r); break;
                case "snapshot": snapshot.add(r); break;
                case "old_beta": beta.add(r); break;
                case "old_alpha": alpha.add(r); break;
                default: release.add(r);
            }
        }
        if (pinned != null) release.add(0, pinned);
        mChannels.clear();
        mChannels.add(installed); mChannels.add(release); mChannels.add(snapshot); mChannels.add(beta); mChannels.add(alpha);
        mTab = (!mHideCustom && !installed.isEmpty()) ? TAB_INSTALLED : TAB_RELEASE;
    }

    private void show() {
        View root = LayoutInflater.from(mContext).inflate(R.layout.dialog_version_selector, null, false);
        mDialog = new Dialog(mContext);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setContentView(root);
        Window w = mDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.62f);
            w.setWindowAnimations(0);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        mCount = root.findViewById(R.id.vs_count);
        mSubtitle = root.findViewById(R.id.vs_subtitle);
        mEmpty = root.findViewById(R.id.vs_empty);
        RecyclerView list = root.findViewById(R.id.vs_list);
        list.setLayoutManager(new LinearLayoutManager(mContext));
        mAdapter = new RowAdapter();
        list.setAdapter(mAdapter);

        int[] tabIds = {R.id.vs_tab_installed, R.id.vs_tab_release, R.id.vs_tab_snapshot, R.id.vs_tab_beta, R.id.vs_tab_alpha};
        for (int i = 0; i < tabIds.length; i++) {
            final int which = i;
            TextView t = root.findViewById(tabIds[i]);
            mTabs.add(t);
            if (i == TAB_INSTALLED && mHideCustom) { t.setVisibility(View.GONE); continue; }
            t.setOnClickListener(v -> { if (mTab != which) { mTab = which; styleTabs(); refresh(true); Anime.pulse(v); } });
        }
        styleTabs();

        EditText search = root.findViewById(R.id.vs_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { mQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT); refresh(false); }
        });

        root.findViewById(R.id.vs_close).setOnClickListener(v -> mDialog.dismiss());

        int total = 0;
        for (List<Row> c : mChannels) total += c.size();
        if (mSubtitle != null) mSubtitle.setText(total == 0 ? "MOJANG MANIFEST  ·  OFFLINE" : "MOJANG MANIFEST  ·  " + total + " VERSIONS");

        mDialog.show();
        if (w != null) {
            android.util.DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
            int pad = (int) (24 * dm.density);
            int width = Math.min((int) (520 * dm.density), dm.widthPixels - pad);
            int height = Math.min((int) (420 * dm.density), dm.heightPixels - pad);
            w.setLayout(width, height);
            w.setGravity(Gravity.CENTER);
        }
        // entrance: sheet scales in (outBack), rows stagger in
        root.setAlpha(0f); root.setScaleX(0.9f); root.setScaleY(0.9f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(Anime.OUT_BACK).start();
        refresh(true);
    }

    private void styleTabs() {
        for (int i = 0; i < mTabs.size(); i++) {
            TextView t = mTabs.get(i);
            boolean on = i == mTab;
            t.setBackgroundResource(on ? R.drawable.md_tab_on : R.drawable.md_tab_off);
            t.setTextColor(on ? 0xFFF4F6F9 : 0xFF8A909C);
        }
    }

    private void refresh(boolean animate) {
        mVisible.clear();
        List<Row> src = mChannels.get(Math.min(mTab, mChannels.size() - 1));
        for (Row r : src) if (mQuery.isEmpty() || r.id.toLowerCase(Locale.ROOT).contains(mQuery)) mVisible.add(r);
        if (mCount != null) mCount.setText(String.valueOf(mVisible.size()));
        if (mEmpty != null) mEmpty.setVisibility(mVisible.isEmpty() ? View.VISIBLE : View.GONE);
        mAdapter.mAnimateFrom = animate ? 0 : Integer.MAX_VALUE;
        mAdapter.notifyDataSetChanged();
    }

    private void pick(Row r) {
        if (mListener != null) mListener.onVersionSelected(r.id, mTab == TAB_SNAPSHOT || "SNAPSHOT".equals(r.type));
        if (mDialog != null) mDialog.dismiss();
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        int mAnimateFrom = 0;

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_version_selector_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = mVisible.get(position);
            h.id.setText(r.id);
            h.type.setText(r.type);
            boolean flag = r.latest || (r.installed && mTab != TAB_INSTALLED);
            h.flag.setVisibility(flag ? View.VISIBLE : View.GONE);
            if (flag) {
                h.flag.setText(r.latest ? "LATEST" : "INSTALLED");
                h.flag.setBackgroundResource(r.latest ? R.drawable.vp_tag_recommended : R.drawable.vp_tag_channel);
                h.flag.setTextColor(r.latest ? 0xFF141519 : 0xFFC9CED8);
            }
            h.itemView.setBackgroundResource(r.latest ? R.drawable.vs_row_hot : R.drawable.vs_row);
            h.itemView.setOnClickListener(v -> pick(r));
            if (position >= mAnimateFrom && position < mAnimateFrom + 12) {
                Anime.in(h.itemView, Anime.Fx.FADE_UP, 40L + (position - mAnimateFrom) * 30L, 380, Anime.OUT_EXPO);
            } else {
                h.itemView.setAlpha(1f); h.itemView.setTranslationY(0f);
            }
        }

        @Override public int getItemCount() { return mVisible.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final TextView id, type, flag;
            VH(@NonNull View v) {
                super(v);
                id = v.findViewById(R.id.vsr_id);
                type = v.findViewById(R.id.vsr_type);
                flag = v.findViewById(R.id.vsr_flag);
            }
        }
    }
}
