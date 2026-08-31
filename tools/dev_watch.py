#!/usr/bin/env python3
"""Ожидает изменения файлов tg-streaks.py, classes.dex, resources.zip и пр. файлов
после чего отправляет собраный tg-streaks.plugin на устройство

Использование: dev_watch.py <source.py> <classes.dex> <resources-dir>
                            [--badges-sdk badges-sdk-loader.py] [--debug] [--poll SECONDS]
"""

import argparse
import logging
import os
import sys
import tempfile
import threading
import time
from pathlib import Path
from exteragram_utils.dev_client import (
    AdbManager,
    DeviceConnection,
    parse_metadata,
)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from embed_assets import embed_source  # noqa: E402
from pack_resources import pack  # noqa: E402

# Из-за большого размера файла, сервер может не успеть обработать такую нагрузку за одну секунду
SOCKET_TIMEOUT = 30.0

_send_lock = threading.Lock()
_original_send_message = DeviceConnection.send_message


def _serialized_send_message(self, action, arguments=None):
    with _send_lock:
        if self.socket is not None:
            self.socket.settimeout(SOCKET_TIMEOUT)
        return _original_send_message(self, action, arguments)


DeviceConnection.send_message = _serialized_send_message  # ty:ignore[invalid-assignment]


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
    badges_sdk_path: str | None,
    zip_path: str,
    temp_path: str,
) -> str | None:
    """Возвращает файл плагина с встроенными в него ресурсами"""
    logger = logging.getLogger("watch")

    try:
        pack(Path(resources_dir), Path(zip_path))

        with open(source_path, "r", encoding="utf-8") as f:
            source = f.read()
        with open(dex_path, "rb") as f:
            dex = f.read()
        with open(zip_path, "rb") as f:
            resources = f.read()

        badges_sdk = None
        if badges_sdk_path is not None:
            with open(badges_sdk_path, "rb") as f:
                badges_sdk = f.read()

        content = embed_source(source, dex, resources, badges_sdk)
    except (OSError, ValueError) as e:
        logger.error(f"Failed to embed assets into '{source_path}': {e}")
        return None

    with open(temp_path, "w", encoding="utf-8") as f:
        f.write(content)

    embedded_sizes = ", ".join(
        f"{len(payload) / 1024} kbytes of {name}"
        for name, payload in (
            ("dex", dex),
            ("resources", resources),
            ("badges-sdk", badges_sdk),
        )
        if payload is not None
    )

    logger.info(f"Embedded {embedded_sizes} into {temp_path}")
    return content


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)

    # fmt: off
    parser.add_argument("source",                           help="Plugin Python source file")
    parser.add_argument("dex",                              help="Compiled classes.dex to embed")
    parser.add_argument("resources",                        help="Resources directory to pack and embed")
    parser.add_argument("--badges-sdk",                     help="badges-sdk-loader.py to embed for the in-process engine loading")
    parser.add_argument("--debug", action="store_true",     help="Enable device debugger")
    parser.add_argument("--poll", type=float, default=1.0,  help="Poll interval in seconds")
    parser.add_argument("--log-level", choices=["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"], default="INFO")
    # fmt: on

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

    badges_sdk_path = args.badges_sdk
    if badges_sdk_path is not None and not os.path.isfile(badges_sdk_path):
        logger.warning(
            f"badges-sdk loader '{badges_sdk_path}' is missing, embedding without it "
            "(run 'just badges-sdk' to fetch it)"
        )
        badges_sdk_path = None

    adb = AdbManager()

    if not adb.setup_device(args.debug):
        logger.error("Failed to set up adb connection")
        return 1

    connection = DeviceConnection(
        debug_enabled=args.debug, response_timeout=int(SOCKET_TIMEOUT)
    )

    if not connection.connect():
        logger.error("Failed to connect to the device")
        return 1

    temp_dir = tempfile.mkdtemp(prefix=f"{plugin_id}-")
    temp_path = os.path.join(temp_dir, os.path.basename(args.source))
    zip_path = os.path.join(temp_dir, "resources.zip")

    logger.info(
        f"Watching '{args.source}', '{args.dex}' and '{args.resources}' for plugin '{plugin_id}'"
    )

    # при запуске принудительно загружаем билд
    last_source = last_dex = last_resources = last_badges_sdk = object()

    try:
        while True:
            source_mtime = _mtime(args.source)
            dex_mtime = _mtime(args.dex)
            resources_mtime = _tree_mtime(args.resources)
            badges_sdk_mtime = _mtime(badges_sdk_path) if badges_sdk_path else None

            if dex_mtime is None:
                if last_dex is not None:
                    logger.warning(f"Waiting for dex '{args.dex}' to appear...")
                    last_dex = None
                time.sleep(args.poll)
                continue

            if (source_mtime, dex_mtime, resources_mtime, badges_sdk_mtime) == (
                last_source,
                last_dex,
                last_resources,
                last_badges_sdk,
            ):
                time.sleep(args.poll)
                continue

            last_source, last_dex, last_resources, last_badges_sdk = (
                source_mtime,
                dex_mtime,
                resources_mtime,
                badges_sdk_mtime,
            )

            content = _build_temp(
                args.source,
                args.dex,
                args.resources,
                badges_sdk_path,
                zip_path,
                temp_path,
            )
            if content is None:
                time.sleep(args.poll)
                continue

            if connection.write_plugin(plugin_id, content):
                # сервер не ждёт загрузки плагина и сразу отвечает, поэтому ждём сами
                time.sleep(max(1.0, len(content) / float(4 << 20)))

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
