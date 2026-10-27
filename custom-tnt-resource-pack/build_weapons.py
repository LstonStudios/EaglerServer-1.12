#!/usr/bin/env python3
"""Add the Mace (gold_shovel.png) and Spear (gold_pickaxe.png) textures to the web pack.

Unlike build_pack.py this leaves every other texture untouched: the zip is copied entry by entry
and only the two weapon textures are replaced or appended.
"""

from pathlib import Path
import zipfile

from build_pack import ROOT, WEB_PACK

ITEMS = ROOT / "assets" / "minecraft" / "textures" / "items"

def add_to_zip():
    names = {"gold_shovel.png", "gold_pickaxe.png"}
    new_entries = {f"assets/minecraft/textures/items/{n}": (ITEMS / n).read_bytes() for n in names}
    tmp = WEB_PACK.with_suffix(".tmp")
    with zipfile.ZipFile(WEB_PACK) as source, zipfile.ZipFile(
        tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
    ) as target:
        entries = {}
        for info in source.infolist():
            if info.filename not in new_entries:
                entries[info.filename] = source.read(info.filename)
        entries.update(new_entries)
        for name in sorted(entries, key=lambda n: (n != "pack.mcmeta", n)):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, entries[name], compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    tmp.replace(WEB_PACK)
    print(f"Updated {WEB_PACK} ({WEB_PACK.stat().st_size} bytes)")


if __name__ == "__main__":
    add_to_zip()
