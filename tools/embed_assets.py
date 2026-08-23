#!/usr/bin/env python3
"""Embed classes.dex, resources.zip and badges-sdk.plugin into the plugin .py as hex comment blocks.

The plugin loads its engine and its assets by reading its own source and
decoding the hex payloads placed between the marker comments (see
JvmPluginBridge / ZipResourcesBridge in the plugin). Storing the payloads as
comments (not string literals) keeps their bytes out of the interpreter heap:
comments are dropped by the tokenizer and never become a live Python object.

Usage: embed_assets.py [--dex classes.dex] [--resources resources.zip]
                       [--badges-sdk badges-sdk.plugin] <source.py> [output.py]

Without an output path the source is rewritten in place. The reusable
``embed_source`` helper is what the dev watcher uses to build the temp file it
hands to extera.
"""

import argparse
import re
from pathlib import Path
from typing import Optional

DEX_BEGIN = "# === EMDEDDED DEX BEGIN ==="
DEX_END = "# === EMDEDDED DEX END ==="
RESOURCES_BEGIN = "# === EMDEDDED RESOURCES BEGIN ==="
RESOURCES_END = "# === EMDEDDED RESOURCES END ==="
BADGES_SDK_BEGIN = "# === EMDEDDED BADGES SDK BEGIN ==="
BADGES_SDK_END = "# === EMDEDDED BADGES SDK END ==="
BADGES_SDK_VERSION_PREFIX = "BADGES_SDK_VERSION = "
# hex chars per line (kept modest so the source stays diff-friendly)
LINE_WIDTH = 120


def embed_block(source: str, begin: str, end: str, data: bytes) -> str:
    """Return ``source`` with ``data`` hex-encoded between the marker comments."""
    lines = source.splitlines()

    try:
        begin_idx = next(i for i, line in enumerate(lines) if line.strip() == begin)
        end_idx = next(i for i, line in enumerate(lines) if line.strip() == end)
    except StopIteration:
        raise ValueError(f"markers not found in source: {begin}") from None

    if end_idx <= begin_idx:
        raise ValueError(f"END marker precedes BEGIN marker: {begin}")

    hex_data = data.hex()
    payload = [
        f"# {hex_data[i : i + LINE_WIDTH]}" for i in range(0, len(hex_data), LINE_WIDTH)
    ]

    new_lines = lines[: begin_idx + 1] + payload + lines[end_idx:]
    return "\n".join(new_lines) + "\n"


def _plugin_version(plugin_source: bytes) -> str:
    """Read ``__version__`` out of an extera plugin .py/.plugin payload."""
    for line in plugin_source.decode("utf-8", "replace").splitlines():
        match = re.fullmatch(r"""__version__ = ["'](.+)["']""", line.strip())
        if match:
            return match.group(1)

    raise ValueError("badges-sdk payload has no __version__")


def stamp_badges_sdk_version(source: str, version: str) -> str:
    """Point the runtime bootstrap at the version of the embedded badges-sdk."""
    lines = source.splitlines()

    for i, line in enumerate(lines):
        if line.startswith(BADGES_SDK_VERSION_PREFIX):
            lines[i] = f'{BADGES_SDK_VERSION_PREFIX}"{version}"'
            return "\n".join(lines) + "\n"

    raise ValueError(f"{BADGES_SDK_VERSION_PREFIX.strip()} not found in source")


def embed_source(
    source: str,
    dex: Optional[bytes] = None,
    resources: Optional[bytes] = None,
    badges_sdk: Optional[bytes] = None,
) -> str:
    """Embed the given payloads; a payload left out keeps its current block."""
    if dex is not None:
        source = embed_block(source, DEX_BEGIN, DEX_END, dex)
    if resources is not None:
        source = embed_block(source, RESOURCES_BEGIN, RESOURCES_END, resources)
    if badges_sdk is not None:
        source = embed_block(source, BADGES_SDK_BEGIN, BADGES_SDK_END, badges_sdk)
        source = stamp_badges_sdk_version(source, _plugin_version(badges_sdk))
    return source


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Plugin Python source file")
    parser.add_argument(
        "output",
        type=Path,
        nargs="?",
        help="Output file (defaults to rewriting the source in place)",
    )
    parser.add_argument("--dex", type=Path, help="classes.dex to embed")
    parser.add_argument("--resources", type=Path, help="resources.zip to embed")
    parser.add_argument(
        "--badges-sdk",
        type=Path,
        help="badges-sdk.plugin to embed for the on-device bootstrap",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output: Path = args.output or args.source

    if args.dex is None and args.resources is None and args.badges_sdk is None:
        print("error: nothing to embed, pass --dex, --resources and/or --badges-sdk")
        return 2

    dex = args.dex.read_bytes() if args.dex else None
    resources = args.resources.read_bytes() if args.resources else None
    badges_sdk = args.badges_sdk.read_bytes() if args.badges_sdk else None

    try:
        embedded = embed_source(
            args.source.read_text(encoding="utf-8"), dex, resources, badges_sdk
        )
    except ValueError as e:
        print(f"error: {e} ({args.source})")
        return 1

    output.write_text(embedded, encoding="utf-8")

    embedded_sizes = ", ".join(
        f"{len(payload)} bytes of {name}"
        for name, payload in (
            ("dex", dex),
            ("resources", resources),
            ("badges-sdk", badges_sdk),
        )
        if payload is not None
    )
    print(f"embedded {embedded_sizes} into {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
