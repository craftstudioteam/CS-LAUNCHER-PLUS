package net.kdt.pojavlaunch.modloaders.modpacks;

import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.modloaders.InstalledModAdapter;

import java.io.File;

/**
 * "Hold the copy button, the mod follows your finger, drop it on a profile".
 *
 * The button used to do nothing on its own — the action existed in the row but
 * no drag was ever started, so people tapped it, the picker opened, and the
 * button still felt broken. This wires the gesture the list was designed for:
 *
 *   • long-press COPY on a mod row   → a chip with the jar name sticks to the
 *     finger; the profile cards on the home pane light up green under it;
 *     releasing on a card copies the file into that profile's mods folder.
 *   • releasing anywhere else         → the profile picker opens instead, so
 *     the action is never lost.
 *   • short tap                       → picker, unchanged (keyboard users and
 *     small screens still have the explicit path).
 */
public final class ModCopyDrag {

    /**
     * The jar currently being carried. It stays set until it is actually used
     * (a successful drop) so the picker can rescue a release that missed every
     * profile card — and so the picker, if it opened behind the shadow, knows to
     * start the same carry instead of showing a static list.
     */
    public static File pendingDragFallback;
    /** Set by the picker when it should keep carrying the pending jar itself. */
    public static boolean handoffToPicker;
    /** The jar of the most recent carry, kept after a release that missed. */
    private static File sLastCarry;
    /** Host hook: put up the fallback UI when the user released on nothing. */
    public static Runnable onMissedDrop;

    private ModCopyDrag() {}

    /**
     * Begins the drag from {@code anchor} (the copy button). Returns true when
     * the system accepted the drag, i.e. exactly what View.OnLongClickListener
     * wants.
     */
    public static boolean start(View anchor, File modFile,
                                InstalledModAdapter.ModActionListener actions) {
        return start(anchor, modFile, actions, true);
    }

    /**
     * @param allowCarry false when the release would have nowhere to land (no
     *                   profile list on screen): then the hold behaves like a
     *                   tap and the picker opens straight away, which is the
     *                   right answer — not a gesture the user has to repeat.
     */
    public static boolean start(View anchor, File modFile,
                                InstalledModAdapter.ModActionListener actions,
                                boolean allowCarry) {
        if (anchor == null || modFile == null || !modFile.isFile()) return false;
        if (!allowCarry) return false;
        ClipData data = ModCopyDragShadow.clipDataFor(modFile);
        ModCopyDragShadow shadow = new ModCopyDragShadow(
                anchor, modFile, anchor.getResources().getDisplayMetrics().density);
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pendingDragFallback = modFile;
        handoffToPicker = false;
        if (actions != null) actions.onCopyModDragStarted(modFile);
        boolean started;
        try {
            started = anchor.startDragAndDrop(data, shadow, modFile,
                    View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_OPAQUE);
        } catch (Throwable t) {
            try {
                // pre-3.13 devices: the old spelling
                started = anchor.startDrag(data, shadow, modFile,
                        View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_OPAQUE);
            } catch (Throwable t2) {
                started = false;
            }
        }
        if (!started) {
            // The system refused the drag: behave like a plain tap and let the
            // host open the picker.
            pendingDragFallback = null;
            return false;
        }
        sLastCarry = modFile;
        // Releasing outside any card must not swallow the action: watch the
        // anchor itself, because DRAG_ENDED is always delivered to the view the
        // drag started from.
        anchor.setOnDragListener((v, ev) -> {
            if (ev.getAction() == DragEvent.ACTION_DRAG_ENDED && pendingDragFallback != null) {
                pendingDragFallback = null;
                if (onMissedDrop != null) onMissedDrop.run();
            }
            return true;
        });
        return started;
    }

    /**
     * Makes every row of {@code rv} a drop target: rows scale up and turn green
     * while the carried jar hovers them, and a drop hands (row, file) to
     * {@code onDrop}.
     */
    public static void attachDropTarget(RecyclerView rv, DropHandler onDrop) {
        if (rv == null) return;
        rv.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(View view) { armRow(view, onDrop); }
            @Override public void onChildViewDetachedFromWindow(View view) { view.setOnDragListener(null); }
        });
        // Rows that already exist (the list is populated before this is called)
        for (int i = 0; i < rv.getChildCount(); i++) armRow(rv.getChildAt(i), onDrop);
    }

    private static void armRow(View row, DropHandler onDrop) {
        if (row == null) return;
        final float[] rest = {row.getScaleX(), row.getScaleY(), row.getAlpha()};
        row.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return accepts(event);
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(140).start();
                    highlight(v, true);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.animate().scaleX(rest[0]).scaleY(rest[1]).setDuration(140).start();
                    highlight(v, false);
                    return true;
                case DragEvent.ACTION_DROP:
                    v.animate().scaleX(rest[0]).scaleY(rest[1]).setDuration(160).start();
                    highlight(v, false);
                    Object local = event.getLocalState();
                    File f = local instanceof File ? (File) local : pendingDragFallback;
                    pendingDragFallback = null;
                    if (f != null) onDrop.onDrop(v, f);
                    return true;
                case DragEvent.ACTION_DRAG_ENDED:
                    highlight(v, false);
                    v.animate().scaleX(rest[0]).scaleY(rest[1]).setDuration(160).start();
                    // Deliberately NOT clearing pendingDragFallback: if no row
                    // took the drop, the host uses it to rescue the gesture.
                    return true;
                default:
                    return false;
            }
        });
    }

    private static boolean accepts(DragEvent event) {
        ClipDescription d = event.getClipDescription();
        return d != null && d.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST);
    }

    /** True when a release just happened with no card taking the jar. */
    public static boolean canStillCarry() {
        return sLastCarry != null && pendingDragFallback == sLastCarry;
    }

    /** Green rim + brighter surface while a card is a live drop target. */
    private static void highlight(View view, boolean on) {
        if (view == null) return;
        view.setAlpha(on ? 1f : 0.72f);
        if (!(view.getBackground() instanceof GradientDrawable)) return;
        GradientDrawable bg = (GradientDrawable) view.getBackground().mutate();
        bg.setStroke(on ? (int) (2 * view.getResources().getDisplayMetrics().density) : 0,
                Color.parseColor("#4ADE80"));
        view.setBackground(bg);
    }

    /** The row that received the drop, plus the jar to copy into its profile. */
    public interface DropHandler {
        void onDrop(View row, File modFile);
    }
}
