# Icon licence / provenance

Every glyph in `design/icons/*.svg` and `design/res/drawable/ic_*.xml` (plus the launcher layers
`ic_launcher_*.xml`) is **original geometry drawn for MacMic** in `design/tools/gen_icons.py`:
24×24 grid, 2-unit round-cap/round-join stroke, built from plain lines, arcs and rounded rects.

- **No Material Symbols / Material Icons path data is used**, so no Apache-2.0 attribution is required.
- No brand assets (Apple, Google, WO Mic, …) were copied. The USB trident and Wi-Fi arcs are generic
  pictograms redrawn from scratch.
- The icons are released under the same licence as this repository (see `/LICENSE`).

To change a glyph, edit `ICONS` in `design/tools/gen_icons.py` and re-run
`python3 phone-app/design/tools/gen_icons.py`; it rewrites both the SVG and the VectorDrawable.
