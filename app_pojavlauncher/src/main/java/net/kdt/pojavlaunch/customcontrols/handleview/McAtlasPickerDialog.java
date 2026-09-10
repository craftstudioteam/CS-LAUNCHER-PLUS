package net.kdt.pojavlaunch.customcontrols.handleview;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlIconStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SPRITE ATLAS BROWSER — grid picker over the vanilla Minecraft GUI sheets
 * (icons.png / widgets.png / bars.png) extracted from an installed client
 * jar. Tiles are pre-cropped with standard UV coordinates and shown with
 * nearest-neighbour scaling so the pixel-art stays authentic.
 * ═══════════════════════════════════════════════════════════════════════
 */
public final class McAtlasPickerDialog {
    private McAtlasPickerDialog() {}

    public interface Callback {
        void onSpritePicked(String atlasSource, int u, int v, int w, int h);
    }

    /** One predefined sprite: label + sheet + UV bounding box. */
    private static final class Sprite {
        final String label, source; final int u, v, w, h;
        Sprite(String label, String source, int u, int v, int w, int h) {
            this.label = label; this.source = source;
            this.u = u; this.v = v; this.w = w; this.h = h;
        }
    }

    /** Classic 256×256 GUI sheet coordinates (stable across vanilla versions). */
    private static final Sprite[] SPRITES = {
            new Sprite("Crosshair",      "icons.png",   0,  0, 16, 16),
            new Sprite("Heart",          "icons.png",  52,  0,  9,  9),
            new Sprite("Half Heart",     "icons.png",  61,  0,  9,  9),
            new Sprite("Heart Frame",    "icons.png",  16,  0,  9,  9),
            new Sprite("Poison Heart",   "icons.png",  88,  0,  9,  9),
            new Sprite("Armor",          "icons.png",  34,  9,  9,  9),
            new Sprite("Half Armor",     "icons.png",  25,  9,  9,  9),
            new Sprite("Armor Frame",    "icons.png",  16,  9,  9,  9),
            new Sprite("Hunger",         "icons.png",  52, 27,  9,  9),
            new Sprite("Half Hunger",    "icons.png",  61, 27,  9,  9),
            new Sprite("Hunger Frame",   "icons.png",  16, 27,  9,  9),
            new Sprite("Air Bubble",     "icons.png",  16, 18,  9,  9),
            new Sprite("XP Bar",         "icons.png",   0, 69, 182, 5),
            new Sprite("Hotbar",         "widgets.png", 0,  0, 182, 22),
            new Sprite("Slot Selector",  "widgets.png", 0, 22, 24, 24),
            new Sprite("Offhand Slot",   "widgets.png", 24, 22, 29, 24),
            new Sprite("MC Button",      "widgets.png", 0, 66, 200, 20),
            new Sprite("MC Button Lit",  "widgets.png", 0, 86, 200, 20),
            new Sprite("Boss Bar Pink",  "bars.png",    0,  5, 182, 5),
            new Sprite("Boss Bar White", "bars.png",    0, 65, 182, 5),
    };

    private static final class Tile {
        final Sprite sprite; final Bitmap preview;
        Tile(Sprite sprite, Bitmap preview) { this.sprite = sprite; this.preview = preview; }
    }

    /** Build the tiles off-thread, then pop the grid dialog. */
    public static void show(Context context, Callback callback) {
        Toast.makeText(context, "Loading Minecraft sprites…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        PojavApplication.sExecutorService.execute(() -> {
            List<Tile> tiles = new ArrayList<>();
            String lastSource = null; Bitmap lastAtlas = null;
            for (Sprite sprite : SPRITES) {
                try {
                    if (!sprite.source.equals(lastSource)) {
                        File sheet = ControlIconStore.resolveAtlas(context, sprite.source);
                        lastAtlas = sheet == null ? null
                                : BitmapFactory.decodeFile(sheet.getAbsolutePath());
                        lastSource = sprite.source;
                    }
                    if (lastAtlas == null) continue;
                    if (sprite.u + sprite.w > lastAtlas.getWidth()
                            || sprite.v + sprite.h > lastAtlas.getHeight()) continue;
                    Bitmap crop = Bitmap.createBitmap(lastAtlas, sprite.u, sprite.v, sprite.w, sprite.h);
                    // Nearest-neighbour preview, longest edge 96px.
                    float scale = 96f / Math.max(crop.getWidth(), crop.getHeight());
                    Bitmap preview = Bitmap.createScaledBitmap(crop,
                            Math.max(1, Math.round(crop.getWidth() * scale)),
                            Math.max(1, Math.round(crop.getHeight() * scale)), false);
                    tiles.add(new Tile(sprite, preview));
                } catch (Throwable ignored) {}
            }
            main.post(() -> {
                if (tiles.isEmpty()) {
                    Toast.makeText(context,
                            "No vanilla Minecraft version found — install & run a vanilla version once, then retry.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showGrid(context, tiles, callback);
            });
        });
    }

    private static void showGrid(Context context, List<Tile> tiles, Callback callback) {
        float d = context.getResources().getDisplayMetrics().density;

        GridView grid = new GridView(context);
        grid.setNumColumns(4);
        grid.setVerticalSpacing((int) (8 * d));
        grid.setHorizontalSpacing((int) (8 * d));
        grid.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (14 * d));
        grid.setSelector(android.R.color.transparent);

        // Small header so the dialog explains itself.
        LinearLayout rootColumn = new LinearLayout(context);
        rootColumn.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("MINECRAFT SPRITE ATLAS");
        title.setTextColor(0xFFF0F1F5);
        title.setTextSize(12);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        title.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), 0);
        TextView sub = new TextView(context);
        sub.setText("Tap a sprite to use it as this button's icon");
        sub.setTextColor(0xFF8D93A1);
        sub.setTextSize(9.5f);
        sub.setPadding((int) (16 * d), (int) (2 * d), (int) (16 * d), 0);
        rootColumn.addView(title);
        rootColumn.addView(sub);
        rootColumn.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (300 * d)));

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return tiles.size(); }
            @Override public Object getItem(int position) { return tiles.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Tile tile = tiles.get(position);
                LinearLayout cell;
                ImageView image; TextView label;
                if (convertView instanceof LinearLayout) {
                    cell = (LinearLayout) convertView;
                    image = (ImageView) cell.getChildAt(0);
                    label = (TextView) cell.getChildAt(1);
                } else {
                    cell = new LinearLayout(context);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.bg_cursorx_style);
                    cell.setPadding((int) (6 * d), (int) (8 * d), (int) (6 * d), (int) (7 * d));
                    image = new ImageView(context);
                    cell.addView(image, new LinearLayout.LayoutParams((int) (52 * d), (int) (34 * d)));
                    label = new TextView(context);
                    label.setTextSize(8f);
                    label.setTextColor(0xFFC6CAD3);
                    label.setGravity(Gravity.CENTER);
                    label.setMaxLines(1);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = (int) (5 * d);
                    cell.addView(label, lp);
                }
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(tile.preview);
                label.setText(tile.sprite.label);
                return cell;
            }
        };
        grid.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(rootColumn)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Sprite s = tiles.get(position).sprite;
            callback.onSpritePicked(s.source, s.u, s.v, s.w, s.h);
            dialog.dismiss();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_cs_dialog);
            dialog.getWindow().setLayout((int) (340 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}
