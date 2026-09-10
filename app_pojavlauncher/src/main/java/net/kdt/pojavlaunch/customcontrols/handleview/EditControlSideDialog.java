package net.kdt.pojavlaunch.customcontrols.handleview;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.kdt.SideDialogView;

import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.colorselector.ColorSelector;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.LauncherControlImportHost;
import net.kdt.pojavlaunch.customcontrols.commands.ChatCommandEngine;
import net.kdt.pojavlaunch.customcontrols.commands.CommandScriptHighlighter;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.utils.interfaces.SimpleItemSelectedListener;
import net.kdt.pojavlaunch.utils.interfaces.SimpleSeekBarListener;
import net.kdt.pojavlaunch.utils.interfaces.SimpleTextWatcher;

import java.util.List;

public class EditControlSideDialog extends SideDialogView {

    private final Spinner[] mKeycodeSpinners = new Spinner[4];
    public boolean internalChanges = false; // True when we programmatically change stuff.
    private final View.OnLayoutChangeListener mLayoutChangedListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (internalChanges) return;
            if (!isSheetBound()) return; // detached: no width/height fields to feed

            internalChanges = true;
            int width = (int) (safeParseFloat(mWidthEditText.getText().toString()));

            if (width >= 0 && Math.abs(right - width) > 1) {
                mWidthEditText.setText(String.valueOf(right - left));
            }
            int height = (int) (safeParseFloat(mHeightEditText.getText().toString()));
            if (height >= 0 && Math.abs(bottom - height) > 1) {
                mHeightEditText.setText(String.valueOf(bottom - top));
            }

