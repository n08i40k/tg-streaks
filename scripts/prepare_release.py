#!/usr/bin/env python3

import argparse
import hashlib
import re
from pathlib import Path


VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")


def compute_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def replace_one(text: str, pattern: str, replacement: str, description: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Failed to update {description}")
    return updated


def update_plugin_file(
    plugin_path: Path,
    version: str,
    dex_sha256: str,
    resources_sha256: str,
) -> None:
    text = plugin_path.read_text()
    text = replace_one(
        text,
        r'^__version__ = ".*"$',
        f'__version__ = "{version}"',
        "__version__",
    )
    text = replace_one(
        text,
        r"^DEBUG_MODE = (True|False)$",
        "DEBUG_MODE = False",
        "DEBUG_MODE",
    )
    text = replace_one(
        text,
        r'^DEX_SHA256 = ".*"$',
        f'DEX_SHA256 = "{dex_sha256}"',
        "DEX_SHA256",
    )
    text = replace_one(
        text,
        r'^RESOURCES_SHA256 = ".*"$',
        f'RESOURCES_SHA256 = "{resources_sha256}"',
        "RESOURCES_SHA256",
    )
    plugin_path.write_text(text)


def update_pyproject_file(pyproject_path: Path, version: str) -> None:
    text = pyproject_path.read_text()
    text = replace_one(
        text,
        r'^version = ".*"$',
        f'version = "{version}"',
        "pyproject version",
    )
    pyproject_path.write_text(text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--dex", required=True, type=Path)
    parser.add_argument("--resources", required=True, type=Path)
    parser.add_argument("--plugin-file", required=True, type=Path)
    parser.add_argument("--pyproject-file", required=True, type=Path)
    args = parser.parse_args()

    if VERSION_RE.fullmatch(args.version) is None:
        raise SystemExit("Version must match x.x.x")

    if not args.dex.is_file():
        raise SystemExit(f"DEX file not found: {args.dex}")
    if not args.resources.is_file():
        raise SystemExit(f"Resources archive not found: {args.resources}")
    if not args.plugin_file.is_file():
        raise SystemExit(f"Plugin file not found: {args.plugin_file}")
    if not args.pyproject_file.is_file():
        raise SystemExit(f"pyproject file not found: {args.pyproject_file}")

    dex_sha256 = compute_sha256(args.dex)
    resources_sha256 = compute_sha256(args.resources)

    update_plugin_file(args.plugin_file, args.version, dex_sha256, resources_sha256)
    update_pyproject_file(args.pyproject_file, args.version)

    print(f"version={args.version}")
    print(f"dex_sha256={dex_sha256}")
    print(f"resources_sha256={resources_sha256}")


if __name__ == "__main__":
    main()
