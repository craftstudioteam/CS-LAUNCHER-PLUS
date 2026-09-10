package net.kdt.pojavlaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.utils.animation.JellyBounceInterpolator;
import net.kdt.pojavlaunch.utils.animation.MotionAttach;
import net.kdt.pojavlaunch.utils.animation.MotionCurves;
import net.kdt.pojavlaunch.utils.animation.MotionSpec;
import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * Small, dependency-free motion system used by launcher screens.
 *
 * It deliberately uses view-property animations: they are hardware accelerated on
 * API 21+, cancel safely when a fragment is replaced, and do not keep a reference
 * to an Activity. Motion is short enough to feel responsive on low-end devices.
 *
 * All durations run through {@link MotionSpeed} so the global "Animation Speed"
 * setting (launcher_animate_speed, A9) retimes every surface from one place.
 */
public final class UiMotion {
    private static final long ENTER_DURATION = MotionSpec.MEDIUM;
    /** The launcher's hero curve — see {@link MotionCurves}. */
    private static final TimeInterpolator ENTER = MotionCurves.EMPHASIZED_DECELERATE;
    private static final TimeInterpolator PRESS_OUT = MotionCurves.SPRING_SOFT;
    private static final JellyBounceInterpolator JELLY = new JellyBounceInterpolator();

    private UiMotion() { }

