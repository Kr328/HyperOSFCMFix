package com.github.kr328.simplefcmfix;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;

import com.github.kr328.simplefcmfix.compat.AppOpsCompat;
import com.github.kr328.simplefcmfix.compat.AurogonCompat;
import com.github.kr328.simplefcmfix.compat.FCMCompat;
import com.github.kr328.simplefcmfix.compat.MilletCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Applier {
    private static final String TAG = "Applier";

    private final Context context;
    private ApplierConfig config;

    public Applier(final Context context, final ApplierConfig config) {
        this.context = context;
        this.config = config;

        Log.d(TAG, "Applier* config = " + config);
    }

    public void setConfig(final ApplierConfig config) {
        this.config = config;

        Log.d(TAG, "setConfig* config = " + config);
    }

    private void setAllowAutoStartForPackagesFromPlayStore(final Iterable<String> apps) {
        final int userId = context.getApplicationInfo().uid / 100000 /* UserHandle.PER_USER_RANGE */;

        for (final String packageName : apps) {
            if (!FCMCompat.checkInstallFromGooglePlayStore(context, packageName)) {
                continue;
            }

            try {
                AppOpsCompat.setAutoStartMode(context, packageName, AppOpsManager.MODE_ALLOWED);

                IPackageManager.Stub.asInterface(ServiceManager.getService("package"))
                        .setPackageStoppedState(packageName, false, userId);

                Log.d(TAG, "setAutoStartMode* allowed: " + packageName);
            } catch (final Throwable e) {
                Log.w(TAG, "setAutoStartMode*", e);
            }
        }
    }

    public boolean apply() {
        boolean changed = false;

        final Set<String> apps = FCMCompat.findAllFCMPackages(context);

        final AurogonCompat.AurogonEnable aurogonConfig = AurogonCompat.getAurogonEnable(context);
        final List<String> noRestrictApps = MilletCompat.getMilletNoRestrictApps(context);

        final LinkedHashSet<Pair<String, String>> newBroadcastExcludes = new LinkedHashSet<>(aurogonConfig.broadcastCtrl() != null ? aurogonConfig.broadcastCtrl().excludes() : List.of());
        FCMCompat.replacePackagesInAurogonCtrl(apps, newBroadcastExcludes);

        final List<String> newNoRestrictApps = new ArrayList<>(noRestrictApps);
        FCMCompat.replaceGMSInMilletList(newNoRestrictApps);

        final AurogonCompat.AurogonEnable newAurogonConfig = new AurogonCompat.AurogonEnable(
                newBroadcastExcludes.isEmpty() ? null : new AurogonCompat.BroadcastCtrl(true, newBroadcastExcludes),
                aurogonConfig.otherFields()
        );

        if (!aurogonConfig.equals(newAurogonConfig)) {
            AurogonCompat.setAurogonEnable(context, newAurogonConfig);
            changed = true;
        }

        if (!noRestrictApps.equals(newNoRestrictApps)) {
            MilletCompat.setMilletNoRestrictApps(context, newNoRestrictApps);
            changed = true;
        }

        if (config.autoAllowFCMWakeForPlayStoreApps()) {
            setAllowAutoStartForPackagesFromPlayStore(apps);
        }

        FCMCompat.reconnect(context);
        FCMCompat.unfreeze(context);

        return changed;
    }

    public void applyAppOps() {
        if (config.autoAllowFCMWakeForPlayStoreApps()) {
            final Set<String> apps = FCMCompat.findAllFCMPackages(context);

            setAllowAutoStartForPackagesFromPlayStore(apps);
        }
    }

    public void restore() {
        final AurogonCompat.AurogonEnable aurogonConfig = AurogonCompat.getAurogonEnable(context);
        if (aurogonConfig.broadcastCtrl() != null) {
            FCMCompat.removePackagesInAurogonCtrl(aurogonConfig.broadcastCtrl().excludes());
        }

        if (aurogonConfig.broadcastCtrl() != null && aurogonConfig.broadcastCtrl().enabled() && aurogonConfig.broadcastCtrl().excludes().isEmpty()) {
            AurogonCompat.setAurogonEnable(context, new AurogonCompat.AurogonEnable(null, aurogonConfig.otherFields()));
        } else {
            AurogonCompat.setAurogonEnable(context, aurogonConfig);
        }

        final List<String> noRestrictApps = MilletCompat.getMilletNoRestrictApps(context);
        FCMCompat.removeGMSInMilletList(noRestrictApps);

        MilletCompat.setMilletNoRestrictApps(context, noRestrictApps);
    }
}
