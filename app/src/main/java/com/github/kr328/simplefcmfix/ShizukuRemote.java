package com.github.kr328.simplefcmfix;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

public final class ShizukuRemote extends IShizukuRemote.Stub {
    private static final long WATCHDOG_PERIOD = 600 * 1000;

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
    private final MilletObserver observer = new MilletObserver(() -> handler.post(() -> injectGMSIntoNoRestrictApps(HistoryRecord.Cause.EVENT)));
    private final History history = new History();
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

    private void injectGMSIntoNoRestrictApps(@NonNull final HistoryRecord.Cause cause) {
        Log.d("ShizukuRemote", "injectGMSIntoNoRestrictApps: " + cause);

        final List<String> original = MilletHelper.getMilletNoRestrictApps(context);
        if (original.contains("com.google.android.gms")) {
            return;
        }
        original.add("com.google.android.gms");
        MilletHelper.setMilletNoRestrictApps(context, original);

        try {
            Log.d("ShizukuRemote", "wakeUp");

            IPowerManager.Stub.asInterface(ServiceManager.getService("power"))
                    .wakeUp(SystemClock.uptimeMillis(), /* WAKE_REASON_APPLICATION */ 2, "GCMFix", "com.android.shell");
        } catch (final Throwable e) {
            Log.e("ShizukuRemote", "PowerManager", e);
        }

        synchronized (history) {
            history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.INJECT, cause));
        }
    }

    private void removeGMSFromNoRestrictApps(@NonNull final HistoryRecord.Cause cause) {
        Log.d("ShizukuRemote", "removeGMSFromNoRestrictApps: " + cause);

        final List<String> original = MilletHelper.getMilletNoRestrictApps(context);
        if (!original.remove("com.google.android.gms")) {
            return;
        }
        MilletHelper.setMilletNoRestrictApps(context, original);

        synchronized (history) {
            history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.REMOVE, cause));
        }
    }

    private void requestGMSReconnect(@NonNull final HistoryRecord.Cause cause) {
        Log.d("ShizukuRemote", "requestGMSReconnect: " + cause);

        try {
            if (FCMHelper.isFcmConnected(context)) {
                return;
            }

            FCMHelper.requestReconnect(context);

            synchronized (history) {
                history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.RECONNECT, cause));
            }
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "requestGMSReconnect", e);
        }
    }

    private void scheduledWatchdogTask() {
        injectGMSIntoNoRestrictApps(HistoryRecord.Cause.WATCHDOG);

        requestGMSReconnect(HistoryRecord.Cause.WATCHDOG);

        handler.postDelayed(this::scheduledWatchdogTask, WATCHDOG_PERIOD);
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
            injectGMSIntoNoRestrictApps(HistoryRecord.Cause.MANUAL);

            requestGMSReconnect(HistoryRecord.Cause.MANUAL);

            observer.start();

            handler.postDelayed(this::scheduledWatchdogTask, WATCHDOG_PERIOD);

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

            removeGMSFromNoRestrictApps(HistoryRecord.Cause.MANUAL);

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

    @Override
    public HistoryRecord[] getHistory() {
        synchronized (history) {
            return history.getRecords().toArray(new HistoryRecord[0]);
        }
    }
}
