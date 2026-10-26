package com.macmic.micserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

/**
 * REFERENCE builder for the foreground-service notification using design/res/layout/notification_mic.xml.
 * Copy into src/com/macmic/micserver/ and call from MicService.buildNotification().
 *
 *   MicNotificationUi.ensureChannel(this, CHANNEL);
 *   Notification n = MicNotificationUi.build(this, CHANNEL, MainUi.UiState.STREAMING,
 *           "192.168.1.40", "Wi-Fi · UDP", Status.sessionStartMs, openPi, stopPi);
 */
final class MicNotificationUi {
    private MicNotificationUi() {}

    /** IMPORTANCE_LOW: no sound/heads-up, still shown in shade + lock screen + status bar. */
    static void ensureChannel(Context c, String id) {
        NotificationChannel ch = new NotificationChannel(id, c.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.notif_channel_desc));
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /**
     * @param peerHost     "192.168.1.40" (empty while waiting)
     * @param transport    already-localised label, e.g. "Wi-Fi · UDP" / "USB · TCP（ADB）"
     * @param sessionStartWallMs Status.sessionStartMs (System.currentTimeMillis based) or 0
     */
    static Notification build(Context c, String channel, MainUi.UiState state, String peerHost, String transport,
                              long sessionStartWallMs, PendingIntent open, PendingIntent stop) {
        String title, text;
        int dot;
        boolean chrono = false;
        switch (state) {
            case STREAMING:
                title = c.getString(R.string.notif_title_streaming, peerHost);
                text = c.getString(R.string.notif_text_streaming, transport);
                dot = R.color.state_streaming_dot;
                chrono = sessionStartWallMs > 0;
                break;
            case CONNECTED: case HANDSHAKING:
                title = c.getString(R.string.notif_title_connected, peerHost);
                text = c.getString(R.string.notif_text_waiting, transport);
                dot = R.color.state_connected_dot;
                break;
            case ERROR:
                title = c.getString(R.string.notif_title_error);
                text = c.getString(R.string.banner_port_title);
                dot = R.color.state_error_dot;
                break;
            default:
                title = c.getString(R.string.notif_title_waiting);
                text = c.getString(R.string.notif_text_waiting, c.getString(R.string.transport_wifi));
                dot = R.color.state_waiting_dot;
        }

        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.notification_mic);
        rv.setTextViewText(R.id.notif_title, title);
        rv.setTextViewText(R.id.notif_text, text);
        rv.setInt(R.id.notif_state_dot, "setColorFilter", c.getColor(dot));
        if (chrono) {
            long base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sessionStartWallMs);
            rv.setChronometer(R.id.notif_chrono, base, null, true);
            rv.setViewVisibility(R.id.notif_chrono, View.VISIBLE);
        } else {
            rv.setViewVisibility(R.id.notif_chrono, View.GONE);
        }
        rv.setOnClickPendingIntent(R.id.notif_stop, stop);

        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setColor(c.getColor(R.color.primary))
                .setContentTitle(title)              // fallback + accessibility + Wear/Auto
                .setContentText(text)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
                .setCustomBigContentView(rv)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)   // full content on the lock screen
                // standard action too: shown in expanded state / Wear OS / when custom views are stripped
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_stop), c.getString(R.string.notif_action_stop), stop).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
