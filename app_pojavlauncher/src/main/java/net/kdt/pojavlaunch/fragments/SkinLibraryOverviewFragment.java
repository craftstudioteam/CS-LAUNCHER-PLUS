package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.UiMotion;
import net.kdt.pojavlaunch.skins.SkinSlotPreviewView;
import net.kdt.pojavlaunch.skins.SkinSlotStore;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;

/**
 * Read-first skin library: a hero stage that shows one skin at a time, the
 * three real slots underneath, and the two real actions — equip a slot
 * ({@link SkinSlotStore#activate(int)}) or open it in Skin Studio.
 *
 * The page only ever reads/writes through {@code SkinSlotStore}; nothing here
 * invents state, and the storage/persistence rules of the store (per-account
 * root, non-destructive activation) are untouched.
 */
public class SkinLibraryOverviewFragment extends Fragment {
    public static final String TAG = "SKIN_LIBRARY_OVERVIEW";

    private View root;
    private LinearLayout slotContainer;
    private TextView accountText, activeText;
    private View heroCard, heroStage, slotsLabel, loadingBlock, emptyBlock, errorBlock;
    private TextView applyButton;
    private TextView heroName, heroHint, heroSlotLabel, heroModel, heroBadge;
    private SkinSlotPreviewView heroPreview;
    private View errorText;

    private MinecraftAccount account;
    private SkinSlotStore store;
    /** Which slot the hero is showing. UI-only — the equipped slot is store state. */
    private int mShownSlot = -1;
    private boolean mBusy = false;

    public SkinLibraryOverviewFragment() { super(R.layout.fragment_skin_library_overview); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        root = view;
        slotContainer = view.findViewById(R.id.skin_overview_slots);
        accountText = view.findViewById(R.id.skin_overview_account);
        activeText = view.findViewById(R.id.skin_overview_active);
        heroCard = view.findViewById(R.id.skin_overview_hero);
        heroStage = view.findViewById(R.id.skin_overview_hero_stage);
        heroPreview = view.findViewById(R.id.skin_overview_hero_preview);
        heroName = view.findViewById(R.id.skin_overview_hero_name);
        heroHint = view.findViewById(R.id.skin_overview_hero_hint);
        heroSlotLabel = view.findViewById(R.id.skin_overview_hero_slot);
        heroModel = view.findViewById(R.id.skin_overview_hero_model);
        heroBadge = view.findViewById(R.id.skin_overview_hero_badge);
        applyButton = (TextView) view.findViewById(R.id.skin_overview_apply);
        slotsLabel = view.findViewById(R.id.skin_overview_slots_label);
        loadingBlock = view.findViewById(R.id.skin_overview_loading);
        emptyBlock = view.findViewById(R.id.skin_overview_empty);
        errorBlock = view.findViewById(R.id.skin_overview_error);
        errorText = view.findViewById(R.id.skin_overview_error_text);

        view.findViewById(R.id.skin_overview_back).setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        view.findViewById(R.id.skin_overview_open_studio).setOnClickListener(v -> openStudio(currentShown()));
        applyButton.setOnClickListener(v -> equip(currentShown()));
        if (emptyBlock != null) {
            View importCta = emptyBlock.findViewById(R.id.skin_overview_empty_cta);
            if (importCta != null) importCta.setOnClickListener(v -> openStudio(0));
        }
        if (errorBlock != null) {
            View retry = errorBlock.findViewById(R.id.skin_overview_retry);
            if (retry != null) retry.setOnClickListener(v -> loadLibrary());
        }

        showState(STATE_LOADING);
        loadLibrary();

        View header = view.findViewById(R.id.skin_overview_header);
        header.setAlpha(0f);
        header.setTranslationY(-12f * getResources().getDisplayMetrics().density);
        header.animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(new FastOutSlowInInterpolator()).start();
        UiMotion.pressFeedback(view.findViewById(R.id.skin_overview_back));
    }

    @Override public void onResume() {
        super.onResume();
        // Coming back from Skin Studio has to re-read the library: a save there
        // is the only way a slot's file can change underneath this page.
        if (slotContainer != null && !mBusy) loadLibrary();
    }

