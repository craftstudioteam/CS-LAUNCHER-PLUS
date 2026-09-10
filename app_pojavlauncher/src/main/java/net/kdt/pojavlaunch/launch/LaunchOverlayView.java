package net.kdt.pojavlaunch.launch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

/**
 * The LAUNCH sequence overlay — visually and semantically the opposite of the
 * Download Console.
 *
 * Download = bottom card, % counters, speed/ETA, pause/stop.
 * Launch   = centered vignette that dims the launcher, the profile card
 *            springs in, a horizontal ENERGY beam sweeps left→right with a
 *            glowing tip, particles rise, phase text (Preparing → Verifying →
 *            Checking runtime → Starting Minecraft) crossfades, and on
 *            STARTING the card pops with a bright pulse before the game takes
 *            the screen.
 *
 * Perf: everything drawn on one Canvas from preallocated Paints/Paths/Matrices
 * and pooled particle arrays — zero per-frame allocations; a single infinite
 * ValueAnimator drives beam + particles + glow pulse; all animators cancel on
 * detach; icon decode happens off-thread and caches through ProfileIconCache.
 */
public final class LaunchOverlayView extends View {

    private static final int SCRIM_COLOR = 0xEC0B0B0E;
    private static final int PARTICLE_COUNT = 16;
    private static final long PHASE_DWELL_MS = 420L;
    private static final long BEAM_PERIOD_MS = 1150L;

    private final Paint mScrimPaint = new Paint();
    private final Paint mCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCardBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBeamBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBeamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBeamGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mParticlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mStatusPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mChipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mIconClipPath = new Path();
    private final RectF mTmpRect = new RectF();
    private final Matrix mBeamMatrix = new Matrix();
    private final Matrix mIconMatrix = new Matrix();

    private LinearGradient mBeamGradient;
    private RadialGradient mGlowGradient;

    private final float[] mPX = new float[PARTICLE_COUNT];
    private final float[] mPY = new float[PARTICLE_COUNT];
    private final float[] mPVY = new float[PARTICLE_COUNT];
    private final int[] mPLife = new int[PARTICLE_COUNT];

    private ValueAnimator mBeamAnimator;
    private ValueAnimator mTextCrossAnimator;
    private ValueAnimator mCardPopAnimator;
    private float mBeamFraction;
    private float mTextCross = 1f; // 1 = new text fully visible
    private float mCardScale = 1f;
    private String mNameText = "";
    private String mVersionText = "";
    private String mStatusText = "";
    private String mPrevStatusText = "";
    private LaunchTracker.Phase mDisplayPhase;
    private long mPhaseShownAtMs;
    private Bitmap mIcon;
    private boolean mShowing;

    @Nullable private LaunchTracker.Phase mPendingPhase;
    /** Dwell queue: the ONLY delayed runnable this view ever posts. */
    private final Runnable mApplyPendingPhase = () -> {
        LaunchTracker.Phase p = mPendingPhase;
        mPendingPhase = null;
        if (p != null) setPhaseInternal(p, false);
    };

    public LaunchOverlayView(@NonNull Context context) {
        super(context);
        setVisibility(GONE);
        // Touches PASS THROUGH (item-1/6 root fix): launcher navigation —
        // Menu / Settings / Logs / every rail button — must stay usable in
        // every launch state. Previously clickable=true swallowed ALL taps
        // under the scrim for the whole PREPARING→STARTING window, so buttons
        // "sometimes worked, sometimes not" (they came back only when the
        // Download Console took over the chrome). Relaunch safety is enforced
        // semantically in LauncherActivity via LaunchTracker.getPhase().
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mScrimPaint.setColor(SCRIM_COLOR);
        mScrimPaint.setStyle(Paint.Style.FILL);
        mCardPaint.setStyle(Paint.Style.FILL);
        mCardPaint.setColor(0xFF1C1C1C);
        mCardBorderPaint.setStyle(Paint.Style.STROKE);
        mCardBorderPaint.setStrokeWidth(dp(1.2f));
        mCardBorderPaint.setColor(0x5534343A);
        mGlowPaint.setStyle(Paint.Style.FILL);
        mBeamBasePaint.setStyle(Paint.Style.FILL);
        mBeamBasePaint.setColor(0xFF242426);
        mBeamGlowPaint.setStyle(Paint.Style.FILL);
        mParticlePaint.setStyle(Paint.Style.FILL);
        mNamePaint.setColor(0xFFFFFFFF);
        mNamePaint.setTextSize(dp(17));
        mNamePaint.setFakeBoldText(true);
        mStatusPaint.setColor(0xFFB9BBC4);
        mStatusPaint.setTextSize(dp(11.5f));
        mChipPaint.setColor(0xFF0D0D0D);
        mChipPaint.setTextSize(dp(10));
        mChipPaint.setFakeBoldText(true);
        mChipBgPaint.setStyle(Paint.Style.FILL);
        mChipBgPaint.setColor(0xFFFFFFFF);
    }

