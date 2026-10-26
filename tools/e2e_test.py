#!/usr/bin/env python3
"""Socket-level end-to-end test for the MacMic phone server (no audio deps).

  wifi  HOST [secs] [--sleep-at N]  : 0x65/0x66(UDP port)/0x67, count UDP media, 0x69 every 2s
  usb   HOST PORT [secs]            : control on PORT, media on 2nd TCP to PORT
  silent HOST [hold]                : TCP connect, send nothing, close after `hold` s
  rst   HOST                        : full handshake + start, then RST
"""
import os, socket, struct, sys, time, threading

ADB = ["adb"] + (["-s", os.environ["ANDROID_SERIAL"]] if os.environ.get("ANDROID_SERIAL") else [])

def frame(t, p=b""):
    return struct.pack(">BI", t, len(p)) + p

def read_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise ConnectionError("EOF")
        b += c
    return b

def exchange(s, t, p=b""):
    t0 = time.monotonic()
    s.sendall(frame(t, p))
    typ, ln = struct.unpack(">BI", read_exact(s, 5))
    status = read_exact(s, 1)[0] if ln else 0
    data = read_exact(s, ln - 1) if ln > 1 else b""
    ok = typ == t and status == 0
    print(f"  0x{t:02x} -> type=0x{typ:02x} len={ln} status={status} data={data.hex()} "
          f"{'OK' if ok else 'FAIL'} ({(time.monotonic()-t0)*1000:.0f} ms)")
    if not ok:
        raise SystemExit(1)

def handshake(s, media_port):
    exchange(s, 0x65, bytes([4, 6, 3, 0, 0, 0]))
    exchange(s, 0x66, bytes([2, 2]) + struct.pack(">I", media_port))
    exchange(s, 0x67)

def rst(s):
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    except OSError:
        pass  # already reset by peer
    s.close()

def hb_loop(s, stop, udp=None, host=None):
    while not stop.wait(2.0):
        try:
            s.sendall(frame(0x69))
            if udp:
                udp.sendto(b"\x00\x04\x00\x00", (host, 8125))
        except OSError as e:
            print("heartbeat failed:", e); return

def parse(pkt):
    ver, ln, seq, ts, flag = struct.unpack(">HHHIB", pkt[:11])
    return ver, ln, seq, ts, flag

def report(per_sec):
    print("  per-second packet counts:", per_sec)

