#!/usr/bin/env python3
"""Render the MacMic phone mockups (HTML -> PNG via headless Chrome).

Each screen is laid out in CSS px == dp (viewport 443x961dp) and captured at device scale
2.4375 (390dpi / 160) => 1080x2342 px, i.e. the Pixel 11 Pro Fold cover display.
Colours/sizes are read from the same tokens as res/values-night/colors.xml + dimens.xml.

    python3 phone-app/design/tools/render_mockups.py
"""
import os
import re
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
OUT = os.path.join(DESIGN, "mockup")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
W, H, SCALE = 443, 961, 2.4375


def colors(scheme):
    path = os.path.join(DESIGN, "res", scheme, "colors.xml")
    out = {}
    for name, val in re.findall(r'<color name="(\w+)">#([0-9A-Fa-f]{8})</color>', open(path).read()):
        a = int(val[:2], 16) / 255
        out[name] = f"rgba({int(val[2:4], 16)},{int(val[4:6], 16)},{int(val[6:8], 16)},{a:.3f})"
    return out


def icon(name, cls="ic"):
    svg = open(os.path.join(DESIGN, "icons", f"{name}.svg")).read()
    return svg.replace("<svg ", f'<svg class="{cls}" ', 1)


CSS = """
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:%(W)dpx;height:%(H)dpx;overflow:hidden;background:%(background)s}
body{font-family:Roboto,"Google Sans","PingFang SC","Noto Sans CJK SC",-apple-system,sans-serif;color:%(on_surface)s;
 -webkit-font-smoothing:antialiased;position:relative}
.tall body,.tall{height:auto;overflow:visible}
.ic{width:24px;height:24px;flex:none;display:block}
.sb{height:40px;display:flex;align-items:center;justify-content:space-between;padding:0 24px 0 28px;font-size:14px;font-weight:500;color:%(on_surface)s}
.sb .r{display:flex;gap:6px;align-items:center}.sb .r .ic{width:16px;height:16px}
.wrap{padding:0 16px 24px}
.header{min-height:64px;display:flex;align-items:center;padding:8px 0 4px}
.badge{width:36px;height:36px;border-radius:50%%;flex:none}
.brand{margin-left:12px;flex:1;min-width:0}
.t-title-l{font-size:22px;font-weight:500;line-height:28px}
.t-body-s{font-size:12px;line-height:16px;color:%(on_surface_variant)s}
.t-body-m{font-size:14px;line-height:20px;color:%(on_surface_variant)s}
.t-body-l{font-size:16px;line-height:24px}
.t-label-m{font-size:12px;font-weight:500;letter-spacing:.4px;color:%(on_surface_variant)s}
.t-title-s{font-size:14px;font-weight:500;letter-spacing:.1px;color:%(on_surface_variant)s}
.t-title-m{font-size:16px;font-weight:500;letter-spacing:.1px;line-height:24px}
.pill{height:32px;border-radius:16px;display:flex;align-items:center;padding:0 12px;gap:8px;font-size:14px;font-weight:500;flex:none}
.dot{width:8px;height:8px;border-radius:50%%;flex:none}
.card{background:%(surface_container)s;border-radius:24px;padding:16px;margin-top:12px}
.hero{background:%(surface_container_high)s;border-radius:24px;padding:24px;margin-top:8px;display:flex;flex-direction:column;align-items:center;text-align:center}
.bframe{width:120px;height:120px;position:relative;display:flex;align-items:center;justify-content:center}
.ring{position:absolute;border-radius:50%%;border:2px solid}
.hbadge{width:88px;height:88px;border-radius:50%%;display:flex;align-items:center;justify-content:center;position:relative}
.hbadge .ic{width:40px;height:40px}
.h-title{font-size:26px;font-weight:500;line-height:34px;margin-top:12px}
.h-sub{margin-top:8px;max-width:340px}
.cta{margin-top:24px;height:64px;width:100%%;border-radius:32px;display:flex;align-items:center;justify-content:center;gap:8px;font-size:18px;font-weight:500;letter-spacing:.3px}
.row{min-height:56px;display:flex;align-items:center;padding:8px 0}
.row>.ic{margin-right:16px;color:%(on_surface_variant)s}
.row .col{flex:1;min-width:0}
.addr{font-size:22px;font-weight:500;color:%(primary)s;font-variant-numeric:tabular-nums;line-height:28px;margin-top:2px}
.copy{width:48px;height:48px;display:flex;align-items:center;justify-content:center;color:%(primary)s;margin-right:-8px}
.div{height:1px;background:%(outline_variant)s;margin-left:40px}
.stats{display:flex;gap:8px;margin-top:8px}
.tile{flex:1;background:%(surface_container_high)s;border-radius:16px;padding:12px}
.tile .v{font-size:20px;font-weight:500;margin-top:4px;font-variant-numeric:tabular-nums;white-space:nowrap}
.tile .s{font-size:12px;color:%(outline)s;margin-top:2px;white-space:nowrap}
.chips{display:flex;gap:8px;margin-top:8px}
.chip{height:28px;border:1px solid %(outline_variant)s;border-radius:8px;display:flex;align-items:center;gap:6px;padding:0 12px 0 10px;font-size:12px;font-weight:500;color:%(on_surface_variant)s}
.sw{width:52px;height:32px;border-radius:16px;position:relative;flex:none;margin-left:16px}
.sw .k{position:absolute;top:50%%;border-radius:50%%;transform:translate(-50%%,-50%%)}
.console{height:232px;margin-top:4px;background:%(surface_container_lowest)s;border:1px solid %(outline_variant)s;border-radius:16px;padding:8px 12px;overflow:hidden;position:relative}
.log{display:flex;gap:12px;font:12px/18px "Roboto Mono",Menlo,monospace;padding:3px 0;color:%(log_text)s}
.log .t{color:%(log_time)s;flex:none}
.log.w .m{color:%(log_warn)s}.log.e .m{color:%(log_error)s}
.loghead{display:flex;align-items:center;min-height:48px}
.banner{border-radius:16px;padding:16px 16px 8px;margin-top:8px;display:flex;gap:16px}
.banner .acts{display:flex;justify-content:flex-end;gap:4px;margin-top:4px}
.tbtn{height:48px;padding:0 12px;display:flex;align-items:center;font-size:14px;font-weight:500;border-radius:24px}
.gnav{position:absolute;bottom:8px;left:50%%;width:108px;height:4px;border-radius:2px;margin-left:-54px;background:%(on_surface)s;opacity:.5}
.footer{text-align:center;margin-top:16px;font-size:12px;color:%(outline)s}
.fade{position:absolute;left:0;right:0;bottom:0;height:56px;background:linear-gradient(transparent,%(background)s)}
"""

