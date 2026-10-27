#!/usr/bin/env python3
"""Generate Verity's 3D body: a faceted yellow smiley sphere worn as a helmet.

Writes one model + texture per facial expression. The plugin puts the matching item on Verity's
armor stand: Barrier plus the six silverfish-block variants (monster_egg data 0-5). None of them
are obtainable in survival, so retexturing their item models affects nothing else (placed blocks
use block models). Run build_pack.py afterwards to rebuild web/custom-tnt-pack.zip.

The sphere is a 32x32x32 voxel ball (0.5 model units per voxel), merged into few cuboids. The
face is painted on the north side of the model, which is the front of the armor stand.
"""

import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets" / "minecraft"

N = 32
VOXEL = 16.0 / N
TEX = 128
UV = 16.0 / TEX
# Top-left pixel of the 32x32 tile used by each face direction (16px gutters stop mipmap bleed).
TILES = {
    "north": (0, 0), "south": (48, 0), "east": (96, 0),
    "west": (0, 64), "up": (48, 64), "down": (96, 64),
}

BASE = (240, 203, 22)
LIGHT = (0.45, 0.6, -0.66)
LIGHT_LEN = math.sqrt(sum(c * c for c in LIGHT))
LIGHT = tuple(c / LIGHT_LEN for c in LIGHT)
FACE_NORMALS = {
    "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0),
    "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0),
}


def solid(x, y, z):
    if not (0 <= x < N and 0 <= y < N and 0 <= z < N):
        return False
    d = math.sqrt((x + 0.5 - N / 2) ** 2 + (y + 0.5 - N / 2) ** 2 + (z + 0.5 - N / 2) ** 2)
    return d <= N / 2 - 0.15


