package com.github.kr328.simplefcmfix;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

public class FCMHelper {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GCM_RECONNECT_ACTION = "com.google.android.intent.action.GCM_RECONNECT";
    private static final String GCM_EXPORTED_CONTENT_URI = "content://com.google.android.gms.chimera";

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
}