def wifi(host, secs=10, sleep_at=None, wake_at=None):
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", 8125)); udp.settimeout(0.5)
    s = socket.create_connection((host, 8125), timeout=5)
    handshake(s, udp.getsockname()[1])
    stop = threading.Event()
    threading.Thread(target=hb_loop, args=(s, stop, udp, host), daemon=True).start()
    udp.sendto(b"\x00\x04\x00\x00", (host, 8125))
    t0 = time.monotonic(); per_sec = [0] * secs; total = 0; nbytes = 0; first = []; last_seq = None; gaps = 0; bad = 0
    import subprocess
    while (el := time.monotonic() - t0) < secs:
        if sleep_at is not None and el >= sleep_at:
            sleep_at = None
            subprocess.run(ADB + ["shell", "input", "keyevent", "KEYCODE_SLEEP"])
            print(f"  [t={el:.1f}s] KEYCODE_SLEEP sent")
        if wake_at is not None and el >= wake_at:
            wake_at = None
            subprocess.run(ADB + ["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
            print(f"  [t={el:.1f}s] KEYCODE_WAKEUP sent")
        try:
            pkt, addr = udp.recvfrom(2048)
        except socket.timeout:
            continue
        ver, ln, seq, ts, flag = parse(pkt)
        if ver != 0x0400 or ln != len(pkt) - 4:
            bad += 1
        if last_seq is not None and seq != (last_seq + 1) & 0xffff:
            gaps += 1
        last_seq = seq
        if len(first) < 5:
            first.append(f"from={addr[0]}:{addr[1]} ver=0x{ver:04x} len={ln} seq={seq} ts={ts} flag={flag} "
                         f"opus_bytes={len(pkt)-11} toc=0x{pkt[11]:02x}")
        total += 1; nbytes += len(pkt)
        i = int(el)
        if i < secs:
            per_sec[i] += 1
    stop.set()
    print("  first packets:"); [print("   ", f) for f in first]
    report(per_sec)
    print(f"  total={total} pkts in {secs}s = {total/secs:.1f}/s, {nbytes*8/secs/1000:.1f} kbit/s, "
          f"seq gaps={gaps}, malformed={bad}, min/s={min(per_sec)}")
    try:
        exchange(s, 0x68)
    except Exception as e:
        print("  0x68:", e)
    rst(s)

def usb(host, port, secs=5):
    s = socket.create_connection((host, port), timeout=5)
    handshake(s, port)
    m = socket.create_connection((host, port), timeout=5)
    stop = threading.Event()
    threading.Thread(target=hb_loop, args=(s, stop), daemon=True).start()
    t0 = time.monotonic(); n = 0; first = []; per_sec = [0] * secs; last = None; gaps = 0
    while (el := time.monotonic() - t0) < secs:
        hdr = read_exact(m, 11)
        ver, ln, seq, ts, flag = parse(hdr)
        payload = read_exact(m, ln - 7)
        if last is not None and seq != (last + 1) & 0xffff: gaps += 1
        last = seq
        if len(first) < 5:
            first.append(f"ver=0x{ver:04x} len={ln} seq={seq} ts={ts} flag={flag} opus_bytes={len(payload)} toc=0x{payload[0]:02x}")
        n += 1; per_sec[min(int(el), secs - 1)] += 1
    stop.set()
    print("  first TCP media frames:"); [print("   ", f) for f in first]
    report(per_sec)
    print(f"  total={n} frames in {secs}s = {n/secs:.1f}/s, seq gaps={gaps}")
    exchange(s, 0x68)
    rst(s); rst(m)

def silent(host, hold=7):
    t0 = time.monotonic()
    s = socket.create_connection((host, 8125), timeout=5)
    print(f"  connected local={s.getsockname()} sending nothing, waiting up to {hold}s for server to drop us")
    s.settimeout(hold)
    try:
        d = s.recv(1)
        print(f"  server closed after {time.monotonic()-t0:.2f}s (recv={d!r})")
    except ConnectionResetError:
        print(f"  server RST after {time.monotonic()-t0:.2f}s")
    except socket.timeout:
        print(f"  still open after {hold}s (BAD)")
    s.close()

def rst_case(host):
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); udp.bind(("0.0.0.0", 0))
    s = socket.create_connection((host, 8125), timeout=5)
    handshake(s, udp.getsockname()[1])
    udp.settimeout(2); pkt, _ = udp.recvfrom(2048)
    print(f"  got media packet len={len(pkt)}; now RST control")
    rst(s); udp.close()
    print(f"  RST sent at {time.strftime('%H:%M:%S')}")

if __name__ == "__main__":
    a = sys.argv[1:]
    mode = a[0]
    t = time.monotonic()
    if mode == "wifi":
        kw = {}
        if "--sleep-at" in a: kw["sleep_at"] = float(a[a.index("--sleep-at") + 1])
        if "--wake-at" in a: kw["wake_at"] = float(a[a.index("--wake-at") + 1])
        wifi(a[1], int(a[2]) if len(a) > 2 and not a[2].startswith("--") else 10, **kw)
    elif mode == "usb": usb(a[1], int(a[2]), int(a[3]) if len(a) > 3 else 5)
    elif mode == "silent": silent(a[1], float(a[2]) if len(a) > 2 else 7)
    elif mode == "rst": rst_case(a[1])
    print(f"[{mode}] done in {time.monotonic()-t:.2f}s")
