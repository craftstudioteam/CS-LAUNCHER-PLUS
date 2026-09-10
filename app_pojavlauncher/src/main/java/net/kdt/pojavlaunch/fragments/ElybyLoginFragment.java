package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import android.widget.TextView;
import java.util.Scanner;

public class ElybyLoginFragment extends Fragment {
    public static final String TAG = "ELYBY_LOGIN_FRAGMENT";

    private EditText mUsernameEditText;
    private EditText mPasswordEditText;
    private View mLoginButton;

    public ElybyLoginFragment() {
        super(R.layout.fragment_elyby_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mUsernameEditText = view.findViewById(R.id.login_edit_email);
        mPasswordEditText = view.findViewById(R.id.login_edit_password);
        mLoginButton = view.findViewById(R.id.login_button);

        mLoginButton.setOnClickListener(v -> performLogin());

        View browserBtn = view.findViewById(R.id.btn_open_elyby_browser);
        // Phase 9: credential sheet entrance — card scales in, fields cascade.
        View card = view.findViewById(R.id.card_elyby_login);
        if (card instanceof android.view.ViewGroup) {
            net.kdt.pojavlaunch.Anime.in(card, net.kdt.pojavlaunch.Anime.Fx.SCALE_IN, 60, 480, net.kdt.pojavlaunch.Anime.OUT_BACK);
            net.kdt.pojavlaunch.Anime.stagger((android.view.ViewGroup) card, 160, 40, net.kdt.pojavlaunch.Anime.Fx.FADE_UP);
        }
        net.kdt.pojavlaunch.UiMotion.pressFeedback(mLoginButton, browserBtn);
        if (browserBtn != null) {
            browserBtn.setOnClickListener(v -> {
                Fragment parent = getParentFragment();
                if (parent instanceof MainMenuFragment) {
                    ((MainMenuFragment) parent).openChildPane(ElybyBrowserFragment.class, ElybyBrowserFragment.TAG, null);
                } else {
                    Tools.swapFragment(requireActivity(), ElybyBrowserFragment.class, ElybyBrowserFragment.TAG, null);
                }
            });
        }
    }

    private void performLogin() {
        String username = mUsernameEditText.getText().toString().trim();
        String password = mPasswordEditText.getText().toString();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter username and password", Toast.LENGTH_SHORT).show();
            return;
        }

        mLoginButton.setEnabled(false);
        if (mLoginButton instanceof TextView) ((TextView) mLoginButton).setText("AUTHENTICATING...");
        Toast.makeText(requireContext(), "Authenticating with Ely.by...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                URL url = new URL("https://authserver.ely.by/auth/authenticate");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                JSONObject agent = new JSONObject();
                agent.put("name", "Minecraft");
                agent.put("version", 1);
                payload.put("agent", agent);
                payload.put("username", username);
                payload.put("password", password);
                payload.put("requestUser", true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                Scanner s = new Scanner(is).useDelimiter("\\A");
                String responseStr = s.hasNext() ? s.next() : "";

                if (responseCode >= 200 && responseCode < 300) {
                    JSONObject responseJson = new JSONObject(responseStr);
                    String accessToken = responseJson.optString("accessToken", "");
                    String clientToken = responseJson.optString("clientToken", java.util.UUID.randomUUID().toString());
                    JSONObject selectedProfile = responseJson.optJSONObject("selectedProfile");
                    if (selectedProfile == null && responseJson.has("availableProfiles")) {
                        org.json.JSONArray profiles = responseJson.optJSONArray("availableProfiles");
                        if (profiles != null && profiles.length() > 0) {
                            selectedProfile = profiles.optJSONObject(0);
                        }
                    }
                    if (selectedProfile == null) {
                        throw new RuntimeException("No Minecraft profile found on this Ely.by account. Please create a username/character on Ely.by website first!");
                    }
                    String profileId = selectedProfile.optString("id", "");
                    String profileName = selectedProfile.optString("name", username);

                    MinecraftAccount account = new MinecraftAccount();
                    account.username = profileName;
                    account.accessToken = accessToken;
                    account.clientToken = clientToken;
                    account.profileId = profileId;
                    account.isMicrosoft = false;

                    // Ely.by skins: we trigger a skin update immediately
                    try { account.updateSkinFace(); } catch (Throwable ignored) {}

                    account.save();
                    try { net.kdt.pojavlaunch.PojavProfile.setCurrentProfile(requireContext(), account.username); } catch (Throwable ignored) {}

                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(requireContext(), "✅ Ely.by Login Successful — Welcome, " + profileName + "!", Toast.LENGTH_LONG).show();
                        if (getActivity() != null) {
                            com.kdt.mcgui.mcAccountSpinner spinner = getActivity().findViewById(R.id.account_spinner);
                            if (spinner != null) spinner.reloadAccounts(true, 0);
                            if (getActivity() instanceof net.kdt.pojavlaunch.LauncherActivity) {
                                ((net.kdt.pojavlaunch.LauncherActivity) getActivity()).updateNavSkinIcon();
                            }
                        }
                        Fragment parent = getParentFragment();
                        while (parent != null && !(parent instanceof MainMenuFragment)) {
                            parent = parent.getParentFragment();
                        }
                        if (parent instanceof MainMenuFragment) {
                            ((MainMenuFragment) parent).clearRightPane();
                        } else {
                            Tools.backToMainMenu(requireActivity());
                        }
                    });

                } else {
                    String errorMessage = "Login failed";
                    try {
                        JSONObject errorJson = new JSONObject(responseStr);
                        if (errorJson.has("errorMessage")) {
                            errorMessage = errorJson.getString("errorMessage");
                        }
                    } catch (JSONException e) {
                        // ignore
                    }
                    final String finalError = errorMessage;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (mLoginButton instanceof TextView) ((TextView) mLoginButton).setText("LOGIN");
                        mLoginButton.setEnabled(true);
                        Tools.dialog(requireContext(), "Error", finalError);
                    });
                }

            } catch (Exception e) {
                Log.e(TAG, "Ely.by login exception", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (mLoginButton instanceof TextView) ((TextView) mLoginButton).setText("LOGIN");
                    mLoginButton.setEnabled(true);
                    Tools.showError(requireContext(), e);
                });
            }
        }).start();
    }
}
