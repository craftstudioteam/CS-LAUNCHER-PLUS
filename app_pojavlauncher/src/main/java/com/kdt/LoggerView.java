package com.kdt;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * CS PREMIUM GLASS TERMINAL (Phase 3).
 *
 * Keeps every legacy behavior (log toggle, autoscroll toggle, cancel, stream
 * batching, animated line arrival) and adds:
 * - translucent glass chrome + status strip (line counter / PAUSED state)
 * - live SEARCH (filter without losing the raw buffer)
 * - PAUSE (keeps buffering silently, resumes cleanly)
 * - COPY to clipboard · EXPORT via share sheet · CLEAR
 * - severity highlighting: error / warning / success / info / debug
 */
public class LoggerView extends ConstraintLayout {
    private Logger.eventLogListener mLogListener;
    private ToggleButton mLogToggle;
    private RecyclerView mLogRecycler;
    private LogLineAdapter mAdapter;
    private LinearLayoutManager mLayoutManager;
    private TextView mCountText;
    private View mLiveDot;
    private View mCliOverlay;
    private TextView mCliCommand;
    private CsBlockLogoView mCliAscii;
    private TextView mCliBrand;
    private TextView mCliStatus;
    private TextView mCliProgressText;
    private android.widget.ProgressBar mCliProgress;
    private boolean mKeepAutoscroll = true;
    private boolean mPaused = false;
    private boolean mSmartMode = true;
    private boolean mCliIntroStarted = false;
    private boolean mCliIntroFinished = false;
    private int mStartupRawLineCount = 0;
    private final java.util.ArrayDeque<String> mSmartImportantBuffer = new java.util.ArrayDeque<>();
    private final java.util.LinkedHashSet<String> mSmartDedup = new java.util.LinkedHashSet<>();
    private Runnable mCliAnimationRunnable;
    private long mSessionStartedAt;
    private int mSmartWarnings;
    private int mSmartErrors;
    private boolean mReadySummaryShown;
    /** Batches rapid emissions so the animator keeps up with burst logger traffic. */
    private final java.util.ArrayDeque<String> mPendingLines = new java.util.ArrayDeque<>();
    private boolean mFlushScheduled = false;
    /** Kept so a burst can shorten its own add-animation instead of queueing 120 ms fades. */
    private DefaultItemAnimator mItemAnimator;
    /** The live dot breathes only while it is actually worth watching. */
    private android.animation.ValueAnimator mLivePulse;
    private int mLinesThisBurst = 0;

    public LoggerView(@NonNull Context context) {
        this(context, null);
    }

