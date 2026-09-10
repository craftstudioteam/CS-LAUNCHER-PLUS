package net.kdt.pojavlaunch.multirt;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.UiMotion;

/**
 * Runtime Manager — a Clean Console XML sheet (was a stock AlertDialog list).
 *
 * Same behaviour as before (installed + downloadable runtimes, set-default,
 * delete mode, "add" hand-off to the JVM installer) but rendered from
 * {@code dialog_runtime_picker.xml} and animated with {@code CsDialogAnim}
 * so it matches the rest of the launcher's popup language.
 */
public class MultiRTConfigDialog {

    private AlertDialog mDialog;
    private RecyclerView mDialogView;
    private RTRecyclerViewAdapter mAdapter;
    private TextView mEditButton;
    private boolean mEditing;

    /** Show the dialog, refreshes the adapter data before showing it */
    public void show() {
        refresh();
        if (mDialog != null && !mDialog.isShowing()) mDialog.show();
    }

    @SuppressLint("NotifyDataSetChanged") //only used to completely refresh the list, it is necessary
    public void refresh() {
        RecyclerView.Adapter<?> adapter = mDialogView != null ? mDialogView.getAdapter() : null;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public MultiRTConfigDialog get() {
        return this;
    }

    public boolean isEditing() {
        return mEditing;
    }

    /** Build the dialog behavior and style */
    public void prepare(Context activity, ActivityResultLauncher<Object> installJvmLauncher) {
        View sheet = LayoutInflater.from(activity)
                .inflate(R.layout.dialog_runtime_picker, null);

        mDialogView = sheet.findViewById(R.id.rt_dialog_list);
        mDialogView.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false));
        mAdapter = new RTRecyclerViewAdapter();
        mAdapter.setDialog(get());
        mDialogView.setAdapter(mAdapter);

        mDialog = new AlertDialog.Builder(activity)
                .setView(sheet)
                .create();

        // Entrance: the sheet is already animated by the window style; the
        // inner content gets a light rise so it feels composed, not dumped.
        sheet.setAlpha(0f);
        sheet.setTranslationY(18f * activity.getResources().getDisplayMetrics().density);
        sheet.animate().alpha(1f).translationY(0f).setStartDelay(70).setDuration(280)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();

        TextView addButton = sheet.findViewById(R.id.rt_dialog_add);
        mEditButton = sheet.findViewById(R.id.rt_dialog_edit);
        View closeButton = sheet.findViewById(R.id.rt_dialog_close);

        View[] pressTargets = new View[]{
                addButton, mEditButton, closeButton
        };
        for (View v : pressTargets) if (v != null) net.kdt.pojavlaunch.UiMotion.pressFeedback(v);

        if (closeButton != null) closeButton.setOnClickListener(v -> mDialog.dismiss());
        if (addButton != null) {
            addButton.setOnClickListener(v -> {
                mDialog.dismiss();
                installJvmLauncher.launch(null);
            });
        }
        if (mEditButton != null) {
            mEditButton.setOnClickListener(v -> {
                mEditing = !mEditing;
                mAdapter.setIsEditing(mEditing);
                mEditButton.setText(mEditing
                        ? R.string.multirt_config_setdefault : R.string.multirt_delete_runtime);
            });
        }

        prepareWindow(activity);
        mDialog.show();
        applyWindowWidth(activity);
    }

    /** Surface + animation are set before show so no stock frame ever flashes. */
    private void prepareWindow(Context context) {
        Window window = mDialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(R.drawable.bg_cs_dialog);
        window.setWindowAnimations(R.style.CsDialogAnim);
    }

    private void applyWindowWidth(Context context) {
        Window window = mDialog.getWindow();
        if (window == null) return;
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        int wanted = (int) (400 * dm.density);
        int max = (int) (dm.widthPixels * 0.94f);
        window.setLayout(Math.min(wanted, max), android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
