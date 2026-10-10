#!/usr/bin/env python3
"""Interactively delete selected backup archives."""

from pathlib import Path
import sys
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parent
BACKUPS = ROOT / "backups"
SELECTION_CODES = "123456789ABCDEFGHIJKLM"


def is_archive(path):
    return zipfile.is_zipfile(path) or tarfile.is_tarfile(path)


def list_archives():
    if not BACKUPS.is_dir():
        return []
    return sorted(
        path
        for path in BACKUPS.iterdir()
        if path.is_file() and is_archive(path)
    )


def choose_archives(archives):
    if not archives:
        print(f"No backup archives found in {BACKUPS}.")
        return None

    print("Available backup archives:")
    for code, archive in zip(SELECTION_CODES, archives):
        print(f"  {code}. {archive.name}")
    print("Enter Q to quit.")

    while True:
        response = input(
            "Select backup(s), separated by commas (or Q to quit): "
        ).strip().upper()
        if response == "Q":
            print("Deletion cancelled.")
            return None

        selections = [selection.strip() for selection in response.split(",")]
        if (
            not selections
            or any(not selection for selection in selections)
            or len(set(selections)) != len(selections)
            or any(
                selection not in SELECTION_CODES[: len(archives)]
                for selection in selections
            )
        ):
            print("Enter valid menu codes separated by commas, or Q to quit.")
            continue

        return [archives[SELECTION_CODES.index(selection)] for selection in selections]


def confirm_deletion(archives):
    print("Selected backup(s):")
    for archive in archives:
        print(f"  {archive.name}")

    while True:
        response = input(
            "Are you sure you want to delete these backups? Type Y or N: "
        ).strip().upper()
        if response == "Y":
            return True
        if response == "N":
            print("Deletion cancelled.")
            return False
        print("Type Y to delete or N to cancel.")


def main():
    archives = list_archives()
    if len(archives) > len(SELECTION_CODES):
        print(
            f"Found {len(archives)} backup archives, but the menu supports "
            f"at most {len(SELECTION_CODES)} at a time.",
            file=sys.stderr,
        )
        return 1
    selected = choose_archives(archives)
    if not selected:
        return 0
    if not confirm_deletion(selected):
        return 0

    for archive in selected:
        try:
            if not archive.is_file() or not is_archive(archive):
                print(
                    f"Backup archive changed or is no longer available: {archive}",
                    file=sys.stderr,
                )
                return 1
            archive.unlink()
        except OSError as error:
            print(
                f"Could not delete backup archive {archive}: {error}",
                file=sys.stderr,
            )
            return 1
        print(f"Deleted backup: {archive.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
