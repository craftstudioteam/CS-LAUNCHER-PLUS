package net.kdt.pojavlaunch.tutorial;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An ordered sequence of guided tutorial tasks (e.g. Home Screen, Cosmetics, Controls).
 */
public final class TutorialSequence {

    private final String mId;
    private final String mPrefsKey;
    private final List<TutorialTask> mTasks;

    private TutorialSequence(Builder b) {
        this.mId = b.mId;
        this.mPrefsKey = b.mPrefsKey;
        this.mTasks = Collections.unmodifiableList(new ArrayList<>(b.mTasks));
    }

    @NonNull public String getId() { return mId; }
    @NonNull public String getPrefsKey() { return mPrefsKey; }
    @NonNull public List<TutorialTask> getTasks() { return mTasks; }
    public int size() { return mTasks.size(); }

    public static final class Builder {
        private final String mId;
        private String mPrefsKey;
        private final List<TutorialTask> mTasks = new ArrayList<>();

        public Builder(@NonNull String id) {
            this.mId = id;
        }

        @NonNull public Builder prefsKey(@NonNull String key) {
            this.mPrefsKey = key;
            return this;
        }

        @NonNull public Builder addTask(@NonNull TutorialTask task) {
            this.mTasks.add(task);
            return this;
        }

        @NonNull
        public TutorialSequence build() {
            if (mPrefsKey == null) {
                throw new IllegalStateException("TutorialSequence '" + mId + "' requires a prefsKey");
            }
            if (mTasks.isEmpty()) {
                throw new IllegalStateException("TutorialSequence '" + mId + "' requires at least one task");
            }
            return new TutorialSequence(this);
        }
    }
}
