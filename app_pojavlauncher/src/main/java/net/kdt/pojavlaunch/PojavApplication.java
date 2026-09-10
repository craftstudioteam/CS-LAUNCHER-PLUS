package net.kdt.pojavlaunch;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.*;
import android.os.*;
import androidx.core.app.*;

import android.util.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.tasks.AsyncAssetManager;
import net.kdt.pojavlaunch.utils.*;
import net.kdt.pojavlaunch.utils.FileUtils;

public class PojavApplication extends Application {
	private static volatile PojavApplication sInstance;

	/** Process-wide application instance, safe to use for SharedPreferences. */
	public static PojavApplication getInstance() { return sInstance; }

	public static final String CRASH_REPORT_TAG = "PojavCrashReport";

	/**
	 * Adaptive thread pool for background tasks.
	 * - Core threads: ceil(CPU cores * 1.5) for I/O-bound operations.
	 * - Max threads: CPU cores * 2 + 1 to handle bursts.
	 * - Keep-alive: 5 seconds to reclaim idle threads quickly on low-end devices.
	 * - Daemon threads: don't block process shutdown.
	 */
	public static final ExecutorService sExecutorService = new ThreadPoolExecutor(
			Math.max(2, (Runtime.getRuntime().availableProcessors() * 3) / 2),
			Math.max(4, Runtime.getRuntime().availableProcessors() * 2 + 1),
			5, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(),
			r -> {
				Thread t = new Thread(r, "CSL-Worker");
				t.setDaemon(true);
				return t;
			});
	
