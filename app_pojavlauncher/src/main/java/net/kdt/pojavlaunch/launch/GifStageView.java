package net.kdt.pojavlaunch.launch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.util.Log;
import android.view.TextureView;

import java.io.File;

/**
 * GifStageView — user-picked GIF renderer for the launch stage.
 *
 * Replaces the old bundled-MP4 player per user direction: the launch screen
 * stays the classic BLACK stage, and if (and only if) the user has imported a
 * GIF from Settings → Launcher Settings, that GIF plays in the same full-screen
 * slot, fit-center on black, looped, until the game's first presented frame —
 * the exact same release contract the video honored.
 *
 * Implementation notes:
 *  - android.graphics.Movie (already used by the in-game cursor renderer, so
 *    proven on this codebase and API-21-safe, unlike AnimatedImageDrawable
 *    which needs 28+).
 *  - Decoding happens off the main thread; on any decode failure the view
 *    quietly reports back and the classic black stage remains — a bad GIF can
 *    never wedge or crash the launch.
 *  - Single dedicated draw thread, TextureView.lockCanvas per frame, timed
 *    off the GIF's own duration. Thread is daemon-named and fully joined on
 *    stop — zero leaks, zero orphan painters.
 */
@SuppressWarnings("deprecation") // Movie is deprecated in 31 but is the only API-21-safe animated GIF path; project already relies on it (CustomCursorRenderer).
public class GifStageView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "GifStageView";

    public interface LoadCallback {
        /** Called on the main thread when the GIF is decoded and painting started. */
        void onReady();
        /** Called on the main thread when decode/paint failed (view must be dropped). */
        void onFailed();
    }

    private volatile Movie mMovie;
    private volatile android.graphics.Bitmap mFallbackBitmap;
    private volatile boolean mRunning;
    private volatile boolean mPaintedOnce;
    private Thread mDrawThread;
    private LoadCallback mCallback;
    private long mStartMs;
    private int mGifDuration;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public GifStageView(Context ctx) {
        super(ctx);
        setOpaque(false);             // let it sit over the black stage without punching odd holes
        setAlpha(0f);                 // revealed on first painted frame (no pop)
        setSurfaceTextureListener(this);
    }

    /** Decode + start painting. Safe to call once per view instance. */
    public void load(File gifFile, LoadCallback cb) {
        mCallback = cb;
        net.kdt.pojavlaunch.PojavApplication.sExecutorService.execute(() -> {
            Movie decoded = null;
            android.graphics.Bitmap fallback = null;
            try {
                decoded = Movie.decodeFile(gifFile.getAbsolutePath());
            } catch (Throwable t) {
                Log.w(TAG, "gif decode failed", t);
            }
            if (decoded == null || decoded.width() <= 0 || decoded.height() <= 0) {
                try {
                    fallback = android.graphics.BitmapFactory.decodeFile(gifFile.getAbsolutePath());
                } catch (Throwable t) {
                    Log.w(TAG, "bitmap fallback failed", t);
                }
            }
            final Movie finalDecoded = (decoded != null && decoded.width() > 0 && decoded.height() > 0) ? decoded : null;
            final android.graphics.Bitmap finalFallback = fallback;
            post(() -> {
                if (finalDecoded == null && finalFallback == null) {
                    Log.w(TAG, "gif: empty/invalid movie and bitmap — staying on black stage");
                    if (mCallback != null) mCallback.onFailed();
                    return;
                }
                mMovie = finalDecoded;
                mFallbackBitmap = finalFallback;
                if (finalDecoded != null) {
                    mGifDuration = Math.max(finalDecoded.duration(), 1);
                } else {
                    mGifDuration = 1000;
                }
                Log.i(TAG, "gif: decoded successfully — starting loop");
                maybeStartLoop();
            });
        });
    }

    /** Stop painting + release thread. Idempotent. */
    public synchronized void shutdown() {
        mRunning = false;
        Thread t = mDrawThread;
        mDrawThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(400); } catch (InterruptedException ignored) {}
        }
        mMovie = null;
        mFallbackBitmap = null;
        mCallback = null;
    }

    // ───────────────────────── surface lifecycle ─────────────────────────

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { maybeStartLoop(); }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        shutdown();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    // ───────────────────────── draw loop ─────────────────────────

    private synchronized void maybeStartLoop() {
        if (mRunning || mMovie == null || !isAvailable()) return;
        mRunning = true;
        mStartMs = SystemClock.uptimeMillis();
        mDrawThread = new Thread(this::drawLoop, "CS-GifStage");
        mDrawThread.setDaemon(true);
        mDrawThread.start();
    }

    private void drawLoop() {
        Log.i(TAG, "gif: paint loop started");
        BitmaplessFrame: while (mRunning) {
            Movie movie = mMovie;
            android.graphics.Bitmap bmp = mFallbackBitmap;
            if (movie == null && bmp == null) break BitmaplessFrame;
            Canvas c = null;
            try {
                if (!isAvailable()) break;
                c = lockCanvas();
                if (c == null) break;
                c.drawColor(0xFF000000); // black canvas
                int vw = getWidth(); int vh = getHeight();
                int dw = movie != null ? movie.width() : bmp.getWidth();
                int dh = movie != null ? movie.height() : bmp.getHeight();
                float scale = Math.min((float) vw / dw, (float) vh / dh);
                float dx = (vw - dw * scale) * 0.5f;
                float dy = (vh - dh * scale) * 0.5f;
                c.scale(scale, scale);
                c.translate(dx / scale, dy / scale);
                if (movie != null) {
                    movie.setTime((int) ((SystemClock.uptimeMillis() - mStartMs) % mGifDuration));
                    movie.draw(c, 0, 0, mPaint);
                } else if (bmp != null) {
                    c.drawBitmap(bmp, 0, 0, mPaint);
                }
            } catch (Throwable t) {
                Log.w(TAG, "gif paint error", t);
            } finally {
                if (c != null) try { unlockCanvasAndPost(c); } catch (Throwable ignored) {}
            }
            if (!mPaintedOnce) {
                mPaintedOnce = true;
                final LoadCallback cb = mCallback;
                post(() -> {
                    animate().alpha(1f).setDuration(260).start();
                    if (cb != null) cb.onReady();
                });
            }
            SystemClock.sleep(33);
        }
        Log.i(TAG, "gif: paint loop ended");
    }
}
