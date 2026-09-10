package net.kdt.pojavlaunch.tutorial;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.fragments.LauncherHomeFragment;

/**
 * Concrete 7-task interactive tutorial sequence for the Home Screen.
 *
 * <p>Task Chain:
 * <ol>
 *   <li><b>Welcome</b> (INFORMATION → Next / Skip)</li>
 *   <li><b>Add Account</b> (ACCOUNT_CREATION → user taps account chip, completes real login on auth screens)</li>
 *   <li><b>Move a Profile</b> (DRAG_DEMO → animated Steve demo then real drag-reorder)</li>
 *   <li><b>Your Profile</b> (TAP → user taps profile / player model)</li>
 *   <li><b>Your Instances</b> (TAP → user taps library carousel)</li>
 *   <li><b>Ready to Play</b> (TAP → user taps Play button, safely intercepted)</li>
 *   <li><b>Finish</b> (FINISH → Finish button)</li>
 * </ol>
 */
public final class HomeTutorial {

    private HomeTutorial() { }

    public static final String PREFS_KEY = "tutorial.home.completed";
    public static final String ID        = "home";

    @Nullable
    private static TutorialSequence sCachedSequence;

    @NonNull
    public static synchronized TutorialSequence getSequence() {
        if (sCachedSequence == null) {
            sCachedSequence = buildSequence();
        }
        return sCachedSequence;
    }

    @NonNull
    private static TutorialSequence buildSequence() {
        return new TutorialSequence.Builder(ID)
                .prefsKey(PREFS_KEY)

                // ── Task 1: Welcome ─────────────────────────────────
                .addTask(new TutorialTask.Builder("task_welcome")
                        .title("WELCOME TO CS LAUNCHER PLUS!")
                        .description("Let's take a quick interactive tour of your Home Screen.")
                        .target(TutorialTarget.none())
                        .actionType(TutorialActionType.INFORMATION)
                        .build())

                // ── Task 2: Add Account (Multi-Screen) ──────────────
                .addTask(new TutorialTask.Builder("task_account")
                        .title("ADD AN ACCOUNT")
                        .description("Tap your account chip to connect or create a Minecraft account.")
                        .target(TutorialTarget.byId(R.id.lh_account_chip))
                        .actionType(TutorialActionType.ACCOUNT_CREATION)
                        .build())

                // ── Task 3: Profile Drag Demo & Reorder ─────────────
                .addTask(new TutorialTask.Builder("task_drag_reorder")
                        .title("MOVE A PROFILE")
                        .description("Press and hold a profile, then drag it to the front to make it your main profile.")
                        .target(TutorialTarget.byId(R.id.lh_library))
                        .actionType(TutorialActionType.DRAG_DEMO)
                        .build())

                // ── Task 4: Skin Management ────────────────────────
                .addTask(new TutorialTask.Builder("task_skin")
                        .title("YOUR SKIN")
                        .description("Tap your character to open Skin Management and customize your skin.")
                        .target(TutorialTarget.firstVisible(
                                R.id.lh_player,
                                R.id.lh_account_chip,
                                R.id.lh_hero_name))
                        .actionType(TutorialActionType.TAP)
                        .interceptAction(false) // Allows real click to open Skin Management!
                        .build())

                // ── Task 5: Instance Library ────────────────────────
                .addTask(new TutorialTask.Builder("task_library")
                        .title("YOUR INSTANCES")
                        .description("Tap the library carousel to choose your Minecraft version or modpack.")
                        .target(TutorialTarget.byId(R.id.lh_library))
                        .actionType(TutorialActionType.TAP)
                        .interceptAction(true)
                        .build())

                // ── Task 6: Play / Launch ───────────────────────────
                .addTask(new TutorialTask.Builder("task_play")
                        .title("READY TO PLAY")
                        .description("Tap the Play button when you're ready to start Minecraft.")
                        .target(TutorialTarget.firstVisible(
                                R.id.lh_launch_button,
                                R.id.lh_launch_row))
                        .actionType(TutorialActionType.TAP)
                        .interceptAction(true)
                        .build())

                // ── Task 7: Finish ──────────────────────────────────
                .addTask(new TutorialTask.Builder("task_finish")
                        .title("YOU'RE ALL SET!")
                        .description("Use the dock on the left to customize controls, settings, and more. Have fun playing!")
                        .target(TutorialTarget.none())
                        .actionType(TutorialActionType.FINISH)
                        .build())

                .build();
    }

    /**
     * Start the tutorial sequence if it has not been completed.
     */
    public static boolean maybeStart(@Nullable Activity activity) {
        if (activity == null) return false;
        try {
            if (activity.isFinishing()) return false;
            if (TutorialEngine.isShowing()) return false;
            if (!TutorialEngine.shouldAutoStart(activity, getSequence())) return false;
            if (!isHomeVisible(activity)) return false;
        } catch (Throwable ignored) { return false; }

        try {
            TutorialEngine.start(activity, getSequence());
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static boolean isHomeVisible(@NonNull Activity activity) {
        try {
            if (!(activity instanceof FragmentActivity)) return false;
            Fragment f = ((FragmentActivity) activity).getSupportFragmentManager()
                    .findFragmentById(R.id.container_fragment);
            return f instanceof LauncherHomeFragment && f.isVisible() && !f.isRemoving();
        } catch (Throwable ignored) { return false; }
    }
}