STATES = {
    "waiting": dict(pill=("state_waiting", "等待连接"), badge="primary_container", glyph="listening",
                    glyph_c="on_primary_container", ring="state_waiting_dot", title="等待 Mac 连接",
                    sub="在 Mac 上打开 MacMic，选择 Wi-Fi 并填入下方地址，或用 USB 连接。",
                    cta=("secondary_container", "on_secondary_container", "stop", "停止服务"),
                    transport=("wifi", "—"), peer=("link_off", "未连接"),
                    stats=(("—", ""), ("—", ""), ("00:01:12", "服务已运行")), locks=True),
    "streaming": dict(pill=("state_streaming", "推流中"), badge="success_container", glyph="mic",
                      glyph_c="on_success_container", ring="state_streaming_dot", title="正在推流",
                      sub="麦克风音频正以 Opus 48 kHz 发送到 192.168.1.40。",
                      cta=("cta_stop_container", "on_cta_stop", "stop", "停止推流"),
                      transport=("wifi", "Wi-Fi · UDP → 192.168.1.40:52100"), peer=("connected", "192.168.1.40:51234"),
                      stats=(("48,213", "50 包/秒 · 丢弃 0"), ("7.9 MB", "64 kbps"), ("16:04", "第 3 次会话")), locks=True),
    "error": dict(pill=("state_error", "错误"), badge="state_error_container", glyph="error",
                  glyph_c="state_error_dot", ring=None, title="端口 8125 被占用",
                  sub="可能有其他 WO Mic 或 MacMic 服务正在运行。关闭它后点击重试，服务也会每 3 秒自动重试。",
                  cta=("primary", "on_primary", "refresh", "重试"),
                  transport=("wifi", "—"), peer=("link_off", "未连接"),
                  stats=(("—", ""), ("—", ""), ("—", "")), locks=True),
    "permission": dict(pill=("state_idle", "未启动"), badge="surface_container_highest", glyph="mic_off",
                       glyph_c="on_surface_variant", ring=None, title="服务未启动",
                       sub="点击下方按钮启动 Mic 服务，Mac 端即可连接本机。",
                       cta=("primary", "on_primary", "play", "启动服务"),
                       transport=("wifi", "—"), peer=("link_off", "未连接"),
                       stats=(("—", ""), ("—", ""), ("—", "")), locks=False,
                       banner=("state_waiting_container", "state_waiting_on", "mic_off", "需要麦克风权限",
                               "MacMic 需要“麦克风”权限才能采集声音并发送给 Mac。", None, "授予权限")),
}

