package net.kdt.pojavlaunch.tutorial;

/**
 * Types of interactions required by tutorial tasks.
 */
public enum TutorialActionType {
    /** Information-only step. Shows Next button. */
    INFORMATION,

    /** User must tap the highlighted target. */
    TAP,

    /** User must add/create an account. Listens to ExtraConstants.ACCOUNT_CHANGED and PojavProfile. */
    ACCOUNT_CREATION,

    /** Profile drag demonstration: Phase 1 fake card demo, Phase 2 real drag-reorder. */
    DRAG_DEMO,

    /** Final step of a tutorial. Shows Finish button. */
    FINISH;

    /** True if this action requires real user interaction rather than just pressing Next. */
    public boolean isInteractive() {
        return this == TAP || this == ACCOUNT_CREATION || this == DRAG_DEMO;
    }
}
