package com.github.kr328.simplefcmfix;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import com.github.kr328.simplefcmfix.compat.AppOpsCompat;
import com.github.kr328.simplefcmfix.compat.AurogonCompat;
import com.github.kr328.simplefcmfix.compat.MilletCompat;

public class Monitor {
    private static final String TAG = "Monitor";

    private static final long WATCHDOG_PERIOD = 600 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Callback callback;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            callback.onMilletOrAurogonChanged();
        }
    };
    private final AppOpsManager.OnOpChangedListener autoStartModeChangedListener = new AppOpsManager.OnOpChangedListener() {

        @Override
        public void onOpChanged(final String op, final String packageName) {
            callback.onAutoStartModeChanged(packageName);
        }
    };
    private final IUidObserver uidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidGone(final int uid, final boolean disabled) {
            callback.onAnyProcessChanged();
        }

        @Override
        public void onUidActive(final int uid) {
            callback.onAnyProcessChanged();
        }

        @Override
        public void onUidIdle(final int uid, final boolean disabled) {
        }

        @Override
        public void onUidStateChanged(final int uid, final int procState, final long procStateSeq, final int capability) {
        }

        @Override
        public void onUidProcAdjChanged(final int uid, final int adj) {
        }

        @Override
        public void onUidCachedChanged(final int uid, final boolean cached) {
        }
    };

    public Monitor(final Context context, final Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    private void scheduleWatchdogTask() {
        context.getSystemService(AlarmManager.class)
                .set(
                        AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + WATCHDOG_PERIOD,
                        "HyperOSFCMFix:watchdog",
                        watchdog,
                        handler
                );
    }

    public void start() {
        try {
            IActivityManager.Stub.asInterface(ServiceManager.getService("activity"))
                    .registerUidObserver(
                            uidObserver,
                            1 << 1 | 1 << 3 /* UID_OBSERVER_GONE | UID_OBSERVER_ACTIVE */,
                            0,
                            android.os.Process.myUid() == Process.SHELL_UID ? "com.android.shell" : "android"
                    );

            MilletCompat.observeMilletNoRestrictApps(context, observer);
            AurogonCompat.observeAurogonEnable(context, observer);
            AppOpsCompat.startWatchingAutoStartMode(context, autoStartModeChangedListener);

            scheduleWatchdogTask();
        } catch (final Throwable e) {
            Log.e(TAG, "start", e);

            stop();

            throw new SecurityException(e.getMessage(), e);
        }
    }

    public void stop() {
        try {
            context.getSystemService(AlarmManager.class).cancel(watchdog);
        } catch (final Exception e) {
            Log.w(TAG, "cancel watchdog", e);
        }

        try {
            context.getContentResolver().unregisterContentObserver(observer);
        } catch (final Exception e) {
            Log.w(TAG, "unregister content observer", e);
        }

        try {
            IActivityManager.Stub.asInterface(ServiceManager.getService("activity"))
                    .unregisterUidObserver(uidObserver);
        } catch (final Exception e) {
            Log.w(TAG, "unregister uid observer", e);
        }

        try {
            AppOpsCompat.stopWatchingAutoStartMode(context, autoStartModeChangedListener);
        } catch (final Exception e) {
            Log.w(TAG, "stop watching auto start mode", e);
        }
    }

    public interface Callback {
        void onMilletOrAurogonChanged();

        void onAnyProcessChanged();

        void onWatchdogToggle();

        void onAutoStartModeChanged(final String packageName);
    }

    private final AlarmManager.OnAlarmListener watchdog = new AlarmManager.OnAlarmListener() {
        @Override
        public void onAlarm() {
            callback.onWatchdogToggle();

            scheduleWatchdogTask();
        }
    };


}
