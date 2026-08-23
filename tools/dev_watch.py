#!/usr/bin/env python3
"""Live-reload the plugin on a device while developing.

Watches the plugin's Python source, the compiled classes.dex and the resources
tree. On any change (a manual `just dex` rebuild, an edit to the .py, a new
resource file) it embeds the current dex and a freshly packed resources.zip
into a temporary copy of the source and hands that copy to extera's dev-sync,
which re-uploads and reloads the plugin on the device.

The device side is extera's own dev server (the same one exteragram_utils talks
to over adb); we only build the file and push it — extera does the reload.

Usage: dev_watch.py <source.py> <classes.dex> <resources-dir> [--debug] [--poll SECONDS]
"""

import argparse
import logging
import os
import sys
import tempfile
import time
from pathlib import Path

# exteragram_utils ships extera's dev-sync client (adb setup + device connection)
from exteragram_utils.dev_client import (
    AdbManager,
    DeviceConnection,
    parse_metadata,
)

# the embed/pack helpers live next to this file
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from embed_assets import embed_source  # noqa: E402
from pack_resources import pack  # noqa: E402


def _mtime(path: str) -> float | None:
    try:
        return os.path.getmtime(path)
    except OSError:
        return None


def _tree_mtime(root: str) -> float | None:
    mtimes = [path.stat().st_mtime for path in Path(root).rglob("*") if path.is_file()]
    return max(mtimes) if mtimes else None


def _build_temp(
    source_path: str,
    dex_path: str,
    resources_dir: str,
    zip_path: str,
    temp_path: str,
) -> str | None:
    """Embed the assets into the source and write the temp file. Returns its content."""
    logger = logging.getLogger("watch")

    try:
        pack(Path(resources_dir), Path(zip_path))

        with open(source_path, "r", encoding="utf-8") as f:
            source = f.read()
        with open(dex_path, "rb") as f:
            dex = f.read()
        with open(zip_path, "rb") as f:
            resources = f.read()

        content = embed_source(source, dex, resources)
    except (OSError, ValueError) as e:
        logger.error(f"Failed to embed assets into '{source_path}': {e}")
        return None

    with open(temp_path, "w", encoding="utf-8") as f:
        f.write(content)

    logger.info(
        f"Embedded {len(dex)} bytes of dex and {len(resources)} bytes of resources into {temp_path}"
    )
    return content


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="Plugin Python source file")
    parser.add_argument("dex", help="Compiled classes.dex to embed")
    parser.add_argument("resources", help="Resources directory to pack and embed")
    parser.add_argument("--debug", action="store_true", help="Enable device debugger")
    parser.add_argument(
        "--poll", type=float, default=1.0, help="Poll interval in seconds"
    )
    parser.add_argument(
        "--log-level",
        choices=["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"],
        default="INFO",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    )
    logger = logging.getLogger("watch")

    if not os.path.isfile(args.source):
        logger.error(f"Source file '{args.source}' not found")
        return 1

    if not os.path.isdir(args.resources):
        logger.error(f"Resources directory '{args.resources}' not found")
        return 1

    with open(args.source, "r", encoding="utf-8") as f:
        metadata = parse_metadata(f.read())
    if metadata is None or not metadata.id:
        logger.error(f"'{args.source}' has no valid plugin metadata (__id__)")
        return 1
    plugin_id = metadata.id

    adb = AdbManager()
    if not adb.setup_device(args.debug):
        logger.error("Failed to set up adb connection")
        return 1

    connection = DeviceConnection(debug_enabled=args.debug)
    if not connection.connect():
        logger.error("Failed to connect to the device")
        return 1

    temp_dir = tempfile.mkdtemp(prefix=f"{plugin_id}-")
    temp_path = os.path.join(temp_dir, os.path.basename(args.source))
    zip_path = os.path.join(temp_dir, "resources.zip")

    logger.info(
        f"Watching '{args.source}', '{args.dex}' and '{args.resources}' for plugin '{plugin_id}'"
    )

    # sentinel: force the first build
    last_source = last_dex = last_resources = object()
    try:
        while True:
            source_mtime = _mtime(args.source)
            dex_mtime = _mtime(args.dex)
            resources_mtime = _tree_mtime(args.resources)

            if dex_mtime is None:
                if last_dex is not None:
                    logger.warning(f"Waiting for dex '{args.dex}' to appear...")
                    last_dex = None
                time.sleep(args.poll)
                continue

            if (source_mtime, dex_mtime, resources_mtime) == (
                last_source,
                last_dex,
                last_resources,
            ):
                time.sleep(args.poll)
                continue

            last_source, last_dex, last_resources = (
                source_mtime,
                dex_mtime,
                resources_mtime,
            )

            content = _build_temp(
                args.source, args.dex, args.resources, zip_path, temp_path
            )
            if content is None:
                time.sleep(args.poll)
                continue

            if connection.write_plugin(plugin_id, content):
                time.sleep(0.3)
                if connection.reload_plugin(plugin_id):
                    logger.info(f"Reloaded plugin '{plugin_id}' on device")
                else:
                    logger.warning(f"Failed to reload plugin '{plugin_id}'")
            else:
                logger.warning(f"Failed to upload plugin '{plugin_id}'")

            time.sleep(args.poll)
    except KeyboardInterrupt:
        logger.info("Stopped by user")
    finally:
        connection.stop_debugger()
        connection.disconnect()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
