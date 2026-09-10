package net.kdt.pojavlaunch.performance;

import android.content.Context;

import net.kdt.pojavlaunch.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small append-only record of how long this device actually spent per frame, keyed by
 * renderer + profile + game. It exists for two reasons: the renderer recommendation needs measured
 * evidence, and the benchmark report needs numbers that came from the launcher itself rather than from
 * a guess.
 *
 * <p>Written once per session at teardown, read once when the settings card is built. Never on the
 * launch path, never per frame.
 *
 * <p>What "fps" means here is honest: the sample is derived from the present counter
 * ({@link net.kdt.pojavlaunch.utils.FpsCounter}, the swap-boundary present counter) divided by wall time over
 * the sampled window, and "worst second" is the lowest 1-second window, not a statistical 1% low. True
 * percentiles and jank come from {@code dumpsys gfxinfo}, which the {@code perf_probe.sh} harness collects.
 */
public final class SessionStats {

    private static final String DIR = "performance";
    private static final String FILE = "sessions.json";
    private static final int CAP = 64;
    private static final long MIN_SESSION_MS = 30_000L; // anything shorter is noise

    private SessionStats() {}

    /** One finished session, already reduced to numbers. */
    public static final class Entry {
        public final long timestamp;
        public final String rendererId;
        public final String modeKey;
        public final String versionId;
        public final int medianFps;
        public final int worstSecondFps;
        public final float medianFrameMs;
        public final long durationMs;
        public final int thermalPeak;

        Entry(long timestamp, String rendererId, String modeKey, String versionId, int medianFps,
              int worstSecondFps, float medianFrameMs, long durationMs, int thermalPeak) {
            this.timestamp = timestamp;
            this.rendererId = rendererId == null ? "" : rendererId;
            this.modeKey = modeKey == null ? "" : modeKey;
            this.versionId = versionId == null ? "" : versionId;
            this.medianFps = medianFps;
            this.worstSecondFps = worstSecondFps;
            this.medianFrameMs = medianFrameMs;
            this.durationMs = durationMs;
            this.thermalPeak = thermalPeak;
        }

        String toJson() {
            return "{\"t\":" + timestamp + ",\"r\":" + quote(rendererId) + ",\"m\":" + quote(modeKey)
                    + ",\"v\":" + quote(versionId) + ",\"fps\":" + medianFps + ",\"worst\":" + worstSecondFps
                    + ",\"ms\":" + medianFrameMs + ",\"dur\":" + durationMs + ",\"th\":" + thermalPeak + "}";
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    public static File file(Context ctx) {
        return new File(new File(Tools.DIR_DATA, DIR), FILE);
    }

    public static void append(Context ctx, Entry e) {
        if (ctx == null || e == null || e.durationMs < MIN_SESSION_MS) return;
        try {
            File out = file(ctx);
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            List<String> lines = new ArrayList<>();
            if (out.isFile()) {
                String raw = Tools.read(out);
                for (String line : raw.split("\n")) {
                    if (!line.trim().isEmpty()) lines.add(line.trim());
                }
            }
            lines.add(e.toJson());
            // newest kept; a rolling window is all the recommendation needs
            int from = Math.max(0, lines.size() - CAP);
            StringBuilder b = new StringBuilder("[\n");
            for (int i = from; i < lines.size(); i++) {
                if (i > from) b.append(",\n");
                b.append(lines.get(i));
            }
            b.append("\n]");
            Tools.write(out.getAbsolutePath(), b.toString());
        } catch (Throwable ignored) {
            // stats are diagnostics; they must never break a shutdown path
        }
    }

    /** Median of the recorded medians per renderer — enough to compare two options, not to rank brands. */
    public static Map<String, Float> bestMedianFpsByRenderer(Context ctx) {
        Map<String, List<Integer>> buckets = new LinkedHashMap<>();
        if (ctx == null) return new HashMap<>();
        try {
            File f = file(ctx);
            if (!f.isFile()) return new HashMap<>();
            JSONArray arr = new JSONArray(Tools.read(f));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String r = o.optString("r", "");
                if (r.isEmpty()) continue;
                List<Integer> list = buckets.get(r);
                if (list == null) buckets.put(r, list = new ArrayList<>());
                list.add(o.optInt("fps", 0));
            }
        } catch (Throwable ignored) {
            return new HashMap<>();
        }
        Map<String, Float> out = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
            List<Integer> vals = e.getValue();
            if (vals.isEmpty()) continue;
            Integer[] copy = vals.toArray(new Integer[0]);
            java.util.Arrays.sort(copy);
            out.put(e.getKey(), (float) (int) copy[copy.length / 2]);
        }
        return out;
    }

    /** Recent sessions for the given renderer/mode pair, newest first — used by the settings card. */
    public static String describeRecent(Context ctx, String rendererId, String modeKey) {
        if (ctx == null) return "";
        try {
            File f = file(ctx);
            if (!f.isFile()) return "";
            JSONArray arr = new JSONArray(Tools.read(f));
            List<String> hits = new ArrayList<>();
            for (int i = arr.length() - 1; i >= 0 && hits.size() < 3; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (rendererId != null && !rendererId.equals(o.optString("r", ""))) continue;
                if (modeKey != null && !modeKey.equals(o.optString("m", ""))) continue;
                hits.add(String.format(Locale.ROOT, "%d fps (worst second %d, %.1f ms/frame)",
                        o.optInt("fps"), o.optInt("worst"), (float) o.optDouble("ms", 0.0)));
            }
            if (hits.isEmpty()) return "";
            StringBuilder b = new StringBuilder("Measured on this phone");
            for (String h : hits) b.append("\n").append(h);
            return b.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