    private int currentShown() {
        return mShownSlot < 0 ? 0 : mShownSlot;
    }

    // ───────────────────────────── page states ─────────────────────────────

    private static final int STATE_LOADING = 0, STATE_LIBRARY = 1,
            STATE_EMPTY = 2, STATE_ERROR = 3;

    private void showState(int state) {
        if (root == null) return;
        setLoadingPulse(state == STATE_LOADING);
        if (loadingBlock != null) loadingBlock.setVisibility(state == STATE_LOADING ? View.VISIBLE : View.GONE);
        if (heroCard != null) heroCard.setVisibility(state == STATE_ERROR || state == STATE_LOADING ? View.GONE : View.VISIBLE);
        if (slotsLabel != null) slotsLabel.setVisibility(state == STATE_LIBRARY ? View.VISIBLE : View.GONE);
        if (slotContainer != null) slotContainer.setVisibility(state == STATE_LIBRARY ? View.VISIBLE : View.GONE);
        if (emptyBlock != null) emptyBlock.setVisibility(state == STATE_EMPTY ? View.VISIBLE : View.GONE);
        if (errorBlock != null) errorBlock.setVisibility(state == STATE_ERROR ? View.VISIBLE : View.GONE);
    }

    /** The skeleton breathes while the library is read, then stops. */
    private android.animation.ValueAnimator mSkeleton;

    private void setLoadingPulse(boolean on) {
        if (mSkeleton != null) {
            UiMotion.stopPulse(mSkeleton);
            mSkeleton = null;
        }
        if (!on || loadingBlock == null) return;
        View a = root.findViewById(R.id.skin_overview_loading_a);
        View b = root.findViewById(R.id.skin_overview_loading_b);
        if (a != null) mSkeleton = UiMotion.pulseSkeleton(a);
        if (b != null) UiMotion.pulseSkeleton(b);
    }

    private void showError(String message) {
        if (errorText != null && message != null) ((TextView) errorText).setText(message);
        showState(STATE_ERROR);
    }

    // ───────────────────────────── reading ─────────────────────────────

