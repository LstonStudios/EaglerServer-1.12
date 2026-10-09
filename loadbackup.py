#!/usr/bin/env python3
"""Restore a world from a ZIP or tar archive without replacing server settings."""

import argparse
import datetime
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import sys
import tarfile
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
SERVER = ROOT / "server"
BACKUPS = ROOT / "backups"
MAX_ARCHIVE_ENTRIES = 200_000


def safe_member_path(name):
    if "\\" in name or name.startswith("/") or (len(name) > 1 and name[1] == ":"):
        raise ValueError(f"Unsafe archive path: {name}")

    path = PurePosixPath(name)
    if any(part in ("", ".", "..") for part in path.parts):
        raise ValueError(f"Unsafe archive path: {name}")
    return path


def extract_archive(archive_path, destination):
    if zipfile.is_zipfile(archive_path):
        with zipfile.ZipFile(archive_path) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_ARCHIVE_ENTRIES:
                raise ValueError("The archive contains too many entries.")

            for entry in entries:
                relative = safe_member_path(entry.filename.rstrip("/"))
                mode = entry.external_attr >> 16
                kind = stat.S_IFMT(mode)
                is_directory = entry.is_dir()
                if kind not in (0, stat.S_IFDIR, stat.S_IFREG):
                    raise ValueError(f"Unsupported file type in archive: {entry.filename}")
                if is_directory or kind == stat.S_IFDIR:
                    (destination / relative).mkdir(parents=True, exist_ok=True)
                    continue

                output = destination / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(entry) as source, output.open("xb") as target:
                    shutil.copyfileobj(source, target)
        return

    if tarfile.is_tarfile(archive_path):
        with tarfile.open(archive_path, mode="r:*") as archive:
            entries = archive.getmembers()
            if len(entries) > MAX_ARCHIVE_ENTRIES:
                raise ValueError("The archive contains too many entries.")

            for entry in entries:
                relative = safe_member_path(entry.name.rstrip("/"))
                output = destination / relative
                if entry.isdir():
                    output.mkdir(parents=True, exist_ok=True)
                elif entry.isfile():
                    output.parent.mkdir(parents=True, exist_ok=True)
                    source = archive.extractfile(entry)
                    if source is None:
                        raise ValueError(f"Unable to read archive entry: {entry.name}")
                    with source, output.open("xb") as target:
                        shutil.copyfileobj(source, target)
                else:
                    raise ValueError(f"Unsupported file type in archive: {entry.name}")
        return

    raise ValueError("The backup must be a ZIP, .tar.gz, .tgz, or other tar archive.")


def configured_world_name():
    properties = SERVER / "server.properties"
    if not properties.is_file():
        raise ValueError(f"Could not find {properties}.")

    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("level-name="):
            name = line.partition("=")[2].strip()
            if not name or Path(name).name != name or "/" in name or "\\" in name:
                raise ValueError("server.properties contains an invalid level-name.")
            return name
    raise ValueError("server.properties does not define level-name.")


def find_world(extracted, preferred_name):
    candidates = []
    for directory, subdirectories, filenames in os.walk(extracted):
        if "level.dat" in filenames and "region" in subdirectories:
            candidates.append(Path(directory))

    preferred = [path for path in candidates if path.name == preferred_name]
    if len(preferred) == 1:
        return preferred[0]
    if len(candidates) == 1:
        return candidates[0]
    if not candidates:
        raise ValueError("The archive does not contain a world with level.dat and region/.")

    names = ", ".join(str(path.relative_to(extracted)) for path in candidates)
    raise ValueError(f"The archive contains multiple worlds; unable to choose safely: {names}")


def running_server_detected():
    proc = Path("/proc")
    if not proc.is_dir():
        return False

    server_directory = SERVER.resolve()
    for process in proc.iterdir():
        if not process.name.isdigit():
            continue
        try:
            command = (process / "cmdline").read_bytes().replace(b"\0", b" ").decode(
                errors="replace"
            )
            working_directory = (process / "cwd").resolve()
        except (OSError, PermissionError):
            continue
        if "server.jar" in command and working_directory == server_directory:
            return True
    return False


