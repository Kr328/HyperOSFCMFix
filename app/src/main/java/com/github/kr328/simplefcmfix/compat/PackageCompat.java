package com.github.kr328.simplefcmfix.compat;

import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.ServiceManager;

public class PackageCompat {
    private static final IPackageManager packageManager = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));

    public static void unstop(final Context context, final String packageName) throws Throwable {
        final int userId = UserCompat.getUserId(context.getApplicationInfo().uid);

        packageManager.setPackageStoppedState(packageName, false, userId);
    }
}