    /**
     * The store constructor touches the filesystem (slots.json + three PNG
     * stat()s), so it runs off the main thread and the page is filled in on
     * the UI thread once it is really known.
     */
    private void loadLibrary() {
        if (mBusy) return;
        mBusy = true;
        showState(STATE_LOADING);
        final Context appContext = requireContext().getApplicationContext();
        PojavApplication.sExecutorService.execute(() -> {
            MinecraftAccount readAccount = null;
            SkinSlotStore readStore = null;
            String failure = null;
            try {
                readAccount = PojavProfile.getCurrentProfileContent(appContext, null);
                if (readAccount == null) {
                    failure = "No account is selected. Pick one on the home screen, then open your skins again.";
                } else {
                    readStore = new SkinSlotStore(appContext, readAccount);
                }
            } catch (Exception | Error e) {
                failure = "Your skin library could not be read: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final MinecraftAccount finalAccount = readAccount;
            final SkinSlotStore finalStore = readStore;
            final String finalFailure = failure;
            if (!isAdded()) { mBusy = false; return; }
            requireActivity().runOnUiThread(() -> {
                mBusy = false;
                if (!isAdded() || getView() == null) return;
                if (finalFailure != null) { showError(finalFailure); return; }
                account = finalAccount;
                store = finalStore;
                bindLibrary();
            });
        });
    }

    private void bindLibrary() {
        accountText.setText(account.username);
        int active = store.getActiveSlot();
        activeText.setText(active >= 0 ? "SLOT " + (active + 1) + " ACTIVE" : "NO ACTIVE SKIN");
        activeText.setTextColor(active >= 0 ? 0xFFE6E9ED : 0xFFBAC0C8);
        activeText.setBackgroundResource(active >= 0
                ? R.drawable.bg_au_chip_ok : R.drawable.bg_au_chip_neutral);

        int firstFilled = -1;
        for (int i = 0; i < SkinSlotStore.SLOT_COUNT; i++) {
            if (store.isFilled(i) && firstFilled < 0) firstFilled = i;
        }
        if (firstFilled < 0) {
            mShownSlot = 0;
            bindHero();
            showState(STATE_EMPTY);
            slotContainer.removeAllViews();
            return;
        }
        // Keep the hero on whatever the player was looking at, if it still exists.
        if (mShownSlot < 0 || !store.isFilled(mShownSlot)) {
            mShownSlot = active >= 0 ? active : firstFilled;
        }
        showState(STATE_LIBRARY);
        bindHero();
        rebuildCards(active);
    }

    // ───────────────────────────── hero ─────────────────────────────

    private void bindHero() {
        if (store == null || mShownSlot < 0) return;
        SkinSlotStore.Slot slot = store.get(mShownSlot);
        boolean filled = store.isFilled(mShownSlot);
        boolean isActive = store.getActiveSlot() == mShownSlot;
        boolean slim = filled && "slim".equalsIgnoreCase(slot.model);

        heroName.setText(filled ? slot.name : "Empty Slot");
        heroHint.setText(!filled ? "Nothing saved in this slot yet — open Skin Studio to import a PNG."
                : isActive ? "This is what the game shows right now."
                : "Saved " + ago(slot.updatedAt) + " · tap Equip to put it on.");
        heroSlotLabel.setText("SLOT " + (mShownSlot + 1));
        heroModel.setText(filled ? (slim ? "SLIM" : "CLASSIC") : "");
        heroModel.setVisibility(filled ? View.VISIBLE : View.GONE);
        heroBadge.setText(isActive ? "EQUIPPED" : "PREVIEW");
        heroBadge.setBackgroundResource(isActive ? R.drawable.bg_au_chip_ok : R.drawable.bg_au_chip_neutral);
        heroBadge.setTextColor(isActive ? 0xFFE6E9ED : 0xFFBAC0C8);
        applyButton.setEnabled(filled && !isActive);
        applyButton.setText(isActive ? "IN GAME" : filled ? "EQUIP THIS SKIN" : "IMPORT FIRST");
        applyButton.animate().cancel();
        applyButton.setAlpha(filled && !isActive ? 1f : 0.45f);
        heroCard.setBackgroundResource(isActive ? R.drawable.bg_au_card_on : R.drawable.bg_au_card);

        File file = filled ? store.fileFor(mShownSlot) : null;
        heroPreview.setSkin(file, slim);
        heroPreview.setMode(isNarrow() ? SkinSlotPreviewView.MODE_FRONT_ONLY
                : SkinSlotPreviewView.MODE_AUTO);
    }

    /** Slot tap = preview it here. That is the whole point of the hero stage. */
    private void showSlot(int slot) {
        if (slot == mShownSlot) {
            UiMotion.springScale(heroStage, 1.03f);
            return;
        }
        mShownSlot = slot;
        bindHero();
        heroStage.setAlpha(0.2f);
        heroStage.setScaleX(0.96f);
        heroStage.setScaleY(0.96f);
        heroStage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                .setInterpolator(new FastOutSlowInInterpolator()).start();
        highlightCards();
    }

    /** The real equip path — same call Skin Studio makes. */
    private void equip(int slot) {
        if (store == null || !store.isFilled(slot) || store.getActiveSlot() == slot) return;
        if (mBusy) return;
        mBusy = true;
        applyButton.setAlpha(0.6f);
        final Context appContext = requireContext().getApplicationContext();
        // Pin the store for the worker: leaving the page clears the field, and
        // a background activate() must not chase a nulled reference.
        final SkinSlotStore workerStore = store;
        if (workerStore == null) { mBusy = false; return; }
        PojavApplication.sExecutorService.execute(() -> {
            String failure = null;
            try {
                workerStore.activate(slot);
            } catch (Exception | Error e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String finalFailure = failure;
            if (!isAdded()) { mBusy = false; return; }
            requireActivity().runOnUiThread(() -> {
                mBusy = false;
                if (!isAdded() || getView() == null) return;
                if (finalFailure != null) {
                    Toast.makeText(requireContext(), "Could not equip: " + finalFailure,
                            Toast.LENGTH_LONG).show();
                    applyButton.setAlpha(1f);
                    return;
                }
                Toast.makeText(requireContext(), "Skin slot " + (slot + 1) + " equipped",
                        Toast.LENGTH_SHORT).show();
                bindLibrary();
                UiMotion.popIn(heroStage);
                UiMotion.springScale(heroCard, 1.015f);
                // keep the pulse from being stuck on the previous tint
                UiMotion.springScale(applyButton, 1.02f);
            });
        });
    }

    // ───────────────────────────── cards ─────────────────────────────

    private void rebuildCards(int active) {
        slotContainer.removeAllViews();
        boolean narrow = isNarrow();
        slotContainer.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        slotContainer.setGravity(narrow ? android.view.Gravity.TOP : android.view.Gravity.CENTER_VERTICAL);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < SkinSlotStore.SLOT_COUNT; i++) {
            final int slot = i;
            View card = inflater.inflate(R.layout.item_skin_overview_slot, slotContainer, false);
            boolean filled = store.isFilled(i);
            boolean isActive = active == i;
            SkinSlotStore.Slot data = store.get(i);
            boolean slim = filled && "slim".equalsIgnoreCase(data.model);

            View body = card.findViewById(R.id.overview_slot_body);
            View stage = card.findViewById(R.id.overview_slot_stage);
            View info = card.findViewById(R.id.overview_slot_info);
            View action = card.findViewById(R.id.overview_slot_action);
            View emptyPlate = card.findViewById(R.id.overview_slot_empty);
            TextView status = card.findViewById(R.id.overview_slot_status);
            TextView name = card.findViewById(R.id.overview_slot_name);
            TextView model = card.findViewById(R.id.overview_slot_model);
            TextView hint = card.findViewById(R.id.overview_slot_hint);
            SkinSlotPreviewView preview = card.findViewById(R.id.overview_slot_preview);

            ((TextView) card.findViewById(R.id.overview_slot_number))
                    .setText("SKIN SLOT " + (i + 1));
            status.setText(isActive ? "IN GAME" : filled ? "SAVED" : "EMPTY");
            status.setTextColor(isActive ? 0xFF101318 : 0xFFBAC0C8);
            status.setBackgroundResource(isActive
                    ? R.drawable.bg_au_chip_ok : R.drawable.bg_au_chip_neutral);
            name.setText(filled ? data.name : "Empty Slot");
            name.setTextColor(filled ? 0xFFF2F3F5 : 0xFF868D97);
            hint.setText(!filled ? "Tap to preview · chevron to fill this slot"
                    : isActive ? "Equipped — the game shows this one"
                    : "Saved " + ago(data.updatedAt) + " · tap to preview");
            model.setVisibility(filled ? View.VISIBLE : View.GONE);
            model.setText(slim ? "SLIM" : "CLASSIC");

            // mutate(): the dot drawable is shared by all three cards.
            android.graphics.drawable.Drawable dot =
                    card.findViewById(R.id.overview_slot_dot).getBackground().mutate();
            dot.setTint(isActive ? 0xFFE6E9ED : filled ? 0xFF5F666F : 0xFF3C3D45);

            preview.setMode(narrow ? SkinSlotPreviewView.MODE_FRONT_ONLY
                    : SkinSlotPreviewView.MODE_AUTO);
            preview.setSkin(filled ? store.fileFor(i) : null, slim);
            emptyPlate.setVisibility(filled ? View.GONE : View.VISIBLE);

            // The card's own height drives the plate: in the portrait stack the
            // body hugs its content (minHeight keeps it a comfortable 104dp),
            // in the wide 3-up row it fills the fixed-height card so the preview
            // gets real room instead of collapsing.
            android.widget.FrameLayout.LayoutParams bodyLp =
                    (android.widget.FrameLayout.LayoutParams) body.getLayoutParams();
            bodyLp.height = narrow ? ViewGroup.LayoutParams.WRAP_CONTENT
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            body.setLayoutParams(bodyLp);

            if (narrow) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = i == 0 ? 0 : (int) (10 * getResources().getDisplayMetrics().density);
                lp.weight = 0f;
                card.setLayoutParams(lp);
            } else {
                // Three cards side by side: the plate becomes the top half and the
                // information stack sits under it, which is what a wide row needs.
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, (int) (236 * getResources().getDisplayMetrics().density), 1f);
                lp.leftMargin = i == 0 ? 0 : (int) (10 * getResources().getDisplayMetrics().density);
                card.setLayoutParams(lp);
                applyWideLayout(body, stage, info);
            }

            body.setBackgroundResource(i == mShownSlot
                    ? R.drawable.bg_au_card_on : R.drawable.bg_au_card);
            stage.setBackgroundResource(i == mShownSlot
                    ? R.drawable.bg_au_plate_on : R.drawable.bg_au_plate);
            card.setTag(i);

            View.OnClickListener select = v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                showSlot(slot);
            };
            body.setOnClickListener(select);
            stage.setClickable(true);
            stage.setOnClickListener(select);
            emptyPlate.setClickable(true);
            emptyPlate.setOnClickListener(select);
            action.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                openStudio(slot);
            });

            UiMotion.pressFeedback(body, action);
            card.setElevation(0f);
            card.setAlpha(0f);
            card.setTranslationY(12f * getResources().getDisplayMetrics().density);
            card.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L)
                    .setDuration(240).setInterpolator(new FastOutSlowInInterpolator()).start();
            slotContainer.addView(card);
        }
    }

    /** Re-tint only the two selection backgrounds — no re-inflate, no flicker. */
    private void highlightCards() {
        if (slotContainer == null) return;
        for (int i = 0; i < slotContainer.getChildCount(); i++) {
            View card = slotContainer.getChildAt(i);
            Object tag = card.getTag();
            if (!(tag instanceof Integer)) continue;
            boolean on = (Integer) tag == mShownSlot;
            card.findViewById(R.id.overview_slot_body)
                    .setBackgroundResource(on ? R.drawable.bg_au_card_on : R.drawable.bg_au_card);
            View stage = card.findViewById(R.id.overview_slot_stage);
            stage.setBackgroundResource(on ? R.drawable.bg_au_plate_on : R.drawable.bg_au_plate);
            if (on) UiMotion.springScale(card, 1.01f);
        }
    }

    /**
     * A phone is not a small tablet: below 600dp the cards stack vertically and
     * the plate stays on the left; wider, the row is horizontal and each card
     * re-orients so the preview gets real height instead of being squeezed.
     */
    private boolean isNarrow() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        // Width, not "smallest width": a phone held sideways has ~640dp across and
        // can carry the three-card row; a tablet in split screen cannot.
        return dm.widthPixels / dm.density < 600f;
    }

    private static void applyWideLayout(View body, View stage, View info) {
        float density = body.getResources().getDisplayMetrics().density;
        LinearLayout bodyL = (LinearLayout) body;
        LinearLayout infoL = (LinearLayout) info;
        bodyL.setOrientation(LinearLayout.VERTICAL);
        bodyL.setGravity(android.view.Gravity.NO_GRAVITY);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stage.setLayoutParams(slp);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = (int) (11 * density);
        ilp.leftMargin = 0;
        infoL.setLayoutParams(ilp);
        infoL.setOrientation(LinearLayout.VERTICAL);
    }

    /** Compact "3d ago" stamp, matching the shape the runtime list uses. */
    private static String ago(long millis) {
        if (millis <= 0) return "just now";
        long mins = (System.currentTimeMillis() - millis) / 60000L;
        if (mins < 1) return "just now";
        if (mins < 60) return mins + "m ago";
        long hours = mins / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private void openStudio(int slot) {
        Bundle args = new Bundle();
        args.putInt(SkinManagerFragment.ARG_SELECTED_SLOT, slot);
        Fragment parent = getParentFragment();
        if (parent instanceof MainMenuFragment) {
            ((MainMenuFragment) parent).openChildPane(SkinManagerFragment.class, SkinManagerFragment.TAG, args);
        } else {
            Tools.swapFragment(requireActivity(), SkinManagerFragment.class,
                    SkinManagerFragment.TAG, args);
        }
    }

    @Override
    public void onDestroyView() {
        setLoadingPulse(false);
        if (heroPreview != null) heroPreview.setSkin(null, false);
        store = null;
        account = null;
        root = null;
        super.onDestroyView();
    }

}
