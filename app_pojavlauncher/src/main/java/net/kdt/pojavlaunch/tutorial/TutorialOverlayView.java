package net.kdt.pojavlaunch.tutorial;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.fragments.LauncherHomeFragment;
import net.kdt.pojavlaunch.fragments.LocalLoginFragment;
import net.kdt.pojavlaunch.fragments.SelectAuthFragment;

import java.lang.ref.WeakReference;

/**
 * Multi-screen guided tutorial overlay attached to the activity's decor view.
 *
 * <p>Features:
 * <ul>
 *   <li>Dynamic runtime coordinate mapping (accounts for system insets, action bars, cutouts).</li>
 *   <li>Continuous target tracking during scrolling and layout animations.</li>
 *   <li>Soft-keyboard awareness (positions tooltips above inputs when keyboard opens).</li>
 *   <li>Minecraft-styled typography ({@code @font/minecraftia}) on all labels and cards.</li>
 *   <li>Multi-screen lifecycle resilience across fragment replacements.</li>
 * </ul>
 */
public final class TutorialOverlayView extends FrameLayout {

    private static final int SKIP_TEXT_COLOR = 0xFFA89EC8;
    private static final float SPOTLIGHT_GAP = 8f;
    private static final float FINGER_SIZE   = 44f;

    @NonNull  private final WeakReference<Activity> mActivityRef;
    @NonNull  private final TutorialSequence mSequence;
    @Nullable private final Listener mListener;

    private int mCurrentIndex;
    @Nullable private RectF mTargetRect;
    @Nullable private View mResolvedTarget;

    @Nullable private View mTouchBlocker;
    @Nullable private TutorialSpotlightView mSpotlight;
    @Nullable private FingerPointerView mFingerView;
    @Nullable private TooltipView mTooltipView;
    @Nullable private TextView mTopSkipBtn;
    @Nullable private ProfileDragDemoView mDragDemoView;
    @NonNull  private final AccountTaskObserver mAccountObserver = new AccountTaskObserver();

    @Nullable private Typeface mMinecraftFont;
    @Nullable private ViewTreeObserver.OnScrollChangedListener mScrollListener;
    @Nullable private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;

    private float mDownX, mDownY;
    private boolean mDownInsideTarget;
    private boolean mDragDemoUserPhase;

    public interface Listener {
        void onTaskCompleted(@NonNull TutorialTask task, int taskIndex);
        void onTaskSkipped(@NonNull TutorialTask task, int taskIndex);
        void onSequenceFinished(@NonNull TutorialOverlayView overlay, boolean allCompleted);
        void onSequenceCancelled(@NonNull TutorialOverlayView overlay);
    }

    public TutorialOverlayView(@NonNull Activity activity,
                               @NonNull TutorialSequence sequence,
                               @Nullable Listener listener) {
        super(activity);
        mActivityRef = new WeakReference<>(activity);
        mSequence = sequence;
        mListener = listener;

        try {
            mMinecraftFont = ResourcesCompat.getFont(activity, R.font.minecraftia);
        } catch (Throwable ignored) {
            mMinecraftFont = Typeface.DEFAULT_BOLD;
        }

        setClipChildren(false);
        setClipToPadding(false);
        buildContent();
        setupDynamicTracking();
    }

    // ── Content Construction ────────────────────────────────────────

