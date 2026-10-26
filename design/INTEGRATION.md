# MacMic phone — design integration guide

Status: **merged** into the real app on 2026-10-26 (see §0). This file is both the record of
what was merged and the contract for future changes. Everything is platform-only (no Maven,
no androidx, no Material Components) and builds with the hand-written `build.sh` (aapt2 + javac + d8).

## 0. What is merged (real app)

| Path in `phone-app/` | Change |
|---|---|
| `res/**` | Replaced with `design/res/**` **minus** the design-only files `layout/notification_mic.xml`, `layout/item_log.xml`, `drawable/ic_stop_small.xml`, `drawable/bg_notif_stop.xml` (unused until `MicService` adopts the custom notification, §5) |
| `src/com/macmic/micserver/MainActivity.java` | Rewritten (161 lines): same intents/permissions/500 ms tick, now maps `Status` → `MainUi.Model` |
| `src/com/macmic/micserver/MainUi.java` | New (477 lines) = `design/reference/MainUi.java` (view binder) |
| `AndroidManifest.xml` | Only `android:icon="@mipmap/ic_launcher"` and `android:theme="@style/Theme.MacMic"` |

Not touched: `MicServer.java`, `MicService.java`, `Status.java`, `Opus.java`, `native/**`,
permissions/components/FGS type in the manifest, `build.sh`.

### Re-sync after editing design files
```sh
cd phone-app
python3 design/tools/gen_icons.py            # if icons changed
python3 design/tools/check_contrast.py       # must exit 0
cp -R design/res/. res/
rm -f res/layout/notification_mic.xml res/layout/item_log.xml res/drawable/ic_stop_small.xml res/drawable/bg_notif_stop.xml
cp design/reference/MainUi.java src/com/macmic/micserver/MainUi.java
./build.sh install
```
(If the project ever moves to `app/src/main/`, the mapping is `design/res/X → app/src/main/res/X`,
`design/reference/*.java → app/src/main/java/com/macmic/micserver/`.)

## 1. Resources

