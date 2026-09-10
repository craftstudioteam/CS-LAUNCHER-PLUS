package net.kdt.pojavlaunch.shortcuts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Turns a raw profile/skin/custom bitmap into a polished launcher icon.
 *
 * <p>Three things happen here that the old single-path code did not do:</p>
 * <ul>
 *   <li><b>Shape masking</b> — square icons look broken on modern launchers, so
 *       the source is clipped to a circle, squircle or rounded square.</li>
 *   <li><b>Action badge</b> — a small corner glyph tells the user whether the
 *       shortcut launches the game, opens mods, edits, etc.</li>
 *   <li><b>Adaptive safe zone</b> — Android 8+ crops adaptive icons aggressively,
 *       so the artwork is inset into the guaranteed-visible centre region.</li>
 * </ul>
 *
 * <p>All methods are pure and thread-safe; they can be called from a worker.</p>
 */
public final class ShortcutIconRenderer {

    /** Final icon edge length in pixels. 192 covers xxxhdpi launchers comfortably. */
    public static final int ICON_SIZE = 192;

    /**
     * Adaptive icons are masked to roughly the centre 72/108 of the canvas, and
     * the outer band may be cropped entirely. Scaling the artwork to 72% keeps it
     * inside the guaranteed safe zone.
     */
    private static final float ADAPTIVE_CONTENT_SCALE = 0.72f;

    /** Badge diameter as a fraction of the icon edge. */
    private static final float BADGE_SCALE = 0.34f;

    /** Icon outline shapes offered in the picker. */
    public enum IconShape {
        CIRCLE("circle"),
        SQUIRCLE("squircle"),
        ROUNDED_SQUARE("rounded_square"),
        SQUARE("square");

        private final String mId;

        IconShape(String id) {
            mId = id;
        }

        @NonNull
        public String getId() {
            return mId;
        }

        @NonNull
        public static IconShape fromId(@Nullable String id) {
            if (id == null) return SQUIRCLE;
            for (IconShape shape : values()) {
                if (shape.mId.equals(id)) return shape;
            }
            return SQUIRCLE;
        }
    }

    private ShortcutIconRenderer() {
        // static only
    }

    /**
     * Render the final launcher icon.
     *
     * @param context      used to resolve the badge drawable
     * @param source       artwork to place inside the shape; a flat colour tile is
     *                     drawn when null
     * @param shape        outline to clip to
     * @param type         action, used to pick the badge glyph; null hides the badge
     * @param badgeColor   accent colour behind the badge glyph
     * @param adaptive     inset the artwork for Android 8+ adaptive masking
     */
    @NonNull
    public static Bitmap render(@NonNull Context context,
                                @Nullable Bitmap source,
                                @NonNull IconShape shape,
                                @Nullable ShortcutType type,
                                @ColorInt int badgeColor,
                                boolean adaptive) {

        Bitmap output = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // Artwork is inset when adaptive so the launcher's mask cannot clip it away.
        float inset = adaptive ? (ICON_SIZE * (1f - ADAPTIVE_CONTENT_SCALE)) / 2f : 0f;
        RectF bounds = new RectF(inset, inset, ICON_SIZE - inset, ICON_SIZE - inset);

        drawShapedArtwork(canvas, source, shape, bounds);

        if (type != null) {
            drawBadge(context, canvas, type, badgeColor);
        }

        return output;
    }

    /** Convenience overload used by the live preview, which never needs a badge. */
    @NonNull
    public static Bitmap renderPreview(@NonNull Context context,
                                       @Nullable Bitmap source,
                                       @NonNull IconShape shape,
                                       @Nullable ShortcutType type,
                                       @ColorInt int badgeColor,
                                       boolean adaptive) {
        return render(context, source, shape, type, badgeColor, adaptive);
    }

    // ─── Artwork ───────────────────────────────────────────────────────

