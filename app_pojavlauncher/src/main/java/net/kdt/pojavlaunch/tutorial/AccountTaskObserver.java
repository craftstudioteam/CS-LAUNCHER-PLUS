package net.kdt.pojavlaunch.tutorial;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;

import java.util.List;

/**
 * Observer that manages real multi-screen account creation lifecycle:
 * <ol>
 *   <li>Detects screen transitions to SelectAuthFragment & LocalLoginFragment.</li>
 *   <li>Attaches text watcher to username input to track validation progress.</li>
 *   <li>Listens for ExtraConstants.ACCOUNT_CHANGED and checks PojavProfile state on success.</li>
 * </ol>
 */
public final class AccountTaskObserver {

    public interface OnAccountAddedListener {
        void onAccountAdded(@NonNull String username);
    }

    public interface OnUsernameValidListener {
        void onUsernameValid(boolean isValid);
    }

    @Nullable private ExtraListener<String> mAccountListener;
    @Nullable private OnAccountAddedListener mCallback;
    @Nullable private OnUsernameValidListener mUsernameValidListener;
    @Nullable private TextWatcher mTextWatcher;
    @Nullable private EditText mObservedEditText;
    private boolean mObserving = false;

    public void startObserving(@NonNull Context context, @NonNull OnAccountAddedListener callback) {
        stopObserving();
        mCallback = callback;
        mObserving = true;

        mAccountListener = (key, username) -> {
            if (!mObserving) return true;
            if (hasValidAccount(context)) {
                if (mCallback != null) {
                    mCallback.onAccountAdded(username != null ? username : "Account");
                }
                stopObserving();
            }
            return false;
        };

        ExtraCore.addExtraListener(ExtraConstants.ACCOUNT_CHANGED, mAccountListener);
    }

    /**
     * Attach a live listener to the username EditText on LocalLoginFragment.
     */
    public void observeUsernameInput(@NonNull EditText editText, @NonNull OnUsernameValidListener listener) {
        detachUsernameInput();
        mObservedEditText = editText;
        mUsernameValidListener = listener;

        mTextWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override
            public void afterTextChanged(Editable s) {
                if (mUsernameValidListener != null) {
                    String text = s != null ? s.toString().trim() : "";
                    boolean valid = text.length() >= 3 && text.length() <= 16;
                    mUsernameValidListener.onUsernameValid(valid);
                }
            }
        };

        editText.addTextChangedListener(mTextWatcher);
        // Initial check
        String current = editText.getText() != null ? editText.getText().toString().trim() : "";
        listener.onUsernameValid(current.length() >= 3 && current.length() <= 16);
    }

    public void detachUsernameInput() {
        if (mObservedEditText != null && mTextWatcher != null) {
            try {
                mObservedEditText.removeTextChangedListener(mTextWatcher);
            } catch (Throwable ignored) { }
        }
        mObservedEditText = null;
        mTextWatcher = null;
        mUsernameValidListener = null;
    }

    public void stopObserving() {
        mObserving = false;
        detachUsernameInput();
        if (mAccountListener != null) {
            ExtraCore.removeExtraListenerFromValue(ExtraConstants.ACCOUNT_CHANGED, mAccountListener);
            mAccountListener = null;
        }
        mCallback = null;
    }

    public static boolean hasValidAccount(@NonNull Context context) {
        try {
            List<String> list = PojavProfile.getAllProfilesList();
            if (list != null && !list.isEmpty()) {
                for (String name : list) {
                    if (name != null && !name.trim().isEmpty()) {
                        return true;
                    }
                }
            }
            String current = PojavProfile.getCurrentProfileName(context);
            return current != null && !current.trim().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
