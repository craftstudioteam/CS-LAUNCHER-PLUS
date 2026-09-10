package net.kdt.pojavlaunch.skins;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * A flat, correctly-unwrapped 2D preview of a Minecraft skin: the front of the
 * character, and — when the slot is wide enough — its back next to it.
 *
 * Why not a 3D head: this view sits in a list, one instance per slot, and a GL
 * context per row is exactly the kind of thing that makes a skin page stutter.
 * A Canvas with a handful of drawBitmap calls is not. The rectangles come
 * straight from the layout the game itself reads (head row 0, torso 16-32,
 * limbs 16-32, overlays 32-48 / 48-64 on a 64x64 sheet), so what you see here
 * is what Minecraft shows in game — with the fake perspective taken out.
 */
public class SkinSlotPreviewView extends View {
    /** Two views side by side when there is room, one when there is not. */
    public static final int MODE_AUTO = 0;
    /** Front only, for the narrow slot cards inside Skin Studio. */
    public static final int MODE_FRONT_ONLY = 1;
    /** The whole 64x64 sheet, unwrapped and nearest-neighbour scaled. */
    public static final int MODE_RAW_SHEET = 2;

    /** Figure footprint in texture pixels: 4-wide arms + 8-wide torso + 4-wide arms. */
    private static final float FIG_W = 10f, FIG_H = 28f;

    private final Paint paint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    @Nullable private Bitmap skin;
    private boolean slim;
    private int mode = MODE_AUTO;