LOGS = {
    "waiting": [("12:08:03.114", "STATE LISTENING - listening TCP/UDP 8125, libopus 1.5.2", ""),
                ("12:08:03.102", "locks: wake=true wifiHighPerf=true wifiLowLatency=true", ""),
                ("12:07:59.870", "STATE STOPPED - service stopped", "")],
    "streaming": [("12:09:15.020", "#3 capture started: MIC 48k mono s16, 20 ms Opus 64 kbps", ""),
                  ("12:09:14.996", "STATE STREAMING 192.168.1.40:51234 - 0x67 start capture", ""),
                  ("12:09:14.981", "#3 0x66 codec=1 srIdx=3", ""),
                  ("12:09:14.975", "STATE CONNECTED 192.168.1.40:51234 - 0x65 OK", ""),
                  ("12:09:14.960", "STATE HANDSHAKING 192.168.1.40:51234", ""),
                  ("12:09:14.958", "accept 192.168.1.40:51234 -> new session #3", ""),
                  ("12:08:41.533", "#2 session end (192.168.1.40): EOF -> RST", "w"),
                  ("12:08:03.114", "STATE LISTENING - listening TCP/UDP 8125", "")],
    "error": [("12:10:22.410", "STATE ERROR - cannot bind port 8125: Address already in use (another WO Mic server running?) retry in 3 s", "e"),
              ("12:10:19.388", "STATE ERROR - cannot bind port 8125: Address already in use", "e"),
              ("12:10:19.350", "locks: wake=true wifiHighPerf=true wifiLowLatency=true", "")],
    "permission": [("12:06:40.002", "RECORD_AUDIO denied; cannot start", "w")],
}


def status_bar(c, light_icons=True):
    return f"""<div class="sb"><span>12:09</span><span class="r">{icon('signal')}{icon('wifi')}
<svg class="ic" viewBox="0 0 24 24" style="width:22px;height:16px"><rect x="1.5" y="6" width="18" height="12" rx="3" fill="none" stroke="currentColor" stroke-width="1.6"/><rect x="3.5" y="8" width="11" height="8" rx="1.5" fill="currentColor"/><rect x="20.5" y="10" width="2" height="4" rx="1" fill="currentColor"/></svg></span></div>"""


def app_badge():
    svg = open(os.path.join(DESIGN, "icons", "app_icon.svg")).read()
    return svg.replace('width="432" height="432"', 'class="badge" width="36" height="36"')


