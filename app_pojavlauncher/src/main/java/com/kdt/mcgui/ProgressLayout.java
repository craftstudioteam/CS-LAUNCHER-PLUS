package com.kdt.mcgui;


import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.widget.ProgressBar;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.utils.DownloadControl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.collection.ArrayMap;
import androidx.constraintlayout.widget.ConstraintLayout;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.services.ProgressService;

import java.util.ArrayList;


/** Class staring at specific values and automatically show something if the progress is present
 * Since progress is posted in a specific way, The packing/unpacking is handheld by the class
 *
 * This class relies on ExtraCore for its behavior.
 *
 * v3 — bottom-anchored "Download Console": big tabular %, luminous beam,
 * size/speed/ETA stats, and PAUSE / STOP / HIDE controls with a collapsed
 * mini-pill state. Pause & stop are delivered to the copy loop through
 * DownloadControl; HIDE collapses the deck into the pill.
 */
public class ProgressLayout extends ConstraintLayout implements View.OnClickListener, TaskCountListener{
    public static final String UNPACK_RUNTIME = "unpack_runtime";
    public static final String DOWNLOAD_MINECRAFT = "download_minecraft";
    public static final String DOWNLOAD_VERSION_LIST = "download_verlist";
    public static final String AUTHENTICATE_MICROSOFT = "authenticate_microsoft";
    public static final String INSTALL_MODPACK = "install_modpack";
    public static final String EXTRACT_COMPONENTS = "extract_components";
    public static final String EXTRACT_SINGLE_FILES = "extract_single_files";

    private static final int COLOR_TEXT_PRIMARY = 0xFFF0F0F3;
    private static final int COLOR_TEXT_SECONDARY = 0xFFFFFFFF;
    private static final int COLOR_SUCCESS = 0xFF9FD6AC;
    private static final int COLOR_AMBER = 0xFFD8C79A;

