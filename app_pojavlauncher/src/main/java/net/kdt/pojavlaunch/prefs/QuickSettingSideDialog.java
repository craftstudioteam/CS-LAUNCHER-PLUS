package net.kdt.pojavlaunch.prefs;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_DISABLE_GESTURES;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_GYRO_INVERT_X;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_GYRO_INVERT_Y;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_GYRO_SENSITIVITY;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_LONGPRESS_TRIGGER;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_MOUSESPEED;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_MOUSE_GRAB_FORCE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SCALE_FACTOR;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import com.kdt.CustomSeekbar;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.interfaces.SimpleSeekBarListener;

/**
 * Side dialog for quick settings that you can change in game
 * The implementation has to take action on some preference changes
 */
public abstract class QuickSettingSideDialog extends com.kdt.SideDialogView {

    private SharedPreferences.Editor mEditor;
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch mGyroSwitch, mGyroXSwitch, mGyroYSwitch, mGestureSwitch, mMouseGrabSwitch;
    private Switch mOsdMemSwitch; // CS On-Screen Display (user req)
    private CustomSeekbar mGyroSensitivityBar, mMouseSpeedBar, mGestureDelayBar, mResolutionBar;
    private TextView mGyroSensitivityText, mGyroSensitivityDisplayText, mMouseSpeedText, mGestureDelayText, mGestureDelayDisplayText, mResolutionText;

    private boolean mOriginalGyroEnabled, mOriginalGyroXEnabled, mOriginalGyroYEnabled, mOriginalGestureDisabled, mOriginalMouseGrab;
    private float mOriginalGyroSensitivity, mOriginalMouseSpeed, mOriginalResolution;
    private int mOriginalGestureDelay;

    public QuickSettingSideDialog(Context context, ViewGroup parent) {
        super(context, parent, R.layout.dialog_quick_setting);
        setTitle(R.string.quick_setting_title);
        setGlyph("QS");
        setKicker("IN-GAME  ·  LIVE");
        setupCancelButton();
    }

    /**
     * These quick settings are also reachable from the launcher's settings screen, which lives in
     * another process. Writing them through {@link SharedSettings} keeps both sides in agreement
     * instead of letting whoever saves last win.
     */
    private android.content.Context ctx() {
        return mDialogContent != null ? mDialogContent.getContext() : null;
    }

    @Override
    protected void onInflate() {
        bindLayout();
        Tools.runOnUiThread(() -> {
            this.setupListeners();
            this.updateGyroCompatibility();
        });
    }

    @Override
    protected void onDestroy() {
        removeListeners();
    }

