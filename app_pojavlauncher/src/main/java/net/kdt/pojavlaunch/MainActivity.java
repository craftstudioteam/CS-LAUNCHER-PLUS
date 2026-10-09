package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static net.kdt.pojavlaunch.Tools.dialogForceClose;
import static net.kdt.pojavlaunch.Tools.hasMods;
import static net.kdt.pojavlaunch.Tools.runMethodbyReflection;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_VIRTUAL_MOUSE_START;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.os.IBinder;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.keyboard.LwjglCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView;
import net.kdt.pojavlaunch.customcontrols.mouse.Touchpad;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import android.content.SharedPreferences;
import android.widget.TextView;
import net.kdt.pojavlaunch.prefs.QuickSettingSideDialog;
import net.kdt.pojavlaunch.services.GameService;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.utils.TouchControllerUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class MainActivity extends BaseActivity implements ControlButtonMenuListener, EditorExitable, ServiceConnection {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    public static final String TAG = "MainActivity";
    public static final String INTENT_MINECRAFT_VERSION = "intent_version";
    public static final String EXTRA_PROFILE_KEY = "cs_launch_profile_key";
    public static final String EXTRA_ACCOUNT_NAME = "cs_launch_account_name";
    /** Extra marking a shortcut-booted launch: open straight onto the launch logs. */
    public static final String EXTRA_AUTO_SHOW_LOGS = "cs_auto_show_logs";
    /** One-shot flag armed by the shortcut router for the next game start. */
    public static volatile boolean sAutoShowLogsOnce = false;

    volatile public static boolean isInputStackCall;

    public static TouchCharInput touchCharInput;
    private MinecraftGLSurface minecraftGLView;
    public static Touchpad touchpad;
    private LoggerView loggerView;
    /** Phase 8: left-side mini boot log (auto-hides on the first presented frame). */
    private net.kdt.pojavlaunch.launch.BootLogOverlay mBootLog;
    private DrawerLayout drawerLayout;
    private ListView navDrawer;
    private View mDrawerPullButton;
    private GyroControl mGyroControl = null;
    private ControlLayout mControlLayout;
    private HotbarView mHotbarView;

    MinecraftProfile minecraftProfile;
    private String mLaunchAccountName;

    private ArrayAdapter<String> gameActionArrayAdapter;
    private AdapterView.OnItemClickListener gameActionClickListener;
    public ArrayAdapter<String> ingameControlsEditorArrayAdapter;
    public AdapterView.OnItemClickListener ingameControlsEditorListener;
    private GameService.LocalBinder mServiceBinder;

    private QuickSettingSideDialog mQuickSettingSideDialog;
    private net.kdt.pojavlaunch.performance.AndroidGameStateController mGameStateController;
    private boolean mFirstGameFramePresented;
    private final Runnable mFirstFrameWatcher=new Runnable(){@Override public void run(){if(isFinishing()||isDestroyed()||mFirstGameFramePresented)return;long frames=net.kdt.pojavlaunch.utils.FpsCounter.getTotalPresents();if(frames>0){mFirstGameFramePresented=true;if(mGameStateController!=null)mGameStateController.playing();if(mBootLog!=null)mBootLog.onGameVisible();return;}Tools.MAIN_HANDLER.postDelayed(this,250L);}};

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        net.kdt.pojavlaunch.performance.GameSessionState.markActive(this);
        mGameStateController=new net.kdt.pojavlaunch.performance.AndroidGameStateController(this);mGameStateController.loading();
        net.kdt.pojavlaunch.performance.PerformanceEngine.onSessionCreate(this);
        Tools.MAIN_HANDLER.post(mFirstFrameWatcher);
        if (LauncherPreferences.PREF_GAMEPAD_SDL_PASSTHRU) {
            // TODO: Use lower level HID capture that needs a dialogue box from the user for the
            // app to fully take focus of the input devices. Might cause issues with older android
            // versions so we don't use that right now. Needs testing.
            // Currently tried but only identification works OOTB, inputs aren't being sent.

            // TODO: Use a hook to load SDL logic depending on whether libSDL3.so is loaded.
            try {
                // Note: This doesn't dlopen it for the mod, they still have to do it themselves
                // Why? https://github.com/android/ndk/issues/201#issuecomment-248060092
                // Just in case that gets deleted off the internet:
                // "On Android only the main executable and LD_PRELOADs are considered to be
                // RTLD_GLOBAL, all the dependencies of the main executable remain RTLD_LOCAL." - dimitry
                SDL.loadLibrary("SDL3", this);
                SDL.loadLibrary("SDL2", this);
                SDL.initialize();
                SDL.setupJNI();
                SDL.setContext(this);
                new SDLSurface(this);
                if (LauncherPreferences.PREF_GAMEPAD_FORCEDSDL_PASSTHRU) Tools.SDL.initializeControllerSubsystems();
            } catch (UnsatisfiedLinkError ignored) {
                // Ignore because if SDL.setupJNI(); fails, SDL wasn't loaded.
            }
        }

        minecraftProfile = LauncherProfiles.getCurrentProfile();
        mLaunchAccountName = getIntent().getStringExtra(EXTRA_ACCOUNT_NAME);
        if (mLaunchAccountName == null || mLaunchAccountName.isEmpty()) {
            mLaunchAccountName = PojavProfile.getCurrentProfileName(this);
        }
        String launchProfileKey = getIntent().getStringExtra(EXTRA_PROFILE_KEY);
        if (launchProfileKey == null || launchProfileKey.isEmpty()) {
            launchProfileKey = LauncherPreferences.DEFAULT_PREF.getString(
                    LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "default");
        }
        net.kdt.pojavlaunch.profiles.ProfileLogStore.setActiveProfileKey(launchProfileKey);

        String gameDirPath = Tools.getGameDirPath(minecraftProfile).getAbsolutePath();
        MCOptionUtils.load(gameDirPath);
        if (Tools.hasTouchController(new File(gameDirPath)) || LauncherPreferences.PREF_FORCE_ENABLE_TOUCHCONTROLLER) {
            TouchControllerUtils.initialize(this);
        }

        Intent gameServiceIntent = new Intent(this, GameService.class);
        // Start the service a bit early
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout(R.layout.activity_basemain);
        CallbackBridge.addGrabListener(touchpad);
        CallbackBridge.addGrabListener(minecraftGLView);

        mGyroControl = new GyroControl(this);

        // Enabling this on TextureView results in a broken white result
        if(PREF_USE_ALTERNATE_SURFACE) getWindow().setBackgroundDrawable(null);
        else getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // CS PERFORMANCE ENGINE: no window-level performance flags are set at all. Sustained
        // performance mode used to be applied from here and it is a frame-rate ceiling in disguise,
        // so the game window is left to the system and to Minecraft's own video settings.

        String[] inGameMenuItems = getResources().getStringArray(R.array.menu_customcontrol);
        int[] inGameIcons = new int[]{
                android.R.drawable.ic_menu_add, // Add Button
                android.R.drawable.ic_menu_gallery, // Add Drawer
                android.R.drawable.ic_menu_compass, // Add Joystick
                android.R.drawable.ic_menu_upload, // Load
                android.R.drawable.ic_menu_save, // Save
                android.R.drawable.ic_menu_myplaces, // Default
                android.R.drawable.ic_menu_close_clear_cancel // Exit
        };
        ingameControlsEditorArrayAdapter = new ArrayAdapter<String>(this,
                R.layout.item_custom_control_menu, R.id.menu_item_text, inGameMenuItems) {
            @androidx.annotation.NonNull
            @Override
            public View getView(int position, @androidx.annotation.Nullable View convertView, @androidx.annotation.NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ImageView icon = view.findViewById(R.id.menu_item_icon);
                if (icon != null && position < inGameIcons.length) {
                    icon.setImageResource(inGameIcons[position]);
                }
                return view;
            }
        };
        ingameControlsEditorListener = (parent, view, position, id) -> {
            position -= navDrawer.getHeaderViewsCount(); // Phase 9 header row
            if (position < 0) return;
            switch(position) {
                case 0: mControlLayout.addControlButton(new ControlData("New")); break;
                case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
                case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
                case 3: mControlLayout.openLoadDialog(); break;
                case 4: mControlLayout.openSaveDialog(this); break;
                case 5: mControlLayout.openSetDefaultDialog(); break;
                case 6: mControlLayout.openExitDialog(this);
            }
        };

        // Recompute the gui scale when options are changed
        MCOptionUtils.MCOptionListener optionListener = MCOptionUtils::getMcScale;
        MCOptionUtils.addMCOptionListener(optionListener);
        mControlLayout.setModifiable(false);

        // Set the activity for the executor. Must do this here, or else Tools.showErrorRemote() may not
        // execute the correct method
        ContextExecutor.setActivity(this);
        //Now, attach to the service. The game will only start when this happens, to make sure that we know the right state.
        bindService(gameServiceIntent, this, 0);
    }

    protected void initLayout(int resId) {
        setContentView(resId);
        bindValues();
        // Shortcut-booted launches land on the Game Launch Logs screen first;
        // the normal game start continues right behind it.
        if (getIntent().getBooleanExtra(EXTRA_AUTO_SHOW_LOGS, false)) {
            getIntent().removeExtra(EXTRA_AUTO_SHOW_LOGS);
            loggerView.post(() -> loggerView.setVisibility(View.VISIBLE));
        }
        mControlLayout.setMenuListener(this);

        mDrawerPullButton.setOnClickListener(v -> onClickedMenu());
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        try {
            // Exactly one latest launch log is retained for each profile.
            // Logger.begin truncates this profile's previous session without
            // touching logs belonging to any other instance.
            File latestLogFile = net.kdt.pojavlaunch.profiles.ProfileLogStore
                    .prepareForCurrentProfile(this);
            Logger.begin(latestLogFile.getAbsolutePath());
            // FIXME: is it safe for multi thread?
            GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            touchCharInput.setCharacterSender(new LwjglCharSender());

            if(minecraftProfile.pojavRendererName != null && !minecraftProfile.pojavRendererName.isEmpty()) {
                Log.i("RdrDebug","__P_renderer="+minecraftProfile.pojavRendererName);
                Tools.LOCAL_RENDERER = minecraftProfile.pojavRendererName;
                if (minecraftProfile.pojavRendererName.equals("vulkan_zink")
                        || minecraftProfile.pojavRendererName.equals("opengles3_desktopgl_zink")) {
                    Tools.LOCAL_RENDERER = "opengles3_desktopgl_zink_kopper";
                }
                try {
                    getSharedPreferences("pojav_renderer_backup", MODE_PRIVATE).edit()
                        .putString("renderer_" + minecraftProfile.name, Tools.LOCAL_RENDERER).apply();
                } catch (Throwable ignored) {}
            } else {
                try {
                    String backup = getSharedPreferences("pojav_renderer_backup", MODE_PRIVATE)
                        .getString("renderer_" + minecraftProfile.name, null);
                    if (backup != null && !backup.isEmpty()) {
                        Tools.LOCAL_RENDERER = backup;
                        minecraftProfile.pojavRendererName = backup;
                    }
                } catch (Throwable ignored) {}
            }

            setTitle("Minecraft " + minecraftProfile.lastVersionId);

            // Minecraft 1.13+

            String version = getIntent().getStringExtra(INTENT_MINECRAFT_VERSION);
            version = version == null ? minecraftProfile.lastVersionId : version;

            JMinecraftVersionList.Version mVersionInfo = Tools.getVersionInfo(version);
            isInputStackCall = mVersionInfo.arguments != null;
            CallbackBridge.nativeSetUseInputStackQueue(isInputStackCall);

            Tools.getDisplayMetrics(this);
            windowWidth = Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, 1f);
            windowHeight = Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, 1f);


            // Menu
            // Phase 9: action cards (icon · title · subtitle · key-cap) with a
            // staggered reveal on every drawer open. Row 0 = force close (danger).
            gameActionArrayAdapter = new net.kdt.pojavlaunch.ui.InGameMenuAdapter(this,
                    getResources().getStringArray(R.array.menu_ingame),
                    new String[]{
                            "Kill the game and return to the launcher",
                            "Live console, copy & share",
                            "Type a key the on-screen pad lacks",
                            "Resolution, gyro, mouse, gestures",
                            "Edit / move / resize on-screen controls",
                            "Resource packs & shaders without leaving"
                    },
                    new int[]{
                            R.drawable.ic_igm_power, R.drawable.ic_igm_terminal, R.drawable.ic_igm_keyboard,
                            R.drawable.ic_igm_sliders, R.drawable.ic_igm_controls, R.drawable.ic_igm_packs
                    }, 0);
            setupInGameDrawerChrome();
            gameActionClickListener = (parent, view, position, id) -> {
                position -= navDrawer.getHeaderViewsCount(); // Phase 9 header row
                if (position < 0) return;
                switch(position) {
                    case 0: dialogForceClose(MainActivity.this); break;
                    case 1: openLogOutput(); break;
                    case 2: dialogSendCustomKey(); break;
                    case 3: openQuickSettings(); break;
                    case 4: openCustomControls(); break;
                    case 5: openResourceBrowser(); break; // user req: packs/shaders without leaving the game
                }
                drawerLayout.closeDrawers();
            };
            navDrawer.setAdapter(gameActionArrayAdapter);
            navDrawer.setOnItemClickListener(gameActionClickListener);
            drawerLayout.closeDrawers();

            final String finalVersion = version;
            // Phase 8: the mini boot log appears as soon as the game screen is
            // up (pref-gated, default ON) and follows the JVM start below.
            if (mBootLog != null && LauncherPreferences.DEFAULT_PREF.getBoolean("boot_log_overlay", true)
                    && (loggerView == null || loggerView.getVisibility() != View.VISIBLE)) {
                mBootLog.post(() -> { if (!isFinishing()) mBootLog.start(); });
            }
            minecraftGLView.setSurfaceReadyListener(() -> {
                try {
                    // Game render starts now: stop any streaming loading video
                    // on this exact frame (Remote Config feature, req: no delay).
                    net.kdt.pojavlaunch.launch.LaunchStageView.onGameRenderStarted();

                    // Setup virtual mouse right before launching
                    if (PREF_VIRTUAL_MOUSE_START) {
                        touchpad.post(() -> touchpad.switchState());
                    }

                    runCraft(finalVersion, mVersionInfo);
                }catch (Throwable e){
                    Tools.showErrorRemote(e);
                }
            });
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private void loadControls() {
        try {
            // Load keys
            mControlLayout.loadLayout(
                    minecraftProfile.controlFile == null
                            ? LauncherPreferences.PREF_DEFAULTCTRL_PATH
                            : Tools.CTRLMAP_PATH + "/" + minecraftProfile.controlFile);
        } catch(IOException e) {
            try {
                Log.w("MainActivity", "Unable to load the control file, loading the default now", e);
                mControlLayout.loadLayout(Tools.CTRLDEF_FILE);
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }
        mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        mControlLayout.toggleControlVisible();
    }

    @Override
    public void onAttachedToWindow() {
        // Post to get the correct display dimensions after layout.
        LauncherPreferences.computeNotchSize(this);
        mControlLayout.post(()->{
            Tools.getDisplayMetrics(this);
            loadControls();
        });
    }

    /** Boilerplate binding */
    private void bindValues(){
        mControlLayout = findViewById(R.id.main_control_layout);
        minecraftGLView = findViewById(R.id.main_game_render_view);
        touchpad = findViewById(R.id.main_touchpad);
        drawerLayout = findViewById(R.id.main_drawer_options);
        navDrawer = findViewById(R.id.main_navigation_view);
        loggerView = findViewById(R.id.mainLoggerView);
        mBootLog = findViewById(R.id.boot_log_overlay);
        if (mBootLog != null) mBootLog.setHost(() -> {
            // EXPAND: open the full log view; the mini overlay steps aside.
            if (loggerView != null) loggerView.setVisibility(View.VISIBLE);
            mBootLog.dismiss(true);
        });
        mControlLayout = findViewById(R.id.main_control_layout);
        touchCharInput = findViewById(R.id.mainTouchCharInput);
        mDrawerPullButton = findViewById(R.id.drawer_button);
        mHotbarView = findViewById(R.id.hotbar_view);
    }

    @Override
    public void onResume() {
        super.onResume();
        // CS PERFORMANCE ENGINE: raise the game threads for as long as the session is
        // foregrounded, attach the thermal governor to the same heartbeat, and start measuring.
        net.kdt.pojavlaunch.performance.PerformanceEngine.onResume(this);
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setHostResumed(true);
        if (minecraftGLView != null) minecraftGLView.onHostResume();
        if(mGameStateController!=null){if(mFirstGameFramePresented)mGameStateController.playing();else mGameStateController.loading();}
        if(PREF_ENABLE_GYRO) mGyroControl.enable();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
    }

    @Override
    protected void onPause() {
        net.kdt.pojavlaunch.performance.PerformanceEngine.onBackground(this);
        stopFpsTicker();
        if(mGameStateController!=null)mGameStateController.paused();
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setHostResumed(false);
        if (minecraftGLView != null) minecraftGLView.onHostPause();
        mGyroControl.disable();
        if (CallbackBridge.isGrabbing()){
            sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
        }
        if(mQuickSettingSideDialog != null) {
            mQuickSettingSideDialog.cancel();
        }
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);

        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    @Override
    protected void onStop() {
        net.kdt.pojavlaunch.performance.PerformanceEngine.onBackground(this);
        stopFpsTicker();
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setHostResumed(false);
        if (minecraftGLView != null) minecraftGLView.onHostPause();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.kdt.pojavlaunch.performance.GameSessionState.clear();
        Tools.MAIN_HANDLER.removeCallbacks(mFirstFrameWatcher);
        net.kdt.pojavlaunch.performance.PerformanceEngine.onSessionEnd(this);
        stopFpsTicker(); // CS Perf
        net.kdt.pojavlaunch.customcontrols.mouse.CustomCursorRenderer.setHostResumed(false);
        if (minecraftGLView != null) minecraftGLView.releaseHostResources();
        CallbackBridge.removeGrabListener(touchpad);
        CallbackBridge.removeGrabListener(minecraftGLView);
        ContextExecutor.clearActivity();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if(mGyroControl != null) mGyroControl.updateOrientation();
        // Layout resize is practically guaranteed on a configuration change, and `onConfigurationChanged`
        // does not implicitly start a layout. So, request a layout and expect the screen dimensions to be valid after the]
        // post.
        mControlLayout.requestLayout();
        mControlLayout.post(()->{
            // Child of mControlLayout, so refreshing size here is correct
            Tools.setFullscreen(this, setFullscreen());
            minecraftGLView.refreshSize();
            Tools.updateWindowSize(this);
            mControlLayout.refreshControlButtonPositions();
        });
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if(minecraftGLView != null)  // Useful when backing out of the app
            Tools.MAIN_HANDLER.postDelayed(() -> minecraftGLView.refreshSize(), 500);
        startFpsTicker(); // CS Perf: live FPS overlay (pref-gated, cosmetic only)
    }

    // ── CS Perf: FPS overlay chip — purely cosmetic, launch chain untouched ──
    private TextView mFpsChip;
    private final Runnable mFpsTicker = new Runnable() {
        @Override public void run() { tickFpsOverlay(); }
    };

    private TextView mMemChip;
    private long mLastMemorySampleUptime;
    private String mLastMemoryText = "RAM —";
    private static final long FPS_HUD_INTERVAL_MS = 750L;
    private static final long MEMORY_HUD_INTERVAL_MS = 2500L;

    private boolean isDiagnosticHudEnabled(String key) {
        try {
            SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                    ? LauncherPreferences.DEFAULT_PREF
                    : getSharedPreferences("cslauncher_settings", MODE_PRIVATE);
            return p.getBoolean(key, false);
        } catch (Throwable ignored) { return false; }
    }

    // ── Advanced FPS HUD ────────────────────────────────────────────────
    // Big animated number + small unit, colour-graded by frame rate, a soft
    // pulse on meaningful changes, and an optional fully transparent skin
    // (pref "fpsCounterTransparent") with a text shadow for readability.
    private int mLastShownFps = -1;
    private int mDisplayedFps = -1;               // animated value actually on screen
    private android.animation.ValueAnimator mFpsCountAnimator;
    private boolean mFpsTransparentApplied;
    private boolean mFpsSkinInitialized;
    private android.graphics.drawable.GradientDrawable mFpsDot, mMemDot;
    private long mLastMemoryMb = -1;
    private long mLastShownMemMb = -1;
    private long mDeviceTotalMemMb = -1;

    /** Small luminous status dot rendered as a compound drawable. */
    private android.graphics.drawable.GradientDrawable makeHudDot(TextView chip) {
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int px = (int) (7 * getResources().getDisplayMetrics().density);
        dot.setSize(px, px);
        dot.setBounds(0, 0, px, px);
        chip.setCompoundDrawables(dot, null, null, null);
        chip.setCompoundDrawablePadding((int) (6 * getResources().getDisplayMetrics().density));
        return dot;
    }

    private boolean mHudSkinChipsDone;
    private void applyHudSkin(TextView chip) {
        boolean transparent = isDiagnosticHudEnabled("fpsCounterTransparent");
        if (!mFpsSkinInitialized || transparent != mFpsTransparentApplied) {
            // Commit the flag after both chips have had a chance to restyle this tick.
            if (mHudSkinChipsDone) { mFpsSkinInitialized = true; mFpsTransparentApplied = transparent; mHudSkinChipsDone = false; }
            else mHudSkinChipsDone = true;
            // (skin flag is shared — both chips restyle together on toggle)
            if (transparent) {
                chip.setBackground(null);
                chip.setShadowLayer(6f, 0f, 1f, 0xCC000000);
            } else {
                chip.setBackgroundResource(R.drawable.bg_fps_chip);
                chip.setShadowLayer(0f, 0f, 0f, 0);
            }
        }
    }

    private void renderFpsText(TextView chip, int fps, int color) {
        android.text.SpannableString text = new android.text.SpannableString(fps + " FPS");
        int numLen = String.valueOf(fps).length();
        text.setSpan(new android.text.style.RelativeSizeSpan(1.45f),
                0, numLen, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.ForegroundColorSpan(color),
                0, numLen, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.ForegroundColorSpan(0xFF9AA0AE),
                numLen, text.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, numLen, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        chip.setText(text);
    }

    private void updateFpsChipAdvanced(TextView chip, int fps) {
        applyHudSkin(chip);
        if (mFpsDot == null) mFpsDot = makeHudDot(chip);

        // Colour grade: platinum ≥60, soft amber 30–59, soft red <30.
        final int color = fps >= 60 ? 0xFFF0F1F5 : (fps >= 30 ? 0xFFE8C989 : 0xFFFF6B6B);
        final int dotColor = fps >= 60 ? 0xFF62D99B : (fps >= 30 ? 0xFFE8C989 : 0xFFFF6B6B);
        mFpsDot.setColor(dotColor);

        // ── Rolling odometer: the number glides from the old value to the
        // new one instead of teleporting — feels like a real instrument.
        if (mDisplayedFps < 0 || !chip.isShown()) {
            mDisplayedFps = fps;
            renderFpsText(chip, fps, color);
        } else if (fps != mDisplayedFps) {
            if (mFpsCountAnimator != null) mFpsCountAnimator.cancel();
            mFpsCountAnimator = android.animation.ValueAnimator.ofInt(mDisplayedFps, fps);
            mFpsCountAnimator.setDuration(420);
            mFpsCountAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            mFpsCountAnimator.addUpdateListener(a -> {
                mDisplayedFps = (int) a.getAnimatedValue();
                renderFpsText(chip, mDisplayedFps, color);
            });
            mFpsCountAnimator.start();
        }

        // Soft breathing pulse on meaningful jumps (≥5 fps).
        if (mLastShownFps >= 0 && Math.abs(fps - mLastShownFps) >= 5) {
            chip.animate().cancel();
            chip.setScaleX(1.06f); chip.setScaleY(1.06f);
            chip.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
        }
        mLastShownFps = fps;
    }

    /** RAM HUD v2 — big MB number, usage-graded dot, pulse on real movement. */
    private void updateMemChipAdvanced(TextView chip, long mb) {
        applyHudSkin(chip);
        if (mMemDot == null) mMemDot = makeHudDot(chip);
        if (mb < 0) { chip.setText("RAM —"); return; }

        if (mDeviceTotalMemMb <= 0) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                if (am != null) { am.getMemoryInfo(mi); mDeviceTotalMemMb = mi.totalMem / (1024L * 1024L); }
            } catch (Throwable ignored) { mDeviceTotalMemMb = 0; }
        }
        float frac = mDeviceTotalMemMb > 0 ? (float) mb / mDeviceTotalMemMb : 0f;
        int numColor = frac < 0.45f ? 0xFFF0F1F5 : (frac < 0.7f ? 0xFFE8C989 : 0xFFFF6B6B);
        mMemDot.setColor(frac < 0.45f ? 0xFF62D99B : (frac < 0.7f ? 0xFFE8C989 : 0xFFFF6B6B));

        String num = String.valueOf(mb);
        android.text.SpannableString text = new android.text.SpannableString(num + " MB");
        text.setSpan(new android.text.style.RelativeSizeSpan(1.45f),
                0, num.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.ForegroundColorSpan(numColor),
                0, num.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.ForegroundColorSpan(0xFF9AA0AE),
                num.length(), text.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, num.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        chip.setText(text);

        if (mLastShownMemMb >= 0 && Math.abs(mb - mLastShownMemMb) >= 24) {
            chip.animate().cancel();
            chip.setScaleX(1.06f); chip.setScaleY(1.06f);
            chip.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                    .start();
        }
        mLastShownMemMb = mb;
    }

    private void startFpsTicker() {
        if (mFpsChip == null) mFpsChip = findViewById(R.id.fps_counter_chip);
        if (mMemChip == null) mMemChip = findViewById(R.id.mem_counter_chip);
        if (mFpsChip != null) armFpsChipDrag(mFpsChip, "fpsChipTx", "fpsChipTy");
        if (mMemChip != null) armFpsChipDrag(mMemChip, "memChipTx", "memChipTy");
        Tools.MAIN_HANDLER.removeCallbacks(mFpsTicker);
        boolean showFps = isDiagnosticHudEnabled("showFpsCounter");
        boolean showMem = isDiagnosticHudEnabled("showMemoryChip");
        if (!showFps && !showMem) {
            if (mFpsChip != null) mFpsChip.setVisibility(View.GONE);
            if (mMemChip != null) mMemChip.setVisibility(View.GONE);
            return; // No hidden heartbeat while both diagnostics are disabled.
        }
        Tools.MAIN_HANDLER.post(mFpsTicker);
    }

    /** Re-evaluate HUD scheduling immediately after the in-game toggle changes. */
    public void refreshDiagnosticHudTicker() {
        if (hasWindowFocus()) startFpsTicker();
    }

    // Item-2: the live FPS overlay is user-draggable anywhere on screen.
    // Position persists per device (cslauncher_settings → fpsChipTx/Ty) and is
    // restored the next time the chip appears. Clamp keeps the chip fully
    // inside the content frame; a light press-scale gives premium feedback.
    private boolean mFpsDragArmed;
    private boolean mMemDragArmed;

    /** Long-press-free drag for the HUD chips (user req: place them anywhere).
     *  @param keyX/keyY per-chip persistence keys so every chip remembers its spot. */
    private void armFpsChipDrag(final TextView chip, final String keyX, final String keyY) {
        if ("fpsChipTx".equals(keyX)) {
            if (mFpsDragArmed) return;
            mFpsDragArmed = true;
        } else {
            if (mMemDragArmed) return;
            mMemDragArmed = true;
        }
        chip.setOnTouchListener(new View.OnTouchListener() {
            private float mDx, mDy;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        mDx = ev.getRawX() - v.getTranslationX();
                        mDy = ev.getRawY() - v.getTranslationY();
                        v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(110).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        View parent = (View) v.getParent();
                        if (parent == null) return true;
                        float tx = ev.getRawX() - mDx;
                        float ty = ev.getRawY() - mDy;
                        tx = Math.max(-v.getLeft(), Math.min(tx, parent.getWidth() - v.getWidth() - v.getLeft()));
                        ty = Math.max(-v.getTop(), Math.min(ty, parent.getHeight() - v.getHeight() - v.getTop()));
                        v.setTranslationX(tx);
                        v.setTranslationY(ty);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        try {
                            SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                                    ? LauncherPreferences.DEFAULT_PREF
                                    : getSharedPreferences("cslauncher_settings", MODE_PRIVATE);
                            p.edit()
                                    .putInt(keyX, (int) v.getTranslationX())
                                    .putInt(keyY, (int) v.getTranslationY())
                                    .apply();
                            // Game process write: mirror it so the launcher cannot revert it.
                            net.kdt.pojavlaunch.prefs.SharedSettings.mirror(
                                    MainActivity.this, keyX, (int) v.getTranslationX());
                            net.kdt.pojavlaunch.prefs.SharedSettings.mirror(
                                    MainActivity.this, keyY, (int) v.getTranslationY());
                        } catch (Throwable ignored) {}
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    /** Restore the saved chip offset (clamped to the current frame) once laid out. */
    private void restoreFpsChipPosition(final TextView chip, final String keyX, final String keyY) {
        chip.post(() -> {
            try {
                SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                        ? LauncherPreferences.DEFAULT_PREF
                        : getSharedPreferences("cslauncher_settings", MODE_PRIVATE);
                float tx = p.getInt(keyX, 0);
                float ty = p.getInt(keyY, 0);
                View parent = (View) chip.getParent();
                if (parent != null) {
                    tx = Math.max(-chip.getLeft(), Math.min(tx, parent.getWidth() - chip.getWidth() - chip.getLeft()));
                    ty = Math.max(-chip.getTop(), Math.min(ty, parent.getHeight() - chip.getHeight() - chip.getTop()));
                }
                chip.setTranslationX(tx);
                chip.setTranslationY(ty);
            } catch (Throwable ignored) {}
        });
    }

    private void stopFpsTicker() {
        Tools.MAIN_HANDLER.removeCallbacks(mFpsTicker);
    }

    private void tickFpsOverlay() {
        TextView chip = mFpsChip;
        if (chip == null || isFinishing() || isDestroyed()) return;
        boolean showFps = false, showMem = false;
        try {
            SharedPreferences p = LauncherPreferences.DEFAULT_PREF != null
                    ? LauncherPreferences.DEFAULT_PREF
                    : getSharedPreferences("cslauncher_settings", MODE_PRIVATE);
            showFps = p.getBoolean("showFpsCounter", false);
            showMem = p.getBoolean("showMemoryChip", false);
        } catch (Throwable ignored) {}

        if (!showFps) {
            if (chip.getVisibility() != View.GONE) chip.setVisibility(View.GONE);
        } else {
            if (chip.getVisibility() != View.VISIBLE) {
                chip.setVisibility(View.VISIBLE);
                restoreFpsChipPosition(chip, "fpsChipTx", "fpsChipTy");
            }
            int fps = net.kdt.pojavlaunch.utils.FpsCounter.getFps();
            if (fps >= 0) updateFpsChipAdvanced(chip, fps);
        }

        // RAM/PSS is a diagnostic query, not a frame metric. Sample it much
        // slower than FPS even when both chips are visible.
        TextView mem = mMemChip;
        if (mem != null) {
            if (!showMem) {
                if (mem.getVisibility() != View.GONE) mem.setVisibility(View.GONE);
            } else {
                if (mem.getVisibility() != View.VISIBLE) {
                    mem.setVisibility(View.VISIBLE);
                    restoreFpsChipPosition(mem, "memChipTx", "memChipTy");
                }
                long now = android.os.SystemClock.uptimeMillis();
                if (mLastMemorySampleUptime == 0
                        || now - mLastMemorySampleUptime >= MEMORY_HUD_INTERVAL_MS) {
                    mLastMemoryText = buildMemoryText();
                    mLastMemorySampleUptime = now;
                }
                updateMemChipAdvanced(mem, mLastMemoryMb);
            }
        }

        if (!showFps && !showMem) {
            return; // Completely stop until a toggle explicitly restarts it.
        }
        Tools.MAIN_HANDLER.postDelayed(mFpsTicker,
                showFps ? FPS_HUD_INTERVAL_MS : MEMORY_HUD_INTERVAL_MS);
    }

    /** "RAM 1234 MB" — real total PSS of the game process (includes everything). */
    private String buildMemoryText() {
        long mb = -1;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) {
                android.os.Debug.MemoryInfo[] mi =
                        am.getProcessMemoryInfo(new int[]{android.os.Process.myPid()});
                if (mi != null && mi.length > 0 && mi[0] != null)
                    mb = mi[0].getTotalPss() / 1024L;
            }
        } catch (Throwable ignored) {}
        mLastMemoryMb = mb;
        return mb < 0 ? "RAM —" : "RAM " + mb + " MB";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            // Reload PREF_DEFAULTCTRL_PATH
            // If the storage root got unmounted/unreadable we won't be able to load the file anyway,
            // and MissingStorageActivity will be started.
            if(!Tools.checkStorageRoot(this)) return;
            LauncherPreferences.loadPreferences(getApplicationContext());
            try {
                mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void runCraft(String versionId, JMinecraftVersionList.Version version) throws Throwable {
        String assetVersion;
        try {
            if (version.inheritsFrom != null) { // We are almost definitely modded if this runs
                File vanillaJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + version.inheritsFrom + "/" + version.inheritsFrom + ".json");
                JMinecraftVersionList.Version vanillaJson;
                try { // Get the vanilla json from modded instance
                    vanillaJson = Tools.GLOBAL_GSON.fromJson(Tools.read(vanillaJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);
                } catch (IOException ignored) { // Should never happen, we check for this in MinecraftDownloader().start()
                    throw new RuntimeException(getString(R.string.error_vanilla_json_corrupt));
                }
                // Something went wrong if this is somehow not the case anymore
                if (!Objects.equals(vanillaJson.assets, vanillaJson.assetIndex.id))
                    Tools.showErrorRemote(new RuntimeException(getString(R.string.error_vanilla_json_corrupt)));
                assetVersion = vanillaJson.assets;
            } else {
                // Else assume we are vanilla
                if (!Objects.equals(version.assets, version.assetIndex.id))
                    Tools.showErrorRemote(new RuntimeException(getString(R.string.error_vanilla_json_corrupt)));
                assetVersion = version.assets;
            }
       } catch (RuntimeException ignored){
            assetVersion = "legacy";
       } // If this fails.. oh well.

        // FIXME: Automatic detection should be based on provided hint GLFW_CONTEXT_VERSION_MAJOR and GLFW_CONTEXT_VERSION_MINOR
        // Autoselect renderer
        if (Tools.LOCAL_RENDERER == null) {
            // Preferably we could detect when it is modded and swap to zink however that would also
            // cover optifine and vanilla+ configurations which are relatively common, degrading their
            // experience for no reason. We will compromise with just having users do it themselves.
            Tools.LOCAL_RENDERER = "opengles2";
            // MobileGlues becomes available post 1.17. It has superior compatibility with mods
            // while having fairly similar performance compared to GL4ES-based forks.
            if(assetVersion.matches("\\d+") || // Should match all digits, which is the modern assetVersioning
               "1.17".equals(assetVersion) ||
               "1.18".equals(assetVersion) ||
               "1.19".equals(assetVersion) ||
                // Angelica gives us GL3.3core on 1.7.10, it's a unique case.
                hasMods("angelica")) Tools.LOCAL_RENDERER = "opengles_mobileglues";
        }
        if(!Tools.checkRendererCompatible(this, Tools.LOCAL_RENDERER)) {
            Tools.RenderersList renderersList = Tools.getCompatibleRenderers(this);
            if (renderersList.rendererIds.isEmpty()) {
                throw new IllegalStateException("No compatible renderer libraries are installed");
            }
            boolean modernVersion = assetVersion.matches("\\d+")
                    || "1.17".equals(assetVersion) || "1.18".equals(assetVersion)
                    || "1.19".equals(assetVersion) || "1.20".equals(assetVersion)
                    || "1.21".equals(assetVersion);
            String safeRenderer = null;
            if (modernVersion && renderersList.rendererIds.contains("opengles_mobileglues"))
                safeRenderer = "opengles_mobileglues";
            else if (renderersList.rendererIds.contains("opengles3_ltw"))
                safeRenderer = "opengles3_ltw";
            else if (renderersList.rendererIds.contains("opengles3_KW"))
                safeRenderer = "opengles3_KW";
            else if (renderersList.rendererIds.contains("opengles2"))
                safeRenderer = "opengles2";
            else safeRenderer = renderersList.rendererIds.get(0);
            Log.w("runCraft","Incompatible renderer "+Tools.LOCAL_RENDERER+ " will be replaced with "+safeRenderer);
            Tools.LOCAL_RENDERER = safeRenderer;
            runOnUiThread(() -> Toast.makeText(this, R.string.autorendererselectfailed, Toast.LENGTH_LONG).show());
            Tools.releaseRenderersCache();
        }

        // MCL-3732 Mitigation
        // I don't trust the bug tracker. 'server-resource-pack" was removed in 1.20.3-pre3
        // so we use 12 to detect that. We still generate till 1.20.5 else we don't cover
        // 1.20.3-pre2 and such. Better to over than to under.
        File folder = new File(Tools.getGameDirPath(minecraftProfile), "server-resource-pack");
        try {
            if (Integer.parseInt(assetVersion) <= 12) folder.mkdir();
        } catch (NumberFormatException e) { folder.mkdir(); }

        if (hasMods("sodium"))
            Logger.appendToLog("WARNING: Sodium is being used. CS LAUNCHER PLUS supports it, but if you encounter visual glitches or crashes, report them in our community!");
        Tools.printLauncherInfo(versionId, Tools.isValidString(minecraftProfile.javaArgs) ? minecraftProfile.javaArgs : LauncherPreferences.PREF_CUSTOM_JAVA_ARGS, Tools.getTotalDeviceMemory(this));
        if(Tools.LOCAL_RENDERER.equals("opengles_mobileglues")) {
            try {
                // MobileGlues needs its config.json freshly written before every launch,
                // otherwise it runs with defaults that break Sodium chunk rendering.
                LauncherPreferences.writeMGRendererSettings();
            } catch (java.io.IOException e) {
                Log.w("runCraft", "Failed to write MobileGlues renderer settings", e);
            }
        }

        // Minecraft owns every graphics/quality default. The launcher does not
        // create a performance preset or mutate render distance, simulation
        // distance, VSync, FPS limit, particles, clouds, shadows, or related
        // visual settings. Existing options.txt remains user/Minecraft-owned.

        // Use the exact account carried across the launcher → game process
        // boundary. Re-reading async SharedPreferences here could select a stale
        // local account and wrongly enable the offline skin injector.
        MinecraftAccount minecraftAccount = PojavProfile.getCurrentProfileContent(this, mLaunchAccountName);
        try { if(net.kdt.pojavlaunch.csclient.CsClientManagedFiles.ensureForProfile(this,minecraftProfile)) Log.i("CSClient","Bundled managed core verified before launch"); } catch(Exception updateError){ throw new RuntimeException("Unable to verify bundled CS Client",updateError); }
        JREUtils.redirectAndPrintJRELog();
        LauncherProfiles.load();
        int requiredJavaVersion = 8;
        if(version.javaVersion != null) requiredJavaVersion = version.javaVersion.majorVersion;
        Tools.launchMinecraft(this, minecraftAccount, minecraftProfile, versionId, requiredJavaVersion);
        //Note that we actually stall in the above function, even if the game crashes. But let's be safe.
        Tools.runOnUiThread(()-> mServiceBinder.isActive = false);
    }

    private void dialogSendCustomKey() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle(R.string.control_customkey);
        dialog.setItems(EfficientAndroidLWJGLKeycode.generateKeyName(), (dInterface, position) -> EfficientAndroidLWJGLKeycode.execKeyIndex(position));
        dialog.show();
    }

    boolean isInEditor;

    /** User req: browse & install resource/shader packs from INSIDE the game
     *  (in-game drawer) into the current profile — never leave the session. */
    private void openResourceBrowser() {
        try {
            net.kdt.pojavlaunch.fragments.ResourceBrowserDialog.show(this);
        } catch (Throwable t) {
            Tools.showError(this, t);
        }
    }

    private void setDrawerKicker(String text) {
        try {
            TextView k = navDrawer.findViewById(R.id.igm_hdr_kicker);
            if (k != null) k.setText(text);
        } catch (Throwable ignored) {}
    }

    /** Phase 9: drawer header (profile · version) + replayed stagger on each open. */
    private void setupInGameDrawerChrome() {
        try {
            if (navDrawer.getHeaderViewsCount() == 0) {
                View header = getLayoutInflater().inflate(R.layout.view_ingame_menu_header, navDrawer, false);
                TextView t = header.findViewById(R.id.igm_hdr_title);
                TextView sub = header.findViewById(R.id.igm_hdr_sub);
                View dot = header.findViewById(R.id.igm_hdr_dot);
                String name = minecraftProfile != null && minecraftProfile.name != null && !minecraftProfile.name.isEmpty()
                        ? minecraftProfile.name : "Minecraft";
                String ver = minecraftProfile != null && minecraftProfile.lastVersionId != null
                        ? minecraftProfile.lastVersionId : "";
                t.setText(name);
                sub.setText(ver.isEmpty() ? "in game" : ver + "  ·  running");
                if (dot != null) dot.setSelected(true);
                navDrawer.addHeaderView(header, null, false);
            }
            drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
                @Override public void onDrawerSlide(View drawerView, float slideOffset) {
                    if (slideOffset > 0f && slideOffset < 0.05f && !isInEditor
                            && gameActionArrayAdapter instanceof net.kdt.pojavlaunch.ui.InGameMenuAdapter) {
                        ((net.kdt.pojavlaunch.ui.InGameMenuAdapter) gameActionArrayAdapter).armEntrance();
                    }
                }
                @Override public void onDrawerOpened(View drawerView) {
                    if (!isInEditor && gameActionArrayAdapter instanceof net.kdt.pojavlaunch.ui.InGameMenuAdapter) {
                        gameActionArrayAdapter.notifyDataSetChanged();
                    }
                }
            });
        } catch (Throwable t) {
            Log.w("MainActivity", "drawer chrome failed", t);
        }
    }

    private void openCustomControls() {
        if(ingameControlsEditorListener == null || ingameControlsEditorArrayAdapter == null) return;

        mControlLayout.setModifiable(true);
        navDrawer.setAdapter(ingameControlsEditorArrayAdapter);
        navDrawer.setOnItemClickListener(ingameControlsEditorListener);
        mDrawerPullButton.setVisibility(View.VISIBLE);
        isInEditor = true;
        setDrawerKicker("CONTROL EDITOR");
    }

    private void openLogOutput() {
        loggerView.setVisibility(View.VISIBLE);
        if (mBootLog != null) mBootLog.dismiss(true);
    }

    /**
     * Phase 10: the first touch on the game (not on the drawer/overlays) also
     * retires the mini boot console — the "line across half the screen" was
     * this overlay outliving a boot whose frames were never counted.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev != null && ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                && mBootLog != null && !mBootLog.isDismissed()
                && (drawerLayout == null || !drawerLayout.isDrawerOpen(android.view.Gravity.RIGHT))) {
            mBootLog.onGameVisible();
        }
        return super.dispatchTouchEvent(ev);
    }

    private void openQuickSettings() {
        if(mQuickSettingSideDialog == null) {
            mQuickSettingSideDialog = new QuickSettingSideDialog(this, mControlLayout) {
                @Override
                public void onResolutionChanged() {
                    minecraftGLView.refreshSize();
                    mHotbarView.onResolutionChanged();
                }

                @Override
                public void onGyroStateChanged() {
                    mGyroControl.updateOrientation();
                    if (PREF_ENABLE_GYRO) {
                        mGyroControl.enable();
                    } else {
                        mGyroControl.disable();
                    }
                }
            };
        }
        mQuickSettingSideDialog.appear(true);
    }

    public static void toggleMouse(Context ctx) {
        if (CallbackBridge.isGrabbing()) return;

        Toast.makeText(ctx, touchpad.switchState()
                        ? R.string.control_mouseon : R.string.control_mouseoff,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if(isInEditor) {
            if(event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if(event.getAction() == KeyEvent.ACTION_DOWN) mControlLayout.askToExit(this);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handleEvent;
        if(!(handleEvent = minecraftGLView.processKeyEvent(event))) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !touchCharInput.isEnabled()) {
                if(event.getAction() != KeyEvent.ACTION_UP) return true; // We eat it anyway
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
                return true;
            }
        }
        return handleEvent;
    }

    public static void switchKeyboardState() {
        if(touchCharInput != null) touchCharInput.switchKeyboardState();
    }

    @Keep
    public static void openLink(String link) {
        Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
        ((Activity)ctx).runOnUiThread(() -> {
            try {
                if(link.startsWith("file:")) {
                    int truncLength = 5;
                    if(link.startsWith("file://")) truncLength = 7;
                    String path = link.substring(truncLength);
                    Tools.openPath(ctx, new File(path), false);
                }else {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(link), "*/*");
                    ctx.startActivity(intent);
                }
            } catch (Throwable th) {
                Tools.showError(ctx, th);
            }
        });
    }

    @SuppressWarnings("unused") //TODO: actually use it
    public static void openPath(String path) {
        Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
        ((Activity)ctx).runOnUiThread(() -> {
            try {
                Tools.openPath(ctx, new File(path), false);
            } catch (Throwable th) {
                Tools.showError(ctx, th);
            }
        });
    }

    @Keep
    public static void querySystemClipboard() {
        Tools.runOnUiThread(()->{
            ClipData clipData = GLOBAL_CLIPBOARD.getPrimaryClip();
            if(clipData == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }
            ClipData.Item firstClipItem = clipData.getItemAt(0);
            //TODO: coerce to HTML if the clip item is styled
            CharSequence clipItemText = firstClipItem.getText();
            if(clipItemText == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }
            AWTInputBridge.nativeClipboardReceived(clipItemText.toString(), "plain");
        });
    }

    @Keep
    public static void putClipboardData(String data, String mimeType) {
        Tools.runOnUiThread(()-> {
            ClipData clipData = null;
            switch(mimeType) {
                case "text/plain":
                    clipData = ClipData.newPlainText("AWT Paste", data);
                    break;
                case "text/html":
                    clipData = ClipData.newHtmlText("AWT Paste", data, data);
            }
            if(clipData != null) GLOBAL_CLIPBOARD.setPrimaryClip(clipData);
        });
    }

    @Override
    public void onClickedMenu() {
        drawerLayout.openDrawer(navDrawer);
        navDrawer.requestLayout();
    }

    @Override
    public void exitEditor() {
        try {
            mControlLayout.loadLayout((CustomControls)null);
            mControlLayout.setModifiable(false);
            // Let the runtime collect naturally; forced full GC can stall gameplay.
            mControlLayout.loadLayout(
                    minecraftProfile.controlFile == null
                            ? LauncherPreferences.PREF_DEFAULTCTRL_PATH
                            : Tools.CTRLMAP_PATH + "/" + minecraftProfile.controlFile);
            mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        } catch (IOException e) {
            Tools.showError(this,e);
        }

        navDrawer.setAdapter(gameActionArrayAdapter);
        navDrawer.setOnItemClickListener(gameActionClickListener);
        isInEditor = false;
        setDrawerKicker("GAME MENU");
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        GameService.LocalBinder localBinder = (GameService.LocalBinder) service;
        mServiceBinder = localBinder;
        minecraftGLView.start(localBinder.isActive, touchpad);
        localBinder.isActive = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    /*
     * Android 14 (or some devices, at least) seems to dispatch the the captured mouse events as trackball events
     * due to a bug(?) somewhere(????)
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean checkCaptureDispatchConditions(MotionEvent event) {
        int eventSource = event.getSource();
        // On my device, the mouse sends events as a relative mouse device.
        // Not comparing with == here because apparently `eventSource` is a mask that can
        // sometimes indicate multiple sources, like in the case of InputDevice.SOURCE_TOUCHPAD
        // (which is *also* an InputDevice.SOURCE_MOUSE when controlling a cursor)
        return (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) != 0 ||
                (eventSource & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if(Tools.isAndroid8OrHigher() && checkCaptureDispatchConditions(ev))
            return minecraftGLView.dispatchCapturedPointerEvent(ev);
        else return super.dispatchTrackballEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            Tools.setFullscreen(this, setFullscreen());
        }
        super.onWindowFocusChanged(hasFocus);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, hasFocus ? 1 : 0);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