    // ───────────────────────── public API ─────────────────────────

    /** Bind identity before showing. Name is ellipsized once here. */
    public void bind(@Nullable String profileName, @Nullable String versionId) {
        String name = profileName != null && !profileName.isEmpty() ? profileName : "Instance";
        mNameText = String.valueOf(TextUtils.ellipsize(name, mNamePaint, dp(240),
                TextUtils.TruncateAt.END));
        mVersionText = versionId != null && !versionId.isEmpty() && !"Unknown".equals(versionId)
                ? versionId : null;
        loadIconAsync();
    }

    public boolean isShowing() { return mShowing; }

    /** Show with entrance: scrim fades in, card springs up, beam starts. */
    public void startLaunch() {
        if (getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
            mCardScale = 0.92f;
            ValueAnimator cardEnter = ValueAnimator.ofFloat(0.92f, 1f);
            cardEnter.setDuration(300);
            // Item-7: spring retained but restrained (0.85 tension — premium
            // settle instead of a bouncy overshoot).
            cardEnter.setInterpolator(new android.view.animation.OvershootInterpolator(0.85f));
            cardEnter.addUpdateListener(a -> {
                mCardScale = (Float) a.getAnimatedValue();
                invalidate();
            });
            cardEnter.start();
        }
        mShowing = true;
        startBeamAnimator();
        setPhaseInternal(LaunchTracker.Phase.PREPARING, true);
    }

    /** Phase updates from LaunchTracker; dwell-gated so text never flickers. */
    public void setPhase(@NonNull LaunchTracker.Phase phase) {
        long sinceShown = android.os.SystemClock.elapsedRealtime() - mPhaseShownAtMs;
        if (sinceShown < PHASE_DWELL_MS && mDisplayPhase != null) {
            mPendingPhase = phase;
            removeCallbacks(mApplyPendingPhase);
            postDelayed(mApplyPendingPhase, PHASE_DWELL_MS - sinceShown);
        } else {
            mPendingPhase = null;
            removeCallbacks(mApplyPendingPhase);
            setPhaseInternal(phase, false);
        }
    }

    /** Fade out and reset to GONE. */
    public void finishAndHide(@Nullable Runnable after) {
        if (mDisplayPhase == null && getVisibility() == GONE) {
            if (after != null) after.run();
            return;
        }
        mShowing = false;
        animate().cancel();
        animate().alpha(0f).setDuration(220).withEndAction(() -> {
            stopBeamAnimator();
            setVisibility(GONE);
            setAlpha(1f);
            clearParticles();
            mDisplayPhase = null;
            mStatusText = "";
            mPrevStatusText = "";
            if (after != null) after.run();
        }).start();
    }

    // ───────────────────────── internals ─────────────────────────

    private void setPhaseInternal(@NonNull LaunchTracker.Phase phase, boolean force) {
        if (!force && phase == mDisplayPhase) return;
        mDisplayPhase = phase;
        mPhaseShownAtMs = android.os.SystemClock.elapsedRealtime();
        mPrevStatusText = mStatusText;
        mStatusText = statusFor(phase);
        startTextCrossfade();
        if (phase == LaunchTracker.Phase.STARTING) startCardPop();
        invalidate();
    }

    @NonNull
    private String statusFor(@NonNull LaunchTracker.Phase phase) {
        switch (phase) {
            case PREPARING: return getContext().getString(R.string.cs_launch_preparing);
            case VERIFYING: return getContext().getString(R.string.cs_launch_verifying);
            case RUNTIME: return getContext().getString(R.string.cs_launch_runtime);
            case STARTING: return getContext().getString(R.string.cs_launch_starting);
            default: return "";
        }
    }

    private void startBeamAnimator() {
        if (mBeamAnimator != null) return;
        mBeamAnimator = ValueAnimator.ofFloat(0f, 1f);
        mBeamAnimator.setDuration(BEAM_PERIOD_MS);
        mBeamAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mBeamAnimator.setInterpolator(new LinearInterpolator());
        mBeamAnimator.addUpdateListener(a -> {
            mBeamFraction = (Float) a.getAnimatedValue();
            stepParticles();
            invalidate();
        });
        if (isAttachedToWindow()) mBeamAnimator.start();
    }

