#!/usr/bin/env python3
"""Generate the MacMic phone icon set as SVG (design/icons) + Android VectorDrawable (design/res/drawable).

All glyphs are ORIGINAL geometry drawn for this project on a 24x24 grid with a 2-unit
round-cap / round-join stroke (no Material Symbols paths are used).

    python3 phone-app/design/tools/gen_icons.py
"""
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
DESIGN = os.path.dirname(HERE)
SVG_DIR = os.path.join(DESIGN, "icons")
VD_DIR = os.path.join(DESIGN, "res", "drawable")
MIPMAP_DIR = os.path.join(DESIGN, "res", "mipmap-anydpi-v26")


def circle(cx, cy, r):
    """Closed circle as two arcs (VectorDrawable has no <circle>)."""
    return (f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0"
            f"a{r:g},{r:g} 0 1,0 {-2 * r:g},0z")


def rrect(x, y, w, h, r):
    return (f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0,1 {r:g},{r:g}"
            f"v{h - 2 * r:g}a{r:g},{r:g} 0 0,1 {-r:g},{r:g}"
            f"h{-(w - 2 * r):g}a{r:g},{r:g} 0 0,1 {-r:g},{-r:g}"
            f"v{-(h - 2 * r):g}a{r:g},{r:g} 0 0,1 {r:g},{-r:g}z")


def dot(x, y):
    """Filled dot r=1.25 (reads as a 2.5dp point next to 2dp strokes)."""
    return circle(x, y, 1.25)


def gear():
    pts = []
    n, r_out, r_in = 8, 9.0, 6.9
    for i in range(n):
        a = i * 2 * math.pi / n
        half_top, half_root = math.radians(9.5), math.radians(15.5)
        for ang, r in ((a - half_root, r_in), (a - half_top, r_out),
                       (a + half_top, r_out), (a + half_root, r_in)):
            pts.append((12 + r * math.cos(ang), 12 + r * math.sin(ang)))
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "z"
    return d


MIC_BODY = rrect(9, 3, 6, 11, 3)
MIC_STAND = "M5.5,11a6.5,6.5 0 0,0 13,0M12,17.5V21M8.5,21h7"

