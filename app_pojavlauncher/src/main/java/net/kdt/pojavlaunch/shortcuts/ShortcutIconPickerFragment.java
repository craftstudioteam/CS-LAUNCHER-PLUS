package net.kdt.pojavlaunch.shortcuts;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configure and create a home screen shortcut for one profile.
 *
 * <p>Rebuilt from the original three-button dialog. It now offers an action
 * picker, an icon shape selector, adaptive-icon and badge toggles, a live preview
 * that mirrors what the home screen will show, and honest success feedback driven
 * by {@link ShortcutPinReceiver}.</p>
 */
public class ShortcutIconPickerFragment extends Fragment
        implements ShortcutPinReceiver.PinListener {

    public static final String TAG = "ShortcutIconPickerFragment";
    public static final String ARG_PROFILE_KEY = "profile_key";

    private static final String LOG_TAG = "ShortcutPicker";

    /** Icon source markers persisted in {@link ShortcutRecord#iconSource}. */
    private static final String SOURCE_PROFILE = "profile";
    private static final String SOURCE_SKIN = "skin";
    private static final String SOURCE_LOADER = "loader";
    private static final String SOURCE_CUSTOM = "custom";

    private String mProfileKey;
    private MinecraftProfile mProfile;

    // Views
    private TextInputEditText mNameInput;
    private ImageView mIconPreview;
    private TextView mLabelPreview;
    private TextView mSubtitle;
    private TextView mUnsupportedWarning;
    private Button mCreateButton;
    private RecyclerView mActionList;
    private MaterialButtonToggleGroup mShapeGroup;
    private MaterialSwitch mSwitchAdaptive;
    private MaterialSwitch mSwitchBadge;
    private MaterialSwitch mSwitchDynamic;
    private MaterialButton mBtnProfileIcon, mBtnSkinHead, mBtnLoaderIcon, mBtnCustom;

    private ShortcutActionAdapter mActionAdapter;

    // State
    /** Unbadged, unmasked artwork chosen by the user. */
    @Nullable
    private Bitmap mSourceBitmap;
    private String mIconSource = SOURCE_PROFILE;
    private ShortcutType mSelectedAction = ShortcutType.LAUNCH;
    private ShortcutIconRenderer.IconShape mShape =
            ShortcutIconRenderer.IconShape.SQUIRCLE;

    private final ActivityResultLauncher<Intent> mImagePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri imageUri = result.getData().getData();
                            if (imageUri != null) loadCustomImage(imageUri);
                        }
                    });

    public ShortcutIconPickerFragment() {
        super(R.layout.dialog_add_shortcut);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mProfileKey = getArguments().getString(ARG_PROFILE_KEY);
        }
        if (mProfileKey != null && LauncherProfiles.mainProfileJson != null
                && LauncherProfiles.mainProfileJson.profiles != null) {
            mProfile = LauncherProfiles.mainProfileJson.profiles.get(mProfileKey);
        }
        restoreLastUsedOptions();
    }

    /** Bring back the shape and adaptive choice from the previous session. */
    private void restoreLastUsedOptions() {
        try {
            mShape = ShortcutIconRenderer.IconShape.fromId(
                    LauncherPreferences.DEFAULT_PREF.getString(
                            ShortcutPreferences.KEY_LAST_SHAPE, null));
        } catch (Exception ignored) {
            mShape = ShortcutIconRenderer.IconShape.SQUIRCLE;
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        bindViews(view);

        if (mProfile == null) {
            Toast.makeText(getContext(), R.string.shortcut_error, Toast.LENGTH_SHORT).show();
            goBack();
            return;
        }

        setupHeader();
        setupActionList();
        setupShapeGroup();
        setupSwitches();
        setupIconSourceButtons();
        setupNameInput();

        mCreateButton.setOnClickListener(v -> createShortcut());

        // Warn early if the home screen will refuse the request outright.
        boolean pinSupported = ProfileShortcutHelper.isPinningSupported(requireContext());
        mUnsupportedWarning.setVisibility(pinSupported ? View.GONE : View.VISIBLE);
        mCreateButton.setEnabled(pinSupported);

        loadProfileIcon();

        ShortcutPinReceiver.setListener(this);
    }

    @Override
    public void onDestroyView() {
        // Static listener would otherwise leak this fragment.
        ShortcutPinReceiver.clearListener(this);
        super.onDestroyView();
    }

    private void bindViews(@NonNull View view) {
        mNameInput = view.findViewById(R.id.shortcut_name_input);
        mIconPreview = view.findViewById(R.id.shortcut_icon_preview);
        mLabelPreview = view.findViewById(R.id.shortcut_label_preview);
        mSubtitle = view.findViewById(R.id.shortcut_subtitle);
        mUnsupportedWarning = view.findViewById(R.id.shortcut_unsupported_warning);
        mCreateButton = view.findViewById(R.id.btn_create_shortcut);
        mActionList = view.findViewById(R.id.shortcut_action_list);
        mShapeGroup = view.findViewById(R.id.shortcut_shape_group);
        mSwitchAdaptive = view.findViewById(R.id.shortcut_switch_adaptive);
        mSwitchBadge = view.findViewById(R.id.shortcut_switch_badge);
        mSwitchDynamic = view.findViewById(R.id.shortcut_switch_dynamic);
        mBtnProfileIcon = view.findViewById(R.id.btn_icon_profile);
        mBtnSkinHead = view.findViewById(R.id.btn_icon_skin_head);
        mBtnLoaderIcon = view.findViewById(R.id.btn_icon_loader);
        mBtnCustom = view.findViewById(R.id.btn_icon_custom);

        ImageButton back = view.findViewById(R.id.shortcut_back);
        if (back != null) back.setOnClickListener(v -> goBack());

        ImageButton manage = view.findViewById(R.id.shortcut_manage);
        if (manage != null) manage.setOnClickListener(v -> openManageScreen());
    }

    private void setupHeader() {
        if (mProfile.name != null) {
            mSubtitle.setText(getString(R.string.shortcut_add_subtitle_named, mProfile.name));
            mNameInput.setText(mProfile.name);
            mLabelPreview.setText(mProfile.name);
        }
    }

    private void setupActionList() {
        List<ShortcutType> actions = new ArrayList<>(Arrays.asList(ShortcutType.values()));
        List<ShortcutType> existing = collectExistingActions();

        mActionAdapter = new ShortcutActionAdapter(actions, mSelectedAction, existing,
                type -> {
                    mSelectedAction = type;
                    // The default label should follow the action, but never stomp
                    // on a name the user typed themselves.
                    applySuggestedNameIfUntouched();
                    updatePreview();
                });

        mActionList.setLayoutManager(new LinearLayoutManager(getContext()));
        mActionList.setAdapter(mActionAdapter);
        mActionList.setHasFixedSize(false);
    }

    /** Which actions already have a shortcut for this profile. */
    @NonNull
    private List<ShortcutType> collectExistingActions() {
        List<ShortcutType> existing = new ArrayList<>();
        if (getContext() == null || mProfileKey == null) return existing;
        for (ShortcutRecord record : ShortcutRegistry.findByProfile(requireContext(), mProfileKey)) {
            existing.add(record.getType());
        }
        return existing;
    }

    private void setupShapeGroup() {
        mShapeGroup.check(shapeToButtonId(mShape));
        mShapeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            mShape = buttonIdToShape(checkedId);
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putString(ShortcutPreferences.KEY_LAST_SHAPE, mShape.getId())
                    .apply();
            updatePreview();
        });
    }

    private void setupSwitches() {
        mSwitchAdaptive.setChecked(LauncherPreferences.DEFAULT_PREF
                .getBoolean(ShortcutPreferences.KEY_LAST_ADAPTIVE, true));
        mSwitchAdaptive.setOnCheckedChangeListener((b, checked) -> {
            LauncherPreferences.DEFAULT_PREF.edit()
                    .putBoolean(ShortcutPreferences.KEY_LAST_ADAPTIVE, checked)
                    .apply();
            updatePreview();
        });

        mSwitchBadge.setOnCheckedChangeListener((b, checked) -> updatePreview());

        mSwitchDynamic.setChecked(LauncherPreferences.DEFAULT_PREF
                .getBoolean(ShortcutPreferences.KEY_DYNAMIC_ENABLED, true));
        mSwitchDynamic.setOnCheckedChangeListener((b, checked) ->
                LauncherPreferences.DEFAULT_PREF.edit()
                        .putBoolean(ShortcutPreferences.KEY_DYNAMIC_ENABLED, checked)
                        .apply());
    }

    private void setupIconSourceButtons() {
        mBtnProfileIcon.setOnClickListener(v -> loadProfileIcon());
        mBtnSkinHead.setOnClickListener(v -> loadSkinHead());
        mBtnLoaderIcon.setOnClickListener(v -> loadLoaderIcon());
        mBtnCustom.setOnClickListener(v -> pickCustomImage());
    }

    private void setupNameInput() {
        mNameInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                // Keep the mock home screen tile in sync as the user types.
                mLabelPreview.setText(s.toString());
            }
        });
    }

    /**
     * Replace the label with one suggested by the action, unless the user has
     * already customised it.
     */
    private void applySuggestedNameIfUntouched() {
        if (mProfile == null || mProfile.name == null) return;

        String current = mNameInput.getText() != null
                ? mNameInput.getText().toString().trim() : "";

        // Only overwrite values we ourselves generated.
        boolean generated = current.isEmpty() || current.equals(mProfile.name);
        for (ShortcutType type : ShortcutType.values()) {
            if (current.equals(suggestedName(type))) {
                generated = true;
                break;
            }
        }
        if (!generated) return;

        mNameInput.setText(suggestedName(mSelectedAction));
    }

    /** Default label for an action, e.g. "Skyblock Mods". */
    @NonNull
    private String suggestedName(@NonNull ShortcutType type) {
        String base = mProfile != null && mProfile.name != null ? mProfile.name : "Minecraft";
        switch (type) {
            case MODS:
                return getString(R.string.shortcut_suggested_mods, base);
            case EDIT:
                return getString(R.string.shortcut_suggested_edit, base);
            case FOLDER:
                return getString(R.string.shortcut_suggested_folder, base);
            case OPEN_PROFILE:
            case LAUNCH:
            default:
                return base;
        }
    }

    // ─── Icon sources ──────────────────────────────────────────────────

    private void loadProfileIcon() {
        if (getContext() == null || mProfile == null) return;
        mIconSource = SOURCE_PROFILE;
        mSourceBitmap = ProfileShortcutHelper.resolveProfileBitmap(
                requireContext(), mProfileKey, mProfile);
        updateSourceButtonStates();
        updatePreview();
    }

    /** Use the mod loader glyph (Fabric, Forge, Quilt, OptiFine) as the artwork. */
    private void loadLoaderIcon() {
        if (getContext() == null || mProfile == null) return;
        mIconSource = SOURCE_LOADER;
        int iconRes = ProfileShortcutHelper.resolveLoaderIcon(mProfile.lastVersionId);
        Drawable drawable = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), iconRes);
        mSourceBitmap = ShortcutIconRenderer.drawableToBitmap(drawable);
        updateSourceButtonStates();
        updatePreview();
    }

    private void loadSkinHead() {
        if (getContext() == null) return;

        String username = getCurrentUsername();
        if (username == null) {
            Toast.makeText(getContext(), R.string.shortcut_no_account,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(getContext(), R.string.shortcut_downloading_skin,
                Toast.LENGTH_SHORT).show();

        PojavApplication.sExecutorService.execute(() -> {
            final Bitmap head = ShortcutSkinHeadHelper.getSkinHead(
                    requireContext().getApplicationContext(), username);

            Tools.runOnUiThread(() -> {
                if (!isAdded() || getContext() == null) return;
                if (head != null) {
                    mIconSource = SOURCE_SKIN;
                    mSourceBitmap = head;
                    updateSourceButtonStates();
                    updatePreview();
                } else {
                    Toast.makeText(getContext(), R.string.shortcut_skin_failed,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void pickCustomImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            mImagePickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.w(LOG_TAG, "No image picker available", e);
            Toast.makeText(getContext(), R.string.shortcut_image_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadCustomImage(@NonNull Uri imageUri) {
        if (getContext() == null) return;

        PojavApplication.sExecutorService.execute(() -> {
            Bitmap bitmap = null;
            try {
                // Decoding on a worker keeps large gallery images off the main thread.
                bitmap = MediaStore.Images.Media.getBitmap(
                        requireContext().getContentResolver(), imageUri);

                int maxSize = ShortcutIconRenderer.ICON_SIZE * 2;
                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                if (width > maxSize || height > maxSize) {
                    float scale = Math.min((float) maxSize / width, (float) maxSize / height);
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                            Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)), true);
                    if (scaled != bitmap) bitmap.recycle();
                    bitmap = scaled;
                }
            } catch (IOException | SecurityException | OutOfMemoryError e) {
                Log.w(LOG_TAG, "Failed to decode picked image", e);
            }

            final Bitmap result = bitmap;
            Tools.runOnUiThread(() -> {
                if (!isAdded() || getContext() == null) return;
                if (result == null) {
                    Toast.makeText(getContext(), R.string.shortcut_image_error,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                mIconSource = SOURCE_CUSTOM;
                mSourceBitmap = result;
                updateSourceButtonStates();
                updatePreview();
            });
        });
    }

    // ─── Preview ───────────────────────────────────────────────────────

    /** Re-render the mock home screen tile from the current settings. */
    private void updatePreview() {
        if (getContext() == null) return;

        Bitmap preview = ShortcutIconRenderer.renderPreview(
                requireContext(),
                mSourceBitmap,
                mShape,
                mSwitchBadge.isChecked() ? mSelectedAction : null,
                ProfileShortcutHelper.resolveAccentColor(requireContext()),
                mSwitchAdaptive.isChecked());

        mIconPreview.setImageBitmap(preview);
    }

    /** Dim the icon-source buttons that are not currently active. */
    private void updateSourceButtonStates() {
        applyAlpha(mBtnProfileIcon, SOURCE_PROFILE);
        applyAlpha(mBtnSkinHead, SOURCE_SKIN);
        applyAlpha(mBtnLoaderIcon, SOURCE_LOADER);
        applyAlpha(mBtnCustom, SOURCE_CUSTOM);
    }

    private void applyAlpha(@Nullable MaterialButton button, @NonNull String source) {
        if (button == null) return;
        button.setAlpha(mIconSource.equals(source) ? 1f : 0.5f);
    }

    // ─── Creation ──────────────────────────────────────────────────────

    private void createShortcut() {
        if (getContext() == null || mProfile == null || mProfileKey == null) {
            Toast.makeText(getContext(), R.string.shortcut_error, Toast.LENGTH_SHORT).show();
            return;
        }

        String label = mNameInput.getText() != null
                ? mNameInput.getText().toString().trim() : "";
        if (label.isEmpty()) label = suggestedName(mSelectedAction);
        if (label.isEmpty()) {
            Toast.makeText(getContext(), R.string.shortcut_name_required,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Warn rather than silently creating a duplicate tile.
        if (ShortcutRegistry.exists(requireContext(), mProfileKey, mSelectedAction)) {
            Toast.makeText(getContext(), R.string.shortcut_already_exists,
                    Toast.LENGTH_SHORT).show();
        }

        boolean adaptive = mSwitchAdaptive.isChecked();
        Bitmap icon = ShortcutIconRenderer.render(
                requireContext(),
                mSourceBitmap,
                mShape,
                mSwitchBadge.isChecked() ? mSelectedAction : null,
                ProfileShortcutHelper.resolveAccentColor(requireContext()),
                adaptive);

        boolean requested = ProfileShortcutHelper.createShortcut(
                requireContext(), mProfileKey, mProfile, mSelectedAction,
                label, icon, mIconSource, adaptive);

        if (!requested) {
            Toast.makeText(getContext(), R.string.shortcut_error, Toast.LENGTH_LONG).show();
            return;
        }

        // No success toast here: ShortcutPinReceiver fires one only if the user
        // actually confirms the system dialog.
        mActionAdapter.setExisting(collectExistingActions());
    }

    @Override
    public void onShortcutPinned(@NonNull String shortcutId) {
        if (!isAdded()) return;
        mActionAdapter.setExisting(collectExistingActions());
    }

    // ─── Navigation ────────────────────────────────────────────────────

    private void openManageScreen() {
        Fragment parent = getParentFragment();
        if (parent instanceof net.kdt.pojavlaunch.fragments.MainMenuFragment) {
            ((net.kdt.pojavlaunch.fragments.MainMenuFragment) parent).openChildPane(
                    ShortcutManagerFragment.class, ShortcutManagerFragment.TAG, null);
        } else if (getActivity() != null) {
            Tools.swapFragment(requireActivity(),
                    ShortcutManagerFragment.class, ShortcutManagerFragment.TAG, null);
        }
    }

    private void goBack() {
        if (getActivity() != null) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    @Nullable
    private String getCurrentUsername() {
        try {
            net.kdt.pojavlaunch.value.MinecraftAccount account =
                    net.kdt.pojavlaunch.PojavProfile.getCurrentProfileContent(
                            getContext(), null);
            return account != null ? account.username : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int shapeToButtonId(@NonNull ShortcutIconRenderer.IconShape shape) {
        switch (shape) {
            case CIRCLE:         return R.id.shape_circle;
            case ROUNDED_SQUARE: return R.id.shape_rounded;
            case SQUARE:         return R.id.shape_square;
            case SQUIRCLE:
            default:             return R.id.shape_squircle;
        }
    }

    @NonNull
    private ShortcutIconRenderer.IconShape buttonIdToShape(int id) {
        if (id == R.id.shape_circle)  return ShortcutIconRenderer.IconShape.CIRCLE;
        if (id == R.id.shape_rounded) return ShortcutIconRenderer.IconShape.ROUNDED_SQUARE;
        if (id == R.id.shape_square)  return ShortcutIconRenderer.IconShape.SQUARE;
        return ShortcutIconRenderer.IconShape.SQUIRCLE;
    }
}
