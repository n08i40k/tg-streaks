#!/usr/bin/env python3
"""Встраивает classes.dex и resources.zip в плагин в виде комментария
с кодировкой base64 и сжатием LZMA.

Использование: embed_assets.py [--dex classes.dex] [--resources resources.zip]
                               <source.py> [output.py]

Если output.py не указан, скрипт перезаписывает source.py.
"""

import argparse
import base64
import lzma
from pathlib import Path
from typing import Optional

DEX_BEGIN = "# === EMDEDDED DEX BEGIN ==="
DEX_END = "# === EMDEDDED DEX END ==="
RESOURCES_BEGIN = "# === EMDEDDED RESOURCES BEGIN ==="
RESOURCES_END = "# === EMDEDDED RESOURCES END ==="

LINE_WIDTH = 120
LZMA_PRESET = 9 | lzma.PRESET_EXTREME


def compress(data: bytes) -> bytes:
    return lzma.compress(data, format=lzma.FORMAT_XZ, preset=LZMA_PRESET)


def replace_block(source: str, begin: str, end: str, payload: list[str]) -> str:
    """Возвращает исходник с телом payload между маркерами begin и end."""

    lines = source.splitlines()

    try:
        begin_idx = next(i for i, line in enumerate(lines) if line.strip() == begin)
        end_idx = next(i for i, line in enumerate(lines) if line.strip() == end)
    except StopIteration:
        raise ValueError(f"markers not found in source: {begin}") from None

    if end_idx <= begin_idx:
        raise ValueError(f"END marker precedes BEGIN marker: {begin}")

    new_lines = lines[: begin_idx + 1] + payload + lines[end_idx:]
    return "\n".join(new_lines) + "\n"


def embed_block(source: str, begin: str, end: str, data: bytes) -> str:
    """Возвращает цельный блок с упакованным телом обёрнутым в begin и end."""

    encoded = base64.b64encode(compress(data)).decode("ascii")
    payload = [
        f"# {encoded[i : i + LINE_WIDTH]}" for i in range(0, len(encoded), LINE_WIDTH)
    ]

    return replace_block(source, begin, end, payload)


def embed_plain_block(source: str, begin: str, end: str, data: bytes) -> str:
    """Вставляет исходник как есть: лоадер сам читает вшитый в него DEX."""

    payload = data.decode("utf-8").splitlines()

    return replace_block(source, begin, end, payload)


def embed_source(
    source: str,
    dex: Optional[bytes] = None,
    resources: Optional[bytes] = None,
) -> str:
    """Встраивает данные файлов в плагин."""

    if dex is not None:
        source = embed_block(source, DEX_BEGIN, DEX_END, dex)

    if resources is not None:
        source = embed_block(source, RESOURCES_BEGIN, RESOURCES_END, resources)

    return source


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)

    # fmt: off
    parser.add_argument("source",       type=Path,              help="Plugin Python source file")
    parser.add_argument("output",       type=Path, nargs="?",   help="Output file (defaults to rewriting the source in place)")
    parser.add_argument("--dex",        type=Path,              help="classes.dex to embed")
    parser.add_argument("--resources",  type=Path,              help="resources.zip to embed")
    # fmt: on

    return parser.parse_args()


def main() -> int:
    args = parse_args()

    output: Path = args.output or args.source

    if args.dex is None and args.resources is None:
        print("error: nothing to embed, pass --dex and/or --resources")
        return 2

    dex = args.dex.read_bytes() if args.dex else None
    resources = args.resources.read_bytes() if args.resources else None

    try:
        embedded = embed_source(
            args.source.read_text(encoding="utf-8"), dex, resources
        )
    except ValueError as e:
        print(f"error: {e} ({args.source})")
        return 1

    output.write_text(embedded, encoding="utf-8")

    embedded_sizes = ", ".join(
        f"{len(payload) / 1024} kbytes of {name}"
        for name, payload in (
            ("dex", dex),
            ("resources", resources),
        )
        if payload is not None
    )

    print(f"embedded {embedded_sizes} into {output}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
