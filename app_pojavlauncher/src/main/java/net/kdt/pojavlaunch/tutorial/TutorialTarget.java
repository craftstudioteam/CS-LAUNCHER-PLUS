package net.kdt.pojavlaunch.tutorial;

import android.app.Activity;
import android.view.View;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Resolves the on-screen {@link View} a tutorial step or task should highlight.
 *
 * <p>Resolution is lazy (executed when the step is shown or on screen changes, not when
 * the tutorial is built) so the framework never holds stale view references across
 * layout passes, rotations, or fragment transactions. Returning {@code null} means
 * "no highlight for this step" — the overlay then shows a centered tooltip over a plain dim.
 */
public interface TutorialTarget {

    /** Resolve the target view for the currently-shown step, or null. */
    @Nullable
    View resolve(@NonNull Activity activity);

    /** No highlight — tooltip is centered over a plain dim. */
    @NonNull
    static TutorialTarget none() {
        return activity -> null;
    }

    /** Resolve a single view by id (activity-wide lookup across all active fragments). */
    @NonNull
    static TutorialTarget byId(@IdRes final int viewId) {
        return activity -> {
            try {
                View v = activity.findViewById(viewId);
                return (v != null && v.getVisibility() == View.VISIBLE && v.isAttachedToWindow()) ? v : null;
            } catch (Throwable ignored) {
                return null;
            }
        };
    }

    /**
     * Try several ids in order, returning the first visible and attached one.
     * Used for primary target + layout fallbacks.
     */
    @NonNull
    static TutorialTarget firstVisible(@IdRes final int... viewIds) {
        return activity -> {
            if (viewIds == null) return null;
            for (int id : viewIds) {
                try {
                    View v = activity.findViewById(id);
                    if (v != null && v.getVisibility() == View.VISIBLE && v.isAttachedToWindow()) {
                        return v;
                    }
                } catch (Throwable ignored) { }
            }
            return null;
        };
    }

    /** Custom functional provider. */
    @NonNull
    static TutorialTarget custom(@NonNull TutorialTarget provider) {
        return provider;
    }
}