	@Override
	public void onCreate() {
		sInstance = this;
		ContextExecutor.setApplication(this);

		// Post-mortem for NATIVE crashes: the GL renderer persists its phase to
		// gl_breadcrumb.txt; "ok" means a healthy session. Anything else means the
		// previous session died inside the GL pipeline without a Java exception
		// (SIGSEGV in the vendor driver) — record it where the crash report lives.
		try {
			File glBc = new File(getFilesDir(), "gl_breadcrumb.txt");
			if (glBc.isFile()) {
				java.io.BufferedReader br = new java.io.BufferedReader(
						new java.io.InputStreamReader(new java.io.FileInputStream(glBc)));
				String phase = br.readLine();
				br.close();
				phase = phase == null ? "" : phase.trim();
				if (!phase.isEmpty() && !"ok".equals(phase)) {
					File dir = new File(getFilesDir(), "crash");
					File note = new File(dir.isDirectory() ? dir : getFilesDir(), "latestcrash.txt");
					java.io.PrintWriter w = new java.io.PrintWriter(note);
					w.println("CS LAUNCHER PLUS crash report");
					w.println(" - Time: " + DateFormat.getDateTimeInstance().format(new Date()));
					w.println(" - Suspected NATIVE crash: previous session died without any Java exception.");
					w.println(" - GL phase at death: " + phase);
					w.println(" - surface_created/programs_ready = driver failed during 3D renderer init;");
					w.println("   java_fail_* = caught Java-side (renderer disabled itself, app survived).");
					w.close();
					Log.e(CRASH_REPORT_TAG, "Previous session died in GL phase: " + phase);
					// Surface the silent crash visibly: an error card appears as
					// soon as the launcher UI is up, with one-tap copy of the report.
					final String fPhase = phase;
					final File fNote = note;
					net.kdt.pojavlaunch.notifications.CsNotifier.queueBootNotice(
							net.kdt.pojavlaunch.notifications.CsNotifier.TYPE_ERROR,
							"Previous session crashed",
							"Stopped in 3D phase: " + fPhase,
							"Copy report",
							() -> {
								try {
									android.content.ClipboardManager cm = (android.content.ClipboardManager)
											getSystemService(CLIPBOARD_SERVICE);
									StringBuilder sb = new StringBuilder();
									java.io.BufferedReader cr = new java.io.BufferedReader(
											new java.io.InputStreamReader(new java.io.FileInputStream(fNote)));
									String cl;
									while ((cl = cr.readLine()) != null) sb.append(cl).append('\n');
									cr.close();
									cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", sb.toString()));
								} catch (Throwable ignored) {}
							});
				}
				//noinspection ResultOfMethodCallIgnored
				glBc.delete();
			}
		} catch (Throwable ignored) {}
		Thread.setDefaultUncaughtExceptionHandler((thread, th) -> {
			// The reporter itself must never die: a broken reporter means silent
			// crashes with no file and no dialog (exactly what users saw before).
			Log.e(CRASH_REPORT_TAG, "Uncaught exception on thread " + thread.getName(), th);
			File crashFile = null;
			try {
				// Pick the first writable location — storage permission quirks must
				// not stop the report from being persisted.
				File root = Tools.DIR_GAME_HOME != null ? new File(Tools.DIR_GAME_HOME) : Tools.getPojavStorageRoot(PojavApplication.this);
				if (root == null || !root.canWrite()) root = PojavApplication.this.getExternalFilesDir(null);
				if (root == null || !root.canWrite()) root = new File(PojavApplication.this.getFilesDir(), "crash");
				crashFile = new File(root, "latestcrash.txt");
				FileUtils.ensureParentDirectory(crashFile);
				PrintStream crashStream = new PrintStream(crashFile);
				crashStream.append("CS LAUNCHER PLUS crash report\n");
				crashStream.append(" - Time: ").append(DateFormat.getDateTimeInstance().format(new Date())).append("\n");
				crashStream.append(" - Device: ").append(Build.PRODUCT).append(" ").append(Build.MODEL).append("\n");
				crashStream.append(" - Android version: ").append(Build.VERSION.RELEASE).append("\n");
				crashStream.append(" - Crash stack trace:\n");
				crashStream.append(" - Launcher version: " + BuildConfig.VERSION_NAME + "\n");
				crashStream.append(Log.getStackTraceString(th));
				crashStream.close();
			} catch (Throwable throwable) {
				Log.e(CRASH_REPORT_TAG, " - Exception attempt saving crash stack trace:", throwable);
			}
			try {
				// Runs in the separate :crash process, so it survives the kill below.
				FatalErrorActivity.showError(PojavApplication.this,
						crashFile == null ? "unavailable" : crashFile.getAbsolutePath(),
						crashFile != null, th);
			} catch (Throwable throwable) {
				Log.e(CRASH_REPORT_TAG, " - Exception attempt showing crash screen:", throwable);
			}
			try {
				Tools.fullyExit();
			} catch (Throwable throwable) {
				android.os.Process.killProcess(android.os.Process.myPid());
			}

		});
		
		try {
			super.onCreate();
			if(Tools.checkStorageRoot(this)){
				// Implicitly initializes early constants and storage constants.
				// Required to run the main activity properly.
				LauncherPreferences.loadPreferences(this);
			} else {
				// In other cases, only initialize enough for the basicmost basics to work
				// and not explode.
				Tools.initEarlyConstants(this);
			}
			Tools.DEVICE_ARCHITECTURE = Architecture.getDeviceArchitecture();
			//Force x86 lib directory for Asus x86 based zenfones
			if(Architecture.isx86Device() && Architecture.is32BitsDevice()){
				String originalJNIDirectory = getApplicationInfo().nativeLibraryDir;
				getApplicationInfo().nativeLibraryDir = originalJNIDirectory.substring(0,
												originalJNIDirectory.lastIndexOf("/"))
												.concat("/x86");
			}
			// The download notification is a foreground service, and a foreground
			// service has to be started by somebody. ProgressServiceKeeper existed
			// for exactly this and was registered nowhere — grep showed zero
			// construction sites — so ProgressService.startService() was never
			// called and the shade stayed empty however good the notification code
			// inside the service was. Registering it application-wide (not on an
			// Activity) is also what keeps bytes moving and the percentage live
			// after the launcher leaves the foreground.
			net.kdt.pojavlaunch.progresskeeper.ProgressKeeper.addTaskCountListener(
					new net.kdt.pojavlaunch.services.ProgressServiceKeeper(this), false);
			// If FCM recreates :launcher during gameplay, keep Application startup
			// lightweight and defer runtime/update work until the game session ends.
			// One synchronous read at process start — the only place the cache may not
			// be trusted yet; every later reader uses the cached value.
			net.kdt.pojavlaunch.performance.GameSessionState.refreshNow(this);
			if (!net.kdt.pojavlaunch.performance.GameSessionState.isGameActiveCached())
				AsyncAssetManager.unpackRuntime(getAssets());
		} catch (Throwable throwable) {
			Intent ferrorIntent = new Intent(this, FatalErrorActivity.class);
			ferrorIntent.putExtra("throwable", throwable);
			ferrorIntent.setFlags(FLAG_ACTIVITY_NEW_TASK);
			startActivity(ferrorIntent);
		}
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
		ContextExecutor.clearApplication();
	}

	@Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleUtils.setLocale(base));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleUtils.setLocale(this);
    }
}
