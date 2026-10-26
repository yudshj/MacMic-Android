package com.macmic.micserver;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Process-wide, thread-safe status + event ring shown by the UI and logcat (tag MacMicServer). */
final class Status {
    static final String TAG = "MacMicServer";
    private static final int MAX_EVENTS = 60;
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    enum State { STOPPED, LISTENING, HANDSHAKING, CONNECTED, STREAMING, ERROR }

    static volatile State state = State.STOPPED;
    static volatile String peer = "";
    static volatile String transport = "";
    static volatile String detail = "";
    static volatile long packetsSent, bytesSent, packetsDropped, sessions, sessionStartMs;
    static volatile boolean wakeLockHeld, wifiLockHeld;

    static void set(State s, String peerText, String why) {
        state = s;
        peer = peerText == null ? "" : peerText;
        detail = why == null ? "" : why;
        event("STATE " + s + (peer.isEmpty() ? "" : " " + peer) + (detail.isEmpty() ? "" : " - " + detail));
    }

    static void event(String msg) {
        Log.i(TAG, msg);
        String line;
        synchronized (FMT) { line = FMT.format(new Date()) + "  " + msg; }
        synchronized (events) {
            events.addFirst(line);
            while (events.size() > MAX_EVENTS) events.removeLast();
        }
    }

    static List<String> recentEvents() {
        synchronized (events) { return new ArrayList<>(events); }
    }
}