            internalChanges = false;
        }
    };
    private EditText mNameEditText, mWidthEditText, mHeightEditText, mCommandEditText;
    private TextView mCommandTextView, mCommandStatusView;
    private View mCommandActionsRow, mCommandTemplatesButton, mCommandHistoryButton,
            mCommandValidateButton, mCommandImportExportButton;
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private Switch mToggleSwitch, mPassthroughSwitch, mSwipeableSwitch, mForwardLockSwitch, mAbsoluteTrackingSwitch;
    private Spinner mOrientationSpinner;
    private final TextView[] mKeycodeTextviews = new TextView[4];
    private SeekBar mStrokeWidthSeekbar, mCornerRadiusSeekbar, mAlphaSeekbar;
    // Phase 9 — FPS read-out look (only bound for the FPS element)
    private View mGroupFps;
    private Switch mFpsTransparentSwitch, mFpsUnitSwitch;
    private SeekBar mFpsTextSizeSeekbar;
    private TextView mFpsTextSizeValue;
    private int mFpsSavedBgColor = 0;
    private TextView mStrokePercentTextView, mCornerRadiusPercentTextView, mAlphaPercentTextView;
    private TextView mSelectBackgroundColor, mSelectStrokeColor;
    private ArrayAdapter<String> mAdapter;
    private List<String> mSpecialArray;
    private CheckBox mDisplayInGameCheckbox, mDisplayInMenuCheckbox;
    private View mLauncherImportSection, mImportImageButton;
    private View mPickAtlasButton, mIconClearButton, mIconTintApplyButton;
    private EditText mIconTintInput;
    private ControlInterface mCurrentlyEditedButton;

    // ── plain sheet (Phase 11: original simple column) ──
    //
    // Lifecycle contract, because this class has a non-obvious host:
    // SideDialogView throws the whole view tree away when the sheet is dismissed
    // (deflateLayout() nulls mDialogContent) while ControlLayout keeps THIS
    // object alive for reuse (mControlDialog is never dropped). So every binding
    // below is only valid between onInflate() and the next deflate, yet
    // ControlLayout.editControlButton() legitimately calls
    // setCurrentlyEditedButton()/loadValues() while nothing is bound: before the
    // very first appear(), and again on the next open after a dismissal. Those
    // calls must be deferred onto the fresh view tree, never dereferenced.
    // Reading mDialogContent straight away is the NPE that killed every reopen.
    private boolean mPendingButtonSync;
    private View mHeaderClose;
    private TextView mTitleView, mSubtitleView;
    private View mRowName, mRowSize, mRowOrientation, mRowIngame,
            mRowInMenu, mRowToggle, mRowPassThrough, mRowSwipeable, mRowForwardLock,
            mRowAbsoluteTracking, mSlotRowTop, mSlotRowBottom, mGroupOpacity, mGroupCorner,
            mGroupStroke, mCommandBlock;
    // Decorative textviews
    private TextView mOrientationTextView, mMappingTextView, mNameTextView,
            mCornerRadiusTextView, mVisibilityTextView, mSizeTextview, mSizeXTextView;

    // Color selector related stuff
    private ColorSelector mColorSelector;
    private final ViewGroup mParent;

    public EditControlSideDialog(Context context, ViewGroup parent) {
        super(context, parent, R.layout.dialog_control_button_setting);
        mParent = parent;
    }

    @Override
    protected void onInflate() {
        bindLayout();
        buildColorSelector();
        loadAdapter();
        setupRealTimeListeners();
        if (mHeaderClose != null) mHeaderClose.setOnClickListener(v -> closeLayer());
        setupRowTargets();
        // Header and host-specific rows — setCurrentlyEditedButton() usually runs a
        // frame before this on the very first open, so re-derive them here instead
        // of shipping an unlabelled sheet.
        applyEditedButtonContext();
        if (mPendingButtonSync) {
            mPendingButtonSync = false;
            if (mCurrentlyEditedButton != null)
                mCurrentlyEditedButton.loadEditValues(EditControlSideDialog.this);
        }
        // Rows whose visibility depends on the host (icon import) are re-derived
        // by applyEditedButtonContext() above, so nothing else has to be replayed.
    }

    /** {@code true} while the base class holds an inflated content tree for us. */
    private boolean isSheetBound() {
        return mDialogContent != null;
    }

    /**
     * Close the visible layer exactly the way a tap on empty canvas does: the
     * colour selector first, then the sheet itself — and when the sheet is the
     * last layer, let the ControlLayout tear down the handle + action row with it.
     */
    public void closeLayer() {
        if (mColorSelector != null && mColorSelector.isDisplaying()) {
            disappearColor();
            return;
        }
        if (mParent instanceof ControlLayout) ((ControlLayout) mParent).removeEditWindow();
        else disappear(true);
    }

    /**
     * Rows are the touch targets for their switch / checkbox, so a tap anywhere
     * on the line works and not only on the 30dp control at its end.
     */
    private void setupRowTargets() {
        bindToggleRow(R.id.btnedit_row_toggle, mToggleSwitch);
        bindToggleRow(R.id.btnedit_row_passthru, mPassthroughSwitch);
        bindToggleRow(R.id.btnedit_row_swipeable, mSwipeableSwitch);
        bindToggleRow(R.id.btnedit_row_forwardlock, mForwardLockSwitch);
        bindToggleRow(R.id.btnedit_row_abstrack, mAbsoluteTrackingSwitch);
        bindCheckRow(R.id.btnedit_row_ingame, mDisplayInGameCheckbox);
        bindCheckRow(R.id.btnedit_row_inmenu, mDisplayInMenuCheckbox);
    }

    /** Bigger touch target: the row toggles the switch, the switch keeps the logic. */
    private void bindToggleRow(int rowId, final android.widget.Switch target) {
        View row = mDialogContent.findViewById(rowId);
        if (row == null || target == null) return;
        row.setOnClickListener(v -> {
            if (row.getVisibility() != VISIBLE) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            target.setChecked(!target.isChecked());
        });
    }

    private void bindCheckRow(int rowId, final CheckBox target) {
        View row = mDialogContent.findViewById(rowId);
        if (row == null || target == null) return;
        row.setOnClickListener(v -> {
            if (row.getVisibility() != VISIBLE) return;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            target.setChecked(!target.isChecked());
        });
    }

    @Override
    protected void onDestroy() {
        if (mColorSelector != null) mColorSelector.disappear(true);
        // The base class deflates right after this: nothing may be painted onto a
        // detached tree, so every binding is dropped and re-taken in onInflate().
        mHeaderClose = null;
        mTitleView = null;
        mSubtitleView = null;
    }

    private void buildColorSelector() {
        mColorSelector = new ColorSelector(mParent.getContext(), mParent, null);
    }

    /**
     * Slide the layout into the visible screen area
     */
    public void appearColor(boolean fromRight, int color) {
        if (mColorSelector == null || !isSheetBound()) return;
        mColorSelector.show(fromRight, color == -1 ? Color.WHITE : color);
    }

    /**
     * Slide out the layout
     */
    public void disappearColor() {
        if (mColorSelector == null) return;
        mColorSelector.disappear(false);
    }

    /**
     * Slide out the first visible layer.
     *
     * @return True if the last layer is disappearing
     */
    public boolean disappearLayer() {
        if (mColorSelector != null && mColorSelector.isDisplaying()) {
            disappearColor();
            return false;
        } else {
            disappear(false);
            return true;
        }
    }

    /**
     * Switch the panels position if needed
     */
    public void adaptPanelPosition() {
        if (mDisplaying && mCurrentlyEditedButton != null
                && mCurrentlyEditedButton.getControlView() != null) {
            boolean isAtRight = mCurrentlyEditedButton.getControlView().getX() + mCurrentlyEditedButton.getControlView().getWidth() / 2f < currentDisplayMetrics.widthPixels / 2f;
            appear(isAtRight);
            if (mColorSelector.isDisplaying()) {
                // keep the picker on the edge opposite to the sheet after a re-dock
                Tools.runOnUiThread(() -> appearColor(!isAtRight, mCurrentlyEditedButton.getProperties().bgColor));
            }
        }
    }

    private void renderFpsTextSizeLabel(int sp) {
        if (mFpsTextSizeValue == null) return;
        mFpsTextSizeValue.setText(sp <= 0 ? "Auto" : sp + " sp");
    }

    public static void setPercentageText(TextView textView, int progress) {
        textView.setText(textView.getContext().getString(R.string.percent_format, progress));
    }

    /* LOADING VALUES */

    /**
     * Load values for basic control data
     */
    public void loadValues(ControlData data) {
        if (!isSheetBound()) {
            // ControlLayout pushes values before/around appear(); replaying them
            // from onInflate() keeps the sheet in sync without touching null views.
            mPendingButtonSync = true;
            return;
        }
        setDefaultVisibilitySetting();
        if (mGroupFps != null) mGroupFps.setVisibility(data.isFpsControl() ? VISIBLE : GONE);
        if (data.isFpsControl()) {
            // A read-out has no keys, no script and no press behaviour to tune, so
            // those rows are hidden instead of being shown empty. (This used to
            // also drop the KEYS/MORE tabs; there are no tabs any more.)
            if (mCommandTextView != null) mCommandTextView.setVisibility(GONE);
            if (mCommandEditText != null) mCommandEditText.setVisibility(GONE);
            if (mCommandActionsRow != null) mCommandActionsRow.setVisibility(GONE);
            if (mCommandStatusView != null) mCommandStatusView.setVisibility(GONE);
            if (mCommandBlock != null) mCommandBlock.setVisibility(GONE);
            // keys / press behaviour rows make no sense for a read-out
            mSlotRowTop.setVisibility(GONE);
            mSlotRowBottom.setVisibility(GONE);
            mMappingTextView.setVisibility(GONE);
            mRowToggle.setVisibility(GONE);
            mRowPassThrough.setVisibility(GONE);
            mRowSwipeable.setVisibility(GONE);
            // Phase 9: the element's own look settings
            boolean transparent = ((data.bgColor >>> 24) == 0) && data.strokeWidth <= 0f;
            if (mFpsTransparentSwitch != null) mFpsTransparentSwitch.setChecked(transparent);
            if (mFpsUnitSwitch != null) mFpsUnitSwitch.setChecked(data.fpsShowUnit);
            if (mFpsTextSizeSeekbar != null) {
                int sp = Math.round(data.fpsTextSize);
                mFpsTextSizeSeekbar.setProgress(sp <= 0 ? 0 : Math.max(9, Math.min(28, sp)));
            }
            renderFpsTextSizeLabel(Math.round(data.fpsTextSize));
        }

        mNameEditText.setText(data.name);
        mWidthEditText.setText(String.valueOf(data.getWidth()));
        mHeightEditText.setText(String.valueOf(data.getHeight()));

        mAlphaSeekbar.setProgress((int) (data.opacity * 100));
        mStrokeWidthSeekbar.setProgress((int) data.strokeWidth * 10);
        mCornerRadiusSeekbar.setProgress((int) data.cornerRadius);

        setPercentageText(mAlphaPercentTextView, (int) (data.opacity * 100));
        setPercentageText(mStrokePercentTextView, (int) data.strokeWidth * 10);
        setPercentageText(mCornerRadiusPercentTextView, (int) data.cornerRadius);

        mToggleSwitch.setChecked(data.isToggle);
        mPassthroughSwitch.setChecked(data.passThruEnabled);
        mSwipeableSwitch.setChecked(data.isSwipeable);

        mDisplayInGameCheckbox.setChecked(data.displayInGame);
        mDisplayInMenuCheckbox.setChecked(data.displayInMenu);

        // Automation scripts persist verbatim in ControlData.command.
        String commandText = data.command == null ? "" : data.command;
        if (!commandText.isEmpty()) {
            net.kdt.pojavlaunch.customcontrols.commands.ChatCommandEngine
                    .recordHistory(mDialogContent.getContext(), commandText);
        }

        for (int i = 0; i < data.keycodes.length; i++) {
            if (data.keycodes[i] < 0) {
                // Clamped on purpose: a layout file from another build can carry a
                // special keycode this build does not know, and an out-of-range
                // selection would throw while merely opening the sheet.
                int specialSelection = data.keycodes[i] + mSpecialArray.size();
                mKeycodeSpinners[i].setSelection(Math.max(0,
                        Math.min(specialSelection, mAdapter.getCount() - 1)));
            } else {
                mKeycodeSpinners[i].setSelection(EfficientAndroidLWJGLKeycode.getIndexByValue(data.keycodes[i]) + mSpecialArray.size());
            }
        }

        // Chat command field is only relevant for the "Command" special button
        mCommandEditText.setText(data.command == null ? "" : data.command);
        updateCommandVisibility();
    }

    /**
     * Load values for extended control data
     */
    public void loadValues(ControlDrawerData data) {
        if (!isSheetBound()) {
            // ControlLayout pushes values before/around appear(); replaying them
            // from onInflate() keeps the sheet in sync without touching null views.
            mPendingButtonSync = true;
            return;
        }
        loadValues(data.properties);

        mOrientationSpinner.setSelection(
                ControlDrawerData.orientationToInt(data.orientation));

        // A group has no keys of its own — the whole key grid goes away.
        mSlotRowTop.setVisibility(GONE);
        mSlotRowBottom.setVisibility(GONE);
        mMappingTextView.setVisibility(GONE);

        mRowOrientation.setVisibility(VISIBLE);
        mOrientationTextView.setVisibility(VISIBLE);
        mOrientationSpinner.setVisibility(VISIBLE);

        mRowSwipeable.setVisibility(GONE);
        mRowPassThrough.setVisibility(GONE);
        mRowToggle.setVisibility(GONE);

    }

    /**
     * Load values for the joystick
     */
    public void loadJoystickValues(ControlJoystickData data) {
        if (!isSheetBound()) {
            // ControlLayout pushes values before/around appear(); replaying them
            // from onInflate() keeps the sheet in sync without touching null views.
            mPendingButtonSync = true;
            return;
        }
        loadValues(data);

        mSlotRowTop.setVisibility(GONE);
        mSlotRowBottom.setVisibility(GONE);
        mMappingTextView.setVisibility(GONE);

        // A stick has no label to name and no corners to round.
        mRowName.setVisibility(GONE);
        mGroupCorner.setVisibility(GONE);

        mRowSwipeable.setVisibility(GONE);
        mRowPassThrough.setVisibility(GONE);
        mRowToggle.setVisibility(GONE);

        mRowForwardLock.setVisibility(VISIBLE);
        mForwardLockSwitch.setChecked(data.forwardLock);

        mRowAbsoluteTracking.setVisibility(VISIBLE);
        mAbsoluteTrackingSwitch.setChecked(data.absolute);

    }

    /**
     * Load values for sub buttons
     */
    public void loadSubButtonValues(ControlData data, ControlDrawerData.Orientation drawerOrientation) {
        if (!isSheetBound()) {
            // ControlLayout pushes values before/around appear(); replaying them
            // from onInflate() keeps the sheet in sync without touching null views.
            mPendingButtonSync = true;
            return;
        }
        loadValues(data);

        // Size linked to the parent drawer depending on the drawer settings
        if(drawerOrientation != ControlDrawerData.Orientation.FREE){
            mRowSize.setVisibility(GONE);
            mSizeTextview.setVisibility(GONE);
            mSizeXTextView.setVisibility(GONE);
            mWidthEditText.setVisibility(GONE);
            mHeightEditText.setVisibility(GONE);
        }

        // No conditional, already depends on the parent drawer visibility
        mRowIngame.setVisibility(GONE);
        mRowInMenu.setVisibility(GONE);
        mVisibilityTextView.setVisibility(GONE);
        mDisplayInMenuCheckbox.setVisibility(GONE);
        mDisplayInGameCheckbox.setVisibility(GONE);

    }

    private void loadAdapter() {
        //Initialize adapter for keycodes
        mAdapter = new ArrayAdapter<>(mDialogContent.getContext(), R.layout.item_centered_textview);
        mSpecialArray = ControlData.buildSpecialButtonArray();

        mAdapter.addAll(mSpecialArray);
        mAdapter.addAll(EfficientAndroidLWJGLKeycode.generateKeyName());
        mAdapter.setDropDownViewResource(R.layout.item_editor_dropdown);

        for (Spinner spinner : mKeycodeSpinners) {
            spinner.setAdapter(mAdapter);
        }

        // Orientation spinner
        ArrayAdapter<ControlDrawerData.Orientation> adapter = new ArrayAdapter<>(mDialogContent.getContext(), android.R.layout.simple_spinner_item);
        adapter.addAll(ControlDrawerData.getOrientations());
        adapter.setDropDownViewResource(R.layout.item_editor_dropdown);

        mOrientationSpinner.setAdapter(adapter);
    }

    /**
     * Show the chat-command input row only when the currently edited button
     * uses the {@link ControlData#SPECIALBTN_CHATCOMMAND} special keycode.
     */
    private void updateCommandVisibility() {
        boolean isCommand = false;
        for (int keycode : mCurrentlyEditedButton.getProperties().keycodes) {
            if (keycode == ControlData.SPECIALBTN_CHATCOMMAND) {
                isCommand = true;
                break;
            }
        }
        // A read-out has no script to edit: even a tab switch that asks for this
        // block again must not resurrect the command row for an FPS control.
        if (mCurrentlyEditedButton.getProperties().isFpsControl()) isCommand = false;
        int visibility = isCommand ? View.VISIBLE : View.GONE;
        if (mCommandBlock != null) mCommandBlock.setVisibility(visibility);
        mCommandTextView.setVisibility(visibility);
        mCommandEditText.setVisibility(visibility);
        if (mCommandActionsRow != null) mCommandActionsRow.setVisibility(visibility);
        if (mCommandStatusView != null) mCommandStatusView.setVisibility(visibility);
        if (isCommand) updateCommandStatus(null);
    }

    /**
     * Every grouped row/panel of the new sheet, restored explicitly instead of
     * blindly flipping direct children (the panel swap nests the controls now).
     * The per-control-type methods below then hide what does not apply.
     */
    private void setDefaultVisibilitySetting() {
        if (!isSheetBound()) return;
        View[] reset = { mRowName, mRowSize, mGroupOpacity, mGroupStroke, mGroupCorner,
                mSlotRowTop, mSlotRowBottom, mMappingTextView, mRowIngame, mRowInMenu,
                mVisibilityTextView, mRowToggle, mRowPassThrough, mRowSwipeable,
                mCommandTextView, mCommandEditText, mCommandActionsRow, mCommandStatusView };
        for (View v : reset) {
            if (v != null) v.setVisibility(VISIBLE);
        }
        mRowOrientation.setVisibility(GONE);
        mRowForwardLock.setVisibility(GONE);
        mRowAbsoluteTracking.setVisibility(GONE);
        // The mapping spinners stay parked UNDER their key chips — visible would
        // cover the label. They are only ever opened through the chip click.
        for (Spinner spinner : mKeycodeSpinners) {
            spinner.setVisibility(View.INVISIBLE);
        }
        mOrientationSpinner.setVisibility(GONE);
        mOrientationTextView.setVisibility(GONE);
        for (TextView keycodeView : mKeycodeTextviews) {
            keycodeView.setVisibility(VISIBLE);
        }
    }

    /** Validate silently while typing, loudly when Validate is tapped. */
    private void updateCommandStatus(@androidx.annotation.Nullable String forcedMessage) {
        if (!isSheetBound() || mCommandStatusView == null || mCommandEditText == null) return;
        Context ctx = mDialogContent.getContext();
        String script = mCommandEditText.getText() != null
                ? mCommandEditText.getText().toString() : "";
        ChatCommandEngine.ValidationResult result = ChatCommandEngine.validate(script);

        if (forcedMessage != null) {
            mCommandStatusView.setText(forcedMessage);
            mCommandStatusView.setTextColor(0xFFBAC0C8);
        } else if (!result.isValid()) {
            mCommandStatusView.setText(ctx.getString(R.string.customctrl_command_error_line,
                    result.errorLine, result.error));
            mCommandStatusView.setTextColor(0xFFDE9A96);
        } else if (result.steps.isEmpty()) {
            mCommandStatusView.setText(R.string.customctrl_command_empty);
            mCommandStatusView.setTextColor(0xFF868D97);
        } else {
            mCommandStatusView.setText(ctx.getString(R.string.customctrl_command_ready,
                    result.steps.size()));
            mCommandStatusView.setTextColor(0xFFA9C6B4);
        }
    }

    private void bindLayout() {
        mNameEditText = mDialogContent.findViewById(R.id.editName_editText);
        mWidthEditText = mDialogContent.findViewById(R.id.editSize_editTextX);
        mHeightEditText = mDialogContent.findViewById(R.id.editSize_editTextY);
        mToggleSwitch = mDialogContent.findViewById(R.id.checkboxToggle);
        mPassthroughSwitch = mDialogContent.findViewById(R.id.checkboxPassThrough);
        mSwipeableSwitch = mDialogContent.findViewById(R.id.checkboxSwipeable);
        mForwardLockSwitch = mDialogContent.findViewById(R.id.checkboxForwardLock);
        mAbsoluteTrackingSwitch = mDialogContent.findViewById(R.id.checkboxAbsoluteFingerTracking);
        mKeycodeSpinners[0] = mDialogContent.findViewById(R.id.editMapping_spinner_1);
        mKeycodeSpinners[1] = mDialogContent.findViewById(R.id.editMapping_spinner_2);
        mKeycodeSpinners[2] = mDialogContent.findViewById(R.id.editMapping_spinner_3);
        mKeycodeSpinners[3] = mDialogContent.findViewById(R.id.editMapping_spinner_4);
        mKeycodeTextviews[0] = mDialogContent.findViewById(R.id.mapping_1_textview);
        mKeycodeTextviews[1] = mDialogContent.findViewById(R.id.mapping_2_textview);
        mKeycodeTextviews[2] = mDialogContent.findViewById(R.id.mapping_3_textview);
        mKeycodeTextviews[3] = mDialogContent.findViewById(R.id.mapping_4_textview);
        mOrientationSpinner = mDialogContent.findViewById(R.id.editOrientation_spinner);
        mStrokeWidthSeekbar = mDialogContent.findViewById(R.id.editStrokeWidth_seekbar);
        mCornerRadiusSeekbar = mDialogContent.findViewById(R.id.editCornerRadius_seekbar);
        mAlphaSeekbar = mDialogContent.findViewById(R.id.editButtonOpacity_seekbar);
        mSelectBackgroundColor = mDialogContent.findViewById(R.id.editBackgroundColor_textView);
        mSelectStrokeColor = mDialogContent.findViewById(R.id.editStrokeColor_textView);
        mStrokePercentTextView = mDialogContent.findViewById(R.id.editStrokeWidth_textView_percent);
        mAlphaPercentTextView = mDialogContent.findViewById(R.id.editButtonOpacity_textView_percent);
        mCornerRadiusPercentTextView = mDialogContent.findViewById(R.id.editCornerRadius_textView_percent);
        mDisplayInGameCheckbox = mDialogContent.findViewById(R.id.visibility_game_checkbox);
        mDisplayInMenuCheckbox = mDialogContent.findViewById(R.id.visibility_menu_checkbox);
        mLauncherImportSection = mDialogContent.findViewById(R.id.launcher_control_import_section);
        mGroupFps = mDialogContent.findViewById(R.id.btnedit_group_fps);
        mFpsTransparentSwitch = mDialogContent.findViewById(R.id.checkboxFpsTransparent);
        mFpsUnitSwitch = mDialogContent.findViewById(R.id.checkboxFpsUnit);
        mFpsTextSizeSeekbar = mDialogContent.findViewById(R.id.editFpsTextSize_seekbar);
        mFpsTextSizeValue = mDialogContent.findViewById(R.id.editFpsTextSize_textView_percent);
        mImportImageButton = mDialogContent.findViewById(R.id.control_import_image);
        mPickAtlasButton = mDialogContent.findViewById(R.id.control_pick_atlas);
        mIconClearButton = mDialogContent.findViewById(R.id.control_icon_clear);
        mIconTintApplyButton = mDialogContent.findViewById(R.id.control_icon_tint_apply);
        mIconTintInput = mDialogContent.findViewById(R.id.control_icon_tint_input);

        mCommandTextView = mDialogContent.findViewById(R.id.editCommand_textView);
        mCommandEditText = mDialogContent.findViewById(R.id.editCommand_editText);
        mCommandActionsRow = mDialogContent.findViewById(R.id.editCommand_actions);
        mCommandStatusView = mDialogContent.findViewById(R.id.editCommand_status);
        mCommandTemplatesButton = mDialogContent.findViewById(R.id.editCommand_templates);
        mCommandHistoryButton = mDialogContent.findViewById(R.id.editCommand_history);
        mCommandValidateButton = mDialogContent.findViewById(R.id.editCommand_validate);
        mCommandImportExportButton = mDialogContent.findViewById(R.id.editCommand_import_export);

        // Live automation syntax highlighting (zero text mutation).
        CommandScriptHighlighter.attach(mCommandEditText);

        // header close + row containers
        mHeaderClose = mDialogContent.findViewById(R.id.btnedit_close);
        mTitleView = mDialogContent.findViewById(R.id.btnedit_title);
        mSubtitleView = mDialogContent.findViewById(R.id.btnedit_subtitle);
        mRowName = mDialogContent.findViewById(R.id.btnedit_row_name);
        mRowSize = mDialogContent.findViewById(R.id.btnedit_row_size);
        mRowOrientation = mDialogContent.findViewById(R.id.btnedit_row_orientation);
        mRowIngame = mDialogContent.findViewById(R.id.btnedit_row_ingame);
        mRowInMenu = mDialogContent.findViewById(R.id.btnedit_row_inmenu);
        mRowToggle = mDialogContent.findViewById(R.id.btnedit_row_toggle);
        mRowPassThrough = mDialogContent.findViewById(R.id.btnedit_row_passthru);
        mRowSwipeable = mDialogContent.findViewById(R.id.btnedit_row_swipeable);
        mRowForwardLock = mDialogContent.findViewById(R.id.btnedit_row_forwardlock);
        mRowAbsoluteTracking = mDialogContent.findViewById(R.id.btnedit_row_abstrack);
        mSlotRowTop = mDialogContent.findViewById(R.id.btnedit_slot_row_top);
        mSlotRowBottom = mDialogContent.findViewById(R.id.btnedit_slot_row_bottom);
        mGroupOpacity = mDialogContent.findViewById(R.id.btnedit_group_opacity);
        mGroupCorner = mDialogContent.findViewById(R.id.btnedit_group_corner);
        mGroupStroke = mDialogContent.findViewById(R.id.btnedit_group_stroke);
        mCommandBlock = mDialogContent.findViewById(R.id.btnedit_command_block);

        //Decorative stuff
        mMappingTextView = mDialogContent.findViewById(R.id.editMapping_textView);
        mOrientationTextView = mDialogContent.findViewById(R.id.editOrientation_textView);
        mNameTextView = mDialogContent.findViewById(R.id.editName_textView);
        mCornerRadiusTextView = mDialogContent.findViewById(R.id.editCornerRadius_textView);
        mVisibilityTextView = mDialogContent.findViewById(R.id.visibility_textview);
        mSizeTextview = mDialogContent.findViewById(R.id.editSize_textView);
        mSizeXTextView = mDialogContent.findViewById(R.id.editSize_x_textView);
    }

    /**
     * A long function linking all the displayed data on the popup and,
     * the currently edited mCurrentlyEditedButton
     * @noinspection SuspiciousNameCombination
     */
    private void setupRealTimeListeners() {
        if (mImportImageButton != null) mImportImageButton.setOnClickListener(v -> {
            Context context = mParent.getContext();
            if (context instanceof LauncherControlImportHost && mCurrentlyEditedButton != null) {
                ((LauncherControlImportHost) context).requestControlImage(mCurrentlyEditedButton);
            }
        });

        // ── Dynamic Button Asset System ──────────────────────────────
        if (mPickAtlasButton != null) mPickAtlasButton.setOnClickListener(v -> {
            if (mCurrentlyEditedButton == null) return;
            McAtlasPickerDialog.show(mParent.getContext(), (source, u, vv, w, h) -> {
                net.kdt.pojavlaunch.customcontrols.ControlData props = mCurrentlyEditedButton.getProperties();
                props.iconType = net.kdt.pojavlaunch.customcontrols.ControlData.ICON_MINECRAFT_ATLAS;
                props.atlasSource = source;
                props.atlasRect = new int[]{u, vv, w, h};
                props.iconPath = null;
                props.customIcon = null;
                mCurrentlyEditedButton.setProperties(props);
            });
        });

        if (mIconTintApplyButton != null) mIconTintApplyButton.setOnClickListener(v -> {
            if (mCurrentlyEditedButton == null) return;
            net.kdt.pojavlaunch.customcontrols.ControlData props = mCurrentlyEditedButton.getProperties();
            String raw = mIconTintInput == null || mIconTintInput.getText() == null
                    ? "" : mIconTintInput.getText().toString().trim();
            if (raw.isEmpty()) {
                props.iconTint = 0;
            } else {
                try {
                    if (!raw.startsWith("#")) raw = "#" + raw;
                    props.iconTint = android.graphics.Color.parseColor(raw);
                } catch (IllegalArgumentException e) {
                    android.widget.Toast.makeText(mParent.getContext(),
                            "Invalid hex color — use #RRGGBB", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            mCurrentlyEditedButton.setProperties(props);
        });

        if (mIconClearButton != null) mIconClearButton.setOnClickListener(v -> {
            if (mCurrentlyEditedButton == null) return;
            net.kdt.pojavlaunch.customcontrols.ControlData props = mCurrentlyEditedButton.getProperties();
            props.iconType = net.kdt.pojavlaunch.customcontrols.ControlData.ICON_NONE;
            props.iconPath = null;
            props.atlasSource = null;
            props.atlasRect = null;
            props.iconTint = 0;
            props.customIcon = null;
            mCurrentlyEditedButton.setProperties(props);
            if (mIconTintInput != null) mIconTintInput.setText("");
        });
        mNameEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            mCurrentlyEditedButton.getProperties().name = s.toString();

            // Cheap and unoptimized, doesn't break the abstraction layer
            mCurrentlyEditedButton.setProperties(mCurrentlyEditedButton.getProperties(), false);
        });

        mWidthEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            float width = safeParseFloat(s.toString());
            if (width >= 0) {
                mCurrentlyEditedButton.getProperties().setWidth(width);
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    // Joysticks are square
                     mCurrentlyEditedButton.getProperties().setHeight(width);
                }
                mCurrentlyEditedButton.updateProperties();
            }
        });

        mHeightEditText.addTextChangedListener((SimpleTextWatcher) s -> {
            if (internalChanges) return;

            float height = safeParseFloat(s.toString());
            if (height >= 0) {
                mCurrentlyEditedButton.getProperties().setHeight(height);
                if (mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData) {
                    // Joysticks are square
                    mCurrentlyEditedButton.getProperties().setWidth(height);
                }
                mCurrentlyEditedButton.updateProperties();
            }
        });

        mSwipeableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().isSwipeable = isChecked;
        });
        mToggleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().isToggle = isChecked;
        });
        mPassthroughSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().passThruEnabled = isChecked;
        });
        mForwardLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            if(mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData){
                ((ControlJoystickData) mCurrentlyEditedButton.getProperties()).forwardLock = isChecked;
            }
        });
        mAbsoluteTrackingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            if(mCurrentlyEditedButton.getProperties() instanceof ControlJoystickData){
                ((ControlJoystickData) mCurrentlyEditedButton.getProperties()).absolute = isChecked;
            }
        });

        mAlphaSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().opacity = mAlphaSeekbar.getProgress() / 100f;
            mCurrentlyEditedButton.getControlView().setAlpha(mAlphaSeekbar.getProgress() / 100f);
            setPercentageText(mAlphaPercentTextView, progress);
        });

        mStrokeWidthSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().strokeWidth = mStrokeWidthSeekbar.getProgress() / 10F;
            mCurrentlyEditedButton.setBackground();
            setPercentageText(mStrokePercentTextView, progress);
        });

        // ── Phase 9: FPS read-out look ──
        if (mFpsTransparentSwitch != null) {
            mFpsTransparentSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (internalChanges || mCurrentlyEditedButton == null) return;
                ControlData props = mCurrentlyEditedButton.getProperties();
                if (isChecked) {
                    // remember the chip colour so switching back restores it
                    if ((props.bgColor >>> 24) != 0) mFpsSavedBgColor = props.bgColor;
                    props.bgColor = 0x00000000;
                    props.strokeWidth = 0f;
                } else {
                    props.bgColor = mFpsSavedBgColor != 0 ? mFpsSavedBgColor : 0x8C141519;
                }
                internalChanges = true;
                mStrokeWidthSeekbar.setProgress((int) props.strokeWidth * 10);
                setPercentageText(mStrokePercentTextView, (int) props.strokeWidth * 10);
                internalChanges = false;
                mCurrentlyEditedButton.setBackground();
                mCurrentlyEditedButton.setProperties(props, false);
            });
        }
        if (mFpsUnitSwitch != null) {
            mFpsUnitSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (internalChanges || mCurrentlyEditedButton == null) return;
                ControlData props = mCurrentlyEditedButton.getProperties();
                props.fpsShowUnit = isChecked;
                mCurrentlyEditedButton.setProperties(props, false);
            });
        }
        if (mFpsTextSizeSeekbar != null) {
            mFpsTextSizeSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
                if (internalChanges || mCurrentlyEditedButton == null) return;
                int sp = progress < 9 ? 0 : progress; // 0..8 → automatic
                ControlData props = mCurrentlyEditedButton.getProperties();
                props.fpsTextSize = sp;
                renderFpsTextSizeLabel(sp);
                mCurrentlyEditedButton.setProperties(props, false);
            });
        }

        mCornerRadiusSeekbar.setOnSeekBarChangeListener((SimpleSeekBarListener) (seekBar, progress, fromUser) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().cornerRadius = mCornerRadiusSeekbar.getProgress();
            mCurrentlyEditedButton.setBackground();
            setPercentageText(mCornerRadiusPercentTextView, progress);
        });


        for (int i = 0; i < mKeycodeSpinners.length; ++i) {
            int finalI = i;
            mKeycodeTextviews[i].setOnClickListener(v -> mKeycodeSpinners[finalI].performClick());

            mKeycodeSpinners[i].setOnItemSelectedListener((SimpleItemSelectedListener) (parent, view, position, id) -> {
                // Side note, spinner listeners are fired later than all the other ones.
                // Meaning the internalChanges bool is useless here.
                if (position < mSpecialArray.size()) {
                    mCurrentlyEditedButton.getProperties().keycodes[finalI] = mKeycodeSpinners[finalI].getSelectedItemPosition() - mSpecialArray.size();
                } else {
                    mCurrentlyEditedButton.getProperties().keycodes[finalI] = EfficientAndroidLWJGLKeycode.getValueByIndex(mKeycodeSpinners[finalI].getSelectedItemPosition() - mSpecialArray.size());
                }
                mKeycodeTextviews[finalI].setText((String) mKeycodeSpinners[finalI].getSelectedItem());
                updateCommandVisibility();
            });
        }


        mOrientationSpinner.setOnItemSelectedListener((SimpleItemSelectedListener) (parent, view, position, id) -> {
            // Side note, spinner listeners are fired later than all the other ones.
            // Meaning the internalChanges bool is useless here.

            if (mCurrentlyEditedButton instanceof ControlDrawer) {
                ((ControlDrawer) mCurrentlyEditedButton).drawerData.orientation = ControlDrawerData.intToOrientation(mOrientationSpinner.getSelectedItemPosition());
                ((ControlDrawer) mCurrentlyEditedButton).syncButtons();
            }
        });

        mDisplayInGameCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().displayInGame = isChecked;
        });

        mDisplayInMenuCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().displayInMenu = isChecked;
        });

        mCommandEditText.addTextChangedListener((SimpleTextWatcher) text -> {
            if (internalChanges) return;
            mCurrentlyEditedButton.getProperties().command = text.toString();
            updateCommandStatus(null);
        });

        if (mCommandTemplatesButton != null) {
            mCommandTemplatesButton.setOnClickListener(v -> showCommandTemplates(v));
        }
        if (mCommandHistoryButton != null) {
            mCommandHistoryButton.setOnClickListener(v -> showCommandHistory(v));
        }
        if (mCommandValidateButton != null) {
            mCommandValidateButton.setOnClickListener(v -> showCommandPreview(v));
        }
        if (mCommandImportExportButton != null) {
            mCommandImportExportButton.setOnClickListener(v -> showCommandImportExport(v));
        }

        mSelectStrokeColor.setOnClickListener(v -> {
            mColorSelector.setAlphaEnabled(false);
            mColorSelector.setColorSelectionListener(color -> {
                mCurrentlyEditedButton.getProperties().strokeColor = color;
                mCurrentlyEditedButton.setBackground();
            });
            // Phase 9 fix: the picker used to slide in on the SAME side as this
            // sheet and cover it. It now takes the opposite edge.
            appearColor(!isAtRight(), mCurrentlyEditedButton.getProperties().strokeColor);
        });

        mSelectBackgroundColor.setOnClickListener(v -> {
            mColorSelector.setAlphaEnabled(true);
            mColorSelector.setColorSelectionListener(color -> {
                mCurrentlyEditedButton.getProperties().bgColor = color;
                mCurrentlyEditedButton.setBackground();
            });
            appearColor(!isAtRight(), mCurrentlyEditedButton.getProperties().bgColor);
        });
    }

    // ═══════════════ Phase 3 — Command Studio editor ═══════════════

    private void showCommandTemplates(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add("Welcome message");
        menu.getMenu().add("Teleport home");
        menu.getMenu().add("Speed boost");
        menu.getMenu().add("Kit starter");
        menu.getMenu().add("Variable + condition demo");
        menu.setOnMenuItemClickListener(item -> {
            String template;
            CharSequence title = item.getTitle();
            if ("Teleport home".contentEquals(title)) {
                template = "/spawnpoint ${player}\ndelay:250\n/tp ${player} ~ 80 ~";
            } else if ("Speed boost".contentEquals(title)) {
                template = "/effect give ${player} minecraft:speed 30 1\ndelay:500\n/title ${player} actionbar {\"text\":\"Speed ready\",\"color\":\"aqua\"}";
            } else if ("Kit starter".contentEquals(title)) {
                template = "/give ${player} minecraft:stone_sword 1\n/give ${player} minecraft:bread 8\nrepeat:2 /tell ${player} Starter kit delivered";
            } else if ("Variable + condition demo".contentEquals(title)) {
                template = "var:mode=pro\nif:${mode}==pro\n/say Pro mode active for ${player}";
            } else {
                template = "/say Welcome ${player}!\ndelay:250";
            }
            appendCommandScript(template);
            return true;
        });
        menu.show();
    }

    private void showCommandHistory(@NonNull View anchor) {
        java.util.List<String> history = ChatCommandEngine.getHistory(anchor.getContext());
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        if (history.isEmpty()) {
            menu.getMenu().add("No saved commands");
        } else {
            int i = 0;
            for (String script : history) {
                String label = script.replace('\n', ' ');
                if (label.length() > 42) label = label.substring(0, 42) + "…";
                menu.getMenu().add(0, i, i, label);
                i++;
            }
            menu.getMenu().add(0, 99, 99, "Clear history");
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 99) {
                ChatCommandEngine.clearHistory(anchor.getContext());
                return true;
            }
            int id = item.getItemId();
            if (id >= 0 && id < history.size()) {
                mCommandEditText.setText(history.get(id));
                mCommandEditText.setSelection(mCommandEditText.getText().length());
            }
            return true;
        });
        menu.show();
    }

    private void showCommandPreview(@NonNull View anchor) {
        String script = mCommandEditText.getText() != null
                ? mCommandEditText.getText().toString() : "";
        updateCommandStatus(null);
        java.util.List<String> lines = ChatCommandEngine.dryRun(anchor.getContext(), script);
        StringBuilder sb = new StringBuilder();
        int max = Math.min(lines.size(), 12);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append('\n');
            sb.append("• ").append(lines.get(i));
        }
        if (lines.size() > max) sb.append("\n… ").append(lines.size() - max).append(" more");
        new android.app.AlertDialog.Builder(anchor.getContext())
                .setTitle("Command test preview")
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showCommandImportExport(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.customctrl_command_export);
        menu.getMenu().add(0, 2, 1, R.string.customctrl_command_import);
        menu.setOnMenuItemClickListener(item -> {
            Context ctx = anchor.getContext();
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return true;
            if (item.getItemId() == 1) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("CS Command Script",
                        mCommandEditText.getText()));
                updateCommandStatus(ctx.getString(R.string.customctrl_command_exported));
            } else {
                android.content.ClipData clip = clipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) {
                    updateCommandStatus(ctx.getString(R.string.customctrl_command_clipboard_empty));
                } else {
                    CharSequence pasted = clip.getItemAt(0).coerceToText(ctx);
                    if (pasted == null || pasted.length() == 0) {
                        updateCommandStatus(ctx.getString(R.string.customctrl_command_clipboard_empty));
                    } else {
                        mCommandEditText.setText(pasted);
                        mCommandEditText.setSelection(mCommandEditText.getText().length());
                        updateCommandStatus(ctx.getString(R.string.customctrl_command_imported));
                    }
                }
            }
            return true;
        });
        menu.show();
    }

    private void appendCommandScript(@NonNull String script) {
        CharSequence current = mCommandEditText.getText();
        String next = current == null || current.length() == 0
                ? script
                : current.toString().replaceAll("[\\s\\n]+$", "") + "\n" + script;
        mCommandEditText.setText(next);
        mCommandEditText.setSelection(mCommandEditText.getText().length());
    }

    private float safeParseFloat(String string) {
        float out = -1; // -1
        try {
            out = Float.parseFloat(string);
        } catch (NumberFormatException e) {
            Log.e("EditControlPopup", e.toString());
        }
        return out;
    }

    public void setCurrentlyEditedButton(ControlInterface button) {
        if (mCurrentlyEditedButton != null && mCurrentlyEditedButton.getControlView() != null)
            mCurrentlyEditedButton.getControlView().removeOnLayoutChangeListener(mLayoutChangedListener);
        mCurrentlyEditedButton = button;
        if (button != null && button.getControlView() != null)
            button.getControlView().addOnLayoutChangeListener(mLayoutChangedListener);
        if (!isSheetBound()) {
            // Nothing to paint on yet — onInflate() replays this for us.
            mPendingButtonSync = true;
            return;
        }
        applyEditedButtonContext();
    }

    /**
     * Header text, host-specific rows and the landing panel for the button that
     * is currently selected. Only safe to run on a bound sheet, and called from
     * both setCurrentlyEditedButton() and onInflate() so the two open orders —
     * "select then appear" and "appear then select" — end up identical.
     */
    private void applyEditedButtonContext() {
        final ControlInterface button = mCurrentlyEditedButton;
        if (button == null || !isSheetBound()) return;
        boolean launcherEditor = mParent.getContext() instanceof LauncherControlImportHost;
        boolean menuControl = false;
        for (int key : button.getProperties().keycodes) {
            if (key == ControlData.SPECIALBTN_MENU) { menuControl = true; break; }
        }
        if (mLauncherImportSection != null) {
            mLauncherImportSection.setVisibility(launcherEditor ? VISIBLE : GONE);
        }
        if (mImportImageButton != null) {
            mImportImageButton.setVisibility(launcherEditor ? VISIBLE : GONE);
        }
        // What am I editing? The header says it, so the sheet is never
        // a mystery box of sliders.
        if (mTitleView != null && button.getProperties() != null) {
            String name = button.getProperties().name;
            mTitleView.setText(name == null || name.trim().isEmpty()
                    ? "Button" : name.trim());
        }
        // Phase 11: the plain sheet has no subtitle line any more.
        if (mSubtitleView != null) mSubtitleView.setVisibility(GONE);
    }

}