def noise(x, y, seed):
    v = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    v = ((v ^ (v >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((v ^ (v >> 16)) & 255) / 255.0


def shade(face, x, y, z):
    """Faceted lighting: mostly the voxel face normal, a little of the sphere normal."""
    fx, fy, fz = FACE_NORMALS[face]
    sx, sy, sz = x + 0.5 - N / 2, y + 0.5 - N / 2, z + 0.5 - N / 2
    length = math.sqrt(sx * sx + sy * sy + sz * sz) or 1
    nx, ny, nz = fx * 0.6 + sx / length * 0.4, fy * 0.6 + sy / length * 0.4, fz * 0.6 + sz / length * 0.4
    nl = math.sqrt(nx * nx + ny * ny + nz * nz) or 1
    diffuse = max(0.0, (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / nl)
    k = 0.58 + 0.5 * diffuse
    k *= 0.96 + 0.08 * noise(x * 7 + y, z * 5 + y, 3)
    return tuple(max(0, min(255, int(c * k))) for c in BASE)


# name -> item model that carries it (must match EXPRESSION_ITEMS in VerityManager.java)
EXPRESSIONS = {
    "happy": "barrier",
    "joy": "stone_monster_egg",
    "sad": "cobblestone_monster_egg",
    "angry": "stone_brick_monster_egg",
    "surprised": "mossy_brick_monster_egg",
    "neutral": "cracked_brick_monster_egg",
    "evil": "chiseled_brick_monster_egg",
    "blank": "mob_spawner",
}

DARK = (22, 20, 12)
MOUTH = (24, 18, 8)
TONGUE = (158, 92, 14)
TEETH = (236, 232, 214)
BLUSH = (236, 120, 70)
TEAR = (120, 190, 240)
RED = (214, 30, 16)


def fill(r, c, rows, cols):
    return rows[0] <= r <= rows[1] and cols[0] <= c <= cols[1]


def grin(r, c, teeth=False, tongue=True):
    if 16 <= r <= 23:
        half = 10 - (r - 16) * 0.55
        if 16 - half <= c < 16 + half:
            if teeth and r <= 18 and (c % 3 != 0 or r == 16):
                return TEETH
            if tongue and r >= 21 and abs(c - 15.5) <= 4.5 - (r - 21) * 0.8:
                return TONGUE
            return MOUTH
    return None


def face_paint(expr, c, r):
    """Features on the 32x32 front tile (c from the viewer's left, r from the top)."""
    col = c if c < N / 2 else N - 1 - c  # features are left-right symmetric
    d = abs(c - 15.5)
    if expr == "happy":
        if fill(r, col, (9, 12), (8, 9)):
            return DARK
        return grin(r, c)
    if expr == "joy":
        # closed happy eyes (^ ^), rosy cheeks, big open smile
        if (r == 10 and 8 <= col <= 9) or (r == 11 and col in (7, 10)) or (r == 12 and col in (6, 11)):
            return DARK
        if fill(r, col, (15, 16), (4, 6)):
            return BLUSH
        return grin(r, c)
    if expr == "sad":
        if fill(r, col, (10, 13), (8, 9)):
            return DARK
        if fill(r, col, (14, 16), (9, 9)):
            return TEAR
        if (r, col) in ((7, 9), (7, 8), (8, 7), (8, 6), (6, 9)):
            return DARK
        y = 20 + d * d / 12.0
        if y <= r <= y + 1.6 and d <= 7.5:
            return MOUTH
        return None
    if expr == "angry":
        if fill(r, col, (10, 12), (8, 9)):
            return DARK
        brow = {9: (8, 9), 8: (7, 8), 7: (6, 7), 6: (5, 5)}
        for rr, cs in brow.items():
            if r == rr and col in cs:
                return DARK
        if 19 <= r <= 22 and d <= 7:
            return TEETH if r == 20 and int(d) % 3 != 0 else MOUTH
        return None
    if expr == "surprised":
        if fill(r, col, (8, 13), (8, 9)):
            return DARK
        if r == 8 and col == 8:
            return (255, 255, 255)
        if r == 5 and 7 <= col <= 10:
            return DARK
        if (r - 21) ** 2 + (c - 15.5) ** 2 <= 14:
            return MOUTH
        return None
    if expr == "neutral":
        if fill(r, col, (11, 12), (7, 10)):
            return DARK
        if r == 10 and 7 <= col <= 10:
            return (176, 146, 14)
        if 21 <= r <= 21.6 or r == 21:
            if d <= 5:
                return MOUTH
        return None
    if expr == "evil":
        if fill(r, col, (10, 12), (7, 10)):
            return RED
        if r == 11 and col in (8, 9):
            return DARK
        brow = {9: (8, 10), 8: (7, 8), 7: (6, 7)}
        for rr, cs in brow.items():
            if r == rr and col in cs:
                return DARK
        return grin(r, c, teeth=True, tongue=False)
    return None


def exposed(face, c, r):
    """Voxel whose `face` side is the outermost one at tile pixel (c, r), or None."""
    if face == "north":
        x, y = N - 1 - c, N - 1 - r
        return next(((x, y, z) for z in range(N) if solid(x, y, z)), None)
    if face == "south":
        x, y = c, N - 1 - r
        return next(((x, y, z) for z in range(N - 1, -1, -1) if solid(x, y, z)), None)
    if face == "east":
        z, y = N - 1 - c, N - 1 - r
        return next(((x, y, z) for x in range(N - 1, -1, -1) if solid(x, y, z)), None)
    if face == "west":
        z, y = c, N - 1 - r
        return next(((x, y, z) for x in range(N) if solid(x, y, z)), None)
    if face == "up":
        x, z = c, r
        return next(((x, y, z) for y in range(N - 1, -1, -1) if solid(x, y, z)), None)
    x, z = c, r
    return next(((x, y, z) for y in range(N) if solid(x, y, z)), None)


def write_png(path, pixels):
    raw = b"".join(
        b"\0" + b"".join(bytes(p) for p in row) for row in pixels
    )

    def chunk(name, data):
        body = name + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    data = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">2I5B", TEX, TEX, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def build_texture(expr):
    pixels = [[(0, 0, 0, 0)] * TEX for _ in range(TEX)]
    for face, (tx, ty) in TILES.items():
        for r in range(N):
            for c in range(N):
                voxel = exposed(face, c, r)
                if voxel is None:
                    continue
                color = shade(face, *voxel)
                if face == "north":
                    paint = face_paint(expr, c, r)
                    if paint:
                        color = paint
                pixels[ty + r][tx + c] = (*color, 255)
    write_png(ASSETS / "textures" / "items" / f"verity_head_{expr}.png", pixels)


def merge_boxes():
    """Greedy merge of the voxel ball into cuboids: x runs, then z, then y."""
    runs = {}
    for y in range(N):
        for z in range(N):
            x = 0
            while x < N:
                if solid(x, y, z):
                    start = x
                    while x < N and solid(x, y, z):
                        x += 1
                    runs[(y, z)] = runs.get((y, z), []) + [(start, x)]
                else:
                    x += 1
    slabs = {}
    for (y, z), spans in runs.items():
        for span in spans:
            slabs.setdefault((y, span), []).append(z)
    layers = []
    for (y, span), zs in slabs.items():
        zs.sort()
        start = prev = zs[0]
        for z in zs[1:]:
            if z != prev + 1:
                layers.append((y, span, start, prev + 1))
                start = z
            prev = z
        layers.append((y, span, start, prev + 1))
    stacks = {}
    for y, span, z0, z1 in layers:
        stacks.setdefault((span, z0, z1), []).append(y)
    boxes = []
    for (span, z0, z1), ys in stacks.items():
        ys.sort()
        start = prev = ys[0]
        for y in ys[1:]:
            if y != prev + 1:
                boxes.append((span[0], start, z0, span[1], prev + 1, z1))
                start = y
            prev = y
        boxes.append((span[0], start, z0, span[1], prev + 1, z1))
    return boxes


def face_uv(face, box):
    x0, y0, z0, x1, y1, z1 = box
    tx, ty = TILES[face]
    if face == "north":
        c0, c1, r0, r1 = N - x1, N - x0, N - y1, N - y0
    elif face == "south":
        c0, c1, r0, r1 = x0, x1, N - y1, N - y0
    elif face == "east":
        c0, c1, r0, r1 = N - z1, N - z0, N - y1, N - y0
    elif face == "west":
        c0, c1, r0, r1 = z0, z1, N - y1, N - y0
    else:
        c0, c1, r0, r1 = x0, x1, z0, z1
    return [round((tx + c0) * UV, 4), round((ty + r0) * UV, 4), round((tx + c1) * UV, 4), round((ty + r1) * UV, 4)]


def face_hidden(face, box):
    x0, y0, z0, x1, y1, z1 = box
    dx, dy, dz = FACE_NORMALS[face]
    if dx:
        xs, ys, zs = [x1 if dx > 0 else x0 - 1], range(y0, y1), range(z0, z1)
    elif dy:
        xs, ys, zs = range(x0, x1), [y1 if dy > 0 else y0 - 1], range(z0, z1)
    else:
        xs, ys, zs = range(x0, x1), range(y0, y1), [z1 if dz > 0 else z0 - 1]
    return all(solid(x, y, z) for x in xs for y in ys for z in zs)


def build_model():
    elements = []
    for box in merge_boxes():
        faces = {
            face: {"uv": face_uv(face, box), "texture": "#0"}
            for face in FACE_NORMALS
            if not face_hidden(face, box)
        }
        if not faces:
            continue
        elements.append({
            "from": [round(box[0] * VOXEL, 4), round(box[1] * VOXEL, 4), round(box[2] * VOXEL, 4)],
            "to": [round(box[3] * VOXEL, 4), round(box[4] * VOXEL, 4), round(box[5] * VOXEL, 4)],
            "faces": faces,
        })
    models = ASSETS / "models" / "item"
    models.mkdir(parents=True, exist_ok=True)
    for expr, item in EXPRESSIONS.items():
        model = {
            "ambientocclusion": False,
            "textures": {"0": f"items/verity_head_{expr}", "particle": f"items/verity_head_{expr}"},
            "elements": elements,
            "display": {
                "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                "gui": {"rotation": [30, 225, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
                "thirdperson": {"rotation": [10, -45, 170], "translation": [0, 1.5, -2.75], "scale": [0.375, 0.375, 0.375]},
                "firstperson": {"rotation": [0, -135, 25], "translation": [0, 4, 2], "scale": [0.4, 0.4, 0.4]},
            },
        }
        (models / f"{item}.json").write_text(json.dumps(model, separators=(",", ":")))
    return len(elements)


if __name__ == "__main__":
    for name in EXPRESSIONS:
        build_texture(name)
    print(f"Verity model: {build_model()} cuboids x {len(EXPRESSIONS)} expressions")
