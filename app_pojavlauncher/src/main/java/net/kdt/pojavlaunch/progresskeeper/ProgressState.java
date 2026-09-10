package net.kdt.pojavlaunch.progresskeeper;

import net.kdt.pojavlaunch.R;

public class ProgressState {
    int progress;
    int resid;
    Object[] varArg;

    /** Last reported percentage (0..100). */
    public int getProgress() { return progress; }
    /** String resource of the current status line, or -1. */
    public int getResid() { return resid; }
    /** Formatting arguments for the status line (may include size/speed/ETA payloads). */
    public Object[] getVarArgs() { return varArg; }

    /**
     * True when this state carries a byte/MB payload that changes as bytes move.
     * The notification heartbeat uses it to know that a "same percentage" tick is
     * still worth repainting: 12.4 MB/s and 34.1/612.0 MB change far more often
     * than the integer percent does, and a stalled percent with dead numbers looks
     * exactly like a finished download.
     */
    public boolean hasLiveBytePayload() {
        if (varArg == null || varArg.length < 3) return false;
        if (!(varArg[0] instanceof Number) || !(varArg[1] instanceof Number)) return false;
        if (resid == R.string.newdl_downloading_game_files) return false; // file-count payload
        return true;
    }
}
