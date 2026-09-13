package com.github.kr328.simplefcmfix;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;

public final class BootReceiver extends BroadcastReceiver {
    private static final long MAX_ALLOWED_BOOT_COMPLETED_UPTIME = 10 * 60 * 1000;

    private static final String NOTIFICATION_CHANNEL_ID = "service_start";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final int contentResource;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (SystemClock.elapsedRealtime() > MAX_ALLOWED_BOOT_COMPLETED_UPTIME) {
                return;
            }

            contentResource = R.string.service_start_notification_boot_content;
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            contentResource = R.string.service_start_notification_update_content;
        } else {
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            return;
        }

        final NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.service_start_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationManager.createNotificationChannel(channel);

        final Intent contentIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final CharSequence content = context.getText(contentResource);
        final Notification notification = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.color_launcher_foreground))
                .setContentTitle(context.getText(R.string.service_start_notification_title))
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();

        notificationManager.notify(NOTIFICATION_ID, notification);
    }
}
