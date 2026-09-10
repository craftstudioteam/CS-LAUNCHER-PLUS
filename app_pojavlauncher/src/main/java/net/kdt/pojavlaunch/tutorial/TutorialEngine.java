package net.kdt.pojavlaunch.tutorial;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.lang.ref.WeakReference;

/**
 * Central engine orchestrating the interactive guided tutorial system across
 * multiple screens and fragments.
 *
 * <p>Registers global FragmentLifecycleCallbacks on FragmentActivity to seamlessly
 * track screen changes and notify the active TutorialOverlayView without destroying
 * the tutorial state.
 */
public final class TutorialEngine {

    private TutorialEngine() { }

    private static final String PREFS_NAME = "cslauncher_settings";

    @Nullable
    private static WeakReference<TutorialOverlayView> sCurrentOverlay;

    @Nullable
    private static FragmentManager.FragmentLifecycleCallbacks sLifecycleCallbacks;

    @NonNull
    private static SharedPreferences prefs(@NonNull Context ctx) {
        try {
            if (LauncherPreferences.DEFAULT_PREF != null) return LauncherPreferences.DEFAULT_PREF;
        } catch (Throwable ignored) { }
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── State & Persistence ──────────────────────────────────────────

    /** True if the sequence as a whole was completed or skipped to the end. */
    public static boolean isSequenceCompleted(@NonNull Context ctx, @NonNull TutorialSequence sequence) {
        try {
            return prefs(ctx).getBoolean(sequence.getPrefsKey(), false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Mark sequence completion. */
    public static void setSequenceCompleted(@NonNull Context ctx, @NonNull TutorialSequence sequence, boolean completed) {
        try {
            prefs(ctx).edit().putBoolean(sequence.getPrefsKey(), completed).apply();
        } catch (Throwable ignored) { }
    }

    /** Get the state of a specific task within a sequence. */
    @NonNull
    public static TutorialTaskState getTaskState(@NonNull Context ctx,
                                                 @NonNull TutorialSequence sequence,
                                                 @NonNull TutorialTask task) {
        try {
            String key = "tutorial.task." + sequence.getId() + "." + task.getId() + ".state";
            String val = prefs(ctx).getString(key, null);
            if (val != null) {
                return TutorialTaskState.valueOf(val);
            }
        } catch (Throwable ignored) { }
        return TutorialTaskState.NOT_STARTED;
    }

    /** Record the state transition for a task (COMPLETED or SKIPPED). */
    public static void setTaskState(@NonNull Context ctx,
                                    @NonNull TutorialSequence sequence,
                                    @NonNull TutorialTask task,
                                    @NonNull TutorialTaskState state) {
        try {
            String key = "tutorial.task." + sequence.getId() + "." + task.getId() + ".state";
            prefs(ctx).edit().putString(key, state.name()).apply();
        } catch (Throwable ignored) { }
    }

    /** Returns true if the sequence should automatically run on launch. */
    public static boolean shouldAutoStart(@NonNull Context ctx, @NonNull TutorialSequence sequence) {
        return !isSequenceCompleted(ctx, sequence);
    }

    /** True if any tutorial overlay is currently attached to the window. */
    public static boolean isShowing() {
        TutorialOverlayView v = sCurrentOverlay != null ? sCurrentOverlay.get() : null;
        try {
            return v != null && v.isAttachedToWindow();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── Execution & Global Lifecycle ────────────────────────────────

    /**
     * Start the tutorial sequence on the given activity.
     */
    public static void start(@NonNull final Activity activity,
                             @NonNull final TutorialSequence sequence) {
        if (activity.isFinishing()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
        } catch (Throwable ignored) { }

        Runnable show = () -> {
            if (activity.isFinishing()) return;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return;
            } catch (Throwable ignored) { }
            if (isShowing()) return;

            try {
                TutorialOverlayView overlay = new TutorialOverlayView(
                        activity, sequence, new TutorialOverlayView.Listener() {
                    @Override
                    public void onTaskCompleted(@NonNull TutorialTask task, int taskIndex) {
                        setTaskState(activity, sequence, task, TutorialTaskState.COMPLETED);
                    }

                    @Override
                    public void onTaskSkipped(@NonNull TutorialTask task, int taskIndex) {
                        setTaskState(activity, sequence, task, TutorialTaskState.SKIPPED);
                    }

                    @Override
                    public void onSequenceFinished(@NonNull TutorialOverlayView overlay, boolean allCompleted) {
                        setSequenceCompleted(activity, sequence, true);
                        unregisterLifecycleCallbacks(activity);
                        WeakReference<TutorialOverlayView> cur = sCurrentOverlay;
                        if (cur != null && cur.get() == overlay) sCurrentOverlay = null;
                    }

                    @Override
                    public void onSequenceCancelled(@NonNull TutorialOverlayView overlay) {
                        setSequenceCompleted(activity, sequence, true);
                        unregisterLifecycleCallbacks(activity);
                        WeakReference<TutorialOverlayView> cur = sCurrentOverlay;
                        if (cur != null && cur.get() == overlay) sCurrentOverlay = null;
                    }
                });

                // Attach global fragment lifecycle monitor
                registerLifecycleCallbacks(activity, overlay);

                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                decor.addView(overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                sCurrentOverlay = new WeakReference<>(overlay);
            } catch (Throwable ignored) { }
        };

        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) show.run();
            else activity.runOnUiThread(show);
        } catch (Throwable ignored) {
            try { activity.runOnUiThread(show); } catch (Throwable ignored2) { }
        }
    }

    private static void registerLifecycleCallbacks(@NonNull Activity activity,
                                                   @NonNull final TutorialOverlayView overlay) {
        if (!(activity instanceof FragmentActivity)) return;
        FragmentActivity fa = (FragmentActivity) activity;
        unregisterLifecycleCallbacks(activity);

        sLifecycleCallbacks = new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                              @NonNull View v, @Nullable Bundle savedInstanceState) {
                v.post(() -> {
                    if (overlay.isAttachedToWindow()) {
                        overlay.onScreenViewCreated(f, v);
                    }
                });
            }

            @Override
            public void onFragmentResumed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                if (overlay.isAttachedToWindow()) {
                    overlay.onScreenResumed(f);
                }
            }

            @Override
            public void onFragmentViewDestroyed(@NonNull FragmentManager fm, @NonNull Fragment f) {
                if (overlay.isAttachedToWindow()) {
                    overlay.onScreenViewDestroyed(f);
                }
            }
        };

        fa.getSupportFragmentManager().registerFragmentLifecycleCallbacks(sLifecycleCallbacks, true);
    }

    private static void unregisterLifecycleCallbacks(@NonNull Activity activity) {
        if (!(activity instanceof FragmentActivity) || sLifecycleCallbacks == null) return;
        try {
            FragmentActivity fa = (FragmentActivity) activity;
            fa.getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(sLifecycleCallbacks);
        } catch (Throwable ignored) { }
        sLifecycleCallbacks = null;
    }

    /** Dismiss current overlay without completing. */
    public static void dismissCurrent() {
        TutorialOverlayView v = sCurrentOverlay != null ? sCurrentOverlay.get() : null;
        sCurrentOverlay = null;
        if (v == null) return;
        try { v.dismissInternal(); } catch (Throwable ignored) { }
    }
}
