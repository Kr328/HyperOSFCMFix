package com.github.kr328.simplefcmfix.compat;

import android.app.AppOpsManager;
import android.content.Context;
import android.util.Log;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

@SuppressWarnings({"JavaLangInvokeHandleSignature", "JavaReflectionMemberAccess"})
public final class AppOpsCompat {
    private static final String TAG = "AppOpsCompat";

    // public void setMode(int code, int uid, String packageName, @Mode int mode)
    private static final MethodHandle setMode;
    // public void startWatchingMode(int op, String packageName, int flags, final OnOpChangedListener callback)
    private static final MethodHandle startWatchingMode;
    // public int checkOp(int op, int uid, String packageName) {
    private static final MethodHandle checkOp;

    private static int OP_AUTO_START = 10008;

    static {
        try {
            setMode = MethodHandles.lookup().findVirtual(
                    AppOpsManager.class,
                    "setMode",
                    MethodType.methodType(void.class, int.class, int.class, String.class, int.class)
            );
            startWatchingMode = MethodHandles.lookup().findVirtual(
                    AppOpsManager.class,
                    "startWatchingMode",
                    MethodType.methodType(void.class, int.class, String.class, int.class, AppOpsManager.OnOpChangedListener.class)
            );
            checkOp = MethodHandles.lookup().findVirtual(
                    AppOpsManager.class,
                    "checkOp",
                    MethodType.methodType(int.class, int.class, int.class, String.class)
            );
        } catch (final Exception e) {
            Log.e(TAG, "AppOpsCompat.setMode|startWatchingMode|checkOp*", e);

            throw new RuntimeException(e);
        }

        try {
            OP_AUTO_START = AppOpsManager.class.getField("OP_AUTO_START").getInt(null);
        } catch (final Exception e) {
            Log.w(TAG, "AppOpsCompat.OP_AUTO_START*", e);
        }
    }

    public static void setMode(final AppOpsManager appOpsManager, final int code, final int uid, final String packageName, final int mode) throws Throwable {
        setMode.invoke(appOpsManager, code, uid, packageName, mode);
    }

    public static void startWatchingMode(final AppOpsManager appOpsManager, final int op, final String packageName, final int flags, final AppOpsManager.OnOpChangedListener callback) throws Throwable {
        startWatchingMode.invoke(appOpsManager, op, packageName, flags, callback);
    }

    public static void setMode(final Context context, final int code, final String packageName, final int mode) throws Throwable {
        AppOpsCompat.setMode(context.getSystemService(AppOpsManager.class), code, context.getPackageManager().getPackageUid(packageName, 0), packageName, mode);
    }

    public static void setAutoStartMode(final Context context, final String packageName, final int mode) throws Throwable {
        setMode(context, OP_AUTO_START, packageName, mode);
    }

    public static void startWatchingAutoStartMode(final Context context, final AppOpsManager.OnOpChangedListener callback) throws Throwable {
        startWatchingMode(context.getSystemService(AppOpsManager.class), OP_AUTO_START, null, 0, callback);
    }

    public static void stopWatchingAutoStartMode(final Context context, final AppOpsManager.OnOpChangedListener callback) {
        context.getSystemService(AppOpsManager.class).stopWatchingMode(callback);
    }

    public static boolean isAllowAutoStart(final Context context, final int uid, final String packageName) throws Throwable {
        return (int) checkOp.invoke(
                context.getSystemService(AppOpsManager.class),
                OP_AUTO_START,
                uid,
                packageName
        ) == AppOpsManager.MODE_ALLOWED;
    }
}