    private static void drawShapedArtwork(@NonNull Canvas canvas,
                                          @Nullable Bitmap source,
                                          @NonNull IconShape shape,
                                          @NonNull RectF bounds) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);

        Path clip = buildShapePath(shape, bounds);

        int layer = canvas.saveLayer(0, 0, ICON_SIZE, ICON_SIZE, null);
        canvas.drawPath(clip, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));

        if (source != null && !source.isRecycled()) {
            // centerCrop the source into the shape bounds so non-square artwork
            // fills the icon instead of being squashed.
            Rect src = centerCropSource(source, bounds);
            canvas.drawBitmap(source, src, bounds, paint);
        } else {
            // Fallback tile: a subtle vertical gradient rather than flat grey.
            paint.setShader(new LinearGradient(
                    bounds.left, bounds.top, bounds.left, bounds.bottom,
                    Color.parseColor("#1F2937"), Color.parseColor("#0B1220"),
                    Shader.TileMode.CLAMP));
            canvas.drawRect(bounds, paint);
            paint.setShader(null);
        }

        paint.setXfermode(null);
        canvas.restoreToCount(layer);
    }

    /**
     * Compute the source rectangle that yields a centre-crop when drawn into
     * {@code dest}, preserving the artwork's aspect ratio.
     */
    @NonNull
    private static Rect centerCropSource(@NonNull Bitmap source, @NonNull RectF dest) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        float destRatio = dest.width() / dest.height();
        float srcRatio = (float) sw / (float) sh;

        if (srcRatio > destRatio) {
            // Source is wider — trim the sides.
            int cropWidth = Math.max(1, Math.round(sh * destRatio));
            int x = (sw - cropWidth) / 2;
            return new Rect(x, 0, x + cropWidth, sh);
        } else {
            // Source is taller — trim top and bottom.
            int cropHeight = Math.max(1, Math.round(sw / destRatio));
            int y = (sh - cropHeight) / 2;
            return new Rect(0, y, sw, y + cropHeight);
        }
    }

    @NonNull
    private static Path buildShapePath(@NonNull IconShape shape, @NonNull RectF bounds) {
        Path path = new Path();
        switch (shape) {
            case CIRCLE:
                path.addOval(bounds, Path.Direction.CW);
                break;
            case ROUNDED_SQUARE: {
                float r = bounds.width() * 0.22f;
                path.addRoundRect(bounds, r, r, Path.Direction.CW);
                break;
            }
            case SQUARE:
                path.addRect(bounds, Path.Direction.CW);
                break;
            case SQUIRCLE:
            default:
                buildSquircle(path, bounds);
                break;
        }
        return path;
    }

    /**
     * Approximate an iOS-style squircle with four cubic segments. Using a plain
     * rounded rect here reads as "app icon from 2014"; the squircle matches what
     * Pixel and One UI launchers mask to.
     */
    private static void buildSquircle(@NonNull Path path, @NonNull RectF b) {
        float w = b.width();
        float h = b.height();
        // 0.28 gives the classic superellipse shoulder without looking circular.
        float cx = w * 0.28f;
        float cy = h * 0.28f;

        path.moveTo(b.left + w / 2f, b.top);
        path.cubicTo(b.left + w / 2f + cx, b.top,
                b.right, b.top + h / 2f - cy,
                b.right, b.top + h / 2f);
        path.cubicTo(b.right, b.top + h / 2f + cy,
                b.left + w / 2f + cx, b.bottom,
                b.left + w / 2f, b.bottom);
        path.cubicTo(b.left + w / 2f - cx, b.bottom,
                b.left, b.top + h / 2f + cy,
                b.left, b.top + h / 2f);
        path.cubicTo(b.left, b.top + h / 2f - cy,
                b.left + w / 2f - cx, b.top,
                b.left + w / 2f, b.top);
        path.close();
    }

    // ─── Action badge ──────────────────────────────────────────────────

    /**
     * Draw a filled circle with the action glyph in the bottom-right corner,
     * with a dark ring so it stays legible over bright artwork.
     */
    private static void drawBadge(@NonNull Context context,
                                  @NonNull Canvas canvas,
                                  @NonNull ShortcutType type,
                                  @ColorInt int badgeColor) {
        float badgeSize = ICON_SIZE * BADGE_SCALE;
        float cx = ICON_SIZE - badgeSize / 2f - ICON_SIZE * 0.04f;
        float cy = ICON_SIZE - badgeSize / 2f - ICON_SIZE * 0.04f;
        float radius = badgeSize / 2f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Dark outer ring separates the badge from the artwork underneath.
        paint.setColor(Color.parseColor("#0B1220"));
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setColor(badgeColor);
        canvas.drawCircle(cx, cy, radius * 0.84f, paint);

        Drawable glyph = ContextCompat.getDrawable(context, type.getIconRes());
        if (glyph == null) return;

        glyph = glyph.mutate();
        // Dark glyph on the bright accent badge keeps contrast high.
        glyph.setColorFilter(Color.parseColor("#0B1220"), PorterDuff.Mode.SRC_IN);

        float glyphHalf = radius * 0.46f;
        glyph.setBounds(Math.round(cx - glyphHalf), Math.round(cy - glyphHalf),
                Math.round(cx + glyphHalf), Math.round(cy + glyphHalf));
        glyph.draw(canvas);
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    /**
     * Rasterise any drawable into a bitmap, capped at {@link #ICON_SIZE}.
     * Vector drawables report intrinsic sizes in dp, so they are upscaled to the
     * icon size rather than rendered tiny.
     */
    @Nullable
    public static Bitmap drawableToBitmap(@Nullable Drawable drawable) {
        if (drawable == null) return null;

        if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        // Vectors and shape drawables often have no intrinsic size.
        if (width <= 0 || height <= 0) {
            width = ICON_SIZE;
            height = ICON_SIZE;
        } else {
            float scale = Math.min((float) ICON_SIZE / width, (float) ICON_SIZE / height);
            // Upscale small vectors, downscale oversized bitmaps.
            width = Math.max(1, Math.round(width * scale));
            height = Math.max(1, Math.round(height * scale));
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * Nearest-neighbour upscale, used for Minecraft skin heads.
     * Bilinear filtering turns 8x8 pixel art into mush; this keeps it crisp.
     */
    @NonNull
    public static Bitmap upscalePixelArt(@NonNull Bitmap source, int targetSize) {
        Bitmap output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        canvas.drawBitmap(source,
                new Rect(0, 0, source.getWidth(), source.getHeight()),
                new RectF(0, 0, targetSize, targetSize),
                paint);
        return output;
    }
}
