# MacMic Android

Phone-side microphone server for **[MacMic](https://github.com/yudshj/MacMic)** — a macOS client that turns an
Android phone into a microphone (Wi‑Fi or USB/ADB). This repository is the Android half: a small,
dependency-free app that streams the phone microphone to the Mac over a WO Mic-compatible protocol that MacMic
speaks.

[中文说明](README_ZH.md)

![design overview](design/mockup/00_overview.png)

## Background

This project started from a concrete failure we hit with the Mac client: it reported *"handshake 0x65 failed"*
while the phone was perfectly reachable at the network level — a plain `nc` listener on the same port accepted
connections instantly, while the listening socket of WO Mic 5.8 (`com.wo.voice2`) did not answer.

In our testing (WO Mic 5.8, Android 17, Pixel 11 Pro Fold) we observed that a client which connects but never
completes the handshake can leave the app's session occupied: the app showed `已连接` (Connected) and ignored
subsequent clients until someone tapped *Stop → Start*. **That is what we measured on this version and device,
not a general claim about the app** — behaviour may differ elsewhere.

So the phone side was written independently, with explicit timeouts and cleanup on every failure path, so that
one aborted attempt cannot wedge it.

## Features

- **Wire-compatible** with MacMic's macOS client: TCP 8125 control channel, Opus media over **UDP** (Wi‑Fi) or a
  second **TCP** connection (USB/ADB).
- **Opus 48 kHz mono, 20 ms frames, ~64 kbps VBR**, encoded with libopus 1.5.2 built from source with the NDK
  (the device's vendor codec set has no `audio/opus` encoder).
- **Never gets stuck**: 5 s handshake timeout, 8 s idle/heartbeat timeout, abortive `SO_LINGER` RST cleanup,
  non-blocking acceptor, single client with a fast hand-over when the same IP reconnects.
- **Keeps streaming with the screen off / locked**: foreground service (`microphone` type) + `PARTIAL_WAKE_LOCK`
  + `WIFI_MODE_FULL_HIGH_PERF`. Measured: 60 s in Doze, 49.9 packets/s, zero sequence gaps.
- **Visible state**: status pill, peer address, transport, packets/bytes, uptime and a rolling event log —
  so the next failure is diagnosable instead of mysterious.
- **No ads, no analytics**, eight permissions, all of them necessary.

| Waiting | Streaming |
| --- | --- |
| ![waiting](design/mockup/real-device/app_01_waiting.png) | ![streaming](design/mockup/real-device/app_02_streaming.png) |

## Requirements

- Phone: **Android 10+** (`minSdk 29`), **arm64-v8a**. Developed and tested on Android 17 (Pixel 11 Pro Fold).
- Host: macOS + [MacMic](https://github.com/yudshj/MacMic) (the client), or any client that speaks
  [the protocol](PROTOCOL.md).
- Build: **JDK 17**, Android SDK platform 36 + build-tools 36.0.0, NDK 29, CMake 3.22.1, Ninja.
  No Gradle / Android Gradle Plugin is used (see `build.sh`).

## Build & install

```bash
# debug-signed build (self-generated key) → build/MacMicServer.apk
./build.sh

# build + adb install -r + grant mic/notification permissions + start the service
./build.sh install

# release-signed build; needs your own keystore (see below)
./build.sh release
./build.sh release-install
```

Release signing reads `~/.android/macmic-release.properties`:

```properties
storeFile=/Users/you/.android/macmic-release.jks
storePassword=...
keyAlias=macmic
keyPassword=...
```

Override with `MACMIC_RELEASE_KS` / `MACMIC_RELEASE_PROPERTIES`. A release-signed APK cannot be installed over a
debug-signed one (different signature), so `release-install` uninstalls first and re-grants permissions.

The first build downloads libopus 1.5.2 (pinned SHA-256) and compiles it for `arm64-v8a`; it is cached under
`build/` (gitignored).

## Usage

1. Open **MacMic** on the phone, tap **Start** (grant microphone + notification permission on first run).
   The card shows the address to type into the Mac app, e.g. `192.168.1.23:8125`.
2. On the Mac, in MacMic choose **Wi‑Fi** and enter that address, or choose **USB (ADB)** — MacMic creates the
   `adb forward` itself.
3. Make sure the original WO Mic app is **not running**: both want TCP 8125. This app reports a port conflict in
   its UI and retries every 3 s.

Command line helpers:

```bash
# start / stop from a terminal (the first call may only bring the UI forward; run it twice)
adb shell am start -n com.macmic.micserver/.MainActivity --ez autostart true
adb shell am start -n com.macmic.micserver/.MainActivity --ez stop true

# socket-level end-to-end test from the Mac (no audio dependencies)
python3 tools/e2e_test.py wifi 192.168.1.23 10
python3 tools/e2e_test.py usb 127.0.0.1 18125 5   # after: adb forward tcp:18125 tcp:8125
python3 tools/e2e_test.py silent 192.168.1.23 9   # connect, send nothing, expect RST after ~5 s
```

Logcat tag: `MacMicServer`.

## Protocol

TCP 8125 control frames, the 11-byte media header, sequences/timestamps, keepalives and the exact transport
matrix are documented in **[PROTOCOL.md](PROTOCOL.md)**. The UI/design system is documented in
[design/INTEGRATION.md](design/INTEGRATION.md).

## Known limitations

- arm64-v8a only; tested on Android 17 / Pixel hardware only.
- Audio source is `MIC` with no AEC/NS on the phone (the Mac client has its own denoiser), fixed 64 kbps.
- The service holds a wake lock and a Wi‑Fi lock while running — **stop it when you are done** to save battery.
  Consider whitelisting the app from battery optimization.
- USB media detection has a 300 ms probe window, so roughly the first 10 frames (~35 packets in the first second)
  are dropped after connecting over ADB.
- Not tested against the official WO Mic desktop client; MacMic is the reference client.

## License

MIT — see [LICENSE](LICENSE).

- The app is an independent implementation. It contains no code, assets or text from the WO Mic app, and is
  **not affiliated with, endorsed or sponsored by** Wolicheng Tech / WO Mic. "WO Mic" is a trademark of its
  respective owner and is used here only to describe interoperability.
- Opus is BSD-3-Clause and is downloaded and built at build time.
- All icons are original (see [design/ICONS_LICENSE.md](design/ICONS_LICENSE.md)).
