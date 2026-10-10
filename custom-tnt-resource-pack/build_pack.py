#!/usr/bin/env python3
"""Build the 1.8-compatible custom TNT resource pack and web download."""

from pathlib import Path
import struct
import zlib
import zipfile

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets" / "minecraft" / "textures"
WEB_PACK = ROOT.parent / "web" / "custom-tnt-pack.zip"


def png(path, rows, palette):
    height = len(rows)
    width = len(rows[0]) if rows else 0
    if not width or any(len(row) != width for row in rows):
        raise ValueError(f"{path} must contain non-empty, equal-width rows")
    raw = b"".join(
        b"\0" + b"".join(bytes(palette[pixel]) for pixel in row)
        for row in rows
    )

    def chunk(name, data):
        content = name + data
        return struct.pack(">I", len(data)) + content + struct.pack(">I", zlib.crc32(content) & 0xFFFFFFFF)

    image = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">2I5B", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(image)


def tnt_block_icon(body, shadow, highlight, fuse):
    rows = [["." for _ in range(32)] for _ in range(32)]

    for y in range(3, 16):
        half_width = 12 - abs(y - 9) * 2
        for x in range(16 - half_width, 17 + half_width):
            edge = abs(x - 16) >= half_width - 1
            rows[y][x] = "h" if y < 8 or edge else "b"

    for y in range(9, 29):
        for x in range(4, 28):
            rows[y][x] = "s" if x == 4 or x >= 26 else "b"
    for y in range(10, 14):
        for x in range(6, 26):
            if (x + y) % 7 == 0:
                rows[y][x] = "h"

    for y in range(14, 26):
        for x in range(4, 28):
            rows[y][x] = "w" if x < 27 else "s"
    for x in range(4, 28):
        rows[13][x] = "s"
        rows[26][x] = "s"

    glyphs = {"T": ("111", "010", "010"), "N": ("101", "111", "101")}
    glyph_width = 3
    scale = 2
    text_width = (glyph_width * 3 + 2) * scale
    text_left = (32 - text_width) // 2
    for index, letter in enumerate("TNT"):
        for y, line in enumerate(glyphs[letter]):
            for x, pixel in enumerate(line):
                if pixel == "1":
                    for dy in range(scale):
                        for dx in range(scale):
                            rows[15 + y * scale + dy][text_left + (index * 4 + x) * scale + dx] = "k"

    for y in range(0, 5):
        rows[y][15] = "f"
        rows[y][16] = "f" if y < 3 else "y"
    rows[0][16] = "y"
    rows[1][17] = "y"
    rows[2][14] = "y"

    palette = {
        ".": (0, 0, 0, 0),
        "b": body,
        "s": shadow,
        "h": highlight,
        "w": (245, 238, 218, 255),
        "k": (37, 34, 31, 255),
        "f": fuse,
        "y": (255, 222, 73, 255),
    }
    return ["".join(row) for row in rows], palette


def build():
    side_rows = [["r" for _ in range(16)] for _ in range(16)]
    for y in range(4, 11):
        side_rows[y] = ["w" for _ in range(16)]
    glyphs = {"T": ("111", "010", "010"), "N": ("101", "111", "101")}
    for index, letter in enumerate("TNT"):
        for y, line in enumerate(glyphs[letter]):
            for x, pixel in enumerate(line):
                if pixel == "1":
                    side_rows[6 + y][2 + index * 4 + x] = "k"
    block_palette = {
        "r": (191, 38, 32, 255),
        "d": (92, 25, 24, 255),
        "w": (224, 218, 195, 255),
        "b": (45, 39, 34, 255),
        "y": (217, 170, 74, 255),
        "k": (35, 31, 29, 255),
    }
    png(ASSETS / "blocks" / "tnt_side.png", ["".join(row) for row in side_rows], block_palette)

    top = [["r" for _ in range(16)] for _ in range(16)]
    top_palette = dict(block_palette)
    for y in range(4, 12):
        for x in range(4, 12):
            top[y][x] = "w"
    for x, y, color in (
        (7, 4, "b"), (8, 4, "b"), (7, 5, "b"), (8, 5, "b"),
        (7, 6, "y"), (8, 6, "y"), (6, 7, "y"), (9, 7, "y"),
        (7, 8, "y"), (8, 8, "y"), (7, 9, "b"), (8, 9, "b"),
        (7, 10, "b"), (8, 10, "b"),
    ):
        top[y][x] = color
    png(ASSETS / "blocks" / "tnt_top.png", ["".join(row) for row in top], top_palette)
    bottom_rows = ["".join("d" if (x + y) % 4 == 0 else "r" for x in range(16))
                   for y in range(16)]
    png(ASSETS / "blocks" / "tnt_bottom.png", bottom_rows, block_palette)

    item_colors = {
        "items/tnt.png": ((191, 38, 32, 255), (92, 25, 24, 255), (235, 88, 65, 255), (92, 25, 24, 255)),
        "items/gold_ingot.png": ((218, 153, 42, 255), (122, 79, 23, 255), (255, 222, 105, 255), (122, 79, 23, 255)),
        "items/fireball.png": ((238, 102, 24, 255), (133, 45, 15, 255), (255, 188, 47, 255), (133, 45, 15, 255)),
        "items/slimeball.png": ((74, 188, 43, 255), (39, 114, 34, 255), (177, 247, 103, 255), (39, 114, 34, 255)),
        "items/ender_pearl.png": ((39, 39, 46, 255), (12, 12, 18, 255), (232, 232, 238, 255), (12, 12, 18, 255)),
    }
    for name, colors in item_colors.items():
        rows, palette = tnt_block_icon(*colors)
        png(ASSETS / name, rows, palette)

    with zipfile.ZipFile(WEB_PACK, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path, name in [
            (ROOT / "pack.mcmeta", "pack.mcmeta"),
            *[
                (path, path.relative_to(ROOT).as_posix())
                for path in sorted((ROOT / "assets").rglob("*.png"))
            ],
        ]:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    print(f"Created {WEB_PACK} ({WEB_PACK.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