    private void bindLayout() {
        // Bind layout elements
        mGyroSwitch = mDialogContent.findViewById(R.id.checkboxGyro);
        mGyroXSwitch = mDialogContent.findViewById(R.id.checkboxGyroX);
        mGyroYSwitch = mDialogContent.findViewById(R.id.checkboxGyroY);
        mGestureSwitch = mDialogContent.findViewById(R.id.checkboxGesture);
        mMouseGrabSwitch = mDialogContent.findViewById(R.id.always_grab_mouse_side_dialog);
        // CS On-Screen Display toggles. The FPS switch is deliberately gone: the
        // frame rate is now a canvas control (Custom Controls -> ADD -> FPS Counter),
        // so it is placed, styled and saved with the layout instead of being a
        // second, floating HUD with its own position and its own toggle.
        mOsdMemSwitch = mDialogContent.findViewById(R.id.osd_show_memory);
        if (mOsdMemSwitch != null) {
            mOsdMemSwitch.setChecked(LauncherPreferences.DEFAULT_PREF.getBoolean("showMemoryChip", false));
            mOsdMemSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedSettings.putBoolean(ctx(), "showMemoryChip", isChecked);
                if (buttonView.getContext() instanceof net.kdt.pojavlaunch.MainActivity)
                    ((net.kdt.pojavlaunch.MainActivity) buttonView.getContext()).refreshDiagnosticHudTicker();
                showSaveFeedback(buttonView.getContext());
            });
        }

        mGyroSensitivityBar = mDialogContent.findViewById(R.id.editGyro_seekbar);
        mMouseSpeedBar = mDialogContent.findViewById(R.id.editMouseSpeed_seekbar);
        mGestureDelayBar = mDialogContent.findViewById(R.id.editGestureDelay_seekbar);
        mResolutionBar = mDialogContent.findViewById(R.id.editResolution_seekbar);

        mGyroSensitivityText = mDialogContent.findViewById(R.id.editGyro_textView_percent);
        mGyroSensitivityDisplayText = mDialogContent.findViewById(R.id.editGyro_textView);
        mMouseSpeedText = mDialogContent.findViewById(R.id.editMouseSpeed_textView_percent);
        mGestureDelayText = mDialogContent.findViewById(R.id.editGestureDelay_textView_percent);
        mGestureDelayDisplayText = mDialogContent.findViewById(R.id.editGestureDelay_textView);
        mResolutionText = mDialogContent.findViewById(R.id.editResolution_textView_percent);
    }

    private void setupListeners() {
        mEditor = LauncherPreferences.DEFAULT_PREF.edit();

        mOriginalGyroEnabled = PREF_ENABLE_GYRO;
        mOriginalGyroXEnabled = PREF_GYRO_INVERT_X;
        mOriginalGyroYEnabled = PREF_GYRO_INVERT_Y;
        mOriginalGestureDisabled = PREF_DISABLE_GESTURES;
        mOriginalMouseGrab = PREF_MOUSE_GRAB_FORCE;

        mOriginalGyroSensitivity = PREF_GYRO_SENSITIVITY;
        mOriginalMouseSpeed = PREF_MOUSESPEED;
        mOriginalGestureDelay = PREF_LONGPRESS_TRIGGER;
        mOriginalResolution = PREF_SCALE_FACTOR;

        mGyroSwitch.setChecked(mOriginalGyroEnabled);
        mGyroXSwitch.setChecked(mOriginalGyroXEnabled);
        mGyroYSwitch.setChecked(mOriginalGyroYEnabled);
        mGestureSwitch.setChecked(mOriginalGestureDisabled);
        mMouseGrabSwitch.setChecked(mOriginalMouseGrab);

        mGyroSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_ENABLE_GYRO = isChecked;
            onGyroStateChanged();
            updateGyroVisibility(isChecked);
            SharedSettings.putBoolean(ctx(), "enableGyro", isChecked);
            showSaveFeedback(buttonView.getContext());
        });

        mGyroXSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_GYRO_INVERT_X = isChecked;
            onGyroStateChanged();
            SharedSettings.putBoolean(ctx(), "gyroInvertX", isChecked);
            showSaveFeedback(buttonView.getContext());
        });

        mGyroYSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_GYRO_INVERT_Y = isChecked;
            onGyroStateChanged();
            SharedSettings.putBoolean(ctx(), "gyroInvertY", isChecked);
            showSaveFeedback(buttonView.getContext());
        });

        mGestureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_DISABLE_GESTURES = isChecked;
            updateGestureVisibility(isChecked);
            SharedSettings.putBoolean(ctx(), "disableGestures", isChecked);
            showSaveFeedback(buttonView.getContext());
        });

        mMouseGrabSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            PREF_MOUSE_GRAB_FORCE = isChecked;
            SharedSettings.putBoolean(ctx(), "always_grab_mouse", isChecked);
            showSaveFeedback(buttonView.getContext());
        });

        mGyroSensitivityBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_GYRO_SENSITIVITY = progress / 100f;
            SharedSettings.putInt(ctx(), "gyroSensitivity", progress);
            setSeekTextPercent(mGyroSensitivityText, progress);
            if (fromUser) showSaveFeedback(seekBar.getContext());
        });
        mGyroSensitivityBar.setProgress((int) (mOriginalGyroSensitivity * 100f));
        setSeekTextPercent(mGyroSensitivityText, mGyroSensitivityBar.getProgress());

        mMouseSpeedBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_MOUSESPEED = progress / 100f;
            SharedSettings.putInt(ctx(), "mousespeed", progress);
            setSeekTextPercent(mMouseSpeedText, progress);
            if (fromUser) showSaveFeedback(seekBar.getContext());
        });
        mMouseSpeedBar.setProgress((int) (mOriginalMouseSpeed * 100f));
        setSeekTextPercent(mMouseSpeedText, mMouseSpeedBar.getProgress());

        mGestureDelayBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_LONGPRESS_TRIGGER = progress;
            SharedSettings.putInt(ctx(), "timeLongPressTrigger", progress);
            setSeekTextMillisecond(mGestureDelayText, progress);
            if (fromUser) showSaveFeedback(seekBar.getContext());
        });
        mGestureDelayBar.setProgress(mOriginalGestureDelay);
        setSeekTextMillisecond(mGestureDelayText, mGestureDelayBar.getProgress());

        mResolutionBar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            PREF_SCALE_FACTOR = progress/100f;
            SharedSettings.putInt(ctx(), "resolutionRatio", progress);
            setSeekTextPercent(mResolutionText, progress);
            onResolutionChanged();
            if (fromUser) showSaveFeedback(seekBar.getContext());
        });
        mResolutionBar.setProgress((int) (mOriginalResolution * 100));
        setSeekTextPercent(mResolutionText, mResolutionBar.getProgress());


        updateMouseGrabVisibility();
        updateGyroVisibility(mOriginalGyroEnabled);
        updateGestureVisibility(mOriginalGestureDisabled);
    }

    /**
     * Phase 11: the sheet header (glyph/kicker) is gone with the plain design.
     * Changes still apply live, so a quiet title nudge is all the feedback needed.
     */
    private void showSaveFeedback(Context context) {
        try {
            if (mDialogContent == null) return;
            android.view.ViewParent p = mDialogContent.getParent();
            View root = null;
            while (p instanceof View) { root = (View) p; if (root.findViewById(R.id.side_dialog_title_textview) != null) break; p = p.getParent(); }
            if (root == null) return;
            final TextView title = root.findViewById(R.id.side_dialog_title_textview);
            if (title == null || title.getVisibility() != View.VISIBLE) return;
            title.animate().cancel();
            title.setAlpha(0.55f);
            title.animate().alpha(1f).setDuration(260).start();
        } catch (Exception ignored) {}
    }

    private static void setSeekTextMillisecond(TextView target, int value) {
        setSeekText(target, R.string.millisecond_format, value);
    }

    private static void setSeekTextPercent(TextView target, int value) {
        setSeekText(target, R.string.percent_format, value);
    }

    private static void setSeekText(TextView target, int format, int value) {
        target.setText(target.getContext().getString(format, value));
    }

    private void updateMouseGrabVisibility(){
        mMouseGrabSwitch.setVisibility(Tools.isPointerDeviceConnected()? View.VISIBLE : View.GONE);
    }

    private void updateGyroVisibility(boolean isEnabled) {
        int visibility = isEnabled ? View.VISIBLE : View.GONE;
        mGyroXSwitch.setVisibility(visibility);
        mGyroYSwitch.setVisibility(visibility);

        mGyroSensitivityBar.setVisibility(visibility);
        mGyroSensitivityText.setVisibility(visibility);
        mGyroSensitivityDisplayText.setVisibility(visibility);
    }

    private void updateGyroCompatibility() {
        boolean isGyroAvailable = Tools.deviceSupportsGyro(mDialogContent.getContext());
        if (!isGyroAvailable) {
            mGyroSwitch.setVisibility(View.GONE);
            updateGestureVisibility(false);
        }
    }

    private void updateGestureVisibility(boolean isDisabled) {
        int visibility = isDisabled ? View.GONE : View.VISIBLE;
        mGestureDelayBar.setVisibility(visibility);
        mGestureDelayText.setVisibility(visibility);
        mGestureDelayDisplayText.setVisibility(visibility);
    }

    private void removeListeners() {
        mGyroSwitch.setOnCheckedChangeListener(null);
        mGyroXSwitch.setOnCheckedChangeListener(null);
        mGyroYSwitch.setOnCheckedChangeListener(null);
        mGestureSwitch.setOnCheckedChangeListener(null);
        mMouseGrabSwitch.setOnCheckedChangeListener(null);

        mGyroSensitivityBar.setOnSeekBarChangeListener(null);
        mMouseSpeedBar.setOnSeekBarChangeListener(null);
        mGestureDelayBar.setOnSeekBarChangeListener(null);
        mResolutionBar.setOnSeekBarChangeListener(null);
    }

    private void setupCancelButton() {
        setEndButtonListener(android.R.string.ok, v -> {
            mEditor.apply();
            disappear(true);
        });
    }

    /** Resets all settings to their original values */
    public void cancel() {
        // Reset all settings if we were editing
        if(isDisplaying()) {
            PREF_ENABLE_GYRO = mOriginalGyroEnabled;
            PREF_GYRO_INVERT_X = mOriginalGyroXEnabled;
            PREF_GYRO_INVERT_Y = mOriginalGyroYEnabled;
            PREF_DISABLE_GESTURES = mOriginalGestureDisabled;
            PREF_MOUSE_GRAB_FORCE = mOriginalMouseGrab;

            PREF_GYRO_SENSITIVITY = mOriginalGyroSensitivity;
            PREF_MOUSESPEED = mOriginalMouseSpeed;
            PREF_LONGPRESS_TRIGGER = mOriginalGestureDelay;
            PREF_SCALE_FACTOR = mOriginalResolution;

            onGyroStateChanged();
            onResolutionChanged();
        }

        disappear(true);
    }

    /** Called when the resolution is changed. Use {@link LauncherPreferences#PREF_SCALE_FACTOR} */
    public abstract void onResolutionChanged();

    /** Called when the gyro state is changed.
     * Use {@link LauncherPreferences#PREF_ENABLE_GYRO}
     * Use {@link LauncherPreferences#PREF_GYRO_INVERT_X}
     * Use {@link LauncherPreferences#PREF_GYRO_INVERT_Y}
     */
    public abstract void onGyroStateChanged();

}