# name -> list of (pathData, "stroke"|"fill")
ICONS = {
    "mic": [(MIC_BODY, "stroke"), (MIC_STAND, "stroke")],
    "mic_off": [
        # body broken where the slash crosses, plus the slash itself
        ("M15,9.6V6a3,3 0 0,0 -5.8,-1.1", "stroke"),
        ("M9,9v2a3,3 0 0,0 4.6,2.5", "stroke"),
        ("M18.5,11a6.5,6.5 0 0,1 -0.9,3.3M16.3,16a6.5,6.5 0 0,1 -10.8,-5", "stroke"),
        ("M12,17.5V21M8.5,21h7", "stroke"),
        ("M4,4L20,20", "stroke"),
    ],
    "play": [("M8,5.6v12.8a0.8,0.8 0 0,0 1.2,0.7l10,-6.4a0.8,0.8 0 0,0 0,-1.4l-10,-6.4A0.8,0.8 0 0,0 8,5.6z", "stroke")],
    "stop": [(rrect(6, 6, 12, 12, 2), "stroke")],
    "listening": [
        (circle(12, 12, 2), "stroke"),
        ("M8,8a5.66,5.66 0 0,0 0,8M16,8a5.66,5.66 0 0,1 0,8", "stroke"),
        ("M5,5a9.9,9.9 0 0,0 0,14M19,5a9.9,9.9 0 0,1 0,14", "stroke"),
    ],
    "wifi": [
        ("M2.5,9.2a13.5,13.5 0 0,1 19,0", "stroke"),
        ("M5.6,12.6a9,9 0 0,1 12.8,0", "stroke"),
        ("M8.8,15.9a4.6,4.6 0 0,1 6.4,0", "stroke"),
        (dot(12, 19.3), "fill"),
    ],
    "usb": [
        ("M12,6V16.5", "stroke"),
        ("M10.2,6.2L12,3.2l1.8,3z", "fill"),
        ("M10.2,6.2L12,3.2l1.8,3z", "stroke"),
        ("M12,14.2L8,12V10.3", "stroke"),
        (circle(8, 8.8, 1.5), "stroke"),
        ("M12,12.2l4,-2.2V8.8", "stroke"),
        ("M14.8,6.3h2.4v2.5h-2.4z", "stroke"),
        (circle(12, 18.6, 2), "stroke"),
    ],
    "connected": [(circle(12, 12, 9), "stroke"), ("M8,12.3l2.7,2.7l5.3,-5.5", "stroke")],
    "link_off": [
        ("M9.5,8H8a4,4 0 0,0 0,8h2.5", "stroke"),
        ("M14.5,8H16a4,4 0 0,1 2.9,6.8", "stroke"),
        ("M8.5,12H10", "stroke"),
        ("M3.5,3.5L20.5,20.5", "stroke"),
    ],
    "copy": [
        ("M15,5.5V4.5A1.5,1.5 0 0,0 13.5,3h-8A1.5,1.5 0 0,0 4,4.5v8A1.5,1.5 0 0,0 5.5,14H6.5", "stroke"),
        (rrect(9.5, 8, 10.5, 12.5, 2), "stroke"),
    ],
    "refresh": [
        ("M19.5,12a7.5,7.5 0 1,1 -2.2,-5.3", "stroke"),
        ("M18.5,3.5v4h-4", "stroke"),
    ],
    "lock": [
        (rrect(5, 10.5, 14, 10.5, 2), "stroke"),
        ("M8,10.5V7.5a4,4 0 0,1 8,0v3", "stroke"),
        ("M12,14.8v2", "stroke"),
    ],
    "notification": [
        ("M6,16.5V11a6,6 0 0,1 12,0v5.5l1.5,1.5H4.5z", "stroke"),
        ("M10,21h4", "stroke"),
        ("M12,3.5V5", "stroke"),
    ],
    "signal": [("M5,19v-3M10,19v-6.5M15,19v-10M20,19V4", "stroke")],
    "chevron": [("M9,6l6,6l-6,6", "stroke")],
    "error": [(circle(12, 12, 9), "stroke"), ("M12,7.5v5.5", "stroke"), (dot(12, 16.6), "fill")],
    "info": [(circle(12, 12, 9), "stroke"), ("M12,11v5.5", "stroke"), (dot(12, 7.6), "fill")],
    "settings": [(gear(), "stroke"), (circle(12, 12, 2.8), "stroke")],
    "timer": [(circle(12, 13.5, 7.5), "stroke"), ("M12,10v3.5l2.5,1.5", "stroke"), ("M9.5,2.5h5", "stroke")],
    "upload": [("M12,19V5.5M6.5,11L12,5.5L17.5,11", "stroke")],
    "screen_on": [(rrect(7, 2.5, 10, 19, 2.5), "stroke"), ("M10.5,18.5h3", "stroke")],
    "shield": [("M12,3l7,3v5.5c0,4.5 -3,8 -7,9.5c-4,-1.5 -7,-5 -7,-9.5V6z", "stroke"), ("M9,12l2.2,2.2l4,-4.2", "stroke")],
}

# ic_mic doubles as MicService's notification small icon (R.drawable.ic_mic) -> no theme tint
NO_THEME_TINT = {"mic"}

SVG_TMPL = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
{body}
</svg>
"""

VD_TMPL = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by design/tools/gen_icons.py — original MacMic glyph, 24dp grid, 2dp stroke. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24"
    android:tint="?android:attr/colorControlNormal"
    android:autoMirrored="{mirror}">
{body}
</vector>
"""


def svg_paths(parts):
    out = []
    for d, mode in parts:
        if mode == "fill":
            out.append(f'  <path d="{d}" fill="currentColor" stroke="none"/>')
        else:
            out.append(f'  <path d="{d}"/>')
    return "\n".join(out)


def vd_paths(parts, color="#FFFFFFFF", width="2"):
    out = []
    for d, mode in parts:
        if mode == "fill":
            out.append(f'    <path android:fillColor="{color}" android:pathData="{d}" />')
        else:
            out.append(
                f'    <path android:pathData="{d}"\n'
                f'        android:strokeColor="{color}" android:strokeWidth="{width}"\n'
                f'        android:strokeLineCap="round" android:strokeLineJoin="round" />')
    return "\n".join(out)


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


# ---------------------------------------------------------------- app / notification icons
APP_BG = "#0F2B27"        # deep teal, matches dark surface family
APP_FG = "#6FD8C4"        # brand primary (dark-theme tone)
APP_FG_SOFT = "#3E8F80"

# 108x108 canvas, safe zone = circle r33 around (54,54). Everything below stays inside r<=27.
APP_FG_PARTS = [
    # capsule (filled)
    (rrect(46, 28, 16, 28, 8), "fill"),
    # stand arc + stem + base
    ("M37,50a17,17 0 0,0 34,0", "stroke5"),
    ("M54,67V76M46,77h16", "stroke5"),
]
APP_WAVES = [("M30,40a19,19 0 0,0 0,20M78,40a19,19 0 0,1 0,20", "wave")]


