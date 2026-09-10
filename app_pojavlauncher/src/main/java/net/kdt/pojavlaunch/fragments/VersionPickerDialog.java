package net.kdt.pojavlaunch.fragments;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dark launcher-themed version-selection popup (replaces the white system
 * AlertDialog previously used by the creation flow).
 *
 * States: LOADING (spinner) → LIST (search + scrollable rows, staggered
 * entrance) / ERROR (retry) / EMPTY. Opens with a scale+fade+rise animation,
 * closes with the reverse. Selecting a row highlights it, animates a
 * checkmark, then delivers the value to the host.
 *
 * Recreation-safe: data is provided by the HOST fragment (setTargetFragment),
 * so rotation/config changes never leave the dialog without its source.
 */
public class VersionPickerDialog extends DialogFragment {

    /** Async data source — must call exactly one receiver method. */
    public interface Receiver {
        void onVersions(List<String> versions);
        void onError(Exception error);
    }

    /** Implemented by the hosting fragment. */
    public interface Host {
        void provideVersions(int purpose, Receiver receiver);
        void onVersionPicked(int purpose, String value);
    }

    private static final String ARG_TITLE = "title";
    private static final String ARG_SEARCH = "search";
    private static final String ARG_PURPOSE = "purpose";

    private View mCard, mLoading, mError, mEmpty, mSearchRow;
    private ListView mListView;
    private EditText mSearch;
    private final List<String> mAll = new ArrayList<>();
    private ArrayAdapter<String> mAdapter;
    private boolean mClosing = false;

