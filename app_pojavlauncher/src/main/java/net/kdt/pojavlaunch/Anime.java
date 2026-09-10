package net.kdt.pojavlaunch;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import net.kdt.pojavlaunch.utils.animation.MotionSpeed;

/**
 * anime.js–flavoured motion vocabulary for Android views.
 *
 * The web library's ideas that translate one-to-one to mobile:
 * <ul>
 *   <li><b>eases</b>: {@link #OUT_EXPO}, {@link #OUT_QUART}, {@link #IN_OUT_QUART},
 *       {@link #OUT_BACK}, {@link #OUT_ELASTIC}, {@link #IN_BACK}, {@link #SPRING}.</li>
 *   <li><b>stagger(step)</b>: {@link #stagger(ViewGroup, long, long, Fx)} — children reveal
 *       sequentially; {@link #staggerCenter(ViewGroup, long, long, Fx)} — from the middle out.</li>
 *   <li><b>keyframes</b>: {@link #pulse(View)} / {@link #shake(View)} / {@link #pop(View)}.</li>
 *   <li><b>timeline</b>: just pass increasing {@code delay}s — every helper takes one.</li>
 * </ul>
 * All durations pass through {@link MotionSpeed} so the user's animation-speed
 * setting (and the "animations off" switch) is respected everywhere.
 */
public final class Anime {

    private Anime() {}

    // ── eases (cubic-bezier approximations of anime.js built-ins) ──────────
    /** anime 'outExpo' — fast start, long silky settle. */
    public static final Interpolator OUT_EXPO   = t -> t >= 1f ? 1f : (float) (1 - Math.pow(2, -10 * t));
    /** anime 'outQuart'. */
    public static final Interpolator OUT_QUART  = t -> 1f - (float) Math.pow(1 - t, 4);
    /** anime 'inOutQuart'. */
    public static final Interpolator IN_OUT_QUART = t -> t < 0.5f ? 8 * t * t * t * t : 1f - (float) Math.pow(-2 * t + 2, 4) / 2f;
    /** anime 'outBack' (overshoot 1.70158). */
    public static final Interpolator OUT_BACK   = new OvershootInterpolator(1.70158f);
    /** anime 'inBack' — pulls back before leaving (exits). */
    public static final Interpolator IN_BACK    = new android.view.animation.AnticipateInterpolator(1.4f);
    /** anime spring({ bounce: .35 }) feel. */
    public static final Interpolator SPRING     = new OvershootInterpolator(1.35f);
    /** anime 'outElastic(1, .5)' — for tiny check-marks and badges. */
    public static final Interpolator OUT_ELASTIC = t -> {
        if (t == 0f || t == 1f) return t;
        float p = 0.5f;
        return (float) (Math.pow(2, -10 * t) * Math.sin((t - p / 4) * (2 * Math.PI) / p) + 1);
    };

    // ── property presets ("fx") ─────────────────────────────────────────────
    public enum Fx { FADE_UP, FADE_DOWN, FADE_LEFT, FADE_RIGHT, SCALE_IN, POP, FLIP_UP }