def app_paths(parts, color, soft):
    out = []
    for d, mode in parts:
        if mode == "fill":
            out.append(f'    <path android:fillColor="{color}" android:pathData="{d}" />')
        elif mode == "stroke5":
            out.append(f'    <path android:pathData="{d}" android:strokeColor="{color}" android:strokeWidth="5"\n'
                       f'        android:strokeLineCap="round" android:strokeLineJoin="round" />')
        elif mode == "wave":
            out.append(f'    <path android:pathData="{d}" android:strokeColor="{soft}" android:strokeWidth="4"\n'
                       f'        android:strokeLineCap="round" />')
    return "\n".join(out)


def app_svg_paths(parts, color, soft):
    out = []
    for d, mode in parts:
        if mode == "fill":
            out.append(f'  <path d="{d}" fill="{color}"/>')
        elif mode == "stroke5":
            out.append(f'  <path d="{d}" fill="none" stroke="{color}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            out.append(f'  <path d="{d}" fill="none" stroke="{soft}" stroke-width="4" stroke-linecap="round"/>')
    return "\n".join(out)


def vd108(body, comment):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} 108dp adaptive-icon layer; artwork kept inside the 66dp safe circle. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
"""


def main():
    os.makedirs(SVG_DIR, exist_ok=True)
    os.makedirs(VD_DIR, exist_ok=True)
    os.makedirs(MIPMAP_DIR, exist_ok=True)
    for name, parts in ICONS.items():
        write(os.path.join(SVG_DIR, f"{name}.svg"), SVG_TMPL.format(body=svg_paths(parts)))
        mirror = "true" if name in ("chevron",) else "false"
        tmpl = VD_TMPL
        if name in NO_THEME_TINT:   # used as notification small icon: plain white, system applies its own tint
            tmpl = VD_TMPL.replace('    android:tint="?android:attr/colorControlNormal"\n', '')
        write(os.path.join(VD_DIR, f"ic_{name}.xml"), tmpl.format(body=vd_paths(parts), mirror=mirror))

    # status-bar / notification small icon: solid white silhouette (system uses alpha only)
    stat = [(MIC_BODY, "fill"), (MIC_BODY, "stroke"), (MIC_STAND, "stroke")]
    write(os.path.join(VD_DIR, "ic_stat_mic.xml"), VD_TMPL.replace(
        '    android:tint="?android:attr/colorControlNormal"\n', '').format(body=vd_paths(stat), mirror="false"))
    write(os.path.join(SVG_DIR, "stat_mic.svg"), SVG_TMPL.format(body=svg_paths(stat).replace(
        f'<path d="{MIC_BODY}"/>', f'<path d="{MIC_BODY}" fill="currentColor"/>', 1)))

    # adaptive launcher icon
    fg_body = app_paths(APP_WAVES + APP_FG_PARTS, APP_FG, APP_FG_SOFT)
    write(os.path.join(VD_DIR, "ic_launcher_foreground.xml"), vd108(fg_body, "MacMic launcher foreground."))
    write(os.path.join(VD_DIR, "ic_launcher_background.xml"), vd108(
        f'    <path android:fillColor="{APP_BG}" android:pathData="M0,0h108v108h-108z" />',
        "MacMic launcher background (solid deep teal)."))
    mono_body = app_paths(APP_WAVES + APP_FG_PARTS, "#FFFFFFFF", "#FFFFFFFF")
    write(os.path.join(VD_DIR, "ic_launcher_monochrome.xml"), vd108(mono_body, "Themed-icon (Android 13+) monochrome layer."))
    write(os.path.join(MIPMAP_DIR, "ic_launcher.xml"), """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
""")
    write(os.path.join(SVG_DIR, "app_icon.svg"), f"""<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">
  <!-- adaptive icon preview: background + foreground, masked to the launcher circle (r=54). -->
  <defs><clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath></defs>
  <g clip-path="url(#m)">
  <rect width="108" height="108" fill="{APP_BG}"/>
{app_svg_paths(APP_WAVES + APP_FG_PARTS, APP_FG, APP_FG_SOFT)}
  </g>
</svg>
""")
    write(os.path.join(SVG_DIR, "app_icon_foreground.svg"), f"""<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <!-- 108dp foreground layer; dashed circle = 66dp safe zone (guide only). -->
  <circle cx="54" cy="54" r="33" fill="none" stroke="#888" stroke-width="0.5" stroke-dasharray="2 2"/>
{app_svg_paths(APP_WAVES + APP_FG_PARTS, APP_FG, APP_FG_SOFT)}
</svg>
""")
    print(f"wrote {len(ICONS)} glyphs + stat/launcher icons")


if __name__ == "__main__":
    main()
