#!/usr/bin/env python3
"""Fail if the served pack textures a vanilla item that no custom item owns.

Every texture overrides ALL vanilla items of that type for every player, so a texture must only
exist for a vanilla item that is used as a custom item. Add new textures to OWNERS on purpose.
"""

import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ZIP = ROOT.parent / "web" / "custom-tnt-pack.zip"
PREFIX = "assets/minecraft/textures/items/"

OWNERS = {
    "minecart_command_block": "Custom TNT items",
    "mining_tnt": "Custom TNT", "fire_tnt": "Custom TNT", "slime_tnt": "Custom TNT",
    "xray_tnt": "Custom TNT", "rdm_tnt": "Custom TNT", "mob_tnt": "Custom TNT",
    "poop_tnt": "Custom TNT", "firework_tnt": "Custom TNT", "sun_tnt": "Custom TNT",
    "black_hole_tnt": "Custom TNT", "boss_tnt": "Custom TNT",
    "gold_hoe": "Custom guns",
    "gold_sword": "Custom swords", "iron_sword": "Custom swords", "diamond_sword": "Custom swords",
    "stone_sword": "Custom swords", "wood_sword": "Custom swords", "diamond_axe": "Custom swords",
    "gold_shovel": "Mace", "gold_pickaxe": "Spear", "gold_axe": "Spear (hand-tilt model while charging)",
    "nether_star": "Verity Box",
    "verity_head_happy": "Verity 3D body expression",
    "verity_head_joy": "Verity 3D body expression",
    "verity_head_sad": "Verity 3D body expression",
    "verity_head_angry": "Verity 3D body expression",
    "verity_head_surprised": "Verity 3D body expression",
    "verity_head_neutral": "Verity 3D body expression",
    "verity_head_evil": "Verity 3D body expression",
    "verity_head_blank": "Verity 3D body expression",
    "verity_demon": "Verity Demon model",
    "prismarine_crystals": "Wind Burst",
}


def main():
    with zipfile.ZipFile(ZIP) as z:
        archive_names = set(z.namelist())
        names = {n[len(PREFIX):-4] for n in archive_names if n.startswith(PREFIX) and n.endswith(".png")}
        archive_models = {n for n in archive_names if n.startswith("assets/minecraft/models/") and n.endswith(".json")}
    on_disk = {p.stem for p in (ROOT / PREFIX).glob("*.png")}
    on_disk_models = {
        p.relative_to(ROOT).as_posix()
        for p in (ROOT / "assets" / "minecraft" / "models").rglob("*.json")
    }
    problems = []
    for n in sorted(names - OWNERS.keys()):
        problems.append(f"Unowned texture in pack: {n}.png (would restyle that vanilla item for everyone)")
    for n in sorted(names ^ on_disk):
        problems.append(f"Zip and assets folder disagree on: {n}.png")
    for n in sorted(archive_models ^ on_disk_models):
        problems.append(f"Zip and assets folder disagree on model: {n}")
    for p in problems:
        print("ERROR:", p)
    print("Pack OK" if not problems else f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
