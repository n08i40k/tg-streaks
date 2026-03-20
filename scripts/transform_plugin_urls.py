#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import sys

RELEASE_DEX_URL = (
    'DEX_URL = '
    'f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases/download/{__version__}/classes.dex"'
)
RELEASE_RESOURCES_URL = (
    'RESOURCES_URL = '
    'f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases/download/{__version__}/resources.zip"'
)
RELEASE_DEBUG_MODE = "DEBUG_MODE = False"

DEBUG_MODE_PATTERN = re.compile(r"^DEBUG_MODE\s*=.*$", re.MULTILINE)
DEX_URL_PATTERN = re.compile(r"^DEX_URL\s*=.*$", re.MULTILINE)
RESOURCES_URL_PATTERN = re.compile(r"^RESOURCES_URL\s*=.*$", re.MULTILINE)


def transform(text: str, mode: str) -> str:
    if mode == "clean":
        text = DEBUG_MODE_PATTERN.sub(RELEASE_DEBUG_MODE, text)
        text = DEX_URL_PATTERN.sub(RELEASE_DEX_URL, text)
        text = RESOURCES_URL_PATTERN.sub(RELEASE_RESOURCES_URL, text)
        return text

    if mode == "smudge":
        return text

    raise ValueError(f"unsupported mode: {mode}")


def check_release(text: str) -> int:
    debug_mode_match = DEBUG_MODE_PATTERN.search(text)
    dex_match = DEX_URL_PATTERN.search(text)
    resources_match = RESOURCES_URL_PATTERN.search(text)

    if debug_mode_match is None or dex_match is None or resources_match is None:
        return 1

    return 0 if (
        debug_mode_match.group(0) == RELEASE_DEBUG_MODE
        and dex_match.group(0) == RELEASE_DEX_URL
        and resources_match.group(0) == RELEASE_RESOURCES_URL
    ) else 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Rewrite plugin asset URLs to release URLs for staged content."
    )
    parser.add_argument(
        "--mode",
        choices=("clean", "smudge", "check-release"),
        required=True,
        help="Transformation direction or staged-content validation mode.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    text = sys.stdin.read()

    if args.mode == "check-release":
        return check_release(text)

    sys.stdout.write(transform(text, args.mode))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
