package net.kdt.pojavlaunch.tutorial;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable specification of a single task in a tutorial sequence.
 */
public final class TutorialTask {

    private final String mId;
    private final String mTitle;
    private final String mDescription;
    private final TutorialTarget mTarget;
    private final TutorialActionType mActionType;
    private final boolean mInterceptAction;

    private TutorialTask(Builder b) {
        this.mId = b.mId;
        this.mTitle = b.mTitle;
        this.mDescription = b.mDescription;
        this.mTarget = b.mTarget != null ? b.mTarget : TutorialTarget.none();
        this.mActionType = b.mActionType != null ? b.mActionType : TutorialActionType.INFORMATION;
        this.mInterceptAction = b.mInterceptAction;
    }

    @NonNull public String getId() { return mId; }
    @NonNull public String getTitle() { return mTitle; }
    @NonNull public String getDescription() { return mDescription; }
    @NonNull public TutorialTarget getTarget() { return mTarget; }
    @NonNull public TutorialActionType getActionType() { return mActionType; }
    public boolean isInterceptAction() { return mInterceptAction; }

    public static final class Builder {
        private final String mId;
        private String mTitle;
        private String mDescription;
        private TutorialTarget mTarget;
        private TutorialActionType mActionType;
        private boolean mInterceptAction;

        public Builder(@NonNull String id) {
            this.mId = id;
        }

        @NonNull public Builder title(@NonNull String title) {
            this.mTitle = title;
            return this;
        }

        @NonNull public Builder description(@NonNull String description) {
            this.mDescription = description;
            return this;
        }

        @NonNull public Builder target(@Nullable TutorialTarget target) {
            this.mTarget = target;
            return this;
        }

        @NonNull public Builder actionType(@NonNull TutorialActionType actionType) {
            this.mActionType = actionType;
            return this;
        }

        @NonNull public Builder interceptAction(boolean intercept) {
            this.mInterceptAction = intercept;
            return this;
        }

        @NonNull
        public TutorialTask build() {
            if (mTitle == null || mDescription == null) {
                throw new IllegalStateException("TutorialTask '" + mId + "' requires title and description");
            }
            return new TutorialTask(this);
        }
    }
}
