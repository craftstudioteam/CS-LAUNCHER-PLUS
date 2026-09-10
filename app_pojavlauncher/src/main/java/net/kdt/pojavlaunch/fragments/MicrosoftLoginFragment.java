package net.kdt.pojavlaunch.fragments;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

public class MicrosoftLoginFragment extends Fragment {
    public static final String TAG = "MICROSOFT_LOGIN_FRAGMENT";
    private WebView mWebview;
    // Technically the client is blank (or there is none) when the fragment is initialized
    private boolean mBlankClient = true;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_microsoft_login, container, false);
        mWebview = root.findViewById(R.id.microsoft_login_webview);
        mRoot = root;
        // Phase 9 entrance: brand column slides from the left, web sheet scales up.
        View brand = root.findViewById(R.id.ms_brand_column);
        View frame = root.findViewById(R.id.ms_web_frame);
        if (brand != null) net.kdt.pojavlaunch.Anime.in(brand, net.kdt.pojavlaunch.Anime.Fx.FADE_RIGHT, 0, 480, net.kdt.pojavlaunch.Anime.OUT_EXPO);
        if (frame != null) net.kdt.pojavlaunch.Anime.in(frame, net.kdt.pojavlaunch.Anime.Fx.SCALE_IN, 120, 520, net.kdt.pojavlaunch.Anime.OUT_BACK);
        setStatus("// opening login.live.com", 0);
        setWebViewSettings();
        if(savedInstanceState == null) startNewSession();
        else restoreWebViewState(savedInstanceState);
        return root;
    }

    // WebView.restoreState() does not restore the WebSettings or the client, so set them there
    // separately. Note that general state should not be altered here (aka no loading pages, no manipulating back/front lists),
    // to avoid "undesirable side-effects"
    @SuppressLint("SetJavaScriptEnabled")
    private void setWebViewSettings() {
        WebSettings settings = mWebview.getSettings();
        settings.setJavaScriptEnabled(true);
        mWebview.setWebViewClient(new WebViewTrackClient());
        mBlankClient = false;
    }

    private void startNewSession() {
        CookieManager.getInstance().removeAllCookies((b)->{
            mWebview.clearHistory();
            mWebview.clearCache(true);
            mWebview.clearFormData();
            mWebview.clearHistory();
            mWebview.loadUrl("https://login.live.com/oauth20_authorize.srf" +
                    "?client_id=00000000402b5328" +
                    "&response_type=code" +
                    "&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL" +
                    "&redirect_url=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf");
        });
    }

    private void restoreWebViewState(Bundle savedInstanceState) {
        Log.i("MSAuthFragment","Restoring state...");
        if(mWebview.restoreState(savedInstanceState) == null) {
            Log.w("MSAuthFragment", "Failed to restore state, starting afresh");
            // if, for some reason, we failed to restore our session,
            // just start afresh
            startNewSession();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // If we have switched to a blank client and haven't fully gone though the lifecycle callbacks to restore it,
        // restore it here.
        if(mBlankClient) mWebview.setWebViewClient(new WebViewTrackClient());
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        // Since the value cannot be null, just create a "blank" client. This is done to not let Android
        // kill us if something happens after the state gets saved, when we can't do fragment transitions
        mWebview.setWebViewClient(new WebViewClient());
        // For some dumb reason state is saved even when Android won't actually destroy the activity.
        // Let the fragment know that the client is blank so that we can restore it in onStart()
        // (it was the earliest lifecycle call actually invoked in this case)
        mBlankClient = true;
        super.onSaveInstanceState(outState);
        mWebview.saveState(outState);
    }

    @Override public void onResume(){super.onResume();if(mWebview!=null)mWebview.onResume();}
    @Override public void onPause(){if(mWebview!=null){mWebview.onPause();mWebview.stopLoading();}super.onPause();}
    @Override public void onDestroyView(){
        if(mWebview!=null){android.view.ViewParent parent=mWebview.getParent();if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(mWebview);mWebview.stopLoading();mWebview.setWebViewClient(null);mWebview.destroy();mWebview=null;}
        super.onDestroyView();
    }

    private View mRoot;

    /** Phase 9: step tracker + status line in the brand column (all optional views). */
    private void setStatus(String text, int stepReached) {
        if (mRoot == null) return;
        android.widget.TextView status = mRoot.findViewById(R.id.ms_status);
        if (status != null) { status.setText(text); net.kdt.pojavlaunch.Anime.pulse(status); }
        int[] dots = {R.id.ms_step_1_dot, R.id.ms_step_2_dot, R.id.ms_step_3_dot};
        int[] labels = {R.id.ms_step_1, R.id.ms_step_2, R.id.ms_step_3};
        for (int i = 0; i < dots.length; i++) {
            View dot = mRoot.findViewById(dots[i]);
            android.widget.TextView label = mRoot.findViewById(labels[i]);
            boolean on = i <= stepReached;
            if (dot != null) dot.setBackgroundResource(on ? R.drawable.mvs_state_ok : R.drawable.pk_check_dot);
            if (label != null) label.setTextColor(on ? 0xFFF4F6F9 : 0xFF8A909C);
        }
        View progress = mRoot.findViewById(R.id.ms_progress);
        if (progress != null) progress.setVisibility(stepReached >= 1 ? View.GONE : View.VISIBLE);
    }

    /* Expose webview actions to others */
    public boolean canGoBack(){ return mWebview.canGoBack();}
    public void goBack(){ mWebview.goBack();}

    /** Client to track when to sent the data to the launcher */
    class WebViewTrackClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if(url.startsWith("ms-xal-00000000402b5328")) {
                // Should be captured by the activity to kill the fragment and get
                ExtraCore.setValue(ExtraConstants.MICROSOFT_LOGIN_TODO, Uri.parse(url));
                setStatus("// token received — Xbox handshake…", 2);
                Toast.makeText(view.getContext(), "Login started !", Toast.LENGTH_SHORT).show();
                // Navigate back to home — use right pane pop if inside MainMenuFragment
                Fragment parent = getParentFragment();
                while (parent != null && !(parent instanceof MainMenuFragment)) {
                    parent = parent.getParentFragment();
                }
                if (parent instanceof MainMenuFragment) {
                    ((MainMenuFragment) parent).clearRightPane();
                } else {
                    Tools.backToMainMenu(requireActivity());
                }
                return true;
            }

            // Sometimes, the user just clicked cancel
            if(url.contains("res=cancel")){
                requireActivity().onBackPressed();
                return true;
            }


            return super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            try {
                String host = Uri.parse(url).getHost();
                if (mRoot != null) {
                    android.widget.TextView status = mRoot.findViewById(R.id.ms_status);
                    if (status != null) status.setText("// loading " + (host != null ? host : "…"));
                    View progress = mRoot.findViewById(R.id.ms_progress);
                    if (progress != null) progress.setVisibility(View.VISIBLE);
                }
            } catch (Throwable ignored) {}
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            try {
                if (mRoot == null) return;
                View progress = mRoot.findViewById(R.id.ms_progress);
                if (progress != null) progress.setVisibility(View.GONE);
                android.widget.TextView status = mRoot.findViewById(R.id.ms_status);
                String host = Uri.parse(url).getHost();
                if (status != null) status.setText("// " + (host != null ? host : "page") + " ready — sign in above");
            } catch (Throwable ignored) {}
        }
    }


}