    /** Shows the picker; the target fragment provides + receives the data. */
    public static void show(@NonNull Fragment host, int purpose,
                            @NonNull String title, boolean searchable) {
        VersionPickerDialog d = new VersionPickerDialog();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putBoolean(ARG_SEARCH, searchable);
        args.putInt(ARG_PURPOSE, purpose);
        d.setArguments(args);
        d.setTargetFragment(host, 0);
        d.show(host.getParentFragmentManager(), "VersionPickerDialog");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = new Dialog(requireContext());
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View root = inflater.inflate(R.layout.dialog_version_picker, null, false);
        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.55f);
        }

        mCard = root.findViewById(R.id.vp_card);
        mLoading = root.findViewById(R.id.vp_loading);
        mError = root.findViewById(R.id.vp_error);
        mEmpty = root.findViewById(R.id.vp_empty);
        mSearchRow = root.findViewById(R.id.vp_search_row);
        mListView = root.findViewById(R.id.vp_list);
        mSearch = root.findViewById(R.id.vp_search);

        ((TextView) root.findViewById(R.id.vp_title)).setText(requireArguments().getString(ARG_TITLE, "Select"));
        mSearchRow.setVisibility(requireArguments().getBoolean(ARG_SEARCH) ? View.VISIBLE : View.GONE);

        ((ImageButton) root.findViewById(R.id.vp_close)).setOnClickListener(v -> animateClose());
        root.findViewById(R.id.vp_retry).setOnClickListener(v -> {
            UiMotion.pressFeedback(v);
            startLoad();
        });

        mAdapter = new ArrayAdapter<>(requireContext(), R.layout.item_version_choice,
                R.id.vp_item_name, new ArrayList<>());
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> select(position, view));

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        // ── Graphite bottom sheet ──────────────────────────────────────────
        // The dialog previously never sized its window, so it collapsed to a
        // narrow wrap-content card. Pin it to the full width at the bottom.
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            android.view.Window w = dialog.getWindow();
            w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(android.view.Gravity.BOTTOM);
            w.setWindowAnimations(0); // we drive the motion ourselves
        }
        // Premium open: the sheet slides up from the bottom and settles.
        if (mCard != null) {
            float rise = 96f * getResources().getDisplayMetrics().density;
            mCard.setAlpha(0f);
            mCard.setTranslationY(rise);
            mCard.setPivotY(rise);
            mCard.setScaleX(1f);
            mCard.setScaleY(1f);
            mCard.animate().alpha(1f).translationY(0f)
                    .setDuration(340)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(0.9f))
                    .start();
        }
        if (mAdapter != null && mAdapter.isEmpty()) startLoad();
    }

    private void startLoad() {
        showState(mLoading);
        Host host = host();
        if (host == null) { showState(mError); return; }
        final int purpose = requireArguments().getInt(ARG_PURPOSE);
        host.provideVersions(purpose, new Receiver() {
            @Override public void onVersions(List<String> versions) {
                VersionPickerDialog.this.onVersions(versions);
            }
            @Override public void onError(Exception error) {
                VersionPickerDialog.this.onError();
            }
        });
    }

    private void onVersions(List<String> versions) {
        if (!isAdded()) return;
        mAll.clear();
        if (versions != null) mAll.addAll(versions);
        if (mAll.isEmpty()) { showState(mEmpty); return; }
        showState(mListView);
        mAdapter.clear();
        mAdapter.addAll(mAll);
        mAdapter.notifyDataSetChanged();
        filter(mSearch.getText().toString());
        staggerRows();
    }

    private void onError() {
        if (!isAdded()) return;
        showState(mError);
    }

    private void showState(View state) {
        mLoading.setVisibility(state == mLoading ? View.VISIBLE : View.GONE);
        mError.setVisibility(state == mError ? View.VISIBLE : View.GONE);
        mEmpty.setVisibility(state == mEmpty ? View.VISIBLE : View.GONE);
        mListView.setVisibility(state == mListView ? View.VISIBLE : View.GONE);
    }

    private void filter(String q) {
        mAdapter.clear();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) mAdapter.addAll(mAll);
        else for (String v : mAll) if (v.toLowerCase(Locale.ROOT).contains(needle)) mAdapter.add(v);
        mAdapter.notifyDataSetChanged();
    }

    /** Subtle cascading entrance for the first visible rows. */
    private void staggerRows() {
        mListView.post(() -> {
            if (!isAdded()) return;
            int count = Math.min(mListView.getChildCount(), 12);
            for (int i = 0; i < count; i++) {
                View row = mListView.getChildAt(i);
                row.setAlpha(0f);
                row.setTranslationY(10f);
                row.animate().alpha(1f).translationY(0f)
                        .setDuration(220)
                        .setStartDelay(i * 22L)
                        .setInterpolator(new DecelerateInterpolator(1.2f))
                        .start();
            }
        });
    }

    private void select(int position, View row) {
        if (mClosing) return;
        final String value = mAdapter.getItem(position);
        mClosing = true;
        mListView.clearChoices();
        for (int i = 0; i < mListView.getChildCount(); i++) {
            mListView.getChildAt(i).setSelected(false);
        }
        row.setSelected(true);
        View check = row.findViewById(R.id.vp_item_check);
        if (check != null) {
            check.setVisibility(View.VISIBLE);
            check.setScaleX(0f);
            check.setScaleY(0f);
            check.animate().scaleX(1f).scaleY(1f)
                    .setDuration(160)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }
        final Host host = host();
        final int purpose = requireArguments().getInt(ARG_PURPOSE);
        mCard.postDelayed(() -> {
            if (host != null) host.onVersionPicked(purpose, value == null ? "" : value);
            animateClose();
        }, 170L);
    }

    private void animateClose() {
        if (mCard == null) { dismissAllowingStateLoss(); return; }
        float drop = 96f * getResources().getDisplayMetrics().density;
        mCard.animate().cancel();
        mCard.animate().alpha(0f).translationY(drop)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.1f))
                .withEndAction(this::dismissAllowingStateLoss)
                .start();
    }

    @Nullable
    private Host host() {
        Fragment target = getTargetFragment();
        if (target instanceof Host) return (Host) target;
        if (getParentFragment() instanceof Host) return (Host) getParentFragment();
        return null;
    }
}