    private void stopBeamAnimator() {
        if (mBeamAnimator != null) {
            mBeamAnimator.cancel();
            mBeamAnimator = null;
        }
        if (mTextCrossAnimator != null) { mTextCrossAnimator.cancel(); mTextCrossAnimator = null; }
        if (mCardPopAnimator != null) { mCardPopAnimator.cancel(); mCardPopAnimator = null; }
    }

    private void startTextCrossfade() {
        if (mTextCrossAnimator != null) mTextCrossAnimator.cancel();
        mTextCrossAnimator = ValueAnimator.ofFloat(0f, 1f);
        mTextCrossAnimator.setDuration(180);
        mTextCrossAnimator.addUpdateListener(a -> {
            mTextCross = (Float) a.getAnimatedValue();
            invalidate();
        });
        mTextCrossAnimator.start();
    }

    /** STARTING moment: card pops with an overshoot — the "go" beat. */
    private void startCardPop() {
        if (mCardPopAnimator != null) mCardPopAnimator.cancel();
        mCardPopAnimator = ValueAnimator.ofFloat(1f, 1.07f);
        mCardPopAnimator.setDuration(340);
        mCardPopAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mCardPopAnimator.setRepeatCount(1);
        mCardPopAnimator.setInterpolator(new DecelerateInterpolator());
        mCardPopAnimator.addUpdateListener(a -> {
            mCardScale = (Float) a.getAnimatedValue();
            invalidate();
        });
        mCardPopAnimator.start();
    }

    private void loadIconAsync() {
        PojavApplication.sExecutorService.execute(() -> {
            Bitmap bmp = null;
            try {
                String key = LauncherPreferences.DEFAULT_PREF.getString(
                        LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                LauncherProfiles.load();
                MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(key);
                if (prof != null) {
                    Drawable d = ProfileIconCache.fetchIcon(getResources(), key, prof.icon);
                    if (d instanceof BitmapDrawable) bmp = ((BitmapDrawable) d).getBitmap();
                }
            } catch (Throwable ignored) {}
            final Bitmap result = bmp;
            post(() -> {
                mIcon = result;
                invalidate();
            });
        });
    }

    // ───────────────────────── particles ─────────────────────────

    private void seedParticle(int i, float cx, float cy) {
        mPX[i] = cx + (float) (Math.random() - 0.5) * dp(200);
        mPY[i] = cy;
        mPVY[i] = -(dp(0.55f) + (float) Math.random() * dp(1.5f));
        mPLife[i] = 24 + (int) (Math.random() * 20);
    }

    private void stepParticles() {
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f + dp(64);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            if (mPLife[i] <= 0) {
                // respawn only in the first 2/3 of a sweep so the field breathes
                if (mBeamFraction < 0.66f && Math.random() < 0.12f) seedParticle(i, cx, cy);
                continue;
            }
            mPLife[i]--;
            mPY[i] += mPVY[i];
            mPVY[i] *= 0.985f;
        }
    }

    private void clearParticles() {
        for (int i = 0; i < PARTICLE_COUNT; i++) mPLife[i] = 0;
    }

