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
    if len(rows) != 16 or any(len(row) != 16 for row in rows):
        raise ValueError(f"{path} must be a 16x16 sprite")
    raw = b"".join(
        b"\0" + b"".join(bytes(palette[pixel]) for pixel in row)
        for row in rows
    )

    def chunk(name, data):
        content = name + data
        return struct.pack(">I", len(data)) + content + struct.pack(">I", zlib.crc32(content) & 0xFFFFFFFF)

    image = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">2I5B", 16, 16, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(image)


def build():
    side_rows = [
        list(row) for row in [
            "rrrrrrrrrrrrrrrr", "rrrrrrrrrrrrrrrr", "rrrrrrrrrrrrrrrr",
            "dddddddddddddddd", "wwwwwwwwwwwwwwww", "wwwwwwwwwwwwwwww",
            "wwwwwwwwwwwwwwww", "wwwwwwwwwwwwwwww", "wwwwwwwwwwwwwwww",
            "wwwwwwwwwwwwwwww", "wwwwwwwwwwwwwwww", "dddddddddddddddd",
            "rrrrrrrrrrrrrrrr", "rrrrrrrrrrrrrrrr", "rrrrrrrrrrrrrrrr",
            "rrrrrrrrrrrrrrrr",
        ]
    ]
    letters = {
        "T": ("111", "010", "010", "010", "010"),
        "N": ("101", "111", "111", "111", "101"),
        "T2": ("111", "010", "010", "010", "010"),
    }
    for letter_index, letter in enumerate(("T", "N", "T2")):
        left = 2 + letter_index * 4
        for y, line in enumerate(letters[letter]):
            for x, pixel in enumerate(line):
                if pixel == "1":
                    side_rows[5 + y][left + x] = "k"
    side_palette = {
        ".": (0, 0, 0, 0),
        "r": (191, 38, 32, 255),
        "d": (92, 25, 24, 255),
        "w": (224, 218, 195, 255),
        "k": (35, 31, 29, 255),
    }
    png(ASSETS / "blocks" / "tnt_side.png", ["".join(row) for row in side_rows], side_palette)

    top = [["r" for _ in range(16)] for _ in range(16)]
    top_palette = {
        "r": (191, 38, 32, 255),
        "d": (92, 25, 24, 255),
        "w": (224, 218, 195, 255),
        "b": (45, 39, 34, 255),
        "y": (217, 170, 74, 255),
    }
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
    png(ASSETS / "blocks" / "tnt_bottom.png", bottom_rows, side_palette)

    sprites = {
        "items/gold_ingot.png": (
            [
                "................", "................", "......dd........",
                ".....dggd.......", "....dgyygd......", "...dgyyyyggd....",
                "..dgyyyyyyggd...", ".dgyyyyyyyyggd..",
                "dgggggggggggggd.", ".dddddddddddddd.",
                "................", "................", "................",
                "................", "................", "................",
            ],
            {".": (0, 0, 0, 0), "d": (88, 55, 24, 255),
             "g": (218, 153, 42, 255), "y": (255, 222, 105, 255)},
        ),
        "items/fireball.png": (
            [
                "................", ".......r........", "......ror.......",
                "......royr......", ".....royyr......", "....royyyr..r...",
                "...royyyyrrro...", "...oyyyyrrryro..",
                "..oyyyyrrryyro..", "..oyyyrrryyyro..",
                "...oyrrryyyro...", "....oyryyyro....",
                ".....oyyro......", "......oro.......",
                ".......o........", "................",
            ],
            {".": (0, 0, 0, 0), "r": (212, 47, 25, 255),
             "o": (255, 118, 20, 255), "y": (255, 222, 73, 255)},
        ),
        "items/slimeball.png": (
            [
                "................", "......gggg......", "....gggggggg....",
                "...gggggggggg...", "..gggggggggggg..",
                ".gggwwggggggggg.", ".ggwwwwggggggggg",
                ".gggwwggggggggg.", "gggggggggllggggg",
                "gggggggggllggggg", "gggggggggggggggg",
                ".gggggggggggggg.", "..gggggggggggg..",
                "...gggggggggg...", ".....gggggg.....",
                "................",
            ],
            {".": (0, 0, 0, 0), "g": (74, 188, 43, 255),
             "w": (187, 255, 142, 255), "l": (39, 114, 34, 255)},
        ),
        "items/ender_pearl.png": (
            [
                "................", "......pppp......", "....pppppppp....",
                "...pppppppppp...", "..pppppppppppp..",
                ".ppppppddpppppp.", ".pppppddddppppp.",
                ".ppppddddddpppp.", "ppppddddddpppppp",
                "pppppddddddppppp", ".pppppddddppppp.",
                ".ppppppddpppppp.", "..pppppppppppp..",
                "...pppppppppp...", ".....pppppp.....",
                "................",
            ],
            {".": (0, 0, 0, 0), "p": (101, 45, 155, 255),
             "d": (22, 14, 36, 255)},
        ),
    }
    for name, (rows, palette) in sprites.items():
        png(ASSETS / name, rows, palette)

    with zipfile.ZipFile(WEB_PACK, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.write(ROOT / "pack.mcmeta", "pack.mcmeta")
        for path in sorted((ROOT / "assets").rglob("*.png")):
            archive.write(path, path.relative_to(ROOT).as_posix())
    print(f"Created {WEB_PACK} ({WEB_PACK.stat().st_size} bytes)")


if __name__ == "__main__":
    build()
