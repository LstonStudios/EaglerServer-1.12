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


def _noise(x, y, seed):
    value = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    value = ((value ^ (value >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((value ^ (value >> 16)) & 255) / 255.0


def _mix(color, factor):
    return tuple(max(0, min(255, int(channel * factor))) for channel in color[:3])


GLYPHS = {
    "T": ("111", "010", "010", "010", "010"),
    "N": ("101", "111", "111", "111", "101"),
}


def face_texture(kind, body, band, ink, spark, seed):
    size = 16
    pixels = [[body for _ in range(size)] for _ in range(size)]
    for y in range(size):
        for x in range(size):
            shade = 0.88 + 0.24 * _noise(x, y, seed)
            if kind == "side" and x % 4 == 0:
                shade *= 0.78
            pixels[y][x] = _mix(body, shade)
    if kind == "side":
        for y in range(4, 12):
            for x in range(size):
                shade = 0.93 + 0.1 * _noise(x, y, seed + 7)
                pixels[y][x] = _mix(band, shade)
        for x in range(size):
            pixels[3][x] = _mix(body, 0.55)
            pixels[12][x] = _mix(body, 0.55)
            pixels[4][x] = _mix(band, 0.82)
            pixels[11][x] = _mix(band, 0.82)
        for index, letter in enumerate("TNT"):
            for gy, line in enumerate(GLYPHS[letter]):
                for gx, bit in enumerate(line):
                    if bit == "1":
                        pixels[5 + gy][2 + index * 4 + gx] = ink
        for y in range(size):
            pixels[y][0] = _mix(body, 0.62)
            pixels[y][15] = _mix(body, 0.62)
    else:
        for y in range(size):
            for x in range(size):
                if x in (0, 15) or y in (0, 15):
                    pixels[y][x] = _mix(body, 0.6)
        for y in range(3, 13):
            for x in range(3, 13):
                if (x - 7.5) ** 2 + (y - 7.5) ** 2 <= 20:
                    pixels[y][x] = _mix(band, 0.95 + 0.08 * _noise(x, y, seed + 3))
        for y in range(7, 13):
            pixels[y][7] = ink
            pixels[y][8] = ink
        pixels[6][8] = spark
        pixels[5][9] = spark
        pixels[6][9] = _mix(spark, 0.8)
    return pixels


def cube_icon(body, band, ink, spark, seed=1):
    size = 32
    top_face = face_texture("top", body, band, ink, spark, seed)
    side_face = face_texture("side", body, band, ink, spark, seed + 11)
    image = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]
    for y in range(size):
        for x in range(size):
            px, py = x + 0.5, y + 0.5
            hit = None
            a = ((px - 16) * 7 + (py - 3) * 13) / 182.0
            b = ((py - 3) * 13 - (px - 16) * 7) / 182.0 if False else None
            # top face: p = T + u*(13,7) + v*(-13,7)
            det = 13 * 7 - (-13) * 7
            u = ((px - 16) * 7 - (-13) * (py - 3)) / det
            v = (13 * (py - 3) - 7 * (px - 16)) / det
            if 0 <= u < 1 and 0 <= v < 1:
                color = top_face[int(v * 16)][int(u * 16)]
                factor = 1.18
                edge = min(u, v, 1 - u, 1 - v) < 0.06
                hit = _mix(color, factor * (0.7 if edge else 1.0))
            else:
                # left face: p = (3,10) + u*(13,7) + v*(0,16)
                u = (px - 3) / 13.0
                v = (py - 10 - u * 7) / 16.0
                if 0 <= u < 1 and 0 <= v < 1:
                    color = side_face[int(v * 16)][int(u * 16)]
                    edge = u < 0.05 or v > 0.94
                    hit = _mix(color, 0.82 * (0.7 if edge else 1.0))
                else:
                    # right face: p = (16,17) + u*(13,-7) + v*(0,16)
                    u = (px - 16) / 13.0
                    v = (py - 17 + u * 7) / 16.0
                    if 0 <= u < 1 and 0 <= v < 1:
                        color = side_face[int(v * 16)][int((1 - u) * 16) if False else int(u * 16)]
                        edge = u > 0.95 or v > 0.94
                        hit = _mix(color, 0.58 * (0.7 if edge else 1.0))
            if hit:
                image[y][x] = hit + (255,)
    # fuse rope and spark above the top face
    for y in range(0, 4):
        image[y][16] = _mix(ink, 1.4) + (255,)
    image[0][17] = spark + (255,)
    image[0][15] = _mix(spark, 0.9) + (255,)
    image[1][16] = _mix(spark, 0.8) + (255,)
    return image


def png_rgba(path, image):
    height = len(image)
    width = len(image[0])
    raw = b"".join(
        b"\0" + b"".join(bytes(pixel) for pixel in row) for row in image
    )

    def chunk(name, data):
        content = name + data
        return struct.pack(">I", len(data)) + content + struct.pack(">I", zlib.crc32(content) & 0xFFFFFFFF)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">2I5B", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def block_face_png(path, pixels):
    png_rgba(path, [[pixel + (255,) for pixel in row] for row in pixels])


THEMES = {
    "nuke": ((196, 40, 34), (240, 232, 210), (34, 28, 26), (255, 214, 64)),
    "mining": ((214, 160, 38), (236, 236, 236), (52, 46, 40), (255, 244, 170)),
    "fire": ((236, 98, 20), (255, 232, 170), (80, 24, 10), (255, 244, 120)),
    "slime": ((70, 190, 48), (226, 250, 190), (22, 80, 28), (200, 255, 120)),
    "xray": ((46, 112, 240), (200, 232, 255), (14, 40, 110), (120, 255, 255)),
    "rdm": ((226, 70, 200), (255, 240, 120), (40, 20, 60), (90, 255, 220)),
    "mob": ((64, 140, 60), (214, 226, 190), (24, 44, 24), (255, 80, 60)),
    "poop": ((101, 67, 33), (160, 110, 60), (30, 18, 8), (190, 140, 80)),
    "firework": ((235, 40, 60), (255, 230, 60), (30, 30, 90), (80, 200, 255)),
    "sun": ((255, 196, 20), (255, 250, 200), (130, 80, 10), (255, 255, 255)),
    "black_hole": ((56, 36, 84), (210, 200, 230), (12, 8, 20), (190, 120, 255)),
}

ITEM_THEME = {
    "tnt": "nuke", "mining_tnt": "mining", "fire_tnt": "fire", "slime_tnt": "slime",
    "xray_tnt": "xray", "rdm_tnt": "rdm", "mob_tnt": "mob", "poop_tnt": "poop", "firework_tnt": "firework", "firework_charge": "firework", "clay_ball": "poop", "bone": "mob", "prismarine_shard": "rdm", "sun_tnt": "sun", "black_hole_tnt": "black_hole",
    "gold_ingot": "mining", "fireball": "fire", "slimeball": "slime",
    "diamond": "xray", "diamond_ore": "xray", "glowstone_dust": "sun",
    "ender_pearl": "black_hole",
}


def build_detailed():
    for index, (item, theme) in enumerate(ITEM_THEME.items()):
        body, band, ink, spark = THEMES[theme]
        png_rgba(ASSETS / "items" / f"{item}.png", cube_icon(body, band, ink, spark, index + 1))
    body, band, ink, spark = THEMES["nuke"]
    block_face_png(ASSETS / "blocks" / "tnt_side.png", face_texture("side", body, band, ink, spark, 5))
    block_face_png(ASSETS / "blocks" / "tnt_top.png", face_texture("top", body, band, ink, spark, 6))
    bottom = face_texture("top", body, body, ink, spark, 9)
    block_face_png(ASSETS / "blocks" / "tnt_bottom.png", bottom)


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

    tnt_themes = {
        "items/tnt.png": ((191, 38, 32, 255), (92, 25, 24, 255), (235, 88, 65, 255), (92, 25, 24, 255)),
        "items/mining_tnt.png": ((169, 169, 169, 255), (89, 89, 89, 255), (230, 230, 230, 255), (89, 89, 89, 255)),
        "items/fire_tnt.png": ((238, 102, 24, 255), (133, 45, 15, 255), (255, 188, 47, 255), (133, 45, 15, 255)),
        "items/slime_tnt.png": ((74, 188, 43, 255), (39, 114, 34, 255), (177, 247, 103, 255), (39, 114, 34, 255)),
        "items/xray_tnt.png": ((40, 112, 255, 255), (18, 55, 138, 255), (155, 203, 255, 255), (18, 55, 138, 255)),
        "items/sun_tnt.png": ((255, 192, 0, 255), (138, 94, 15, 255), (255, 242, 156, 255), (138, 94, 15, 255)),
        "items/black_hole_tnt.png": ((59, 30, 74, 255), (18, 12, 22, 255), (191, 129, 255, 255), (18, 12, 22, 255)),
        "items/gold_ingot.png": ((218, 153, 42, 255), (122, 79, 23, 255), (255, 222, 105, 255), (122, 79, 23, 255)),
        "items/fireball.png": ((238, 102, 24, 255), (133, 45, 15, 255), (255, 188, 47, 255), (133, 45, 15, 255)),
        "items/slimeball.png": ((74, 188, 43, 255), (39, 114, 34, 255), (177, 247, 103, 255), (39, 114, 34, 255)),
        "items/diamond_ore.png": ((40, 112, 255, 255), (18, 55, 138, 255), (155, 203, 255, 255), (18, 55, 138, 255)),
        "items/glowstone_dust.png": ((255, 206, 82, 255), (138, 94, 15, 255), (255, 242, 156, 255), (138, 94, 15, 255)),
        "items/ender_pearl.png": ((39, 39, 46, 255), (12, 12, 18, 255), (232, 232, 238, 255), (12, 12, 18, 255)),
    }
    for name, colors in tnt_themes.items():
        rows, palette = tnt_block_icon(*colors)
        png(ASSETS / name, rows, palette)

    build_detailed()

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
