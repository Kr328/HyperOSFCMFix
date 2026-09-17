package com.github.kr328.simplefcmfix;

import android.app.AlarmManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

public final class ShizukuRemote extends IShizukuRemote.Stub {
    private static final long WATCHDOG_PERIOD = 600 * 1000;

    private static final int HANDLE_MILLET_CHANGED = 0x0001;
    private static final int HANDLE_WATCHDOG = 0x0002;
    private static final int HANDLE_UNFREEZE_GMS = 0x0003;

    static {
        try {
            Log.d("ShizukuRemote", "applyContentProviderCompatForShizuku");

            Compat.applyActivityManagerCompatForShizuku();
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "ContentProviderCompat.applyContentProviderCompatForShizuku", e);

            System.exit(1);
        }
    }

    @NonNull
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull final Message msg) {
            switch (msg.what) {
                case HANDLE_MILLET_CHANGED ->
                        injectGMSIntoNoRestrictApps(HistoryRecord.Cause.EVENT);
                case HANDLE_WATCHDOG -> {
                    injectGMSIntoNoRestrictApps(HistoryRecord.Cause.WATCHDOG);

                    scheduleWatchdogTask();
                }
                case HANDLE_UNFREEZE_GMS -> {
                    FCMHelper.unfreeze(context);
                    FCMHelper.reconnect(context);
                }
            }

            super.handleMessage(msg);
        }
    };
    private final History history = new History();
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            handler.removeMessages(HANDLE_MILLET_CHANGED);
            handler.sendEmptyMessageDelayed(HANDLE_MILLET_CHANGED, 1000);
        }
    };
    private final AlarmManager.OnAlarmListener watchdog = () -> {
        handler.removeMessages(HANDLE_WATCHDOG);
        handler.sendEmptyMessageDelayed(HANDLE_WATCHDOG, 1000);
    };
    private boolean started = false;

    public ShizukuRemote(@NonNull final Context context) {
        try {
            final ShizukuContext shizukuContext = new ShizukuContext(context);

            this.context = shizukuContext;

            Log.d("ShizukuRemote", "Context[packageName=" + shizukuContext.getEffectiveContext().getPackageName() + ",opPackageName=" + shizukuContext.getEffectiveContext().getOpPackageName() + "]");
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "new ShizukuContext()", e);

            throw new Error(e);
        }

        try {
            Compat.applyContentServiceCompatForShizuku(this.context);
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "applyContentServiceCompatForShizuku", e);

            throw new Error(e);
        }
    }

    private void injectGMSIntoNoRestrictApps(@NonNull final HistoryRecord.Cause cause) {
        Log.d("ShizukuRemote", "injectGMSIntoNoRestrictApps: " + cause);

        final List<String> original = MilletHelper.getMilletNoRestrictApps(context);
        if (!original.contains("com.google.android.gms")) {
            original.add("com.google.android.gms");
            MilletHelper.setMilletNoRestrictApps(context, original);

            synchronized (history) {
                history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.INJECT, cause));
            }
        }

        handler.removeMessages(HANDLE_UNFREEZE_GMS);
        handler.sendEmptyMessageDelayed(HANDLE_UNFREEZE_GMS, 2000);
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

    private void scheduleWatchdogTask() {
        context.getSystemService(AlarmManager.class)
                .set(
                        AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + WATCHDOG_PERIOD,
                        "SimpleFCMFix:watchdog",
                        watchdog,
                        handler
                );
    }

    @Override
    public void destroy() {
        stop();

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
            MilletHelper.observeMilletNoRestrictApps(context, observer);

            injectGMSIntoNoRestrictApps(HistoryRecord.Cause.MANUAL);

            scheduleWatchdogTask();

            started = true;
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "start", e);

            throw new SecurityException(e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        try {
            context.getSystemService(AlarmManager.class).cancel(watchdog);

            context.getContentResolver().unregisterContentObserver(observer);

            removeGMSFromNoRestrictApps(HistoryRecord.Cause.MANUAL);

            started = false;
        } catch (final Exception e) {
            Log.e("ShizukuRemote", "stop", e);

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
