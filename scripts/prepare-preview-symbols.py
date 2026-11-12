"""同梱の単純な自作SVG二点を、標準ライブラリだけで固定32px PNGへ変換する。"""
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/images/preview"
SCALE = 4

def inside(node, x, y):
    kind = node.tag.rsplit("}", 1)[-1]
    a = node.attrib
    if kind == "circle":
        return (x - float(a["cx"])) ** 2 + (y - float(a["cy"])) ** 2 <= float(a["r"]) ** 2
    if kind == "rect":
        left, top, width, height = (float(a[k]) for k in ("x", "y", "width", "height"))
        radius = float(a.get("rx", 0))
        if not (left <= x <= left + width and top <= y <= top + height):
            return False
        dx = max(left + radius - x, 0, x - (left + width - radius))
        dy = max(top + radius - y, 0, y - (top + height - radius))
        return dx * dx + dy * dy <= radius * radius
    if kind == "polygon":
        points = [tuple(map(float, pair.split(","))) for pair in a["points"].split()]
        hit = False
        for (ax, ay), (bx, by) in zip(points, points[1:] + points[:1]):
            if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
                hit = not hit
        return hit
    raise ValueError("未対応の自作SVG要素: " + kind)

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

def render(path):
    svg = ET.parse(path).getroot()
    assert svg.attrib["viewBox"] == "0 0 32 32"
    rows = []
    for py in range(32):
        row = bytearray([0])
        for px in range(32):
            samples = []
            for sy in range(SCALE):
                for sx in range(SCALE):
                    x, y = px + (sx + .5) / SCALE, py + (sy + .5) / SCALE
                    color = None
                    for node in svg:
                        if inside(node, x, y):
                            fill = node.attrib["fill"].removeprefix("#")
                            color = tuple(int(fill[i:i + 2], 16) for i in (0, 2, 4))
                    if color is not None:
                        samples.append(color)
            if samples:
                rgb = tuple(round(sum(c[i] for c in samples) / len(samples)) for i in range(3))
                row.extend((*rgb, round(255 * len(samples) / SCALE ** 2)))
            else:
                row.extend((0, 0, 0, 0))
        rows.append(bytes(row))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 32, 32, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
    path.with_suffix(".png").write_bytes(png)

if __name__ == "__main__":
    for name in ("item.svg", "missing.svg"):
        render(ROOT / name)
