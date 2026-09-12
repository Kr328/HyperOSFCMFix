package com.github.kr328.simplefcmfix;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

public final class ShizukuRemote extends IShizukuRemote.Stub {
    private static final long FORCE_APPLY_PERIOD = 3600 * 1000;

    static {
        try {
            Log.d("ShizukuRemote", "applyContentProviderCompatForShizuku");

            Compat.applyContentProviderCompatForShizuku();
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "ContentProviderCompat.applyContentProviderCompatForShizuku", e);

            System.exit(1);
        }
    }

    @NonNull
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MilletObserver observer = new MilletObserver(() -> handler.post(this::injectGMSIntoNoRestrictApps));
    private boolean started = false;

    public ShizukuRemote(@NonNull final Context context) {
        this.context = context;

        try {
            Compat.applyNewPackageNameToContextImpl(((Application) context.getApplicationContext()).getBaseContext(), "com.android.shell");
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "Compat.applyNewPackageNameToContextImpl", e);

            System.exit(1);
        }
    }

    private void injectGMSIntoNoRestrictApps() {
        Log.d("ShizukuRemote", "injectGMSIntoNoRestrictApps");

        final List<String> original = MilletHelper.getMilletNoRestrictApps(context);
        if (original.contains("com.google.android.gms")) {
            return;
        }
        original.add("com.google.android.gms");
        MilletHelper.setMilletNoRestrictApps(context, original);
    }

    private void removeGMSFromNoRestrictApps() {
        Log.d("ShizukuRemote", "removeGMSFromNoRestrictApps");

        final List<String> original = MilletHelper.getMilletNoRestrictApps(context);
        if (!original.remove("com.google.android.gms")) {
            return;
        }
        MilletHelper.setMilletNoRestrictApps(context, original);
    }

    private void scheduledPeriodTask() {
        injectGMSIntoNoRestrictApps();

        handler.postDelayed(this::scheduledPeriodTask, FORCE_APPLY_PERIOD);
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    @Override
    public void start() {
        if (started) {
            return;
        }

        try {
            injectGMSIntoNoRestrictApps();

            observer.start();

            handler.postDelayed(this::scheduledPeriodTask, FORCE_APPLY_PERIOD);

            started = true;
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "MilletObserver.start", e);

            throw new SecurityException(e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        try {
            handler.removeMessages(0);

            removeGMSFromNoRestrictApps();

            observer.stop();

            started = false;
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "MilletObserver.stop", e);

            throw new SecurityException(e.getMessage(), e);
        }
    }

    @Override
    public String[] getNoRestrictApps() {
        return MilletHelper.getMilletNoRestrictApps(context).toArray(new String[0]);
    }
}
