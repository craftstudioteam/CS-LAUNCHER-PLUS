package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.JavaGUILauncherActivity;
import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.Anime;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.FabriclikeDownloadTask;
import net.kdt.pojavlaunch.modloaders.FabricVersion;
import net.kdt.pojavlaunch.modloaders.FabriclikeUtils;
import net.kdt.pojavlaunch.modloaders.ForgeDownloadTask;
import net.kdt.pojavlaunch.modloaders.ForgeUtils;
import net.kdt.pojavlaunch.modloaders.ModloaderDownloadListener;
import net.kdt.pojavlaunch.modloaders.NeoForgeDownloadTask;
import net.kdt.pojavlaunch.modloaders.CustomJarInstallTask;
import net.kdt.pojavlaunch.modloaders.JarProbe;
import net.kdt.pojavlaunch.modloaders.OptiFineUtils;
import net.kdt.pojavlaunch.profiles.PendingProfileRename;
import net.kdt.pojavlaunch.utils.CropperUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen guided "create instance" screen — dependency-driven flow:
 *
 *   PROFILE (icon + name)
 *        ↓
 *   LOADER  (Vanilla / Fabric / Forge / NeoForge / Quilt / OptiFine)
 *        ↓
 *   LOADER VERSION  (dark themed {@link VersionPickerDialog}; the fields
 *                    shown depend on the selected loader; Forge / NeoForge /
 *                    OptiFine builds also DETERMINE the Minecraft version)
 *        ↓
 *   MINECRAFT VERSION  (dark themed picker, filtered by the loader: the
 *                       loader's own supported-version metadata for
 *                       Fabric/Quilt, the vanilla release table for Vanilla)
 *        ↓
 *   CREATE  (completes right here — no extra wizard pages)
 *
 *  The user's PROFILE NAME + ICON are carried through the entire flow and
 *  land on the created profile verbatim (Fabric/Quilt directly; Forge/
 *  NeoForge/OptiFine via {@link PendingProfileRename} across the installer).
 */
