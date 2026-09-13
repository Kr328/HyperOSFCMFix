package com.github.kr328.simplefcmfix;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FCMHelper {
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GCM_RECONNECT_ACTION = "com.google.android.intent.action.GCM_RECONNECT";
    private static final String TCP_ESTABLISHED = "01";
    private static final int FCM_REMOTE_PORT_MIN = 5228;
    private static final int FCM_REMOTE_PORT_MAX = 5230;
    private static final Path[] PROC_NET_FILES = {
            Paths.get("/proc/net/tcp"),
            Paths.get("/proc/net/tcp6"),
    };

    public static boolean isFcmConnected(final Context context) throws PackageManager.NameNotFoundException {
        final int uid = getGmsUid(context);

        for (final Path path : PROC_NET_FILES) {
            try {
                if (hasEstablishedFcmSocket(path, uid)) {
                    return true;
                }
            } catch (final IOException e) {
                Log.w("FcmHelper", "Failed to read proc file: " + path, e);

                // An unavailable proc file is treated the same as no matching connection.
            }
        }

        return false;
    }

    public static void requestReconnect(final Context context) throws PackageManager.NameNotFoundException {
        getGmsUid(context);

        context.sendBroadcast(new Intent(GCM_RECONNECT_ACTION).setPackage(GMS_PACKAGE));
    }

    private static int getGmsUid(final Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageUid(
                GMS_PACKAGE,
                PackageManager.PackageInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY)
        );
    }

    private static boolean hasEstablishedFcmSocket(final Path path, final int uid) throws IOException {
        try (final BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (matchesEstablishedFcmSocket(line, uid)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean matchesEstablishedFcmSocket(final String line, final int uid) {
        final String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        final String[] fields = trimmed.split("\\s+");
        if (fields.length <= 7 || !TCP_ESTABLISHED.equals(fields[3])) {
            return false;
        }

        final int separator = fields[2].lastIndexOf(':');
        if (separator < 0 || separator == fields[2].length() - 1) {
            return false;
        }

        try {
            final int remotePort = Integer.parseInt(fields[2].substring(separator + 1), 16);
            final int socketUid = Integer.parseInt(fields[7]);

            return socketUid == uid
                    && remotePort >= FCM_REMOTE_PORT_MIN
                    && remotePort <= FCM_REMOTE_PORT_MAX;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }
}