    /** Sets the "from" state for a preset. */
    private static void prime(View v, Fx fx, float d) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(0f); v.setTranslationY(0f);
        v.setScaleX(1f); v.setScaleY(1f); v.setRotationX(0f);
        switch (fx) {
            case FADE_UP:    v.setTranslationY(18f * d); break;
            case FADE_DOWN:  v.setTranslationY(-18f * d); break;
            case FADE_LEFT:  v.setTranslationX(24f * d); break;   // comes in from the right
            case FADE_RIGHT: v.setTranslationX(-24f * d); break;  // comes in from the left
            case SCALE_IN:   v.setScaleX(0.88f); v.setScaleY(0.88f); break;
            case POP:        v.setScaleX(0.5f);  v.setScaleY(0.5f);  break;
            case FLIP_UP:    v.setRotationX(-35f); v.setTranslationY(10f * d); break;
        }
    }

    /** Reveal a single view with a preset. Returns the animator so callers can chain. */
    public static ViewPropertyAnimator in(View v, Fx fx, long delay, long duration, Interpolator ease) {
        if (v == null) return null;
        if (!MotionSpeed.isEnabled()) { settle(v); return null; }
        float d = v.getResources().getDisplayMetrics().density;
        prime(v, fx, d);
        ViewPropertyAnimator a = v.animate().alpha(1f).translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f).rotationX(0f)
                .setStartDelay(MotionSpeed.scale(delay))
                .setDuration(MotionSpeed.scale(duration))
                .setInterpolator(ease == null ? OUT_EXPO : ease);
        a.start();
        return a;
    }

    /** Convenience: outExpo fade-up, the anime.js "default" feel. */
    public static void in(View v, long delay) { in(v, Fx.FADE_UP, delay, 520, OUT_EXPO); }

    /** anime stagger(step): each child of {@code group} reveals {@code step} ms after the previous. */
    public static void stagger(ViewGroup group, long startDelay, long step, Fx fx) {
        if (group == null) return;
        int n = group.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = group.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            in(c, fx, startDelay + i * step, fx == Fx.POP ? 420 : 520,
                    fx == Fx.POP || fx == Fx.SCALE_IN ? OUT_BACK : OUT_EXPO);
        }
    }

    /** anime stagger(step, { from: 'center' }). */
    public static void staggerCenter(ViewGroup group, long startDelay, long step, Fx fx) {
        if (group == null) return;
        int n = group.getChildCount();
        float mid = (n - 1) / 2f;
        for (int i = 0; i < n; i++) {
            View c = group.getChildAt(i);
            long delay = startDelay + (long) (Math.abs(i - mid) * step);
            in(c, fx, delay, 460, OUT_BACK);
        }
    }

    /** Exit with inBack + fade, then run {@code end}. */
    public static void out(View v, Fx towards, long duration, Runnable end) {
        if (v == null) { if (end != null) end.run(); return; }
        if (!MotionSpeed.isEnabled()) { if (end != null) end.run(); return; }
        float d = v.getResources().getDisplayMetrics().density;
        float tx = 0, ty = 0, s = 1f;
        switch (towards) {
            case FADE_UP:    ty = -14f * d; break;
            case FADE_DOWN:  ty = 14f * d; break;
            case FADE_LEFT:  tx = -24f * d; break;
            case FADE_RIGHT: tx = 24f * d; break;
            case SCALE_IN: case POP: s = 0.9f; break;
            default: break;
        }
        v.animate().cancel();
        ViewPropertyAnimator a = v.animate().alpha(0f).translationX(tx).translationY(ty).scaleX(s).scaleY(s)
                .setStartDelay(0).setDuration(MotionSpeed.scale(duration)).setInterpolator(IN_BACK);
        if (end != null) a.withEndAction(end);
        a.start();
    }

    // ── keyframe-style micro interactions ───────────────────────────────────
    /** keyframes: [{scale:1.06},{scale:1}] — a confident "I'm ready" pulse. */
    public static void pulse(View v) {
        if (v == null || !MotionSpeed.isEnabled()) return;
        v.animate().cancel();
        v.setScaleX(1f); v.setScaleY(1f); v.setAlpha(1f); // never strand a half-faded entrance
        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(MotionSpeed.scale(140)).setInterpolator(OUT_QUART)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(MotionSpeed.scale(420)).setInterpolator(SPRING).start())
                .start();
    }

    /** keyframes: x: [-6, 6, -4, 4, 0] — "no, that doesn't work". */
    public static void shake(View v) {
        if (v == null || !MotionSpeed.isEnabled()) return;
        float d = v.getResources().getDisplayMetrics().density;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(MotionSpeed.scale(420));
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            float x = (float) (Math.sin(t * Math.PI * 4) * (1 - t) * 6 * d);
            v.setTranslationX(x);
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { v.setTranslationX(0f); }
        });
        a.start();
    }

    /** Scale 0.5 → 1 with outElastic — the check-mark / badge pop. */
    public static void pop(View v) {
        if (v == null) return;
        if (!MotionSpeed.isEnabled()) { settle(v); return; }
        v.animate().cancel();
        v.setAlpha(0f); v.setScaleX(0.5f); v.setScaleY(0.5f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(0).setDuration(MotionSpeed.scale(560)).setInterpolator(OUT_ELASTIC).start();
    }

    /** Gentle infinite alpha breathing for non-clickable decorative rings. Cancels on detach. */
    public static void breathe(View v, float min, float max, long halfPeriod) {
        if (v == null || !MotionSpeed.isEnabled()) return;
        if (!net.kdt.pojavlaunch.performance.LauncherQuietPolicy.animationsAllowed()) return;
        ValueAnimator a = ValueAnimator.ofFloat(min, max);
        a.setDuration(Math.max(500L, halfPeriod));
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(IN_OUT_QUART);
        a.addUpdateListener(an -> v.setAlpha((float) an.getAnimatedValue()));
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { a.cancel(); view.removeOnAttachStateChangeListener(this); }
        });
        a.start();
    }

    /** Text swap with a vertical "flip" (old text exits up, new text enters from below). */
    public static void swapText(TextView tv, CharSequence next) {
        if (tv == null) return;
        if (!MotionSpeed.isEnabled() || tv.getText() == null || tv.getText().toString().contentEquals(next)) {
            tv.setText(next); return;
        }
        float d = tv.getResources().getDisplayMetrics().density;
        tv.animate().cancel();
        tv.animate().alpha(0f).translationY(-6f * d).setDuration(MotionSpeed.scale(120)).setInterpolator(IN_OUT_QUART)
                .withEndAction(() -> {
                    tv.setText(next);
                    tv.setTranslationY(8f * d);
                    tv.animate().alpha(1f).translationY(0f).setDuration(MotionSpeed.scale(320)).setInterpolator(OUT_EXPO).start();
                }).start();
    }

    /** anime "letterSpacing" tween — a title that tightens into place. */
    public static void tightenTitle(TextView tv, long delay) {
        if (tv == null || !MotionSpeed.isEnabled()) return;
        ValueAnimator a = ValueAnimator.ofFloat(0.12f, -0.01f);
        a.setStartDelay(MotionSpeed.scale(delay));
        a.setDuration(MotionSpeed.scale(720));
        a.setInterpolator(OUT_EXPO);
        a.addUpdateListener(an -> tv.setLetterSpacing((float) an.getAnimatedValue()));
        a.start();
    }

    /** Resets any transform an interrupted animation might have left. */
    public static void settle(View v) {
        if (v == null) return;
        v.setAlpha(1f); v.setTranslationX(0f); v.setTranslationY(0f);
        v.setScaleX(1f); v.setScaleY(1f); v.setRotationX(0f);
    }
}