    /**
     * Decoding runs off the UI thread: one 64x64 PNG is nothing, but this view is
     * created three times per screen and re-bound on every resume, and a
     * BitmapFactory call inside onMeasure used to be the reason a skin page
     * hitched. pendingFile holds what we want on screen; skin holds what we have.
     */
    private File pendingFile;
    private long pendingStamp;
    @Nullable private String notice;
    private boolean loading;
    @Nullable private Handler decodeHandler;
    private final Runnable decodeRunnable = new Runnable() {
        @Override public void run() {
            File f = pendingFile;
            Bitmap decoded = null;
            String error = null;
            if (f != null && f.isFile()) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inScaled = false;
                    decoded = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
                    if (decoded == null) error = "UNREADABLE SKIN FILE";
                    else if (decoded.getWidth() < 64 || decoded.getHeight() < 32) {
                        error = "NOT A 64x32 OR 64x64 SKIN";
                    }
                } catch (Throwable t) {
                    decoded = null;
                    error = "COULD NOT READ THE SKIN";
                }
            }
            pendingStamp = f == null ? 0L : f.lastModified();
            final Bitmap toShow = decoded;
            final String toSay = error;
            // Qualified with the outer class on purpose: inside this anonymous
            // Runnable, "post(...)" would otherwise resolve to nothing (Runnable
            // has no post) and isDetached() is a View method — javac reports
            // both as "cannot find symbol" without the outer this.
            SkinSlotPreviewView.this.post(new Runnable() {
                @Override public void run() {
                    // View has no isDetached() (that is RecyclerView's ViewHolder);
                    // attachment is the real question and this is the API that asks
                    // it — minSdk is 21, so this is safe from API 19 up.
                    if (!SkinSlotPreviewView.this.isAttachedToWindow()) {
                        if (toShow != null) toShow.recycle();
                        return;
                    }
                    loading = false;
                    notice = toSay;
                    swapSkin(toShow);
                }
            });
        }
    };

    public SkinSlotPreviewView(Context context) { super(context); init(); }
    public SkinSlotPreviewView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        // Nearest neighbour, because a skin is pixel art and any filter smears it.
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        shade.setColor(0x33000000);
        float density = getResources().getDisplayMetrics().density;
        labelPaint.setColor(0x77FFFFFF);
        labelPaint.setTextSize(7.5f * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        labelPaint.setLetterSpacing(0.18f);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setSkin(@Nullable File file) { setSkin(file, false); }

    /** @param slimModel the slot's model, so a slim sheet is laid out slim. */
    public void setSkin(@Nullable File file, boolean slimModel) {
        slim = slimModel;
        notice = null;
        if (file != null && (!file.isFile() || file.length() <= 0)) {
            // Slot points at a file that is gone: say so instead of silently
            // drawing the empty mannequin, which reads as "no skin saved".
            pendingFile = null;
            stopDecoding();
            swapSkin(null);
            notice = "SAVED FILE IS MISSING";
            invalidate();
            return;
        }
        boolean same = (file == null && pendingFile == null)
                || (file != null && pendingFile != null
                    && file.equals(pendingFile) && file.lastModified() == pendingStamp);
        pendingFile = file;
        if (same && (skin != null || loading)) return;   // nothing changed
        stopDecoding();
        if (file == null) {
            swapSkin(null);
            invalidate();
            return;
        }
        // Show it as soon as the decode is back; until then the loading hint is
        // drawn, so the card never flashes empty.
        loading = true;
        invalidate();
        handler().postDelayed(decodeRunnable, 16L);
    }

    /** Hand an already-decoded bitmap to the view (Skin Studio keeps its own copy). */
    public void setBitmap(@Nullable Bitmap bitmap, boolean slimModel) {
        stopDecoding();
        pendingFile = null;
        loading = false;
        notice = bitmap == null ? "NO SKIN SELECTED YET" : null;
        slim = slimModel;
        swapSkin(bitmap);
    }

    /** @param text small caption drawn under the figure; null clears it. */
    public void setNotice(@Nullable String text) {
        if (textEquals(notice, text)) return;
        notice = text;
        invalidate();
    }

    private static boolean textEquals(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }

    private Handler handler() {
        if (decodeHandler == null) decodeHandler = new Handler(Looper.getMainLooper());
        return decodeHandler;
    }

    private void stopDecoding() {
        if (decodeHandler != null) decodeHandler.removeCallbacks(decodeRunnable);
        loading = false;
    }

    /** The bitmap is owned here: recycle the old one, never the caller's twice. */
    private void swapSkin(@Nullable Bitmap next) {
        if (skin == next) {
            if (skin != null && skin.isRecycled()) skin = null;
            invalidate();
            return;
        }
        if (skin != null && !skin.isRecycled()) skin.recycle();
        skin = next;
        invalidate();
    }

    public void setMode(int newMode) {
        if (mode != newMode) {
            mode = newMode;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float pad = 9f * density;
        float labelH = labelPaint.getTextSize() * 1.6f;
        float availW = getWidth() - pad * 2f;
        float availH = getHeight() - pad * 2f;
        if (availW <= 0f || availH <= 0f) return;

        if (skin == null || skin.isRecycled()) {
            drawEmptyFigure(canvas, availW, availH, pad, density);
            drawCaption(canvas, availW, availH, pad, density);
            return;
        }

        if (mode == MODE_RAW_SHEET) {
            drawRawSheet(canvas, availW, availH, pad, density);
            drawCaption(canvas, availW, availH, pad, density);
            return;
        }

        boolean twoViews = mode != MODE_FRONT_ONLY
                && availW / availH >= (FIG_W * 2f + 4f) / FIG_H;
        float gap = twoViews ? FIG_W * 0.5f : 0f;
        float totalW = FIG_W * (twoViews ? 2f : 1f) + gap;
        float unit = Math.min(availW / totalW, (availH - labelH) / FIG_H);
        if (unit <= 0f) {
            // A card shorter than the label row: draw the mannequin rather than
            // feed negative rectangles to the canvas.
            drawEmptyFigure(canvas, availW, availH, pad, density);
            drawCaption(canvas, availW, availH, pad, density);
            return;
        }
        float ox = pad + (availW - unit * totalW) / 2f;
        float oy = pad + (availH - labelH - unit * FIG_H) / 2f + labelH * 0.35f;
        float scale = skin.getWidth() / 64f;

        // Ground the figures before drawing them, so they read as standing.
        float baseY = oy + unit * FIG_H + unit * 1.1f;
        int views = twoViews ? 2 : 1;
        for (int i = 0; i < views; i++) {
            float cx = ox + unit * (FIG_W * 0.5f + i * (FIG_W + gap));
            dst.set(cx - unit * FIG_W * 0.6f, baseY - unit * 0.9f,
                    cx + unit * FIG_W * 0.6f, baseY + unit * 1.9f);
            canvas.drawOval(dst, shade);
        }

        drawFigure(canvas, scale, unit, ox, oy, false);
        if (twoViews) {
            float ox2 = ox + unit * (FIG_W + gap);
            drawFigure(canvas, scale, unit, ox2, oy, true);
            float ly = Math.min(baseY + unit * 1.9f, getHeight() - 3f * density);
            canvas.drawText("FRONT", ox + unit * FIG_W * 0.5f, ly, labelPaint);
            canvas.drawText("BACK", ox2 + unit * FIG_W * 0.5f, ly, labelPaint);
        }
        drawCaption(canvas, availW, availH, pad, density);
    }

    /** Loading / error line, so a preview state is never silent. */
    private void drawCaption(Canvas c, float availW, float availH, float pad, float density) {
        String text = loading ? "READING SKIN\u2026" : notice;
        if (text == null || text.isEmpty()) return;
        Paint p = new Paint(labelPaint);
        p.setColor(loading ? 0x99FFFFFF : noticeIsError() ? 0xFFF2A0A0 : 0x99FFFFFF);
        p.setTextSize(8f * density);
        float y = Math.min(getHeight() - pad, pad + availH);
        c.drawText(text, pad + availW / 2f, y, p);
    }

    private boolean noticeIsError() {
        return notice != null && !loading;
    }

    /**
     * One side of the character. {@code back} draws the rear faces of the same
     * silhouette — the sheet stores them in the rows below each front face.
     */
    private void drawFigure(Canvas c, float scale, float u, float ox, float oy, boolean back) {
        boolean slimLayout = slim || skin.getHeight() <= 32;
        float armW = slimLayout ? 3f : 4f;
        // The viewer's right arm: 44-48 on a classic sheet, 46-49 when slim.
        float rightArmX = slimLayout ? 46f : 44f;
        float leftArmX = 40f;
        if (!back) {
            part(c, scale, 8, 8, 8, 8, ox + u, oy, u * 8, u * 8);                     // head
            layer(c, scale, 40, 8, 8, 8, ox + u, oy, u * 8, u * 8);                    // hat
            part(c, scale, 20, 20, 8, 12, ox + u, oy + u * 8, u * 8, u * 12);          // torso
            layer(c, scale, 20, 36, 8, 12, ox + u, oy + u * 8, u * 8, u * 12);         // jacket
            // The viewer's right arm is the sheet's LEFT arm (40-44); classic uses
            // 44-48 for the other one, slim squeezes both into 3 pixels.
            part(c, scale, rightArmX, 20, armW, 12,
                    ox + u * (9f + (4f - armW)), oy + u * 8, armW * u, u * 12);
            layer(c, scale, rightArmX, 36, armW, 12,
                    ox + u * (9f + (4f - armW)), oy + u * 8, armW * u, u * 12);
            part(c, scale, leftArmX, 20, armW, 12, ox, oy + u * 8, armW * u, u * 12);
            layer(c, scale, leftArmX, 36, armW, 12, ox, oy + u * 8, armW * u, u * 12);
            part(c, scale, 4, 20, 4, 12, ox + u * 2, oy + u * 20, u * 4, u * 12);      // right leg
            layer(c, scale, 4, 52, 4, 12, ox + u * 2, oy + u * 20, u * 4, u * 12);     // pants
            part(c, scale, 20, 20, 4, 12, ox + u * 6, oy + u * 20, u * 4, u * 12);     // left leg
            layer(c, scale, 20, 52, 4, 12, ox + u * 6, oy + u * 20, u * 4, u * 12);
            return;
        }
        part(c, scale, 24, 8, 8, 8, ox, oy, u * 8, u * 8);                             // head
        layer(c, scale, 56, 8, 8, 8, ox, oy, u * 8, u * 8);                            // hat
        part(c, scale, 28, 20, 8, 12, ox + u, oy + u * 8, u * 8, u * 12);              // torso
        layer(c, scale, 28, 36, 8, 12, ox + u, oy + u * 8, u * 8, u * 12);             // jacket
        part(c, scale, rightArmX, 32, armW, 12,
                ox + u * (9f + (4f - armW)), oy + u * 8, armW * u, u * 12);
        layer(c, scale, rightArmX, 48, armW, 12,
                ox + u * (9f + (4f - armW)), oy + u * 8, armW * u, u * 12);
        part(c, scale, leftArmX, 32, armW, 12, ox, oy + u * 8, armW * u, u * 12);
        layer(c, scale, leftArmX, 48, armW, 12, ox, oy + u * 8, armW * u, u * 12);
        part(c, scale, 8, 20, 4, 12, ox + u * 2, oy + u * 20, u * 4, u * 12);          // right leg
        layer(c, scale, 8, 52, 4, 12, ox + u * 2, oy + u * 20, u * 4, u * 12);
        part(c, scale, 24, 20, 4, 12, ox + u * 6, oy + u * 20, u * 4, u * 12);          // left leg
        layer(c, scale, 24, 52, 4, 12, ox + u * 6, oy + u * 20, u * 4, u * 12);
    }

    private void part(Canvas c, float scale, float x, float y, float w, float h,
                      float dx, float dy, float dw, float dh) {
        blit(c, scale, x, y, w, h, dx, dy, dw, dh);
    }

    /**
     * Hat / jacket / sleeve / cuff layer. Overlays only exist on sheets that are
     * 64 tall, and they deliberately overhang their part by a hair — that is the
     * whole look of a Minecraft skin, so the 2D preview keeps it.
     */
    private void layer(Canvas c, float scale, float x, float y, float w, float h,
                       float dx, float dy, float dw, float dh) {
        if ((y + h) * scale > skin.getHeight() + 0.5f) return;   // 64x32 sheet: no overlays
        blit(c, scale, x, y, w, h, dx - dw * 0.02f, dy - dh * 0.015f, dw * 1.04f, dh * 1.03f);
    }

    private void blit(Canvas c, float scale, float x, float y, float w, float h,
                      float dx, float dy, float dw, float dh) {
        src.set((int) (x * scale), (int) (y * scale),
                Math.min(skin.getWidth(), (int) ((x + w) * scale)),
                Math.min(skin.getHeight(), (int) ((y + h) * scale)));
        if (src.width() <= 0 || src.height() <= 0 || dw <= 0f || dh <= 0f) return;
        paint.setColor(Color.WHITE);
        dst.set(dx, dy, dx + dw, dy + dh);
        c.drawBitmap(skin, src, dst, paint);
    }

    /**
     * Everything the game reads, in one image: the layout is what makes a "wrong
     * looking" skin explainable, so a phone card gets it as a small secondary
     * view while the big one stays the assembled character.
     */
    private void drawRawSheet(Canvas c, float availW, float availH, float pad, float density) {
        float u = Math.min(availW / 64f, availH / 64f);
        if (u <= 0f) return;
        float x = pad + (availW - u * 64f) / 2f;
        float y = pad + (availH - u * 64f) / 2f;
        dst.set(x, y, x + u * 64f, y + u * 64f);
        paint.setColor(Color.WHITE);
        c.drawBitmap(skin, null, dst, paint);
        if (u > 2.5f) {
            // Hairline over the head/torso boundary: enough to read the rows
            // without turning the thumbnail into a grid.
            Paint g = new Paint();
            g.setColor(0x33000000);
            c.drawRect(x, y + u * 8f, x + u * 64f, y + u * 8.5f, g);
            c.drawRect(x, y + u * 20f, x + u * 64f, y + u * 20.5f, g);
            c.drawRect(x + u * 32f, y, x + u * 32.5f, y + u * 64f, g);
        }
    }

    /** Dashed mannequin for an unused slot, so an empty card still reads as a skin. */
    private void drawEmptyFigure(Canvas c, float availW, float availH, float pad, float density) {
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        stroke.setColor(0x59FFFFFF);
        float u = Math.min(availW / FIG_W, availH / FIG_H);
        if (u <= 0f) return;
        float ox = pad + (availW - u * FIG_W) / 2f;
        float oy = pad + (availH - u * FIG_H) / 2f;
        c.drawRoundRect(new RectF(ox + u, oy, ox + u * 9f, oy + u * 8f), u, u, stroke);
        c.drawRect(ox + u, oy + u * 8f, ox + u * 9f, oy + u * 20f, stroke);
        c.drawRect(ox + u * 2f, oy + u * 20f, ox + u * 5f, oy + u * 28f, stroke);
        c.drawRect(ox + u * 5f, oy + u * 20f, ox + u * 8f, oy + u * 28f, stroke);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Detaching freed the bitmap (a list of three of them should not hold
        // memory while off screen); coming back must refill it or the card is
        // blank until the user leaves and returns again.
        if (skin == null && pendingFile != null) {
            loading = true;
            handler().postDelayed(decodeRunnable, 16L);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopDecoding();
        if (skin != null && !skin.isRecycled()) skin.recycle();
        skin = null;
        super.onDetachedFromWindow();
    }
}