def choose_archive():
    archives = sorted(
        path
        for path in BACKUPS.iterdir()
        if path.is_file()
        and (
            zipfile.is_zipfile(path)
            or tarfile.is_tarfile(path)
        )
    ) if BACKUPS.is_dir() else []
    if not archives:
        raise ValueError(
            f"No ZIP or tar backups found. Put a backup archive in {BACKUPS} "
            "or pass its path as an argument."
        )

    print("Available backups:")
    for index, path in enumerate(archives, start=1):
        print(f"  {index}. {path.relative_to(ROOT)}")
    while True:
        selection = input("Choose a backup number (or q to cancel): ").strip()
        if selection.lower() == "q":
            raise ValueError("Restore cancelled.")
        try:
            index = int(selection)
        except ValueError:
            index = 0
        if 1 <= index <= len(archives):
            return archives[index - 1]
        print(f"Enter a number from 1 to {len(archives)}, or q to cancel.")


def restore(archive_path):
    if running_server_detected():
        raise ValueError("Stop the Minecraft server before restoring a backup.")
    if not SERVER.is_dir():
        raise ValueError(f"Could not find the server directory: {SERVER}")

    world_name = configured_world_name()
    archive_path = archive_path.resolve()
    if not archive_path.is_file():
        raise ValueError(f"Backup archive not found: {archive_path}")

    with tempfile.TemporaryDirectory(prefix="world-backup-") as temporary:
        extracted = Path(temporary)
        extract_archive(archive_path, extracted)
        source_world = find_world(extracted, world_name)
        source_name = source_world.name
        staged = Path(tempfile.mkdtemp(prefix=".restore-", dir=SERVER))

        try:
            shutil.copytree(source_world, staged / world_name)
            dimensions = {}
            for suffix in ("_nether", "_the_end"):
                source_dimension = source_world.parent / f"{source_name}{suffix}"
                if source_dimension.is_dir():
                    target_name = f"{world_name}{suffix}"
                    shutil.copytree(source_dimension, staged / target_name)
                    dimensions[target_name] = staged / target_name

            targets = [world_name, f"{world_name}_nether", f"{world_name}_the_end"]
            rollback = BACKUPS / (
                "pre-restore-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
            )
            existing = [
                name for name in targets if (SERVER / name).exists() or (SERVER / name).is_symlink()
            ]
            if existing:
                rollback.mkdir(parents=True, exist_ok=False)

            moved = []
            installed = []
            try:
                for name in existing:
                    os.replace(SERVER / name, rollback / name)
                    moved.append(name)
                for name in targets:
                    staged_path = staged / name
                    if staged_path.exists():
                        os.replace(staged_path, SERVER / name)
                        installed.append(name)
            except Exception:
                for name in reversed(installed):
                    path = SERVER / name
                    if path.is_dir() and not path.is_symlink():
                        shutil.rmtree(path)
                    elif path.exists() or path.is_symlink():
                        path.unlink()
                for name in reversed(moved):
                    os.replace(rollback / name, SERVER / name)
                raise

            print(f"Restored world '{world_name}' from {archive_path}.")
            if existing:
                print(f"Previous world files were preserved in {rollback}.")
            if not dimensions:
                print("The archive did not include separate nether/end world folders.")
        finally:
            shutil.rmtree(staged, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(
        description="Restore the configured Minecraft world from a ZIP or tar backup."
    )
    parser.add_argument(
        "archive",
        nargs="?",
        type=Path,
        help="backup archive; omit to choose one from the backups/ directory",
    )
    arguments = parser.parse_args()

    try:
        archive = arguments.archive if arguments.archive else choose_archive()
        restore(archive)
    except (OSError, ValueError, tarfile.TarError, zipfile.BadZipFile) as error:
        print(f"Backup restore failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
