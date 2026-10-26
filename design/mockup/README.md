# MacMic phone — mockups

## A. Design mockups (HTML → PNG, 1080×2342 = Pixel 11 Pro Fold cover screen @ 390 dpi)
Rendered by `python3 phone-app/design/tools/render_mockups.py` (headless Chrome, CSS px = dp,
scale 2.4375). Colours come from `res/values-night/colors.xml`, so they match the real app.

| File | State |
|---|---|
| `00_overview.png` | All five side by side (quick look) |
| `01_waiting.png` | **WAITING** — service on, listening on 8125, no client. Amber pill, radar glyph with halo, tonal "停止服务" |
| `02_streaming.png` | **STREAMING** — client connected over Wi-Fi/UDP; green pill, mic glyph, red-tonal "停止推流", live stats |
| `03_error_port_busy.png` | **ERROR** — port 8125 in use; red pill/badge, guidance text, filled "重试" |
| `04_lockscreen_notification.png` | Lock screen with the proposed custom foreground-service notification (dot + title + transport/uptime + compact "停止") |
| `05_permission_needed.png` | Empty state: RECORD_AUDIO not granted → amber banner "授予权限" + filled "启动服务" |
| `06_streaming_light.png` | STREAMING in the light scheme (system light mode) |
| `07_streaming_full_scroll.png` | Full-length scroll capture: all cards (connection, keep-alive with switch, event log) |

## B. Real-device screenshots (`real-device/`, Pixel 11 Pro Fold outer display, dark mode, zh-CN)

| File | What |
|---|---|
| `app_01_waiting.png` | **Real app** `com.macmic.micserver` after merge, LISTENING |
| `app_02_streaming.png` | **Real app** while `tools/e2e_test.py wifi` was streaming (~50 pkt/s) |
| `app_04_notification_shade_crop.png` | Notification shade, cropped to MacMic only. Top: the *proposed* custom layout (`notification_mic.xml`, posted by the preview APK). Bottom: the real app's current notification (standard template from the unchanged `MicService`) |
| `preview_02_streaming.png` | Design-preview APK (same res + `MainUi`, fake data), STREAMING |
| `preview_03_error_port_busy.png` | Design-preview APK, ERROR / port busy (not reproduced on the real app on purpose: it would mean stealing port 8125 from the running server) |
| `preview_05_permission_needed.png` | Design-preview APK, mic permission missing |

The shade screenshot is cropped so no other apps' notifications are included; the lock screen was not
captured on device for the same privacy reason (see mockup 04 instead).
