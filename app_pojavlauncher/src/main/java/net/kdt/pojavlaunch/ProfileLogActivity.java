package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

/** Full-screen, zoomable console for a profile's single latest launch log. */
public class ProfileLogActivity extends AppCompatActivity {
    public static final String EXTRA_LOG_PATH = "profile_log_path";
    public static final String EXTRA_PROFILE_NAME = "profile_name";
    public static final String EXTRA_VERSION = "profile_version";
    private static final String MCLOGS_ENDPOINT = "https://api.mclo.gs/1/log";
    private static final int MAX_LINES = 25_000;
    private static final int MAX_CHARS = 10 * 1024 * 1024;

    private TextView mLogText, mMeta, mZoomValue;
    private ProgressBar mLoading;
    private TextView mUpload;
    private File mLogFile;
    private String mContent = "", mUploadedUrl;
    private float mTextSizeSp = 11f;
    private ScaleGestureDetector mScaleDetector;
    private String mProfileName, mVersion;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_profile_log);
        Tools.setFullscreen(this, true);
        mLogText = findViewById(R.id.profile_log_text);
        mMeta = findViewById(R.id.profile_log_meta);
        mZoomValue = findViewById(R.id.profile_log_zoom_value);
        mLoading = findViewById(R.id.profile_log_loading);
        mUpload = findViewById(R.id.profile_log_upload);
        mProfileName = getIntent().getStringExtra(EXTRA_PROFILE_NAME);
        mVersion = getIntent().getStringExtra(EXTRA_VERSION);
        String path = getIntent().getStringExtra(EXTRA_LOG_PATH);
        mLogFile = TextUtils.isEmpty(path) ? null : new File(path);

        ((TextView) findViewById(R.id.profile_log_title)).setText(
                "LATEST LOG • " + (TextUtils.isEmpty(mProfileName) ? "PROFILE" : mProfileName.toUpperCase()));
        findViewById(R.id.profile_log_back).setOnClickListener(v -> finish());
        findViewById(R.id.profile_log_copy).setOnClickListener(v -> copyLog());
        findViewById(R.id.profile_log_share).setOnClickListener(v -> shareFile());
        mUpload.setOnClickListener(v -> {
            if (mUploadedUrl != null) shareText(mUploadedUrl, "Share mclo.gs link");
            else uploadLog();
        });
        findViewById(R.id.profile_log_zoom_out).setOnClickListener(v -> applyZoom(mTextSizeSp - 1f));
        findViewById(R.id.profile_log_zoom_in).setOnClickListener(v -> applyZoom(mTextSizeSp + 1f));
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                applyZoom(mTextSizeSp * detector.getScaleFactor());
                return true;
            }
        });
        loadLog();
        animateEntry();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (mScaleDetector != null) mScaleDetector.onTouchEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private void animateEntry() {
        View toolbar = findViewById(R.id.profile_log_toolbar);
        View console = findViewById(R.id.profile_log_console);
        toolbar.setAlpha(0f); toolbar.setTranslationY(-18f);
        console.setAlpha(0f); console.setTranslationY(18f);
        toolbar.animate().alpha(1f).translationY(0f).setDuration(220).start();
        console.animate().alpha(1f).translationY(0f).setStartDelay(45).setDuration(260).start();
    }

    private void loadLog() {
        PojavApplication.sExecutorService.execute(() -> {
            String content;
            int lines = 0;
            boolean truncated = false;
            StringBuilder out = new StringBuilder();
            if (mLogFile == null || !mLogFile.isFile()) {
                content = "No launch log is available for this profile yet.";
            } else {
                try (BufferedReader reader = new BufferedReader(new FileReader(mLogFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (lines >= MAX_LINES || out.length() + line.length() + 1 > MAX_CHARS) {
                            truncated = true; break;
                        }
                        out.append(line).append('\n'); lines++;
                    }
                    if (truncated) out.append("\n[CS LAUNCHER PLUS] Display truncated at the mclo.gs safety limit.\n");
                    content = out.toString();
                } catch (Exception e) {
                    content = "Unable to read this profile log:\n" + e.getMessage();
                }
            }
            final String loaded = content;
            final int lineCount = lines;
            runOnUiThread(() -> {
                mContent = loaded;
                mLogText.setText(styleLog(loaded));
                mLoading.setVisibility(View.GONE);
                if (mLogFile != null && mLogFile.isFile()) {
                    String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(mLogFile.lastModified()));
                    mMeta.setText(date + "  •  " + formatBytes(mLogFile.length()) + "  •  "
                            + lineCount + " lines" + (TextUtils.isEmpty(mVersion) ? "" : "  •  " + mVersion));
                } else mMeta.setText("No saved session");
            });
        });
    }

    private CharSequence styleLog(String text) {
        android.text.SpannableString styled = new android.text.SpannableString(text);
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String line = text.substring(start, end).toLowerCase(java.util.Locale.ROOT);
            int color = 0xFFF2F2F2;
            if (line.contains("error") || line.contains("exception") || line.contains("fatal")) color = 0xFFFF737D;
            else if (line.contains("warn")) color = 0xFFFFC857;
            else if (line.contains("debug") || line.contains("trace")) color = 0xFF8A8C92;
            styled.setSpan(new android.text.style.ForegroundColorSpan(color), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end + 1;
        }
        return styled;
    }

    private void applyZoom(float value) {
        mTextSizeSp = Math.max(8f, Math.min(24f, value));
        mLogText.setTextSize(mTextSizeSp);
        mZoomValue.setText(Math.round(mTextSizeSp) + "sp");
    }

    private void copyLog() {
        if (TextUtils.isEmpty(mContent)) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("profile log", mContent));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void shareFile() {
        if (mLogFile == null || !mLogFile.isFile()) {
            Toast.makeText(this, "No log to share", Toast.LENGTH_SHORT).show(); return;
        }
        Tools.openPath(this, mLogFile, true);
    }

    private void uploadLog() {
        if (TextUtils.isEmpty(mContent) || mLogFile == null || !mLogFile.isFile()) {
            Toast.makeText(this, "No log to upload", Toast.LENGTH_SHORT).show(); return;
        }
        mUpload.setEnabled(false); mUpload.setText("UPLOADING…");
        PojavApplication.sExecutorService.execute(() -> {
            try {
                String url = uploadToMclogs(mContent);
                runOnUiThread(() -> {
                    mUpload.setEnabled(true);
                    if (url == null) {
                        mUpload.setText("MCLO.GS");
                        Toast.makeText(this, "Upload failed", Toast.LENGTH_LONG).show();
                    } else {
                        mUploadedUrl = url;
                        mUpload.setText("SHARE LINK");
                        copyText(url);
                        Toast.makeText(this, "mclo.gs link copied", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    mUpload.setEnabled(true); mUpload.setText("MCLO.GS");
                    Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String uploadToMclogs(String content) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(MCLOGS_ENDPOINT).openConnection();
        conn.setRequestMethod("POST"); conn.setConnectTimeout(15000); conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "CSLauncher/" + BuildConfig.VERSION_NAME);
        JSONObject body = new JSONObject();
        body.put("content", content); body.put("source", "CS LAUNCHER PLUS");
        JSONArray metadata = new JSONArray();
        metadata.put(meta("profile", mProfileName, "Profile"));
        metadata.put(meta("minecraft_version", mVersion, "Minecraft Version"));
        metadata.put(meta("launcher_version", BuildConfig.VERSION_NAME, "Launcher Version"));
        body.put("metadata", metadata);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = conn.getOutputStream()) { output.write(bytes); }
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close(); conn.disconnect();
        JSONObject json = new JSONObject(response.toString());
        return json.optBoolean("success", false) ? json.optString("url", null) : null;
    }

    private static JSONObject meta(String key, @Nullable Object value, String label) throws Exception {
        JSONObject item = new JSONObject(); item.put("key", key);
        item.put("value", value == null ? "" : value); item.put("label", label); item.put("visible", true);
        return item;
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("mclo.gs", text));
    }

    private void shareText(String text, String title) {
        Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(share, title));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }
}