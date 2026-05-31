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
ANDROID_NS = "http://schemas.android.com/apk/res/android"
A_FILL_COLOR = f"{{{ANDROID_NS}}}fillColor"
A_PATH_DATA = f"{{{ANDROID_NS}}}pathData"
A_WIDTH = f"{{{ANDROID_NS}}}width"
A_HEIGHT = f"{{{ANDROID_NS}}}height"
A_VP_WIDTH = f"{{{ANDROID_NS}}}viewportWidth"
A_VP_HEIGHT = f"{{{ANDROID_NS}}}viewportHeight"
A_SCALE_X = f"{{{ANDROID_NS}}}scaleX"
A_SCALE_Y = f"{{{ANDROID_NS}}}scaleY"
A_TRANS_X = f"{{{ANDROID_NS}}}translateX"
A_TRANS_Y = f"{{{ANDROID_NS}}}translateY"


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


def _apply_transform_attrs(transform_str: str, group_elem: ET.Element) -> None:
    if not transform_str:
        return
    # Parse translate(x, y) or translate(x)
    trans_match = re.search(r"translate\(([-\d.]+)(?:[\s,]+([-\d.]+))?\)", transform_str)
    if trans_match:
        group_elem.set(A_TRANS_X, trans_match.group(1))
        group_elem.set(A_TRANS_Y, trans_match.group(2) if trans_match.group(2) else "0")
        
    # Parse scale(sx, sy) or scale(s)
    scale_match = re.search(r"scale\(([-\d.]+)(?:[\s,]+([-\d.]+))?\)", transform_str)
    if scale_match:
        group_elem.set(A_SCALE_X, scale_match.group(1))
        group_elem.set(A_SCALE_Y, scale_match.group(2) if scale_match.group(2) else scale_match.group(1))


def _convert_node(svg_node: ET.Element, android_parent: ET.Element, inherited_fill: str = "#FF000000") -> None:
    for child in svg_node:
        tag = _strip_ns(child.tag)
        raw_fill = child.get("fill") or inherited_fill
        if raw_fill.lower() in ("none", "transparent"):
            fill_color = "@android:color/transparent"
        else:
            fill_color = _normalize_color(raw_fill)

        if tag == "g":
            group_elem = ET.SubElement(android_parent, "group")
            transform = child.get("transform", "")
            if transform:
                _apply_transform_attrs(transform, group_elem)
            _convert_node(child, group_elem, raw_fill if child.get("fill") else inherited_fill)
            
        elif tag == "path":
            d = child.get("d", "").strip()
            if d:
                transform = child.get("transform", "")
                if transform:
                    target_parent = ET.SubElement(android_parent, "group")
                    _apply_transform_attrs(transform, target_parent)
                else:
                    target_parent = android_parent
                    
                path_elem = ET.SubElement(target_parent, "path")
                path_elem.set(A_FILL_COLOR, fill_color)
                path_elem.set(A_PATH_DATA, d)


def svg_to_android_xml(svg_path: Path, xml_path: Path) -> None:
    ET.register_namespace('android', ANDROID_NS)
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

    # Build the Vector Drawable root tree structure
    vector_root = ET.Element("vector")
    vector_root.set(A_WIDTH, f"{dp_w}dp")
    vector_root.set(A_HEIGHT, f"{dp_h}dp")
    vector_root.set(A_VP_WIDTH, vp_w)
    vector_root.set(A_VP_HEIGHT, vp_h)

    # Process nodes hierarchically instead of flattening them
    _convert_node(root, vector_root)

    if len(vector_root) == 0:
        print(f"    Warning: no paths found in {svg_path.name}, skipping XML")
        return

    # Handle clean indents and format compilation
    out_tree = ET.ElementTree(vector_root)
    ET.indent(out_tree, space="    ")
    
    with open(xml_path, "wb") as f:
        f.write(b'<?xml version="1.0" encoding="utf-8"?>\n')
        out_tree.write(f, encoding="utf-8", xml_declaration=False)


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