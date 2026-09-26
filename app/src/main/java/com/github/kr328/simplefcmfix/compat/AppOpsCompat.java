package com.github.kr328.simplefcmfix.compat;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.github.kr328.simplefcmfix.refine.Refine;

@Refine
public final class AppOpsCompat {
    private static final String TAG = "AppOpsCompat";

    private static int OP_AUTO_START = 10008;

    static {
        try {
            OP_AUTO_START = getOpAutoStart();
        } catch (final Exception e) {
            Log.w(TAG, "AppOpsManager.OP_AUTO_START", e);
        }
    }

    @Refine.GetStatic(value = AppOpsManager.class, name = "OP_AUTO_START")
    private static int getOpAutoStart() {
        throw new IllegalArgumentException("Stub!");
    }

    @Refine.InvokeVirtual
    public static int checkOp(final AppOpsManager appOps, final int op, final int uid, final String packageName) {
        throw new IllegalArgumentException("Stub!");
    }

    @Refine.InvokeVirtual
    public static void setMode(final AppOpsManager appOps, final int code, final int uid, final String packageName, final int mode) {
        throw new IllegalArgumentException("Stub!");
    }

    @Refine.InvokeVirtual
    public static void startWatchingMode(final AppOpsManager appOps, final int op, final String packageName, final int flags, final AppOpsManager.OnOpChangedListener callback) {
        throw new IllegalArgumentException("Stub!");
    }

    public static void setAutoStartMode(final Context context, final String packageName, final int mode) throws PackageManager.NameNotFoundException {
        setMode(context.getSystemService(AppOpsManager.class), OP_AUTO_START, context.getPackageManager().getPackageUid(packageName, 0), packageName, mode);
    }

    public static void startWatchingAutoStartMode(final Context context, final AppOpsManager.OnOpChangedListener callback) {
        startWatchingMode(context.getSystemService(AppOpsManager.class), OP_AUTO_START, null, 0, callback);
    }

    public static void stopWatchingAutoStartMode(final Context context, final AppOpsManager.OnOpChangedListener callback) {
        context.getSystemService(AppOpsManager.class).stopWatchingMode(callback);
    }

    public static boolean isAllowAutoStart(final Context context, final int uid, final String packageName) throws Throwable {
        return checkOp(
                context.getSystemService(AppOpsManager.class),
                OP_AUTO_START,
                uid,
                packageName
        ) == AppOpsManager.MODE_ALLOWED;
    }
}
