package net.kdt.pojavlaunch.fragments;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.csclient.CsClientCatalog;
import net.kdt.pojavlaunch.csclient.CsClientSelection;

import java.util.ArrayList;

/**
 * CS CLIENT BUILDER — the page that answers, before anything is downloaded:
 * which client is being built, which Minecraft version it targets, exactly
 * what will be installed, what can still be added, and what the current
 * progress is.
 *
 * The identity card and the options card are two separate files for portrait
 * and landscape (see layout-land) so a wide screen gets two real columns
 * instead of one stretched one.
 */
public class CsClientBuilderFragment extends Fragment {

    public static final String TAG = "CS_CLIENT_BUILDER";

    private CsClientCatalog.VersionEntry version;
    private LinearLayout manifest;
    private TextView statModules;
    private TextView statLoader;
    private TextView statPack;

    public CsClientBuilderFragment() {
        super(R.layout.fragment_cs_client_builder);
    }

    @Override
    public void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        version = (CsClientCatalog.VersionEntry) requireArguments().getSerializable("version");
    }

    @Override
    public void onViewCreated(@NonNull View r, @Nullable Bundle s) {
        manifest = r.findViewById(R.id.csc_selection_chips);
        statModules = r.findViewById(R.id.csc_stat_modules);
        statLoader = r.findViewById(R.id.csc_stat_loader);
        statPack = r.findViewById(R.id.csc_stat_pack);

        ((TextView) r.findViewById(R.id.csc_builder_version))
                .setText("MINECRAFT " + version.minecraft);
        ((TextView) r.findViewById(R.id.csc_builder_build))
                .setText("CS CLIENT " + version.build + "  •  FABRIC");

        r.findViewById(R.id.csc_builder_back)
                .setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        r.findViewById(R.id.csc_browse_mods).setOnClickListener(v -> openPicker(false));
        r.findViewById(R.id.csc_browse_pack).setOnClickListener(v -> openPicker(true));
        r.findViewById(R.id.csc_create_profile).setOnClickListener(v -> openInstall());

        UiMotion.pressFeedback(r.findViewById(R.id.csc_builder_back),
                r.findViewById(R.id.csc_browse_mods),
                r.findViewById(R.id.csc_browse_pack),
                r.findViewById(R.id.csc_create_profile));

        // Graphite entrance: identity springs up with a soft scale pop.
        enter(r.findViewById(R.id.csc_builder_identity), 0, 0, 26, true);
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (manifest != null) refresh();
    }

    private void openPicker(boolean pack) {
        Bundle b = new Bundle();
        b.putString("mc", version.minecraft);
        b.putBoolean("pack", pack);
        Tools.swapFragment(requireActivity(), CsClientPickerFragment.class,
                CsClientPickerFragment.TAG, b);
    }

    private void openInstall() {
        Bundle b = new Bundle();
        b.putSerializable("version", version);
        Tools.swapFragment(requireActivity(), CsClientInstallFragment.class,
                CsClientInstallFragment.TAG, b);
    }

    private void refresh() {
        if (manifest == null) return;
        manifest.removeAllViews();

        addRow("FABRIC LOADER", "Compatible stable channel", null, true, 0);
        addRow("CS CLIENT", version.build, null, true, 45);
        addRow("FABRIC API", "Always included", null, true, 90);

        int delay = 135;
        if (CsClientSelection.modpack != null) {
            CsClientCatalog.ProjectEntry e = CsClientSelection.modpack;
            addRow(e.title, "SELECTED MODPACK",
                    () -> { CsClientSelection.modpack = null; refresh(); }, true, delay);
            delay += 45;
        }
        for (CsClientCatalog.ProjectEntry e : new ArrayList<>(CsClientSelection.mods)) {
            addRow(e.title, "FABRIC MODULE",
                    () -> { CsClientSelection.toggleMod(e); refresh(); }, true, delay);
            delay += 45;
        }

        updateStats();
    }

    /** Live counts so the player can see the shape of the install at a glance. */
    private void updateStats() {
        int mods = CsClientSelection.mods == null ? 0 : CsClientSelection.mods.size();
        if (statModules != null) countTo(statModules, 3 + mods
                + (CsClientSelection.modpack != null ? 1 : 0));
        if (statLoader != null) statLoader.setText("FABRIC");
        if (statPack != null) {
            statPack.setText(CsClientSelection.modpack != null
                    ? trim(CsClientSelection.modpack.title, 10) : "NONE");
        }
    }

    private void countTo(TextView target, int value) {
        int from = parseIntSafe(target.getText());
        if (from == value) { target.setText(String.valueOf(value)); return; }
        ValueAnimator anim = ValueAnimator.ofInt(from, value);
        anim.setDuration(360);
        anim.addUpdateListener(a -> target.setText(String.valueOf(a.getAnimatedValue())));
        anim.start();
    }

    private static int parseIntSafe(CharSequence cs) {
        if (cs == null) return 0;
        try { return Integer.parseInt(cs.toString().trim()); } catch (Throwable t) { return 0; }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, Math.max(1, n - 1)) + "…";
    }

    private void addRow(String title, String sub, @Nullable Runnable remove,
                        boolean checked, long delay) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(10), 0);
        row.setBackgroundResource(R.drawable.aur_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.bottomMargin = dp(7);
        row.setLayoutParams(lp);

        // status mark
        FrameLayout markHost = new FrameLayout(requireContext());
        markHost.setLayoutParams(new LinearLayout.LayoutParams(dp(28), -1));
        TextView mark = new TextView(requireContext());
        mark.setText(checked ? "✓" : "•");
        mark.setTextSize(10);
        mark.setTextColor(checked ? 0xFF141519 : 0xFF8A8F99);
        mark.setTypeface(null, android.graphics.Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setIncludeFontPadding(false);
        int markSize = dp(20);
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(markSize, markSize);
        markLp.gravity = Gravity.CENTER;
        mark.setBackgroundResource(checked
                ? R.drawable.aur_mark : R.drawable.aur_mark_idle);
        markHost.addView(mark, markLp);
        row.addView(markHost);

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = t(title, 11, 0xFFF4F6F9, true);
        TextView subView = t(sub, 7.5f, 0xFF9AA0AC, false);
        box.addView(titleView, new LinearLayout.LayoutParams(-1, dp(21)));
        box.addView(subView, new LinearLayout.LayoutParams(-1, dp(15)));
        row.addView(box, new LinearLayout.LayoutParams(0, -1, 1));

        if (remove != null) {
            TextView x = t("×", 18, 0xFFA9AEBA, false);
            x.setGravity(Gravity.CENTER);
            x.setBackgroundResource(R.drawable.aur_mark_idle);
            int xs = dp(26);
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(xs, xs);
            xlp.setMarginStart(dp(6));
            row.addView(x, xlp);
            row.setOnClickListener(v -> row.animate().alpha(0f).translationX(dp(16))
                    .setDuration(150).withEndAction(remove).start());
            UiMotion.pressFeedback(row);
        }

        manifest.addView(row);
        enter(row, delay, 0, 12);
    }

    private TextView t(String s, float z, int c, boolean bold) {
        TextView v = new TextView(requireContext());
        v.setText(s);
        v.setTextSize(z);
        v.setTextColor(c);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return v;
    }

    private void enter(View v, long delay, float x, float y) {
        enter(v, delay, x, y, false);
    }

    private void enter(View v, long delay, float x, float y, boolean scalePop) {
        if (v == null) return;
        v.setAlpha(0f);
        if (x != 0) v.setTranslationX(dp((int) x));
        if (y != 0) v.setTranslationY(dp((int) y));
        if (scalePop) {
            v.setScaleX(0.95f);
            v.setScaleY(0.95f);
        }
        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f);
        android.animation.ObjectAnimator tx = android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_X, v.getTranslationX(), 0f);
        android.animation.ObjectAnimator ty = android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, v.getTranslationY(), 0f);
        java.util.List<android.animation.Animator> parts = new java.util.ArrayList<>();
        parts.add(a); parts.add(tx); parts.add(ty);
        if (scalePop) {
            parts.add(android.animation.ObjectAnimator.ofFloat(v, View.SCALE_X, v.getScaleX(), 1f));
            parts.add(android.animation.ObjectAnimator.ofFloat(v, View.SCALE_Y, v.getScaleY(), 1f));
        }
        set.playTogether(parts);
        set.setStartDelay(delay);
        set.setDuration(440);
        set.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        set.start();
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
