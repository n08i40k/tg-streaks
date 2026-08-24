#!/usr/bin/env python3
"""Пережимает видео-ресурсы в VP9.

Использование: optimize_media.py <input-dir> [output-dir] [--jobs N] [--dry-run] [--force]

Если output-dir не указан, файлы перезаписываются на месте. Уже пережатые файлы
помечены в контейнере и пропускаются, пока не передан --force.
"""

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

# Другие форматы выкидывают альфа канал, увы
VPX = "libvpx-vp9"
PIX_FMT = "yuva420p"

CPU_USED = "1"
MAX_FPS = 60

# чтоб не пережимать уже пережатое
MARKER = "TG_STREAKS_OPTIMIZED"

CRF_BY_WIDTH = ((1024, 40), (512, 38), (0, 32))


@dataclass
class Probe:
    width: int
    height: int
    fps: float
    pix_fmt: str
    has_alpha: bool
    optimized: bool


def _run(args: list[str]) -> str:
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{args[0]} failed: {result.stderr.strip()}")
    return result.stdout


def probe(path: Path) -> Probe:
    raw = _run(
        # fmt: off
        [
            "ffprobe",
            "-v",
            "error",
            "-c:v",
            VPX,
            "-select_streams",
            "v:0",
            "-show_streams",
            "-show_format",
            "-of",
            "json",
            str(path),
        ]
        # fmt: on
    )

    probed = json.loads(raw)

    streams = probed.get("streams")
    if not streams:
        raise RuntimeError(f"no video stream: {path}")

    stream = streams[0]
    num, _, den = stream["r_frame_rate"].partition("/")

    # исходники подписаны alpha_mode, а ffmpeg пишет ALPHA_MODE
    tags = {key.lower(): value for key, value in stream.get("tags", {}).items()}
    format_tags = {
        key.lower(): value
        for key, value in probed.get("format", {}).get("tags", {}).items()
    }

    return Probe(
        width=int(stream["width"]),
        height=int(stream["height"]),
        fps=int(num) / int(den or 1),
        pix_fmt=stream["pix_fmt"],
        has_alpha=tags.get("alpha_mode") == "1",
        optimized=format_tags.get(MARKER.lower()) == "1",
    )


def crf_for(width: int) -> int:
    return next(crf for threshold, crf in CRF_BY_WIDTH if width >= threshold)


def encode(source: Path, target: Path, info: Probe) -> None:
    crf = crf_for(info.width)

    # fmt: off
    args = [
        "ffmpeg", "-y", "-v", "error",
        "-c:v", VPX, "-i", str(source),
    ]

    if info.fps > MAX_FPS:
        args += ["-vf", f"fps={MAX_FPS}"]

    args += [
        # без bitexact webm к хуям ломается
        "-fflags", "+bitexact",
        "-flags", "+bitexact",
        "-c:v", VPX,
        "-pix_fmt", PIX_FMT,
        "-crf", str(crf),
        "-b:v", "0",
        "-row-mt", "1",
        "-cpu-used", CPU_USED,
        "-an",
        "-metadata", f"{MARKER}=1",
        str(target),
    ]
    # fmt: on

    target.parent.mkdir(parents=True, exist_ok=True)
    _run(args)


def keep(source: Path, target: Path) -> None:
    if source == target:
        return

    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def optimize(source: Path, target: Path, dry_run: bool, force: bool) -> tuple[int, int]:
    """Возвращает вес до и после. При dry_run второе значение равно первому."""

    info = probe(source)
    before = source.stat().st_size

    plan = f"crf {crf_for(info.width)}"
    if info.fps > MAX_FPS:
        plan += f", fps {info.fps:g} -> {MAX_FPS}"

    if info.optimized and not force:
        print(f"{source}: {before} bytes, пропуск (уже оптимизирован)")
        keep(source, target)
        return before, before

    if dry_run:
        print(f"{source}: {info.width}x{info.height} alpha={info.has_alpha} ({plan})")
        return before, before

    # промежуточный файл, чтобы не потерять исходник при перезаписи
    with tempfile.TemporaryDirectory() as tmp:
        staged = Path(tmp) / source.name
        encode(source, staged, info)

        result = probe(staged)
        if info.has_alpha and not result.has_alpha:
            raise RuntimeError(f"alpha channel lost: {source}")

        after = staged.stat().st_size
        if after >= before:
            print(f"{source}: {before} -> {after} bytes, пропуск (стало больше)")
            keep(source, target)
            return before, before

        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staged), target)

    print(f"{source}: {before} -> {after} bytes ({plan})")
    return before, after


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)

    # fmt: off
    parser.add_argument("input",                type=Path,              help="Directory to scan for .webm files")
    parser.add_argument("output",               type=Path, nargs="?",   help="Output directory (defaults to rewriting in place)")
    parser.add_argument("--jobs",       "-j",   type=int, default=4,    help="Parallel ffmpeg processes")
    parser.add_argument("--dry-run",            action="store_true",    help="Only report what would be done")
    parser.add_argument("--force",              action="store_true",    help="Re-encode even already optimized files")
    # fmt: on

    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if not args.input.is_dir():
        print(f"error: input directory not found: {args.input}", file=sys.stderr)
        return 1

    for command in ("ffmpeg", "ffprobe"):
        if shutil.which(command) is None:
            print(f"error: {command} not found in PATH", file=sys.stderr)
            return 1

    output: Optional[Path] = args.output or args.input
    sources = sorted(args.input.rglob("*.webm"))

    if not sources:
        print(f"error: no .webm files under {args.input}", file=sys.stderr)
        return 1

    def task(source: Path) -> tuple[int, int]:
        target = output / source.relative_to(args.input)
        return optimize(source, target, args.dry_run, args.force)

    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(task, sources))

    before = sum(size for size, _ in results)
    after = sum(size for _, size in results)
    saved = (1 - after / before) * 100 if before else 0

    print(f"{len(sources)} files: {before} -> {after} bytes (-{saved:.1f}%)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