    // ───────────────────────── drawing ─────────────────────────

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float beamWidth = Math.min(w - dp(64), dp(260));
        mBeamGradient = new LinearGradient(0, 0, beamWidth, 0,
                new int[]{0x00FFFFFF, 0x88FFFFFF, 0xFFD0D0D0, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.30f, 0.52f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        mBeamPaint.setShader(mBeamGradient);
        mGlowGradient = new RadialGradient(0, 0, dp(110),
                new int[]{0x55D0D0D0, 0x22D0D0D0, 0x00D0D0D0},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        mGlowPaint.setShader(mGlowGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, mScrimPaint);

        float cx = w * 0.5f;
        float iconSize = dp(84) * mCardScale;
        float iconTop = h * 0.5f - dp(120) * mCardScale - iconSize * 0.5f + dp(10);

        // ① Glow ring behind the card — breathes with the beam sweep
        float pulse = 0.72f + 0.28f * (float) Math.sin(mBeamFraction * Math.PI * 2.0);
        canvas.save();
        canvas.translate(cx, iconTop + iconSize * 0.5f);
        canvas.scale(pulse * mCardScale, pulse * mCardScale);
        canvas.drawCircle(0, 0, dp(112), mGlowPaint);
        canvas.restore();

        // ② Card: rounded plate + icon (or letter-tile fallback)
        float cardHalf = iconSize * 0.5f + dp(10) * mCardScale;
        mTmpRect.set(cx - cardHalf, iconTop - dp(10) * mCardScale,
                cx + cardHalf, iconTop + iconSize + dp(10) * mCardScale);
        canvas.drawRoundRect(mTmpRect, dp(26), dp(26), mCardPaint);
        canvas.drawRoundRect(mTmpRect, dp(26), dp(26), mCardBorderPaint);

        mIconClipPath.reset();
        mTmpRect.set(cx - iconSize * 0.5f, iconTop, cx + iconSize * 0.5f, iconTop + iconSize);
        mIconClipPath.addRoundRect(mTmpRect, dp(20), dp(20), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mIconClipPath);
        if (mIcon != null) {
            mIconMatrix.reset();
            float scale = Math.max(iconSize / mIcon.getWidth(), iconSize / mIcon.getHeight());
            mIconMatrix.setScale(scale, scale);
            mIconMatrix.postTranslate(cx - mIcon.getWidth() * scale * 0.5f,
                    iconTop + (iconSize - mIcon.getHeight() * scale) * 0.5f);
            canvas.drawBitmap(mIcon, mIconMatrix, null);
        } else {
            canvas.drawRoundRect(mTmpRect, dp(20), dp(20), mCardBorderPaint);
            mNamePaint.getTextBounds("A", 0, 1, new android.graphics.Rect());
        }
        canvas.restore();

        // ③ Name
        float nameY = iconTop + iconSize + dp(24);
        mNamePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(mNameText, cx, nameY, mNamePaint);

        // ④ Version chip
        float chipBottom = nameY + dp(12);
        if (mVersionText != null) {
            float chipW = mChipPaint.measureText(mVersionText) + dp(16);
            float chipH = dp(20);
            mTmpRect.set(cx - chipW * 0.5f, chipBottom, cx + chipW * 0.5f, chipBottom + chipH);
            canvas.drawRoundRect(mTmpRect, chipH * 0.5f, chipH * 0.5f, mChipBgPaint);
            mChipPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(mVersionText, cx, chipBottom + chipH - dp(5.6f), mChipPaint);
            chipBottom += chipH;
        }

        // ⑤ Horizontal ENERGY BEAM (the launch line — opposite of download %)
        float beamW = Math.min(w - dp(64), dp(260));
        float beamH = dp(4.5f);
        float beamY = chipBottom + dp(22);
        mTmpRect.set(cx - beamW * 0.5f, beamY, cx + beamW * 0.5f, beamY + beamH);
        canvas.drawRoundRect(mTmpRect, beamH, beamH, mBeamBasePaint);

        // wide soft glow pass under the sweep
        float sweepX = -beamW * 0.5f + mBeamFraction * beamW * 2f; // travels beyond both edges
        mBeamMatrix.reset();
        mBeamMatrix.setTranslate(cx - beamW + sweepX - beamW * 0.5f, 0);
        mBeamGradient.setLocalMatrix(mBeamMatrix);
        canvas.save();
        canvas.clipRect(cx - beamW * 0.5f, beamY - dp(3), cx + beamW * 0.5f, beamY + beamH + dp(3));
        canvas.drawRoundRect(mTmpRect, beamH, beamH, mBeamPaint);
        canvas.restore();

        // glowing tip dot
        float tipX = cx - beamW + sweepX - beamW * 0.5f + beamW;
        mBeamGlowPaint.setColor(0xCCD0D0D0);
        float tipAlpha = tipX > cx - beamW * 0.5f && tipX < cx + beamW * 0.5f ? 1f : 0f;
        if (tipAlpha > 0f) {
            canvas.drawCircle(tipX, beamY + beamH * 0.5f, dp(6), mBeamGlowPaint);
        }

        // Particles rising from the beam
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            int life = mPLife[i];
            if (life <= 0) continue;
            int alpha = Math.min(200, life * 8);
            mParticlePaint.setColor((alpha << 24) | (i % 3 == 0 ? 0xD0D0D0 : 0xFFFFFF));
            canvas.drawCircle(mPX[i], mPY[i], dp(i % 4 == 0 ? 1.8f : 1.1f), mParticlePaint);
        }

        // ⑥ Phase text with crossfade
        float statusY = beamY + dp(34);
        mStatusPaint.setTextAlign(Paint.Align.CENTER);
        if (mPrevStatusText != null && !mPrevStatusText.isEmpty() && mTextCross < 1f) {
            mStatusPaint.setAlpha((int) (255 * (1f - mTextCross)));
            canvas.drawText(mPrevStatusText, cx, statusY, mStatusPaint);
        }
        mStatusPaint.setAlpha((int) (255 * mTextCross));
        canvas.drawText(mStatusText, cx, statusY, mStatusPaint);
        mStatusPaint.setAlpha(255);
    }

    // ───────────────────────── lifecycle ─────────────────────────

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mShowing) startBeamAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mApplyPendingPhase);
        mPendingPhase = null;
        stopBeamAnimator();
        animate().cancel();
        mShowing = false;
        clearParticles();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) stopBeamAnimator();
        else if (mShowing) startBeamAnimator();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
