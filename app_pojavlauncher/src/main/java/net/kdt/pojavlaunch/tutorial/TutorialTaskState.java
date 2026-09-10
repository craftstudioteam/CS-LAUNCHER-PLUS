package net.kdt.pojavlaunch.tutorial;

/**
 * State of an individual tutorial task.
 */
public enum TutorialTaskState {
    NOT_STARTED,
    ACTIVE,
    COMPLETED,
    SKIPPED;

    public boolean isFinished() {
        return this == COMPLETED || this == SKIPPED;
    }
}