    private void buildContent() {
        removeAllViews();
        float density = getResources().getDisplayMetrics().density;

        // 1. Touch blocker — MATCH_PARENT, routes touches to target or consumes outside
        mTouchBlocker = new View(getContext());
        mTouchBlocker.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mTouchBlocker.setClickable(true);
        mTouchBlocker.setFocusable(true);
        mTouchBlocker.setOnTouchListener((v, ev) -> handleTouch(ev));
        addView(mTouchBlocker);

        // 2. Spotlight layer
        mSpotlight = new TutorialSpotlightView(getContext());
        addView(mSpotlight, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 3. Animated downward-pointing finger
        int fingerPx = Math.round(FINGER_SIZE * density);
        mFingerView = new FingerPointerView(getContext());
        addView(mFingerView, new LayoutParams(fingerPx, fingerPx));

        // 4. Tooltip card
        mTooltipView = new TooltipView(getContext());
        mTooltipView.setButtonListener(new TooltipView.ButtonListener() {
            @Override public void onActionClicked() { advanceTask(); }
            @Override public void onSkipClicked()   { skipCurrentTask(); }
        });
        addView(mTooltipView, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // 5. Top-right "Skip Tutorial" shortcut
        mTopSkipBtn = new TextView(getContext());
        mTopSkipBtn.setText("SKIP TUTORIAL");
        mTopSkipBtn.setTextColor(SKIP_TEXT_COLOR);
        mTopSkipBtn.setTextSize(10f);
        if (mMinecraftFont != null) mTopSkipBtn.setTypeface(mMinecraftFont);
        mTopSkipBtn.setIncludeFontPadding(false);
        mTopSkipBtn.setClickable(true);
        mTopSkipBtn.setFocusable(true);
        mTopSkipBtn.setOnClickListener(v -> cancelEntireSequence());
        int skipPad = Math.round(8 * density);
        mTopSkipBtn.setPadding(skipPad, skipPad, skipPad, skipPad);
        LayoutParams skipLp = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        skipLp.gravity = Gravity.TOP | Gravity.END;
        skipLp.rightMargin = Math.round(10 * density);
        skipLp.topMargin = Math.round(10 * density);
        addView(mTopSkipBtn, skipLp);

        post(() -> showTask(0));
    }

    private void setupDynamicTracking() {
        mScrollListener = this::updateTargetBounds;
        mLayoutListener = this::updateTargetBounds;

        getViewTreeObserver().addOnScrollChangedListener(mScrollListener);
        getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
    }

    private void updateTargetBounds() {
        if (mResolvedTarget != null && mResolvedTarget.isAttachedToWindow() && mResolvedTarget.getVisibility() == VISIBLE) {
            RectF newRect = rectForTarget(mResolvedTarget);
            if (newRect != null && !newRect.equals(mTargetRect)) {
                mTargetRect = newRect;
                if (mSpotlight != null) mSpotlight.setHole(mTargetRect);
                if (mFingerView != null && mFingerView.getVisibility() == VISIBLE) {
                    positionFinger(mTargetRect);
                }
                positionTooltip();
            }
        }
    }

    // ── Global Screen Lifecycle Notifications ───────────────────────

    public void onScreenViewCreated(@NonNull Fragment fragment, @NonNull View view) {
        if (mCurrentIndex >= mSequence.size()) return;
        TutorialTask currentTask = mSequence.getTasks().get(mCurrentIndex);

        if (currentTask.getActionType() == TutorialActionType.ACCOUNT_CREATION) {
            Activity act = mActivityRef.get();
            if (act != null && !act.isFinishing()) {
                view.post(() -> adaptAccountTaskToScreen(fragment, act));
            }
        } else {
            Activity act = mActivityRef.get();
            if (act != null && !act.isFinishing()) {
                view.post(() -> showTask(mCurrentIndex));
            }
        }
    }

    public void onScreenResumed(@NonNull Fragment fragment) {
        if (mCurrentIndex >= mSequence.size()) return;
        TutorialTask currentTask = mSequence.getTasks().get(mCurrentIndex);

        if (currentTask.getActionType() == TutorialActionType.ACCOUNT_CREATION) {
            Activity act = mActivityRef.get();
            if (act != null && !act.isFinishing()) {
                adaptAccountTaskToScreen(fragment, act);
            }
        } else if (fragment instanceof LauncherHomeFragment) {
            // Re-sync Home screen targets when returning
            post(() -> showTask(mCurrentIndex));
        }
    }

    public void onScreenViewDestroyed(@NonNull Fragment fragment) {
        if (mResolvedTarget != null && !mResolvedTarget.isAttachedToWindow()) {
            mResolvedTarget = null;
            mTargetRect = null;
        }
    }

    // ── Task Management ─────────────────────────────────────────────

    void showTask(int index) {
        if (index < 0 || index >= mSequence.size()) return;
        mCurrentIndex = index;
        TutorialTask task = mSequence.getTasks().get(index);

        Activity act = mActivityRef.get();
        if (act == null || act.isFinishing()) return;

        cleanupDragDemo();
        mAccountObserver.stopObserving();
        mDragDemoUserPhase = false;

        if (task.getActionType() == TutorialActionType.DRAG_DEMO) {
            showDragDemoTask(index, task, act);
            return;
        }

        if (task.getActionType() == TutorialActionType.ACCOUNT_CREATION) {
            showAccountCreationTask(index, task, act);
            return;
        }

        // Standard Tasks (INFORMATION, TAP, FINISH)
        RectF targetRect = null;
        View target = null;
        try {
            target = task.getTarget().resolve(act);
            if (target != null) {
                if (target.getWidth() == 0 || target.getHeight() == 0) {
                    final int taskIdx = index;
                    target.post(() -> {
                        if (mCurrentIndex == taskIdx) showTask(taskIdx);
                    });
                } else {
                    targetRect = rectForTarget(target);
                }
            }
        } catch (Throwable ignored) {
            target = null;
        }
        mTargetRect = targetRect;
        mResolvedTarget = target;

        if (mSpotlight != null) {
            mSpotlight.setHole(targetRect);
        }

        if (mTooltipView != null) {
            mTooltipView.setTaskInfo(index + 1, mSequence.size());
            mTooltipView.setTitle(task.getTitle());
            mTooltipView.setMessage(task.getDescription());
            mTooltipView.setShowArrow(targetRect != null);
            mTooltipView.configureButtons(task.getActionType(), true);
            post(this::positionTooltip);
        }

        boolean hasTarget = targetRect != null;
        if (mFingerView != null) {
            if (hasTarget && task.getActionType().isInteractive()) {
                mFingerView.setVisibility(VISIBLE);
                positionFinger(targetRect);
            } else {
                mFingerView.setVisibility(GONE);
                mFingerView.stopAnimation();
            }
        }

        if (mTopSkipBtn != null) {
            mTopSkipBtn.setVisibility(VISIBLE);
        }
    }

    private void showAccountCreationTask(int index, TutorialTask task, Activity act) {
        mAccountObserver.startObserving(act.getApplicationContext(), username -> {
            post(() -> {
                if (mTooltipView != null) {
                    mTooltipView.setTitle("ACCOUNT ADDED!");
                    mTooltipView.setMessage("Welcome, " + username + "!");
                    mTooltipView.setShowArrow(false);
                }
                postDelayed(this::advanceTask, 1100);
            });
        });

        Fragment currentFragment = null;
        if (act instanceof FragmentActivity) {
            currentFragment = ((FragmentActivity) act).getSupportFragmentManager()
                    .findFragmentById(R.id.container_fragment);
        }

        adaptAccountTaskToScreen(currentFragment, act);
    }

    private void adaptAccountTaskToScreen(@Nullable Fragment fragment, @NonNull Activity act) {
        if (mCurrentIndex >= mSequence.size()) return;
        TutorialTask task = mSequence.getTasks().get(mCurrentIndex);
        if (task.getActionType() != TutorialActionType.ACCOUNT_CREATION) return;

        if (fragment instanceof LocalLoginFragment) {
            View userEdit = act.findViewById(R.id.login_edit_email);
            View loginBtn = act.findViewById(R.id.login_button);

            if (userEdit instanceof EditText) {
                EditText et = (EditText) userEdit;
                mAccountObserver.observeUsernameInput(et, isValid -> {
                    if (isValid && loginBtn != null) {
                        bindTargetView(loginBtn, "CREATE ACCOUNT", "Tap here to save your local account.");
                    } else {
                        bindTargetView(et, "ENTER USERNAME", "Type your player name (3-16 characters).");
                    }
                });
            } else if (loginBtn != null) {
                bindTargetView(loginBtn, "CREATE ACCOUNT", "Tap here to save your local account.");
            }
        } else if (fragment instanceof SelectAuthFragment) {
            mAccountObserver.detachUsernameInput();
            View localOption = act.findViewById(R.id.button_local_authentication);
            View target = localOption != null ? localOption : act.findViewById(R.id.button_microsoft_authentication);
            if (target != null) {
                bindTargetView(target, "CHOOSE ACCOUNT TYPE", "Select 'Local Account' for offline play, or Microsoft to sign in.");
            }
        } else {
            mAccountObserver.detachUsernameInput();
            View chip = act.findViewById(R.id.lh_account_chip);
            if (chip != null) {
                bindTargetView(chip, task.getTitle(), task.getDescription());
            }
        }
    }

    private void bindTargetView(@NonNull View target, @NonNull String title, @NonNull String description) {
        mResolvedTarget = target;
        if (target.getWidth() == 0 || target.getHeight() == 0) {
            target.post(() -> {
                if (mResolvedTarget == target) {
                    mTargetRect = rectForTarget(target);
                    if (mSpotlight != null) mSpotlight.setHole(mTargetRect);
                    if (mFingerView != null && mTargetRect != null) {
                        mFingerView.setVisibility(VISIBLE);
                        positionFinger(mTargetRect);
                    }
                    post(this::positionTooltip);
                }
            });
        } else {
            mTargetRect = rectForTarget(target);
            if (mSpotlight != null) mSpotlight.setHole(mTargetRect);
            if (mFingerView != null && mTargetRect != null) {
                mFingerView.setVisibility(VISIBLE);
                positionFinger(mTargetRect);
            }
        }

        if (mTooltipView != null) {
            mTooltipView.setTaskInfo(mCurrentIndex + 1, mSequence.size());
            mTooltipView.setTitle(title);
            mTooltipView.setMessage(description);
            mTooltipView.setShowArrow(mTargetRect != null);
            mTooltipView.configureButtons(TutorialActionType.ACCOUNT_CREATION, true);
            post(this::positionTooltip);
        }
    }

    private void showDragDemoTask(int index, TutorialTask task, Activity act) {
        if (mFingerView != null) {
            mFingerView.setVisibility(GONE);
            mFingerView.stopAnimation();
        }

        View libraryView = null;
        try {
            libraryView = task.getTarget().resolve(act);
        } catch (Throwable ignored) { }
        mResolvedTarget = libraryView;

        if (mTooltipView != null) {
            mTooltipView.setTaskInfo(index + 1, mSequence.size());
            mTooltipView.setTitle(task.getTitle());
            mTooltipView.setMessage("Watch how it works...");
            mTooltipView.setShowArrow(false);
            mTooltipView.configureButtons(task.getActionType(), true);
        }

        // During the scripted demo the REAL carousel must stay visible through
        // the spotlight: the demo card is a real item inside it.
        RectF libRect = null;
        if (libraryView != null) {
            libRect = rectForTarget(libraryView);
            if (libRect != null) libRect.inset(-dp(4), -dp(6));
        }
        mTargetRect = libRect;
        if (mSpotlight != null) mSpotlight.setHole(libRect);

        mDragDemoView = new ProfileDragDemoView(act);
        addView(mDragDemoView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        if (mTooltipView != null) bringChildToFront(mTooltipView);
        if (mTopSkipBtn != null) bringChildToFront(mTopSkipBtn);

        mDragDemoView.setCompletionListener(this::advanceTask);

        mDragDemoView.setPhaseListener(new ProfileDragDemoView.PhaseListener() {
            @Override
            public void onDemoStarted() {
                if (mTooltipView != null) {
                    post(() -> mTooltipView.positionCentered(getWidth(), getHeight()));
                }
            }

            @Override
            public void onUserPhaseStarted() {
                mDragDemoUserPhase = true;
                if (mTooltipView != null) {
                    mTooltipView.setTitle("NOW YOU TRY!");
                    mTooltipView.setMessage("Press and hold a profile, then drag it to the front.");
                    mTooltipView.setShowArrow(true);
                }
                if (mDragDemoView != null && mSpotlight != null) {
                    RectF libRect = mDragDemoView.getLibraryRect();
                    mTargetRect = libRect;
                    mSpotlight.setHole(libRect);
                    if (mTooltipView != null && libRect != null) {
                        post(() -> mTooltipView.positionRelativeTo(libRect, getWidth(), getHeight()));
                    }
                }
            }

            @Override
            public void onSuccess() {
                if (mTooltipView != null) {
                    mTooltipView.setTitle("PERFECT!");
                    mTooltipView.setMessage("You've learned how to reorder profiles.");
                    mTooltipView.setShowArrow(false);
                    post(() -> mTooltipView.positionCentered(getWidth(), getHeight()));
                }
            }
        });

        final View lib = libraryView;
        post(() -> {
            if (mDragDemoView != null) {
                mDragDemoView.startDemo(lib);
            }
        });

        if (mTopSkipBtn != null) mTopSkipBtn.setVisibility(VISIBLE);
    }

    private void cleanupDragDemo() {
        if (mDragDemoView != null) {
            mDragDemoView.cleanup();
            removeView(mDragDemoView);
            mDragDemoView = null;
        }
    }

    // ── Touch Handling ──────────────────────────────────────────────

    private boolean handleTouch(MotionEvent event) {
        TutorialTask task = mSequence.getTasks().get(mCurrentIndex);

        // DRAG_DEMO task
        if (task.getActionType() == TutorialActionType.DRAG_DEMO) {
            if (mDragDemoUserPhase && mResolvedTarget != null) {
                forwardToTarget(mResolvedTarget, event);
                return true;
            }
            return true;
        }

        // ACCOUNT_CREATION task
        if (task.getActionType() == TutorialActionType.ACCOUNT_CREATION) {
            if (mResolvedTarget != null) {
                int[] overlayLoc = new int[2];
                getLocationOnScreen(overlayLoc);
                float screenX = event.getX() + overlayLoc[0];
                float screenY = event.getY() + overlayLoc[1];

                int[] targetLoc = new int[2];
                mResolvedTarget.getLocationOnScreen(targetLoc);
                float pad = (SPOTLIGHT_GAP + dp(4));
                RectF screenTargetRect = new RectF(
                        targetLoc[0] - pad, targetLoc[1] - pad,
                        targetLoc[0] + mResolvedTarget.getWidth() + pad,
                        targetLoc[1] + mResolvedTarget.getHeight() + pad);

                if (screenTargetRect.contains(screenX, screenY)) {
                    forwardToTarget(mResolvedTarget, event);
                    return true;
                }
            }
            return true;
        }

        // INFORMATION / FINISH steps — consume touches outside tooltip buttons
        if (!task.getActionType().isInteractive()) {
            return true;
        }

        // TAP tasks — verify target bounds
        if (mTargetRect == null || mResolvedTarget == null) {
            return true;
        }

        int[] overlayLoc = new int[2];
        getLocationOnScreen(overlayLoc);
        float screenX = event.getX() + overlayLoc[0];
        float screenY = event.getY() + overlayLoc[1];

        int[] targetLoc = new int[2];
        mResolvedTarget.getLocationOnScreen(targetLoc);
        float pad = (SPOTLIGHT_GAP + dp(4));
        RectF screenTargetRect = new RectF(
                targetLoc[0] - pad, targetLoc[1] - pad,
                targetLoc[0] + mResolvedTarget.getWidth() + pad,
                targetLoc[1] + mResolvedTarget.getHeight() + pad);

        boolean insideTarget = screenTargetRect.contains(screenX, screenY);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = screenX;
                mDownY = screenY;
                mDownInsideTarget = insideTarget;
                if (insideTarget) {
                    forwardToTarget(mResolvedTarget, event);
                    return true;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mDownInsideTarget && mResolvedTarget != null) {
                    forwardToTarget(mResolvedTarget, event);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDownInsideTarget && insideTarget && mResolvedTarget != null) {
                    if (task.isInterceptAction()) {
                        advanceTask();
                    } else {
                        forwardToTarget(mResolvedTarget, event);
                        final View t = mResolvedTarget;
                        t.post(() -> {
                            t.performClick();
                            advanceTask();
                        });
                    }
                    return true;
                }
                mDownInsideTarget = false;
                return true;
        }
        return true;
    }

    private void forwardToTarget(@NonNull View target, @NonNull MotionEvent event) {
        try {
            int[] overlayLoc = new int[2];
            getLocationOnScreen(overlayLoc);
            int[] targetLoc = new int[2];
            target.getLocationOnScreen(targetLoc);

            MotionEvent transformed = MotionEvent.obtain(event);
            float localX = event.getX() + overlayLoc[0] - targetLoc[0];
            float localY = event.getY() + overlayLoc[1] - targetLoc[1];
            transformed.setLocation(localX, localY);
            target.dispatchTouchEvent(transformed);
            transformed.recycle();
        } catch (Throwable ignored) { }
    }

    // ── Transitions ─────────────────────────────────────────────────

    public void advanceTask() {
        if (mCurrentIndex < mSequence.size()) {
            TutorialTask task = mSequence.getTasks().get(mCurrentIndex);
            if (mListener != null) {
                mListener.onTaskCompleted(task, mCurrentIndex);
            }
        }

        if (mCurrentIndex + 1 >= mSequence.size()) {
            finishSequence(true);
        } else {
            showTask(mCurrentIndex + 1);
        }
    }

    public void skipCurrentTask() {
        if (mCurrentIndex < mSequence.size()) {
            TutorialTask task = mSequence.getTasks().get(mCurrentIndex);
            if (mListener != null) {
                mListener.onTaskSkipped(task, mCurrentIndex);
            }
        }

        Activity act = mActivityRef.get();
        if (act instanceof FragmentActivity) {
            try {
                Fragment f = ((FragmentActivity) act).getSupportFragmentManager()
                        .findFragmentById(R.id.container_fragment);
                if (f instanceof SelectAuthFragment || f instanceof LocalLoginFragment) {
                    Tools.backToMainMenu((FragmentActivity) act);
                }
            } catch (Throwable ignored) { }
        }

        if (mCurrentIndex + 1 >= mSequence.size()) {
            finishSequence(false);
        } else {
            showTask(mCurrentIndex + 1);
        }
    }

    public void cancelEntireSequence() {
        Activity act = mActivityRef.get();
        if (act instanceof FragmentActivity) {
            try {
                Fragment f = ((FragmentActivity) act).getSupportFragmentManager()
                        .findFragmentById(R.id.container_fragment);
                if (f instanceof SelectAuthFragment || f instanceof LocalLoginFragment) {
                    Tools.backToMainMenu((FragmentActivity) act);
                }
            } catch (Throwable ignored) { }
        }

        if (mListener != null) {
            mListener.onSequenceCancelled(this);
        }
        dismissInternal();
    }

    private void finishSequence(boolean allCompleted) {
        if (mListener != null) {
            mListener.onSequenceFinished(this, allCompleted);
        }
        dismissInternal();
    }

    void dismissInternal() {
        try {
            if (mScrollListener != null) {
                getViewTreeObserver().removeOnScrollChangedListener(mScrollListener);
                mScrollListener = null;
            }
            if (mLayoutListener != null) {
                getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
                mLayoutListener = null;
            }
            mAccountObserver.stopObserving();
            cleanupDragDemo();
            if (mFingerView != null) mFingerView.stopAnimation();
            removeAllViews();
            if (getParent() instanceof ViewGroup) {
                ((ViewGroup) getParent()).removeView(this);
            }
        } catch (Throwable ignored) { }
    }

    // ── Exact Coordinate & Positioning Helpers ──────────────────────

    @Nullable
    private RectF rectForTarget(@NonNull View target) {
        try {
            int[] targetLoc = new int[2];
            target.getLocationOnScreen(targetLoc);
            int[] overlayLoc = new int[2];
            getLocationOnScreen(overlayLoc);

            float pad = SPOTLIGHT_GAP + dp(4);
            float left = targetLoc[0] - overlayLoc[0] - pad;
            float top  = targetLoc[1] - overlayLoc[1] - pad;
            return new RectF(
                    left, top,
                    left + target.getWidth() + pad * 2,
                    top  + target.getHeight() + pad * 2);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void positionFinger(@Nullable RectF rect) {
        if (mFingerView == null || rect == null) return;
        float density = getResources().getDisplayMetrics().density;
        float fingerPx = FINGER_SIZE * density;

        float cx = rect.centerX();
        float fingerBottom = rect.top - dp(4);
        float fingerTop = fingerBottom - fingerPx;

        cx = Math.max(fingerPx / 2, Math.min(cx, getWidth() - fingerPx / 2));
        fingerTop = Math.max(0, fingerTop);

        mFingerView.setTranslationX(cx - fingerPx / 2);
        mFingerView.setTranslationY(fingerTop);
        mFingerView.startTapAnimation();
    }

    private void positionTooltip() {
        if (mTooltipView == null) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            post(this::positionTooltip);
            return;
        }

        // Account for soft keyboard visible height
        Rect visibleFrame = new Rect();
        getWindowVisibleDisplayFrame(visibleFrame);
        int availableH = visibleFrame.height() > 0 ? Math.min(h, visibleFrame.height()) : h;

        if (mTargetRect != null) {
            mTooltipView.positionRelativeTo(mTargetRect, w, availableH);
        } else {
            mTooltipView.positionCentered(w, availableH);
        }
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * getResources().getDisplayMetrics().density));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            Activity act = mActivityRef.get();
            if (act != null && !act.isFinishing() && mCurrentIndex < mSequence.size()) {
                TutorialTask task = mSequence.getTasks().get(mCurrentIndex);
                try {
                    View target = task.getTarget().resolve(act);
                    if (target != null) {
                        mTargetRect = rectForTarget(target);
                        mResolvedTarget = target;
                        if (mSpotlight != null) mSpotlight.setHole(mTargetRect);
                        if (task.getActionType().isInteractive() && mFingerView != null) {
                            positionFinger(mTargetRect);
                        }
                    }
                } catch (Throwable ignored) { }
                post(this::positionTooltip);
            }
        }
    }
}
