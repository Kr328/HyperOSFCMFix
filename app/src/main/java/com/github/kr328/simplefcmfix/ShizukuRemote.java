package com.github.kr328.simplefcmfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.kr328.simplefcmfix.compat.ActivityCompat;
import com.github.kr328.simplefcmfix.compat.AppOpsCompat;
import com.github.kr328.simplefcmfix.compat.CompatHelper;
import com.github.kr328.simplefcmfix.compat.ContentCompat;
import com.github.kr328.simplefcmfix.compat.FCMCompat;
import com.github.kr328.simplefcmfix.compat.MilletCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ShizukuRemote extends IShizukuRemote.Stub implements Monitor.Callback {
    private static final String TAG = "ShizukuRemote";

    private static final int HANDLE_MILLET_OR_AUROGON_CHANGED = 0x0001;
    private static final int HANDLE_PROCESS_CHANGED = 0x0002;
    private static final int HANDLE_WATCHDOG = 0x0003;
    private static final int HANDLE_AUTO_START_MODE_CHANGED = 0x0004;

    static {
        try {
            Log.d(TAG, "applyCompat");

            ActivityCompat.installDelegate();
            ContentCompat.installDelegate();
        } catch (final Exception e) {
            Log.e(TAG, "ContentProviderCompat.apply*", e);

            System.exit(1);
        }
    }

    @NonNull
    private final Applier applier;
    @NonNull
    private final Monitor monitor;
    @NonNull
    private final Context context;
    @NonNull
    private final History history = new History();
    @NonNull
    private Set<String> interestApps = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull final Message msg) {
            switch (msg.what) {
                case HANDLE_MILLET_OR_AUROGON_CHANGED -> {
                    Log.d(TAG, "HANDLE_MILLET_OR_AUROGON_CHANGED");

                    if (applier.apply()) {
                        synchronized (history) {
                            history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.APPLY, HistoryRecord.Cause.EVENT));
                        }
                    }
                }
                case HANDLE_PROCESS_CHANGED -> {
                    Log.d(TAG, "HANDLE_PROCESS_CHANGED");

                    final Set<String> newInterestApps = FCMCompat.findAllFCMPackages(context);
                    if (!newInterestApps.equals(interestApps)) {
                        Log.d(TAG, "newInterestApps=" + newInterestApps);

                        interestApps = newInterestApps;
                        if (applier.apply()) {
                            synchronized (history) {
                                history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.APPLY, HistoryRecord.Cause.EVENT));
                            }
                        }
                    }
                }
                case HANDLE_WATCHDOG -> {
                    Log.d(TAG, "HANDLE_WATCHDOG");

                    interestApps = FCMCompat.findAllFCMPackages(context);

                    if (applier.apply()) {
                        synchronized (history) {
                            history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.APPLY, HistoryRecord.Cause.WATCHDOG));
                        }
                    }
                }
                case HANDLE_AUTO_START_MODE_CHANGED -> {
                    Log.d(TAG, "HANDLE_AUTO_START_MODE_CHANGED");

                    applier.applyAppOps();

                    synchronized (history) {
                        history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.APPLY, HistoryRecord.Cause.EVENT));
                    }
                }
                default -> super.handleMessage(msg);
            }
        }
    };

    private boolean started = false;

    public ShizukuRemote(@NonNull final Context context) {
        CompatHelper.attachContext(context);

        try {
            final ShizukuContext shizukuContext = new ShizukuContext(context);

            this.context = shizukuContext;

            Log.d(TAG, "Context[packageName=" + shizukuContext.getEffectiveContext().getPackageName() + ",opPackageName=" + shizukuContext.getEffectiveContext().getOpPackageName() + "]");
        } catch (final Exception e) {
            Log.e(TAG, "new ShizukuContext()", e);

            throw new Error(e);
        }

        Log.d(TAG, "ShizukuRemote: setup");

        applier = new Applier(this.context, ConfigProvider.getApplierConfig(this.context));
        monitor = new Monitor(this.context, this);

        ConfigProvider.observeApplierConfig(this.context, new ContentObserver(handler) {
            @Override
            public void onChange(final boolean selfChange) {
                Log.d(TAG, "OnConfigChanged");

                final ApplierConfig cfg = ConfigProvider.getApplierConfig(ShizukuRemote.this.context);
                applier.setConfig(cfg);
                if (started) {
                    applier.apply();
                }
            }
        });
    }

    @Override
    public void destroy() {
        applier.restore();
        monitor.stop();

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
            interestApps = FCMCompat.findAllFCMPackages(context);

            applier.apply();

            monitor.start();

            started = true;

            synchronized (history) {
                history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.APPLY, HistoryRecord.Cause.MANUAL));
            }
        } catch (final Exception e) {
            Log.e(TAG, "start", e);

            applier.restore();

            throw new SecurityException(e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        if (!started) {
            return;
        }

        try {
            monitor.stop();

            handler.removeMessages(HANDLE_MILLET_OR_AUROGON_CHANGED);
            handler.removeMessages(HANDLE_PROCESS_CHANGED);
            handler.removeMessages(HANDLE_WATCHDOG);
            handler.removeMessages(HANDLE_AUTO_START_MODE_CHANGED);

            applier.restore();

            started = false;

            synchronized (history) {
                history.addRecord(new HistoryRecord(System.currentTimeMillis(), HistoryRecord.Action.RESTORE, HistoryRecord.Cause.MANUAL));
            }
        } catch (final Exception e) {
            Log.e(TAG, "stop", e);

            throw new SecurityException(e.getMessage(), e);
        }
    }

    @Override
    public String[] getNoRestrictApps() {
        return MilletCompat.getMilletNoRestrictApps(context).toArray(new String[0]);
    }

    @Override
    public HistoryRecord[] getHistory() {
        synchronized (history) {
            return history.getRecords().toArray(new HistoryRecord[0]);
        }
    }

    @Override
    public AppStatus[] listApps() {
        final List<PackageInfo> packages = context.getPackageManager().getInstalledPackages(0);
        final List<String> noRestrictApps = MilletCompat.getMilletNoRestrictApps(context);
        final Set<String> fcmApps = FCMCompat.findAllFCMPackages(context);

        return packages.stream()
                .filter(pkg -> pkg.applicationInfo != null)
                .map(pkg -> {
                    boolean isAllowAutoStart = false;
                    try {
                        isAllowAutoStart = AppOpsCompat.isAllowAutoStart(context, pkg.applicationInfo.uid, pkg.packageName);
                    } catch (final Throwable e) {
                        Log.e(TAG, "isAllowAutoStart", e);
                    }

                    return new AppStatus(
                            pkg.packageName,
                            (pkg.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                            fcmApps.contains(pkg.packageName),
                            FCMCompat.checkInstallFromPlayStore(context, pkg.packageName),
                            noRestrictApps.contains(pkg.packageName),
                            isAllowAutoStart
                    );
                }).toArray(AppStatus[]::new);
    }

    @Override
    public void onMilletOrAurogonChanged() {
        handler.removeMessages(HANDLE_MILLET_OR_AUROGON_CHANGED);
        handler.sendEmptyMessageDelayed(HANDLE_MILLET_OR_AUROGON_CHANGED, 1000);
    }

    @Override
    public void onAnyProcessChanged() {
        handler.removeMessages(HANDLE_PROCESS_CHANGED);
        handler.sendEmptyMessageDelayed(HANDLE_PROCESS_CHANGED, 5 * 1000);
    }

    @Override
    public void onWatchdogToggle() {
        handler.removeMessages(HANDLE_WATCHDOG);
        handler.sendEmptyMessageDelayed(HANDLE_WATCHDOG, 1000);
    }

    @Override
    public void onAutoStartModeChanged(final String packageName) {
        if (!interestApps.contains(packageName)) {
            return;
        }

        handler.removeMessages(HANDLE_AUTO_START_MODE_CHANGED);
        handler.sendEmptyMessageDelayed(HANDLE_AUTO_START_MODE_CHANGED, 1000);
    }
}