public class VersionCreateFragment extends Fragment implements CropperUtils.CropperListener,
        ModloaderDownloadListener, VersionPickerDialog.Host {

    public static final String TAG = "VersionCreateFragment";

    // ── Loader definitions ──
    private static final int LDR_NONE      = -1;
    private static final int LDR_VANILLA   = 0;
    private static final int LDR_FABRIC    = 1;
    private static final int LDR_FORGE     = 2;
    private static final int LDR_NEOFORGE  = 3;
    private static final int LDR_QUILT     = 4;
    private static final int LDR_JAR     = 5;
    /** Phase 10: OptiFine is a first-class option again (official optifine.net
     *  build list → tokenised download → the bundled installer agent). */
    private static final int LDR_OPTIFINE = 6;

    private static final String[] LOADER_NAMES =
            {"Vanilla", "Fabric", "Forge", "NeoForge", "Quilt", "JAR", "OptiFine"};

    // Version-picker purposes (recreation-safe host contract).
    private static final int PURPOSE_LOADER_VERSION = 1;
    private static final int PURPOSE_MC_VERSION = 2;

    private int mSelectedLoader = LDR_NONE;
    private String mMinecraftVersion = null;
    private String mLoaderVersion = null;
    private String mEncodedIcon = null;
    private boolean mInstalling = false;
    /** Application context captured in onAttach — usable after the page is gone. */
    private Context mAppContext;
    /**
     * Auto-naming: the field is pre-filled from loader + version ("Fabric 1.21.1")
     * and keeps following the selection UNTIL the user types something of their
     * own. mNameAuto is true while the current text is ours; mSuppressWatcher
     * stops our own setText from being mistaken for user input.
     */
    private boolean mNameAuto = true;
    private boolean mSuppressWatcher = false;
    /**
     * Phase 8: the default identity follows the loader. Every loader gets its
     * own mark (Fabric → Fabric, Quilt → Quilt, Forge → anvil, NeoForge →
     * NeoForge, Vanilla → crafting table, JAR → CS logo). Encoded lazily, once
     * per loader, and used only while the user has not cropped their own icon.
     */
    private final android.util.SparseArray<String> mDefaultIcons = new android.util.SparseArray<>();
    private String mDefaultIcon = null;
    /**
     * The JAR the user picked (OptiFine-style installers, mod jars, any other
     * build that ships its own installer). Replaces the old OptiFine version
     * list: no scraping, the user just says which file.
     */
    private android.net.Uri mJarUri = null;
    private String mJarName = null;
    /**
     * What the picked jar itself declared, and how sure we are of it. Kept separate
     * from mMinecraftVersion so a detected version is distinguishable from a chosen
     * one — the create guard must accept the former, the hint text must explain it.
     */
    @Nullable private String mJarDetectedMc;
    @Nullable private String mJarProbeEvidence;
    private boolean mJarLooksLikePlainMod;

    private LinearLayout mChipsContainer;
    private final List<TextView> mChipViews = new ArrayList<>();
    private View mLoaderVersionRow;
    private TextView mLoaderVersionLabel, mLoaderVersionValue;
    private View mMinecraftField;
    private TextView mMinecraftVersionValue;
    private TextView mHint;
    private EditText mNameField;
    private ImageView mIconView;
    private TextView mCreateButton;
    private TextView mDockTitle, mDockSub;

    private final ActivityResultLauncher<?> mCropperLauncher =
            CropperUtils.registerCropper(this, this);

    /** SAF picker for the JAR loader — no fragment navigation, so no state loss. */
    private final ActivityResultLauncher<String[]> mJarPickerLauncher =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts
                    .OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    requireContext().getContentResolver()
                            .takePersistableUriPermission(uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                mJarUri = uri;
                mJarName = jarDisplayName(uri);
                mLoaderVersionValue.setText(mJarName);
                mLoaderVersionValue.setTextColor(0xFFD2D6DE);
                Anime.pop(mLoaderVersionValue);
                applyAutoName(true);
                updateDockStatus();
                probePickedJar(uri);
            });

    public VersionCreateFragment() {
        super(R.layout.fragment_version_create);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mAppContext = context.getApplicationContext();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mChipsContainer = view.findViewById(R.id.vcreate_loader_chips);
        mLoaderVersionRow = view.findViewById(R.id.vcreate_loader_version_row);
        mLoaderVersionLabel = view.findViewById(R.id.vcreate_loader_version_label);
        mLoaderVersionValue = view.findViewById(R.id.vcreate_loader_version_field)
                .findViewById(R.id.vcreate_loader_version_value);
        mMinecraftField = view.findViewById(R.id.vcreate_mc_version_field);
        mMinecraftVersionValue = view.findViewById(R.id.vcreate_mc_version_value);
        mHint = view.findViewById(R.id.vcreate_hint);
        mNameField = view.findViewById(R.id.vcreate_name);
        mIconView = view.findViewById(R.id.vcreate_icon);
        mCreateButton = view.findViewById(R.id.vcreate_create);
        mDockTitle = view.findViewById(R.id.vcreate_dock_title);
        mDockSub = view.findViewById(R.id.vcreate_dock_sub);

        view.findViewById(R.id.vcreate_back).setOnClickListener(v -> navigateBack());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() { navigateBack(); }
                });

        view.findViewById(R.id.vcreate_icon_frame).setOnClickListener(
                v -> CropperUtils.startCropper(mCropperLauncher));

        mMinecraftField.setOnClickListener(v -> pickMinecraftVersion());
        view.findViewById(R.id.vcreate_loader_version_field).setOnClickListener(v -> {
            // For the JAR loader the row is a file picker, not a version list.
            if (mSelectedLoader == LDR_JAR) { pickJarFile(); return; }
            pickLoaderVersion();
        });

        mCreateButton.setOnClickListener(v -> onCreateClicked());
        mNameField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (!mSuppressWatcher) {
                    // The user took over the name: stop auto-filling. An emptied
                    // field hands control back so the suggestion returns.
                    mNameAuto = s.toString().trim().isEmpty();
                    if (mNameAuto) applyAutoName(false);
                }
                updateDockStatus();
            }
        });
        // Default identity: a loader mark until the user crops their own.
        showDefaultIcon();
        prepareDefaultIcon(LDR_NONE);
        UiMotion.pressFeedback(mCreateButton,
                view.findViewById(R.id.vcreate_back),
                view.findViewById(R.id.vcreate_icon_frame),
                mMinecraftField,
                view.findViewById(R.id.vcreate_loader_version_field));

        buildLoaderChips();
        // Phase 10 (highest priority): the page opens READY — Vanilla + the
        // launcher's pinned default version (1.21.11) are pre-selected, so a
        // single tap on CREATE gives a working profile. Every chip / version
        // pick still overrides this exactly as before.
        mMinecraftVersion = MinecraftProfile.DEFAULT_VERSION;
        applyLoaderSelection(LDR_VANILLA, true);
        if (mMinecraftVersionValue != null) {
            mMinecraftVersionValue.setText(MinecraftProfile.DEFAULT_VERSION + "  \u00b7  default");
            mMinecraftVersionValue.setTextColor(0xFFF2F3F5);
        }
        applyAutoName(false);
        updateDockStatus();

        // ── anime.js-style entrance timeline ──────────────────────────────
        final float d = getResources().getDisplayMetrics().density;
        UiMotion.revealScreen(view);
        view.post(() -> {
            // 60   identity card rises (outExpo)
            Anime.in(view.findViewById(R.id.vcreate_identity_card), Anime.Fx.FADE_UP, 60, 620, Anime.OUT_EXPO);
            // 160  profile icon jelly-pops, name field flips up
            UiMotion.heroIn(view.findViewById(R.id.vcreate_icon_frame));
            Anime.in(mNameField, Anime.Fx.FLIP_UP, 180, 620, Anime.OUT_EXPO);
            // 200  loader engine card
            Anime.in(view.findViewById(R.id.vcreate_engine_card), Anime.Fx.FADE_UP, 200, 640, Anime.OUT_EXPO);
            // 300  chips: stagger(50, from center) with outBack
            Anime.staggerCenter(mChipsContainer, 300, 50, Anime.Fx.POP);
            // 420  version fields flip up one after another
            Anime.in(mLoaderVersionRow.getVisibility() == View.VISIBLE ? mLoaderVersionRow : null,
                    Anime.Fx.FLIP_UP, 420, 560, Anime.OUT_EXPO);
            Anime.in(mMinecraftField, Anime.Fx.FLIP_UP, 480, 560, Anime.OUT_EXPO);
            // 520  the CREATE dock springs up last
            View dock = view.findViewById(R.id.vcreate_dock);
            if (dock != null) {
                dock.setAlpha(0f);
                dock.setTranslationY(44f * d);
                dock.animate().alpha(1f).translationY(0f).setStartDelay(520)
                        .setDuration(560).setInterpolator(Anime.SPRING).start();
            }
            mCreateButton.postDelayed(() -> Anime.pop(mCreateButton), 700);
        });
    }

    // ── Default icon + auto-name ──────────────────────────────────────────

    /** Drawable that represents a loader in the icon well (and on the created profile). */
    private static int defaultIconRes(int loader) {
        switch (loader) {
            case LDR_FABRIC:   return R.drawable.ic_fabric;
            case LDR_QUILT:    return R.drawable.ic_quilt;
            case LDR_FORGE:    return R.drawable.ic_forge;
            case LDR_NEOFORGE: return R.drawable.ic_neoforge_profile;
            case LDR_VANILLA:  return R.drawable.img_crafting_table;
            case LDR_JAR:      return R.drawable.cs_logo;
            case LDR_OPTIFINE: return R.drawable.ic_optifine;
            default:           return R.drawable.cs_logo;
        }
    }

    /** The loader's own mark sits in the icon well until the user crops their own. */
    private void showDefaultIcon() {
        if (mIconView == null || mEncodedIcon != null) return;
        int res = defaultIconRes(mSelectedLoader);
        mIconView.setImageResource(res);
        mIconView.setColorFilter(null);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) ((res == R.drawable.cs_logo ? 10 : 8) * d);
        mIconView.setPadding(pad, pad, pad, pad);
        mIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
    }

    /** Swap the default mark with a small flip when the loader changes. */
    private void animateDefaultIconSwap() {
        if (mIconView == null || mEncodedIcon != null) return;
        mIconView.animate().cancel();
        mIconView.animate().rotationY(90f).scaleX(0.7f).scaleY(0.7f).alpha(0.2f)
                .setDuration(140).setInterpolator(Anime.IN_BACK)
                .withEndAction(() -> {
                    showDefaultIcon();
                    mIconView.setRotationY(-90f);
                    mIconView.animate().rotationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(360).setInterpolator(Anime.OUT_BACK).start();
                }).start();
    }

    /**
     * Encode the loader mark as a webp data-URI (off the UI thread) so it lands
     * on the created profile exactly as shown. Cached per loader.
     */
    private void prepareDefaultIcon(final int loader) {
        String cached = mDefaultIcons.get(loader);
        if (cached != null) { mDefaultIcon = cached; return; }
        final int res = defaultIconRes(loader);
        PojavApplication.sExecutorService.execute(() -> {
            try {
                Bitmap bmp = renderDrawable(res, 192);
                if (bmp == null) return;
                final String enc = encodeIcon(bmp);
                if (enc == null) return;
                Tools.runOnUiThread(() -> {
                    mDefaultIcons.put(loader, enc);
                    if (mSelectedLoader == loader) mDefaultIcon = enc;
                });
            } catch (Throwable ignored) {}
        });
    }

    /** Rasterise any drawable (vector, webp, png) into a square ARGB bitmap. */
    @Nullable
    private Bitmap renderDrawable(int res, int size) {
        try {
            android.graphics.drawable.Drawable d = androidx.core.content.res.ResourcesCompat
                    .getDrawable(getResources(), res, null);
            if (d == null) return null;
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap src = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (src != null) return src;
            }
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            int iw = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : size;
            int ih = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : size;
            float scale = Math.min(size / (float) iw, size / (float) ih);
            int w = Math.round(iw * scale), h = Math.round(ih * scale);
            int l = (size - w) / 2, t = (size - h) / 2;
            d.setBounds(l, t, l + w, t + h);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** "Fabric 1.21.1", "Minecraft 1.20.4", "Forge 47.2.0", jar base name… */
    private String suggestedName() {
        String mc = mMinecraftVersion;
        switch (mSelectedLoader) {
            case LDR_VANILLA:  return mc != null ? "Minecraft " + mc : "Minecraft";
            case LDR_FABRIC:   return mc != null ? "Fabric " + mc : "Fabric";
            case LDR_QUILT:    return mc != null ? "Quilt " + mc : "Quilt";
            case LDR_FORGE:    return mc != null ? "Forge " + mc : "Forge";
            case LDR_NEOFORGE: return mc != null ? "NeoForge " + mc : "NeoForge";
            case LDR_OPTIFINE: return mc != null ? "OptiFine " + mc : "OptiFine";
            case LDR_JAR:
                if (mJarName != null) {
                    String base = mJarName.endsWith(".jar") ? mJarName.substring(0, mJarName.length() - 4) : mJarName;
                    return base.length() > 32 ? base.substring(0, 32) : base;
                }
                return mc != null ? "Custom " + mc : "Custom JAR";
            default: return "";
        }
    }

    /**
     * Writes the suggestion into the field while it is still ours. {@code animate}
     * flashes the field so the user notices the name followed their choice.
     */
    private void applyAutoName(boolean animate) {
        if (mNameField == null || !mNameAuto) return;
        String next = suggestedName();
        mNameField.setHint(next.isEmpty() ? "My Survival Profile" : next);
        String cur = mNameField.getText().toString();
        if (cur.equals(next)) return;
        mSuppressWatcher = true;
        mNameField.setText(next);
        mNameField.setSelection(next.length());
        mSuppressWatcher = false;
        if (animate && !next.isEmpty()) Anime.pulse(mNameField);
        updateDockStatus();
    }

    // ── Loader chips ──────────────────────────────────────────────────────

    private void buildLoaderChips() {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < LOADER_NAMES.length; i++) {
            final int index = i;
            TextView chip = new TextView(requireContext());
            chip.setText(LOADER_NAMES[i]);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setIncludeFontPadding(false);
            int padH = (int) (16 * density);
            int padV = (int) (10 * density);
            chip.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * density));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                if (mInstalling) return;
                // Switching engines keeps an already-picked Minecraft version
                // (the loader-build list is then filtered to it); JAR is the
                // exception because its version comes from the file.
                boolean keep = mMinecraftVersion != null && index != LDR_JAR && mSelectedLoader != LDR_JAR;
                applyLoaderSelection(index, keep);
            });
            if (i == LDR_JAR) {
                // Phase 11: the "JAR" loader option is removed from the create page.
                // The chip is kept (GONE) so mChipViews indices stay aligned with the
                // LDR_* constants; every LDR_JAR code path remains for internal use.
                chip.setVisibility(View.GONE);
            }
            mChipsContainer.addView(chip);
            mChipViews.add(chip);
        }
    }

    /**
     * Dynamic state: switching the loader immediately re-shapes the form —
     * irrelevant fields disappear, dependent values reset, hints update.
     */
    private void applyLoaderSelection(int index) {
        applyLoaderSelection(index, false);
    }

    /**
     * @param keepMinecraft true when the loader was inferred from a version the
     *                      user already picked (version-first flow) — the
     *                      Minecraft choice must survive the switch.
     */
    private void applyLoaderSelection(int index, boolean keepMinecraft) {
        final int previous = mSelectedLoader;
        mSelectedLoader = index;
        final String keptMc = keepMinecraft ? mMinecraftVersion : null;
        // Icon well follows the loader (unless the user cropped their own).
        if (previous != index) {
            prepareDefaultIcon(index);
            if (mDefaultIcons.get(index) != null) mDefaultIcon = mDefaultIcons.get(index);
            else mDefaultIcon = null;
            if (mIconView != null && mIconView.isLaidOut()) animateDefaultIconSwap();
            else showDefaultIcon();
        }
        for (int i = 0; i < mChipViews.size(); i++) {
            TextView chip = mChipViews.get(i);
            boolean active = i == index;
            boolean wasActive = Boolean.TRUE.equals(chip.getTag());
            chip.setTag(active);
            chip.setBackgroundResource(active ? R.drawable.bg_loader_chip_active : R.drawable.bg_loader_chip);
            chip.setTextColor(active ? 0xFF0E0E11 : 0xFFB9BEC7);
            if (active && !wasActive) Anime.pulse(chip);
        }

        // Dependent selections are invalidated by a loader change.
        mLoaderVersion = null;
        mMinecraftVersion = keptMc;
        mJarUri = null;
        mJarName = null;
        mJarDetectedMc = null;
        mJarProbeEvidence = null;
        mJarLooksLikePlainMod = false;
        mJarUserChose = false;
        mLoaderVersionValue.setText("Select loader version");
        mLoaderVersionValue.setTextColor(0xFF5C6068);
        if (keptMc != null) {
            mMinecraftVersionValue.setText(keptMc);
            mMinecraftVersionValue.setTextColor(0xFFF2F3F5);
        } else {
            mMinecraftVersionValue.setText("Select version");
            mMinecraftVersionValue.setTextColor(0xFF5C6068);
        }

        boolean none = index == LDR_NONE;
        boolean vanilla = index == LDR_VANILLA;
        boolean fabricLike = index == LDR_FABRIC || index == LDR_QUILT;
        boolean derived = index == LDR_FORGE || index == LDR_NEOFORGE || index == LDR_JAR || index == LDR_OPTIFINE;

        // Loader-version row: only the fields the selected loader actually has.
        // With no loader yet it stays visible as the "pick any build" entry.
        boolean showLoaderRow = fabricLike || derived || none;
        if (showLoaderRow && mLoaderVersionRow.getVisibility() != View.VISIBLE) {
            mLoaderVersionRow.setVisibility(View.VISIBLE);
            mLoaderVersionRow.setAlpha(0f);
            float td = getResources().getDisplayMetrics().density;
            mLoaderVersionRow.setTranslationY(16f * td);
            mLoaderVersionRow.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60).setDuration(360)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        } else if (!showLoaderRow) {
            mLoaderVersionRow.animate().cancel();
            mLoaderVersionRow.setVisibility(View.GONE);
            mLoaderVersionRow.setAlpha(1f);
            mLoaderVersionRow.setTranslationY(0f);
        }
        if (none) {
            mLoaderVersionLabel.setText("LOADER BUILD");
        } else if (fabricLike) {
            mLoaderVersionLabel.setText(index == LDR_QUILT ? "QUILT LOADER" : "FABRIC LOADER");
        } else if (index == LDR_FORGE) {
            mLoaderVersionLabel.setText("FORGE VERSION");
        } else if (index == LDR_NEOFORGE) {
            mLoaderVersionLabel.setText("NEOFORGE VERSION");
        } else if (index == LDR_JAR) {
            mLoaderVersionLabel.setText("JAR FILE");
        } else if (index == LDR_OPTIFINE) {
            mLoaderVersionLabel.setText("OPTIFINE BUILD");
        }

        // Dependency chain: Loader → Minecraft version → Loader version.
        // The Minecraft list is loader-filtered, so it opens right after the
        // loader is chosen; the loader-version list is then filtered by the
        // chosen Minecraft version.
        // Version-first is allowed too: with no loader chosen, the Minecraft
        // row lists plain releases (picking one selects Vanilla) and the
        // loader-version row lists every loader build (picking one selects
        // its loader — and its Minecraft version when the build implies it).
        if (none) {
            setMcField("Select version  ·  or pick a loader", true, 1f);
        } else {
            setMcField("Select version", true, 1f);
        }
        if (index == LDR_JAR) {
            mLoaderVersionValue.setText(mJarName != null ? mJarName : "Choose a .jar file");
            mLoaderVersionValue.setTextColor(mJarName != null ? 0xFFD2D6DE : 0xFF5C6068);
        } else if (none) {
            mLoaderVersionValue.setText("Any loader build  ·  auto-selects its loader");
        } else {
            mLoaderVersionValue.setText(mMinecraftVersion == null
                    ? "Select Minecraft version first" : "Select loader version");
        }

        if (none) { mHint.setVisibility(View.GONE); }
        else {
            mHint.setVisibility(View.VISIBLE);
            mHint.setText(fabricLike
                    ? "Pick a loader build, then a compatible Minecraft version — the profile is created right here."
                    : index == LDR_JAR
                        ? "Pick any .jar installer. The launcher opens the archive and reads the Minecraft version the file itself declares (version.json, an OptiFine Config class, an installer profile), downloads that version if it is missing, and asks you only when the file truly does not say."
                        : index == LDR_OPTIFINE
                        ? "Official optifine.net builds. Pick the Minecraft version, the newest OptiFine build fills in by itself; the launcher downloads the game if needed and runs the OptiFine installer for you."
                        : derived
                        ? "Each build already targets one Minecraft version. The official installer opens to finish setup."
                        : "Plain Minecraft — no loader. The profile is created instantly.");
        }
        mCreateButton.setText(vanilla || fabricLike ? "CREATE" : "DOWNLOAD & CREATE");
        // The suggested name follows the loader ("Fabric", then "Fabric 1.21.1").
        applyAutoName(!none);
        updateDockStatus();
        // Phase 9: switching loader while a Minecraft version is already chosen
        // fills the loader build immediately (Fabric 1.21.4 → loader 0.16.x).
        if (keptMc != null && (fabricLike || index == LDR_FORGE || index == LDR_NEOFORGE || index == LDR_OPTIFINE)) {
            autoSelectLoaderBuild(index, keptMc);
        } else {
            mAutoLoaderToken++; // cancel any resolve that no longer applies
        }
    }

    /** Keeps the floating dock's two-line status in sync with form progress. */
    private void updateDockStatus() {
        if (mDockTitle == null || mDockSub == null) return;
        boolean named = mNameField.getText().toString().trim().length() > 0;
        String title, sub;
        if (mSelectedLoader == LDR_NONE) {
            title = "Start building"; sub = "Pick a loader — or a version first";
        } else if (!named) {
            title = "Name your profile"; sub = "Enter a profile name above";
        } else if (mMinecraftVersion == null) {
            title = "Choose Minecraft"; sub = "A game version is required";
        } else if (mSelectedLoader != LDR_VANILLA
                && (mSelectedLoader == LDR_JAR ? mJarUri == null : mLoaderVersion == null)) {
            title = "One more step";
            sub = mSelectedLoader == LDR_JAR ? "Pick the .jar installer" : "Pick the loader build";
        } else {
            title = "Ready to create";
            sub = mMinecraftVersion + (mLoaderVersion != null ? "  ·  " + mLoaderVersion : "");
        }
        if (!title.equals(mDockTitle.getText().toString())) {
            float dd = getResources().getDisplayMetrics().density;
            mDockTitle.animate().cancel();
            mDockSub.animate().cancel();
            mDockTitle.setAlpha(0f); mDockTitle.setTranslationY(6f * dd);
            mDockSub.setAlpha(0f);   mDockSub.setTranslationY(6f * dd);
            mDockTitle.setText(title);
            mDockSub.setText(sub);
            mDockTitle.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setStartDelay(0).start();
            mDockSub.animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .setStartDelay(50).start();
        } else {
            mDockSub.setText(sub);
        }
    }

    private void setMcField(String placeholder, boolean enabled, float alpha) {
        mMinecraftField.setEnabled(enabled);
        mMinecraftField.setAlpha(alpha);
        if (mMinecraftVersion == null) {
            mMinecraftVersionValue.setText(placeholder);
            mMinecraftVersionValue.setTextColor(0xFF5C6068);
        }
    }

    /** Opens the system document picker, filtered to .jar. */
    private void pickJarFile() {
        if (mInstalling) return;
        try {
            mJarPickerLauncher.launch(new String[]{"application/java-archive", "*/*"});
        } catch (Throwable t) {
            Toast.makeText(requireContext(), "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Ask the jar which Minecraft it wants. The launcher used to make the user
     * supply that by hand — which is exactly the "fake generic JAR support" this
     * flow was meant to replace — but the answer is inside the file (see
     * {@link JarProbe}), so we read it first and only ask when the file genuinely
     * does not say.
     */
    private void probePickedJar(final android.net.Uri uri) {
        if (uri == null || !isAdded()) return;
        mJarDetectedMc = null;
        mJarProbeEvidence = null;
        mJarLooksLikePlainMod = false;
        mJarUserChose = false;
        mMinecraftVersionValue.setText("Reading the jar\u2026");
        mMinecraftVersionValue.setTextColor(0xFF8FB8E8);
        final String label = jarDisplayName(uri);
        PojavApplication.sExecutorService.execute(() -> {
            JarProbe.Result res;
            try (java.io.InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                res = JarProbe.probeStream(in, label);
            } catch (Throwable t) {
                res = new JarProbe.Result();
                res.evidence = "the picked file could not be opened ("
                        + t.getClass().getSimpleName() + ")";
            }
            final JarProbe.Result out = res;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                applyJarProbe(out, label);
            });
        });
    }

    /**
     * One rule the whole flow obeys: the launcher never downloads a Minecraft version
     * it is not sure about. CERTAIN (the file states its own target) → pre-select it,
     * HINT (a mod's version range, a file name) → the user confirms, NONE → ask.
     */
    private void applyJarProbe(@NonNull JarProbe.Result res, @NonNull String label) {
        mJarProbeEvidence = res.evidence;
        mJarLooksLikePlainMod = res.looksLikePlainMod;
        // The row that used to show only the file name now shows what the launcher
        // actually read out of it.
        mLoaderVersionValue.setText(res.loaderVersion == null
                ? label : label + "  \u00b7  " + res.loaderVersion);
        if (res.isCertain()) {
            mJarDetectedMc = res.minecraftVersion;
            boolean have = false;
            try {
                have = Tools.getVersionInfo(res.minecraftVersion) != null;
            } catch (Throwable ignored) {}
            mMinecraftVersionValue.setText(res.minecraftVersion
                    + "  \u00b7  read from the jar  \u00b7  "
                    + (have ? "already installed" : "will be downloaded"));
            mMinecraftVersionValue.setTextColor(0xFFD2D6DE);
            if (mHint != null && res.evidence != null && !res.evidence.isEmpty()) {
                mHint.setText("The jar names its own target (" + res.evidence
                        + "). Tap the version row to override it.");
            }
            if (mJarLooksLikePlainMod) {
                // A mod is not an installer. Handing one to the JVM as one is how a
                // user ends up with a stack trace and no explanation.
                net.kdt.pojavlaunch.utils.CsPopup.show(requireContext(),
                        "Looks like a mod, not an installer \u2014 use Download Resources instead");
            }
            return;
        }
        mJarDetectedMc = null;
        mMinecraftVersionValue.setText(res.candidates.isEmpty()
                ? "Tap to choose the version"
                : "Tap to confirm  \u00b7  " + res.candidates.iterator().next());
        mMinecraftVersionValue.setTextColor(0xFFE8C989);
        if (mHint != null) {
            mHint.setText(res.evidence == null || res.evidence.isEmpty()
                    ? "The launcher could not tell which Minecraft version this jar needs, so "
                            + "you pick it \u2014 a guess here would download the wrong version."
                    : res.evidence + " \u2014 that is only a hint, so confirm the version yourself.");
        }
    }

    /** True once the user chose explicitly; then the jar's hint stops mattering. */
    private boolean mJarUserChose;


    private String jarDisplayName(android.net.Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "selected .jar";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.length() > 28 ? name.substring(0, 27) + "…" : name;
    }

    // ── Version data for the themed picker (existing metadata systems) ────

    @Override
    public void provideVersions(int purpose, VersionPickerDialog.Receiver receiver) {
        final int loader = mSelectedLoader;
        final String mc = mMinecraftVersion;
        PojavApplication.sExecutorService.execute(() -> {
            try {
                List<String> result = new ArrayList<>();
                if (loader == LDR_NONE) {
                    // Version-first: plain releases, or every loader build
                    // prefixed with its loader name ("Fabric  ·  0.16.9").
                    result = purpose == PURPOSE_MC_VERSION
                            ? loadVanillaVersions() : loadAllLoaderBuilds(mc);
                } else if (purpose == PURPOSE_MC_VERSION) {
                    result = loadMcVersionsForLoader(loader);
                } else {
                    result = loadLoaderVersionsFor(loader, mc);
                }
                if (result == null) throw new IOException("no data");
                if (purpose == PURPOSE_LOADER_VERSION && loader != LDR_NONE && mc == null) {
                    throw new IOException("Minecraft version not selected");
                }
                final List<String> out = result;
                Tools.runOnUiThread(() -> receiver.onVersions(out));
            } catch (Exception e) {
                Tools.runOnUiThread(() -> receiver.onError(e));
            }
        });
    }

    /** Separator between the loader brand and the build in version-first rows. */
    private static final String BRAND_SEP = "  \u00b7  ";
    private static final String[] BRANDS = {"Fabric", "Forge", "NeoForge", "Quilt"};
    private static final int[] BRAND_LOADERS = {LDR_FABRIC, LDR_FORGE, LDR_NEOFORGE, LDR_QUILT};

    /**
     * Version-first list: every loader's builds, newest first per brand, each
     * row tagged with its brand so picking one can select the loader. When a
     * Minecraft version is already chosen the list is filtered to it.
     * Sources that are offline are skipped — one dead metadata server must not
     * empty the whole list.
     */
    private List<String> loadAllLoaderBuilds(@Nullable String mc) {
        List<String> out = new ArrayList<>();
        for (int b = 0; b < BRANDS.length; b++) {
            try {
                List<String> builds;
                int loader = BRAND_LOADERS[b];
                if (mc != null) {
                    builds = loadLoaderVersionsFor(loader, mc);
                } else if (loader == LDR_FABRIC || loader == LDR_QUILT) {
                    FabriclikeUtils utils = loader == LDR_QUILT
                            ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
                    FabricVersion[] all = utils.downloadAllLoaderVersions();
                    builds = new ArrayList<>();
                    if (all != null) for (FabricVersion v : all) if (v.stable) builds.add(v.version);
                } else if (loader == LDR_FORGE) {
                    builds = ForgeUtils.downloadForgeVersions();
                } else {
                    builds = NeoForgeInstallFragment.downloadNeoForgeVersions();
                }
                if (builds == null) continue;
                int cap = mc != null ? builds.size() : Math.min(builds.size(), 60);
                for (int i = 0; i < cap; i++) out.add(BRANDS[b] + BRAND_SEP + builds.get(i));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** Loader builds filtered by the ALREADY-SELECTED Minecraft version. */
    private List<String> loadLoaderVersionsFor(int loader, String mc) throws IOException {
        List<String> out = new ArrayList<>();
        if (mc == null) return out;
        if (loader == LDR_FABRIC || loader == LDR_QUILT) {
            FabriclikeUtils utils = loader == LDR_QUILT
                    ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
            FabricVersion[] loaders = utils.downloadLoaderVersions(mc);
            if (loaders != null) for (FabricVersion v : loaders) out.add(v.version);
        } else if (loader == LDR_FORGE) {
            List<String> versions = ForgeUtils.downloadForgeVersions();
            String prefix = mc + "-";
            if (versions != null) for (String v : versions) if (v.startsWith(prefix)) out.add(v);
        } else if (loader == LDR_NEOFORGE) {
            List<String> versions = NeoForgeInstallFragment.downloadNeoForgeVersions();
            if (versions != null) for (String v : versions) {
                if (mc.equals(neoforgeToMcVersion(v))) out.add(v);
            }
        } else if (loader == LDR_JAR) {
            // No loader-version list at all: the "version" for JAR is the file.
        } else if (loader == LDR_OPTIFINE) {
            OptiFineUtils.OptiFineVersions ofv = OptiFineUtils.downloadOptiFineVersions();
            if (ofv != null) {
                for (int g = 0; g < ofv.minecraftVersions.size(); g++) {
                    if (!mc.equals(optifineGroupToMc(ofv.minecraftVersions.get(g)))) continue;
                    for (OptiFineUtils.OptiFineVersion v : ofv.optifineVersions.get(g)) {
                        if (v.versionName != null) out.add(v.versionName);
                    }
                }
            }
        }
        return out;
    }

    /** "Minecraft 1.21.11" (optifine.net group heading) → "1.21.11". */
    static String optifineGroupToMc(String heading) {
        if (heading == null) return null;
        String h = heading.trim();
        if (h.startsWith("Minecraft ")) h = h.substring("Minecraft ".length()).trim();
        return h;
    }

    /** The OptiFine build object behind a display name for {@code mc} (worker thread). */
    @Nullable
    private static OptiFineUtils.OptiFineVersion findOptiFineBuild(@NonNull String mc, @NonNull String buildName) throws IOException {
        OptiFineUtils.OptiFineVersions ofv = OptiFineUtils.downloadOptiFineVersions();
        if (ofv == null) return null;
        for (int g = 0; g < ofv.minecraftVersions.size(); g++) {
            if (!mc.equals(optifineGroupToMc(ofv.minecraftVersions.get(g)))) continue;
            for (OptiFineUtils.OptiFineVersion v : ofv.optifineVersions.get(g)) {
                if (buildName.equals(v.versionName)) return v;
            }
        }
        return null;
    }

    /** Minecraft versions compatible with the selected loader (its own metadata). */
    private List<String> loadMcVersionsForLoader(int loader) throws IOException {
        List<String> out = new ArrayList<>();
        if (loader == LDR_VANILLA) {
            return loadVanillaVersions();
        } else if (loader == LDR_FABRIC || loader == LDR_QUILT) {
            FabriclikeUtils utils = loader == LDR_QUILT
                    ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
            FabricVersion[] games = utils.downloadGameVersions();
            if (games != null) for (FabricVersion g : games) {
                if (g.stable) out.add(g.version);
            }
        } else if (loader == LDR_FORGE) {
            List<String> versions = ForgeUtils.downloadForgeVersions();
            if (versions != null) for (String v : versions) {
                if (!v.contains("-")) continue;
                String mc = v.substring(0, v.indexOf('-'));
                if (!out.contains(mc)) out.add(mc);
            }
        } else if (loader == LDR_NEOFORGE) {
            List<String> versions = NeoForgeInstallFragment.downloadNeoForgeVersions();
            if (versions != null) for (String v : versions) {
                String mc = neoforgeToMcVersion(v);
                if (!out.contains(mc)) out.add(mc);
            }
        } else if (loader == LDR_JAR) {
            // A custom jar installs onto a plain Minecraft version, so the
            // selectable set is exactly the vanilla release list.
            for (String v : loadVanillaVersions()) {
                if (!v.contains("  ·  ")) out.add(v);
            }
        } else if (loader == LDR_OPTIFINE) {
            OptiFineUtils.OptiFineVersions ofv = OptiFineUtils.downloadOptiFineVersions();
            if (ofv != null) for (String heading : ofv.minecraftVersions) {
                String mc = optifineGroupToMc(heading);
                if (mc != null && !out.contains(mc)) out.add(mc);
            }
        }
        return out;
    }


    /** Vanilla releases (+ labeled snapshots) from the existing release table. */
    private List<String> loadVanillaVersions() {
        List<String> out = new ArrayList<>();
        Object table = ExtraCore.getValue(ExtraConstants.RELEASE_TABLE);
        if (table instanceof JMinecraftVersionList) {
            JMinecraftVersionList list = (JMinecraftVersionList) table;
            if (list.versions != null) {
                for (JMinecraftVersionList.Version v : list.versions) {
                    if (v == null || v.id == null) continue;
                    if ("release".equals(v.type)) out.add(v.id);
                    else if ("snapshot".equals(v.type)) out.add(v.id + "  ·  snapshot");
                }
            }
        }
        return out;
    }

    @Override
    public void onVersionPicked(int purpose, String value) {
        if (value == null) return;
        if (mSelectedLoader == LDR_NONE) {
            // ── Version-first: the pick decides the loader ──
            if (purpose == PURPOSE_MC_VERSION) {
                String id = value.contains(BRAND_SEP) ? value.substring(0, value.indexOf(BRAND_SEP)) : value;
                mMinecraftVersion = id;
                applyLoaderSelection(LDR_VANILLA, true);
                Anime.pop(mMinecraftVersionValue);
                Toast.makeText(requireContext(), "Vanilla selected for " + id, Toast.LENGTH_SHORT).show();
            } else {
                int sep = value.indexOf(BRAND_SEP);
                if (sep <= 0) return;
                String brand = value.substring(0, sep);
                String build = value.substring(sep + BRAND_SEP.length());
                int loader = LDR_NONE;
                for (int b = 0; b < BRANDS.length; b++) if (BRANDS[b].equals(brand)) loader = BRAND_LOADERS[b];
                if (loader == LDR_NONE) return;
                // Forge / NeoForge builds carry their Minecraft version.
                String impliedMc = null;
                if (loader == LDR_FORGE && build.contains("-")) impliedMc = build.substring(0, build.indexOf('-'));
                else if (loader == LDR_NEOFORGE) impliedMc = neoforgeToMcVersion(build);
                if (impliedMc != null) mMinecraftVersion = impliedMc;
                applyLoaderSelection(loader, mMinecraftVersion != null);
                mLoaderVersion = build;
                mLoaderVersionValue.setText(build);
                mLoaderVersionValue.setTextColor(0xFFF2F3F5);
                Anime.pop(mLoaderVersionValue);
                if (impliedMc != null) Anime.pop(mMinecraftVersionValue);
                Toast.makeText(requireContext(), brand + " selected", Toast.LENGTH_SHORT).show();
            }
            applyAutoName(true);
            updateDockStatus();
            return;
        }
        if (purpose == PURPOSE_MC_VERSION) {
            String id = value.contains("  \u00b7  ") ? value.substring(0, value.indexOf("  \u00b7  ")) : value;
            mMinecraftVersion = id;
            if (mSelectedLoader == LDR_JAR) mJarUserChose = true;
            mMinecraftVersionValue.setText(id);
            mMinecraftVersionValue.setTextColor(0xFFF2F3F5);
            // The loader version depends on the Minecraft version — re-pick.
            mLoaderVersion = null;
            mLoaderVersionValue.setText(mSelectedLoader == LDR_JAR
                    ? (mJarName != null ? mJarName : "Choose a .jar file")
                    : "Select loader version");
            mLoaderVersionValue.setTextColor(0xFF5C6068);
            // Phase 9: the loader build is chosen for the user — the newest
            // stable build for this Minecraft version lands in the field by
            // itself (still tappable to change).
            autoSelectLoaderBuild(mSelectedLoader, id);
        } else if (purpose == PURPOSE_LOADER_VERSION) {
            mAutoLoaderToken++; // a manual pick wins over any auto-resolve in flight
            mLoaderVersion = value;
            mLoaderVersionValue.setText(value);
            mLoaderVersionValue.setTextColor(0xFFF2F3F5);
            Anime.pop(mLoaderVersionValue);
        }
        if (purpose == PURPOSE_MC_VERSION) Anime.pop(mMinecraftVersionValue);
        applyAutoName(true);
        updateDockStatus();
    }

    // ── Phase 9: automatic loader build ──────────────────────────────────
    /** Bumped on every auto-resolve request and on manual picks; stale results are dropped. */
    private int mAutoLoaderToken = 0;

    /**
     * Picks the newest STABLE loader build for {@code mc} in the background and
     * writes it into the loader-version field. The user asked for exactly this:
     * choose Fabric + 1.21.4 and the Fabric loader (e.g. 0.16.9) is already
     * filled in — no third tap needed. Manual picks still override it.
     */
    private void autoSelectLoaderBuild(final int loader, @Nullable final String mc) {
        if (mc == null || mc.trim().isEmpty()) return;
        if (loader != LDR_FABRIC && loader != LDR_QUILT && loader != LDR_FORGE && loader != LDR_NEOFORGE && loader != LDR_OPTIFINE) return;
        final int token = ++mAutoLoaderToken;
        mLoaderVersionValue.setText("Finding the newest loader build\u2026");
        mLoaderVersionValue.setTextColor(0xFF8A909C);
        PojavApplication.sExecutorService.execute(() -> {
            String pick = null;
            try {
                pick = resolveNewestLoaderBuild(loader, mc);
            } catch (Throwable ignored) {}
            final String found = pick;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || token != mAutoLoaderToken) return; // superseded
                if (mSelectedLoader != loader || !mc.equals(mMinecraftVersion)) return;
                if (found == null) {
                    mLoaderVersionValue.setText("Select loader version");
                    mLoaderVersionValue.setTextColor(0xFF5C6068);
                    return;
                }
                mLoaderVersion = found;
                mLoaderVersionValue.setText(found + "  \u00b7  auto");
                mLoaderVersionValue.setTextColor(0xFFF2F3F5);
                Anime.pop(mLoaderVersionValue);
                applyAutoName(true);
                updateDockStatus();
            });
        });
    }

    /** Newest stable build of {@code loader} that targets {@code mc}, or null (worker thread). */
    @Nullable
    private String resolveNewestLoaderBuild(int loader, @NonNull String mc) throws IOException {
        if (loader == LDR_FABRIC || loader == LDR_QUILT) {
            FabriclikeUtils utils = loader == LDR_QUILT
                    ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
            FabricVersion[] loaders = utils.downloadLoaderVersions(mc);
            if (loaders == null || loaders.length == 0) return null;
            // The meta server lists newest first; prefer the newest stable one.
            for (FabricVersion v : loaders) if (v != null && v.stable && v.version != null) return v.version;
            return loaders[0] != null ? loaders[0].version : null;
        }
        if (loader == LDR_FORGE) {
            List<String> versions = ForgeUtils.downloadForgeVersions();
            if (versions == null) return null;
            String prefix = mc + "-";
            // maven-metadata is oldest → newest: the last match is the newest build.
            String last = null;
            for (String v : versions) if (v != null && v.startsWith(prefix)) last = v;
            return last;
        }
        if (loader == LDR_OPTIFINE) return resolveNewestOptiFineBuild(mc);
        if (loader == LDR_NEOFORGE) {
            List<String> versions = NeoForgeInstallFragment.downloadNeoForgeVersions();
            if (versions == null) return null;
            String last = null;
            for (String v : versions) {
                if (v == null) continue;
                if (mc.equals(neoforgeToMcVersion(v)) && !v.toLowerCase(java.util.Locale.ROOT).contains("beta")) last = v;
            }
            if (last == null) for (String v : versions) if (v != null && mc.equals(neoforgeToMcVersion(v))) last = v;
            return last;
        }
        return null;
    }

    /** Newest non-preview OptiFine build for {@code mc} (first row of its group is newest). */
    @Nullable
    private static String resolveNewestOptiFineBuild(@NonNull String mc) throws IOException {
        List<String> builds = new ArrayList<>();
        OptiFineUtils.OptiFineVersions ofv = OptiFineUtils.downloadOptiFineVersions();
        if (ofv == null) return null;
        for (int g = 0; g < ofv.minecraftVersions.size(); g++) {
            if (!mc.equals(optifineGroupToMc(ofv.minecraftVersions.get(g)))) continue;
            for (OptiFineUtils.OptiFineVersion v : ofv.optifineVersions.get(g)) if (v.versionName != null) builds.add(v.versionName);
        }
        if (builds.isEmpty()) return null;
        for (String b : builds) if (!b.toLowerCase(java.util.Locale.ROOT).contains("pre")) return b;
        return builds.get(0);
    }

    static String neoforgeToMcVersion(String neoforge) {
        // Same mapping the existing NeoForgeVersionListAdapter uses.
        String[] parts = neoforge.split("\\.");
        if (parts.length < 2) return neoforge;
        try {
            if (Integer.parseInt(parts[1]) < 25) return "1." + parts[0] + "." + parts[1];
            return parts[0] + "." + parts[1];
        } catch (NumberFormatException e) {
            return parts[0] + "." + parts[1];
        }
    }

    private void autoSetMcVersion(String mc) {
        mMinecraftVersion = mc;
        mMinecraftVersionValue.setText(mc + "  (from loader)");
        mMinecraftVersionValue.setTextColor(0xFFD2D6DE);
        Anime.pop(mMinecraftVersionValue);
        applyAutoName(true);
    }

    // ── Picker entry points ───────────────────────────────────────────────

    private void pickLoaderVersion() {
        if (mInstalling) return;
        if (mSelectedLoader == LDR_NONE) {
            VersionPickerDialog.show(this, PURPOSE_LOADER_VERSION,
                    mMinecraftVersion != null ? "Loader builds for " + mMinecraftVersion : "All loader builds", true);
            return;
        }
        if (mMinecraftVersion == null) {
            Toast.makeText(requireContext(), "Select a Minecraft version first", Toast.LENGTH_SHORT).show();
            return;
        }
        String title;
        switch (mSelectedLoader) {
            case LDR_QUILT:    title = "Quilt Loader"; break;
            case LDR_FABRIC:   title = "Fabric Loader"; break;
            case LDR_FORGE:    title = "Forge"; break;
            case LDR_NEOFORGE: title = "NeoForge"; break;
            case LDR_OPTIFINE: title = "OptiFine"; break;
            case LDR_JAR:   // handled by the file picker below
                pickJarFile();
                return;
            default: return;
        }
        VersionPickerDialog.show(this, PURPOSE_LOADER_VERSION, title, true);
    }

    private void pickMinecraftVersion() {
        if (mInstalling) return;
        if (mSelectedLoader == LDR_NONE) {
            VersionPickerDialog.show(this, PURPOSE_MC_VERSION, "Minecraft Versions", true);
            return;
        }
        String brand = mSelectedLoader == LDR_QUILT ? "Quilt"
                : mSelectedLoader == LDR_FABRIC ? "Fabric"
                : mSelectedLoader == LDR_FORGE ? "Forge"
                : mSelectedLoader == LDR_NEOFORGE ? "NeoForge"
                : mSelectedLoader == LDR_OPTIFINE ? "OptiFine"
                : mSelectedLoader == LDR_JAR ? "JAR" : "Minecraft";
        VersionPickerDialog.show(this, PURPOSE_MC_VERSION, brand + " — Minecraft Versions", true);
    }

    // ── Create (completes in this page — no extra wizard screens) ─────────

    private void onCreateClicked() {
        if (mInstalling) return;
        String name = mNameField.getText().toString().trim();
        if (name.isEmpty()) name = suggestedName().trim();
        if (name.isEmpty()) {
            mNameField.setError("Enter a profile name");
            Anime.shake(mNameField);
            mNameField.requestFocus();
            return;
        }
        if (mSelectedLoader == LDR_NONE) {
            Toast.makeText(requireContext(), "Select a loader", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mMinecraftVersion == null) {
            Toast.makeText(requireContext(), "Select a Minecraft version", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mSelectedLoader == LDR_JAR) {
            if (mJarUri == null) {
                Toast.makeText(requireContext(), "Choose the .jar first", Toast.LENGTH_SHORT).show();
                pickJarFile();
                return;
            }
            // A version the jar itself reported counts as selected: asking the user to
            // confirm a fact the file states is what made this feel like a wrapper
            // around OptiFine rather than real jar support.
            if (mMinecraftVersion == null && mJarDetectedMc != null) {
                autoSetMcVersion(mJarDetectedMc);
            }
            if (mMinecraftVersion == null) {
                Toast.makeText(requireContext(),
                        "This jar does not say which Minecraft version it needs \u2014 tap the "
                                + "version row and choose it",
                        Toast.LENGTH_LONG).show();
                pickMinecraftVersion();
                return;
            }
        } else if (mSelectedLoader != LDR_VANILLA && mSelectedLoader != LDR_OPTIFINE && mLoaderVersion == null) {
            // (OptiFine may run without an explicit build: the newest one is resolved)
            Toast.makeText(requireContext(), "Select a loader version", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mSelectedLoader == LDR_VANILLA) {
            mCreateButton.setEnabled(false);
            createVanillaProfile(name);
            return;
        }

        // Mod loaders follow the project's existing online-profile requirement.
        if (!hasOnlineProfile()) {
            hasNoOnlineProfileDialog(requireActivity());
            return;
        }

        mInstalling = true;
        mCreateButton.setEnabled(false);
        mCreateButton.setText("DOWNLOADING…");

        if (mSelectedLoader == LDR_FABRIC || mSelectedLoader == LDR_QUILT) {
            FabriclikeUtils utils = mSelectedLoader == LDR_QUILT
                    ? FabriclikeUtils.QUILT_UTILS : FabriclikeUtils.FABRIC_UTILS;
            // The user's name + icon travel with the task — preserved verbatim.
            new Thread(new FabriclikeDownloadTask(this, utils,
                    mMinecraftVersion, mLoaderVersion, true, name, effectiveIcon())).start();
        } else if (mSelectedLoader == LDR_FORGE) {
            new Thread(new ForgeDownloadTask(this, mLoaderVersion)).start();
        } else if (mSelectedLoader == LDR_NEOFORGE) {
            new Thread(new NeoForgeDownloadTask(this, mLoaderVersion)).start();
        } else if (mSelectedLoader == LDR_OPTIFINE) {
            startOptiFineInstall(mMinecraftVersion, mLoaderVersion);
        } else if (mSelectedLoader == LDR_JAR) {
            new Thread(new CustomJarInstallTask(
                    requireContext().getApplicationContext(), requireActivity(),
                    mJarUri, mMinecraftVersion, this)).start();
        }
    }

    /**
     * Phase 10: OptiFine end to end. Resolves the picked build to its
     * optifine.net entry (or the newest build of the version when none was
     * picked), then runs {@link net.kdt.pojavlaunch.modloaders.OptiFineDownloadTask}:
     * interstitial → tokenised downloadx → jar (validated), the vanilla version
     * downloaded first, and the bundled installer agent finishes the profile.
     */
    private void startOptiFineInstall(@NonNull final String mc, @Nullable final String buildName) {
        mCreateButton.setText("FINDING BUILD…");
        PojavApplication.sExecutorService.execute(() -> {
            OptiFineUtils.OptiFineVersion pick = null;
            String err = null;
            try {
                String name = buildName;
                if (name == null) name = resolveNewestOptiFineBuild(mc);
                if (name != null) pick = findOptiFineBuild(mc, name);
                if (pick == null) err = "No OptiFine build found for " + mc;
            } catch (Throwable t) {
                err = t.getMessage() == null ? "optifine.net unreachable" : t.getMessage();
            }
            final OptiFineUtils.OptiFineVersion found = pick;
            final String error = err;
            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                if (found == null) {
                    resetInstallState();
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
                    return;
                }
                mCreateButton.setText("DOWNLOADING…");
                new Thread(new net.kdt.pojavlaunch.modloaders.OptiFineDownloadTask(found, this, requireActivity())).start();
            });
        });
    }

    // ── ModloaderDownloadListener (installer hand-off = existing system) ──

    @Override
    public void onDownloadFinished(File downloadedFile) {
        // Phase 11 (item 1): snapshot everything the hand-off needs NOW, on the
        // calling thread, from plain fields — the UI-thread hop below may run
        // after this page is gone, and the hand-off must still happen.
        final int loader = mSelectedLoader;
        final String mc = mMinecraftVersion;
        final String loaderVersion = mLoaderVersion;
        final String chosenName = mNameField != null && mNameField.getText() != null
                ? mNameField.getText().toString().trim() : "";
        final String icon = effectiveIcon();
        final Context app = mAppContext;
        Tools.runOnUiThread(() -> {
            boolean attached = isAdded();
            if (attached) resetInstallState();
            if (loader == LDR_FABRIC || loader == LDR_QUILT) {
                // Fabric/Quilt: the task already wrote the profile AND made it
                // current — the spinner resolves the selection from the pref.
                if (attached) net.kdt.pojavlaunch.utils.CsPopup.show(requireContext(), "Instance created");
                net.kdt.pojavlaunch.notifications.CsNotifier.success("Instance created", "Ready to play");
                if (attached) navigateBack();
                return;
            }
            // Forge / NeoForge / OptiFine / JAR: the installer agent creates the
            // profile with its own auto-name. InstallerHandoff records the user's
            // name+icon, starts the installer and reconciles the profile list the
            // moment the installer has written it — whether or not this page is
            // still on screen (the old code silently dropped the hand-off when
            // it was not, which is exactly the "works on the second click" bug).
            String token = null;
            String label = "Profile";
            String loaderTag = "";
            if (loader == LDR_FORGE) {
                label = "Forge"; loaderTag = "forge";
                token = loaderVersion != null && loaderVersion.contains("-")
                        ? loaderVersion.substring(loaderVersion.indexOf('-') + 1) : loaderVersion;
            } else if (loader == LDR_NEOFORGE) {
                label = "NeoForge"; loaderTag = "neoforge";
                token = loaderVersion;
            } else if (loader == LDR_OPTIFINE) {
                label = "OptiFine"; loaderTag = "optifine";
                token = mc != null ? mc + "-OptiFine" : "OptiFine";
            } else if (loader == LDR_JAR) {
                label = "Custom jar"; loaderTag = "";
                token = mc;
            }
            try {
                Context ctx = app != null ? app : requireContext().getApplicationContext();
                Intent intent = new Intent(ctx, JavaGUILauncherActivity.class);
                if (loader == LDR_FORGE) {
                    ForgeUtils.addAutoInstallArgs(intent, downloadedFile, true);
                } else if (loader == LDR_NEOFORGE) {
                    intent.putExtra("javaArgs", "-jar " + downloadedFile.getAbsolutePath() + " --install-client");
                    intent.putExtra("openLogOutput", true);
                } else {
                    OptiFineUtils.addAutoInstallArgs(intent, downloadedFile);
                }
                net.kdt.pojavlaunch.profiles.InstallerHandoff.start(ctx, intent, token,
                        chosenName, icon, label, loaderTag);
            } catch (Throwable t) {
                net.kdt.pojavlaunch.notifications.CsNotifier.error("Installer hand-off failed",
                        t.getMessage() == null ? "Try again" : t.getMessage(), null, null);
            }
            if (attached) navigateBack();
        });
    }

    @Override
    public void onDataNotAvailable() {
        Tools.runOnUiThread(() -> {
            if (!isAdded()) {
                // Page already left: the user still has to hear about it.
                net.kdt.pojavlaunch.notifications.CsNotifier.error("Download failed",
                        "No data available for this version", null, null);
                return;
            }
            resetInstallState();
            Toast.makeText(requireContext(), "No data available for this version", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onDownloadError(Exception e) {
        final String msg = e == null || e.getMessage() == null ? "Unknown error" : e.getMessage();
        Tools.runOnUiThread(() -> {
            if (!isAdded()) {
                net.kdt.pojavlaunch.notifications.CsNotifier.error("Download failed", msg, null, null);
                return;
            }
            resetInstallState();
            Toast.makeText(requireContext(), "Download failed: " + msg, Toast.LENGTH_LONG).show();
        });
    }

    private void resetInstallState() {
        mInstalling = false;
        mCreateButton.setEnabled(true);
        mCreateButton.setText(mSelectedLoader == LDR_VANILLA || mSelectedLoader == LDR_FABRIC
                || mSelectedLoader == LDR_QUILT ? "CREATE" : "DOWNLOAD & CREATE");
        if (mSelectedLoader == LDR_JAR) {
            mLoaderVersionValue.setText(mJarName != null ? mJarName : "Choose a .jar file");
        }
    }

    /** Vanilla: create the profile directly with the chosen name/icon/version,
     *  then hand the user to the existing Profile Studio. */
    private void createVanillaProfile(String name) {
        PojavApplication.sExecutorService.execute(() -> {
            LauncherProfiles.load();
            MinecraftProfile profile = MinecraftProfile.createTemplate();
            profile.name = name;
            profile.lastVersionId = mMinecraftVersion;
            String icon = effectiveIcon();
            if (icon != null) profile.icon = icon;

            String key = LauncherProfiles.getFreeProfileKey();
            LauncherProfiles.mainProfileJson.profiles.put(key, profile);
            LauncherProfilesShim.setCurrentProfile(key);
            LauncherProfiles.write();
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, key);

            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                mCreateButton.setEnabled(true);
                net.kdt.pojavlaunch.utils.CsPopup.show(requireContext(), "Profile created");
                net.kdt.pojavlaunch.notifications.CsNotifier.success("Profile created", "Ready to play");
                // Open the existing Profile Studio for the new profile. Args are null so
                // the studio loads the profile we just made current via the pref above.
                navigateTo(ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
            });
        });
    }

    // ── Icon cropper callback ─────────────────────────────────────────────

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mIconView.setImageBitmap(contentBitmap);
        mIconView.setPadding(0, 0, 0, 0);
        mIconView.setColorFilter(null);
        mIconView.setClipToOutline(true);
        mIconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mIconView.setBackgroundResource(R.drawable.bg_creation_icon_well);
        Anime.pop(mIconView);
        PojavApplication.sExecutorService.execute(() -> {
            String dataUri = encodeIcon(contentBitmap);
            Tools.runOnUiThread(() -> mEncodedIcon = dataUri);
        });
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }

    /** The user's cropped icon, else the CS Launcher logo (null only if encoding failed). */
    @Nullable
    private String effectiveIcon() {
        if (mEncodedIcon != null) return mEncodedIcon;
        if (mDefaultIcon == null && isAdded()) {
            // CREATE tapped before the background encode landed: the loader
            // marks are tiny, so encoding inline here is a few milliseconds.
            try {
                Bitmap bmp = renderDrawable(defaultIconRes(mSelectedLoader), 192);
                if (bmp != null) mDefaultIcon = encodeIcon(bmp);
            } catch (Throwable ignored) {}
        }
        return mDefaultIcon;
    }

    private static String encodeIcon(Bitmap bmp) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             Base64OutputStream b64 = new Base64OutputStream(bos, Base64.NO_WRAP)) {
            bmp.compress(Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                            ? Bitmap.CompressFormat.WEBP : Bitmap.CompressFormat.WEBP_LOSSY,
                    60, b64);
            b64.flush();
            bos.flush();
            return "data:image/webp;base64," + new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Navigation (same rules as all other creation fragments) ───────────

    private void navigateTo(Class<? extends Fragment> cls, String tag, @Nullable Bundle args) {
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

    private void navigateBack() {
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).clearRightPane();
        } else {
            Tools.removeCurrentFragment(requireActivity());
        }
    }

    /** Small indirection to avoid importing preferences at field-init time. */
    static final class LauncherProfilesShim {
        static void setCurrentProfile(String key) {
            net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key)
                    .apply();
        }
    }
}
