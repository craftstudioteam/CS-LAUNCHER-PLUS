package com.kdt.mcgui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class MineToast {
    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_ERROR = 1;
    public static final int TYPE_WARNING = 2;

    public static void show(Context context, String message, int type) {
        if (context == null || message == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        });
    }

    public static void show(Context context, int resId, int type) {
        if (context == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context.getApplicationContext(), resId, Toast.LENGTH_SHORT).show();
        });
    }
}
