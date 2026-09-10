package net.kdt.pojavlaunch.multirt;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.BaseActivity;
import net.kdt.pojavlaunch.NewJREUtil;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.ProgressListener;

import com.kdt.mcgui.ProgressLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * First-launch Java installer — phase 11 rewrite.
 *
 * <p>One screen, two panes, two buttons. The left pane explains the page in
 * two lines and turns into the live progress panel while installing; the
 * right pane is a plain list of Java versions (tap = tick). Every row shows
 * its own thin progress bar while it downloads, so there is no second
 * "install deck" any more.</p>
 *
 * <p>The pipeline is unchanged and deliberately boring: up to two downloads
 * in parallel through {@link NewJREUtil#downloadRuntimeArchive}, then strictly
 * sequential installs through {@link NewJREUtil#installRuntimeArchive}; a
 * failed row is tapped to retry. Back is blocked while installing.</p>
 */
public class RuntimeSetupActivity extends BaseActivity {

    private static final String PREF_SHOWN = "runtimeWizardShown";

    public static boolean wasShown() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_SHOWN, false);
    }

    private static final int[] MAJORS = {8, 17, 21, 25};
    private static final int[] ROLE_RES = {
            R.string.rw_java8_role, R.string.rw_java17_role,
            R.string.rw_java21_role, R.string.rw_java25_role};
    private static final int[] SIZE_ESTIMATE_MB = {45, 55, 60, 65};

    // row states
    private static final int ST_IDLE = 0, ST_QUEUED = 1, ST_DOWNLOADING = 2,
            ST_VERIFIED = 3, ST_INSTALLING = 4, ST_DONE = 5, ST_FAILED = 6;

    private static final class Row {
        int major;
        NewJREUtil.ExternalRuntime runtime;
        boolean installed, selectable, selected;
        View root, check, liveRow, progTrack, progFill, well;
        TextView chip, stateText, meta, majorText;
        int state = ST_IDLE;
        int progress = 0;
        double curMb, totalMb, speedMbps, etaSec;
        File archive;
        String progressRecord;
        ValueAnimator barAnim;
    }

    private final List<Row> mRows = new ArrayList<>();
    private final Map<String, ProgressListener> mHooks = new ConcurrentHashMap<>();

    private ViewGroup mCardContainer;
    private View mInstallPanel, mFinishRow, mOfflineNote, mDock, mLeftPane;
    private TextView mSkipButton, mInstallButton, mDoneButton, mStartButton;
    private TextView mEyebrow, mTitle, mSummary, mOverallPercent, mOverallStep, mStatusLine,
            mStatSize, mStatSpeed, mStatEta;
    private RuntimeRingView mRing;
    private View mScroll;

    private boolean mInstalling = false;
    private int mDisplayedPct = 0;
    private ValueAnimator mPctAnim;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_runtime_setup);

        mCardContainer = findViewById(R.id.rs_card_container);
        mSkipButton = findViewById(R.id.rs_btn_skip);
        mInstallButton = findViewById(R.id.rs_btn_install);
        mEyebrow = findViewById(R.id.rs_eyebrow);
        mTitle = findViewById(R.id.rs_title);
        mSummary = findViewById(R.id.rs_summary);
        mOfflineNote = findViewById(R.id.rs_offline_note);
        mInstallPanel = findViewById(R.id.rs_install_container);
        mRing = findViewById(R.id.rs_ring);
        mOverallPercent = findViewById(R.id.rs_overall_percent);
        mOverallStep = findViewById(R.id.rs_overall_step);
        mStatusLine = findViewById(R.id.rs_status_line);
        mDoneButton = findViewById(R.id.rs_btn_done);
        mStartButton = findViewById(R.id.rs_btn_start);
        mFinishRow = findViewById(R.id.rs_finish_row);
        mStatSpeed = findViewById(R.id.rs_stat_speed);
        mStatSize = findViewById(R.id.rs_stat_size);
        mStatEta = findViewById(R.id.rs_stat_eta);
        mScroll = findViewById(R.id.rs_scroll);
        mDock = findViewById(R.id.rs_action_dock);
        mLeftPane = findViewById(R.id.rs_left_pane);

        buildRows();

        boolean anySelectable = false;
        for (Row r : mRows) if (r.selectable) anySelectable = true;
        LauncherPreferences.DEFAULT_PREF.edit().putBoolean(PREF_SHOWN, true).apply();
        if (!anySelectable) { finish(); return; }

        if (!Tools.isOnline(this) && mOfflineNote != null) mOfflineNote.setVisibility(View.VISIBLE);

        mSkipButton.setOnClickListener(v -> finish());
        mInstallButton.setOnClickListener(v -> onPrimaryClick());
        mDoneButton.setOnClickListener(v -> finish());
        mStartButton.setOnClickListener(v -> finish());
        UiMotion.pressFeedback(mSkipButton, mInstallButton, mDoneButton, mStartButton);
        updateCta();
        playEntrance();
    }

    // ───────────────────────────── rows ─────────────────────────────

    private void buildRows() {
        LayoutInflater inflater = LayoutInflater.from(this);
        List<NewJREUtil.ExternalRuntime> downloadable = MultiRTUtils.getRuntimesToDownload();
        mCardContainer.removeAllViews();
        mRows.clear();
        String arch = net.kdt.pojavlaunch.Architecture.archAsString(Tools.DEVICE_ARCHITECTURE);
        for (int i = 0; i < MAJORS.length; i++) {
            final Row r = new Row();
            r.major = MAJORS[i];
            r.progressRecord = ProgressLayout.UNPACK_RUNTIME + ":" + r.major;
            r.root = inflater.inflate(R.layout.item_runtime_choice, mCardContainer, false);
            r.chip = r.root.findViewById(R.id.runtime_chip);
            r.well = r.root.findViewById(R.id.runtime_major_well);
            r.majorText = r.root.findViewById(R.id.runtime_major_text);
            r.check = r.root.findViewById(R.id.runtime_check);
            r.liveRow = r.root.findViewById(R.id.runtime_live_row);
            r.progTrack = r.root.findViewById(R.id.runtime_prog_track);
            r.progFill = r.root.findViewById(R.id.runtime_prog_fill);
            r.stateText = r.root.findViewById(R.id.runtime_state_text);
            r.meta = r.root.findViewById(R.id.runtime_meta_text);

            r.majorText.setText(String.valueOf(r.major));
            ((TextView) r.root.findViewById(R.id.runtime_name_text)).setText("Java " + r.major);
            ((TextView) r.root.findViewById(R.id.runtime_role_text)).setText(ROLE_RES[i]);

            r.installed = MultiRTUtils.getExactJreName(r.major) != null;
            for (NewJREUtil.ExternalRuntime rt : downloadable) {
                if (rt.majorVersion == r.major) { r.runtime = rt; break; }
            }
            r.selectable = r.runtime != null && !r.installed;

            if (r.installed) {
                r.meta.setText(arch + "  ·  already on this phone");
                setChip(r, getString(R.string.rw_installed), R.drawable.rt_chip_light, 0xFF141519);
                r.root.setAlpha(0.62f);
                r.check.setSelected(true);
                r.check.setAlpha(0.6f);
            } else if (r.selectable) {
                r.meta.setText(arch + "  ·  ~" + SIZE_ESTIMATE_MB[i] + " MB");
                boolean recommended = r.major == 17 || r.major == 21;
                r.selected = recommended;
                if (recommended) setChip(r, getString(R.string.rw_recommended), R.drawable.rt_chip, 0xFFC9CED8);
                applySelection(r, false);
                r.root.setOnClickListener(v -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    if (mInstalling) {
                        if (r.state == ST_FAILED) retryRow(r);
                        return;
                    }
                    r.selected = !r.selected;
                    applySelection(r, true);
                    updateCta();
                });
                UiMotion.pressFeedback(r.root);
            } else {
                r.root.setVisibility(View.GONE);
            }
            mRows.add(r);
            mCardContainer.addView(r.root);
        }
    }

    private void setChip(Row r, CharSequence text, int bg, int color) {
        if (r.chip == null) return;
        if (text == null || text.length() == 0) { r.chip.setVisibility(View.GONE); return; }
        r.chip.setVisibility(View.VISIBLE);
        r.chip.setText(text);
        r.chip.setBackgroundResource(bg);
        r.chip.setTextColor(color);
    }

    private void applySelection(Row r, boolean animate) {
        r.root.setSelected(r.selected);
        r.check.setSelected(r.selected);
        if (!animate) {
            r.check.setScaleX(1f); r.check.setScaleY(1f);
            return;
        }
        r.check.animate().cancel();
        r.check.setScaleX(0.55f); r.check.setScaleY(0.55f);
        r.check.animate().scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new OvershootInterpolator(2.4f)).start();
        r.well.animate().cancel();
        r.well.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110)
                .withEndAction(() -> r.well.animate().scaleX(1f).scaleY(1f).setDuration(200).start())
                .start();
    }

    // ───────────────────────────── cta ─────────────────────────────

    private int selectedCount() {
        int n = 0;
        for (Row r : mRows) if (r.selectable && r.selected && !r.installed) n++;
        return n;
    }

    private int selectedMb() {
        int mb = 0;
        for (int i = 0; i < mRows.size(); i++) {
            Row r = mRows.get(i);
            if (r.selectable && r.selected && !r.installed) mb += SIZE_ESTIMATE_MB[i];
        }
        return mb;
    }

    private void updateCta() {
        if (mInstalling) return;
        int n = selectedCount();
        mInstallButton.setEnabled(n > 0);
        mInstallButton.setAlpha(n > 0 ? 1f : 0.55f);
        mInstallButton.setText(n > 0
                ? "Install " + n + (n == 1 ? " runtime" : " runtimes") + "  ·  ~" + selectedMb() + " MB"
                : "Pick a Java version");
    }

    private void onPrimaryClick() {
        if (mInstalling) return;
        boolean anyFailed = false;
        for (Row r : mRows) if (r.state == ST_FAILED) anyFailed = true;
        if (anyFailed) {
            for (Row r : mRows) if (r.state == ST_FAILED) retryRow(r);
            return;
        }
        if (selectedCount() == 0) {
            net.kdt.pojavlaunch.Anime.shake(mInstallButton);
            return;
        }
        beginInstall();
    }

    // ───────────────────────────── install ─────────────────────────────

    private void beginInstall() {
        final List<Row> queue = new ArrayList<>();
        for (Row r : mRows) if (r.selectable && r.selected && !r.installed) queue.add(r);
        if (queue.isEmpty()) { finish(); return; }
        mInstalling = true;
        float d = getResources().getDisplayMetrics().density;

        // rows not in the queue step back; queued rows show their bar
        for (Row r : mRows) {
            r.root.setOnClickListener(null);
            if (queue.contains(r)) {
                r.state = ST_QUEUED;
                r.root.setSelected(false);
                r.check.setVisibility(View.INVISIBLE);
                setChip(r, getString(R.string.rw_queued), R.drawable.rt_chip, 0xFFC9CED8);
                r.liveRow.setAlpha(0f);
                r.liveRow.setVisibility(View.VISIBLE);
                r.liveRow.animate().alpha(1f).setStartDelay(120).setDuration(260).start();
                r.stateText.setText("0%");
                setBar(r, 0, false);
            } else if (!r.installed) {
                r.root.animate().alpha(0.35f).setDuration(260).start();
            }
        }

        // left pane → progress panel
        mInstallButton.setEnabled(false);
        mInstallButton.setText("Installing…");
        mInstallButton.setAlpha(0.6f);
        mSkipButton.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> mSkipButton.setVisibility(View.INVISIBLE)).start();
        net.kdt.pojavlaunch.Anime.swapText(mEyebrow, "INSTALLING");
        net.kdt.pojavlaunch.Anime.swapText(mTitle, "Setting up Java");
        net.kdt.pojavlaunch.Anime.swapText(mSummary, "Keep the app open — this takes a minute or two on a normal connection.");
        mInstallPanel.setVisibility(View.VISIBLE);
        mInstallPanel.setAlpha(0f);
        mInstallPanel.setTranslationY(22f * d);
        mInstallPanel.animate().alpha(1f).translationY(0f).setDuration(420)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        mRing.setProgress(0f);
        mDisplayedPct = 0;
        mOverallPercent.setText("0%");
        mStatSize.setText("0 MB");
        mStatSpeed.setText("— MB/s");
        mStatEta.setText("—");
        mOverallStep.setText(R.string.rs_step_preparing);
        mStatusLine.setText(R.string.rs_stats_preparing_short);

        hookProgress(queue);
        PojavApplication.sExecutorService.execute(() -> runQueue(queue));
    }

    private void hookProgress(List<Row> queue) {
        for (final Row row : queue) {
            ProgressListener hook = new ProgressListener() {
                @Override public void onProgressStarted() {}
                @Override public void onProgressEnded() {}
                @Override public void onProgressUpdated(int progress, int resid, Object... va) {
                    runOnUiThreadSafe(() -> {
                        if (row.state != ST_DOWNLOADING && row.state != ST_QUEUED) return;
                        row.state = ST_DOWNLOADING;
                        row.progress = Math.max(0, Math.min(100, progress));
                        setBar(row, row.progress, true);
                        row.stateText.setText(row.progress + "%");
                        double cur = num(va, 1), tot = num(va, 2), speed = num(va, 3), remain = num(va, 4);
                        if (tot > 0) { row.curMb = cur; row.totalMb = tot; row.speedMbps = speed; row.etaSec = remain; }
                        refreshAggregate();
                    });
                }
            };
            mHooks.put(row.progressRecord, hook);
            ProgressKeeper.addListener(row.progressRecord, hook);
        }
    }

    private static double num(Object[] va, int idx) {
        if (va == null || va.length <= idx) return -1;
        Object o = va[idx];
        return o instanceof Number ? ((Number) o).doubleValue() : -1;
    }

    private void runQueue(final List<Row> queue) {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(Math.min(2, Math.max(1, queue.size())));
        final java.util.concurrent.CountDownLatch downloads = new java.util.concurrent.CountDownLatch(queue.size());
        for (final Row row : queue) {
            pool.execute(() -> {
                runOnUiThreadSafe(() -> {
                    row.state = ST_DOWNLOADING;
                    setChip(row, getString(R.string.rw_downloading), R.drawable.rt_chip_light, 0xFF141519);
                    mOverallStep.setText("Downloading Java " + row.major);
                    mStatusLine.setText(R.string.rs_stats_preparing);
                });
                try {
                    row.archive = NewJREUtil.downloadRuntimeArchive(getApplicationContext(), row.major, row.progressRecord);
                    row.progress = 100;
                    row.state = ST_VERIFIED;
                    runOnUiThreadSafe(() -> {
                        setBar(row, 100, true);
                        row.stateText.setText("100%");
                        setChip(row, "READY", R.drawable.rt_chip, 0xFFC9CED8);
                        refreshAggregate();
                    });
                } catch (RuntimeException e) {
                    row.state = ST_FAILED;
                    runOnUiThreadSafe(() -> failRow(row, "download"));
                } finally {
                    ProgressLayout.clearProgress(row.progressRecord);
                    downloads.countDown();
                }
            });
        }
        pool.shutdown();
        try { downloads.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        for (final Row row : queue) {
            if (row.state != ST_VERIFIED || row.archive == null) continue;
            runOnUiThreadSafe(() -> {
                row.state = ST_INSTALLING;
                setChip(row, "INSTALLING", R.drawable.rt_chip_light, 0xFF141519);
                mOverallStep.setText(getString(R.string.rs_step_installing, row.major));
                mStatusLine.setText(getString(R.string.rs_stats_installing, row.major));
                pulse(row.well);
            });
            try {
                NewJREUtil.installRuntimeArchive(row.archive, row.major);
                row.state = ST_DONE;
                long bytes = row.archive != null && row.archive.exists() ? row.archive.length() : 0L;
                RuntimeStats.recordInstall(row.major, bytes);
                runOnUiThreadSafe(() -> markDone(row));
            } catch (RuntimeException e) {
                row.state = ST_FAILED;
                runOnUiThreadSafe(() -> failRow(row, "install"));
            }
        }
        runOnUiThreadSafe(this::finishAll);
    }

    private void markDone(Row row) {
        row.installed = true;
        setChip(row, getString(R.string.rw_installed), R.drawable.rt_chip_light, 0xFF141519);
        row.stateText.setText("Done");
        row.check.setVisibility(View.VISIBLE);
        row.check.setSelected(true);
        row.check.setScaleX(0.4f); row.check.setScaleY(0.4f);
        row.check.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2.6f)).start();
        refreshAggregate();
    }

    private void failRow(Row row, String stage) {
        setChip(row, "FAILED · TAP TO RETRY", R.drawable.rt_chip_warn, 0xFFFFB4B8);
        row.stateText.setText("!");
        row.root.setOnClickListener(v -> retryRow(row));
        net.kdt.pojavlaunch.Anime.shake(row.root);
        mStatusLine.setText(getString(R.string.rs_failed_toast, row.major));
        refreshAggregate();
    }

    private void retryRow(final Row row) {
        if (row.state != ST_FAILED) return;
        row.root.setOnClickListener(null);
        row.state = ST_DOWNLOADING;
        row.progress = 0;
        row.archive = null;
        setBar(row, 0, false);
        row.stateText.setText("0%");
        setChip(row, getString(R.string.rw_downloading), R.drawable.rt_chip_light, 0xFF141519);
        mFinishRow.setVisibility(View.GONE);
        mInstalling = true;
        mInstallButton.setEnabled(false);
        mInstallButton.setText("Installing…");
        mInstallButton.setAlpha(0.6f);
        mOverallStep.setText("Downloading Java " + row.major);
        mStatusLine.setText(R.string.rs_stats_preparing);
        if (!mHooks.containsKey(row.progressRecord)) {
            List<Row> one = new ArrayList<>(); one.add(row); hookProgress(one);
        }
        PojavApplication.sExecutorService.execute(() -> {
            try {
                row.archive = NewJREUtil.downloadRuntimeArchive(getApplicationContext(), row.major, row.progressRecord);
                row.state = ST_INSTALLING;
                runOnUiThreadSafe(() -> {
                    setBar(row, 100, true);
                    setChip(row, "INSTALLING", R.drawable.rt_chip_light, 0xFF141519);
                    mOverallStep.setText(getString(R.string.rs_step_installing, row.major));
                });
                NewJREUtil.installRuntimeArchive(row.archive, row.major);
                row.state = ST_DONE;
                long bytes = row.archive != null && row.archive.exists() ? row.archive.length() : 0L;
                RuntimeStats.recordInstall(row.major, bytes);
                runOnUiThreadSafe(() -> markDone(row));
            } catch (RuntimeException e) {
                row.state = ST_FAILED;
                runOnUiThreadSafe(() -> failRow(row, "retry"));
            } finally {
                ProgressLayout.clearProgress(row.progressRecord);
            }
            runOnUiThreadSafe(this::finishAll);
        });
    }

    private void finishAll() {
        if (isFinishingOrDestroyedSoft()) return;
        boolean anyFailed = false, anyBusy = false;
        for (Row r : mRows) {
            if (r.state == ST_FAILED) anyFailed = true;
            if (r.state == ST_QUEUED || r.state == ST_DOWNLOADING || r.state == ST_VERIFIED || r.state == ST_INSTALLING) anyBusy = true;
        }
        if (anyBusy) return;
        mInstalling = false;
        refreshAggregate();
        if (anyFailed) {
            mOverallStep.setText(R.string.rs_step_partial);
            mStatusLine.setText(R.string.rs_stats_partial);
            mInstallButton.setEnabled(true);
            mInstallButton.setAlpha(1f);
            mInstallButton.setText("Retry failed");
            mDoneButton.setVisibility(View.VISIBLE);
            mStartButton.setVisibility(View.GONE);
        } else {
            mOverallStep.setText(R.string.rs_step_done);
            mStatusLine.setText(R.string.rs_stats_completed);
            mRing.setProgress(1f);
            animatePct(100);
            mInstallButton.setEnabled(true);
            mInstallButton.setAlpha(1f);
            mInstallButton.setText(R.string.rs_done_cta);
            mInstallButton.setOnClickListener(v -> finish());
            mDoneButton.setVisibility(View.GONE);
            mStartButton.setVisibility(View.VISIBLE);
            net.kdt.pojavlaunch.Anime.swapText(mEyebrow, "ALL SET");
            net.kdt.pojavlaunch.Anime.swapText(mTitle, "Java is ready");
            net.kdt.pojavlaunch.Anime.swapText(mSummary, "Every runtime you picked is installed. Minecraft will use the right one automatically.");
            pulse(mRing);
        }
        float d = getResources().getDisplayMetrics().density;
        mFinishRow.setVisibility(View.VISIBLE);
        mFinishRow.setAlpha(0f);
        mFinishRow.setTranslationY(10f * d);
        mFinishRow.animate().alpha(1f).translationY(0f).setDuration(320)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    // ───────────────────────────── telemetry ─────────────────────────────

    /** Ring + chips from the live rows (download 0..90 %, install fills the rest). */
    private void refreshAggregate() {
        int n = 0; double sum = 0, speed = 0, cur = 0, tot = 0, eta = 0;
        for (Row r : mRows) {
            if (r.state == ST_IDLE || (r.installed && r.state == ST_IDLE)) continue;
            if (r.state == ST_IDLE) continue;
            n++;
            double part;
            switch (r.state) {
                case ST_DONE: part = 1.0; break;
                case ST_INSTALLING: part = 0.94; break;
                case ST_VERIFIED: part = 0.9; break;
                case ST_FAILED: part = 0.0; break;
                default: part = r.progress / 100.0 * 0.9;
            }
            sum += part;
            speed += Math.max(0, r.speedMbps);
            cur += Math.max(0, r.curMb);
            tot += Math.max(0, r.totalMb);
            eta = Math.max(eta, r.etaSec);
        }
        if (n == 0) return;
        int pct = (int) Math.round(sum / n * 100.0);
        mRing.setProgress(pct / 100f);
        animatePct(pct);
        if (tot > 0) mStatSize.setText(String.format(Locale.US, "%.0f / %.0f MB", cur, tot));
        mStatSpeed.setText(speed > 0 ? String.format(Locale.US, "%.1f MB/s", speed) : "— MB/s");
        mStatEta.setText(eta > 0 ? "~" + formatRemaining(eta) : "—");
    }

    private static String formatRemaining(double seconds) {
        int s = (int) Math.round(seconds);
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    private void animatePct(int target) {
        if (target == mDisplayedPct) return;
        if (mPctAnim != null) mPctAnim.cancel();
        mPctAnim = ValueAnimator.ofInt(mDisplayedPct, target);
        mPctAnim.setDuration(360);
        mPctAnim.setInterpolator(new DecelerateInterpolator(1.4f));
        mPctAnim.addUpdateListener(a -> {
            mDisplayedPct = (int) a.getAnimatedValue();
            mOverallPercent.setText(mDisplayedPct + "%");
        });
        mPctAnim.start();
    }

    private void setBar(final Row r, int pct, boolean animate) {
        if (r.progTrack == null || r.progFill == null) return;
        final int track = r.progTrack.getWidth();
        if (track <= 0) {
            final int p = pct;
            r.progTrack.post(() -> setBar(r, p, false));
            return;
        }
        final int target = Math.max(0, Math.min(track, track * pct / 100));
        ViewGroup.LayoutParams lp = r.progFill.getLayoutParams();
        if (!animate) { lp.width = target; r.progFill.setLayoutParams(lp); return; }
        if (r.barAnim != null) r.barAnim.cancel();
        r.barAnim = ValueAnimator.ofInt(lp.width, target);
        r.barAnim.setDuration(300);
        r.barAnim.setInterpolator(new DecelerateInterpolator(1.3f));
        r.barAnim.addUpdateListener(a -> {
            ViewGroup.LayoutParams p = r.progFill.getLayoutParams();
            p.width = (int) a.getAnimatedValue();
            r.progFill.setLayoutParams(p);
        });
        r.barAnim.start();
    }

    private void pulse(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(140)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(2f)).start())
                .start();
    }

    // ───────────────────────────── entrance ─────────────────────────────

    private void playEntrance() {
        if (!net.kdt.pojavlaunch.utils.animation.MotionSpeed.isEnabled()) return;
        float d = getResources().getDisplayMetrics().density;
        View[] left = {mEyebrow, mTitle, mSummary, mOfflineNote};
        long delay = 60;
        for (View v : left) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.setAlpha(0f); v.setTranslationX(-18f * d);
            v.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(460)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            delay += 70;
        }
        delay = 160;
        for (Row r : mRows) {
            if (r.root.getVisibility() != View.VISIBLE) continue;
            final float restAlpha = r.root.getAlpha();
            r.root.setAlpha(0f); r.root.setTranslationY(22f * d); r.root.setScaleX(0.97f); r.root.setScaleY(0.97f);
            r.root.animate().alpha(restAlpha).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(delay).setDuration(480)
                    .setInterpolator(new OvershootInterpolator(1.1f)).start();
            delay += 75;
        }
        if (mDock != null) {
            mDock.setAlpha(0f); mDock.setTranslationY(14f * d);
            mDock.animate().alpha(1f).translationY(0f).setStartDelay(delay + 40).setDuration(380)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    private boolean isFinishingOrDestroyedSoft() {
        return isFinishing() || isDestroyed();
    }

    private void runOnUiThreadSafe(Runnable r) {
        runOnUiThread(() -> { if (!isFinishingOrDestroyedSoft()) r.run(); });
    }

    @Override
    public void onBackPressed() {
        if (mInstalling) {
            Toast.makeText(this, R.string.rs_back_locked, Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        for (Map.Entry<String, ProgressListener> e : mHooks.entrySet()) {
            try { ProgressKeeper.removeListener(e.getKey(), e.getValue()); } catch (Throwable ignored) {}
        }
        mHooks.clear();
        if (mPctAnim != null) mPctAnim.cancel();
        for (Row r : mRows) if (r.barAnim != null) r.barAnim.cancel();
        super.onDestroy();
    }
}
