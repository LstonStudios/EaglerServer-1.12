#!/usr/bin/env python3
"""Turn a saved pre-restore folder into a loadable backup archive."""

import argparse
from pathlib import Path
import sys
import tarfile

ROOT = Path(__file__).resolve().parent
BACKUPS = ROOT / "backups"
PREFIX = "pre-restore-"


def saved_folders():
    if not BACKUPS.is_dir():
        return []
    return sorted(
        path for path in BACKUPS.iterdir()
        if path.is_dir() and not path.is_symlink() and path.name.startswith(PREFIX)
    )


def choose_folder():
    folders = saved_folders()
    if not folders:
        raise ValueError(f"No {PREFIX}* folders found in {BACKUPS}.")
    print("Saved restorable files:")
    for index, path in enumerate(folders, start=1):
        print(f"  {index}. {path.name}")
    while True:
        selection = input("Choose a number (or q to cancel): ").strip()
        if selection.lower() == "q":
            raise ValueError("Cancelled.")
        if selection.isdigit() and 1 <= int(selection) <= len(folders):
            return folders[int(selection) - 1]
        print(f"Enter a number from 1 to {len(folders)}, or q to cancel.")


def convert(folder):
    folder = folder.resolve()
    if folder.parent != BACKUPS.resolve() or not folder.name.startswith(PREFIX) or not folder.is_dir():
        raise ValueError(f"{folder} is not a {PREFIX}* folder inside {BACKUPS}.")
    archive = BACKUPS / f"restored-{folder.name[len(PREFIX):]}.tar.gz"
    if archive.exists():
        raise ValueError(f"{archive.relative_to(ROOT)} already exists.")
    temporary = archive.with_name(f".{archive.name}.tmp")
    try:
        with tarfile.open(temporary, "w:gz") as tar:
            for child in sorted(folder.iterdir()):
                tar.add(child, arcname=child.name)
        temporary.replace(archive)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Created {archive.relative_to(ROOT)}. It now appears in 'backups' and 'loadbackup'.")


def main():
    parser = argparse.ArgumentParser(
        description="Make a saved pre-restore folder loadable by creating a backup archive from it."
    )
    parser.add_argument("folder", nargs="?", type=Path, help="pre-restore folder; omit to choose")
    arguments = parser.parse_args()
    try:
        convert(arguments.folder if arguments.folder else choose_folder())
    except (OSError, ValueError, tarfile.TarError) as error:
        print(f"Restore backup failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