    /** Gives every newly created screen a consistent, subtle entrance. */
    public static void revealScreen(View root) {
        if (root == null) return;
        if (!MotionSpeed.isEnabled()) { settle(root); return; }
        if (root.getWindowToken() == null) return;
        root.animate().cancel();
        root.setAlpha(0f);
        root.setTranslationY(MotionSpec.travel(dp(root, 20)));
        root.setScaleX(0.97f);
        root.setScaleY(0.97f);
        root.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionSpeed.scale(ENTER_DURATION))
                .setInterpolator(ENTER)
                .withEndAction(() -> {
                    root.setAlpha(1f);
                    root.setTranslationY(0f);
                    root.setScaleX(1f);
                    root.setScaleY(1f);
                })
                .start();
        cascadeChildren(root);
        // One motion language: the same screen that fades in also gets the shared press feedback,
        // so no screen has to remember to wire its buttons up by hand.
        root.post(() -> MotionAttach.attachAll(root));
    }

    /**
     * Reveals the first visual layer of a page in a short cascade. Limiting this
     * to direct children keeps RecyclerView, text input and game-control internals
     * untouched while still making every page feel intentionally composed.
     */
    private static void cascadeChildren(View root) {
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        int animated = 0;
        for (int i = 0; i < group.getChildCount() && animated < 14; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0 || child.getHeight() == 0) continue;
            // CRITICAL: skip views that are already mid-animation (fragment-owned
            // entrances). Cancelling them here used to freeze their in-flight
            // translationY as the "resting" spot — top bars ended up half cut.
            if (child.getAlpha() < 1f || child.getTranslationY() != 0f
                    || child.getScaleX() != 1f || child.getTranslationX() != 0f) continue;
            final float originalTranslationY = child.getTranslationY();
            final float originalScaleX = child.getScaleX();
            final float originalScaleY = child.getScaleY();
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(originalTranslationY + MotionSpec.travel(dp(child, 24)));
            child.setScaleX(originalScaleX * 0.94f);
            child.setScaleY(originalScaleY * 0.94f);
            child.animate()
                    .alpha(1f)
                    .translationY(originalTranslationY)
                    .scaleX(originalScaleX)
                    .scaleY(originalScaleY)
                    .setStartDelay(90 + MotionSpec.staggerMs(animated))
                    .setDuration(MotionSpeed.scale(380L))
                    .setInterpolator(MotionCurves.SPRING_SOFT)
                    .start();
            animated++;
        }
    }

    /** Animates app chrome (header/account/settings) after its layout is attached. */
    public static void revealChrome(View chrome) {
        if (chrome == null) return;
        if (!MotionSpeed.isEnabled()) return;
        chrome.post(() -> {
            if (chrome.getWindowToken() == null) return;
            chrome.setAlpha(0f);
            chrome.setTranslationY(-MotionSpec.travel(dp(chrome, 22)));
            chrome.setScaleX(0.95f);
            chrome.setScaleY(0.95f);
            chrome.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(60)
                    .setDuration(MotionSpeed.scale(430L)).setInterpolator(MotionCurves.SPRING).start();
        });
    }

    /**
     * Big, bouncy entrance for the one hero element of a screen — the PLAY button,
     * the logo, a featured card. Jelly pop: spins in from -5°, scales 0.55 → 1 with
     * elastic land. Hardware-layered for the duration so even large views stay 60fps.
     */
    public static void heroIn(View view) {
        if (view == null) return;
        if (!MotionSpeed.isEnabled()) { settle(view); return; }
        if (view.getWindowToken() == null) { view.post(() -> heroIn(view)); return; }
        view.animate().cancel();
        view.setAlpha(0f);
        view.setScaleX(0.55f);
        view.setScaleY(0.55f);
        view.setRotation(-5f);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setStartDelay(MotionSpeed.scale(140L))
                .setDuration(MotionSpeed.scale(680L))
                .setInterpolator(JELLY)
                .withLayer()
                .withEndAction(() -> {
                    view.setRotation(0f);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                })
                .start();
    }

    /**
     * Ambient loops are refused while a boosted game session owns the device: every infinite animator
     * is a wake-up on a core Minecraft would rather have, and the launcher surface is not on screen to
     * see it. Finite, interaction-driven motion is untouched, and the callers re-run these on the next
     * bind, so nothing stays permanently un-animated after the session ends.
     */
    private static boolean ambientAllowed() {
        return net.kdt.pojavlaunch.performance.LauncherQuietPolicy.animationsAllowed();
    }

    /**
     * Gentle infinite float: the view drifts up, breathes a little larger, settles
     * back — ambience for decorative surfaces. Do NOT put this on clickable views:
     * it animates the same scale/translation the press feedback uses. Cancels
     * itself when the view detaches, so no leak, no stale frames off-screen.
     */
    public static void floatIdle(View view) {
        if (view == null || !MotionSpeed.isEnabled() || !ambientAllowed()) return;
        final float amp = MotionSpec.travel(dp(view, 5));
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2300L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            view.setTranslationY(-amp * t);
            float scale = 1f + 0.018f * t;
            view.setScaleX(scale);
            view.setScaleY(scale);
        });
        attachAutoCancel(view, animator);
        animator.start();
    }

    /**
     * Slow alpha breathing for glow rims and highlights (min ↔ max over one half
     * period, then back). Decorative only; cancels on detach like floatIdle.
     */
    public static void breathPulse(View view, float minAlpha, float maxAlpha, long halfPeriodMs) {
        if (view == null || !MotionSpeed.isEnabled() || !ambientAllowed()) return;
        ValueAnimator animator = ValueAnimator.ofFloat(minAlpha, maxAlpha);
        animator.setDuration(Math.max(400L, halfPeriodMs));
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> view.setAlpha((Float) a.getAnimatedValue()));
        attachAutoCancel(view, animator);
        animator.start();
    }

    /** Runs an ambient animator only while its view is on a window. */
    private static void attachAutoCancel(View view, ValueAnimator animator) {
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }
            @Override public void onViewDetachedFromWindow(View v) {
                animator.cancel();
                v.removeOnAttachStateChangeListener(this);
            }
        });
    }

    /**
     * Tactile press feedback for touch targets: quick scale-down on touch down,
     * springy settle on release. Returns false from the listener so the view's
     * own click handling keeps working.
     */
    public static void pressFeedback(View... views) {
        for (View view : views) MotionAttach.press(view);
    }

    /**
     * One call per screen: gives every button, card and row underneath {@code root} the same
     * press feedback. Screens no longer need to list their views one by one, which is what made
     * the old UI feel inconsistent — some buttons reacted, most did not.
     */
    public static void attachTouchFeedback(View root) {
        MotionAttach.attachAll(root);
    }

    /** Crossfades one view out and another in, on the shared standard curve. */
    public static void crossfade(View outgoing, View incoming) {
        if (incoming == null) return;
        long duration = MotionSpec.mediumMs();
        if (!MotionSpec.enabled()) {
            if (outgoing != null) outgoing.setVisibility(View.GONE);
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(1f);
            return;
        }
        incoming.animate().cancel();
        incoming.setAlpha(0f);
        incoming.setVisibility(View.VISIBLE);
        incoming.animate().alpha(1f).setDuration(duration)
                .setInterpolator(MotionCurves.STANDARD).start();
        if (outgoing != null) {
            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).setDuration(MotionSpec.shortMs())
                    .setInterpolator(MotionCurves.STANDARD_ACCELERATE)
                    .withEndAction(() -> { outgoing.setVisibility(View.GONE); outgoing.setAlpha(1f); })
                    .start();
        }
    }

    /**
     * Slides a view in from an edge. {@code direction}: -1 left, 1 right, -2 top, 2 bottom.
     * Distance follows the user's amplitude setting.
     */
    public static void slideIn(View view, int direction, long startDelay) {
        if (view == null) return;
        if (!MotionSpec.enabled()) { settle(view); return; }
        float distance = MotionSpec.travel(dp(view, 28));
        view.animate().cancel();
        view.setAlpha(0f);
        if (direction == -1) view.setTranslationX(-distance);
        else if (direction == 1) view.setTranslationX(distance);
        else if (direction == -2) view.setTranslationY(-distance);
        else view.setTranslationY(distance);
        view.animate().alpha(1f).translationX(0f).translationY(0f)
                .setStartDelay(startDelay)
                .setDuration(MotionSpec.mediumMs())
                .setInterpolator(MotionCurves.EMPHASIZED_DECELERATE)
                .start();
    }

    /** Springs a view to a scale — used for selection states and emphasis. */
    public static void springScale(View view, float scale) {
        if (view == null) return;
        if (!MotionSpec.enabled()) { view.setScaleX(scale); view.setScaleY(scale); return; }
        view.animate().cancel();
        view.animate().scaleX(scale).scaleY(scale)
                .setDuration(MotionSpec.mediumMs())
                .setInterpolator(MotionCurves.SPRING_SOFT)
                .start();
    }

    /** Staggered entrance for the direct children of any container. */
    public static void stagger(android.view.ViewGroup container) {
        if (container == null) return;
        if (!MotionSpec.enabled()) { settle(container); return; }
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(MotionSpec.travel(dp(child, 12)));
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(MotionSpec.staggerMs(i))
                    .setDuration(MotionSpec.mediumMs())
                    .setInterpolator(MotionCurves.EMPHASIZED_DECELERATE)
                    .start();
        }
    }

    /** Soft drop-in for a single element (dialog content, chips, banners...). */
    public static void fadeInDown(View view, long startDelay) {
        if (view == null) return;
        if (!MotionSpeed.isEnabled()) { settle(view); return; }
        if (view.getWindowToken() == null) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(-dp(view, 10));
        view.animate().alpha(1f).translationY(0f)
                .setStartDelay(MotionSpeed.scale(startDelay))
                .setDuration(MotionSpeed.scale(280L))
                .setInterpolator(ENTER)
                .start();
    }

    /**
     * Success/check "pop" (A3) — jelly-bounce overshoot on scale, used for
     * moments like a runtime install completing or a check badge appearing.
     * Cancel-safe: callers may pass views that get recycled.
     */
    public static void popIn(View view) {
        if (view == null) return;
        if (!MotionSpeed.isEnabled()) { settle(view); return; }
        view.animate().cancel();
        view.setScaleX(0.3f);
        view.setScaleY(0.3f);
        view.setAlpha(1f);
        view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionSpeed.scale(520L))
                .setInterpolator(JELLY)
                .start();
    }

    /**
     * reactbits "ShinyText": a metallic highlight band sweeps across the text
     * forever (title shimmer). Uses the text's own colour as the base so it
     * stays perfectly readable between sweeps. Clears itself up on detach.
     */
    public static void shineText(final TextView view, final long periodMs) {
        if (view == null || !MotionSpeed.isEnabled() || !ambientAllowed()) return;
        if (Boolean.TRUE.equals(view.getTag(R.id.motion_shine_tag))) return;
        view.setTag(R.id.motion_shine_tag, Boolean.TRUE);
        view.post(() -> {
            if (view.getWindowToken() == null) return;
            float textW = view.getPaint().measureText(
                    view.getText() != null ? view.getText().toString() : "");
            final float w = Math.max(1f, Math.max(view.getWidth(), textW));
            final int base = view.getCurrentTextColor();
            final android.graphics.LinearGradient shader = new android.graphics.LinearGradient(
                    -w, 0f, 0f, 0f,
                    new int[]{base, 0x88FFFFFF, base},
                    new float[]{0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            final android.graphics.Matrix matrix = new android.graphics.Matrix();
            final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(MotionSpeed.scale(Math.max(600L, periodMs)));
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                matrix.setTranslate(w * 2f * t, 0f);
                shader.setLocalMatrix(matrix);
                if (view.getPaint().getShader() != shader) view.getPaint().setShader(shader);
                view.invalidate();
            });
            // Never leave a stale shader parked on a recycled/reused TextView.
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    view.getPaint().setShader(null);
                    view.invalidate();
                }
            });
            attachAutoCancel(view, animator);
            animator.start();
        });
    }

    /** Live CountUp animators — cancelled and replaced on re-bind, never stacked. */
    private static final java.util.WeakHashMap<TextView, ValueAnimator> COUNTERS =
            new java.util.WeakHashMap<>();

    /**
     * reactbits "CountUp": the number rolls up to its target on the hero curve
     * instead of popping in (player counts, stats, badges).
     */
    public static void countUp(final TextView view, final int target, final long durationMs,
                               final String prefix, final String suffix) {
        if (view == null) return;
        final String pre = prefix == null ? "" : prefix;
        final String post = suffix == null ? "" : suffix;
        ValueAnimator running = COUNTERS.get(view);
        if (running != null) running.cancel();
        if (!MotionSpeed.isEnabled() || target <= 0) {
            view.setText(pre + target + post);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(0, target);
        animator.setDuration(MotionSpeed.scale(durationMs));
        animator.setInterpolator(MotionCurves.EMPHASIZED_DECELERATE);
        animator.addUpdateListener(a -> view.setText(pre + a.getAnimatedValue() + post));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { COUNTERS.remove(view); }
        });
        COUNTERS.put(view, animator);
        attachAutoCancel(view, animator);
        animator.start();
    }

    /** Cards that lean toward the finger (reactbits "TiltCard") plus the juicy press. */
    public static void pressTiltFeedback(View... views) {
        for (View view : views) MotionAttach.pressTilt(view);
    }

    /**
     * Skeleton/shimmer pulse (A7) — infinite alpha pulse 0.3 ↔ 0.6 on a
     * 1000 ms linear loop, reversed. Start it on loading placeholders
     * (runtime download deck, download cards); {@link #stopPulse} on detach.
     */
    public static ValueAnimator pulseSkeleton(View view) {
        if (view == null) return null;
        if (!MotionSpeed.isEnabled() || !ambientAllowed()) return null;
        ValueAnimator animator = ValueAnimator.ofFloat(0.3f, 0.6f);
        animator.setDuration(MotionSpeed.scale(1000L));
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.addUpdateListener(a -> view.setAlpha((Float) a.getAnimatedValue()));
        animator.start();
        return animator;
    }

    public static void stopPulse(ValueAnimator animator) {
        if (animator != null) animator.cancel();
    }

    /** Staggered child entrance for RecyclerViews (layout animation). */
    public static void revealList(RecyclerView list) {
        if (list == null || list.getContext() == null) return;
        if (!MotionSpeed.isEnabled()) return;
        LayoutAnimationController controller = AnimationUtils.loadLayoutAnimation(
                list.getContext(), R.anim.list_item_enter);
        list.setLayoutAnimation(controller);
        list.scheduleLayoutAnimation();
    }

    /** Instant final state — used when animations are turned off. */
    private static void settle(View root) {
        root.animate().cancel();
        root.setAlpha(1f);
        root.setTranslationX(0f);
        root.setTranslationY(0f);
        root.setScaleX(1f);
        root.setScaleY(1f);
    }

    private static float dp(View view, float value) {
        return value * view.getResources().getDisplayMetrics().density;
    }
}
