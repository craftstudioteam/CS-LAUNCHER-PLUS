package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class FatalErrorActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		try {
		 onCreateImpl();
		} catch (Throwable t) {
			android.util.Log.e("FatalError", "report dialog failed", t);
			finish();
		}
	}

	private void onCreateImpl() {
		Bundle extras = getIntent().getExtras();
		if(extras == null) {
			finish();
			return;
		}
		boolean storageAllow = extras.getBoolean("storageAllow", false);
		// The stack now travels as a plain String extra (binder-safe across the
		// :crash process boundary); the old Serializable extra stays as fallback.
		String stack = extras.getString("stack");
		if (stack == null) {
			Throwable throwable = (Throwable) extras.getSerializable("throwable");
			stack = throwable != null ? Tools.printToString(throwable) : "<null>";
		}
		final String stackTrace = stack;
		String strSavePath = extras.getString("savePath");
		String errHeader = storageAllow ?
			"Crash stack trace saved to " + strSavePath + "." :
			"Storage permission is required to save crash stack trace!";
		
		new AlertDialog.Builder(this)
			.setTitle(R.string.error_fatal)
			.setMessage(errHeader + "\n\n" + stackTrace)
			.setPositiveButton(android.R.string.ok, (p1, p2) -> finish())
			.setNegativeButton(R.string.global_restart, (p1, p2) -> startActivity(new Intent(FatalErrorActivity.this, LauncherActivity.class)))
			.setNeutralButton(android.R.string.copy, (p1, p2) -> {
				ClipboardManager mgr = (ClipboardManager) FatalErrorActivity.this.getSystemService(CLIPBOARD_SERVICE);
				mgr.setPrimaryClip(ClipData.newPlainText("error", stackTrace));

				finish();
			})
			.setCancelable(false)
			.show();
	}

	public static void showError(Context ctx, String savePath, boolean storageAllow, Throwable th) {
		Intent fatalErrorIntent = new Intent(ctx, FatalErrorActivity.class);
		fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
		fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		fatalErrorIntent.putExtra("stack", android.util.Log.getStackTraceString(th));
		fatalErrorIntent.putExtra("savePath", savePath);
		fatalErrorIntent.putExtra("storageAllow", storageAllow);
		try {
			ctx.startActivity(fatalErrorIntent);
		} catch (Throwable t) {
			android.util.Log.e("FatalError", "cannot show crash screen", t);
		}
	}
}
