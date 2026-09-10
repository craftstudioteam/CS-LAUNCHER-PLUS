package net.kdt.pojavlaunch.multirt;

import android.content.Context;
import android.content.SharedPreferences;

import net.kdt.pojavlaunch.PojavApplication;

/**
 * Real, on-device download statistics for the Runtime Forge.
 *
 * Nothing here is invented: every counter is bumped from an actual, verified
 * runtime install and persisted in the launcher's own preferences. The Forge
 * screen uses it to answer "which runtime do people on this device grab the
 * most", "what is popular", "what is already installed" and "what is still
 * available" — all from real data.
 */
public final class RuntimeStats {

    private static final String PREFS = "cs_runtime_stats";
    private static final String KEY_COUNT = "count_";
    private static final String KEY_BYTES = "bytes_";
    private static final String KEY_LAST = "last_";

    private RuntimeStats() {}

    private static SharedPreferences prefs() {
        Context ctx = PojavApplication.getInstance();
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called once per successfully installed runtime. */
    public static void recordInstall(int major, long bytes) {
        SharedPreferences p = prefs();
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        e.putInt(KEY_COUNT + major, p.getInt(KEY_COUNT + major, 0) + 1);
        e.putLong(KEY_BYTES + major, p.getLong(KEY_BYTES + major, 0L) + Math.max(0L, bytes));
        e.putLong(KEY_LAST + major, System.currentTimeMillis());
        e.apply();
    }

    public static int getInstallCount(int major) {
        SharedPreferences p = prefs();
        return p == null ? 0 : p.getInt(KEY_COUNT + major, 0);
    }

    public static long getBytes(int major) {
        SharedPreferences p = prefs();
        return p == null ? 0L : p.getLong(KEY_BYTES + major, 0L);
    }

    public static long getLastInstalled(int major) {
        SharedPreferences p = prefs();
        return p == null ? 0L : p.getLong(KEY_LAST + major, 0L);
    }

    /** "3 installs" / "1 install" / "never" */
    public static String describeCount(int major) {
        int n = getInstallCount(major);
        if (n <= 0) return "never";
        return n + (n == 1 ? " install" : " installs");
    }

    /** Human "3 days ago" style stamp, empty when never installed. */
    public static String describeLast(int major) {
        long t = getLastInstalled(major);
        if (t <= 0) return "";
        long diff = Math.max(0, System.currentTimeMillis() - t);
        long minutes = diff / 60000L;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        if (days < 30) return days + "d ago";
        long months = days / 30;
        return months + (months == 1 ? " month ago" : " months ago");
    }

    /**
     * The most-downloaded runtime among the given majors, ranked by real
     * install count and then by recency. Returns -1 when nothing was ever
     * installed on this device.
     */
    public static int getMostDownloadedMajor(int[] majors) {
        int best = -1;
        int bestCount = 0;
        long bestLast = 0;
        for (int major : majors) {
            int count = getInstallCount(major);
            long last = getLastInstalled(major);
            if (count <= 0) continue;
            if (count > bestCount || (count == bestCount && last > bestLast)) {
                best = major;
                bestCount = count;
                bestLast = last;
            }
        }
        return best;
    }

    /** Total successful installs across every runtime. */
    public static int getTotalInstalls(int[] majors) {
        int total = 0;
        for (int major : majors) total += getInstallCount(major);
        return total;
    }

    /** Most recently installed runtime, -1 when there is no history yet. */
    public static int getRecentlyUsedMajor(int[] majors) {
        int best = -1;
        long bestLast = 0;
        for (int major : majors) {
            long last = getLastInstalled(major);
            if (last > 0 && last > bestLast) {
                best = major;
                bestLast = last;
            }
        }
        return best;
    }
}
