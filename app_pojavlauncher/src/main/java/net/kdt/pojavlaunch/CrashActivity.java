package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import net.kdt.pojavlaunch.utils.CrashAnalyzer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Landscape crash mission-control: local diagnosis, fixes, raw log and mclo.gs sharing. */
public class CrashActivity extends AppCompatActivity {
    public static final String EXTRA_LOG_PATH = "crash_log_path";
    public static final String EXTRA_EXIT_CODE = "crash_exit_code";
    private static final String MCLOGS_ENDPOINT = "https://api.mclo.gs/1/log";

    private TextView mCategory, mTitle, mSummary, mConfidence, mCulprit, mSolutions;
    private TextView mLogView, mResultUrl;
    private ProgressBar mLoading;
    private View mCulpritCard, mUploadResultCard;
    private Button mUploadBtn;
    private String mFullLog = "";
    private int mExitCode = -1;
    private CrashAnalyzer.Result mAnalysis;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);
        mCategory = findViewById(R.id.crash_category);
        mTitle = findViewById(R.id.crash_reason_title);
        mSummary = findViewById(R.id.crash_summary);
        mConfidence = findViewById(R.id.crash_confidence);
        mCulprit = findViewById(R.id.crash_culprit);
        mSolutions = findViewById(R.id.crash_solutions);
        mLogView = findViewById(R.id.crash_log_view);
        mResultUrl = findViewById(R.id.upload_result_url);
        mLoading = findViewById(R.id.crash_loading);
        mCulpritCard = findViewById(R.id.crash_culprit_card);
        mUploadResultCard = findViewById(R.id.upload_result_card);
        mUploadBtn = findViewById(R.id.btn_upload_log);
        mExitCode = getIntent().getIntExtra(EXTRA_EXIT_CODE, -1);

        loadLog(getIntent().getStringExtra(EXTRA_LOG_PATH));
        findViewById(R.id.crash_back_button).setOnClickListener(v -> goHome());
        findViewById(R.id.btn_home).setOnClickListener(v -> goHome());
        mUploadBtn.setOnClickListener(v -> analyseUploadAndShare());
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> copyLog());
        findViewById(R.id.btn_copy_url).setOnClickListener(v ->
                copyText(mResultUrl.getText().toString(), "Link copied"));
        findViewById(R.id.btn_share_url).setOnClickListener(v -> shareCrashLink());
        playEntrance();
    }

    private void playEntrance() {
        View panel = findViewById(R.id.crash_analysis_panel);
        if (panel != null) {
            panel.setAlpha(0f); panel.setTranslationX(-18f * getResources().getDisplayMetrics().density);
            panel.animate().alpha(1f).translationX(0f).setDuration(260).withLayer().start();
        }
        View log = findViewById(R.id.crash_log_panel);
        if (log != null) {
            log.setAlpha(0f); log.setTranslationX(18f * getResources().getDisplayMetrics().density);
            log.animate().alpha(1f).translationX(0f).setStartDelay(45).setDuration(280).withLayer().start();
        }
    }

    private void loadLog(String logPath) {
        File logFile = logPath != null ? new File(logPath)
                : new File(Tools.DIR_GAME_HOME, Tools.LAST_CRASH_LOG_NAME);
        if (!logFile.exists()) logFile = new File(Tools.DIR_GAME_HOME, Tools.LATEST_LOG_NAME);
        if (logFile.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(logFile), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder(); String line; int lines = 0;
                while ((line = br.readLine()) != null && lines++ < 25000 && sb.length() < 9_500_000) {
                    sb.append(line).append('\n');
                }
                mFullLog = sb.toString();
                mLogView.setText(mFullLog);
            } catch (Exception e) {
                mLogView.setText("Unable to read the crash log.");
            }
        } else {
            mLogView.setText("No crash log was found.");
        }
        mAnalysis = CrashAnalyzer.analyse(mFullLog, mExitCode);
        renderAnalysis();
    }

    private void renderAnalysis() {
        mCategory.setText(mAnalysis.category.toUpperCase(java.util.Locale.US)
                + (mExitCode == -1 ? "" : "  •  EXIT " + mExitCode));
        mTitle.setText(mAnalysis.title);
        mSummary.setText(mAnalysis.explanation);
        mConfidence.setText(mAnalysis.confidence + "% MATCH");
        if (TextUtils.isEmpty(mAnalysis.culprit)) {
            mCulpritCard.setVisibility(View.GONE);
        } else {
            mCulpritCard.setVisibility(View.VISIBLE);
            mCulprit.setText(mAnalysis.culprit);
        }
        StringBuilder fixes = new StringBuilder();
        for (int i = 0; i < mAnalysis.suggestions.size(); i++) {
            fixes.append(i + 1).append(". ").append(mAnalysis.suggestions.get(i));
            if (i + 1 < mAnalysis.suggestions.size()) fixes.append("\n\n");
        }
        mSolutions.setText(fixes.length() == 0 ? "Open Full Log for technical details." : fixes.toString());
    }

    private void analyseUploadAndShare() {
        if (TextUtils.isEmpty(mFullLog)) {
            Toast.makeText(this, "No log to upload", Toast.LENGTH_SHORT).show(); return;
        }
        mUploadBtn.setEnabled(false);
        mUploadBtn.setText("ANALYSING…");
        mLoading.setVisibility(View.VISIBLE);
        mUploadResultCard.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                UploadResult result = uploadToMclogs(mFullLog, mExitCode);
                if (result != null && result.insight != null && !result.insight.isEmpty()) {
                    mAnalysis.suggestions.add(0, "mclo.gs insight: " + result.insight);
                }
                new Handler(Looper.getMainLooper()).post(() -> {
                    resetUploadUi();
                    if (result != null && result.url != null) {
                        renderAnalysis();
                        mResultUrl.setText(result.url);
                        mUploadResultCard.setVisibility(View.VISIBLE);
                        shareText(buildShareText(result.url), "Share crash analysis");
                    } else Toast.makeText(this, "Upload failed. Check your connection.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    resetUploadUi();
                    Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "CrashAnalysisUpload").start();
    }

    private void resetUploadUi() {
        mLoading.setVisibility(View.GONE);
        mUploadBtn.setEnabled(true);
        mUploadBtn.setText("ANALYSE & SHARE");
    }

    private String buildShareText(String url) {
        return "CS LAUNCHER PLUS crash analysis\nReason: " + mAnalysis.title
                + "\nCategory: " + mAnalysis.category
                + (mAnalysis.culprit == null ? "" : "\nPossible culprit: " + mAnalysis.culprit)
                + "\nFull filtered log: " + url;
    }

    private void shareCrashLink() {
        String url = mResultUrl.getText().toString();
        if (!url.isEmpty()) shareText(buildShareText(url), "Share crash analysis");
    }

    private static final class UploadResult {
        String id, url, insight;
    }

    private static UploadResult uploadToMclogs(String content, int exitCode) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(MCLOGS_ENDPOINT).openConnection();
        conn.setRequestMethod("POST"); conn.setConnectTimeout(15000); conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "CSLauncher/" + BuildConfig.VERSION_NAME);
        JSONObject payload = new JSONObject();
        payload.put("content", content); payload.put("source", "CS LAUNCHER PLUS");
        JSONArray metadata = new JSONArray();
        metadata.put(meta("launcher_version", BuildConfig.VERSION_NAME, "Launcher Version"));
        metadata.put(meta("android_version", android.os.Build.VERSION.RELEASE, "Android"));
        metadata.put(meta("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, "Device"));
        metadata.put(meta("exit_code", exitCode, "Exit Code"));
        payload.put("metadata", metadata);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) { out.write(body); }
        JSONObject response = readJsonResponse(conn);
        if (!response.optBoolean("success", false)) return null;
        UploadResult result = new UploadResult();
        result.id = response.optString("id", null); result.url = response.optString("url", null);
        if (result.id != null) result.insight = fetchInsight(result.id);
        return result;
    }

    private static JSONObject meta(String key, Object value, String label) throws Exception {
        JSONObject o = new JSONObject(); o.put("key", key); o.put("value", value);
        o.put("label", label); o.put("visible", true); return o;
    }

    private static String fetchInsight(String id) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    "https://api.mclo.gs/1/log/" + id + "?insights=true").openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/json");
            JSONObject root = readJsonResponse(c);
            JSONObject content = root.optJSONObject("content");
            JSONObject insights = content == null ? null : content.optJSONObject("insights");
            JSONArray problems = insights == null ? null : insights.optJSONArray("problems");
            if (problems == null || problems.length() == 0) return null;
            Object first = problems.opt(0);
            if (first instanceof String) return (String) first;
            if (first instanceof JSONObject) {
                JSONObject p = (JSONObject) first;
                String[] fields = {"message", "title", "name", "description"};
                for (String field : fields) {
                    String value = p.optString(field, ""); if (!value.isEmpty()) return value;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static JSONObject readJsonResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        if (response.length() == 0) return new JSONObject();
        return new JSONObject(response.toString());
    }

    private void copyLog() {
        if (TextUtils.isEmpty(mFullLog)) { Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show(); return; }
        copyText(mFullLog, "Log copied to clipboard");
    }
    private void copyText(String text, String message) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("crashlog", text)); Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    }
    private void shareText(String text, String title) {
        Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(share, title));
    }
    private void goHome() {
        Intent home = new Intent(this, LauncherActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home); finish();
    }
    @Override public void onBackPressed() { goHome(); }
}
