#!/usr/bin/env python3
"""Convert PNG icons to SVG (via vtracer) and Android Vector Drawable XML.

Usage: python vectorize.py <folder>

For each PNG without a matching SVG, vectorizes it.
For each SVG without a matching XML, converts it to an Android vector drawable.
"""

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    import vtracer
except ImportError:
    sys.exit("vtracer not found. Install with: pip install vtracer")

SVG_NS = "http://www.w3.org/2000/svg"


def png_to_svg(png_path: Path, svg_path: Path, colormode: str = "color") -> None:
    # Keyword args crash with Python 3.14 due to a PyO3 ABI bug in vtracer — use positional.
    # Arg order: image_path, out_path, colormode, hierarchical, mode,
    #            filter_speckle, color_precision, layer_difference,
    #            corner_threshold, length_threshold, max_iterations,
    #            splice_threshold, path_precision
    vtracer.convert_image_to_svg_py(
        str(png_path), str(svg_path),
        colormode, "stacked", "spline",
        4, 6, 16, 60, 4.0, 10, 45, 8,
    )


def _strip_ns(tag: str) -> str:
    return tag.split("}")[-1] if "}" in tag else tag


def _parse_dim(value: str, fallback: float = 24.0) -> float:
    m = re.match(r"([\d.]+)", value or "")
    return float(m.group(1)) if m else fallback


def _normalize_color(color: str) -> str:
    color = color.strip()
    if color.startswith("#"):
        h = color[1:]
        if len(h) == 3:
            h = "".join(c * 2 for c in h)
        if len(h) == 6:
            return f"#FF{h.upper()}"
        if len(h) == 8:
            return f"#{h.upper()}"
    m = re.match(r"rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)", color)
    if m:
        r, g, b = int(m.group(1)), int(m.group(2)), int(m.group(3))
        a = int(float(m.group(4)) * 255) if m.group(4) else 255
        return f"#{a:02X}{r:02X}{g:02X}{b:02X}"
    return "#FF000000"


def _collect_paths(node: ET.Element, inherited_fill: str = "#FF000000") -> list[tuple[str, str]]:
    results: list[tuple[str, str]] = []
    for child in node:
        tag = _strip_ns(child.tag)
        raw_fill = child.get("fill") or inherited_fill
        if raw_fill.lower() in ("none", "transparent"):
            fill_color = "@android:color/transparent"
        else:
            fill_color = _normalize_color(raw_fill)

        if tag == "path":
            d = child.get("d", "").strip()
            if d:
                results.append((d, fill_color))
        elif tag in ("g", "svg"):
            results.extend(_collect_paths(child, raw_fill if child.get("fill") else inherited_fill))
    return results


def svg_to_android_xml(svg_path: Path, xml_path: Path) -> None:
    tree = ET.parse(svg_path)
    root = tree.getroot()

    viewbox = root.get("viewBox", "")
    if viewbox:
        parts = re.split(r"[\s,]+", viewbox.strip())
        vp_w, vp_h = parts[2], parts[3]
    else:
        vp_w = str(_parse_dim(root.get("width", ""), 24))
        vp_h = str(_parse_dim(root.get("height", ""), 24))

    try:
        dp_w = int(float(vp_w))
        dp_h = int(float(vp_h))
    except ValueError:
        dp_w, dp_h = 24, 24

    paths = _collect_paths(root)
    if not paths:
        print(f"    Warning: no paths found in {svg_path.name}, skipping XML")
        return

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{dp_w}dp"',
        f'    android:height="{dp_h}dp"',
        f'    android:viewportWidth="{vp_w}"',
        f'    android:viewportHeight="{vp_h}">',
    ]

    for path_data, fill_color in paths:
        lines.append(
            f'    <path\n'
            f'        android:fillColor="{fill_color}"\n'
            f'        android:pathData="{path_data}"/>'
        )

    lines.append("</vector>")
    xml_path.write_text("\n".join(lines), encoding="utf-8")


def process_folder(folder: Path) -> None:
    pngs = sorted(folder.glob("*.png"))
    if not pngs:
        print(f"No PNG files found in {folder}")
        return

    for png in pngs:
        svg = png.with_suffix(".svg")
        xml = png.with_suffix(".xml")

        if svg.exists():
            print(f"  {png.name}: SVG already exists, skipping vectorization")
        else:
            print(f"  {png.name}: vectorizing -> {svg.name}")
            png_to_svg(png, svg)

        if xml.exists():
            print(f"  {svg.name}: XML already exists, skipping conversion")
        else:
            print(f"  {svg.name}: converting -> {xml.name}")
            svg_to_android_xml(svg, xml)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(f"Usage: {sys.argv[0]} <folder>")

    folder = Path(sys.argv[1])
    if not folder.is_dir():
        sys.exit(f"Not a directory: {folder}")

    print(f"Processing: {folder}")
    process_folder(folder)
    print("Done.")


if __name__ == "__main__":
    main()