    public LoggerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Triggers the log view shown state by default when viewing it
            if (mLogToggle != null) mLogToggle.setChecked(visibility == VISIBLE);
    }

    private void init(){
        inflate(getContext(), R.layout.view_logger, this);

        // ── Animated terminal stream ──
        mLogRecycler = findViewById(R.id.content_log_recycler);
        mAdapter = new LogLineAdapter();
        mLayoutManager = new LinearLayoutManager(getContext());
        mLogRecycler.setLayoutManager(mLayoutManager);
        mLogRecycler.setAdapter(mAdapter);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(120);
        animator.setChangeDuration(100);
        animator.setMoveDuration(160);
        animator.setRemoveDuration(120);
        // Overlapping change animations are what makes a busy log look like it is
        // flickering; rows here only ever arrive and leave, so moves stay animated
        // and changes snap straight to their new content.
        animator.setSupportsChangeAnimations(false);
        mLogRecycler.setItemAnimator(animator);
        mItemAnimator = animator;
        mLogRecycler.setVisibility(GONE);

        mCountText = findViewById(R.id.log_count_text);
        mLiveDot = findViewById(R.id.log_live_dot);
        mCliOverlay = findViewById(R.id.log_cli_overlay);
        mCliCommand = findViewById(R.id.log_cli_command);
        mCliAscii = findViewById(R.id.log_cli_ascii);
        mCliBrand = findViewById(R.id.log_cli_brand);
        mCliStatus = findViewById(R.id.log_cli_status);
        mCliProgress = findViewById(R.id.log_cli_progress);
        mCliProgressText = findViewById(R.id.log_cli_progress_text);

        // Toggle log visibility
        mLogToggle = findViewById(R.id.content_log_toggle_log);
        mLogToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    if(isChecked) {
                        // Soft body fade-in: the terminal "opens" instead of popping
                        mLogRecycler.setAlpha(0f);
                        mLogRecycler.setTranslationY(dp(14));
                        mLogRecycler.setVisibility(VISIBLE);
                        mLogRecycler.animate().alpha(1f).translationY(0f)
                                .setDuration(320)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .start();
                        String mode = LauncherPreferences.DEFAULT_PREF != null
                                ? LauncherPreferences.DEFAULT_PREF.getString(
                                        "game_log_display_mode", "animated_smart")
                                : "animated_smart";
                        mSmartMode = !"full_raw".equals(mode);
                        if (mAdapter.getItemCount() == 0) {
                            if (mSmartMode && !mCliIntroStarted) {
                                startCliIntro();
                            } else {
                                mAdapter.appendLine(new LogLineAdapter.LogLine(
                                        tinted("› Waiting for game output…", 0xFF6B7280),
                                        "› Waiting for game output…"));
                            }
                        }
                        Logger.addLogListener(mLogListener);
                    }else{
                        mAdapter.clear();
                        if (mCliAnimationRunnable != null) removeCallbacks(mCliAnimationRunnable);
                        if (mCliOverlay != null) mCliOverlay.setVisibility(GONE);
                        if (mCliIntroStarted) mCliIntroFinished = true;
                        Logger.removeLogListener(mLogListener);
                        mLogRecycler.animate().cancel();
                        mLogRecycler.setVisibility(GONE);
                    }
                    updateStatus();
                });
        mLogToggle.setChecked(false);

        // Remove the loggerView from the user View
        ImageButton cancelButton = findViewById(R.id.log_view_cancel);
        cancelButton.setOnClickListener(view -> LoggerView.this.setVisibility(GONE));

        // Autoscroll switch
        ToggleButton autoscrollToggle = findViewById(R.id.content_log_toggle_autoscroll);
        autoscrollToggle.setOnCheckedChangeListener(
                (compoundButton, isChecked) -> {
                    mKeepAutoscroll = isChecked;
                    if(isChecked) scrollToEnd(false);
                }
        );
        autoscrollToggle.setChecked(true);

        // ── Phase 3 controls ──
        EditText search = findViewById(R.id.log_search_input);
        View searchClear = findViewById(R.id.log_search_clear);
        if (searchClear != null) {
            searchClear.setOnClickListener(v -> {
                search.setText("");
                search.clearFocus();
                mAdapter.setFilter(null);
                updateStatus();
                try {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
                } catch (Throwable ignored) {}
            });
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mAdapter.setFilter(s != null ? s.toString() : null);
                if (searchClear != null) {
                    searchClear.setVisibility(s != null && s.length() > 0 ? VISIBLE : GONE);
                }
                if (mKeepAutoscroll) scrollToEnd(false);
                updateStatus();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            search.clearFocus();
            try {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            } catch (Throwable ignored) {}
            return true;
        });

        ImageButton pause = findViewById(R.id.log_btn_pause);
        pause.setOnClickListener(v -> togglePause());

        ImageButton copy = findViewById(R.id.log_btn_copy);
        copy.setOnClickListener(v -> copyLogs());

        ImageButton export = findViewById(R.id.log_btn_export);
        export.setOnClickListener(v -> exportLogs());

        ImageButton clear = findViewById(R.id.log_btn_clear);
        clear.setOnClickListener(v -> {
            mAdapter.clear();
            mSmartErrors = 0; mSmartWarnings = 0;
            updateStatus();
        });
        // Phase 8: severity chips filter the stream with one tap (toggle).
        mChipErrors = findViewById(R.id.log_chip_errors);
        mChipWarns = findViewById(R.id.log_chip_warns);
        if (mChipErrors != null) mChipErrors.setOnClickListener(v -> toggleSeverityFilter(search, "error"));
        if (mChipWarns != null) mChipWarns.setOnClickListener(v -> toggleSeverityFilter(search, "warn"));
        net.kdt.pojavlaunch.UiMotion.pressFeedback(pause, copy, export, clear, cancelButton, mChipErrors, mChipWarns);
        // Rail tools stagger in the first time the terminal opens.
        View rail = findViewById(R.id.log_rail);
        if (rail instanceof android.view.ViewGroup) {
            net.kdt.pojavlaunch.Anime.stagger((android.view.ViewGroup) rail, 120, 50, net.kdt.pojavlaunch.Anime.Fx.POP);
        }

        // Listen to logs — batched + buttery
        mLogListener = text -> {
            if(mLogRecycler.getVisibility() != VISIBLE) return;
            synchronized (mPendingLines) {
                mPendingLines.add(text);
                // paused → keep buffering silently (bounded) without flushing
                if (mPaused && mPendingLines.size() > 600) mPendingLines.poll();
            }
            if (!mPaused) scheduleFlush();
        };

        // Soft pulse on the live indicator while streaming. It is driven from
        // updateLivePulse() so pausing the stream or hiding the terminal parks the
        // dot instead of leaving an infinite animator running behind it.
        updateLivePulse();
    }

    /**
     * Start / stop the breathing halo on the live dot. "Live" means: the terminal
     * is on screen, attached to a window, and the stream is not paused. Anything
     * else parks the dot at full alpha, which reads as "paused", not "broken".
     */
    private void updateLivePulse() {
        if (mLiveDot == null) return;
        boolean wantRunning = mLogRecycler != null
                && mLogRecycler.getVisibility() == VISIBLE
                && isShown() && getWindowToken() != null && !mPaused;
        if (wantRunning) {
            if (mLivePulse != null && mLivePulse.isRunning()) return;
            android.animation.ValueAnimator pulse =
                    android.animation.ValueAnimator.ofFloat(1f, 0.35f);
            pulse.setDuration(900);
            pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            pulse.addUpdateListener(a -> {
                View dot = mLiveDot;
                if (dot != null) dot.setAlpha((Float) a.getAnimatedValue());
            });
            mLivePulse = pulse;
            pulse.start();
            return;
        }
        if (mLivePulse != null) {
            mLivePulse.cancel();
            mLivePulse = null;
        }
        mLiveDot.setAlpha(1f);
    }

    @Override
    protected void onVisibilityChanged(@androidx.annotation.NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this) updateLivePulse();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateLivePulse();
    }

    // ═══════════════════════ ACTIONS ═══════════════════════

    private void togglePause() {
        post(this::updateLivePulse);
        mPaused = !mPaused;
        if (!mPaused) scheduleFlush(); // drain everything buffered while paused
        if (mLiveDot != null) {
            mLiveDot.getBackground().setTint(mPaused ? 0xFFE8C989 : 0xFFD2D6DE);
        }
        ImageButton pause = findViewById(R.id.log_btn_pause);
        if (pause != null) pause.setColorFilter(mPaused ? 0xFFFFB020 : 0xFFFFFFFF);
        updateStatus();
    }

    private void copyLogs() {
        String text = mAdapter.dumpText();
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.cs_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("game_logs", text));
        }
        Toast.makeText(getContext(), R.string.cs_log_copied, Toast.LENGTH_SHORT).show();
    }

    private void exportLogs() {
        String text = mAdapter.dumpText();
        if (text.isEmpty()) {
            Toast.makeText(getContext(), R.string.cs_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "CS LAUNCHER PLUS — Game Log");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            getContext().startActivity(Intent.createChooser(send,
                    getContext().getString(R.string.cs_log_export)));
        } catch (Exception ignored) {}
    }

    private void updateStatus() {
        if (mCountText == null) return;
        String state = mPaused ? "PAUSED" : "LIVE";
        int raw = mAdapter.rawCount();
        int vis = mAdapter.visibleCount();
        String mode = mSmartMode ? "SMART" : "RAW";
        mCountText.setText((vis == raw
                ? raw + " lines"
                : vis + "/" + raw + " lines") + " • " + mode + " • " + state);
        if (mChipErrors != null) {
            String t = mSmartErrors + " err";
            if (!t.contentEquals(mChipErrors.getText())) {
                mChipErrors.setText(t);
                if (mSmartErrors > 0) net.kdt.pojavlaunch.Anime.pop(mChipErrors);
            }
            mChipErrors.setAlpha(mSmartErrors > 0 ? 1f : 0.55f);
        }
        if (mChipWarns != null) {
            String t = mSmartWarnings + " warn";
            if (!t.contentEquals(mChipWarns.getText())) {
                mChipWarns.setText(t);
                if (mSmartWarnings > 0) net.kdt.pojavlaunch.Anime.pulse(mChipWarns);
            }
            mChipWarns.setAlpha(mSmartWarnings > 0 ? 1f : 0.55f);
        }
    }

    private TextView mChipErrors, mChipWarns;

    /** Tapping a severity chip drops its keyword into the search box (again = clear). */
    private void toggleSeverityFilter(EditText search, String keyword) {
        if (search == null) return;
        String cur = search.getText() != null ? search.getText().toString() : "";
        search.setText(keyword.equalsIgnoreCase(cur) ? "" : keyword);
        search.setSelection(search.getText().length());
    }

    // ═══════════════════════ SMART CLI INTRO ═══════════════════════

    private void startCliIntro() {
        mCliIntroStarted = true;
        mCliIntroFinished = false;
        mStartupRawLineCount = 0;
        mSmartImportantBuffer.clear();
        mSmartDedup.clear();
        mSmartWarnings = 0;
        mSmartErrors = 0;
        mReadySummaryShown = false;
        mSessionStartedAt = android.os.SystemClock.elapsedRealtime();

        if (mCliOverlay == null) {
            mCliIntroFinished = true;
            return;
        }
        // ── Phase 8 BOOT SEQUENCE ──────────────────────────────────────────
        // A real timeline instead of a fixed-rate bar:
        //   0    overlay fades in, titlebar dots pop one by one
        //   180  prompt types "cs boot --profile" with a blinking block cursor
        //   ~900 pixel logo reveals, brand tracks in from wide letter-spacing
        //   then three checklist steps complete in sequence — each flips its
        //        spinner into a "[ ok ]" tick with a pop — while the bar eases
        //        (outExpo) towards the step's milestone
        //   end  "ready" pulse, then the overlay lifts and the live log lands
        final String command = "cs boot --profile --renderer auto";
        final String[] spinner = {"\u280b", "\u2819", "\u2839", "\u2838", "\u283c", "\u2834", "\u2826", "\u2827", "\u2807", "\u280f"};
        final String[] steps = {"runtime", "renderer", "mod loader"};
        final int[] milestones = {30, 64, 96};

        mCliOverlay.animate().cancel();
        mCliOverlay.setVisibility(VISIBLE);
        mCliOverlay.setAlpha(0f);
        mCliOverlay.setScaleX(0.975f);
        mCliOverlay.setScaleY(0.975f);
        mCliOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(260).withLayer().start();
        View titlebar = findViewById(R.id.log_cli_titlebar);
        if (titlebar instanceof android.view.ViewGroup) {
            android.view.ViewGroup tb = (android.view.ViewGroup) titlebar;
            for (int i = 0; i < Math.min(3, tb.getChildCount()); i++) {
                View dot = tb.getChildAt(i);
                dot.setScaleX(0f); dot.setScaleY(0f);
                dot.animate().scaleX(1f).scaleY(1f).setStartDelay(120L + i * 70L).setDuration(320)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
            }
        }
        mCliCommand.setText("$ \u2588");
        if (mCliAscii != null) {
            mCliAscii.setAlpha(0f);
            mCliAscii.setTranslationY(8f * getResources().getDisplayMetrics().density);
        }
        mCliBrand.setAlpha(0f);
        mCliBrand.setScaleX(0.94f);
        mCliBrand.setScaleY(0.94f);
        mCliBrand.setLetterSpacing(0.46f);
        View subtitle = findViewById(R.id.log_cli_subtitle);
        if (subtitle != null) subtitle.setAlpha(0f);
        mCliStatus.setText(line(steps[0], "") + "\n" + line(steps[1], "") + "\n" + line(steps[2], ""));
        mCliStatus.setAlpha(0f);
        mCliProgress.setProgress(0);
        mCliProgressText.setText(bar(0));

        mCliAnimationRunnable = new Runnable() {
            int stage = 0;          // 0 typing · 1 logo · 2..4 steps · 5 ready
            int charIndex = 0;
            int spin = 0;
            int step = 0;
            int shown = 0;          // progress currently shown
            long stepStartedAt = 0L;
            boolean cursorOn = true;

            @Override public void run() {
                if (mCliIntroFinished) return;
                switch (stage) {
                    case 0: { // typewriter with a blinking block cursor
                        if (charIndex < command.length()) {
                            charIndex++;
                            mCliCommand.setText("$ " + command.substring(0, charIndex) + "\u2588");
                            postDelayed(this, charIndex < 3 ? 120L : 26L);
                            return;
                        }
                        mCliCommand.setText("$ " + command);
                        stage = 1;
                        postDelayed(this, 160L);
                        return;
                    }
                    case 1: { // logo reveal + brand tracking
                        if (mCliAscii != null) {
                            mCliAscii.startReveal();
                            mCliAscii.animate().alpha(1f).translationY(0f).setDuration(340)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                                    .withLayer().start();
                        }
                        mCliBrand.animate().alpha(1f).scaleX(1f).scaleY(1f)
                                .setStartDelay(260L).setDuration(460)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                                .withLayer().start();
                        ValueAnimator tracking = ValueAnimator.ofFloat(0.46f, 0.22f);
                        tracking.setStartDelay(260L);
                        tracking.setDuration(620);
                        tracking.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
                        tracking.addUpdateListener(a -> {
                            if (mCliBrand != null) mCliBrand.setLetterSpacing((float) a.getAnimatedValue());
                        });
                        tracking.start();
                        if (subtitle != null) subtitle.animate().alpha(1f).setStartDelay(560L).setDuration(360).start();
                        mCliStatus.animate().alpha(1f).setStartDelay(640L).setDuration(300).start();
                        stage = 2;
                        stepStartedAt = android.os.SystemClock.uptimeMillis() + 760L;
                        postDelayed(this, 760L);
                        return;
                    }
                    case 2: case 3: case 4: { // checklist steps
                        step = stage - 2;
                        int target = milestones[step];
                        int from = step == 0 ? 0 : milestones[step - 1];
                        long elapsed = android.os.SystemClock.uptimeMillis() - stepStartedAt;
                        long dur = 700L + step * 160L;
                        float t = Math.min(1f, elapsed / (float) dur);
                        // outExpo easing of the bar towards this step's milestone
                        float eased = t >= 1f ? 1f : (float) (1 - Math.pow(2, -10 * t));
                        shown = Math.round(from + (target - from) * eased);
                        String spinChar = spinner[spin++ % spinner.length];
                        StringBuilder status = new StringBuilder();
                        for (int i = 0; i < steps.length; i++) {
                            if (i > 0) status.append('\n');
                            if (i < step) status.append(line(steps[i], null));
                            else if (i == step) status.append(line(steps[i], t >= 1f ? null : spinChar));
                            else status.append(line(steps[i], ""));
                        }
                        mCliStatus.setText(status);
                        mCliProgress.setProgress(shown);
                        mCliProgressText.setText(bar(shown));
                        if (t >= 1f) {
                            // tick lands: the checklist pops once
                            mCliStatus.animate().cancel();
                            mCliStatus.setScaleX(1.015f); mCliStatus.setScaleY(1.015f);
                            mCliStatus.animate().scaleX(1f).scaleY(1f).setDuration(220)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
                            stage++;
                            stepStartedAt = android.os.SystemClock.uptimeMillis() + 140L;
                            postDelayed(this, 140L);
                        } else {
                            postDelayed(this, 60L);
                        }
                        return;
                    }
                    default: { // ready
                        mCliProgress.setProgress(100);
                        mCliProgressText.setText(bar(100));
                        mCliCommand.setText("$ ready \u2014 handing over to the game log");
                        mCliProgressText.animate().alpha(0.4f).setDuration(160)
                                .withEndAction(() -> mCliProgressText.animate().alpha(1f).setDuration(160).start()).start();
                        postDelayed(() -> hideCliOverlayAndFinish(false), 520L);
                    }
                }
            }
        };
        post(mCliAnimationRunnable);
    }

    /** ASCII progress bar: {@code [=======>       ]  42%}. */
    private static String bar(int progress) {
        int cells = 22;
        int filled = Math.min(cells, Math.round(progress * cells / 100f));
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < cells; i++) b.append(i < filled ? '=' : (i == filled ? '>' : ' '));
        b.append("] ").append(progress < 10 ? "  " : progress < 100 ? " " : "").append(progress).append('%');
        return b.toString();
    }

    /**
     * One aligned terminal line: {@code > label ......... [ ok ]}.
     * Passing {@code null} marks the step done, an empty string marks it pending.
     */
    private static String line(String label, @Nullable String spinner) {
        StringBuilder sb = new StringBuilder("> ");
        sb.append(label);
        for (int i = label.length(); i < 12; i++) sb.append('.');
        sb.append(' ');
        if (spinner == null) sb.append("[ ok ]");
        else if (spinner.isEmpty()) sb.append("[    ]");
        else sb.append("[  ").append(spinner).append(" ]");
        return sb.toString();
    }

    private void hideCliOverlayAndFinish(boolean immediate) {
        if (mCliOverlay == null) {
            finishCliIntro();
            return;
        }
        mCliOverlay.animate().cancel();
        if (immediate) {
            mCliOverlay.setVisibility(GONE);
            finishCliIntro();
            return;
        }
        mCliOverlay.animate().alpha(0f).scaleX(0.985f).scaleY(0.985f)
                .setDuration(220).withLayer()
                .withEndAction(() -> {
                    mCliOverlay.setVisibility(GONE);
                    mCliOverlay.setAlpha(1f);
                    mCliOverlay.setScaleX(1f);
                    mCliOverlay.setScaleY(1f);
                    finishCliIntro();
                }).start();
    }

    private void finishCliIntro() {
        if (mCliIntroFinished) return;
        mCliIntroFinished = true;
        if (mCliAnimationRunnable != null) removeCallbacks(mCliAnimationRunnable);
        appendSmartImportant("[SYSTEM] CS LAUNCHER PLUS bootstrap complete");
        while (!mSmartImportantBuffer.isEmpty()) {
            appendSmartImportant(mSmartImportantBuffer.poll());
        }
        if (mKeepAutoscroll) scrollToEnd(true);
        updateStatus();
    }

    /** Deduplicate repetitive mod-loader messages before they reach the user. */
    private boolean appendSmartImportant(@Nullable String important) {
        if (important == null || important.trim().isEmpty()) return false;
        String key = important.replaceAll("\\s+", " ").trim();
        if (!mSmartDedup.add(key)) return false;
        if (mSmartDedup.size() > 240) {
            java.util.Iterator<String> it = mSmartDedup.iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        String up = important.toUpperCase(java.util.Locale.US);
        if (up.startsWith("[ERROR]")) mSmartErrors++;
        else if (up.startsWith("[WARNING]")) mSmartWarnings++;
        mAdapter.appendLine(new LogLineAdapter.LogLine(
                styledSmartLine(important), important));
        if (up.startsWith("[READY]") && !mReadySummaryShown) {
            mReadySummaryShown = true;
            String summary = mSmartErrors == 0
                    ? "[SESSION] Launch healthy • " + mSmartWarnings + " actionable warning(s)"
                    : "[SESSION] Launch completed with " + mSmartErrors + " error(s)";
            mAdapter.appendLine(new LogLineAdapter.LogLine(styledSmartLine(summary), summary));
        }
        return true;
    }

    /** Timestamp + colored level + readable message, similar to modern log viewers. */
    private CharSequence styledSmartLine(String important) {
        long elapsed = Math.max(0L, android.os.SystemClock.elapsedRealtime() - mSessionStartedAt);
        String stamp = String.format(java.util.Locale.US, "%02d:%02d.%d  ",
                elapsed / 60000L, (elapsed / 1000L) % 60L, (elapsed / 100L) % 10L);
        String full = stamp + important;
        android.text.SpannableString styled = new android.text.SpannableString(full);
        styled.setSpan(new android.text.style.ForegroundColorSpan(0xFF5F626D),
                0, stamp.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int tagStart = stamp.length();
        int tagEnd = important.indexOf(']') + 1;
        if (tagEnd > 0) {
            tagEnd += stamp.length();
            String tag = important.substring(0, important.indexOf(']') + 1).toUpperCase(java.util.Locale.US);
            int color = tag.contains("ERROR") ? 0xFFFF6B74
                    : tag.contains("WARN") ? 0xFFFFB020
                    : tag.contains("READY") ? 0xFFD2D6DE
                    : tag.contains("MOD") ? 0xFFB8A7E8
                    : tag.contains("GRAPHICS") ? 0xFF91B7D9
                    : 0xFFB9BBC4;
            styled.setSpan(new android.text.style.ForegroundColorSpan(color),
                    tagStart, tagEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    tagStart, tagEnd, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (tagEnd < full.length()) {
                styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFD1D2D8),
                        tagEnd, full.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return styled;
    }

    private boolean isCriticalLine(String line) {
        String up = line.toUpperCase(java.util.Locale.US);
        return up.contains("FATAL") || up.contains("ERROR") || up.contains("EXCEPTION")
                || up.contains("CAUSED BY") || up.contains("OUTOFMEMORY")
                || up.contains("UNSATISFIEDLINK") || up.contains("MIXIN APPLY FAILED")
                || up.contains("CRASH REPORT");
    }

    /** Return a user-readable important line, or null for harmless startup spam. */
    @Nullable
    private String toSmartLine(String line) {
        if (line == null) return null;
        String clean = line.trim();
        String up = clean.toUpperCase(java.util.Locale.US);
        if (clean.isEmpty()) return null;

        // Errors remain complete: diagnostics must never be simplified away.
        if (isCriticalLine(clean)) return "[ERROR] " + clean;

        // Hide harmless warning spam; surface warnings a player can act on.
        if (up.contains("WARN")) {
            boolean actionable = up.contains("MOD") || up.contains("MIXIN")
                    || up.contains("RENDER") || up.contains("OPENGL")
                    || up.contains("VULKAN") || up.contains("MISSING")
                    || up.contains("UNSUPPORTED") || up.contains("MEMORY")
                    || up.contains("AUTH") || up.contains("FAILED")
                    || up.contains("INCOMPATIB");
            return actionable ? "[WARNING] " + clean : null;
        }

        if (up.contains("FABRIC LOADER"))
            return "[MOD LOADER] Fabric Loader initialized";
        if (up.contains("NEOFORGE"))
            return "[MOD LOADER] NeoForge initialized";
        if (up.contains("FORGE MOD LOADER") || up.contains("MINECRAFTFORGE")
                || up.contains("MODLAUNCHER RUNNING"))
            return "[MOD LOADER] Forge initialized";
        if (up.contains("QUILT LOADER"))
            return "[MOD LOADER] Quilt Loader initialized";

        java.util.regex.Matcher count = java.util.regex.Pattern
                .compile("(?i)(?:loading|loaded|discover(?:ed)?|found)\\s+(\\d+)\\s+mods?")
                .matcher(clean);
        if (count.find()) return "[MODS] " + count.group(1) + " mods discovered";

        if (up.contains("SODIUM")) return "[MOD] Sodium performance engine active";
        if (up.contains("IRIS")) return "[MOD] Iris shader support active";
        if (up.contains("OPTIFINE")) return "[MOD] OptiFine detected";
        if (up.contains("IMMEDIATELYFAST")) return "[MOD] ImmediatelyFast active";
        if (up.contains("LITHIUM")) return "[MOD] Lithium optimization active";

        // Renderer/JVM implementation chatter is intentionally hidden in Smart
        // mode; the CLI intro already reports readiness. Real failures/warnings
        // were handled above and are never suppressed.
        if (up.contains("MOBILEGLUES") || up.contains(" ZINK")
                || up.contains("GALLIUM_DRIVER=ZINK") || up.contains("GL4ES")
                || up.contains("OPENGL VENDOR") || up.contains("BACKEND LIBRARY")
                || up.contains("LWJGL VERSION") || up.contains("JAVA VERSION")
                || up.contains("JAVA RUNTIME") || up.contains("JVM INITIALIZED")) {
            return null;
        }

        if (up.contains("SOUND ENGINE STARTED") || up.contains("OPENAL INITIALIZED"))
            return "[AUDIO] Game audio engine ready";
        if (up.contains("RELOADING RESOURCEMANAGER") || up.contains("RESOURCE RELOAD"))
            return "[RESOURCES] Resource packs loaded";
        if (up.contains("AUTHENTICATED") || up.contains("LOGGED IN AS"))
            return "[ACCOUNT] Premium session authenticated";
        if (up.contains("DATAFIXER") && up.contains("OPTIM"))
            return "[GAME] Optimizing Minecraft world data";
        if (up.contains("SETTING USER")) return "[GAME] Player profile attached";
        if (up.contains("STARTING MINECRAFT")) return "[GAME] Minecraft process started";
        if (up.contains("PREPARING SPAWN AREA")) return "[WORLD] Preparing spawn area";
        if (up.contains("JOINING WORLD") || up.contains("JOINED THE GAME"))
            return "[WORLD] Entering the Minecraft world";
        if (up.contains("CONNECTING TO") && (up.contains("SERVER") || up.contains(":")))
            return "[NETWORK] Connecting to multiplayer server";
        if (up.contains("SAVING CHUNKS")) return "[WORLD] Saving world data";
        if (up.contains("DONE (") && (up.contains("MAIN") || up.contains("SERVER")))
            return "[READY] Minecraft finished loading";
        if (up.contains("JAVA EXIT CODE")) return "[GAME] " + clean;

        // Intentionally hidden: JVM argument dumps, env variables, classpath,
        // individual mixin registrations and repetitive debug/info chatter.
        return null;
    }

    // ═══════════════════════ STREAM ═══════════════════════

    private void scheduleFlush() {
        synchronized (mPendingLines) {
            if (mFlushScheduled) return;
            mFlushScheduled = true;
        }
        postDelayed(this::flushPending, 90);
    }

    private void flushPending() {
        String line;
        boolean inserted = false;
        mLinesThisBurst = 0;
        mLinesJustAppended = 0;
        for (;;) {
            synchronized (mPendingLines) {
                line = mPendingLines.poll();
                if (line == null) {
                    mFlushScheduled = false;
                    break;
                }
            }
            if (!mSmartMode) {
                mAdapter.appendLine(new LogLineAdapter.LogLine(colorizeLine(line), line));
                inserted = true;
                mLinesThisBurst++;
                mLinesJustAppended++;
                continue;
            }

            String important = toSmartLine(line);
            if (!mCliIntroFinished) {
                // Raw logging continues normally in Logger/latestlog.txt. During
                // the non-blocking visual intro we retain only readable events.
                mStartupRawLineCount++;
                if (isCriticalLine(line)) {
                    hideCliOverlayAndFinish(true);
                    appendSmartImportant(important);
                    inserted = true;
                } else if (important != null && mSmartImportantBuffer.size() < 80) {
                    mSmartImportantBuffer.add(important);
                }
                continue;
            }

            if (important != null && appendSmartImportant(important)) {
                inserted = true;
                mLinesThisBurst++;
                mLinesJustAppended++;
            }
        }
        if (inserted) {
            // A single line gets the soft 120 ms fade; a wall of startup output
            // gets a shortened one, otherwise twenty overlapping 120 ms add
            // animations queue up and the tail visibly stutters.
            if (mItemAnimator != null) {
                int want = mLinesThisBurst > 5 ? 60 : 120;
                if (mItemAnimator.getAddDuration() != want) mItemAnimator.setAddDuration(want);
            }
            mLinesThisBurst = 0;
            if (mKeepAutoscroll) scrollToEnd(mLinesJustAppended <= 2);
            updateStatus();
        }
        mLinesJustAppended = 0;
    }

    /** How many rows the last flush put on screen — feeds the glide/jump choice. */
    private int mLinesJustAppended = 0;

    /**
     * Glide to the newest line when one or two rows landed; jump when a burst
     * landed. smoothScrollToPosition across a long distance never catches up with
     * a busy log, and the reader sees the viewport lagging behind the tail.
     */
    private void scrollToEnd(boolean smooth) {
        int last = mAdapter.getItemCount() - 1;
        if (last < 0) return;
        if (!smooth) {
            mLogRecycler.scrollToPosition(last);
            return;
        }
        int first = mLayoutManager.findFirstVisibleItemPosition();
        int distance = first == androidx.recyclerview.widget.RecyclerView.NO_POSITION
                ? Integer.MAX_VALUE : last - first;
        if (distance > 6) {
            mLogRecycler.scrollToPosition(last);
        } else {
            mLogRecycler.smoothScrollToPosition(last);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static CharSequence tinted(String text, int color) {
        android.text.SpannableString s = new android.text.SpannableString(text);
        s.setSpan(new android.text.style.ForegroundColorSpan(color), 0, text.length(),
                android.text.SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Severity highlighting: error → red, warn → amber, success → green, info → silver. */
    private CharSequence colorizeLine(String line) {
        int color = 0xFFD0D0D0; // default body tone
        String up = line.toUpperCase(java.util.Locale.US);
        if (up.contains("ERROR") || up.contains("FATAL") || up.contains("EXCEPTION")
                || up.contains("CAUSED BY") || up.contains("FAILED")) {
            color = 0xFFFF6B74; // error red
        } else if (up.contains("WARN")) {
            color = 0xFFFFB020; // warning amber
        } else if (up.startsWith("[READY]") || up.contains("SUCCESS")
                || up.contains("DONE!") || up.contains("DONE ")
                || up.contains("FINISHED") || up.contains("STARTED SERVER")
                || up.contains("SUCCESSFULLY")) {
            color = 0xFFD2D6DE; // success — silver (no green in the graphite theme)
        } else if (up.startsWith("[INFO]") || up.contains(" INFO ")) {
            color = 0xFFFFFFFF; // silver
        } else if (up.contains("DEBUG")) {
            color = 0xFF6B7280; // dim
        }
        return tinted(line, color);
    }
}