def screen(name, c):
    s = STATES[name]
    pk, plabel = s["pill"]
    banner = ""
    if s.get("banner"):
        bg, fg, ic, t, m, dismiss, act = s["banner"]
        dis = f'<span class="tbtn" style="color:{c[fg]}">{dismiss}</span>' if dismiss else ""
        banner = f"""<div class="banner" style="background:{c[bg]};color:{c[fg]}">
<span style="color:{c[fg]}">{icon(ic)}</span><div style="flex:1">
<div class="t-title-m" style="color:{c[fg]}">{t}</div>
<div class="t-body-m" style="color:{c[fg]};margin-top:4px">{m}</div>
<div class="acts">{dis}<span class="tbtn" style="color:{c[fg]}">{act}</span></div></div></div>"""
    ring = ""
    if s["ring"]:
        rc = c[s["ring"]]
        ring = (f'<div class="ring" style="width:120px;height:120px;border-color:{rc};opacity:.18"></div>'
                f'<div class="ring" style="width:102px;height:102px;border-color:{rc};opacity:.45"></div>')
    cbg, cfg, cic, ctext = s["cta"]
    tic, tval = s["transport"]
    pic, pval = s["peer"]
    peer_color = c["on_surface"] if pval != "未连接" else c["on_surface_variant"]
    tiles = "".join(f'<div class="tile"><div class="t-label-m">{lab}</div><div class="v">{v}</div><div class="s">{sub or "&nbsp;"}</div></div>'
                    for lab, (v, sub) in zip(("已发包", "已发送", "运行时长"), s["stats"]))
    lock_ok = s["locks"]
    chip_dot = c["success"] if lock_ok else c["outline"]
    keep_text = ("前台服务运行中，已持有 WakeLock 与 Wi-Fi 低延迟锁；锁屏通知可随时停止。" if lock_ok
                 else "启动后将以前台服务常驻，并在通知栏显示状态。")
    sw_on = name == "streaming"
    sw = (f'<div class="sw" style="background:{c["primary"]}"><div class="k" style="left:37px;width:22px;height:22px;background:{c["on_primary"]}"></div></div>'
          if sw_on else
          f'<div class="sw" style="background:{c["surface_container_highest"]};border:2px solid {c["outline"]}"><div class="k" style="left:13px;width:16px;height:16px;background:{c["outline"]}"></div></div>')
    logs = "".join(f'<div class="log {k}"><span class="t">{t[:8]}</span><span class="m">{m}</span></div>' for t, m, k in LOGS[name])
    addr = "192.168.1.23:8125"
    return f"""
{status_bar(c)}
<div class="wrap">
 <div class="header">{app_badge()}<div class="brand"><div class="t-title-l">MacMic</div><div class="t-body-s">手机麦克风服务端</div></div>
  <div class="pill" style="background:{c[pk + '_container']};color:{c[pk + '_on']}"><span class="dot" style="background:{c[pk + '_dot']}"></span>{plabel}</div></div>
 {banner}
 <div class="hero">
  <div class="bframe">{ring}<div class="hbadge" style="background:{c[s['badge']]};color:{c[s['glyph_c']]}">{icon(s['glyph'])}</div></div>
  <div class="h-title">{s['title']}</div>
  <div class="h-sub t-body-m">{s['sub']}</div>
  <div class="cta" style="background:{c[cbg]};color:{c[cfg]}">{icon(cic)}<span>{ctext}</span></div>
 </div>
 <div class="card">
  <div class="t-title-s" style="margin-bottom:4px">连接信息</div>
  <div class="row">{icon('signal')}<div class="col"><div class="t-body-s">本机地址</div><div class="addr">{addr}</div>
   <div class="t-body-s" style="margin-top:2px">USB：adb forward tcp:8125 tcp:8125</div></div><div class="copy">{icon('copy')}</div></div>
  <div class="div"></div>
  <div class="row">{icon(tic)}<div class="col"><div class="t-body-s">连接方式</div><div class="t-body-l">{tval}</div></div></div>
  <div class="div"></div>
  <div class="row"><span style="margin-right:16px;color:{c['success'] if pic == 'connected' else c['on_surface_variant']}">{icon(pic)}</span><div class="col"><div class="t-body-s">对端客户端</div><div class="t-body-l" style="color:{peer_color}">{pval}</div></div></div>
  <div class="stats">{tiles}</div>
 </div>
 <div class="card">
  <div class="t-title-s">后台保活</div>
  <div style="display:flex;margin-top:12px"><span style="margin-right:16px;color:{c['primary']}">{icon('lock')}</span><div style="flex:1">
   <div class="t-title-m">锁屏、熄屏后继续推流</div><div class="t-body-m" style="margin-top:4px">{keep_text}</div>
   <div class="chips"><span class="chip"><span class="dot" style="background:{chip_dot}"></span>WakeLock</span><span class="chip"><span class="dot" style="background:{chip_dot}"></span>Wi-Fi 锁</span></div>
   <div class="t-body-s" style="margin-top:8px;color:{c['outline']}">如被系统清理，请在 设置 › 应用 › 电池 中设为“不受限制”。</div></div></div>
  <div class="div" style="margin-top:12px"></div>
  <div class="row" style="margin-bottom:-8px"><span style="margin-right:16px;color:{c['on_surface_variant']}">{icon('screen_on')}</span><div class="col"><div class="t-body-l">保持屏幕常亮</div><div class="t-body-s">仅在本页面打开时生效，更耗电</div></div>{sw}</div>
 </div>
 <div class="card" style="padding-top:4px">
  <div class="loghead"><div class="t-title-s" style="flex:1">事件日志</div><span class="t-label-m" style="color:{c['outline']}">{len(LOGS[name])} 条</span><span class="copy" style="color:{c['on_surface_variant']};margin-right:-12px">{icon('copy')}</span></div>
  <div class="console">{logs}</div>
 </div>
 <div class="footer">v1.0.0 · 端口 8125 · libopus 1.5.2</div>
</div>
<div class="gnav"></div>
"""


