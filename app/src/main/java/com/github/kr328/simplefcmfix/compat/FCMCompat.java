package com.github.kr328.simplefcmfix.compat;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class FCMCompat {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GCM_RECONNECT_ACTION = "com.google.android.intent.action.GCM_RECONNECT";
    private static final String GCM_BROADCAST_ACTION = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GCM_EXPORTED_CONTENT_URI = "content://com.google.android.gms.chimera";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    public static void reconnect(final Context context) {
        context.sendBroadcast(new Intent(GCM_RECONNECT_ACTION).setPackage(GMS_PACKAGE));
    }

    public static void unfreeze(final Context context) {
        try (final Cursor cursor = context.getContentResolver().query(Uri.parse(GCM_EXPORTED_CONTENT_URI), null, null, null, null)) {
            Log.d("FCMHelper", "unfreeze: " + cursor);
        } catch (final Exception e) {
            Log.e("FCMHelper", "unfreeze", e);
        }
    }


    public static Set<String> findAllFCMPackages(final Context context) {
        return context.getPackageManager().queryBroadcastReceivers(new Intent(GCM_BROADCAST_ACTION), 0)
                .stream()
                .map(resolveInfo -> resolveInfo.activityInfo.packageName)
                .filter(pkg -> !"android".equals(pkg))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("deprecation")
    public static boolean checkInstallFromPlayStore(final Context context, final String packageName) {
        return PLAY_STORE_PACKAGE.equals(context.getPackageManager().getInstallerPackageName(packageName));
    }

    public static void replaceGMSInMilletList(final List<String> packages) {
        if (!packages.contains(GMS_PACKAGE)) {
            packages.add(GMS_PACKAGE);
        }
    }

    public static void replacePackagesInAurogonCtrl(final Set<String> packages, final Set<Pair<String, String>> excludes) {
        excludes.removeIf(pair -> pair.second.equals(FCMCompat.GCM_BROADCAST_ACTION));
        for (final String packageName : packages) {
            excludes.add(new Pair<>(packageName, FCMCompat.GCM_BROADCAST_ACTION));
        }
    }

    public static void removeGMSInMilletList(final List<String> packages) {
        packages.remove(GMS_PACKAGE);
    }

    public static void removePackagesInAurogonCtrl(final Set<Pair<String, String>> excludes) {
        excludes.removeIf(pair -> FCMCompat.GCM_BROADCAST_ACTION.equals(pair.second));
    }
}
