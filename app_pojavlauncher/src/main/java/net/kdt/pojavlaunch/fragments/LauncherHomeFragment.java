package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.mcgui.mcAccountSpinner;
import net.kdt.pojavlaunch.PojavProfile;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.tutorial.DragTutorialHost;
import net.kdt.pojavlaunch.tutorial.HomeTutorial;
import net.kdt.pojavlaunch.ui.PremiumPlayButtonView;
import net.kdt.pojavlaunch.ui.SkinGLRenderer;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.time.Instant;
import android.widget.ProgressBar;

import com.kdt.mcgui.ProgressLayout;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  LAUNCHER HOME — "STAGE" (complete redesign)
 *
 *  The screen itself becomes the primary instance: its artwork fills the
 *  display under a legibility scrim, and every control floats on glass.
 *
 *    left dock .......... Home / Cursor / Controls / About / Settings
 *    account chip ....... avatar + name + auth badge → account dialog
 *    3D player .......... real skin, breathing bob + one-shot greeting wave
 *    launch cluster ..... eyebrow / instance name / meta / PLAY capsule
 *    continue pill ...... most-recent NON-primary instance, one-tap relaunch
 *    library carousel ... every instance as an artwork card + New Instance tile
 *
 *  Primary instance = first entry of the saved order (LauncherProfiles).
 *  Tap a card → it becomes primary (whole stage re-themes + background
 *  crossfades). Long-press → drag reorder. ⋮ → contextual action sheet.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class LauncherHomeFragment extends Fragment implements DragTutorialHost {

    public static final String TAG = "LauncherHomeFragment";

    private List<String> mKeys = new ArrayList<>();
    private List<MinecraftProfile> mProfiles = new ArrayList<>();
    private String mPrimaryKey = null;
    private int mPrimaryMods = -1;

    // Background crossfade pair
    private ImageView mBgA, mBgB;
    private boolean mBgFrontIsA = true;

    // Stage
    private TextView mLaunchLabel;
    private TextView mHeroName;
    private PremiumPlayButtonView mLaunchButton;
    private View mLaunchSpecs;
    private TextView mSpecJavaText;
    private TextView mSpecRamText;
    private android.opengl.GLSurfaceView mPlayer;
    private SkinGLRenderer mRenderer;
    /** Delayed re-fetch budget when the character had to fall back to Steve. */
    private int mSkinRetries = 0;
    // Light-weight pose driver: idles the shared GL renderer only while visible.
    private android.animation.ValueAnimator mPoseAnim;
    private static final float PLAYER_BASE_YAW = -32f;

    // Chrome
    private LinearLayout mAccountChip;
    private ImageView mAccountHead;
    private TextView mAccountName, mAccountType;
    private String mLastAccountLabel = null;
    private View mEmptyHint;

    // Library
    private RecyclerView mLibrary;
    private InstanceLibraryAdapter mAdapter;
    private ItemTouchHelper mTouchHelper;
    private boolean mLibraryIntroduced = false;

    // ── Drag tutorial host (transient demo cards, never persisted) ──
    private static final String DEMO_KEY_STEVE = "\0tutorial.demo.steve";
    private static final String DEMO_KEY_ALEX  = "\0tutorial.demo.alex";
    @Nullable private DragTutorialHost.DemoPracticeListener mDemoPracticeListener;
    private boolean mPracticeMovedThisGesture = false;

    // ── Compact download progress pill ──
    private View mDlPill;
    private View mDlIconWrap;
    private TextView mDlTitle, mDlPercent, mDlDetail;
    private ProgressBar mDlBar;
    private boolean mDlListening = false;
    private android.animation.ValueAnimator mDlIconPulse;
    private android.animation.ValueAnimator mDlShimmer;
    private int mDlLastProgress = 0;

    public LauncherHomeFragment() {
        super(R.layout.fragment_launcher_home);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mBgA = view.findViewById(R.id.lh_bg_a);
        mBgB = view.findViewById(R.id.lh_bg_b);
        mLaunchButton = view.findViewById(R.id.lh_launch_button);
        mLaunchLabel = view.findViewById(R.id.lh_launch_label);
        mLaunchSpecs = view.findViewById(R.id.lh_launch_specs);
        mSpecJavaText = view.findViewById(R.id.lh_spec_java_text);
        mSpecRamText = view.findViewById(R.id.lh_spec_ram_text);
        mHeroName = view.findViewById(R.id.lh_hero_name);
        mPlayer = view.findViewById(R.id.lh_player);
        setupPlayer();
        mAccountChip = view.findViewById(R.id.lh_account_chip);
        mAccountHead = view.findViewById(R.id.lh_account_head);
        mAccountName = view.findViewById(R.id.lh_account_name);
        mAccountType = view.findViewById(R.id.lh_account_type);
        mEmptyHint = view.findViewById(R.id.lh_empty);
        mLibrary = view.findViewById(R.id.lh_library);

        mDlPill = view.findViewById(R.id.lh_dl_pill);
        mDlIconWrap = view.findViewById(R.id.lh_dl_icon_wrap);
        mDlTitle = view.findViewById(R.id.lh_dl_title);
        mDlPercent = view.findViewById(R.id.lh_dl_percent);
        mDlDetail = view.findViewById(R.id.lh_dl_detail);
        mDlBar = view.findViewById(R.id.lh_dl_bar);

        setupLibrary();

        // PENCIL → edit page of the SELECTED profile (same flow as ⋮ Edit Instance).
        View editBtn = view.findViewById(R.id.lh_edit_btn);
        if (editBtn != null) {
            UiMotion.pressFeedback(editBtn);
            editBtn.setOnClickListener(v -> {
                if (mPrimaryKey == null) return;
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, mPrimaryKey).apply();
                navigateTo(ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
            });
        }

        // Play / create — the one dominant action.
        mLaunchButton.setOnClickListener(v -> {
            if (mLaunchButton.isStopMode()) {
                mLaunchButton.cancelPendingLaunch();
                cancelLaunchVisuals();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                return;
            }
            if (mPrimaryKey == null) {
                CreationTypeDialog.show(this);
                return;
            }
            mLaunchButton.beginLaunch();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            playLaunchAnimationThenLaunch(mPrimaryKey);
        });

        mAccountChip.setOnClickListener(v -> switchAccount());

        // ── Brand cluster (top-left, Phase 8): YouTube red / Discord blurple.
        //    Slides in from the left while the account chip lands on the right;
        //    the pills pop one after another (anime stagger). ──
        View brand = view.findViewById(R.id.lh_brand_cluster);
        View yt = view.findViewById(R.id.lh_btn_youtube);
        View dc = view.findViewById(R.id.lh_btn_discord);
        if (yt != null) yt.setOnClickListener(v -> {
            net.kdt.pojavlaunch.Anime.pop(v);
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            net.kdt.pojavlaunch.CsLinks.open(v.getContext(), net.kdt.pojavlaunch.CsLinks.YOUTUBE);
        });
        if (dc != null) dc.setOnClickListener(v -> {
            net.kdt.pojavlaunch.Anime.pop(v);
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            net.kdt.pojavlaunch.CsLinks.open(v.getContext(), net.kdt.pojavlaunch.CsLinks.DISCORD);
        });
        if (brand != null) {
            net.kdt.pojavlaunch.Anime.in(brand, net.kdt.pojavlaunch.Anime.Fx.FADE_RIGHT, 80, 620, net.kdt.pojavlaunch.Anime.OUT_EXPO);
            net.kdt.pojavlaunch.Anime.in(yt, net.kdt.pojavlaunch.Anime.Fx.POP, 420, 520, net.kdt.pojavlaunch.Anime.OUT_BACK);
            net.kdt.pojavlaunch.Anime.in(dc, net.kdt.pojavlaunch.Anime.Fx.POP, 500, 520, net.kdt.pojavlaunch.Anime.OUT_BACK);
            net.kdt.pojavlaunch.Anime.in(mAccountChip, net.kdt.pojavlaunch.Anime.Fx.FADE_LEFT, 80, 620, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        }

        // One pass of elastic press feedback for everything on the stage.
        UiMotion.attachTouchFeedback(view.findViewById(R.id.lh_content));

        // ── Display-cutout safety (works for top-left / top-center / top-right
        //    punch-holes and any orientation — never hardcoded). The artwork
        //    stays full-bleed behind; only the interactive layer is padded out
        //    of the real cutout area using platform insets. ──
        final View content = view.findViewById(R.id.lh_content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int l = 0, t = 0, r = 0, b = 0;
            androidx.core.view.DisplayCutoutCompat cut = insets.getDisplayCutout();
            if (cut != null) {
                l = cut.getSafeInsetLeft();
                t = cut.getSafeInsetTop();
                r = cut.getSafeInsetRight();
                b = cut.getSafeInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        // The activity consumes insets at its root, so the listener above may
        // never fire under edge-to-edge. Apply the real cutout padding directly
        // from the root window insets as well, so the account chip / stage stay
        // clear of a notch in fullscreen landscape regardless of dispatch.
        content.post(() -> applyCutoutPaddingDirect(content));

        // Background artwork: dim + desaturate so it stays atmospheric and
        // never competes with the player, titles or controls.
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                0.90f, 0, 0, 0, 0,
                0, 0.90f, 0, 0, 0,
                0, 0, 0.94f, 0, 0,
                0, 0, 0, 1, 0});
        android.graphics.ColorMatrixColorFilter filter = new android.graphics.ColorMatrixColorFilter(cm);
        mBgA.setColorFilter(filter);
        mBgB.setColorFilter(filter);

        playEntrance(view);

        // Real account switches (spinner pick / add / remove) refresh Home instantly.
        ExtraCore.addExtraListener(ExtraConstants.ACCOUNT_CHANGED, mAccountChangedListener);

        // Expose the drag-tutorial host before the tutorial may start.
        DragTutorialHost.Registry.register(this);

        // First-launch chain: runtime installer → PLUS thank-you popup → Home
        // tutorial. The popup gates the tour (shown exactly once); once it has
        // been seen the tutorial auto-starts here as before.
        // Deferred one frame so every target view is measured first.
        view.post(() -> {
            if (!isAdded() || getActivity() == null) return;
            if (PlusWelcomeDialog.maybeShow(getActivity())) return;
            HomeTutorial.maybeStart(getActivity());
        });
    }

    /**
     * Pads the interactive layer out of the real display cutout using the root
     * window insets directly — independent of the inset-dispatch chain (the
     * activity consumes insets at its root). Full-bleed artwork stays behind;
     * only controls are inset. Safe on all orientations / cutout positions.
     */
    private void applyCutoutPaddingDirect(@NonNull View content) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            android.view.WindowInsets wi = content.getRootWindowInsets();
            if (wi == null) return;
            android.view.DisplayCutout cut = wi.getDisplayCutout();
            if (cut == null) { content.setPadding(0, 0, 0, 0); return; }
            content.setPadding(cut.getSafeInsetLeft(), cut.getSafeInsetTop(),
                    cut.getSafeInsetRight(), cut.getSafeInsetBottom());
        } catch (Throwable ignored) {}
    }

    /** Rebinds chip + 3D player whenever the selected account really changes. */
    /**
     * Phase 11 (item 1): profiles written behind Home's back (a modloader
     * installer that just finished, a rename applied by InstallerHandoff) show
     * up without leaving and re-entering the page. Registered while resumed only.
     */
    private final Runnable mProfilesWrittenListener = () -> {
        if (!isAdded() || getView() == null) return;
        // From memory only: LauncherProfiles.load() itself fires this listener,
        // so calling loadData() (which loads) from here would loop forever.
        rebindProfilesFromMemory();
    };

    private final ExtraListener<String> mAccountChangedListener = (key, value) -> {
        if (!isAdded()) return false;
        bindAccount();
        loadPlayerSkin();
        // Replay the "Hello" greeting so the new character introduces itself.
        mPoseStartMs = android.os.SystemClock.uptimeMillis();
        try {
            net.kdt.pojavlaunch.value.MinecraftAccount acc =
                    PojavProfile.getCurrentProfileContent(requireContext(), null);
            if (acc != null && acc.username != null)
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Account switched", acc.username);
        } catch (Throwable ignored) {}
        return false;
    };


    // ── 3D player (SHARED renderer — the exact Skin Management pipeline) ───

    private void setupPlayer() {
        if (mPlayer == null) return;
        // A GLSurfaceView accepts setRenderer() exactly once. If this view
        // instance is already wired, just restart the pose driver + skin load.
        if (Boolean.TRUE.equals(mPlayer.getTag())) {
            startPoseDriver();
            loadPlayerSkin();
            return;
        }
        mRenderer = new SkinGLRenderer(requireContext());
        mRenderer.mAngleX = PLAYER_BASE_YAW;   // front + char-left three-quarter
        mRenderer.mAngleY = 5f;
        // Bigger, centred figure. Zoom is as large as possible while still
        // keeping the WHOLE body — raised (left) hand up beside the head and
        // both legs/feet — inside the surface. (Home framing only; Skin
        // Management is separate.) The GL surface fills the middle band and is
        // horizontally centred over the LAUNCH capsule.
        // Framing tuned so the WHOLE figure fits inside the band whose top is
        // the name tag: the head + raised hand keep clear top headroom (no
        // clip at the surface's top edge) and the feet stay above the launch
        // row. The band's top is now the account chip (tall), so the head, the
        // raised hand AND the in-scene name tag above the head all have clear
        // headroom and none of them touches the surface's top edge = no clip
        // line. Skin size is unchanged (kept large per user request).
        // Slightly bigger figure than last build (0.92 -> 0.96). A NEGATIVE
        // vertical shift pushes the whole figure DOWN in the frame, keeping the
        // head + raised (left) hand clear of the ortho frustum's top edge so
        // NOTHING clips (empirically -1.4 was clean; -1.5 keeps that margin at
        // the larger zoom), while the feet stay above the launch row.
        mRenderer.mZoomFactor = 1.06f;
        mRenderer.mVerticalShift = -1.9f;
        // The 3/4-view yaw + raised left hand push the visible body mass toward
        // screen-right, so the figure reads as offset from the LAUNCH button
        // axis. Nudge the whole scene left so the head/torso sit dead-centre
        // over the button. (Home framing only; Skin Management is untouched.)
        mRenderer.mHorizontalShift = -2.8f;
        mRenderer.mAutoRotate = false;
        // Home draws its name tag in the UI layer (lh_hero_name TextView) above
        // the GL surface. The surface top is anchored BELOW that TextView, so it
        // never paints over the tag. Disable the in-scene GL name plate to keep
        // exactly ONE name tag (and because the GL plate sits outside the ortho
        // frustum and would be clipped anyway).
        mRenderer.setNametag(null);
        // Persist GL phase markers so a native driver crash is diagnosable.
        SkinGLRenderer.sBreadcrumbFile = new java.io.File(
                requireContext().getFilesDir(), "gl_breadcrumb.txt");
        // EGL config / surface format / z-order MUST be set BEFORE setRenderer():
        // after it, GLSurfaceView rejects every config call (IllegalStateException).
        try {
            // ROOT CAUSE of the silent Home crash: GLSurfaceView creates a
            // GLES *1.0* context by default. This renderer is pure GLES20 —
            // calling ES2 entry points on an ES1 context SIGSEGVs natively
            // inside the vendor driver (no Java exception = no crash report).
            // The working Skin Management page sets exactly this before its
            // renderer; Home must mirror it.
            mPlayer.setEGLContextClientVersion(2);
            mPlayer.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            // See-through compositing: the GL surface must float ABOVE the window
            // layer with an alpha channel — media-overlay translucency renders as
            // an opaque BLACK rectangle on several OEM compositors, which is the
            // black box bug. Nothing overlaps the player's bounds here (Manage
            // Skin sits below it, instance cards below the column), so on-top is
            // the safe, canonical transparent-SurfaceView recipe.
            mPlayer.setZOrderOnTop(true);
            mPlayer.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
            mPlayer.setRenderer(mRenderer);
            // Battery-safe: frames are drawn only when we ask (pose ticks / skin swap).
            mPlayer.setRenderMode(android.opengl.GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            mPlayer.setTag(Boolean.TRUE);
        } catch (Throwable t) {
            // This device refused the GL setup — Home must survive without the
            // 3D player rather than crash-loop on every launch.
            android.util.Log.e("LauncherHome", "3D player disabled on this device", t);
            mRenderer = null;
            mPlayer.setVisibility(android.view.View.GONE);
            return;
        }
        // Tapping the character opens Skin Management — restricted to actual character bounds.
        mPlayer.setClickable(true);
        final float density = getResources().getDisplayMetrics().density;
        final float characterHalfWidth = 85f * density;
        mPlayer.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downX, downY;
            private boolean downOnCharacter;

            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                float x = event.getX();
                float y = event.getY();
                float cx = v.getWidth() / 2f;
                float top = v.getHeight() * 0.10f;
                float bottom = v.getHeight() * 0.95f;
                boolean onCharacter = Math.abs(x - cx) <= characterHalfWidth && y >= top && y <= bottom;

                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = x;
                        downY = y;
                        downOnCharacter = onCharacter;
                        return onCharacter;
                    case android.view.MotionEvent.ACTION_UP:
                        if (downOnCharacter && onCharacter) {
                            float dx = Math.abs(x - downX);
                            float dy = Math.abs(y - downY);
                            if (dx < 20f * density && dy < 20f * density) {
                                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                                Tools.swapFragment(requireActivity(), SkinLibraryOverviewFragment.class,
                                        SkinLibraryOverviewFragment.TAG, null);
                                return true;
                            }
                        }
                        downOnCharacter = false;
                        break;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        downOnCharacter = false;
                        break;
                }
                return false;
            }
        });
        startPoseDriver();
        loadPlayerSkin();
    }

    /** Loads the active account's skin into the shared renderer (async). */
    private void loadPlayerSkin() {
        if (mRenderer == null) return;
        final android.content.Context ctx = getContext() == null ? null : getContext().getApplicationContext();
        if (ctx == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            android.graphics.Bitmap skin = null;
            android.graphics.Bitmap cape = null;
            String name = null;
            try {
                MinecraftAccount account = PojavProfile.getCurrentProfileContent(ctx, null);
                if (account != null && account.username != null
                        && !account.username.trim().isEmpty()) {
                    name = account.username;
                    // SAME source as the Skin Management page: the launcher's
                    // per-account skin file. Microsoft/premium skins are written
                    // here by updateOfficialSkin(); local/ely.by skins too.
                    File f = new File(Tools.DIR_DATA + "/skins/" + account.username + "_skin.png");
                    // Phase 10: a premium account also re-validates a sheet that
                    // is NOT marked as its exact Mojang skin (stale local/default
                    // sheet of the same name) — the home 3D + head then swap to
                    // the real skin as soon as it lands.
                    boolean stalePremium = account.isMicrosoft && !account.isSkinSlotManaged()
                            && !net.kdt.pojavlaunch.skins.SkinResolver.isPremiumCache(f, account);
                    if (!net.kdt.pojavlaunch.skins.SkinResolver.isUsableSkin(f) || stalePremium) {
                        // Not cached yet: resolve per account kind (Microsoft →
                        // Mojang UUID; ely.by → skinsystem https; local → Mojang
                        // by name / ely.by / mc-heads) and cache it where every
                        // other skin surface reads. Falls back to the default
                        // character offline.
                        try {
                            File got = net.kdt.pojavlaunch.skins.SkinResolver.resolve(account);
                            if (got != null) {
                                // Phase 9: the 3D head (top chip + nav icon) is
                                // rendered from the same sheet — drop its cached
                                // render so it picks the real skin up too.
                                net.kdt.pojavlaunch.ui.SkinHead3DRenderer.invalidate(account.username);
                                // Phase 11: the flat face PNG (account list /
                                // nav icon) was cut from the OLD sheet — drop
                                // it so every surface re-reads the new skin.
                                try { new File(Tools.DIR_CACHE, account.username + ".png").delete(); } catch (Throwable ignored) {}
                                try { new File(Tools.DIR_DATA + "/skins/" + account.username + "_face.png").delete(); } catch (Throwable ignored) {}
                                final MinecraftAccount headAcc = account;
                                Tools.runOnUiThread(() -> {
                                    if (!isAdded()) return;
                                    loadAccountHead(headAcc);
                                    Activity a = getActivity();
                                    if (a instanceof net.kdt.pojavlaunch.LauncherActivity)
                                        ((net.kdt.pojavlaunch.LauncherActivity) a).updateNavSkinIcon();
                                });
                            }
                        }
                        catch (Throwable ignored) {}
                    }
                    if (f.isFile()) {
                        try { skin = net.kdt.pojavlaunch.skins.SkinResolver.decode(f); }
                        catch (Throwable ignored) {}
                    }
                    // Cape parity with Skin Management (optional, may be absent).
                    File capeFile = new File(Tools.DIR_DATA + "/capes/" + account.username + "_cape.png");
                    if (capeFile.isFile()) {
                        try { cape = android.graphics.BitmapFactory.decodeFile(capeFile.getAbsolutePath()); }
                        catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            // Robust fallback: a valid cached skin is always preferred; only
            // when none is available do we show the bundled default character.
            final boolean usedFallback = name != null && (skin == null || skin.getWidth() < 64);
            if (skin == null || skin.getWidth() < 64) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inScaled = false;
                skin = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.cs_default_skin, o);
            }
            final android.graphics.Bitmap fSkin = skin;
            final android.graphics.Bitmap fCape = cape;
            final String fName = name;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || mRenderer == null) return;
                mRenderer.setTexture(fSkin, fCape);
                // A fresh login writes the skin file a moment after the account
                // appears; one delayed retry catches it without polling.
                if (usedFallback && mSkinRetries < 3 && mPlayer != null) {
                    mSkinRetries++;
                    mPlayer.postDelayed(() -> { if (isAdded()) loadPlayerSkin(); }, 2500L * mSkinRetries);
                } else if (!usedFallback) {
                    mSkinRetries = 0;
                }
                // The name tag is the UI-layer TextView (lh_hero_name); the GL
                // nametag stays disabled so the raised hand is never clipped.
                if (mPlayer != null) mPlayer.requestRender();
            });
        });
    }


    // ── Greeting state machine ("Hello 👋") ─────────────────────────────────
    //
    // IMPORTANT: SkinGLRenderer applies every pose field through
    // Matrix.rotateM(), which takes DEGREES. The previous rig fed radian-sized
    // numbers (arm = 2.62 => 2.6°), so the "raised hand" was invisible. All the
    // constants below are therefore in DEGREES.
    //
    // The greeting is driven by the actual LEFT-ARM shoulder joint (mPoseLeftArmZ)
    // — the whole body is never rotated to fake it. Timeline (ms):
    //   0 ─ NOTICE ─ 380 ─ RAISE ─ 900 ─ WAVE ─ 2100 ─ (settle) ─ 2450 ─> HOLD
    // After the one-shot greeting the character HOLDS a relaxed left-hand-up
    // pose, kept alive by breathing / micro-sway / occasional glances.
    private long mPoseStartMs = 0L;
    private static final float GREET_ARM_UP_DEG   = 156f; // shoulder lift: hand up beside the head
    private static final float GREET_ARM_HOLD_DEG = 150f; // relaxed held pose (hand stays UP) between waves
    private static final float GREET_ARM_OUT_DEG  = 10f;  // slight outward (X) so the hand clears the head
    // Greeting timeline (ms): 0 ─ NOTICE ─ RAISE ─ WAVE ─ SETTLE ─> HOLD(loop)
    private static final long  T_NOTICE = 340L, T_RAISE = 860L, T_WAVE = 2200L, T_SETTLE = 2520L;
    // While HOLDing (hand up), replay a friendly wave every this-many ms.
    private static final long  REWAVE_PERIOD = 5200L, REWAVE_DUR = 1500L;

    /**
     * One light animator drives the whole greet + idle performance and requests
     * GL frames on demand — nothing renders when Home is not visible.
     */
    private void startPoseDriver() {
        stopPoseDriver();
        mPoseStartMs = android.os.SystemClock.uptimeMillis();
        mPoseAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        mPoseAnim.setDuration(4000);
        mPoseAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mPoseAnim.setInterpolator(new android.view.animation.LinearInterpolator());
        mPoseAnim.addUpdateListener(a -> drivePose());
        mPoseAnim.start();
    }

    private void stopPoseDriver() {
        if (mPoseAnim != null) { mPoseAnim.cancel(); mPoseAnim = null; }
    }

    /**
     * Advanced "Hello" rig. A one-shot greeting (notice → raise → wave → hold)
     * plays every time Home becomes visible; afterwards the character holds a
     * friendly left-hand-up pose with subtle life. All angles are DEGREES.
     */
    private void drivePose() {
        if (mRenderer == null || mPlayer == null) return;

        long t = android.os.SystemClock.uptimeMillis() - mPoseStartMs;
        // Continuous, slow "alive" clock (seconds) for breathing / sway.
        float sec = t / 1000f;
        float breathe = (float) Math.sin(sec * 1.7f);          // ~0.27 Hz
        float sway    = (float) Math.sin(sec * 0.9f);

        float armZ, armX, headYaw, headPitch;

        if (t < T_NOTICE) {
            // NOTICE — arm still down, head turns toward the user.
            float k = smooth(t / (float) T_NOTICE);
            armZ = 4f;
            armX = 0f;
            headYaw = -7f * k;
            headPitch = -3f * k;
        } else if (t < T_RAISE) {
            // RAISE — smooth eased lift of the LEFT arm from the side to up.
            float k = smooth((t - T_NOTICE) / (float) (T_RAISE - T_NOTICE));
            armZ = 4f + (GREET_ARM_UP_DEG - 4f) * k;
            armX = GREET_ARM_OUT_DEG * k;
            headYaw = -7f;
            headPitch = -3f - 2f * k;
        } else if (t < T_WAVE) {
            // WAVE — natural side-to-side wave from the shoulder joint, with a
            // soft ease-in/ease-out envelope so it starts and stops smoothly.
            float p = (t - T_RAISE) / (float) (T_WAVE - T_RAISE);
            float wave = (float) Math.sin(p * Math.PI * 6.0);   // 3 full waves
            float envelope = (float) Math.sin(p * Math.PI);     // ease in/out
            armZ = GREET_ARM_UP_DEG + wave * 17f * envelope;    // swing amplitude
            armX = GREET_ARM_OUT_DEG + wave * 3f * envelope;    // tiny forward/back
            headYaw = -6f + wave * 2.2f * envelope;             // head bobs with the wave
            headPitch = -5f;
        } else if (t < T_SETTLE) {
            // SETTLE — ease from the raised wave into the relaxed HOLD pose
            // (hand stays UP the whole time — it never drops back down).
            float k = smooth((t - T_WAVE) / (float) (T_SETTLE - T_WAVE));
            armZ = GREET_ARM_UP_DEG + (GREET_ARM_HOLD_DEG - GREET_ARM_UP_DEG) * k;
            armX = GREET_ARM_OUT_DEG;
            headYaw = -6f * (1f - k);
            headPitch = -5f + 2f * k;
        } else {
            // HOLD — hand stays raised, character stays alive: gentle breathing
            // micro-wave, occasional glance, AND a full friendly re-wave every
            // few seconds so it keeps saying "hello" without ever lowering.
            long hold = (t - T_SETTLE) % REWAVE_PERIOD;
            float micro = (float) Math.sin(sec * 2.1f) * 3.0f;   // small living wave
            float glance = (float) Math.sin(sec * 0.5f);
            if (hold < REWAVE_DUR) {
                // periodic full wave burst
                float p = hold / (float) REWAVE_DUR;
                float wave = (float) Math.sin(p * Math.PI * 4.0);   // 2 waves
                float envelope = (float) Math.sin(p * Math.PI);
                armZ = GREET_ARM_HOLD_DEG + wave * 15f * envelope;
                armX = GREET_ARM_OUT_DEG + wave * 2.5f * envelope;
                headYaw = wave * 2.0f * envelope;
                headPitch = -3f + breathe * 0.8f;
            } else {
                armZ = GREET_ARM_HOLD_DEG + micro;
                armX = GREET_ARM_OUT_DEG + breathe * 1.2f;
                headYaw = glance * 5f;
                headPitch = -3f + breathe * 0.8f;
            }
        }

        // Whole-scene: only breathing bob + a hair of yaw sway. Body is NOT spun.
        mRenderer.mAngleX = PLAYER_BASE_YAW + sway * 1.4f;
        mRenderer.mPoseBobY = breathe * 0.22f;

        // Head joint.
        mRenderer.mPoseHeadYaw = headYaw;
        mRenderer.mPoseHeadPitch = headPitch;

        // Torso: subtle shoulder/chest life (degrees).
        mRenderer.mPoseTorsoZ = sway * 1.1f;
        mRenderer.mPoseTorsoX = breathe * 0.8f;

        // LEFT arm = the greeting arm (real shoulder joint). RIGHT arm relaxed.
        mRenderer.mPoseLeftArmZ = armZ;
        mRenderer.mPoseLeftArmX = armX;
        mRenderer.mPoseRightArmZ = 2f + breathe * 1.5f;
        mRenderer.mPoseRightArmX = sway * 1.2f;

        // Legs: barely-there weight shift so the stance isn't stiff.
        mRenderer.mPoseLeftLegX = sway * 1.0f;
        mRenderer.mPoseRightLegX = -sway * 1.0f;

        mPlayer.requestRender();
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3 - 2 * t);
    }

    // ── Entrance choreography ───────────────────────────────────────────────

    private void playEntrance(@NonNull View root) {
        UiMotion.revealScreen(root);
        root.post(() -> {
            // Player rises out of the ground and greets.
            if (mPlayer != null) {
                mPlayer.setAlpha(0f);
                mPlayer.setTranslationY(30f);
                mPlayer.setScaleX(0.94f);
                mPlayer.setScaleY(0.94f);
                mPlayer.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(480).start();
            }
            UiMotion.heroIn(mLaunchButton);
            UiMotion.slideIn(mLibrary, 2, 140);
            UiMotion.slideIn(mAccountChip, 1, 90);
        });
    }

    // ── Data ────────────────────────────────────────────────────────────────

    // ── Compact download progress pill ───────────────────────────────────────
    private final ProgressListener mDlListener = new ProgressListener() {
        @Override public void onProgressStarted() {
            if (getActivity() != null) getActivity().runOnUiThread(() -> showDlPill(true));
        }
        @Override public void onProgressUpdated(int progress, int resid, Object... va) {
            if (getActivity() == null) return;
            final String detail = extractDlDetail(va);
            final String title = safeResString(resid);
            getActivity().runOnUiThread(() -> {
                showDlPill(true);
                if (mDlBar != null && progress >= 0) animateBarTo(Math.min(100, progress));
                if (mDlPercent != null) mDlPercent.setText((progress >= 0 ? progress : 0) + "%");
                if (mDlTitle != null && title != null) mDlTitle.setText(title);
                if (mDlDetail != null) {
                    mDlDetail.setText(detail == null ? "" : detail);
                    mDlDetail.setVisibility(detail == null || detail.isEmpty()
                            ? View.GONE : View.VISIBLE);
                }
            });
        }
        @Override public void onProgressEnded() {
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (ProgressKeeper.getTaskCount() <= 0) showDlPill(false);
            });
        }
    };

    private String safeResString(int resid) {
        try { return resid != 0 && getContext() != null ? getString(resid) : "Downloading"; }
        catch (Throwable t) { return "Downloading"; }
    }

    /** Pull "x.x MB / y.y MB" from the downloader's variadic payload when present. */
    private String extractDlDetail(Object... va) {
        try {
            if (va != null && va.length >= 3
                    && va[1] instanceof Number && va[2] instanceof Number) {
                double cur = ((Number) va[1]).doubleValue();
                double total = ((Number) va[2]).doubleValue();
                if (total > 0) return String.format(Locale.US, "%.1f MB / %.1f MB", cur, total);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Smoothly ease the determinate bar to its new value instead of snapping. */
    private void animateBarTo(int target) {
        if (mDlBar == null) return;
        int from = mDlLastProgress;
        mDlLastProgress = target;
        if (Math.abs(target - from) <= 1) { mDlBar.setProgress(target); return; }
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(from, target);
        va.setDuration(320);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(a -> {
            if (mDlBar != null) mDlBar.setProgress((int) a.getAnimatedValue());
        });
        va.start();
    }

    /** Infinite shimmer that sweeps a soft band across the bar (secondaryProgress). */
    private void startDlShimmer() {
        if (mDlBar == null || mDlShimmer != null) return;
        mDlShimmer = android.animation.ValueAnimator.ofInt(0, 100);
        mDlShimmer.setDuration(1200);
        mDlShimmer.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mDlShimmer.setInterpolator(new android.view.animation.LinearInterpolator());
        mDlShimmer.addUpdateListener(a -> {
            if (mDlBar != null) mDlBar.setSecondaryProgress((int) a.getAnimatedValue());
        });
        mDlShimmer.start();
    }

    /** Gentle breathing pulse on the download glyph disc. */
    private void startDlIconPulse() {
        if (mDlIconWrap == null || mDlIconPulse != null) return;
        mDlIconPulse = android.animation.ValueAnimator.ofFloat(1f, 1.14f);
        mDlIconPulse.setDuration(760);
        mDlIconPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        mDlIconPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        mDlIconPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        mDlIconPulse.addUpdateListener(a -> {
            if (mDlIconWrap != null) {
                float s = (float) a.getAnimatedValue();
                mDlIconWrap.setScaleX(s);
                mDlIconWrap.setScaleY(s);
            }
        });
        mDlIconPulse.start();
    }

    private void stopDlAnimators() {
        if (mDlIconPulse != null) { mDlIconPulse.cancel(); mDlIconPulse = null; }
        if (mDlShimmer != null) { mDlShimmer.cancel(); mDlShimmer = null; }
        if (mDlIconWrap != null) { mDlIconWrap.setScaleX(1f); mDlIconWrap.setScaleY(1f); }
    }

    private void showDlPill(boolean show) {
        if (mDlPill == null) return;
        if (show) {
            if (mDlPill.getVisibility() != View.VISIBLE) {
                // Slide + fade in from the LEFT edge (side-anchored, never centered).
                mDlPill.setAlpha(0f);
                mDlPill.setTranslationX(-40f);
                mDlPill.setTranslationY(0f);
                mDlPill.setScaleX(0.96f);
                mDlPill.setScaleY(0.96f);
                mDlPill.setVisibility(View.VISIBLE);
                mDlPill.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                        .setDuration(340)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                        .start();
                mDlLastProgress = 0;
                startDlShimmer();
                startDlIconPulse();
            }
        } else if (mDlPill.getVisibility() == View.VISIBLE) {
            mDlPill.animate().alpha(0f).translationX(-40f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (mDlPill != null) mDlPill.setVisibility(View.GONE);
                        stopDlAnimators();
                    })
                    .start();
        }
    }

    private void registerDlListeners() {
        if (mDlListening) return;
        mDlListening = true;
        ProgressKeeper.addListener(ProgressLayout.DOWNLOAD_MINECRAFT, mDlListener);
        ProgressKeeper.addListener(ProgressLayout.UNPACK_RUNTIME, mDlListener);
        ProgressKeeper.addListener(ProgressLayout.INSTALL_MODPACK, mDlListener);
        if (ProgressKeeper.getTaskCount() <= 0) showDlPill(false);
    }

    private void unregisterDlListeners() {
        if (!mDlListening) return;
        mDlListening = false;
        ProgressKeeper.removeListener(ProgressLayout.DOWNLOAD_MINECRAFT, mDlListener);
        ProgressKeeper.removeListener(ProgressLayout.UNPACK_RUNTIME, mDlListener);
        ProgressKeeper.removeListener(ProgressLayout.INSTALL_MODPACK, mDlListener);
        stopDlAnimators();
    }

    @Override
    public void onResume() {
        super.onResume();
        cancelLaunchVisuals();
        LauncherProfiles.addUpdateListener(mProfilesWrittenListener);
        loadData();
        bindAccount();
        if (mPlayer != null) mPlayer.onResume();
        startPoseDriver();
        registerDlListeners();
        // Covers returning to Home later + activity recreation: the manager
        // itself dedupes (completed flag + already-showing guard). The Plus
        // popup also re-checks itself — once seen it never returns here.
        final View root = getView();
        if (root != null) {
            root.post(() -> {
                if (!isAdded() || getActivity() == null) return;
                if (PlusWelcomeDialog.maybeShow(getActivity())) return;
                HomeTutorial.maybeStart(getActivity());
            });
        }
    }

    @Override
    public void onPause() {
        LauncherProfiles.removeUpdateListener(mProfilesWrittenListener);
        stopPoseDriver();
        unregisterDlListeners();
        if (mPlayer != null) mPlayer.onPause();
        if (mRenderer != null) mRenderer.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        DragTutorialHost.Registry.unregister(this);
        mDemoPracticeListener = null;
        stopPoseDriver();
        ExtraCore.removeExtraListenerFromValue(ExtraConstants.ACCOUNT_CHANGED, mAccountChangedListener);
        if (mMenu != null) { mMenu.dismiss(); mMenu = null; }
        super.onDestroyView();
    }

    private void loadData() {
        LauncherProfiles.loadAsync(() -> {
            if (!isAdded()) return;
            // Installer-created profiles (Forge/NeoForge/OptiFine) get the
            // user's chosen name + icon applied as soon as we see them.
            try {
                net.kdt.pojavlaunch.profiles.PendingProfileRename.apply(requireContext());
            } catch (Throwable ignored) {}
            rebindProfilesFromMemory();
        });
    }

    /** Rebuild the stage + library from the already-loaded profile model (no disk read). */
    private void rebindProfilesFromMemory() {
        List<String> keys = new ArrayList<>();
        List<MinecraftProfile> profiles = new ArrayList<>();
        if (LauncherProfiles.mainProfileJson != null) {
            for (Map.Entry<String, MinecraftProfile> e : LauncherProfiles.getOrderedEntries()) {
                String key = e.getKey();
                MinecraftProfile p = e.getValue();
                if (key == null || key.isEmpty()) continue;
                if (p == null || p.name == null || p.name.trim().isEmpty()) continue;
                keys.add(key);
                profiles.add(p);
            }
        }
        Tools.runOnUiThread(() -> {
            if (!isAdded()) return;
            mKeys = keys;
            mProfiles = profiles;
            bindStage();
            bindLibrary();
        });
    }

    // ── Stage (primary instance = first in saved order) ─────────────────────

    private void bindStage() {
        mPrimaryMods = -1;
        if (mKeys.isEmpty()) {
            mPrimaryKey = null;
            mLaunchLabel.setText("CREATE");
            crossfadeBackground(null);
            if (mLaunchSpecs != null) mLaunchSpecs.setVisibility(View.GONE);
            return;
        }
        mPrimaryKey = mKeys.get(0);
        MinecraftProfile p = mProfiles.get(0);
        mLaunchLabel.setText("LAUNCH");
        mLaunchButton.setContentDescription("Launch " + (p.name != null ? p.name : "instance"));

        if (mLaunchSpecs != null) {
            mLaunchSpecs.setVisibility(View.VISIBLE);
            if (mSpecJavaText != null) mSpecJavaText.setText(resolveJavaVersion(p));
            if (mSpecRamText != null) mSpecRamText.setText(resolveAllocatedRam());
        }

        // Async: mods count + stage background artwork.
        final String key = mPrimaryKey;
        PojavApplication.sExecutorService.execute(() -> {
            int count = countMods(p);
            Drawable bg = ProfileIconCache.fetchBackground(getResources(), key, p.background);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || !key.equals(mPrimaryKey)) return;
                mPrimaryMods = count;
                crossfadeBackground(bg);
            });
        });
    }

    /** Crossfades the full-screen artwork between the two layers (dimmed to 55%). */
    private void crossfadeBackground(@Nullable Drawable next) {
        if (next == null) {
            // Elegant Minecraft-themed fallback.
            next = androidx.core.content.ContextCompat.getDrawable(requireContext(),
                    R.drawable.bg_hero_minecraft);
        }
        final float target = 0.55f;
        ImageView incoming = mBgFrontIsA ? mBgB : mBgA;
        ImageView outgoing = mBgFrontIsA ? mBgA : mBgB;
        incoming.setImageDrawable(next);
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.setAlpha(0f);
        incoming.setVisibility(View.VISIBLE);
        incoming.animate().alpha(target).setDuration(420).start();
        outgoing.animate().alpha(0f).setDuration(420)
                .withEndAction(() -> outgoing.setVisibility(View.GONE))
                .start();
        mBgFrontIsA = !mBgFrontIsA;
    }

    // ── Continue pill (last played, excluding the primary) ──────────────────

    // ── Library carousel ────────────────────────────────────────────────────

    private void bindLibrary() {
        boolean empty = mKeys.isEmpty();
        mEmptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            mLibrary.setVisibility(View.GONE);
            return;
        }
        mLibrary.setVisibility(View.VISIBLE);
        if (mAdapter != null) mAdapter.updateData(mKeys, mProfiles);
        // One-time staggered card entrance (first successful load only).
        if (!mLibraryIntroduced) {
            mLibraryIntroduced = true;
            mLibrary.post(() -> {
                if (!isAdded()) return;
                for (int i = 0; i < mLibrary.getChildCount(); i++) {
                    View child = mLibrary.getChildAt(i);
                    child.setAlpha(0f);
                    child.setTranslationX(46f);
                    child.setScaleX(0.92f);
                    child.setScaleY(0.92f);
                    child.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                            .setStartDelay(180 + i * 45L)
                            .setDuration(380)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(0.6f))
                            .start();
                }
            });
        }
    }

    private void setupLibrary() {
        LinearLayoutManager lm = new LinearLayoutManager(getContext(),
                LinearLayoutManager.HORIZONTAL, false);
        mLibrary.setLayoutManager(lm);
        new LinearSnapHelper().attachToRecyclerView(mLibrary);

        mAdapter = new InstanceLibraryAdapter(mKeys, mProfiles, new InstanceLibraryAdapter.Listener() {
            @Override public void onPromote(String key, MinecraftProfile profile) {
                promoteToPrimary(key, profile);
            }
            @Override public void onPlay(String key, MinecraftProfile profile) {
                mLaunchButton.beginLaunch();
                launch(key);
            }
            @Override public void onMore(@NonNull View anchor, String key, MinecraftProfile profile) {
                showInstanceMenu(anchor, key, profile);
            }
            @Override public void onCreateInstance() {
                CreationTypeDialog.show(LauncherHomeFragment.this);
            }
            @Override public void onOrderChanged(List<String> orderedKeys) {
                LauncherProfiles.applyProfileOrder(orderedKeys);
                loadData();   // primary (first) may have changed → re-theme stage
            }
        });
        mAdapter.setDragStartListener(holder -> {
            if (mTouchHelper != null) mTouchHelper.startDrag(holder);
        });
        mLibrary.setAdapter(mAdapter);

        mTouchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            /**
             * Press and hold a card, then drag it to the front: the whole card is
             * grabbable, not just the small dotted handle. The handle keeps working
             * (its own listener still calls startDrag), and the "+ create" tile is
             * excluded, so its tap is untouched.
             */
            @Override public boolean isLongPressDragEnabled() { return true; }
            @Override public boolean isItemViewSwipeEnabled() { return false; }
            /** Tutorial practice signal: a REAL long-press matured into drag state. */
            @Override public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    mPracticeMovedThisGesture = false;
                    if (mDemoPracticeListener != null) mDemoPracticeListener.onPracticeDragStart();
                }
            }
            @Override public int getMovementFlags(@NonNull RecyclerView rv,
                                                  @NonNull RecyclerView.ViewHolder vh) {
                if (vh instanceof InstanceLibraryAdapter.CreateHolder) return 0;
                return makeMovementFlags(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
            }
            @Override public boolean onMove(@NonNull RecyclerView rv,
                                            @NonNull RecyclerView.ViewHolder from,
                                            @NonNull RecyclerView.ViewHolder to) {
                if (mAdapter == null || to instanceof InstanceLibraryAdapter.CreateHolder) return false;
                int f = from.getBindingAdapterPosition(), t = to.getBindingAdapterPosition();
                boolean moved = mAdapter.moveItem(f, t);
                if (moved) {
                    from.itemView.setElevation(10f * getResources().getDisplayMetrics().density);
                    from.itemView.setScaleX(1.05f);
                    from.itemView.setScaleY(1.05f);
                    // Tutorial practice signal: while holding, the card truly
                    // crossed another slot (a real reorder, not a wiggle).
                    if (f != t) {
                        mPracticeMovedThisGesture = true;
                        if (mDemoPracticeListener != null) mDemoPracticeListener.onPracticeDragMoved();
                    }
                }
                return moved;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
            @Override public void clearView(@NonNull RecyclerView rv,
                                            @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                vh.itemView.setElevation(0f);
                if (mAdapter != null) mAdapter.dispatchOrderChanged();
                // Tutorial practice completes ONLY on release after a real reorder.
                if (mPracticeMovedThisGesture) {
                    mPracticeMovedThisGesture = false;
                    if (mDemoPracticeListener != null) mDemoPracticeListener.onPracticeComplete();
                }
            }
        });
        mTouchHelper.attachToRecyclerView(mLibrary);
    }

    // ── DragTutorialHost implementation ─────────────────────────────────────

    private static MinecraftProfile buildDemoProfile(String displayName) {
        MinecraftProfile p = new MinecraftProfile();
        p.name = displayName;
        p.type = "custom";
        p.lastVersionId = MinecraftProfile.DEFAULT_VERSION;
        p.icon = "default";
        // No banner fetch for demo cards (transient + offline friendly).
        p.background = null;
        p.favorite = false;
        return p;
    }

    @Override
    public boolean beginDragDemo() {
        if (!isAdded() || getView() == null || mAdapter == null || mLibrary == null) return false;
        int realCount = mAdapter.getRealCount();
        if (realCount <= 0) {
            // No real instances yet: seed TWO demo cards so "the front" exists
            // and a physical reorder is possible during practice.
            mAdapter.insertDemoItem(DEMO_KEY_ALEX, buildDemoProfile("Alex"), 0);
            mAdapter.insertDemoItem(DEMO_KEY_STEVE, buildDemoProfile("Steve"), 1);
        } else {
            // Real primary owns slot 0; Steve sits beside it, ready to be
            // dragged to the front.
            MinecraftProfile steve = buildDemoProfile("Steve");
            if (realCount == 1 && mProfiles != null && !mProfiles.isEmpty()
                    && mProfiles.get(0).favorite) {
                // Keep the demo inside the same favorite group so holding and
                // dragging Steve past the only real card stays a legal move.
                steve.favorite = true;
            }
            mAdapter.insertDemoItem(DEMO_KEY_STEVE, steve, 1);
        }
        if (mEmptyHint != null) mEmptyHint.setVisibility(View.GONE);
        mLibrary.setVisibility(View.VISIBLE);
        mLibrary.stopScroll();
        mLibrary.scrollToPosition(0);
        return true;
    }

    @Override
    public boolean hasRealProfiles() {
        return getRealProfileCount() > 0;
    }

    @Override
    public int getRealProfileCount() {
        if (mAdapter != null) return mAdapter.getRealCount();
        return mKeys != null ? mKeys.size() : 0;
    }

    @Nullable
    private RecyclerView.ViewHolder holderForKey(String key) {
        if (mAdapter == null || mLibrary == null) return null;
        int pos = mAdapter.indexOfKey(key);
        if (pos < 0) return null;
        return mLibrary.findViewHolderForAdapterPosition(pos);
    }

    @Nullable
    private static Rect viewRectOnScreen(@Nullable View v) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return new Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    @Override
    @Nullable
    public View getDemoCardView() {
        RecyclerView.ViewHolder vh = holderForKey(DEMO_KEY_STEVE);
        return vh != null ? vh.itemView : null;
    }

    @Override
    @Nullable
    public Rect getDemoCardScreenRect() {
        return viewRectOnScreen(getDemoCardView());
    }

    @Override
    @Nullable
    public Rect getFrontSlotScreenRect() {
        if (mAdapter == null || mLibrary == null) return null;
        RecyclerView.ViewHolder vh0 = mLibrary.findViewHolderForAdapterPosition(0);
        Rect r = vh0 != null ? viewRectOnScreen(vh0.itemView) : null;
        if (r != null) return r;
        return viewRectOnScreen(mLibrary);
    }

    @Override
    public void resetDemoCardTransform() {
        View card = getDemoCardView();
        if (card == null) return;
        card.animate().cancel();
        card.setTranslationX(0f);
        card.setTranslationY(0f);
        card.setScaleX(1f);
        card.setScaleY(1f);
        card.setElevation(0f);
    }

    @Override
    public void removeDemoCardsAfterDemo(boolean keepSteveForPractice) {
        if (mAdapter == null) return;
        resetDemoCardTransform();
        mAdapter.removeDemoItem(DEMO_KEY_ALEX);
        if (!keepSteveForPractice) {
            mAdapter.removeDemoItem(DEMO_KEY_STEVE);
        }
    }

    @Override
    public void setDemoPracticeListener(@Nullable DragTutorialHost.DemoPracticeListener listener) {
        mDemoPracticeListener = listener;
    }

    @Override
    public void endDragTutorial() {
        mDemoPracticeListener = null;
        mPracticeMovedThisGesture = false;
        if (mAdapter != null) {
            resetDemoCardTransform();
            mAdapter.removeDemoItems();
        }
        // Restore the truthful library state (empty hint / binding) from storage.
        View v = getView();
        if (isAdded() && v != null) {
            v.post(() -> { if (isAdded()) loadData(); });
        }
    }

    /** Reorder so `key` leads its favorite group → becomes the primary instance. */
    private void promoteToPrimary(String key, MinecraftProfile profile) {
        // Tutorial demo cards can never be promoted or persisted.
        if (mAdapter != null && mAdapter.isDemoKey(key)) return;
        if (key.equals(mPrimaryKey)) return;
        List<String> order = new ArrayList<>(mKeys);
        order.remove(key);
        if (profile.favorite) {
            order.add(0, key);
        } else {
            int favCount = 0;
            for (MinecraftProfile p : mProfiles) if (p.favorite) favCount++;
            order.add(Math.min(favCount, order.size()), key);
        }
        LauncherProfiles.applyProfileOrder(order);
        loadData();
    }

    // ── Contextual instance menu (anchored, clamped to screen) ──────────────

    private android.widget.PopupWindow mMenu = null;

    private void showInstanceMenu(@NonNull View anchor, String key, MinecraftProfile profile) {
        Context ctx = getContext();
        if (ctx == null) return;
        float dp = getResources().getDisplayMetrics().density;

        // Opening another card's menu → close the previous one cleanly first.
        if (mMenu != null) {
            android.widget.PopupWindow old = mMenu;
            mMenu = null;
            old.dismiss();
        }

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_lh_menu);
        panel.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));

        boolean isPrimary = key.equals(mPrimaryKey);
        if (!isPrimary) {
            panel.addView(menuRow(ctx, dp, R.drawable.ic_layers, "Set as Primary",
                    () -> promoteToPrimary(key, profile)));
        }
        panel.addView(menuRow(ctx, dp, R.drawable.ic_play_arrow, "Play",
                () -> { mLaunchButton.beginLaunch(); launch(key); }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_edit_profile, "Edit Instance",
                () -> {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
                    navigateTo(ProfileEditorFragment.class, ProfileEditorFragment.TAG, null);
                }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_search, "Download Resources",
                () -> {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
                    Bundle args = new Bundle(1);
                    args.putString(ManageModsFragment.BUNDLE_PROFILE_KEY, key);
                    navigateTo(ModsSearchFragment.class, ModsSearchFragment.TAG, args);
                }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_shortcut_add, "Create Shortcut",
                () -> {
                    Bundle args = new Bundle(1);
                    args.putString(net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.ARG_PROFILE_KEY, key);
                    navigateTo(net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.class,
                            net.kdt.pojavlaunch.shortcuts.ShortcutIconPickerFragment.TAG, args);
                }));
        panel.addView(menuRow(ctx, dp,
                profile.favorite ? R.drawable.ic_csp_star_filled : R.drawable.ic_csp_star_outline,
                profile.favorite ? "Remove from Favorites" : "Add to Favorites",
                () -> {
                    LauncherProfiles.setFavorite(key, !profile.favorite);
                    loadData();
                }));
        panel.addView(menuRow(ctx, dp, R.drawable.ic_menu_delete_forever, "Delete",
                () -> confirmDeleteInstance(key, profile)));

        // Measure with a real width spec so wrap_content + clamping both work.
        panel.measure(
                View.MeasureSpec.makeMeasureSpec((int) (232 * dp), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = Math.max(panel.getMeasuredWidth(), (int) (196 * dp));
        int h = panel.getMeasuredHeight();

        final android.widget.PopupWindow popup = new android.widget.PopupWindow(panel, w,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(14 * dp);
        popup.setClippingEnabled(true);
        popup.setOnDismissListener(() -> {
            if (mMenu == popup) mMenu = null;
            // The ⋮ relaxes back to rest when the menu closes.
            anchor.animate().cancel();
            anchor.animate().rotation(0f).scaleX(1f).scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f))
                    .start();
        });
        mMenu = popup;

        // ── Positioning: anchored to THIS card's ⋮ button, always fully on-screen.
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        android.graphics.Rect screen = new android.graphics.Rect();
        requireActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(screen);
        int margin = (int) (6 * dp);

        // Prefer opening toward the screen center, then clamp to the safe area.
        int x = loc[0] + anchor.getWidth() - w + (int) (8 * dp);   // left of the ⋮
        if (x + w / 2f > screen.centerX()) {                        // near right edge → flip leftward
            x = loc[0] - w - (int) (8 * dp);
        }
        x = Math.max(screen.left + margin, Math.min(x, screen.right - margin - w));

        boolean below = loc[1] + anchor.getHeight() + h + (int) (10 * dp) <= screen.bottom;
        int y = below ? loc[1] + anchor.getHeight() + (int) (4 * dp)
                      : loc[1] - h - (int) (4 * dp);                // near bottom → open upward

        // The ⋮ springs open, so it reads as a real control and not a glyph.
        anchor.animate().cancel();
        anchor.setPivotX(anchor.getWidth() / 2f);
        anchor.setPivotY(anchor.getHeight() / 2f);
        anchor.animate().rotation(90f).scaleX(1.18f).scaleY(1.18f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();

        popup.showAtLocation(anchor, Gravity.NO_GRAVITY,
                x, Math.max(screen.top + margin, y));

        // ── Opening animation: fade + scale + drift from the anchor corner.
        boolean fromRight = (loc[0] + anchor.getWidth() / 2f) > screen.centerX();
        panel.setPivotX(fromRight ? w * 0.9f : w * 0.1f);
        panel.setPivotY(below ? 0f : h);
        panel.setAlpha(0f);
        panel.setScaleX(0.88f);
        panel.setScaleY(0.88f);
        panel.setTranslationY(below ? 10 * dp : -10 * dp);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(190)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** Eased close, then run the picked action (menus never just vanish). */
    private void closeMenuAnimated(@Nullable Runnable after) {
        if (mMenu == null) {
            if (after != null) after.run();
            return;
        }
        final android.widget.PopupWindow popup = mMenu;
        mMenu = null;
        View content = popup.getContentView();
        content.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
                .setDuration(130)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    popup.dismiss();
                    if (after != null) after.run();
                })
                .start();
    }

    // ── Delete instance (confirm → real delete → instant refresh) ───────────

    /** Premium confirmation: dark card, fade + scale + rise, dim behind. */
    private void confirmDeleteInstance(String key, MinecraftProfile profile) {
        android.app.Dialog dialog = new android.app.Dialog(requireContext());
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int)(22*dp), (int)(20*dp), (int)(22*dp), (int)(16*dp));
        card.setBackgroundResource(R.drawable.bg_vp_card);

        TextView title = new TextView(requireContext());
        title.setText("Delete Instance?");
        title.setTextColor(0xFFF2F3F5);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView message = new TextView(requireContext());
        String name = profile != null && profile.name != null ? profile.name : key;
        message.setText("Are you sure you want to delete \"" + name + "\"?\nThis action cannot be undone.");
        message.setTextColor(0xFF9CA3AF);
        message.setTextSize(13);
        message.setLineSpacing((int)(2*dp), 1f);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        TextView cancel = pillButton("CANCEL", 0xFFC4B5FD, dp);
        TextView delete = pillButton("DELETE", 0xFFFF6B81, dp);
        buttons.addView(cancel);
        buttons.addView(delete);

        card.addView(title);
        card.addView(message);
        android.widget.LinearLayout.LayoutParams mp =
                new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = (int)(14*dp);
        card.addView(buttons, mp);

        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setDimAmount(0.55f);
        }
        dialog.show();

        // Entrance: fade + scale 92→100% + rise.
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.setTranslationY(16f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f))
                .start();

        cancel.setOnClickListener(v -> {
            UiMotion.pressFeedback(v);
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(130)
                    .withEndAction(dialog::dismiss)
                    .start();
        });
        delete.setOnClickListener(v -> {
            UiMotion.pressFeedback(v);
            dialog.dismiss();
            deleteInstance(key);
        });
    }

    private TextView pillButton(String label, int color, float dp) {
        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(12.5f);
        tv.setLetterSpacing(0.1f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        tv.setPadding((int)(16*dp), (int)(10*dp), (int)(16*dp), (int)(10*dp));
        tv.setBackgroundResource(R.drawable.bg_cs_ghost_button);
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int)(8*dp);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** Deletes the profile, reselects primary, and refreshes Home immediately. */
    private void deleteInstance(String key) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                LauncherProfiles.load();
                LauncherProfiles.mainProfileJson.profiles.remove(key);
                LauncherProfiles.write();
                try {
                    net.kdt.pojavlaunch.shortcuts.ProfileShortcutHelper
                            .removeShortcutsForProfile(requireContext(), key);
                } catch (Exception ignored) {}
                // If the deleted instance was current, fall back to the first one.
                String current = LauncherPreferences.DEFAULT_PREF.getString(
                        LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                String next = null;
                for (Map.Entry<String, MinecraftProfile> e
                        : LauncherProfiles.getOrderedEntries()) {
                    if (e.getKey() != null && !e.getKey().isEmpty()
                            && e.getValue() != null && e.getValue().name != null) {
                        next = e.getKey();
                        break;
                    }
                }
                if (key.equals(current)) {
                    LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, next == null ? "" : next)
                            .apply();
                }
                ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER,
                        next != null ? next : ProfileEditorFragment.DELETED_PROFILE);
            } catch (Throwable ignored) {}

            Tools.runOnUiThread(() -> {
                if (!isAdded()) return;
                net.kdt.pojavlaunch.utils.CsPopup.show(requireContext(), "Instance deleted");
                net.kdt.pojavlaunch.notifications.CsNotifier.info("Instance deleted", "List updated");
                loadData();
            });
        });
    }

    private View menuRow(Context ctx, float dp, int iconRes, String label, Runnable action) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setBackgroundResource(R.drawable.bg_lh_sheet_row);
        int padH = (int) (12 * dp), padV = (int) (11 * dp);
        l.setPadding(padH + (int)(2*dp), padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (4 * dp);
        l.setLayoutParams(lp);

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        icon.setColorFilter(0xFFC9B8FF);
        int size = (int) (18 * dp);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
        ilp.rightMargin = (int) (12 * dp);
        icon.setLayoutParams(ilp);
        l.addView(icon);

        TextView text = new TextView(ctx);
        text.setText(label);
        text.setTextColor(0xFFEDEAF6);
        text.setTextSize(13.5f);
        l.addView(text);

        l.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            // Eased close → then run the real action once the menu is gone.
            closeMenuAnimated(action);
        });
        return l;
    }

    // ── Account ─────────────────────────────────────────────────────────────

    private void bindAccount() {
        Activity act = getActivity();
        if (act == null) return;
        View v = act.findViewById(R.id.account_spinner);
        if (!(v instanceof mcAccountSpinner)) return;
        MinecraftAccount account = ((mcAccountSpinner) v).getSelectedAccount();
        String label;
        String type;
        if (account == null || account.username == null || account.username.trim().isEmpty()) {
            label = "Add account";
            type = "NEW";
        } else {
            label = account.username;
            type = account.isMicrosoft ? "MICROSOFT"
                    : account.isElyByAccount() ? "ELY.BY" : "LOCAL";
        }
        boolean changed = !label.equals(mLastAccountLabel);
        mLastAccountLabel = label;
        mAccountName.setText(label);
        mAccountType.setText(type);
        if (mHeroName != null) {
            // The floating name tag over the character: real username, or a
            // clean "Player" default when logged out (never "Add account",
            // never null/blank).
            boolean loggedOut = account == null || account.username == null
                    || account.username.trim().isEmpty();
            String tag = loggedOut ? "Player" : label;
            mHeroName.setText(tag);
            // Feed the SAME name into the in-scene GL name tag (floats above the
            // head, cannot be clipped or hidden). This is now the ONE visible
            // name tag; the TextView stays gone as a fallback anchor.
            if (mRenderer != null) mRenderer.setNametag(tag);
        }
        // Name tag = lh_hero_name TextView (set above). The GL nametag remains
        // disabled so it can never clip the raised hand or hide behind the bg.
        loadAccountHead(account);
        if (changed && mAccountChip != null) {
            // Account switch: quick dip-and-back so the swap reads intentional.
            mAccountChip.animate().cancel();
            mAccountChip.setAlpha(0.25f);
            mAccountChip.animate().alpha(1f).setDuration(220).start();
        }
    }

    /** 3D Minecraft head avatar (existing SkinHead3DRenderer, cached + invalidated on skin change). */
    private void loadAccountHead(MinecraftAccount account) {
        if (mAccountHead == null) return;
        PojavApplication.sExecutorService.execute(() -> {
            Bitmap head = net.kdt.pojavlaunch.ui.SkinHead3DRenderer.resolve(getResources(), account);
            Tools.runOnUiThread(() -> {
                if (!isAdded() || mAccountHead == null) return;
                mAccountHead.setImageTintList(null);
                if (head != null) mAccountHead.setImageBitmap(head);
                else mAccountHead.setImageResource(R.drawable.ic_profile_player);
            });
        });
    }

    /** Opens the existing account dialog (add / switch / delete). */
    private void switchAccount() {
        Activity act = getActivity();
        if (act == null) return;
        View v = act.findViewById(R.id.account_spinner);
        if (v instanceof mcAccountSpinner) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            ((mcAccountSpinner) v).performClick();
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private void playLaunchAnimationThenLaunch(String key) {
        // Hero Skin / Character Launch Transition
        if (mPlayer != null && mPlayer.getVisibility() == View.VISIBLE) {
            mPlayer.animate().cancel();
            mPlayer.animate()
                    .scaleX(1.35f)
                    .scaleY(1.35f)
                    .translationY(-25f * getResources().getDisplayMetrics().density)
                    .setDuration(450)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                    .start();
        }
        if (mHeroName != null) {
            mHeroName.animate().cancel();
            mHeroName.animate()
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .alpha(1f)
                    .setDuration(400)
                    .start();
        }
        if (mAccountChip != null) {
            mAccountChip.animate().alpha(0.35f).setDuration(350).start();
        }
        if (mLibrary != null) {
            mLibrary.animate().alpha(0.25f).translationY(14f * getResources().getDisplayMetrics().density).setDuration(400).start();
        }

        getView().postDelayed(() -> {
            if (!isAdded()) return;
            launch(key);
        }, 420);
    }

    private void cancelLaunchVisuals() {
        if (mPlayer != null && mPlayer.getVisibility() == View.VISIBLE) {
            mPlayer.animate().cancel();
            mPlayer.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(300).start();
        }
        if (mHeroName != null) {
            mHeroName.animate().cancel();
            mHeroName.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start();
        }
        if (mAccountChip != null) {
            mAccountChip.animate().alpha(1f).setDuration(250).start();
        }
        if (mLibrary != null) {
            mLibrary.animate().alpha(1f).translationY(0f).setDuration(300).start();
        }
    }

    private void launch(String key) {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, key).apply();
        ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private void navigateTo(Class<? extends Fragment> cls, String tag, @Nullable Bundle args) {
        Tools.swapFragment(requireActivity(), cls, tag, args);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Real allocated RAM from the launcher configuration, e.g. "4 GB RAM". */
    private static String formatRam(int mb) {
        if (mb <= 0) return "RAM —";
        if (mb >= 1024) {
            float gb = mb / 1024f;
            return (gb == Math.floor(gb) ? String.valueOf((int) gb)
                    : String.format(java.util.Locale.US, "%.1f", gb)) + " GB RAM";
        }
        return mb + " MB RAM";
    }

    private static String displayVersion(String raw) {
        if (raw == null || raw.isEmpty()) return "—";
        return raw;
    }

    private static String loaderName(String versionId) {
        if (versionId == null) return null;
        String v = versionId.toLowerCase(Locale.ROOT);
        if (v.contains("neoforge")) return "NeoForge";
        if (v.contains("forge")) return "Forge";
        if (v.contains("fabric")) return "Fabric";
        if (v.contains("quilt")) return "Quilt";
        if (v.contains("optifine")) return "OptiFine";
        return null;
    }

    private String resolveJavaVersion(MinecraftProfile p) {
        if (p == null) return "Java 21";
        if (p.javaDir != null && !p.javaDir.isEmpty()) {
            String runtimeName = Tools.getRuntimeName(p.javaDir);
            if (runtimeName != null && !runtimeName.isEmpty()) {
                try {
                    Runtime rt = MultiRTUtils.read(runtimeName);
                    if (rt != null && rt.javaVersion > 0) {
                        return "Java " + rt.javaVersion;
                    }
                } catch (Throwable ignored) {}
                if (runtimeName.contains("21")) return "Java 21";
                if (runtimeName.contains("17")) return "Java 17";
                if (runtimeName.contains("8")) return "Java 8";
                return runtimeName;
            }
        }
        try {
            String defRt = LauncherPreferences.PREF_DEFAULT_RUNTIME;
            if (defRt != null && !defRt.isEmpty()) {
                Runtime rt = MultiRTUtils.read(defRt);
                if (rt != null && rt.javaVersion > 0) {
                    return "Java " + rt.javaVersion;
                }
            }
        } catch (Throwable ignored) {}
        String ver = p.lastVersionId;
        if (ver != null) {
            String v = ver.toLowerCase(Locale.ROOT);
            if (v.contains("1.20.5") || v.contains("1.20.6") || v.contains("1.21")) return "Java 21";
            if (v.contains("1.17") || v.contains("1.18") || v.contains("1.19") || v.contains("1.20")) return "Java 17";
            if (v.contains("1.7") || v.contains("1.8") || v.contains("1.12") || v.contains("1.16")) return "Java 8";
        }
        return "Java 21";
    }

    private String resolveAllocatedRam() {
        int ram = LauncherPreferences.PREF_RAM_ALLOCATION;
        if (ram <= 0 && getContext() != null) {
            ram = LauncherPreferences.DEFAULT_PREF.getInt("allocation", 1024);
        }
        return (ram > 0 ? ram : 1024) + " MB RAM";
    }

    private static String javaName(MinecraftProfile p) {
        if (p != null && p.javaDir != null) {
            if (p.javaDir.contains("21")) return "Java 21";
            if (p.javaDir.contains("17")) return "Java 17";
        }
        return null;
    }

    private static int countMods(MinecraftProfile p) {
        try {
            File gameDir = p.resolveGameDir();
            if (gameDir != null) {
                File modsDir = new File(gameDir, "mods");
                if (modsDir.exists() && modsDir.isDirectory()) {
                    File[] files = modsDir.listFiles(f -> f.isFile() &&
                            (f.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                                    || f.getName().toLowerCase(Locale.ROOT).endsWith(".jar.disabled")));
                    return files != null ? files.length : 0;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static long parseLastUsed(String iso) {
        if (iso == null || iso.isEmpty()) return -1;
        try { return Instant.parse(iso).toEpochMilli(); }
        catch (Throwable t) { return -1; }
    }
}
