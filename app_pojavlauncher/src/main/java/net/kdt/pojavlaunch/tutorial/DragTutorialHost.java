package net.kdt.pojavlaunch.tutorial;

import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Bridge between the tutorial overlay and the screen that owns the real
 * instance carousel ({@code LauncherHomeFragment}).
 *
 * <p>The host owns ALL interaction with real instances: it injects fully
 * transient demo items into the real adapter (rendered by the REAL card
 * binder), exposes their live views/rects, and reports real user
 * hold → drag → reorder → release gestures captured from the real
 * {@code ItemTouchHelper}.
 *
 * <p>Demo items are guaranteed in-memory only; the host filters them out of
 * every persistence path (profile order, storage, Firebase, shortcuts).
 */
public interface DragTutorialHost {

    /** Signals captured from the REAL drag gesture chain. */
    interface DemoPracticeListener {
        /** User pressed AND HELD a real card — the real drag state began. */
        void onPracticeDragStart();
        /** While holding, the card actually moved across another slot. */
        void onPracticeDragMoved();
        /** Released AFTER a real reorder — the practice goal. */
        void onPracticeComplete();
    }

    /** Weakref registry so the decor-level overlay can find the live host. */
    final class Registry {
        private static WeakReference<DragTutorialHost> sHost;

        private Registry() { }

        public static void register(@NonNull DragTutorialHost host) {
            sHost = new WeakReference<>(host);
        }

        public static void unregister(@NonNull DragTutorialHost host) {
            WeakReference<DragTutorialHost> w = sHost;
            if (w != null && w.get() == host) sHost = null;
        }

        @Nullable
        public static DragTutorialHost current() {
            WeakReference<DragTutorialHost> w = sHost;
            return w != null ? w.get() : null;
        }
    }

    /**
     * Injects the transient demo card(s) into the real carousel, ensures the
     * carousel is visible (even with zero real profiles) and scrolled so that
     * slot 0 and the demo card are on screen.
     *
     * @return true when the demo card exists and will be laid out shortly.
     */
    boolean beginDragDemo();

    /** True when at least one REAL (non-demo) profile exists. */
    boolean hasRealProfiles();

    /** Number of REAL profiles (dummies excluded). */
    int getRealProfileCount();

    /** Live item view of the "Steve" demo card, or null before layout. */
    @Nullable View getDemoCardView();

    /** Screen rect of the demo card, or null before layout. */
    @Nullable Rect getDemoCardScreenRect();

    /** Screen rect of the front (slot 0) position, or null if unknown. */
    @Nullable Rect getFrontSlotScreenRect();

    /** Resets scale/elevation/translation applied to the demo card. */
    void resetDemoCardTransform();

    /**
     * Demo finished: remove the extra placeholder dummy. The Steve card is
     * also removed when the user has enough real profiles to practice on.
     *
     * @param keepSteveForPractice keep the Steve demo card so the user can
     *                             physically practise a real reorder on it
     */
    void removeDemoCardsAfterDemo(boolean keepSteveForPractice);

    /** Attach/detach real-gesture reporting. */
    void setDemoPracticeListener(@Nullable DemoPracticeListener listener);

    /** Full teardown: remove every demo card, reset transforms, restore UI. */
    void endDragTutorial();
}
