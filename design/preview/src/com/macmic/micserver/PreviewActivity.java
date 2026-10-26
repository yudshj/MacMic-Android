package com.macmic.micserver;

import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Design-only preview harness (package com.macmic.micserver.design, NOT the real app).
 * Shows activity_main with fake data; tap the big CTA to cycle
 * STOPPED(+permission banner) -> WAITING -> STREAMING -> ERROR(port busy) -> ...
 *   adb shell am start -n com.macmic.micserver.design/com.macmic.micserver.PreviewActivity --es state streaming
 * Also posts the custom foreground-style notification so the lock-screen look can be checked.
 */
public class PreviewActivity extends Activity implements MainUi.Actions {
    private static final String CH = "mic_preview";
    private final Handler h = new Handler(Looper.getMainLooper());
    private MainUi ui;
    private int step;
    private final long started = System.currentTimeMillis() - 964_000;
    private final Runnable tick = new Runnable() { @Override public void run() { render(); h.postDelayed(this, 500); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        MainUi.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        ui = new MainUi(this, this);
        String s = getIntent().getStringExtra("state");
        step = s == null ? 1 : Arrays.asList("stopped", "waiting", "streaming", "error").indexOf(s);
        if (step < 0) step = 1;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        MicNotificationUi.ensureChannel(this, CH);
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); ui.pause(); }

    private void render() {
        MainUi.Model m = new MainUi.Model();
        m.addresses = new ArrayList<>(Arrays.asList("192.168.1.23"));
        m.version = "1.0.0"; m.opusVersion = "1.5.2";
        m.events = new ArrayList<>(Arrays.asList(
                "12:09:14.996  STATE STREAMING 192.168.1.40:51234 - 0x67 start capture",
                "12:09:14.975  STATE CONNECTED 192.168.1.40:51234 - 0x65 OK",
                "12:08:41.533  #2 session end (192.168.1.40): EOF -> RST",
                "12:08:03.114  STATE LISTENING - listening TCP/UDP 8125, libopus 1.5.2",
                "12:08:03.102  locks: wake=true wifiHighPerf=true wifiLowLatency=true"));
        switch (step) {
            case 0: m.state = MainUi.UiState.STOPPED; m.banner = MainUi.Banner.MIC_PERMISSION; m.events.clear(); break;
            case 1: m.state = MainUi.UiState.WAITING; m.serviceRunning = m.wakeLock = m.wifiLock = true; break;
            case 2:
                m.state = MainUi.UiState.STREAMING; m.serviceRunning = m.wakeLock = m.wifiLock = true;
                m.peer = "192.168.1.40:51234"; m.transport = "UDP -> 192.168.1.40:52100";
                m.sessionStartMs = started; m.sessions = 3;
                long secs = (System.currentTimeMillis() - started) / 1000;
                m.packets = secs * 50; m.bytes = secs * 8200; break;
            default:
                m.state = MainUi.UiState.ERROR; m.portBusy = true; m.serviceRunning = m.wakeLock = m.wifiLock = true;
                m.detail = "cannot bind port 8125: Address already in use";
        }
        ui.bind(m);
    }

    private void next() {
        step = (step + 1) % 4;
        render();
        notifyPreview();
    }

    private void notifyPreview() {
        MainUi.UiState s = step == 2 ? MainUi.UiState.STREAMING : step == 3 ? MainUi.UiState.ERROR : MainUi.UiState.WAITING;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, PreviewActivity.class), PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(1, MicNotificationUi.build(this, CH, s,
                "192.168.1.40", getString(R.string.transport_wifi), started, open, open));
    }

    @Override public void onStartService() { next(); }
    @Override public void onStopService() { next(); }
    @Override public void onRetryService() { next(); }
    @Override public void onBannerAction(MainUi.Banner b) { next(); }
}
