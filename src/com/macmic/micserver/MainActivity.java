package com.macmic.micserver;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Status UI (Material 3 look on platform widgets: res/layout/activity_main.xml + MainUi binder).
 * adb: am start -n com.macmic.micserver/.MainActivity --ez autostart true
 *      am start -n com.macmic.micserver/.MainActivity --ez stop true
 */
public class MainActivity extends Activity implements MainUi.Actions {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MainUi ui;
    private boolean pendingStart, askedMic, hasWifiAddress;
    private final Runnable tick = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 500); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainUi.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        ui = new MainUi(this, this);
        askedMic = getPreferences(MODE_PRIVATE).getBoolean("askedMic", false);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("stop", false)) stopServer();
        else if (i.getBooleanExtra("autostart", false)) startServer();
    }

    private void stopServer() { stopService(new Intent(this, MicService.class)); }

    private void startServer() {
        boolean needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
        boolean needNotif = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (needMic || needNotif) {
            pendingStart = true;
            if (needMic) {
                askedMic = true;
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedMic", true).apply();
            }
            requestPermissions(needMic && needNotif
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : needMic ? new String[]{Manifest.permission.RECORD_AUDIO}
                    : new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        startForegroundService(new Intent(this, MicService.class));
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (!pendingStart) return;
        pendingStart = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startForegroundService(new Intent(this, MicService.class));
        else Status.event("RECORD_AUDIO denied; cannot start");
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); ui.pause(); }

    // ------------------------------------------------------------------ MainUi.Actions
    @Override public void onStartService() { startServer(); }
    @Override public void onStopService() { stopServer(); }
    @Override public void onRetryService() {
        stopServer();
        handler.postDelayed(this::startServer, 400);   // let MicService.onDestroy release the socket first
    }
    @Override public void onBannerAction(MainUi.Banner b) {
        switch (b) {
            case MIC_PERMISSION: startServer(); break;
            case MIC_PERMISSION_PERMANENT:
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
                break;
            case NOTIFICATIONS_OFF:
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                break;
            default: break;
        }
    }

    // ------------------------------------------------------------------ Status -> MainUi.Model
    private void render() {
        MainUi.Model m = new MainUi.Model();
        switch (Status.state) {
            case LISTENING: m.state = MainUi.UiState.WAITING; break;
            case HANDSHAKING: m.state = MainUi.UiState.HANDSHAKING; break;
            case CONNECTED: m.state = MainUi.UiState.CONNECTED; break;
            case STREAMING: m.state = MainUi.UiState.STREAMING; break;
            case ERROR: m.state = MainUi.UiState.ERROR; break;
            default: m.state = MainUi.UiState.STOPPED;
        }
        m.serviceRunning = MicService.running;
        m.detail = Status.detail;
        m.portBusy = m.state == MainUi.UiState.ERROR && Status.detail.contains("cannot bind port");
        m.peer = Status.peer;
        m.transport = Status.transport;
        m.packets = Status.packetsSent;
        m.bytes = Status.bytesSent;
        m.dropped = Status.packetsDropped;
        m.sessions = Status.sessions;
        m.sessionStartMs = Status.sessionStartMs;
        m.wakeLock = Status.wakeLockHeld;
        m.wifiLock = Status.wifiLockHeld;
        m.events = Status.recentEvents();
        m.addresses = addresses();
        try { m.version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        m.opusVersion = "1.5.2";

        boolean micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!micGranted) {
            m.banner = askedMic && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    ? MainUi.Banner.MIC_PERMISSION_PERMANENT : MainUi.Banner.MIC_PERMISSION;
        } else if (m.serviceRunning && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            m.banner = MainUi.Banner.NOTIFICATIONS_OFF;
        } else if (m.serviceRunning && m.state == MainUi.UiState.WAITING && !hasWifiAddress) {
            m.banner = MainUi.Banner.NO_WIFI;
        }
        ui.bind(m);
    }

    /** IPv4 addresses of up, non-loopback interfaces; Wi-Fi (wlan*) first. */
    private List<String> addresses() {
        List<String> wifi = new ArrayList<>(), other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                boolean isWifi = ni.getName().startsWith("wlan");
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address) (isWifi ? wifi : other).add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        hasWifiAddress = !wifi.isEmpty();
        wifi.addAll(other);
        return wifi;
    }
}
