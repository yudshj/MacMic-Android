#!/usr/bin/env python3
"""WCAG 2.1 contrast check for the MacMic colour roles (both schemes).

    python3 phone-app/design/tools/check_contrast.py      # exit 1 if any pair fails
"""
import os
import re
import sys

DESIGN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (foreground, background, minimum) — 4.5 body text, 3.0 large text / UI glyphs
PAIRS = [
    ("on_surface", "surface", 4.5), ("on_surface", "surface_container", 4.5),
    ("on_surface", "surface_container_high", 4.5), ("on_surface_variant", "surface", 4.5),
    ("on_surface_variant", "surface_container", 4.5), ("on_surface_variant", "surface_container_high", 4.5),
    ("on_surface_variant", "surface_container_highest", 4.5),
    ("primary", "surface", 4.5), ("primary", "surface_container", 4.5), ("primary", "surface_container_high", 4.5),
    ("on_primary", "primary", 4.5), ("on_primary_container", "primary_container", 4.5),
    ("on_secondary_container", "secondary_container", 4.5), ("on_tertiary_container", "tertiary_container", 4.5),
    ("tertiary", "surface_container", 4.5),
    ("error", "surface_container", 4.5), ("on_error", "error", 4.5), ("on_error_container", "error_container", 4.5),
    ("success", "surface_container", 4.5), ("on_success_container", "success_container", 4.5),
    ("warning", "surface_container", 4.5), ("on_warning_container", "warning_container", 4.5),
    ("state_idle_on", "state_idle_container", 4.5), ("state_waiting_on", "state_waiting_container", 4.5),
    ("state_connected_on", "state_connected_container", 4.5), ("state_streaming_on", "state_streaming_container", 4.5),
    ("state_error_on", "state_error_container", 4.5),
    ("state_idle_dot", "state_idle_container", 3.0), ("state_waiting_dot", "state_waiting_container", 3.0),
    ("state_connected_dot", "state_connected_container", 3.0), ("state_streaming_dot", "state_streaming_container", 3.0),
    ("state_error_dot", "state_error_container", 3.0),
    ("log_text", "surface_container_lowest", 4.5), ("log_time", "surface_container_lowest", 4.5),
    ("log_warn", "surface_container_lowest", 4.5), ("log_error", "surface_container_lowest", 4.5),
    ("outline", "surface_container", 3.0), ("on_cta_stop", "cta_stop_container", 4.5),
]


def load(path):
    out = {}
    for name, val in re.findall(r'<color name="(\w+)">#([0-9A-Fa-f]{8})</color>', open(path).read()):
        out[name] = tuple(int(val[i:i + 2], 16) for i in (2, 4, 6))
    return out


def lum(rgb):
    def ch(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = map(ch, rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def main():
    bad = 0
    for scheme in ("values", "values-night"):
        c = load(os.path.join(DESIGN, "res", scheme, "colors.xml"))
        print(f"== {scheme}")
        for fg, bg, need in PAIRS:
            r = ratio(c[fg], c[bg])
            ok = r >= need
            bad += not ok
            print(f"  {'OK ' if ok else 'FAIL'} {r:5.1f}:1 (>= {need}) {fg} on {bg}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
