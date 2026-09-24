package com.github.kr328.simplefcmfix;

import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.github.kr328.simplefcmfix.compat.ContextCompat;

public class ShizukuContext extends ContextWrapper {
    private final Context effectiveContext;

    public ShizukuContext(final Context base) throws PackageManager.NameNotFoundException, ReflectiveOperationException {
        super(base);

        final String packageName = switch (android.os.Process.myUid()) {
            case android.os.Process.ROOT_UID -> "android";
            case android.os.Process.SHELL_UID -> "com.android.shell";
            default ->
                    throw new UnsupportedOperationException("Unknown uid: " + android.os.Process.myUid());
        };

        this.effectiveContext = ((Context) ActivityThread.currentActivityThread().getSystemContext()).createPackageContext(packageName, 0);

        ContextCompat.fixContextImplOpPackage(effectiveContext);
    }

    public Context getEffectiveContext() {
        return effectiveContext;
    }

    @Override
    public Object getSystemService(final String name) {
        return effectiveContext.getSystemService(name);
    }

    @Override
    public PackageManager getPackageManager() {
        return effectiveContext.getPackageManager();
    }

    @Override
    public String getSystemServiceName(final Class<?> serviceClass) {
        return effectiveContext.getSystemServiceName(serviceClass);
    }

    @Override
    public ContentResolver getContentResolver() {
        return effectiveContext.getContentResolver();
    }

    @NonNull
    @Override
    public String getOpPackageName() {
        return effectiveContext.getOpPackageName();
    }
}
