package com.macmic.micserver;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WO Mic v4 compatible server (the protocol macmic_protocol.py speaks).
 *
 * Concurrency model:
 *  - acceptor thread: only accept()s and dispatches; never blocks on a client.
 *  - at most one live Session (control TCP) + optionally one "pending takeover" Session.
 *  - per streaming session: one capture/encode thread (UDP sent inline, TCP via bounded queue
 *    + writer thread, dropping the oldest frame when full).
 *  - udp thread: owns UDP 8125 (send source port + drains the client's 00 04 00 00 trigger).
 *
 * Every failure path (timeout, EOF, bad frame, write error) ends in Session.terminate(), which
 * stops capture, RSTs sockets (SO_LINGER 0) and returns the server to LISTENING.
 */
final class MicServer {
    static final int PORT = 8125;
    static final int HANDSHAKE_TIMEOUT_MS = 5000;   // complete 0x65 request after accept
    static final int IDLE_TIMEOUT_MS = 8000;        // any control frame (0x69 every 2 s)
    static final int MEDIA_ATTACH_WINDOW_MS = 4000; // USB: 2nd TCP to 8125 right after 0x67
    static final int SAMPLE_RATE = 48000, FRAME = 960, BITRATE = 64000;
    static final int MEDIA_QUEUE_FRAMES = 25;       // 500 ms of audio
    static final int MAX_CONTROL_PAYLOAD = 4096;
    static final int MEDIA_PROBE_MS = 300;          // bytes on a "media" socket => it is a control client

    static final int CMD_CHECK_VERSION = 0x65, CMD_SET_CODEC = 0x66, CMD_START = 0x67,
            CMD_STOP = 0x68, CMD_HEARTBEAT = 0x69;

    private volatile boolean running;
    private ServerSocket server;
    private DatagramSocket udp;
    private Thread acceptThread, udpThread;
    private Session current, pending; // guarded by this

    synchronized void start() throws IOException {
        if (running) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        DatagramSocket ds = null;
        try {
            ss.bind(new InetSocketAddress(PORT), 4); // "::" dual-stack on Android
            ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            closeQuietly(ss);
            if (ds != null) ds.close();
            throw e;
        }
        server = ss;
        udp = ds;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "mic-accept");
        udpThread = new Thread(this::udpLoop, "mic-udp");
        acceptThread.start();
        udpThread.start();
        Status.set(Status.State.LISTENING, "", "listening TCP/UDP " + PORT + ", libopus " + Opus.nativeVersion());
    }

    void stop() {
        Session c, p;
        synchronized (this) {
            if (!running) return;
            running = false;
            c = current; p = pending;
            current = pending = null;
        }
        if (c != null) c.terminate("server stopping", false);
        if (p != null) p.terminate("server stopping", false);
        closeQuietly(server);
        if (udp != null) udp.close();
        join(acceptThread);
        join(udpThread);
        Status.set(Status.State.STOPPED, "", "service stopped");
    }

    // ---------------------------------------------------------------- accept / dispatch
    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (running) Status.event("accept error: " + e);
                continue;
            }
            try {
                dispatch(s);
            } catch (Throwable t) {
                Status.event("dispatch error: " + t);
                abort(s);
            }
        }
    }

    private void dispatch(Socket s) throws IOException {
        String who = addr(s);
        InetAddress ip = s.getInetAddress();
        Session start = null;
        synchronized (this) {
            Session cur = current;
            if (cur == null || cur.dead) {
                current = start = new Session(s, false, -1);
                Status.event("accept " + who + " -> new session #" + start.id);
            } else if (cur.acceptsMedia(ip)) {
                cur.attachMedia(s);
                Status.event("accept " + who + " -> TCP media for session #" + cur.id);
                return;
            } else if ((ip.equals(cur.peerIp) || !cur.handshakeDone) && (pending == null || pending.dead)) {
                // Same client reconnecting while its old session has not yet timed out, or the
                // current one is still a half-open/unhandshaken probe: let the newcomer take
                // over IF it completes 0x65. Never makes the acceptor wait.
                pending = start = new Session(s, true, -1);
                Status.event("accept " + who + " while session #" + cur.id
                        + " busy -> pending takeover #" + start.id);
            } else {
                Status.event("accept " + who + " REJECTED (busy with " + cur.peerText + ") -> RST");
                abort(s);
                return;
            }
        }
        start.thread.start();
    }

    /**
     * A socket we took for TCP media sent bytes within MEDIA_PROBE_MS (nothing was written to it
     * yet): it is really the same client reconnecting. Re-run it as a pending takeover session.
     */
    private void reclassifyAsControl(Session owner, Socket s, int firstByte) {
        Session start = null;
        synchronized (this) {
            if (running && (pending == null || pending.dead)) pending = start = new Session(s, true, firstByte);
        }
        if (start == null) { Status.event("reclassify " + addr(s) + " failed (busy) -> RST"); abort(s); return; }
        Status.event(addr(s) + " sent data on media slot of #" + owner.id + " -> pending takeover #" + start.id);
        start.thread.start();
    }

    /** Pending session finished a valid 0x65: it becomes the current session. */
    private void promote(Session s) {
        Session old;
        synchronized (this) {
            if (pending != s || !running) { s.terminate("takeover no longer valid", false); return; }
            pending = null;
            old = current;
            current = s;
        }
        if (old != null) old.terminate("taken over by session #" + s.id + " (" + s.peerText + ")", false);
    }

    private void sessionEnded(Session s) {
        boolean wasCurrent;
        synchronized (this) {
            wasCurrent = current == s;
            if (wasCurrent) current = null;
            if (pending == s) pending = null;
        }
        if (wasCurrent && running) Status.set(Status.State.LISTENING, "", "waiting for connection (等待连接)");
    }

    private void udpLoop() {
        byte[] buf = new byte[1500];
        long triggers = 0;
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                udp.receive(p);
                if (triggers++ % 50 == 0)
                    Status.event("UDP from " + p.getAddress().getHostAddress() + ":" + p.getPort()
                            + " len=" + p.getLength() + " (trigger, ignored; total " + triggers + ")");
            } catch (IOException e) {
                if (running) Status.event("udp receive error: " + e);
            }
        }
    }

    // ---------------------------------------------------------------- session
    private static int nextId = 1;

    final class Session implements Runnable {
        final int id;
        final Socket control;
        final InetAddress peerIp;
        final String peerText;
        final boolean takeover;
        final Thread thread;
        volatile boolean dead, handshakeDone, started;
        volatile long startedAt;
        volatile int clientMediaPort = -1;
        volatile Socket media;
        private Thread captureThread, writerThread;
        private final ArrayBlockingQueue<byte[]> mediaQueue = new ArrayBlockingQueue<>(MEDIA_QUEUE_FRAMES);
        private long frameDeadline;
        private final int prefixByte;

        Session(Socket s, boolean takeover, int prefixByte) {
            this.prefixByte = prefixByte;
            synchronized (MicServer.class) { id = nextId++; }
            control = s;
            peerIp = s.getInetAddress();
            peerText = addr(s);
            this.takeover = takeover;
            thread = new Thread(this, "mic-session-" + id);
        }

        synchronized boolean acceptsMedia(InetAddress ip) {
            return !dead && started && media == null && ip.equals(peerIp)
                    && System.currentTimeMillis() - startedAt < MEDIA_ATTACH_WINDOW_MS;
        }

        synchronized void attachMedia(Socket s) throws IOException {
            s.setTcpNoDelay(true);
            s.setSendBufferSize(64 * 1024);
            media = s;
            new Thread(() -> mediaWatch(s), "mic-media-watch-" + id).start();
        }

        @Override public void run() {
            String why = "unknown";
            try {
                control.setTcpNoDelay(true);
                control.setSoTimeout(250);
                if (!takeover) Status.set(Status.State.HANDSHAKING, peerText, "TCP accepted, waiting 0x65");
                InputStream in = control.getInputStream();
                if (prefixByte >= 0) {
                    java.io.PushbackInputStream pb = new java.io.PushbackInputStream(in, 1);
                    pb.unread(prefixByte);
                    in = pb;
                }
                OutputStream out = control.getOutputStream();
                byte[] hdr = new byte[5];
                frameDeadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
                while (!dead) {
                    readFully(in, hdr, 5, "header");
                    int type = hdr[0] & 0xff;
                    long len = ((hdr[1] & 0xffL) << 24) | ((hdr[2] & 0xff) << 16) | ((hdr[3] & 0xff) << 8) | (hdr[4] & 0xff);
                    if (len > MAX_CONTROL_PAYLOAD) throw new IOException("control payload too large: " + len);
                    byte[] payload = new byte[(int) len];
                    readFully(in, payload, payload.length, "payload 0x" + Integer.toHexString(type));
                    if (!handshakeDone && type != CMD_CHECK_VERSION)
                        throw new IOException("first frame must be 0x65, got 0x" + Integer.toHexString(type));
                    frameDeadline = System.currentTimeMillis() + IDLE_TIMEOUT_MS;
                    if (!handle(type, payload, out)) { why = "client sent 0x68 stop"; return; }
                }
                why = "terminated";
            } catch (SocketTimeoutException e) {
                why = e.getMessage();
            } catch (EOFException e) {
                why = "EOF: " + e.getMessage();
            } catch (Throwable e) {
                why = dead ? "closed" : e.toString();
            } finally {
                terminate(why, false);
            }
        }

        /** Returns false when the session should end (0x68). */
        private boolean handle(int type, byte[] p, OutputStream out) throws IOException {
            switch (type) {
                case CMD_CHECK_VERSION:
                    Status.event("#" + id + " 0x65 version proto=" + (p.length > 0 ? p[0] : -1)
                            + " client=" + (p.length > 2 ? p[1] + "." + p[2] : "?"));
                    reply(out, type, 0);
                    if (!handshakeDone) {
                        handshakeDone = true;
                        if (takeover) promote(this);
                        if (dead) return false;
                        Status.sessions++;
                        Status.set(Status.State.CONNECTED, peerText, "0x65 OK (已连接)");
                    }
                    return true;
                case CMD_SET_CODEC:
                    if (p.length >= 6) {
                        clientMediaPort = ((p[2] & 0xff) << 24) | ((p[3] & 0xff) << 16) | ((p[4] & 0xff) << 8) | (p[5] & 0xff);
                    }
                    Status.event("#" + id + " 0x66 codec=" + (p.length > 0 ? p[0] : -1) + " srIdx="
                            + (p.length > 1 ? p[1] : -1) + " clientMediaPort=" + clientMediaPort);
                    reply(out, type, 0);
                    return true;
                case CMD_START:
                    reply(out, type, 0);
                    startCapture();
                    return true;
                case CMD_STOP:
                    Status.event("#" + id + " 0x68 stop");
                    try { reply(out, type, 0); } catch (IOException ignored) { }
                    return false;
                case CMD_HEARTBEAT:
                    return true; // no reply; deadline already refreshed
                default:
                    Status.event("#" + id + " unknown cmd 0x" + Integer.toHexString(type) + " len=" + p.length);
                    reply(out, type, 0);
                    return true;
            }
        }

        private void reply(OutputStream out, int type, int status) throws IOException {
            synchronized (control) {
                out.write(new byte[]{(byte) type, 0, 0, 0, 1, (byte) status});
                out.flush();
            }
        }

        private void readFully(InputStream in, byte[] b, int n, String what) throws IOException {
            int off = 0;
            while (off < n) {
                if (dead) throw new IOException("session terminated");
                int r;
                try {
                    r = in.read(b, off, n - off);
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() > frameDeadline) {
                        throw new SocketTimeoutException(handshakeDone
                                ? "idle timeout: no control frame/heartbeat for " + IDLE_TIMEOUT_MS + " ms"
                                : "handshake timeout: no complete 0x65 within " + HANDSHAKE_TIMEOUT_MS + " ms");
                    }
                    continue;
                }
                if (r < 0) throw new EOFException("peer closed while reading " + what);
                off += r;
            }
        }

        // ------------------------------------------------------------ capture / media
        private synchronized void startCapture() {
            if (started || dead) return;
            started = true;
            startedAt = System.currentTimeMillis();
            Status.packetsSent = Status.bytesSent = Status.packetsDropped = 0;
            Status.sessionStartMs = startedAt;
            Status.transport = peerIp.isLoopbackAddress()
                    ? "TCP (USB/ADB), waiting media connection"
                    : "UDP -> " + peerIp.getHostAddress() + ":" + clientMediaPort;
            captureThread = new Thread(this::captureLoop, "mic-capture-" + id);
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
            Status.set(Status.State.STREAMING, peerText, "0x67 start capture (推流中)");
        }

        @SuppressLint("MissingPermission")
        private void captureLoop() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            AudioRecord rec = null;
            Opus enc = null;
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 2 * 8));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord init failed (RECORD_AUDIO granted?)");
                enc = new Opus(SAMPLE_RATE, 1, BITRATE);
                rec.startRecording();
                if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("AudioRecord did not start recording");
                Status.event("#" + id + " capture started: MIC 48k mono s16, 20 ms Opus " + BITRATE / 1000 + " kbps");
                short[] pcm = new short[FRAME];
                byte[] buf = new byte[11 + 1500];
                InetSocketAddress udpTarget = (!peerIp.isLoopbackAddress() && clientMediaPort > 0 && clientMediaPort < 65536)
                        ? new InetSocketAddress(peerIp, clientMediaPort) : null;
                DatagramPacket dp = new DatagramPacket(buf, 0);
                int seq = 0;
                long ts = 0;
                while (!dead) {
                    int got = 0;
                    while (got < FRAME && !dead) {
                        int r = rec.read(pcm, got, FRAME - got, AudioRecord.READ_BLOCKING);
                        if (r < 0) throw new IllegalStateException("AudioRecord.read error " + r);
                        got += r;
                    }
                    if (dead) break;
                    int n = enc.encode(pcm, FRAME, buf, 11);
                    int dataLen = n + 7;
                    buf[0] = 0x04; buf[1] = 0x00;                      // ver 0x0400
                    buf[2] = (byte) (dataLen >> 8); buf[3] = (byte) dataLen;
                    buf[4] = (byte) (seq >> 8); buf[5] = (byte) seq;
                    buf[6] = (byte) (ts >> 24); buf[7] = (byte) (ts >> 16); buf[8] = (byte) (ts >> 8); buf[9] = (byte) ts;
                    buf[10] = 0;                                       // flag
                    int total = 11 + n;
                    Socket m = media;
                    if (m != null) {
                        byte[] frame = new byte[total];
                        System.arraycopy(buf, 0, frame, 0, total);
                        while (!mediaQueue.offer(frame)) {             // full: drop the oldest frame
                            if (mediaQueue.poll() != null) Status.packetsDropped++;
                        }
                    } else if (udpTarget != null) {
                        dp.setData(buf, 0, total);
                        dp.setSocketAddress(udpTarget);
                        try {
                            udp.send(dp);
                            Status.packetsSent++;
                            Status.bytesSent += total;
                        } catch (IOException e) {
                            Status.packetsDropped++;                   // transient (e.g. ENOBUFS / no route)
                            if (Status.packetsDropped % 50 == 1) Status.event("#" + id + " UDP send error: " + e);
                        }
                    } else {
                        Status.packetsDropped++;                       // USB mode before media TCP attached
                    }
                    seq = (seq + 1) & 0xffff;
                    ts += FRAME;
                }
            } catch (Throwable t) {
                if (!dead) terminate("capture failed: " + t, false);
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Throwable ignored) { }
                    rec.release();
                }
                if (enc != null) enc.close();
            }
        }

        private void mediaWriter(Socket s) {
            try {
                OutputStream os = s.getOutputStream();
                while (!dead) {
                    byte[] f = mediaQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (f == null) continue;
                    os.write(f);
                    Status.packetsSent++;
                    Status.bytesSent += f.length;
                }
            } catch (Throwable t) {
                if (!dead) terminate("TCP media write failed: " + t, false);
            }
        }

        /**
         * The real client never sends on the media socket. Probe it briefly before writing:
         * data => it is a reconnecting control client (reclassify); silence => start the writer.
         * Afterwards EOF/RST on it ends the session.
         */
        private void mediaWatch(Socket s) {
            try {
                InputStream in = s.getInputStream();
                s.setSoTimeout(MEDIA_PROBE_MS);
                int r;
                try {
                    r = in.read();
                } catch (SocketTimeoutException quiet) {
                    r = -2;
                }
                if (r >= 0) {
                    synchronized (this) { if (media == s) media = null; }
                    reclassifyAsControl(this, s, r);
                    return;
                }
                if (r == -1) { if (!dead) terminate("TCP media connection closed by peer", false); return; }
                s.setSoTimeout(0);
                mediaQueue.clear(); // don't start the stream with a probe-window backlog
                synchronized (this) {
                    if (dead) return;
                    Status.transport = "TCP (USB/ADB) " + addr(s);
                    writerThread = new Thread(() -> mediaWriter(s), "mic-media-tcp-" + id);
                    writerThread.start();
                }
                r = in.read();
                if (!dead) terminate(r < 0 ? "TCP media connection closed by peer"
                        : "unexpected data on media connection", false);
            } catch (Throwable t) {
                if (!dead) terminate("TCP media connection error: " + t, false);
            }
        }

        void terminate(String why, boolean graceful) {
            synchronized (this) {
                if (dead) return;
                dead = true;
            }
            Status.event("#" + id + " session end (" + peerText + "): " + why + " -> RST");
            abort(control);
            Socket m = media;
            if (m != null) abort(m);
            mediaQueue.clear();
            if (Thread.currentThread() != captureThread) join(captureThread);
            if (Thread.currentThread() != writerThread) join(writerThread);
            sessionEnded(this);
        }
    }

    // ---------------------------------------------------------------- helpers
    static void abort(Socket s) {
        if (s == null) return;
        try { s.setSoLinger(true, 0); } catch (IOException ignored) { }
        closeQuietly(s);
    }

    static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) { }
    }

    static void join(Thread t) {
        if (t == null) return;
        try { t.join(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    static String addr(Socket s) {
        InetAddress a = s.getInetAddress();
        return (a == null ? "?" : a.getHostAddress()) + ":" + s.getPort();
    }
}
