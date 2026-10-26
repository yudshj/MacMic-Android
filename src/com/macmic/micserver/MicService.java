package com.macmic.micserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Foreground (microphone) service owning the server, a PARTIAL_WAKE_LOCK and Wi-Fi locks so
 * accept()/streaming keep working with the screen off.
 */
public class MicService extends Service {
    static final String ACTION_STOP = "com.macmic.micserver.STOP";
    private static final String CHANNEL = "mic";
    private static final int NOTIF_ID = 1;
    static volatile boolean running;

    private MicServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiHighPerf, wifiLowLatency;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Status.State shownState;
    private String shownPeer;

    private final Runnable retryStart = this::startServer;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (Status.state != shownState || !Status.peer.equals(shownPeer)) updateNotification();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Mic server", NotificationManager.IMPORTANCE_LOW));
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTIF_ID, n);
            running = true;
            acquireLocks();
            startServer();
            handler.post(refresh);
        }
        return START_NOT_STICKY;
    }

    private void startServer() {
        if (!running || server != null) return;
        MicServer s = new MicServer();
        try {
            s.start();
            server = s;
        } catch (Exception e) {
            Status.set(Status.State.ERROR, "", "cannot bind port " + MicServer.PORT + ": " + e.getMessage()
                    + " (another WO Mic server running?) retry in 3 s");
            handler.postDelayed(retryStart, 3000);
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MacMicServer:mic");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wifiHighPerf = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MacMicServer:hiperf");
        wifiHighPerf.setReferenceCounted(false);
        wifiHighPerf.acquire();
        if (Build.VERSION.SDK_INT >= 29) {
            wifiLowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MacMicServer:lowlat");
            wifiLowLatency.setReferenceCounted(false);
            wifiLowLatency.acquire();
        }
        Status.wakeLockHeld = wakeLock.isHeld();
        Status.wifiLockHeld = wifiHighPerf.isHeld();
        Status.event("locks: wake=" + Status.wakeLockHeld + " wifiHighPerf=" + Status.wifiLockHeld
                + " wifiLowLatency=" + (wifiLowLatency != null && wifiLowLatency.isHeld()));
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MicService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        shownState = Status.state;
        shownPeer = Status.peer;
        String text = Status.state + (Status.peer.isEmpty() ? "" : " " + Status.peer) + "  · port " + MicServer.PORT;
        Notification.Builder nb = new Notification.Builder(this, CHANNEL);
        if (Build.VERSION.SDK_INT >= 31) nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return nb
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("MacMic Server")
                .setContentText(text)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        MicServer s = server;
        server = null;
        if (s != null) new Thread(s::stop, "mic-stop").start();
        else Status.set(Status.State.STOPPED, "", "service stopped");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiHighPerf != null && wifiHighPerf.isHeld()) wifiHighPerf.release();
        if (wifiLowLatency != null && wifiLowLatency.isHeld()) wifiLowLatency.release();
        Status.wakeLockHeld = Status.wifiLockHeld = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