    public ProgressLayout(@NonNull Context context) {
        super(context);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private final ArrayList<LayoutProgressListener> mMap = new ArrayList<>();
    private LinearLayout mLinearLayout;
    private TextView mTaskNumberDisplayer;
    private ImageView mFlipArrow;
    private PulseOrbitView mPulseOrbit; // Phase-5 premium download animation system (Req-1)
    private TextView mStatusText;
    private TextView mDetailText;
    private TextView mSpeedText;
    private String mLastProgressingKey;
    private int mLastProgress = 0;
    private String mLastDetailText = "";

    private ProgressBar mProgressBar;
    private TextView mPercentageText;
    private TextView mEtaText;
    private View mDownloadCard;
    private ImageView mProgressIcon;
    private boolean mIsFinishing = false;
    /** true after the user pressed STOP — the next count-drop must exit quietly, no "✓" flash */
    private boolean mExpectingQuietEnd = false;

    // ── Download Console v3 controls ────────────────────────────────────
    private View mControlsRow;
    private View mBtnPause;
    private ImageView mBtnPauseIcon;
    private TextView mBtnPauseText;
    private View mBtnStop;
    private View mBtnHide;
    private View mPausedBadge;
    private View mConsolePill;
    private TextView mPillPercent;
    private boolean mIsCollapsed = false;

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private final Runnable mFadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            // Exit downward — the console sinks back off the bottom edge
            ProgressLayout.this.animate()
                .alpha(0f)
                .translationY(-(ProgressLayout.this.getHeight() + dp(24f)))
                .setDuration(420)
                .withEndAction(() -> {
                    hideChromeImmediately();
                })
                .start();
        }
    };

    /** Instantly parks the whole console off-screen and resets transient state. */
    private void hideChromeImmediately() {
        removeCallbacks(mFadeOutRunnable);
        ProgressLayout.this.animate().cancel();
        if (mDownloadCard != null) {
            mDownloadCard.animate().cancel();
        }
        if (mConsolePill != null) {
            mConsolePill.animate().cancel();
            mConsolePill.setVisibility(GONE);
            mConsolePill.setAlpha(1f);
            mConsolePill.setTranslationY(0f);
        }
        mIsCollapsed = false;
        if (mDownloadCard != null) {
            mDownloadCard.setVisibility(VISIBLE);
            mDownloadCard.setAlpha(1f);
            mDownloadCard.setTranslationY(0f);
        }
        ProgressLayout.this.setVisibility(GONE);
        ProgressLayout.this.setAlpha(1f);
        ProgressLayout.this.setTranslationY(0f);
        mIsFinishing = false;
    }


    public void observe(String progressKey){
        mMap.add(new LayoutProgressListener(progressKey));
    }

    public void cleanUpObservers() {
        for(LayoutProgressListener progressListener : mMap) {
            ProgressKeeper.removeListener(progressListener.progressKey, progressListener);
        }
    }

    public boolean hasProcesses(){
        return ProgressKeeper.getTaskCount() > 0;
    }

    /** Records that flow through DownloadUtils' monitored loop and honor pause/stop. */
    private static boolean isCancellableKey(String key) {
        return INSTALL_MODPACK.equals(key)
                || DOWNLOAD_MINECRAFT.equals(key)
                || UNPACK_RUNTIME.equals(key);
    }


    private void init(){
        inflate(getContext(), R.layout.view_progress, this);
        mLinearLayout = findViewById(R.id.progress_linear_layout);
        mTaskNumberDisplayer = findViewById(R.id.progress_textview);
        mFlipArrow = findViewById(R.id.progress_flip_arrow);
        mPulseOrbit = findViewById(R.id.pulse_orbit);
        mStatusText = findViewById(R.id.progress_status_text);
        mDetailText = findViewById(R.id.progress_detail_text);
        mSpeedText = findViewById(R.id.progress_speed_text);

        mProgressBar = findViewById(R.id.progress_horizontal_bar);
        mPercentageText = findViewById(R.id.progress_percentage_text);
        mEtaText = findViewById(R.id.progress_eta_text);
        mDownloadCard = findViewById(R.id.download_card);
        mProgressIcon = findViewById(R.id.progress_icon);

        mControlsRow = findViewById(R.id.progress_controls_row);
        mBtnPause = findViewById(R.id.progress_btn_pause);
        mBtnPauseIcon = findViewById(R.id.progress_btn_pause_icon);
        mBtnPauseText = findViewById(R.id.progress_btn_pause_text);
        mBtnStop = findViewById(R.id.progress_btn_stop);
        mBtnHide = findViewById(R.id.progress_btn_hide);
        mPausedBadge = findViewById(R.id.progress_paused_badge);
        mConsolePill = findViewById(R.id.console_pill);
        mPillPercent = findViewById(R.id.console_pill_percent);

        if (mDownloadCard != null) {
            mDownloadCard.setOnClickListener(v -> {
                if (mIsFinishing || ProgressKeeper.getTaskCount() == 0) {
                    mExpectingQuietEnd = false;
                    hideChromeImmediately();
                } else {
                    ProgressLayout.this.onClick(ProgressLayout.this);
                }
            });
        }

        // ── PAUSE / RESUME ──────────────────────────────────────────────
        if (mBtnPause != null) {
            mBtnPause.setOnClickListener(v -> {
                String key = mLastProgressingKey;
                if (key == null) return;
                boolean nowPaused = !DownloadControl.isPaused(key);
                DownloadControl.requestPause(key, nowPaused);
                updatePauseUi(nowPaused);
            });
        }

        // ── STOP ────────────────────────────────────────────────────────
        if (mBtnStop != null) {
            mBtnStop.setOnClickListener(v -> {
                String key = mLastProgressingKey;
                if (key == null) return;
                DownloadControl.requestPause(key, false); // clear pause so the loop can see the cancel
                DownloadControl.requestCancel(key);
                updatePauseUi(false);
                mExpectingQuietEnd = true;
                Toast.makeText(getContext(), R.string.download_console_stopped, Toast.LENGTH_SHORT).show();
                hideChromeImmediately(); // deck leaves right away; the task winds down quietly
            });
        }

        // ── HIDE (collapse to mini pill) ────────────────────────────────
        if (mBtnHide != null) {
            mBtnHide.setOnClickListener(v -> collapseToPill());
        }

        // ── Mini pill → expand back to full console ─────────────────────
        if (mConsolePill != null) {
            mConsolePill.setOnClickListener(v -> expandFromPill());
        }

        setBackgroundColor(Color.TRANSPARENT);
        setOnClickListener(this);
    }

    /** Collapse the console into the corner mini-pill (download keeps running). */
    private void collapseToPill() {
        if (mIsCollapsed || mIsFinishing || getVisibility() != VISIBLE) return;
        mIsCollapsed = true;

        if (mPillPercent != null) mPillPercent.setText(mLastProgress + "%");
        if (mConsolePill != null) {
            mConsolePill.setVisibility(VISIBLE);
            mConsolePill.setAlpha(0f);
            mConsolePill.setTranslationY(dp(12f));
            mConsolePill.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        if (mDownloadCard != null) {
            mDownloadCard.animate().cancel();
            mDownloadCard.animate()
                    .alpha(0f)
                    .translationY(dp(26f))
                    .setDuration(230)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        mDownloadCard.setVisibility(GONE);
                        mDownloadCard.setTranslationY(0f);
                    })
                    .start();
        }
    }

    /** Restore the full console from the mini-pill. */
    private void expandFromPill() {
        if (!mIsCollapsed) return;
        mIsCollapsed = false;

        if (mDownloadCard != null) {
            mDownloadCard.setVisibility(VISIBLE);
            mDownloadCard.setAlpha(0f);
            mDownloadCard.setTranslationY(dp(18f));
            mDownloadCard.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(300)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.5f))
                    .start();
        }
        if (mConsolePill != null) {
            mConsolePill.animate().cancel();
            mConsolePill.animate()
                    .alpha(0f)
                    .translationY(dp(8f))
                    .setDuration(190)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        mConsolePill.setVisibility(GONE);
                        mConsolePill.setAlpha(1f);
                        mConsolePill.setTranslationY(0f);
                    })
                    .start();
        }
    }

    /** Reflect the paused/running state across badge, button and beam tint. */
    private void updatePauseUi(boolean paused) {
        if (mPausedBadge != null) mPausedBadge.setVisibility(paused ? VISIBLE : GONE);
        if (mBtnPauseIcon != null) mBtnPauseIcon.setImageResource(paused ? R.drawable.ic_play_arrow : R.drawable.ic_pause);
        if (mBtnPauseText != null) mBtnPauseText.setText(paused ? R.string.download_console_resume : R.string.download_console_pause);
        if (mProgressBar != null && !mIsFinishing) {
            mProgressBar.setProgressTintList(paused ? ColorStateList.valueOf(COLOR_AMBER) : null);
        }
        if (paused) {
            if (mSpeedText != null) mSpeedText.setVisibility(GONE);
            if (mEtaText != null) mEtaText.setVisibility(GONE);
        }
    }

    public static void setProgress(String progressKey, int progress){
        ProgressKeeper.submitProgress(progressKey, progress, -1, (Object)null);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, @StringRes int resource, Object... message){
        ProgressKeeper.submitProgress(progressKey, progress, resource, message);
    }

    /** Update the text and progress content */
    public static void setProgress(String progressKey, int progress, String message){
        setProgress(progressKey,progress, -1, message);
    }

    /** Update the text and progress content */
    public static void clearProgress(String progressKey){
        setProgress(progressKey, -1, -1);
    }

    @Override
    public void onClick(View v) {
        if (mIsFinishing || ProgressKeeper.getTaskCount() == 0) {
            mExpectingQuietEnd = false;
            hideChromeImmediately();
        } else {
            mLinearLayout.setVisibility(mLinearLayout.getVisibility() == GONE ? VISIBLE : GONE);
            mFlipArrow.setRotation(mLinearLayout.getVisibility() == GONE? 0 : 180);
        }
    }

    @Override
    public void onUpdateTaskCount(int tc) {
        post(()->{
            // Launch-vs-download semantics: while the Launch sequence owns the
            // chrome (PREPARING / VERIFYING / STARTING), the console must NOT
            // pop up for the verification task record. It opens only for real
            // downloads (RUNTIME / DOWNLOADING) and non-launch flows (IDLE).
            int effectiveTc = tc;
            if (effectiveTc > 0 && net.kdt.pojavlaunch.launch.LaunchTracker.suppressesDownloadConsole()) {
                effectiveTc = 0;
            }
            if(effectiveTc > 0) {
                // v3 UX: the deck UI is RETIRED — downloads communicate through
                // the floating notification cards only. This view stays in the
                // tree (GONE) purely as the engine bridge: it keeps observing
                // ProgressKeeper records, drives pause/stop flags and feeds the
                // CsNotifier cards. Nothing of the old console is ever shown.
                setVisibility(GONE);
                return;
            }
            if (true) {
                removeCallbacks(mFadeOutRunnable);
                boolean becameVisible = getVisibility() != VISIBLE;
                mIsFinishing = false;
                mExpectingQuietEnd = false;
                setAlpha(1f);
                setTranslationY(0f);
                // fresh task: console starts expanded, chrome reset
                mIsCollapsed = false;
                if (mConsolePill != null) {
                    mConsolePill.animate().cancel();
                    mConsolePill.setVisibility(GONE);
                    mConsolePill.setAlpha(1f);
                    mConsolePill.setTranslationY(0f);
                }
                if (mDownloadCard != null) {
                    mDownloadCard.animate().cancel();
                    mDownloadCard.setVisibility(VISIBLE);
                    mDownloadCard.setAlpha(1f);
                    mDownloadCard.setTranslationY(0f);
                }
                if (mProgressBar != null) {
                    mProgressBar.setProgressTintList(null);
                }
                if (mStatusText != null) {
                    mStatusText.setTextColor(COLOR_TEXT_PRIMARY);
                }
                if (mPercentageText != null) {
                    mPercentageText.setTextColor(COLOR_TEXT_SECONDARY);
                }
                if (mProgressIcon != null) {
                    mProgressIcon.setAlpha(1f);
                    mProgressIcon.setVisibility(VISIBLE);
                }
                updatePauseUi(false);
                if (mControlsRow != null) {
                    mControlsRow.setVisibility(isCancellableKey(mLastProgressingKey) ? VISIBLE : GONE);
                }
                mTaskNumberDisplayer.setText(getContext().getString(R.string.progresslayout_tasks_in_progress, tc));
                setVisibility(VISIBLE);
                // Premium entrance: console rises from the bottom edge with a soft land
                if (becameVisible) {
                    animate().cancel();
                    setAlpha(0f);
                    setTranslationY(-dp(72f));
                    animate().alpha(1f).translationY(0f)
                            .setDuration(460)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(0.65f))
                            .start();
                }
            } else {
                if (getVisibility() == VISIBLE && !mIsFinishing) {
                    if (mExpectingQuietEnd) {
                        // User stopped this download — no success flash, just leave quietly.
                        mExpectingQuietEnd = false;
                        hideChromeImmediately();
                        return;
                    }
                    mIsFinishing = true;
                    // Completion choreography: beam flash + success palette
                    if (mPulseOrbit != null) {
                        mPulseOrbit.showCompleted();
                    }
                    if (mProgressIcon != null) {
                        mProgressIcon.setImageResource(R.drawable.ic_download);
                        mProgressIcon.animate().alpha(0.35f).setDuration(280).start();
                    }
                    if (mStatusText != null) {
                        mStatusText.setText("✓ Download Complete");
                        mStatusText.setTextColor(COLOR_SUCCESS);
                    }
                    if (mPercentageText != null) {
                        mPercentageText.setText("100%");
                        mPercentageText.setTextColor(COLOR_SUCCESS);
                    }
                    if (mProgressBar != null) {
                        mProgressBar.setProgress(100);
                        mProgressBar.setProgressTintList(ColorStateList.valueOf(COLOR_SUCCESS));
                        // one-shot beam flash to signal completion
                        mProgressBar.animate().cancel();
                        mProgressBar.setAlpha(0.3f);
                        mProgressBar.animate().alpha(1f).setDuration(480)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                    }
                    if (mPausedBadge != null) {
                        mPausedBadge.setVisibility(GONE);
                    }
                    if (mControlsRow != null) {
                        mControlsRow.setVisibility(GONE);
                    }
                    if (mDetailText != null) {
                        mDetailText.setVisibility(GONE);
                    }
                    if (mSpeedText != null) {
                        mSpeedText.setVisibility(GONE);
                    }
                    if (mEtaText != null) {
                        mEtaText.setVisibility(GONE);
                    }
                    // The collapse pill mirrors the success moment, then leaves with the deck
                    if (mIsCollapsed && mPillPercent != null) {
                        mPillPercent.setText("✓");
                    }
                    removeCallbacks(mFadeOutRunnable);
                    postDelayed(mFadeOutRunnable, 2600);
                } else if (getVisibility() != VISIBLE) {
                    mExpectingQuietEnd = false;
                    hideChromeImmediately();
                }
            }
        });
    }

    private static String formatRemainingTime(double seconds) {
        if (seconds < 0) return "";
        int totalSecs = (int) seconds;
        int mins = totalSecs / 60;
        int secs = totalSecs % 60;
        if (mins > 0) {
            return mins + "m " + secs + "s remaining";
        } else {
            return secs + "s remaining";
        }
    }

    /** Human title for a progress key, used by the notification cards. */
    static String friendlyKeyTitle(String key) {
        switch (key == null ? "" : key) {
            case DOWNLOAD_MINECRAFT: return "Minecraft files";
            case DOWNLOAD_VERSION_LIST: return "Version list";
            case UNPACK_RUNTIME: return "Java runtime";
            case AUTHENTICATE_MICROSOFT: return "Microsoft sign-in";
            case INSTALL_MODPACK: return "Modpack";
            case EXTRACT_COMPONENTS: return "Game components";
            case EXTRACT_SINGLE_FILES: return "Game files";
            default: return key != null && key.contains("mod_") ? "Mod download" : "Download";
        }
    }

    class LayoutProgressListener implements ProgressListener {
        final String progressKey;
        final TextProgressBar textView;
        final LinearLayout.LayoutParams params;
        private ObjectAnimator mProgressAnimator;
        private ValueAnimator mPercentageAnimator;
        private int mTargetProgress = -1;

        public LayoutProgressListener(String progressKey) {
            this.progressKey = progressKey;
            textView = new TextProgressBar(getContext());
            textView.setTextPadding(getContext().getResources().getDimensionPixelOffset(R.dimen._6sdp));
            params = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, getResources().getDimensionPixelOffset(R.dimen._20sdp));
            params.bottomMargin = getResources().getDimensionPixelOffset(R.dimen._6sdp);
            ProgressKeeper.addListener(progressKey, this);
        }
        @Override
        public void onProgressStarted() {
            // Fresh attempt for this record: clear stale pause/stop flags & notes
            DownloadControl.reset(progressKey);
            post(()-> {
                Log.i("ProgressLayout", "onProgressStarted");
                mLinearLayout.addView(textView, params);
                // Mirror live downloads into the notification system (skipped
                // while a launch owns the pipeline — its own states notify).
                if (!net.kdt.pojavlaunch.launch.LaunchTracker.suppressesDownloadConsole()) {
                    net.kdt.pojavlaunch.notifications.CsNotifier.progress(
                            progressKey, "Downloading", friendlyKeyTitle(progressKey));
                }
            });
        }

        @Override
        public void onProgressUpdated(int progress, int resid, Object... va) {
            post(()-> {
                if (progress >= 0) {
                    net.kdt.pojavlaunch.notifications.CsNotifier.updateProgress(progressKey, progress, null);
                }
                // Update individual task bar in the expandable list
                int current = textView.getProgress();
                if (progress != current && progress >= 0) {
                    ObjectAnimator anim = ObjectAnimator.ofInt(textView, "progress", current, progress);
                    anim.setDuration(200);
                    anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                    anim.start();
                } else {
                    textView.setProgress(progress);
                }
                if(resid != -1) textView.setText(getContext().getString(resid, va));
                else if(va != null && va.length > 0 && va[0] != null)textView.setText((String)va[0]);
                else textView.setText("");

                // Drive the Pulse-Orbit dial and remember the latest value
                if (progress >= 0 && mPulseOrbit != null) {
                    mPulseOrbit.setProgress(progress);
                    mLastProgress = progress;
                }
                boolean keyChanged = !this.progressKey.equals(mLastProgressingKey);
                mLastProgressingKey = this.progressKey;
                // Collapsed pill always mirrors the live percentage
                if (mIsCollapsed && mPillPercent != null && progress >= 0 && !mIsFinishing) {
                    mPillPercent.setText(progress + "%");
                }
                // Controls only exist for transfers that honor them
                if (mControlsRow != null && !mIsFinishing) {
                    mControlsRow.setVisibility(isCancellableKey(this.progressKey) ? VISIBLE : GONE);
                }
                if (keyChanged && !mIsFinishing) {
                    // Pause UI must reflect the newly-active record, not the previous one
                    updatePauseUi(DownloadControl.isPaused(this.progressKey));
                }

                // --- NEW COMPACT VIEW UPDATES ---
                if (mProgressBar != null && progress >= 0) {
                    int currProgress = mProgressBar.getProgress();
                    if (progress != mTargetProgress) {
                        mTargetProgress = progress;

                        if (mProgressAnimator != null && mProgressAnimator.isRunning()) {
                            mProgressAnimator.cancel();
                        }
                        mProgressAnimator = ObjectAnimator.ofInt(mProgressBar, "progress", currProgress, progress);
                        mProgressAnimator.setDuration(250);
                        mProgressAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
                        mProgressAnimator.start();

                        if (mPercentageAnimator != null && mPercentageAnimator.isRunning()) {
                            mPercentageAnimator.cancel();
                        }
                        mPercentageAnimator = ValueAnimator.ofInt(currProgress, progress);
                        mPercentageAnimator.setDuration(250);
                        mPercentageAnimator.addUpdateListener(animation -> {
                            if (mPercentageText != null) {
                                mPercentageText.setText(animation.getAnimatedValue() + "%");
                            }
                        });
                        mPercentageAnimator.start();
                    }

                }

                // Determine formatted values
                double speed = -1;
                String detailStr = "";
                String etaStr = "";
                String statusTitle = "";

                String modName = null;
                String modVersion = null;
                String modIconUrl = null;
                String contentType = null;

                if (va != null && va.length >= 9) {
                    try {
                        modName = (String) va[5];
                        modVersion = (String) va[6];
                        modIconUrl = (String) va[7];
                        contentType = (String) va[8];

                        double currentMB = ((Number) va[1]).doubleValue();
                        double totalMB = ((Number) va[2]).doubleValue();
                        speed = ((Number) va[3]).doubleValue();
                        double remainingSec = ((Number) va[4]).doubleValue();

                        if (totalMB > 0) {
                            detailStr = String.format(java.util.Locale.US, "%.1f MB / %.1f MB", currentMB, totalMB);
                        }
                        if (remainingSec >= 0) {
                            etaStr = formatRemainingTime(remainingSec);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (mProgressIcon != null) {
                    if (modIconUrl != null && !modIconUrl.isEmpty()) {
                        String cacheTag = modName != null ? modName : modIconUrl.substring(modIconUrl.lastIndexOf('/') + 1);
                        ModIconCache.getInstance().getImage(bitmap -> {
                            post(() -> {
                                if (mProgressIcon != null && bitmap != null) {
                                    int pad = (int) (getResources().getDisplayMetrics().density * 6);
                                    mProgressIcon.setPadding(pad, pad, pad, pad);
                                    mProgressIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                                    mProgressIcon.setImageBitmap(bitmap);
                                    mProgressIcon.setAlpha(1f);
                                }
                            });
                        }, cacheTag, modIconUrl);
                    } else {
                        int pad = (int) (getResources().getDisplayMetrics().density * 11);
                        mProgressIcon.setPadding(pad, pad, pad, pad);
                        mProgressIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                        mProgressIcon.setImageResource(R.drawable.ic_download);
                    }
                }

                if (this.progressKey != null) {
                    // One builder for both this row and the shade card (see
                    // ProgressTitles). The duplicated wording had already drifted —
                    // this row said "Unpacking Runtime" where the notification said
                    // "Downloading Java Runtime", and it also silently dropped shader
                    // packs (only "resourcepack" got a friendly label).
                    ProgressTitles.init(getResources());
                    statusTitle = ProgressTitles.title(progressKey, resid, va, contentType, modName);
                    if (statusTitle == null && resid != -1) {
                        statusTitle = getContext().getString(resid);
                    }
                }

                // If not using new format, perform fallback legacy parsing
                if (detailStr.isEmpty() && va != null) {
                    if (resid == R.string.newdl_downloading_game_files_size && va.length >= 3) {
                        try {
                            double currentMB = ((Number) va[0]).doubleValue();
                            double totalMB = ((Number) va[1]).doubleValue();
                            speed = ((Number) va[2]).doubleValue();
                            detailStr = String.format(java.util.Locale.US, "%.1f MB / %.1f MB", currentMB, totalMB);
                            if (speed > 0) {
                                double remainingMB = totalMB - currentMB;
                                double etaSeconds = remainingMB / speed;
                                etaStr = formatRemainingTime(etaSeconds);
                            } else {
                                etaStr = "";
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (resid == R.string.newdl_downloading_game_files && va.length >= 3) {
                        try {
                            long currentFiles = ((Number) va[0]).longValue();
                            long totalFiles = ((Number) va[1]).longValue();
                            speed = ((Number) va[2]).doubleValue();
                            detailStr = currentFiles + " / " + totalFiles + " files";
                            etaStr = "";
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (va.length >= 2 && va[0] instanceof Number && va[1] instanceof Number) {
                        try {
                            double currentMB = ((Number) va[0]).doubleValue();
                            double totalMB = ((Number) va[1]).doubleValue();
                            detailStr = String.format(java.util.Locale.US, "%.1f MB / %.1f MB", currentMB, totalMB);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else if (va.length >= 3 && va[1] instanceof Number && va[2] instanceof Number) {
                        try {
                            double currentMB = ((Number) va[1]).doubleValue();
                            double totalMB = ((Number) va[2]).doubleValue();
                            detailStr = String.format(java.util.Locale.US, "%.1f MB / %.1f MB", currentMB, totalMB);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        if (va.length > 1 && va[1] != null) {
                            detailStr = String.valueOf(va[1]);
                        } else if (va.length > 0 && va[0] != null) {
                            detailStr = String.valueOf(va[0]);
                        }
                    }
                }

                if (mStatusText != null) {
                    mStatusText.setTextColor(COLOR_TEXT_PRIMARY); // Keep status bright during progress
                    CharSequence prev = mStatusText.getText();
                    boolean titleChanged = prev == null || !prev.toString().contentEquals(statusTitle);
                    mStatusText.setText(statusTitle);
                    if (titleChanged && !statusTitle.isEmpty()) {
                        // task swap: quick fade+slide so transitions feel intentional
                        mStatusText.animate().cancel();
                        mStatusText.setAlpha(0f);
                        mStatusText.setTranslationX(8f);
                        mStatusText.animate().alpha(1f).translationX(0f)
                                .setDuration(220)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                    }
                }
                if (mDetailText != null) {
                    if (!detailStr.isEmpty()) {
                        mDetailText.setText(detailStr);
                        mDetailText.setVisibility(VISIBLE);
                    } else {
                        mDetailText.setVisibility(GONE);
                    }
                }
                boolean isPausedNow = DownloadControl.isPaused(this.progressKey);
                if (mSpeedText != null) {
                    if (speed >= 0 && !isPausedNow) {
                        mSpeedText.setText(String.format(java.util.Locale.US, "%.1f MB/s", speed));
                        mSpeedText.setVisibility(VISIBLE);
                    } else {
                        mSpeedText.setVisibility(GONE);
                    }
                }
                if (mEtaText != null) {
                    if (!etaStr.isEmpty() && !isPausedNow) {
                        mEtaText.setText("• " + etaStr);
                        mEtaText.setVisibility(VISIBLE);
                    } else {
                        mEtaText.setVisibility(GONE);
                    }
                }
            });
        }

        @Override
        public void onProgressEnded() {
            post(()-> {
                mLinearLayout.removeView(textView);
                if (mExpectingQuietEnd) {
                    net.kdt.pojavlaunch.notifications.CsNotifier.dismiss(progressKey);
                } else if (!net.kdt.pojavlaunch.launch.LaunchTracker.suppressesDownloadConsole()) {
                    net.kdt.pojavlaunch.notifications.CsNotifier.success(
                            progressKey, "Download complete", friendlyKeyTitle(progressKey));
                }
            });
        }

    }
}
