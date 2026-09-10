package net.kdt.pojavlaunch;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.LauncherControlImportHost;
import net.kdt.pojavlaunch.customcontrols.handleview.DrawerPullButton;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;


public class CustomControlsActivity extends BaseActivity implements EditorExitable, LauncherControlImportHost {
	private DrawerLayout mDrawerLayout;
	private ControlLayout mControlLayout;
    private DrawerPullButton mEditorSettingsButton;
    private ControlInterface mPendingImageControl;
    private final ActivityResultLauncher<String> mMenuImagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::applyControlButtonImage);
    private final ActivityResultLauncher<String> mDrawerImagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::applyDrawerButtonImage);
    private final ActivityResultLauncher<String[]> mControlJsonPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::importControlJson);

    private boolean mIsPreviewMode = false;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_custom_controls);

		mControlLayout = findViewById(R.id.customctrl_controllayout);
		mDrawerLayout = findViewById(R.id.customctrl_drawerlayout);
		mEditorSettingsButton = findViewById(R.id.drawer_button);
        View editorCanvas = findViewById(R.id.control_editor_canvas);
        editorCanvas.setAlpha(0f);
        editorCanvas.setScaleX(0.985f);
        editorCanvas.setScaleY(0.985f);
        editorCanvas.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        mEditorSettingsButton.setAlpha(0f);
        mEditorSettingsButton.animate().alpha(1f).setStartDelay(280).setDuration(220).start();

		// The fixed in-game Settings/Drawer handle is edited directly here.
        // Short tap opens tools; hold + drag moves it anywhere and persists.
        setupSettingsButtonGestures();
		mDrawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

		// Mobile-first drawer: width adapts to the device — never a fixed slab
		// covering the portrait canvas. Clamped so a small phone keeps the canvas
		// visible while a large phone still gets comfortable 2-up tiles.
		bindAdaptiveDrawerWidth();
		// Every row of the new static drawer maps onto the exact action id the
		// old ListView adapter used to dispatch, so behaviour is unchanged.
		bindDrawerActions();
		playDrawerMotion();

		mControlLayout.setModifiable(true);
		try {
			mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
		}catch (IOException e) {
			Tools.showError(this, e);
		}
		// Seed the undo stack with the freshly loaded layout.
		mControlLayout.pushUndoSnapshot();
	}

    // ───────────────────────── drawer: layout + wiring ─────────────────────────

    /** min 252dp — max 78% of the screen, hard cap 320dp. */
    private void bindAdaptiveDrawerWidth() {
        View drawerContent = findViewById(R.id.control_editor_drawer);
        if (drawerContent == null) return;
        float density = getResources().getDisplayMetrics().density;
        int screen = getResources().getDisplayMetrics().widthPixels;
        int wanted = Math.min((int) (screen * 0.78f), (int) (320 * density));
        int floor = (int) (252 * density);
        androidx.drawerlayout.widget.DrawerLayout.LayoutParams lp =
                (androidx.drawerlayout.widget.DrawerLayout.LayoutParams) drawerContent.getLayoutParams();
        lp.width = Math.max(floor, Math.min(wanted, screen - (int) (56 * density)));
        drawerContent.setLayoutParams(lp);
    }

    /** id → action table. The switch below is the ORIGINAL action code, moved
     *  verbatim out of the ListView adapter so nothing about behaviour changed. */
    private void bindDrawerActions() {
        bindDrawerAction(R.id.editor_tile_add_button, 0);
        bindDrawerAction(R.id.editor_tile_add_joystick, 2);
        bindDrawerAction(R.id.editor_tile_add_command, 3);
        bindDrawerAction(R.id.editor_tile_add_drawer, 1);
        bindDrawerAction(R.id.editor_tile_add_fps, 13);
        bindDrawerAction(R.id.editor_btn_undo, 10);
        bindDrawerAction(R.id.editor_btn_redo, 11);
        bindDrawerAction(R.id.editor_row_import, 4);
        bindDrawerAction(R.id.editor_row_save, 7);
        bindDrawerAction(R.id.editor_row_default, 8);
        bindDrawerAction(R.id.editor_row_export, 9);
        bindDrawerAction(R.id.editor_row_wrench_image, 5);
        bindDrawerAction(R.id.editor_row_wrench_size, 6);
        View close = findViewById(R.id.editor_drawer_close);
        if (close != null) close.setOnClickListener(v -> mDrawerLayout.closeDrawers());
    }

    private void bindDrawerAction(int id, int action) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(view -> {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            performEditorAction(action);
            mDrawerLayout.closeDrawers();
        });
    }

    /** Drawer motion: sections cascade in, and the canvas recedes behind it. */
    private void playDrawerMotion() {
        // Light scrim so the canvas — the thing you are actually editing — never
        // disappears behind a wall of black.
        mDrawerLayout.setScrimColor(0x38000000);
        final View canvas = findViewById(R.id.control_editor_canvas);
        mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerSlide(View drawerView, float slideOffset) {
                if (canvas == null) return;
                float s = 1f - 0.035f * slideOffset;
                canvas.setScaleX(s);
                canvas.setScaleY(s);
                canvas.setAlpha(1f - 0.32f * slideOffset);
                if (slideOffset == 0f) {
                    canvas.setTranslationX(0f);
                    canvas.setScaleX(1f);
                    canvas.setScaleY(1f);
                    canvas.setAlpha(1f);
                }
            }

            @Override public void onDrawerOpened(View drawerView) {
                refreshDrawerContext();
                View header = drawerView.findViewById(R.id.editor_drawer_header);
                float d = getResources().getDisplayMetrics().density;
                if (header != null) {
                    header.setAlpha(0f);
                    header.setTranslationX(16f * d);
                    header.animate().alpha(1f).translationX(0f).setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
                ViewGroup sections = drawerView.findViewById(R.id.editor_drawer_sections);
                if (sections == null) return;
                for (int i = 0; i < sections.getChildCount(); i++) {
                    View row = sections.getChildAt(i);
                    row.setAlpha(0f);
                    row.setTranslationX(26f * d);
                    row.animate().alpha(1f).translationX(0f)
                            .setStartDelay(60L + i * 45L)
                            .setDuration(240L)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withLayer().start();
                }
            }

            @Override public void onDrawerClosed(View drawerView) {
                if (canvas != null) {
                    canvas.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                }
            }
        });
    }

    /**
     * The drawer's whole action set. Ids 0-11 are the ORIGINAL items with their
     * original bodies — moved here verbatim when the ListView adapter was
     * replaced by the static, grouped drawer layout.
     */
    private void performEditorAction(int act) {
        android.util.Log.i("CustomControlsActivity", "Menu item clicked: action=" + act);
        switch (act) {
            case 0: android.util.Log.i("CustomControlsActivity", "Action: Add Button"); mControlLayout.addControlButton(new ControlData("New")); break;
            case 1: android.util.Log.i("CustomControlsActivity", "Action: Add Button Drawer"); mControlLayout.addDrawer(new ControlDrawerData()); break;
            case 2: android.util.Log.i("CustomControlsActivity", "Action: Add Joystick"); mControlLayout.addJoystickButton(new ControlJoystickData()); break;
            case 3: {
                android.util.Log.i("CustomControlsActivity", "Action: Add Command Button");
                net.kdt.pojavlaunch.customcontrols.ControlData cmdData = new net.kdt.pojavlaunch.customcontrols.ControlData("Command");
                cmdData.keycodes[0] = net.kdt.pojavlaunch.customcontrols.ControlData.SPECIALBTN_CHATCOMMAND;
                mControlLayout.addControlButton(cmdData);
                // Open the edit dialog for this new button immediately
                final net.kdt.pojavlaunch.customcontrols.ControlData finalCmdData = cmdData;
                mControlLayout.post(() -> {
                    for (int i = 0; i < mControlLayout.getChildCount(); i++) {
                        android.view.View child = mControlLayout.getChildAt(i);
                        if (child instanceof net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface) {
                            net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface iface = (net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface) child;
                            if (iface.getProperties() == finalCmdData) {
                                mControlLayout.editControlButton(iface);
                                break;
                            }
                        }
                    }
                });
                break;
            }
            case 4: mControlJsonPicker.launch(new String[]{"application/json", "text/json", "text/plain"}); break;
            case 5: showHandleImageDialog(); break;
            case 6: showSettingsButtonSizeDialog(); break;
            case 7: android.util.Log.i("CustomControlsActivity", "Action: Save"); mControlLayout.openSaveDialog(this); break;
            case 8: android.util.Log.i("CustomControlsActivity", "Action: Select Default"); mControlLayout.openSetDefaultDialog(); break;
            case 9: // Saving the currently shown control
                android.util.Log.i("CustomControlsActivity", "Action: Share layout");
                try {
                    Uri contentUri = DocumentsContract.buildDocumentUri(getString(R.string.storageProviderAuthorities), mControlLayout.saveToDirectory(mControlLayout.mLayoutFileName));

                    Intent shareIntent = new Intent();
                    shareIntent.setAction(Intent.ACTION_SEND);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    shareIntent.setType("application/json");
                    startActivity(shareIntent);

                    Intent sendIntent = Intent.createChooser(shareIntent, mControlLayout.mLayoutFileName);
                    startActivity(sendIntent);
                }catch (Exception e) {
                    Tools.showError(this, e);
                }
                break;
            case 10:
                if (mControlLayout.canUndo()) { mControlLayout.undo(); pulseCanvas(); }
                else Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
                break;
            case 11:
                if (mControlLayout.canRedo()) { mControlLayout.redo(); pulseCanvas(); }
                else Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show();
                break;
            case 12: togglePreviewMode(); break;
            case 13: {
                android.util.Log.i("CustomControlsActivity", "Action: Add FPS read-out");
                net.kdt.pojavlaunch.customcontrols.ControlData fpsData =
                        new net.kdt.pojavlaunch.customcontrols.ControlData("FPS",
                                new int[]{net.kdt.pojavlaunch.customcontrols.ControlData.SPECIALBTN_FPS});
                // A chip, not a square key. setWidth/setHeight take pixels and
                // store dp internally, exactly like the size field in the sheet.
                fpsData.setWidth(net.kdt.pojavlaunch.Tools.dpToPx(96));
                fpsData.setHeight(net.kdt.pojavlaunch.Tools.dpToPx(32));
                // Graphite defaults — all of these stay editable in the inspector.
                fpsData.opacity = 0.9f;
                fpsData.bgColor = 0xB30B0D10;
                fpsData.strokeColor = 0x3DFFFFFF;
                fpsData.strokeWidth = 1f;
                fpsData.cornerRadius = 60f;
                // Both true on purpose: inside the game the counter has to stay on
                // screen whether the pointer is grabbed (playing) or not (the game's
                // own menu), which is exactly what onGrabState() reads. The editor is
                // "modifiable", where that gate is skipped, so it previews here too.
                fpsData.displayInGame = true;
                fpsData.displayInMenu = true;
                mControlLayout.addControlButton(fpsData);
                // Select it immediately (the Command control's own behaviour) so the
                // user can drag it into place without hunting for it on the canvas.
                final net.kdt.pojavlaunch.customcontrols.ControlData finalFps = fpsData;
                mControlLayout.post(() -> {
                    for (int i = 0; i < mControlLayout.getChildCount(); i++) {
                        android.view.View child = mControlLayout.getChildAt(i);
                        if (child instanceof net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface) {
                            net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface iface =
                                    (net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface) child;
                            if (iface.getProperties() == finalFps) {
                                mControlLayout.editControlButton(iface);
                                break;
                            }
                        }
                    }
                });
                break;
            }
            default: break;
        }
    }

    private void togglePreviewMode() {
        mIsPreviewMode = !mIsPreviewMode;
        mControlLayout.setModifiable(!mIsPreviewMode);
        if (mEditorSettingsButton != null) {
            mEditorSettingsButton.setVisibility(mIsPreviewMode ? View.GONE : View.VISIBLE);
        }
        Toast.makeText(this, mIsPreviewMode ? "Preview Mode: test your buttons" : "Edit Mode: drag & customize", Toast.LENGTH_SHORT).show();
    }

    /** Live count in the drawer header — how many controls are on the canvas. */
    private void refreshDrawerContext() {
        View drawer = findViewById(R.id.control_editor_drawer);
        if (drawer == null || mControlLayout == null) return;
        TextView label = drawer.findViewById(R.id.editor_drawer_count);
        if (label == null) return;
        int n = 0;
        for (int i = 0; i < mControlLayout.getChildCount(); i++) {
            if (mControlLayout.getChildAt(i) instanceof ControlInterface) n++;
        }
        label.setText(n == 0 ? "EMPTY CANVAS" : n + " CONTROL" + (n == 1 ? "" : "S") + " ON CANVAS");
    }


    private void setupSettingsButtonGestures() {
        mEditorSettingsButton.applySavedLayout();
        mEditorSettingsButton.setOnTouchListener(new View.OnTouchListener() {
            float offsetX, offsetY; long downTime; boolean dragging;
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                View parent = (View)v.getParent();
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        offsetX = e.getX(); offsetY = e.getY(); downTime = e.getEventTime(); dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (!dragging && e.getEventTime() - downTime >= 220) {
                            dragging = true;
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        }
                        if (dragging) {
                            int[] loc = new int[2]; parent.getLocationOnScreen(loc);
                            float x = e.getRawX() - loc[0] - offsetX;
                            float y = e.getRawY() - loc[1] - offsetY;
                            v.setX(Math.max(0, Math.min(parent.getWidth() - v.getWidth(), x)));
                            v.setY(Math.max(0, Math.min(parent.getHeight() - v.getHeight(), y)));
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        if (dragging) mEditorSettingsButton.savePosition();
                        else { refreshDrawerContext(); mDrawerLayout.openDrawer(GravityCompat.END); }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (dragging) mEditorSettingsButton.savePosition();
                        return true;
                }
                return true;
            }
        });
    }

    // tracked for the continuous slider session (60dp = default wrench width)
    private float mHandleWidthDp = 60f;
    private static final float HANDLE_RATIO = 0.55f;

    /** Continuous-size dialog (XML): live-resizes the settings handle as the
     *  finger drags — replaces the old fixed Small/Medium/Large choices. */
    private void showSettingsButtonSizeDialog() {
        View root = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_handle_size, null);
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setView(root).create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        final android.widget.SeekBar seek = root.findViewById(R.id.dhs_seek);
        final android.widget.TextView value = root.findViewById(R.id.dhs_value);
        seek.setMax(96 - 24);
        seek.setProgress(Math.round(mHandleWidthDp) - 24);
        value.setText(Math.round(mHandleWidthDp) + " dp");
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                mHandleWidthDp = 24 + p;
                value.setText(Math.round(mHandleWidthDp) + " dp");
                mEditorSettingsButton.setSizeDp(mHandleWidthDp, mHandleWidthDp * HANDLE_RATIO); // LIVE
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        root.findViewById(R.id.dhs_reset).setOnClickListener(v -> seek.setProgress(60 - 24));
        root.findViewById(R.id.dhs_close).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        playDialogEntry(root);
    }

    /** Image picker with real reset (XML dialog) — replaces raw gallery jump. */
    private void showHandleImageDialog() {
        View root = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_handle_image, null);
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setView(root).create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        root.findViewById(R.id.dhi_pick).setOnClickListener(v -> { dialog.dismiss(); mDrawerImagePicker.launch("image/*"); });
        root.findViewById(R.id.dhi_reset).setOnClickListener(v -> {
            mEditorSettingsButton.setCustomImageData(null);
            Toast.makeText(this, "Settings button image reset", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        dialog.show();
        playDialogEntry(root);
    }

    /** Shared subtle dialog entry: fade + light rise + soft settle. */
    private void playDialogEntry(View root) {
        root.setAlpha(0f);
        root.setScaleX(0.94f); root.setScaleY(0.94f);
        root.setTranslationY(14f * getResources().getDisplayMetrics().density);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /** Visual ack for undo/redo: the canvas breathes once. */
    private void pulseCanvas() {
        if (mControlLayout == null) return;
        mControlLayout.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90)
                .withEndAction(() -> mControlLayout.animate().scaleX(1f).scaleY(1f).setDuration(130).start())
                .start();
        refreshDrawerContext();
    }


    private void applyDrawerButtonImage(Uri uri) {
        if (uri == null) return;
        String data = encodeImage(uri);
        if (data == null) { Toast.makeText(this, "Unsupported image", Toast.LENGTH_SHORT).show(); return; }
        mEditorSettingsButton.setCustomImageData(data);
        Toast.makeText(this, "Settings button image changed", Toast.LENGTH_SHORT).show();
    }

    private String encodeImage(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap source = BitmapFactory.decodeStream(in);
            if (source == null) return null;
            int max = 192;
            float scale = Math.min(1f, max / (float)Math.max(source.getWidth(), source.getHeight()));
            Bitmap bitmap = scale < 1f ? Bitmap.createScaledBitmap(source,
                    Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true) : source;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    private void importControlJson(Uri uri) {
        if (uri == null) return;
        try {
            new File(Tools.CTRLMAP_PATH).mkdirs();
            String raw = Tools.getFileName(this, uri);
            String name = raw == null ? "imported_controls.json" : raw.replaceAll("[^A-Za-z0-9._-]", "_");
            if (!name.toLowerCase().endsWith(".json")) name += ".json";
            File dest = new File(Tools.CTRLMAP_PATH, name);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("Unable to read selected file");
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            mControlLayout.loadLayout(dest.getAbsolutePath());
            Toast.makeText(this, "Controls imported: " + name, Toast.LENGTH_SHORT).show();
            refreshDrawerContext();
        } catch (Exception e) { Tools.showError(this, e); }
    }

    @Override
    public void requestControlImage(ControlInterface control) {
        mPendingImageControl = control;
        mMenuImagePicker.launch("image/*");
    }

    private void applyControlButtonImage(Uri uri) {
        if (uri == null || mPendingImageControl == null) return;
        try {
            // Dynamic Button Asset System: keep the ORIGINAL media file so
            // SVG stays vector and GIF stays animated. The file is copied into
            // the launcher's own icon folder and referenced by absolute path.
            String ext = resolveImageExtension(uri);
            File dir = new File(Tools.DIR_GAME_HOME, "controlicons");
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, "icon_" + System.currentTimeMillis() + "." + ext);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("Unable to read selected file");
                byte[] buffer = new byte[8192]; int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            net.kdt.pojavlaunch.customcontrols.ControlData properties = mPendingImageControl.getProperties();
            properties.iconType = net.kdt.pojavlaunch.customcontrols.ControlData.ICON_EXTERNAL_FILE;
            properties.iconPath = dest.getAbsolutePath();
            properties.atlasSource = null;
            properties.atlasRect = null;
            properties.customIcon = null;
            mPendingImageControl.setProperties(properties);
            mPendingImageControl = null;
            android.widget.Toast.makeText(this, "Control image applied", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Tools.showError(this, e);
        }
    }

    /** Best-effort extension detection (mime first, then file name). */
    private String resolveImageExtension(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime != null) {
            if (mime.contains("svg")) return "svg";
            if (mime.contains("gif")) return "gif";
            if (mime.contains("webp")) return "webp";
            if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
            if (mime.contains("png")) return "png";
        }
        String name = Tools.getFileName(this, uri);
        if (name != null) {
            String lower = name.toLowerCase();
            int dot = lower.lastIndexOf('.');
            if (dot >= 0 && dot < lower.length() - 1) {
                String ext = lower.substring(dot + 1);
                if (ext.equals("svg") || ext.equals("gif") || ext.equals("webp")
                        || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")) return ext;
            }
        }
        return "png";
    }

    private void applyControlIconData(String data) {
        ControlInterface control = mPendingImageControl;
        if (control == null) return;
        ControlData properties = control.getProperties();
        properties.customIcon = data;
        control.setProperties(properties);
        mPendingImageControl = null;
    }

	@Override
	public void onBackPressed() {
		mControlLayout.askToExit(this);
	}

	@Override
	public void exitEditor() {
		super.onBackPressed();
	}
}
