package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.csclient.CsClientCatalog;
import net.kdt.pojavlaunch.csclient.CsClientSelection;

import java.util.List;

/**
 * CS Client — version picker (Aurora design, CS Launcher Plus V1.0).
 *
 * <p>Hero brand card + version cards that cascade in with the shared
 * {@code aur_cascade} layout animation; each card uses the new
 * {@code aur_row} glass tile with a leading version medallion.
 */
public class CsClientVersionsFragment extends Fragment {
    public static final String TAG = "CS_CLIENT_VERSIONS";

    public CsClientVersionsFragment() {
        super(R.layout.fragment_cs_client_versions);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle state) {
        LinearLayout box = root.findViewById(R.id.csc_versions_container);
        ProgressBar load = root.findViewById(R.id.csc_versions_loading);
        View back = root.findViewById(R.id.csc_versions_back);
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        UiMotion.pressFeedback(back);

        // Hero entrance
        View hero = root.findViewById(R.id.csc_versions_header);
        if (hero != null) {
            hero.setAlpha(0f);
            hero.setTranslationY(18f * getResources().getDisplayMetrics().density);
            hero.animate().alpha(1f).translationY(0f).setDuration(340)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        }

        PojavApplication.sExecutorService.execute(() -> {
            try {
                List<CsClientCatalog.VersionEntry> list = CsClientCatalog.fetchLatestByMinecraft();
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    load.setVisibility(View.GONE);
                    long d = 120;
                    for (CsClientCatalog.VersionEntry e : list) {
                        View card = makeCard(e);
                        box.addView(card);
                        card.setAlpha(0f);
                        card.setTranslationY(18f * getResources().getDisplayMetrics().density);
                        card.setScaleX(0.97f);
                        card.setScaleY(0.97f);
                        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                                .setStartDelay(d).setDuration(360)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                                .start();
                        d += 70;
                    }
                });
            } catch (Exception e) {
                Tools.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    load.setVisibility(View.GONE);
                    Toast.makeText(requireContext(),
                            "Could not load bundled CS Client versions", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** Graphite v2 list row: leading version medallion, text block, trailing chevron. */
    private View makeCard(CsClientCatalog.VersionEntry e) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        card.setBackgroundResource(R.drawable.csc_vrow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        // Leading medallion: graphite tile with the MC minor version
        TextView badge = new TextView(requireContext());
        badge.setBackgroundResource(R.drawable.csc_medallion);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setTextColor(0xFFE6E9EF);
        badge.setTextSize(15f);
        badge.setTypeface(null, 1);
        badge.setIncludeFontPadding(false);
        badge.setSingleLine(true);
        String mc = e.minecraft == null ? "?" : e.minecraft;
        String tail = mc.contains(".") ? mc.substring(mc.lastIndexOf('.') + 1) : mc;
        badge.setText(mc);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(62), dp(52));
        bp.rightMargin = dp(14);
        badge.setLayoutParams(bp);

        // Text block
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView tag = t("MINECRAFT  •  FABRIC  •  VERIFIED", 7, 0xFF9AA0AC, true);
        TextView title = t("Minecraft " + mc, 17, 0xFFF4F6F9, true);
        TextView sub = t("CS Client " + e.build + " • bundled & verified", 9, 0xFF8A909C, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(2);
        text.addView(tag);
        text.addView(title);
        sub.setLayoutParams(subLp);
        text.addView(sub);

        card.addView(badge);
        card.addView(text);

        // Trailing chevron
        TextView chev = new TextView(requireContext());
        chev.setText("›");
        chev.setTextSize(26f);
        chev.setGravity(android.view.Gravity.CENTER);
        chev.setTextColor(0xFF8A8F99);
        chev.setIncludeFontPadding(false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(28), dp(40));
        chev.setLayoutParams(cp);
        card.addView(chev);

        card.setOnClickListener(v -> {
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                            .start())
                    .start();
            CsClientSelection.clear();
            Bundle b = new Bundle();
            b.putSerializable("version", e);
            Tools.swapFragment(requireActivity(), CsClientBuilderFragment.class,
                    CsClientBuilderFragment.TAG, b);
        });
        UiMotion.pressFeedback(card);
        return card;
    }

    private TextView t(String s, int sp, int c, boolean bold) {
        TextView v = new TextView(requireContext());
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(c);
        if (bold) v.setTypeface(null, 1);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return v;
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
