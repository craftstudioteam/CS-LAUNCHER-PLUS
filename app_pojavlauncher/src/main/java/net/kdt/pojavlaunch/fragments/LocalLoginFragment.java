package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.utils.SkinFetchUtils;
import net.kdt.pojavlaunch.PojavApplication;

import java.io.File;
import java.net.URL;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.os.Handler;
import android.os.Looper;

public class LocalLoginFragment extends Fragment {
    public static final String TAG = "LOCAL_LOGIN_FRAGMENT";

    private final Pattern mUsernameValidationPattern;
    private EditText mUsernameEditText;
    private ImageView mHeadPreview;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Runnable mFetchRunnable;
    private View mStatusDot, mRuleLenDot, mRuleCharsDot, mRuleFreeDot, mCreateButton;
    private android.widget.TextView mPreviewName, mCounter, mRuleLen, mRuleChars, mRuleFree;

    public LocalLoginFragment(){
        super(R.layout.fragment_local_login);
        mUsernameValidationPattern = Pattern.compile("^[a-zA-Z0-9_]*$");
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // This is overkill but meh
        if (!hasOnlineProfile()){
            Tools.swapFragment(requireActivity(), net.kdt.pojavlaunch.fragments.LauncherHomeFragment.class, "ROOT_HOME", null);
        }
        mUsernameEditText = view.findViewById(R.id.login_edit_email);
        mHeadPreview = view.findViewById(R.id.live_head_preview);
        mStatusDot = view.findViewById(R.id.auth_status_dot);
        mPreviewName = view.findViewById(R.id.ll_preview_name);
        mCounter = view.findViewById(R.id.ll_counter);
        mRuleLenDot = view.findViewById(R.id.ll_rule_len_dot);
        mRuleCharsDot = view.findViewById(R.id.ll_rule_chars_dot);
        mRuleFreeDot = view.findViewById(R.id.ll_rule_free_dot);
        mRuleLen = view.findViewById(R.id.ll_rule_len);
        mRuleChars = view.findViewById(R.id.ll_rule_chars);
        mRuleFree = view.findViewById(R.id.ll_rule_free);
        mCreateButton = view.findViewById(R.id.login_button);

        loadSteveHead();
        applyRules("");

        mUsernameEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String username = s.toString().trim();
                applyRules(username);
                if (mPreviewName != null) {
                    net.kdt.pojavlaunch.Anime.swapText(mPreviewName, username.isEmpty() ? "Player" : username);
                }
                mMainHandler.removeCallbacks(mFetchRunnable);
                if (username.length() >= 3) {
                    setFetching(true);
                    mFetchRunnable = () -> fetchPreviewHead(username);
                    mMainHandler.postDelayed(mFetchRunnable, 700);
                } else {
                    setFetching(false);
                    loadSteveHead();
                }
            }
        });
        mUsernameEditText.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                mCreateButton.performClick();
                return true;
            }
            return false;
        });

        net.kdt.pojavlaunch.UiMotion.pressFeedback(mCreateButton);
        mCreateButton.setOnClickListener(v -> {
            if(!checkEditText()) {
                net.kdt.pojavlaunch.Anime.shake(mUsernameEditText);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                Context context = v.getContext();
                Tools.dialog(context, context.getString(R.string.local_login_bad_username_title), context.getString(R.string.local_login_bad_username_text));
                return;
            }
            net.kdt.pojavlaunch.Anime.pulse(v);

            String username = mUsernameEditText.getText().toString();

            // Auto-fetch skin for local account
            PojavApplication.sExecutorService.execute(() -> {
                File skinsDir = new File(Tools.DIR_DATA + "/skins");
                if (!skinsDir.exists()) skinsDir.mkdirs();
                File destSkinFile = new File(skinsDir, username + "_skin.png");
                SkinFetchUtils.fetchAndSaveSkin(username, destSkinFile);
                if (!net.kdt.pojavlaunch.skins.SkinResolver.isUsableSkin(destSkinFile)) {
                    // Phase 8: local accounts resolve like every other kind
                    // (Mojang by name → ely.by → mc-heads) so the home 3D
                    // character shows the real skin, not Steve.
                    net.kdt.pojavlaunch.value.MinecraftAccount probe = new net.kdt.pojavlaunch.value.MinecraftAccount();
                    probe.username = username;
                    probe.accessToken = "0";
                    net.kdt.pojavlaunch.skins.SkinResolver.resolve(probe);
                }

                File destHeadFile = new File(Tools.DIR_CACHE, username + ".png");
                SkinFetchUtils.fetchAndSaveHead(username, destHeadFile);
            });

            ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{
                    username, "" });

            Tools.swapFragment(requireActivity(), net.kdt.pojavlaunch.fragments.LauncherHomeFragment.class, "ROOT_HOME", null);
        });

        // ── entrance timeline: identity pane slides from the left, form from the
        //    right, head pops, rules stagger, button springs last ──
        View identity = view.findViewById(R.id.header_container);
        View form = view.findViewById(R.id.card_account_input);
        net.kdt.pojavlaunch.Anime.in(identity, net.kdt.pojavlaunch.Anime.Fx.FADE_RIGHT, 40, 620, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(form, net.kdt.pojavlaunch.Anime.Fx.FADE_LEFT, 120, 620, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(view.findViewById(R.id.ll_identity_well), net.kdt.pojavlaunch.Anime.Fx.POP, 260, 560, net.kdt.pojavlaunch.Anime.OUT_BACK);
        net.kdt.pojavlaunch.Anime.in(view.findViewById(R.id.ll_title), net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 300, 480, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        net.kdt.pojavlaunch.Anime.in(mUsernameEditText, net.kdt.pojavlaunch.Anime.Fx.FLIP_UP, 380, 560, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        View rules = view.findViewById(R.id.ll_rules);
        if (rules instanceof android.view.ViewGroup) net.kdt.pojavlaunch.Anime.stagger((android.view.ViewGroup) rules, 460, 60, net.kdt.pojavlaunch.Anime.Fx.FADE_UP);
        net.kdt.pojavlaunch.Anime.in(mCreateButton, net.kdt.pojavlaunch.Anime.Fx.FADE_UP, 640, 560, net.kdt.pojavlaunch.Anime.SPRING);
        mUsernameEditText.postDelayed(() -> {
            if (!isAdded()) return;
            mUsernameEditText.requestFocus();
        }, 700);
    }

    // ── live validation ───────────────────────────────────────────────────

    /** Lights the rule dots as the name grows; the button follows the verdict. */
    private void applyRules(String name) {
        int len = name.length();
        boolean lenOk = len >= 3 && len <= 16;
        boolean charsOk = len > 0 && mUsernameValidationPattern.matcher(name).find();
        boolean freeOk = len > 0 && !new File(Tools.DIR_ACCOUNT_NEW + "/" + name + ".json").exists();
        if (mCounter != null) {
            mCounter.setText(len + " / 16");
            mCounter.setTextColor(len > 16 ? 0xFFE8C989 : lenOk ? 0xFFD2D6DE : 0xFF5C6068);
        }
        rule(mRuleLenDot, mRuleLen, lenOk);
        rule(mRuleCharsDot, mRuleChars, charsOk);
        rule(mRuleFreeDot, mRuleFree, freeOk);
        boolean all = lenOk && charsOk && freeOk;
        if (mCreateButton != null) {
            boolean was = mCreateButton.isEnabled();
            mCreateButton.setEnabled(all);
            mCreateButton.animate().alpha(all ? 1f : 0.5f).setDuration(180).start();
            if (all && !was) net.kdt.pojavlaunch.Anime.pop(mCreateButton);
        }
    }

    private void rule(View dot, android.widget.TextView label, boolean ok) {
        if (dot == null || label == null) return;
        boolean was = Boolean.TRUE.equals(dot.getTag());
        if (was == ok && dot.getTag() != null) return;
        dot.setTag(ok);
        dot.setBackgroundResource(ok ? R.drawable.ll_rule_dot_on : R.drawable.ll_rule_dot_off);
        label.setTextColor(ok ? 0xFFF4F6F9 : 0xFF8A909C);
        if (ok) net.kdt.pojavlaunch.Anime.pop(dot);
    }

    private android.animation.ValueAnimator mDotPulse;

    /** The identity dot breathes while a preview request is pending. */
    private void setFetching(boolean fetching) {
        if (mStatusDot == null) return;
        if (mDotPulse != null) { mDotPulse.cancel(); mDotPulse = null; }
        if (fetching) {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0.45f, 1f);
            a.setDuration(480);
            a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            a.addUpdateListener(an -> {
                float f = (float) an.getAnimatedValue();
                mStatusDot.setAlpha(f);
                mStatusDot.setScaleX(0.8f + 0.4f * f);
                mStatusDot.setScaleY(0.8f + 0.4f * f);
            });
            a.start();
            mDotPulse = a;
        } else {
            mStatusDot.setScaleX(1f); mStatusDot.setScaleY(1f); mStatusDot.setAlpha(1f);
        }
    }

    @Override
    public void onDestroyView() {
        if (mDotPulse != null) { mDotPulse.cancel(); mDotPulse = null; }
        mMainHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void loadSteveHead() {
        if (mHeadPreview == null) return;
        Bitmap steveSkin = BitmapFactory.decodeResource(getResources(), R.drawable.cs_default_skin);
        if (steveSkin != null) {
            Bitmap head = net.kdt.pojavlaunch.value.MinecraftAccount.extractSkinHead(steveSkin);
            steveSkin.recycle();
            if (head != null) {
                mHeadPreview.setImageBitmap(head);
            }
        }
    }

    private void fetchPreviewHead(String username) {
        PojavApplication.sExecutorService.execute(() -> {
            try {
                URL url = new URL("https://mc-heads.net/head/" + username + "/100");
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.connect();
                java.io.InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) {
                    Bitmap rounded = net.kdt.pojavlaunch.value.MinecraftAccount.roundBitmap(bitmap, 128, 16f);
                    mMainHandler.post(() -> {
                        if (mHeadPreview != null) {
                            mHeadPreview.setImageBitmap(rounded);
                            net.kdt.pojavlaunch.Anime.pop(mHeadPreview);
                        }
                        setFetching(false);
                    });
                } else {
                    mMainHandler.post(() -> setFetching(false));
                }
            } catch (Exception e) {
                Log.w("SkinPreview", "Failed to fetch preview head", e);
                mMainHandler.post(() -> setFetching(false));
            }
        });
    }


    /** @return Whether the mail (and password) text are eligible to make an auth request  */
    private boolean checkEditText(){

        String text = mUsernameEditText.getText().toString();

        Matcher matcher = mUsernameValidationPattern.matcher(text);
        return !(text.isEmpty()
                || text.length() < 3
                || text.length() > 16
                || !matcher.find()
                || new File(Tools.DIR_ACCOUNT_NEW + "/" + text + ".json").exists()
        );
    }
}