def lockscreen(c):
    n = c
    return f"""
<div style="position:absolute;inset:0;background:#0B1110"></div>
<div style="position:absolute;inset:0;background:radial-gradient(120% 70% at 30% 10%,rgba(40,80,72,.55),transparent 60%),radial-gradient(90% 60% at 90% 90%,rgba(30,50,70,.5),transparent 60%)"></div>
<div style="position:relative">
{status_bar(c)}
<div style="display:flex;justify-content:center;margin-top:20px;color:{c['on_surface']}">{icon('lock')}</div>
<div style="text-align:center;margin-top:40px;font-size:96px;font-weight:300;line-height:1;letter-spacing:-2px;color:#E4ECE9">12<br>09</div>
<div style="text-align:center;margin-top:20px;font-size:16px;color:#C4CFCC">10月26日 周一</div>
<div style="margin:56px 12px 0;background:rgba(36,43,42,.96);border-radius:24px;padding:14px 16px 12px">
 <div style="display:flex;align-items:center;gap:8px;font-size:12px;color:{n['on_surface_variant']}">
  <span style="width:24px;height:24px;border-radius:50%;background:{n['primary']};color:{n['on_primary']};display:flex;align-items:center;justify-content:center">
   {icon('stat_mic').replace('class="ic"', 'class="ic" style="width:16px;height:16px"')}</span>
  <span style="color:{n['on_surface']};font-weight:500">MacMic Server</span><span>· 16 分钟</span>
  <span style="margin-left:auto;display:flex;align-items:center;gap:4px;color:{n['success']}">{icon('mic').replace('class="ic"', 'class="ic" style="width:14px;height:14px"')}</span></div>
 <div style="display:flex;align-items:center;min-height:48px;margin-top:6px;padding-left:32px">
  <span class="dot" style="background:{n['state_streaming_dot']};margin-right:12px"></span>
  <div style="flex:1;min-width:0"><div style="font-size:16px;font-weight:500;color:{n['on_surface']};white-space:nowrap;overflow:hidden;text-overflow:ellipsis">正在推流到 192.168.1.40</div>
   <div style="font-size:14px;color:{n['on_surface_variant']}">Wi-Fi · UDP · 已运行 16:04</div></div>
  <span style="height:32px;border-radius:16px;background:{n['secondary_container']};color:{n['on_secondary_container']};display:flex;align-items:center;gap:4px;padding:0 16px 0 12px;font-size:14px;font-weight:500;margin-left:12px">
   <svg viewBox="0 0 24 24" width="18" height="18"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor"/></svg>停止</span>
 </div>
</div>
<div style="margin:8px 12px 0;background:rgba(36,43,42,.80);border-radius:24px;padding:14px 16px;font-size:14px;color:{n['on_surface_variant']}">
 <span style="color:{n['on_surface']};font-weight:500">2 条通知</span> · 消息、日历</div>
</div>
<div style="position:absolute;left:0;right:0;bottom:56px;display:flex;justify-content:space-between;padding:0 40px;color:#C4CFCC">
 <span style="width:56px;height:56px;border-radius:50%;background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center">{icon('notification')}</span>
 <span style="width:56px;height:56px;border-radius:50%;background:rgba(255,255,255,.08);display:flex;align-items:center;justify-content:center">{icon('screen_on')}</span></div>
<div class="gnav" style="background:#fff"></div>
"""


def page(body, c, tall=False):
    css = CSS % dict(W=W, H=H, **c)
    cls = ' class="tall"' if tall else ""
    return f'<!doctype html><html{cls}><head><meta charset="utf-8"><style>{css}</style></head><body>{body}</body></html>'


def shoot(html, png, h=H):
    with tempfile.NamedTemporaryFile("w", suffix=".html", delete=False, encoding="utf-8") as f:
        f.write(html)
        src = f.name
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                    f"--force-device-scale-factor={SCALE}", f"--window-size={W},{h}",
                    f"--screenshot={png}", "file://" + src],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.unlink(src)
    print("wrote", os.path.relpath(png, DESIGN))


def main():
    os.makedirs(OUT, exist_ok=True)
    dark, light = colors("values-night"), colors("values")
    dark_full = {**colors("values"), **dark}
    light_full = light
    shots = [("01_waiting", "waiting", dark_full), ("02_streaming", "streaming", dark_full),
             ("03_error_port_busy", "error", dark_full), ("05_permission_needed", "permission", dark_full)]
    for fname, state, c in shots:
        shoot(page(screen(state, c), c), os.path.join(OUT, f"{fname}.png"))
    shoot(page(lockscreen(dark_full), dark_full), os.path.join(OUT, "04_lockscreen_notification.png"))
    shoot(page(screen("streaming", light_full), light_full), os.path.join(OUT, "06_streaming_light.png"))
    # full-length scroll capture of the streaming screen (all cards)
    full_h = 1780
    css_fix = "<style>html,body{height:%dpx!important}</style>" % full_h
    shoot(page(screen("streaming", dark_full), dark_full).replace("</head>", css_fix + "</head>"),
          os.path.join(OUT, "07_streaming_full_scroll.png"), h=full_h)


if __name__ == "__main__":
    main()
