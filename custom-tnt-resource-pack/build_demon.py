#!/usr/bin/env python3
"""Generate the Verity Demon model as jointed parts: a gaunt, pale-yellow, skeletal humanoid.

Each part is its own item model worn as a helmet by a separate invisible armor stand, so the
plugin can swing arms and legs, hunch the torso and raise the arms when it attacks. Every part is
authored with its joint at the model centre (8,8,8), which is the pivot of the stand's head pose.
Items (unobtainable in survival; placed blocks are unaffected): command_block = head+torso
(pivot: waist), lit_furnace = one arm (pivot: shoulder), end_portal_frame = one leg (pivot: hip).
Proportions come from the reference image (120px figure). Run build_pack.py afterwards.
"""

import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets" / "minecraft"
TEXTURE = "verity_demon"

K = 30.0 / 113.0  # model units per reference pixel
SCALE = 2.0       # head display scale; VerityManager's DEMON_PX derives from it

# swatch name -> (pixel x, pixel y, colour); each swatch is a 4x4 block of a 16x16 texture
SWATCH = {
    "light": (0, 0, (226, 210, 122)),
    "mid": (4, 0, (206, 188, 98)),
    "dark": (8, 0, (150, 126, 70)),
    "socket": (12, 0, (18, 14, 8)),
    "rib": (0, 4, (120, 98, 52)),
    "mouth": (4, 4, (86, 52, 34)),
}

# Joint positions in reference pixels from the figure's top: waist 70, shoulder 37, hip 92.
WAIST, SHOULDER, HIP = 70.0, 37.0, 92.0

PARTS = {"command_block": [], "lit_furnace": [], "end_portal_frame": []}
JOINT = {"command_block": WAIST, "lit_furnace": SHOULDER, "end_portal_frame": HIP}


def box(item, x0, x1, y0, y1, z0, z1, sw="mid"):
    """x: px from the part centre; y: px from the figure's top; z: px (negative = front)."""
    PARTS[item].append((x0, x1, y0, y1, z0, z1, sw))


B = "command_block"
# head: narrow tall skull, long jaw, dark sockets and mouth
box(B, -5.5, 5.5, 0, 17, -5, 5, "light")
box(B, -4, 4, 17, 24, -4, 4, "mid")
box(B, -4.2, -1.4, 7, 11.5, -5.3, -5, "socket")
box(B, 1.4, 4.2, 7, 11.5, -5.3, -5, "socket")
box(B, -0.7, 0.7, 12.5, 15, -5.3, -5, "dark")
box(B, -2.5, 2.5, 18.5, 20.5, -4.3, -4, "mouth")
box(B, -2, 2, 24, 31, -2, 2, "dark")  # neck
# ribcage with rib stripes, spine, narrow waist, pelvis
box(B, -8, 8, 31, 42, -4.5, 4.5, "mid")
box(B, -7, 7, 42, 53, -4, 4, "mid")
box(B, -5.5, 5.5, 53, 63, -3.5, 3.5, "mid")
for i, y in enumerate((43.5, 47, 50.5, 54, 57.5)):
    w = 6.5 - i * 0.5
    box(B, -w, w, y, y + 1, -4.4 + i * 0.1, -4.1 + i * 0.1, "rib")
box(B, -0.8, 0.8, 31, 85, 2.6, 3.6, "dark")
box(B, -4, 4, 63, 84, -3, 3, "dark")
box(B, -5.5, 5.5, 84, 92, -3.5, 3.5, "mid")

A = "lit_furnace"  # arm hanging from the shoulder: shoulder cap, upper arm, forearm, long hand
box(A, -3, 3, 33, 41, -3, 3, "mid")
box(A, -2.2, 2.2, 37, 77, -2.2, 2.2, "mid")
box(A, -1.8, 1.8, 77, 113, -1.8, 1.8, "light")
box(A, -1.5, 1.5, 113, 121, -1.5, 1.5, "dark")

L = "end_portal_frame"  # leg hanging from the hip: thigh, shin, foot
box(L, -3, 3, 92, 106, -3, 3, "mid")
box(L, -2.5, 2.5, 106, 117, -2.5, 2.5, "light")
box(L, -2.8, 2.8, 117, 120, -7, 3, "dark")


def elem(item, p):
    x0, x1, y0, y1, z0, z1, sw = p
    ux, uy, _ = SWATCH[sw]
    face = {"uv": [ux, uy, ux + 4, uy + 4], "texture": "#0"}
    jy = JOINT[item]
    return {
        "from": [round(8 + x0 * K, 3), round(8 - (y1 - jy) * K, 3), round(8 + z0 * K, 3)],
        "to": [round(8 + x1 * K, 3), round(8 - (y0 - jy) * K, 3), round(8 + z1 * K, 3)],
        "faces": {d: dict(face) for d in ("north", "south", "east", "west", "up", "down")},
    }


def write_png(path, pixels, w, h):
    raw = b"".join(b"\x00" + b"".join(bytes(px) + b"\xff" for px in row) for row in pixels)

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.write_bytes(data)


def main():
    tex = ASSETS / "textures" / "items"
    mod = ASSETS / "models" / "item"
    tex.mkdir(parents=True, exist_ok=True)
    mod.mkdir(parents=True, exist_ok=True)
    pixels = [[(0, 0, 0)] * 16 for _ in range(16)]
    for ux, uy, col in SWATCH.values():
        for y in range(uy, uy + 4):
            for x in range(ux, ux + 4):
                pixels[y][x] = col
    write_png(tex / (TEXTURE + ".png"), pixels, 16, 16)
    for item, parts in PARTS.items():
        elements = [elem(item, p) for p in parts]
        for e in elements:
            assert all(-16 <= v <= 32 for v in e["from"] + e["to"]), (item, e)
        model = {
            "textures": {"0": "items/" + TEXTURE, "particle": "items/" + TEXTURE},
            "elements": elements,
            "display": {
                "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [SCALE] * 3},
                "thirdperson": {"scale": [0.1] * 3},
                "firstperson": {"scale": [0.1] * 3},
                "gui": {"scale": [0.1] * 3},
            },
        }
        (mod / (item + ".json")).write_text(json.dumps(model))
        print(item, len(elements), "elements")


if __name__ == "__main__":
    main()