| Folder | Content |
|---|---|
| `values/colors.xml` / `values-night/colors.xml` | M3 roles (light / **dark = hero**): `primary`, `on_primary`, `primary_container`, `secondary_container`, `surface`, `surface_container{_lowest,_low,,_high,_highest}`, `on_surface(_variant)`, `outline(_variant)`, `error*`, `success*`, `warning*`, `state_{idle,waiting,connected,streaming,error}_{dot,container,on}`, `cta_stop_container`/`on_cta_stop`, `log_*`, `app_icon_background` |
| `values/themes.xml`, `values-night/themes.xml`, `values-v31/themes.xml` | `Base.Theme.MacMic` → `android:Theme.Material(.Light).NoActionBar`; `Theme.MacMic` (+ v31 splash-screen attrs) |
| `values/styles.xml` | `TextAppearance.MacMic.*` (Headline 26, TitleLarge 22, TitleMedium 16, TitleSmall 14, Body L/M/S 16/14/12, Label L/M, Cta 18, Address 22 tnum, Stat 20 tnum, Mono 12) and `Widget.MacMic.*` (Card, Row, StatTile, Cta, Button.Tonal/Text, IconButton, Switch, Pill, Chip, Divider) |
| `values/dimens.xml` (+ `values-w600dp`, `values-w840dp`) | 8dp grid (`space_xs…xxxl` = 4…48), radii 8/12/16/24/full, `touch_min` 48, `cta_height` 64, type sizes; wider `screen_margin` on the Fold inner screen |
| `values/strings.xml` (English fallback) + `values-zh/strings.xml` (Chinese, primary) | all UI/notification copy. Chinese must stay in `values-zh`: with a device locale list `[zh-CN, en-US]` Android would otherwise choose `values-en` over `values/` |
| `drawable/ic_*.xml` | 22 VectorDrawable glyphs (tint = `?android:colorControlNormal`, except `ic_mic` which is the notification small icon), `ic_stat_mic` (filled), launcher layers |
| `drawable/bg_*.xml`, `switch_*.xml` | shapes / ripples / selectors (cards 24dp, tiles & console 16dp, rows 12dp, pills full) |
| `animator/press_scale.xml`, `animator/pulse.xml` | CTA press feedback, listening halo |
| `mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon (background + foreground + monochrome for themed icons) |

## 2. Theme notes
- **Light/dark** follow the system (`values-night`). The dark scheme is the designed default.
  To force dark for the whole app without androidx: `getSystemService(UiModeManager.class).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)` (API 31+); on 29–30 there is no per-app switch — remove `values/colors.xml` light values or copy the night palette into `values/`.
- **Edge-to-edge**: bars transparent, `enforce*Contrast=false`, cutout `shortEdges`.
  `MainUi.enableEdgeToEdge(window)` covers API 29–34; API 35+ with targetSdk 36 is enforced by the system.
  `MainUi` pads `@id/scroll` with system-bar + cutout insets and pads the bottom of `@id/content` so the
  last card clears the gesture bar.
- **Dynamic colour** (Material You): possible without androidx via `@android:color/system_accent1_*`
  (API 31+), but deliberately **not** enabled — status colours (green streaming / amber waiting / red error)
  must stay recognisable. Opt-in recipe: add `values-v31/colors.xml` overriding only
  `primary=@android:color/system_accent1_200`, `on_primary=@android:color/system_accent1_800`,
  `primary_container=@android:color/system_accent1_700`, `on_primary_container=@android:color/system_accent1_100`
  (light: 600/0/100/900) and leave the `state_*` colours alone.
- **Fonts**: none bundled. System `sans-serif` / `sans-serif-medium`, `fontFeatureSettings="tnum"` for
  numbers, `monospace` for logs.

## 3. `activity_main.xml` view-id contract

### 3.1 Kept from the original functional layout (behaviour preserved)
| id | Type (was → now) | Binding |
|---|---|---|
| `btn_start` | Button → clickable `LinearLayout` (icon + label) | visible in STOPPED / ERROR; STOPPED: "启动服务" → `startServer()` (permission flow); ERROR: "重试" → stop + start 400 ms later |
| `btn_stop` | Button → clickable `LinearLayout` | visible while running; tonal "停止服务" (WAITING/HANDSHAKING/CONNECTED), red-tonal "停止推流" (STREAMING) → `stopService(MicService)` |
| `chk_keep_screen_on` | CheckBox → `Switch` (M3 look) | toggles `FLAG_KEEP_SCREEN_ON`; whole row `row_keep_screen` toggles it too |
| `txt_state` | TextView (state pill label) | 未启动 / 等待连接 / 握手中 / 已连接 / 推流中 / 错误 |
| `txt_status` | TextView (hero subtitle) | per-state explanation; ERROR shows `Status.detail` or port-busy guidance |
| `txt_events` | TextView in `log_scroll` | `Status.recentEvents()` newest first, ≤60, time column dimmed, errors red, warnings amber; nested-scrolls inside the page |

### 3.2 New ids
| id | Type | Purpose / binding |
|---|---|---|
| `root` | FrameLayout | WindowInsets listener |
| `scroll`, `content` | ScrollView, LinearLayout | page scroller; padded by insets |
| `app_badge`, `app_title`, `app_subtitle` | ImageView, TextView×2 | brand (static) |
| `state_pill`, `state_dot` | LinearLayout, View | `setBackgroundTintList(state_*_container / state_*_dot)`; pill has `accessibilityLiveRegion=polite` + contentDescription "当前状态：…" |
| `banner`, `banner_icon`, `banner_title`, `banner_message`, `banner_action`, `banner_dismiss` | LinearLayout, ImageView, TextView×2, Button×2 | empty/error placeholder. Kinds: `MIC_PERMISSION` (授予权限 → request), `MIC_PERMISSION_PERMANENT` (打开设置 → app details), `NOTIFICATIONS_OFF` (开启通知 → channel settings, dismissible), `NO_WIFI` (info, dismissible) |
| `card_hero`, `hero_badge_frame`, `hero_pulse`, `hero_badge`, `hero_title` | — | state badge (tinted circle + glyph), halo animator (WAITING/STREAMING; static ring if animations are off), headline |
| `btn_start_icon/_label`, `btn_stop_icon/_label` | ImageView, TextView | CTA content (recoloured per state) |
| `btn_secondary` | Button (text) | ERROR while the service still auto-retries → "停止服务" |
| `row_address`, `address_value`, `address_extra`, `address_copy_icon` | — | `IP:8125` (Wi-Fi `wlan*` first), tap = copy (toast only < API 33; 13+ shows system UI); extra = other interfaces + `adb forward` hint |
| `row_transport`, `transport_icon`, `transport_value` | — | `ic_wifi` "Wi-Fi · UDP → ip:port" or `ic_usb` "USB · TCP（ADB）" (from `Status.transport` / loopback peer) |
| `row_peer`, `peer_icon`, `peer_value` | — | client `ip:port` with green check, or "未连接" + `ic_link_off` |
| `stat_packets(_value/_sub)`, `stat_bytes(_value/_sub)`, `stat_uptime(_value/_sub)` | tiles | packets (+ pkt/s, dropped), bytes (+ kbps), session uptime (+ session #); tiles get a combined contentDescription |
| `card_keepalive`, `keepalive_icon/_title/_text/_battery`, `chip_wakelock`, `chip_wifilock` | — | foreground-service explanation; chips' dot green when `Status.wakeLockHeld / wifiLockHeld` |
| `row_keep_screen`, `keep_screen_title` (labelFor the switch), `keep_screen_desc` | — | keep-screen-on row |
| `card_log`, `log_count`, `btn_log_copy`, `log_scroll`, `log_empty` | — | log header, copy-all, scroller, "暂无事件" |
| `footer_text` | TextView | `v<versionName> · 端口 8125 · libopus 1.5.2` |

### 3.3 State → look (implemented in `MainUi.renderState`)
| `Status.State` | UiState | Pill | Badge / glyph | CTA |
|---|---|---|---|---|
| STOPPED | STOPPED | grey 未启动 | `surface_container_highest` / `ic_mic_off` | `btn_start` filled primary 启动服务 |
| LISTENING | WAITING | amber 等待连接 | `primary_container` / `ic_listening` + halo | `btn_stop` tonal 停止服务 |
| HANDSHAKING | HANDSHAKING | amber 握手中 | same as WAITING | tonal 停止服务 |
| CONNECTED | CONNECTED | teal 已连接 | `primary_container` / `ic_connected` | tonal 停止服务 |
| STREAMING | STREAMING | green 推流中 | `success_container` / `ic_mic` + halo | `btn_stop` red-tonal 停止推流 |
| ERROR | ERROR | red 错误 | `state_error_container` / `ic_error` | `btn_start` filled 重试 (+ `btn_secondary` 停止服务) |

Port-busy is detected from `Status.detail` containing `"cannot bind port"`.
Changing state runs a 180 ms `AutoTransition` on the hero card.

## 4. Behaviour kept
`--ez autostart true` / `--ez stop true`, RECORD_AUDIO + POST_NOTIFICATIONS request flow and the
`"RECORD_AUDIO denied; cannot start"` event, 500 ms refresh (`onResume`/`onPause`), keep-screen-on,
scrollable event log. Note (pre-existing, not changed): when the activity's task is merely brought to
front, `am start --ez autostart true` does not reach `onNewIntent`; run it again or use `-S` semantics.

## 5. Notification (proposal — needs a `MicService` change, so NOT merged)
The current notification is the standard template built by `MicService` (ongoing, PUBLIC, CATEGORY_SERVICE,
FGS_IMMEDIATE, Stop action); after the merge it automatically uses the new `ic_mic` glyph and the app
icon. The designed version (`design/res/layout/notification_mic.xml` + `design/reference/MicNotificationUi.java`,
see `mockup/04_lockscreen_notification.png` and `mockup/real-device/app_04_notification_shade_crop.png`, top):
- `Notification.DecoratedCustomViewStyle` + `setCustomContentView` (≤48dp, RemoteViews-safe widgets only),
  state dot via `setInt("setColorFilter")`, `Chronometer` for uptime, compact "停止" pill
  (`setOnClickPendingIntent(R.id.notif_stop, ACTION_STOP)`) **plus** the standard Stop action.
- `setOngoing(true)`, `setVisibility(VISIBILITY_PUBLIC)` (lock-screen visible), `setOnlyAlertOnce(true)`,
  `setShowWhen(false)`, `setColor(primary)`, small icon `ic_stat_mic`, localized title/text also set for
  accessibility/Wear fallback.
- Channel: id `mic`, name `@string/notif_channel_name` (麦克风服务), `IMPORTANCE_LOW`, no badge,
  lockscreen visibility PUBLIC.
To adopt: copy the four design-only files back into `res/`, copy `MicNotificationUi.java`, and in
`MicService.buildNotification()` return `MicNotificationUi.build(this, CHANNEL, uiState, host, transport,
Status.sessionStartMs, open, stop)` (map `Status.State` exactly like `MainActivity.render()`).

## 6. Accessibility
- Touch targets ≥48dp (CTA 64dp, rows ≥56dp, icon buttons 48dp, banner buttons 48dp, switch row).
- Text contrast checked by `design/tools/check_contrast.py`: all text pairs ≥4.5:1 (dark ≥7:1), status
  dots/outline ≥3:1.
- Decorative icons `importantForAccessibility="no"`; CTA / copy / pill / tiles have contentDescription;
  card titles are `accessibilityHeading`; pill + hero title are polite live regions, banner assertive.
- TalkBack order = visual order: brand → state → banner → hero (title, status, CTA) → connection rows →
  stats → keep-alive → switch → log.
- Halo animation honours "Remove animations" (`ValueAnimator.areAnimatorsEnabled()`).
- Chinese copy wraps freely (no fixed heights except the 232dp log viewport); stat values auto-size 14–20sp.

## 7. Tools / preview
- `design/tools/gen_icons.py` — icons (SVG + VectorDrawable); `design/tools/check_contrast.py` — WCAG check;
  `design/tools/render_mockups.py` — HTML mockups → PNG.
- `design/preview/build_preview.sh` — builds a **separate** preview APK (`com.macmic.micserver.design`,
  fake data, cycles states on CTA tap, posts the proposed notification) into `/tmp/macmic-design-preview/`.
  It never installs by itself; uninstall after use: `adb uninstall com.macmic.micserver.design`.
