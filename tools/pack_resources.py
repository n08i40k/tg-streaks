#!/usr/bin/env python3
"""Pack the resources/ directory into a reproducible resources.zip.

Entries are sorted and stamped with a fixed timestamp, so the same tree always
produces byte-identical output (the plugin ships the archive embedded in its
source, and a stable archive keeps release diffs meaningful).

Usage: pack_resources.py <resources-dir> <output.zip>
"""

import sys
import zipfile
from pathlib import Path

FIXED_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def pack(root: Path, output: Path) -> int:
    files = sorted(path for path in root.rglob("*") if path.is_file())

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            info = zipfile.ZipInfo(
                str(Path(root.name) / path.relative_to(root)), FIXED_TIMESTAMP
            )
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())

    return len(files)


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {sys.argv[0]} <resources-dir> <output.zip>", file=sys.stderr)
        return 2

    root, output = Path(sys.argv[1]), Path(sys.argv[2])
    if not root.is_dir():
        print(f"error: resources directory not found: {root}", file=sys.stderr)
        return 1

    count = pack(root, output)
    print(f"packed {count} files into {output} ({output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
