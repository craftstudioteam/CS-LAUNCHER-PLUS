package net.kdt.pojavlaunch.fragments;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.LauncherActivity;
import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.utils.SkinFetchUtils;
import net.kdt.pojavlaunch.skins.MinecraftSkinUploader;
import net.kdt.pojavlaunch.skins.SkinSlotPreviewView;
import net.kdt.pojavlaunch.skins.SkinSlotStore;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.yggdrasil.SkinAnalyzer;
import net.kdt.pojavlaunch.yggdrasil.SkinModelType;
import net.kdt.pojavlaunch.yggdrasil.PlayerSkin;
import net.kdt.pojavlaunch.yggdrasil.PlayerCape;
import net.kdt.pojavlaunch.yggdrasil.LocalUuidUtils;
import net.kdt.pojavlaunch.yggdrasil.LocalYggdrasilServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class SkinManagerFragment extends Fragment {

    public static final String TAG = "SKIN_MANAGER_FRAGMENT";
    public static final String ARG_SELECTED_SLOT = "selected_skin_slot";
    private static final int REQUEST_CODE_SKIN = 1001;
    private static final int REQUEST_CODE_CAPE = 1002;
    private static final float PREVIEW_MODEL_HALF_HEIGHT = 16.0f;
    private static final float PREVIEW_FIT_MARGIN = 1.13f;
    private static final float DEFAULT_PREVIEW_ZOOM = 1.0f;
    private static final float DEFAULT_PREVIEW_YAW = 18f;
    private static final float DEFAULT_PREVIEW_PITCH = -4f;
    private static final float MIN_PREVIEW_ZOOM = 0.75f;
    private static final float MAX_PREVIEW_ZOOM = 1.60f;

    private GLSurfaceView mSkinPreviewSurface;
    private TextView mTvSkinPath;
    private TextView mTvCapePath;
    private TextView mTvSkinStatusChip;
    private TextView mTvCapeStatusChip;
    private TextView mTvServerStatusChip;
    private TextView mTvPreviewHint;
    private EditText mEtUsername;
    private TextView mBtnFetch;
    private LinearLayout mSlotContainer;
    private FrameLayout mInlinePreviewHost;
    private net.kdt.pojavlaunch.skins.SkinSlotPreviewView mFlatPreview;
    private android.widget.TextView mFlatToggle;
    private android.widget.TextView mFlatBar, mFlatTitle, mFlatSub, mFlatModel, mFlatClose;
    private boolean mFlatShown;
    private FrameLayout mFullscreenPreviewHost;
    private View mFullscreenOverlay;
    private boolean mPreviewFullscreen;
    private LinearLayout.LayoutParams mPreviewLpOriginal;
    private SkinSlotStore mSlotStore;
    private MinecraftAccount mActiveAccount;
    private int mSelectedSlot;
    private int mImportTargetSlot;

    private String mPendingSkinUri;
    private String mPendingCapeUri;

    private net.kdt.pojavlaunch.ui.SkinGLRenderer mSkinRenderer;
    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetector mGestureDetector;
    private final Handler mAutoRotateHandler = new Handler(Looper.getMainLooper());
    private final Runnable mAutoRotateRunnable = new Runnable() {
        @Override
        public void run() {
            if (mSkinRenderer != null && mSkinRenderer.mAutoRotate && isAdded()
                    && mSkinPreviewSurface != null) {
                mSkinPreviewSurface.requestRender();
                mAutoRotateHandler.postDelayed(this, 42);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_skin_manager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        MinecraftAccount activeAccount = net.kdt.pojavlaunch.PojavProfile.getCurrentProfileContent(requireContext(), null);
        mActiveAccount = activeAccount;
        if (activeAccount == null) {
            Tools.dialog(requireContext(), "Authentication Required", "Please log in or create an account first.");
            getParentFragmentManager().popBackStack();
            return;
        }

        mSkinPreviewSurface = view.findViewById(R.id.skin_preview_surface);
        View previewCardForLp = view.findViewById(R.id.skin_preview_card);
        if (previewCardForLp != null
                && previewCardForLp.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            mPreviewLpOriginal = (LinearLayout.LayoutParams) previewCardForLp.getLayoutParams();
        }
        mTvSkinPath = view.findViewById(R.id.tv_skin_path);
        mTvCapePath = view.findViewById(R.id.tv_cape_path);
        mTvSkinStatusChip = view.findViewById(R.id.tv_skin_status_chip);
        mTvCapeStatusChip = view.findViewById(R.id.tv_cape_status_chip);
        mTvServerStatusChip = view.findViewById(R.id.tv_server_status_chip);
        mTvPreviewHint = view.findViewById(R.id.tv_preview_hint);
        mEtUsername = view.findViewById(R.id.et_skin_username);
        mBtnFetch = view.findViewById(R.id.btn_fetch_skin);
        mSlotContainer = view.findViewById(R.id.skin_slot_container);
        mInlinePreviewHost = view.findViewById(R.id.skin_preview_inline_host);
        mFlatPreview = view.findViewById(R.id.skin_preview_flat);
        mFlatToggle = view.findViewById(R.id.skin_preview_flat_toggle);
        if (mFlatToggle != null) {
            mFlatToggle.setOnClickListener(v -> showFlatPreview(!mFlatShown, true));
        }
        mFlatBar = view.findViewById(R.id.skin_preview_flat_bar);
        mFlatTitle = view.findViewById(R.id.skin_preview_flat_title);
        mFlatSub = view.findViewById(R.id.skin_preview_flat_sub);
        mFlatModel = view.findViewById(R.id.skin_preview_flat_model);
        mFlatClose = view.findViewById(R.id.skin_preview_flat_done);
        if (mFlatClose != null) {
            // A page needs its own way out; the ✕ in the corner is easy to miss
            // when the sheet fills the screen.
            mFlatClose.setOnClickListener(v -> {
                if (mPreviewFullscreen) exitFullscreenPreview();
                showFlatPreview(false, true);
            });
        }
        mFullscreenPreviewHost = view.findViewById(R.id.skin_fullscreen_preview_host);
        mFullscreenOverlay = view.findViewById(R.id.skin_fullscreen_overlay);

        View backButton = view.findViewById(R.id.skin_back_button);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                if (mPreviewFullscreen) exitFullscreenPreview();
                else getParentFragmentManager().popBackStack();
            });
        }
        View expandPreview = view.findViewById(R.id.skin_preview_expand);
        View closePreview = view.findViewById(R.id.skin_preview_close_inline);
        if (expandPreview != null) expandPreview.setOnClickListener(v -> {
            // The expand button is the "look closer" button; on a phone that
            // almost always means the flat sheet, not a bigger spinning cube.
            showFlatPreview(true, false);
            enterFullscreenPreview();
        });
        if (closePreview != null) closePreview.setOnClickListener(v -> exitFullscreenPreview());
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        if (mPreviewFullscreen) exitFullscreenPreview();
                        else {
                            setEnabled(false);
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });

        mSkinPreviewSurface.setEGLContextClientVersion(2);
        mSkinRenderer = new net.kdt.pojavlaunch.ui.SkinGLRenderer(requireContext());
        mSkinRenderer.mZoomFactor = DEFAULT_PREVIEW_ZOOM;
        mSkinRenderer.mAngleX = DEFAULT_PREVIEW_YAW;
        mSkinRenderer.mAngleY = DEFAULT_PREVIEW_PITCH;
        mSkinPreviewSurface.setRenderer(mSkinRenderer);
        mSkinPreviewSurface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        if (mFlatPreview != null) mFlatPreview.setMode(net.kdt.pojavlaunch.skins
                .SkinSlotPreviewView.MODE_AUTO);
        mSkinRenderer.mAutoRotate = true;
        mAutoRotateHandler.postDelayed(mAutoRotateRunnable, 500);
        setupPreviewGestures();

        File skinsDir = new File(Tools.DIR_DATA + "/skins");
        File capesDir = new File(Tools.DIR_DATA + "/capes");
        if (!skinsDir.exists()) skinsDir.mkdirs();
        if (!capesDir.exists()) capesDir.mkdirs();

        File localCapeFile = new File(capesDir, activeAccount.username + "_cape.png");
        mSlotStore = new SkinSlotStore(requireContext(), activeAccount);
        int requestedSlot = getArguments() != null
                ? getArguments().getInt(ARG_SELECTED_SLOT, -1) : -1;
        if (requestedSlot >= 0 && requestedSlot < SkinSlotStore.SLOT_COUNT) {
            mSelectedSlot = requestedSlot;
        } else {
            mSelectedSlot = mSlotStore.getActiveSlot() >= 0
                    ? mSlotStore.getActiveSlot() : firstFilledSlot();
            if (mSelectedSlot < 0) mSelectedSlot = 0;
        }
        mImportTargetSlot = mSelectedSlot;
        syncPendingSkinFromSelectedSlot();
        updateStudioSubtitle(view);
        mPendingCapeUri = localCapeFile.exists() ? Uri.fromFile(localCapeFile).toString() : null;

        updatePathText(mTvSkinPath, mPendingSkinUri, "No skin in selected slot");
        updatePathText(mTvCapePath, mPendingCapeUri, "No custom cape selected");
        updateAccountInfo();
        bindSkinSlots();

        view.findViewById(R.id.btn_change_skin).setOnClickListener(v -> {
            mImportTargetSlot = mSelectedSlot;
            openFilePicker(REQUEST_CODE_SKIN);
        });
        view.findViewById(R.id.btn_remove_skin).setOnClickListener(v -> deleteSelectedSlot());
        view.findViewById(R.id.btn_reset_default).setOnClickListener(v -> {
            if (mSlotStore != null) mSlotStore.clearAll();
            mSelectedSlot = 0;
            syncPendingSkinFromSelectedSlot();
            mPendingCapeUri = null;
            new File(Tools.DIR_DATA + "/capes/" + activeAccount.username + "_cape.png").delete();
            updatePathText(mTvSkinPath, null, "No skin in selected slot");
            updatePathText(mTvCapePath, null, "No custom cape selected");
            bindSkinSlots(); updateAccountInfo(); updatePreview();
        });
        View btnBrowseCapes = view.findViewById(R.id.btn_browse_capes);
        if (btnBrowseCapes != null) {
            btnBrowseCapes.setOnClickListener(v -> {
                Tools.swapFragment(requireActivity(), net.kdt.pojavlaunch.capes.CapeBrowserFragment.class,
                        net.kdt.pojavlaunch.capes.CapeBrowserFragment.TAG, null);
            });
        }
        view.findViewById(R.id.btn_change_cape).setOnClickListener(v -> openFilePicker(REQUEST_CODE_CAPE));
        view.findViewById(R.id.btn_remove_cape).setOnClickListener(v -> {
            mPendingCapeUri = null;
            updatePathText(mTvCapePath, null, "No custom cape selected");
            updateAccountInfo();
            updatePreview();
        });

        if (mBtnFetch != null) {
            mBtnFetch.setOnClickListener(v -> {
                String username = mEtUsername.getText().toString().trim();
                if (username.isEmpty()) return;
                // Explicit target: the fetch lands in the SELECTED slot — and
                // if that slot already holds a skin, replacing it is a choice
                // the user made by selecting it, not an accident of stale state.
                mImportTargetSlot = mSelectedSlot;
                fetchSkinFromUsername(username);
            });
        }

        view.findViewById(R.id.btn_save_skin_changes).setOnClickListener(v -> saveSkinChanges());

        resetPreviewCamera(false);
        updatePreview();
        animateEntry(view);
        applyInteractiveAnimations(view);
    }

    private int firstFilledSlot() {
        if (mSlotStore == null) return -1;
        for (int i = 0; i < SkinSlotStore.SLOT_COUNT; i++) if (mSlotStore.isFilled(i)) return i;
        return -1;
    }

    private void syncPendingSkinFromSelectedSlot() {
        if (mSlotStore != null && mSlotStore.isFilled(mSelectedSlot)) {
            mPendingSkinUri = Uri.fromFile(mSlotStore.fileFor(mSelectedSlot)).toString();
            updatePathText(mTvSkinPath, mPendingSkinUri, "No skin in selected slot");
        } else {
            mPendingSkinUri = null;
            updatePathText(mTvSkinPath, null, "No skin in selected slot");
        }
        View root = getView();
        if (root != null) updateStudioSubtitle(root);
    }

    private void bindSkinSlots() {
        if (mSlotContainer == null || mSlotStore == null) return;
        mSlotContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < SkinSlotStore.SLOT_COUNT; i++) {
            final int slotIndex = i;
            View card = inflater.inflate(R.layout.item_skin_slot, mSlotContainer, false);
            boolean filled = mSlotStore.isFilled(i);
            boolean active = mSlotStore.getActiveSlot() == i;
            boolean selected = mSelectedSlot == i;

            card.setBackgroundResource(active ? R.drawable.bg_skinv2_slot_active
                    : selected ? R.drawable.bg_skinv2_slot_selected
                    : R.drawable.bg_skinv2_slot_idle);

            TextView number = card.findViewById(R.id.skin_slot_number);
            number.setText("SLOT " + (i + 1));
            number.setTextColor(active || selected ? 0xFFD7D9E0 : 0xFF8D93A1);

            TextView status = card.findViewById(R.id.skin_slot_status);
            status.setText(active ? "ACTIVE" : filled ? "SAVED" : "EMPTY");
            status.setBackgroundResource(active ? R.drawable.bg_skinv2_pill_on
                    : R.drawable.bg_skinv2_pill);
            status.setTextColor(active ? 0xFF14151A : 0xFF9AA0AE);

            TextView check = card.findViewById(R.id.skin_slot_check);
            if (check != null) {
                check.setVisibility(active || selected ? View.VISIBLE : View.GONE);
                check.setBackgroundResource(R.drawable.bg_skinv2_check);
                check.setAlpha(active ? 1f : 0.55f);
            }

            ((TextView) card.findViewById(R.id.skin_slot_name)).setText(mSlotStore.get(i).name);
            ((TextView) card.findViewById(R.id.skin_slot_model)).setText(
                    filled ? mSlotStore.get(i).model.toUpperCase() : "NO SKIN");

            SkinSlotPreviewView preview = card.findViewById(R.id.skin_slot_preview);
            preview.setMode(SkinSlotPreviewView.MODE_FRONT_ONLY);
            preview.setSkin(filled ? mSlotStore.fileFor(i) : null,
                    filled && "slim".equalsIgnoreCase(mSlotStore.get(i).model));
            card.findViewById(R.id.skin_slot_empty).setVisibility(filled ? View.GONE : View.VISIBLE);

            View delete = card.findViewById(R.id.skin_slot_delete);
            delete.setVisibility(filled ? View.VISIBLE : View.GONE);

            TextView action = card.findViewById(R.id.skin_slot_action);
            action.setText(!filled ? "ADD" : active ? "ACTIVE" : "USE");
            action.setBackgroundResource(!filled || active
                    ? R.drawable.bg_skinv2_action_ghost : R.drawable.bg_skinv2_action);
            action.setTextColor(!filled || active ? 0xFFC9CDD8 : 0xFF14151A);

            card.setOnClickListener(v -> selectSkinSlot(slotIndex));
            action.setOnClickListener(v -> {
                selectSkinSlot(slotIndex);
                if (!filled) {
                    mImportTargetSlot = slotIndex;
                    openFilePicker(REQUEST_CODE_SKIN);
                } else if (!active) {
                    saveSkinChanges();
                } else {
                    Toast.makeText(requireContext(), "This skin is already active", Toast.LENGTH_SHORT).show();
                }
            });
            delete.setOnClickListener(v -> {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("Delete " + mSlotStore.get(slotIndex).name + "?")
                        .setMessage(active ? "The active skin will return to the default look." : "This saved skin slot will be cleared.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Delete", (dialog, which) -> {
                            mSlotStore.deleteSlot(slotIndex);
                            if (mSelectedSlot == slotIndex) {
                                int next = firstFilledSlot(); mSelectedSlot = next >= 0 ? next : 0;
                            }
                            // Keep the import target on the slot the user is
                            // actually looking at, or the next fetch/import
                            // silently replaces a DIFFERENT slot's skin.
                            mImportTargetSlot = mSelectedSlot;
                            syncPendingSkinFromSelectedSlot(); bindSkinSlots(); updatePreview(); updateAccountInfo(); refreshAccountFaces();
                        }).show();
            });
            applyPressAnimation(card);

            float d = getResources().getDisplayMetrics().density;
            card.setAlpha(0f);
            card.setTranslationY(14f * d);
            card.setScaleX(0.94f);
            card.setScaleY(0.94f);
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 70L).setDuration(320)
                    .setInterpolator(new FastOutSlowInInterpolator()).withLayer().start();

            mSlotContainer.addView(card);
            if (i < SkinSlotStore.SLOT_COUNT - 1) {
                android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) card.getLayoutParams();
                lp.setMarginEnd((int) (8 * d));
                card.setLayoutParams(lp);
            }
        }
    }

    private void selectSkinSlot(int index) {
        mSelectedSlot = index;
        mImportTargetSlot = index;
        View root = getView();
        if (root != null) updateStudioSubtitle(root);
        syncPendingSkinFromSelectedSlot();
        bindSkinSlots();
        updatePreview();
        updateAccountInfo();
    }

    private void updateStudioSubtitle(@NonNull View root) {
        TextView subtitle = root.findViewById(R.id.skin_page_subtitle);
        if (subtitle == null) return;
        String name = mSlotStore != null && mSlotStore.isFilled(mSelectedSlot)
                ? mSlotStore.get(mSelectedSlot).name : "Empty slot";
        subtitle.setText("Slot " + (mSelectedSlot + 1) + "  •  " + name);
    }

    private void deleteSelectedSlot() {
        if (mSlotStore == null || !mSlotStore.isFilled(mSelectedSlot)) return;
        mSlotStore.deleteSlot(mSelectedSlot);
        int next = firstFilledSlot(); mSelectedSlot = next >= 0 ? next : 0;
        mImportTargetSlot = mSelectedSlot;   // never leave the import target stale
        syncPendingSkinFromSelectedSlot(); bindSkinSlots(); updatePreview(); updateAccountInfo(); refreshAccountFaces();
    }

    private void fetchSkinFromUsername(String username) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                File skinsDir = new File(Tools.DIR_DATA + "/skins");
                if (!skinsDir.exists()) skinsDir.mkdirs();
                File tempSkin = new File(skinsDir, "temp_fetch_skin.png");
                SkinFetchUtils.fetchAndSaveSkin(username, tempSkin);
                
                if (tempSkin.exists()) {
                    boolean slim = detectSlimModel(tempSkin.getAbsolutePath());
                    final int targetSlot = mImportTargetSlot;
                    mSlotStore.saveSlot(targetSlot, tempSkin, username, slim);
                    tempSkin.delete();
                    mAutoRotateHandler.post(() -> {
                        mSelectedSlot = targetSlot;
                        syncPendingSkinFromSelectedSlot();
                        bindSkinSlots(); updatePreview(); updateAccountInfo();
                        Toast.makeText(requireContext(), username + " saved to SLOT " + (targetSlot + 1), Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                mAutoRotateHandler.post(() -> Toast.makeText(requireContext(), "Failed to fetch skin", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveSkinChanges() {
        MinecraftAccount acc = mActiveAccount;
        if (acc == null || mSlotStore == null || !mSlotStore.isFilled(mSelectedSlot)) {
            Toast.makeText(requireContext(), "Choose or add a skin first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mSlotStore.activate(mSelectedSlot);
            syncPendingSkinFromSelectedSlot();
            File activeSkin = new File(Tools.DIR_DATA + "/skins/" + acc.username + "_skin.png");
            boolean slim = "slim".equalsIgnoreCase(mSlotStore.get(mSelectedSlot).model);

            if (mPendingCapeUri != null) {
                File destCape = new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png");
                if (!mPendingCapeUri.equals(Uri.fromFile(destCape).toString())) {
                    copyUriToFile(Uri.parse(mPendingCapeUri), destCape);
                }
            } else {
                new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png").delete();
            }

            boolean isEly = !acc.isMicrosoft && acc.accessToken != null
                    && !"0".equals(acc.accessToken) && !acc.accessToken.isEmpty();
            if (acc.isMicrosoft) {
                updateStatusChip(mTvServerStatusChip, "SYNCING MINECRAFT", true, null);
                PojavApplication.sExecutorService.execute(() -> {
                    try {
                        MinecraftSkinUploader.upload(acc, activeSkin, slim);
                        mAutoRotateHandler.post(() -> {
                            updateStatusChip(mTvServerStatusChip, "MINECRAFT SYNCED", true, null);
                            Toast.makeText(requireContext(), "Skin active and synced to Minecraft", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        mAutoRotateHandler.post(() -> {
                            updateStatusChip(mTvServerStatusChip, "LOCAL SAVED • SYNC FAILED", false, null);
                            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } else if (isEly) {
                updateStatusChip(mTvServerStatusChip, "LOCAL SLOT ACTIVE", true, null);
                Toast.makeText(requireContext(), "Slot saved locally. Ely.by server skin remains account-managed.", Toast.LENGTH_LONG).show();
            } else {
                String cape = mPendingCapeUri != null
                        ? new File(Tools.DIR_DATA + "/capes/" + acc.username + "_cape.png").getAbsolutePath() : null;
                if (LocalYggdrasilServer.getPort() > 0) {
                    String uuid = LocalUuidUtils.generateProfileId(acc.username,
                            slim ? SkinModelType.ALEX : SkinModelType.STEVE);
                    LocalYggdrasilServer.registerProfile(acc.username, uuid,
                            activeSkin.getAbsolutePath(), cape, slim);
                }
                updateStatusChip(mTvServerStatusChip, "LOCAL SKIN ACTIVE", true, null);
                Toast.makeText(requireContext(), "Skin slot activated", Toast.LENGTH_SHORT).show();
            }
            if (mSkinRenderer != null) mSkinRenderer.mIsSlim = slim;
            bindSkinSlots(); updateAccountInfo(); updatePreview(); refreshAccountFaces();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Failed to activate skin: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAccountFaces() {
        if (mActiveAccount != null) {
            mActiveAccount.clearFaceCache();
            net.kdt.pojavlaunch.ui.SkinHead3DRenderer.invalidate(mActiveAccount.username);
        }
        if (getActivity() != null) {
            com.kdt.mcgui.mcAccountSpinner spinner = getActivity().findViewById(R.id.account_spinner);
            if (spinner != null) spinner.reloadAccounts(true, spinner.getSelectedItemPosition());
            if (getActivity() instanceof LauncherActivity) ((LauncherActivity) getActivity()).updateNavSkinIcon();
        }
    }

    /**
     * Expands the existing preview in-place. GLSurfaceView must never be re-parented:
     * moving it between ViewGroups destroys/recreates its Surface on several Android
     * vendors and produced the reported all-black full-screen preview.
     */
    /**
     * Expands the hero preview in place. GLSurfaceView must never be
     * re-parented: moving it between ViewGroups destroys and recreates its
     * Surface on several Android vendors and produced the all-black
     * full-screen preview this method replaces.
     *
     * The page body is a LinearLayout that is vertical in portrait and
     * horizontal in landscape, so the weight is applied to the axis that
     * actually stretches in the current orientation.
     */
    private void enterFullscreenPreview() {
        if (mPreviewFullscreen || mSkinPreviewSurface == null) return;
        View root = getView();
        if (root == null) return;
        View content = root.findViewById(R.id.skin_studio_content);
        View previewCard = root.findViewById(R.id.skin_preview_card);
        if (!(content instanceof LinearLayout) || previewCard == null) return;

        mPreviewFullscreen = true;
        setVisible(root.findViewById(R.id.skin_top_bar), false);
        setVisible(root.findViewById(R.id.skin_header_divider), false);
        setVisible(root.findViewById(R.id.skin_tools_scroll), false);
        setVisible(root.findViewById(R.id.skin_preview_expand), false);
        setVisible(root.findViewById(R.id.skin_preview_close_inline), true);

        boolean landscape = ((LinearLayout) content).getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT,
                landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0,
                1f);
        lp.setMargins(0, 0, 0, 0);
        previewCard.setLayoutParams(lp);

        previewCard.setAlpha(0.9f);
        previewCard.animate().alpha(1f).setDuration(200)
                .setInterpolator(new FastOutSlowInInterpolator()).start();
        mSkinPreviewSurface.post(() -> mSkinPreviewSurface.requestRender());
    }

    private void exitFullscreenPreview() {
        if (!mPreviewFullscreen) return;
        View root = getView();
        if (root == null) return;
        View previewCard = root.findViewById(R.id.skin_preview_card);
        if (previewCard == null) return;

        mPreviewFullscreen = false;
        setVisible(root.findViewById(R.id.skin_top_bar), true);
        setVisible(root.findViewById(R.id.skin_header_divider), true);
        setVisible(root.findViewById(R.id.skin_tools_scroll), true);
        setVisible(root.findViewById(R.id.skin_preview_expand), true);
        setVisible(root.findViewById(R.id.skin_preview_close_inline), false);

        if (mPreviewLpOriginal != null) {
            LinearLayout.LayoutParams restore = new LinearLayout.LayoutParams(mPreviewLpOriginal);
            previewCard.setLayoutParams(restore);
        }

        previewCard.setAlpha(0.9f);
        previewCard.animate().alpha(1f).setDuration(200)
                .setInterpolator(new FastOutSlowInInterpolator()).start();
        mSkinPreviewSurface.post(() -> mSkinPreviewSurface.requestRender());
    }

    private void setVisible(@Nullable View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void animateEntry(@NonNull View root) {
        int[] ids = new int[]{R.id.skin_top_bar, R.id.skin_preview_card, R.id.skin_skin_card,
                R.id.skin_cape_card, R.id.skin_fetch_card, R.id.skin_action_card};
        float distance = 12f * getResources().getDisplayMetrics().density;
        long delay = 0L;
        for (int id : ids) {
            View target = root.findViewById(id);
            if (target == null) continue;
            target.setAlpha(0f);
            target.setTranslationY(id == R.id.skin_top_bar ? -distance : distance);
            target.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(240)
                    .setInterpolator(new FastOutSlowInInterpolator()).start();
            delay += 35L;
        }
    }

    private void applyInteractiveAnimations(@NonNull View root) {
        int[] animatedButtons = new int[]{R.id.skin_back_button, R.id.skin_preview_expand,
                R.id.skin_preview_close_inline, R.id.btn_change_skin, R.id.btn_remove_skin,
                R.id.btn_reset_default, R.id.btn_browse_capes, R.id.btn_change_cape, R.id.btn_remove_cape,
                R.id.btn_save_skin_changes, R.id.btn_fetch_skin};
        for (int id : animatedButtons) applyPressAnimation(root.findViewById(id));
    }

    private void applyPressAnimation(@Nullable View target) {
        if (target == null) return;
        target.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new FastOutSlowInInterpolator()).start();
                    break;
            }
            return false;
        });
    }

    private void setupPreviewGestures() {
        mScaleGestureDetector = new ScaleGestureDetector(requireContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (mSkinRenderer == null) return false;
                float nextZoom = mSkinRenderer.mZoomFactor * detector.getScaleFactor();
                mSkinRenderer.mZoomFactor = Math.max(MIN_PREVIEW_ZOOM, Math.min(MAX_PREVIEW_ZOOM, nextZoom));
                mSkinPreviewSurface.requestRender();
                return true;
            }
        });
        mGestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { resetPreviewCamera(true); return true; }
        });
        mSkinPreviewSurface.setOnTouchListener((v, event) -> {
            if (mScaleGestureDetector != null) mScaleGestureDetector.onTouchEvent(event);
            if (mGestureDetector != null) mGestureDetector.onTouchEvent(event);
            if (mSkinRenderer == null) return true;
            if (event.getPointerCount() == 1 && (mScaleGestureDetector == null || !mScaleGestureDetector.isInProgress())) {
                float x = event.getX(), y = event.getY();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mSkinRenderer.mAutoRotate = false;
                        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
                        mSkinRenderer.mLastX = x;
                        mSkinRenderer.mLastY = y;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        mSkinRenderer.mAngleX += (x - mSkinRenderer.mLastX) * 0.45f;
                        mSkinRenderer.mAngleY = Math.max(-30f, Math.min(30f, mSkinRenderer.mAngleY + (y - mSkinRenderer.mLastY) * 0.35f));
                        mSkinPreviewSurface.requestRender();
                        mSkinRenderer.mLastX = x; mSkinRenderer.mLastY = y;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
                        mAutoRotateHandler.postDelayed(() -> {
                            if (mSkinRenderer != null) {
                                mSkinRenderer.mAutoRotate = true;
                                mAutoRotateHandler.postDelayed(mAutoRotateRunnable, 42);
                            }
                        }, 1800);
                        break;
                }
            }
            return true;
        });
    }

    private void resetPreviewCamera(boolean animateSurface) {
        if (mSkinRenderer == null || mSkinPreviewSurface == null) return;
        mSkinRenderer.mAutoRotate = false;
        mSkinRenderer.mAngleX = DEFAULT_PREVIEW_YAW;
        mSkinRenderer.mAngleY = DEFAULT_PREVIEW_PITCH;
        mSkinRenderer.mZoomFactor = DEFAULT_PREVIEW_ZOOM;
        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
        mSkinPreviewSurface.requestRender();
        if (animateSurface) {
            mSkinPreviewSurface.animate().cancel();
            mSkinPreviewSurface.setScaleX(0.985f); mSkinPreviewSurface.setScaleY(0.985f);
            mSkinPreviewSurface.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
    }

    private void updateAccountInfo() {
        boolean selectedFilled = mSlotStore != null && mSlotStore.isFilled(mSelectedSlot);
        boolean active = mSlotStore != null && mSlotStore.getActiveSlot() == mSelectedSlot;
        updateStatusChip(mTvSkinStatusChip,
                active ? "ACTIVE SLOT " + (mSelectedSlot + 1)
                        : selectedFilled ? "PREVIEW SLOT " + (mSelectedSlot + 1) : "EMPTY SLOT",
                selectedFilled, null);
        updateStatusChip(mTvCapeStatusChip, "3 SKIN SLOTS", true, null);
        boolean isEly = mActiveAccount != null && !mActiveAccount.isMicrosoft
                && mActiveAccount.accessToken != null && !"0".equals(mActiveAccount.accessToken);
        String sync = mActiveAccount != null && mActiveAccount.isMicrosoft ? "MINECRAFT CLOUD"
                : isEly ? "ELY.BY ACCOUNT" : "LOCAL SKIN SERVER";
        updateStatusChip(mTvServerStatusChip, sync, active, null);
        if (mTvPreviewHint != null) mTvPreviewHint.setText(
                selectedFilled ? "Drag to rotate • Pinch to zoom • Slot " + (mSelectedSlot + 1)
                        : "Choose ADD to import a Minecraft skin PNG");
    }

    private void updateStatusChip(TextView view, String text, boolean active, String cd) {
        if (view == null) return;
        view.setText(text);
        view.setBackgroundResource(active ? R.drawable.bg_skin_status_chip_active : R.drawable.bg_skin_status_chip_inactive);
        view.setTextColor(active ? 0xFF14151A : 0xFF9AA0AE);
    }

    /** Pixel-alpha based Steve/Alex detection (64x64 vs legacy 64x32 safe). */
    private boolean detectSlimModel(@NonNull String skinPath) {
        Bitmap bmp = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            bmp = BitmapFactory.decodeFile(skinPath, opts);
            if (bmp == null) return false;
            final Bitmap source = bmp;
            return SkinAnalyzer.detectSkinModel(source.getHeight(),
                    (x, y) -> {
                        if (x < 0 || y < 0 || x >= source.getWidth() || y >= source.getHeight()) return 0;
                        return Color.alpha(source.getPixel(x, y));
                    }) == SkinModelType.ALEX;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        }
    }

    private void copyUriToFile(Uri uri, File destFile) throws Exception {
        try (InputStream in = requireContext().getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    private void openFilePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        startActivityForResult(intent, requestCode);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mActiveAccount != null) {
            File localCapeFile = new File(Tools.DIR_DATA + "/capes/" + mActiveAccount.username + "_cape.png");
            mPendingCapeUri = localCapeFile.exists() ? Uri.fromFile(localCapeFile).toString() : null;
            updatePathText(mTvCapePath, mPendingCapeUri, "No custom cape selected");
        }
        if (mSkinPreviewSurface != null && !mFlatShown) {
            mSkinPreviewSurface.onResume();
            if (mSkinRenderer != null && mSkinRenderer.mAutoRotate) {
                mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
                mAutoRotateHandler.postDelayed(mAutoRotateRunnable, 42);
            }
            updatePreview();
        } else if (mFlatPreview != null) {
            // Back on the page with the flat view open. A repaint is not enough:
            // the view drops its bitmap when it leaves the window (three of them
            // holding memory off screen is wasteful), so the pixels have to be
            // handed over again before drawing — otherwise the page comes back
            // showing the empty mannequin and looks like the skin was deleted.
            Bitmap flat = loadBitmapFromUri(mPendingSkinUri);
            mFlatPreview.setBitmap(flat, isSlimSelected());
            bindFlatBar(flat == null, flat);
            mFlatPreview.invalidate();
        }
    }

    @Override
    public void onPause() {
        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
        if (mSkinPreviewSurface != null) mSkinPreviewSurface.onPause();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        mAutoRotateHandler.removeCallbacks(mAutoRotateRunnable);
        if (mFullscreenOverlay != null) mFullscreenOverlay.animate().cancel();
        if (mSkinRenderer != null) mSkinRenderer.onPause();
        if (mSkinPreviewSurface != null) mSkinPreviewSurface.onPause();
        mPreviewFullscreen = false;
        // Both views die with the fragment's view: keeping the references means a
        // stale view (and for the flat preview, one whose bitmap was recycled on
        // detach) being painted after the fact.
        mFlatPreview = null;
        mFlatToggle = null;
        mFlatBar = null;
        mFlatTitle = null;
        mFlatSub = null;
        mFlatModel = null;
        mFlatClose = null;
        mSkinPreviewSurface = null;
        mSkinRenderer = null;
        mInlinePreviewHost = null;
        mFullscreenPreviewHost = null;
        mFullscreenOverlay = null;
        super.onDestroyView();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) {}
            if (requestCode == REQUEST_CODE_SKIN) {
                try {
                    File temp = new File(requireContext().getCacheDir(), "skin_slot_import.png");
                    copyUriToFile(uri, temp);
                    Bitmap check = BitmapFactory.decodeFile(temp.getAbsolutePath());
                    if (check == null || check.getWidth() < 64 || check.getHeight() < 32) {
                        if (check != null) check.recycle(); temp.delete();
                        throw new Exception("Invalid Minecraft skin PNG");
                    }
                    check.recycle();
                    boolean slim = detectSlimModel(temp.getAbsolutePath());
                    String fileName = uri.getLastPathSegment();
                    mSlotStore.saveSlot(mImportTargetSlot, temp,
                            fileName == null ? "Skin Slot " + (mImportTargetSlot + 1)
                                    : fileName.replaceAll("(?i)\\.png$", ""), slim);
                    temp.delete();
                    mSelectedSlot = mImportTargetSlot;
                    syncPendingSkinFromSelectedSlot(); bindSkinSlots(); updateAccountInfo(); updatePreview();
                } catch (Exception e) {
                    Toast.makeText(requireContext(), "Skin import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else if (requestCode == REQUEST_CODE_CAPE) {
                mPendingCapeUri = uri.toString();
                updatePathText(mTvCapePath, uri.toString(), "");
                updateAccountInfo(); updatePreview();
            }
        }
    }

    private void updatePathText(TextView textView, String uriStr, String defaultText) {
        if (textView == null) return;
        if (uriStr != null) {
            Uri uri = Uri.parse(uriStr);
            textView.setText(uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uriStr);
        } else textView.setText(defaultText);
    }

    /**
     * Both previews read the same decode: the GL cube gets the texture, the flat
     * view gets the bitmap itself (it takes ownership, hence setBitmap and not a
     * File — re-decoding here would double the work on every slot switch).
     */
    private void updatePreview() {
        Bitmap skinBitmap = loadBitmapFromUri(mPendingSkinUri);
        boolean fromDefault = skinBitmap == null;
        if (fromDefault) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inScaled = false;
            skinBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.cs_default_skin, options);
        }
        Bitmap capeBitmap = loadBitmapFromUri(mPendingCapeUri);
        if (mSkinRenderer != null) {
            mSkinRenderer.setTexture(skinBitmap, capeBitmap);
            mSkinPreviewSurface.requestRender();
        }
        if (mFlatPreview != null) {
            if (mFlatShown) {
                // A placeholder is not a skin: say so rather than let the user
                // "save" Steve and wonder why nothing changed.
                mFlatPreview.setBitmap(fromDefault ? null : skinBitmap, isSlimSelected());
                mFlatPreview.setNotice(null);   // the bar carries the wording now
                bindFlatBar(fromDefault, skinBitmap);
            } else {
                mFlatPreview.setNotice(null);
            }
        }
    }

    /** What kind of sheet did we actually get? Users bring 64x32s and capes. */
    private static String skinSheetHint(Bitmap skin) {
        if (skin == null) return null;
        int w = skin.getWidth(), h = skin.getHeight();
        if (w == 64 && h == 32) return "64x32 SHEET \u00b7 OVERLAYS NOT SUPPORTED";
        if (w == 64 && h == 64) return "64x64 SHEET \u00b7 OVERLAYS SHOWN";
        if (w == h && w >= 128) return "HD SKIN " + w + "x" + h + " \u00b7 SCALED TO FIT";
        return "UNUSUAL SIZE " + w + "x" + h;
    }

    /**
     * Title line of the flat page: which slot, what state, and what kind of sheet
     * came back. The state line is the point — "NO SKIN IN THIS SLOT" and
     * "COULD NOT READ THE SAVED SKIN" look identical without it, and only one of
     * them means the file is broken.
     */
    private void bindFlatBar(boolean fromDefault, Bitmap skin) {
        if (mFlatBar == null) return;
        String slotName = "SLOT " + (mSelectedSlot + 1);
        String saved = null;
        try {
            if (mSlotStore != null && mSlotStore.isFilled(mSelectedSlot)) {
                net.kdt.pojavlaunch.skins.SkinSlotStore.Slot e =
                        mSlotStore.get(mSelectedSlot);
                if (e != null && e.name != null && !e.name.isEmpty()) saved = e.name;
            }
        } catch (Throwable ignored) {}
        if (mFlatTitle != null) {
            mFlatTitle.setText(saved != null ? saved.toUpperCase(java.util.Locale.ROOT) : slotName);
        }
        if (mFlatModel != null) {
            boolean slim = isSlimSelected();
            mFlatModel.setText(slim ? "SLIM" : "CLASSIC");
            mFlatModel.setVisibility(View.VISIBLE);
        }
        if (mFlatSub != null) {
            if (fromDefault) {
                mFlatSub.setText(isSlotFilled(mSelectedSlot)
                        ? "The saved file could not be read — pick the skin again"
                        : "Nothing saved in this slot yet");
                mFlatSub.setTextColor(0xFFF2A0A0);
            } else {
                String hint = skinSheetHint(skin);
                mFlatSub.setText(hint == null ? slotName : hint);
                mFlatSub.setTextColor(0xFF8A8D98);
            }
        }
    }

    private boolean isSlotFilled(int slot) {
        try {
            return mSlotStore != null && mSlotStore.isFilled(slot);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Switch the hero card between the 3D cube and the flat 2D skin.
     *
     * The GL surface is paused rather than destroyed — recreating an EGL context
     * to look at a texture is how you get a black frame for 200 ms, which on a
     * mid-range phone reads exactly like a crash.
     */
    private void showFlatPreview(boolean flat, boolean toggleChip) {
        if (mFlatPreview == null || mSkinPreviewSurface == null) return;
        mFlatShown = flat;
        if (toggleChip && mFlatToggle != null) {
            mFlatToggle.setText(flat ? "3D" : "2D");
            mFlatToggle.setTextColor(flat ? 0xFF82F3F4 : 0xFFC9CDD8);
        }
        if (mTvPreviewHint != null) {
            mTvPreviewHint.setText(flat
                    ? "Front and back, exactly as the sheet stores them"
                    : "Drag to turn \u00b7 pinch to zoom \u00b7 2D for the flat sheet");
        }
        if (mFlatBar != null) {
            mFlatBar.setVisibility(flat ? View.VISIBLE : View.GONE);
            // The bar is an overlay on this card, so the figure has to be pushed
            // clear of it or the FRONT/BACK labels land underneath it. 34dp is the
            // bar's own height plus its padding.
            if (mInlinePreviewHost != null) {
                int bottom = flat ? dp(34) : 0;
                mInlinePreviewHost.setPadding(mInlinePreviewHost.getPaddingLeft(),
                        mInlinePreviewHost.getPaddingTop(),
                        mInlinePreviewHost.getPaddingRight(), bottom);
            }
        }
        if (flat) {
            mSkinPreviewSurface.onPause();
            mFlatPreview.setVisibility(View.VISIBLE);
            mFlatPreview.setMode(net.kdt.pojavlaunch.skins.SkinSlotPreviewView.MODE_AUTO);
            // updatePreview() owns the flat bitmap (it decodes once and hands the
            // same Bitmap to the renderer and to this view); decoding it a second
            // time here would be a stutter on every toggle.
            updatePreview();
        } else {
            mFlatPreview.setVisibility(View.GONE);
            mSkinPreviewSurface.onResume();
            mSkinPreviewSurface.requestRender();
        }
        if (mTvSkinStatusChip != null) {
            mTvSkinStatusChip.setText(flat ? "FLAT PREVIEW" : mTvSkinStatusChip.getText());
        }
    }

    private boolean isSlimSelected() {
        try {
            if (mSlotStore == null || !mSlotStore.isFilled(mSelectedSlot)) return false;
            return "slim".equalsIgnoreCase(mSlotStore.get(mSelectedSlot).model);
        } catch (Throwable t) {
            return false;
        }
    }

    private Bitmap loadBitmapFromUri(String uriStr) {
        if (uriStr == null) return null;
        try {
            Uri uri = Uri.parse(uriStr);
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                if (is != null) return BitmapFactory.decodeStream(is);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Premium GL preview renderer: lighting, shadow, cached geometry, slim/steve arms. */
}
