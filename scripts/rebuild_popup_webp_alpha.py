#!/usr/bin/env python3

import argparse
import json
import subprocess
from pathlib import Path

import numpy as np


def probe_video(path: Path) -> tuple[int, int, float]:
    probe = subprocess.check_output(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height,r_frame_rate",
            "-of",
            "json",
            str(path),
        ],
        text=True,
    )
    stream = json.loads(probe)["streams"][0]
    width = int(stream["width"])
    height = int(stream["height"])
    num, den = map(int, stream["r_frame_rate"].split("/"))
    fps = num / den if den else float(num)
    return width, height, fps


def rebuild_file(input_path: Path, output_path: Path, low: int, high: int) -> int:
    width, height, fps = probe_video(input_path)
    frame_size = width * height * 4

    decoder = subprocess.Popen(
        [
            "ffmpeg",
            "-v",
            "error",
            "-c:v",
            "libvpx-vp9",
            "-i",
            str(input_path),
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgba",
            "-",
        ],
        stdout=subprocess.PIPE,
    )

    encoder = subprocess.Popen(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "rgba",
            "-s",
            f"{width}x{height}",
            "-r",
            str(fps),
            "-i",
            "-",
            "-an",
            "-c:v",
            "libwebp_anim",
            "-quality",
            "100",
            "-lossless",
            "1",
            "-loop",
            "0",
            "-preset",
            "drawing",
            str(output_path),
        ],
        stdin=subprocess.PIPE,
    )

    frames = 0
    while True:
        buf = decoder.stdout.read(frame_size)
        if not buf:
            break
        if len(buf) != frame_size:
            raise RuntimeError(f"{input_path.name}: incomplete frame {len(buf)}")

        arr = np.frombuffer(buf, dtype=np.uint8).reshape((height, width, 4)).copy()
        rgb = arr[:, :, :3].astype(np.uint16)
        max_channel = rgb.max(axis=2)

        alpha = np.zeros((height, width), dtype=np.uint16)
        opaque = max_channel >= high
        semi = (max_channel > low) & (max_channel < high)

        alpha[opaque] = 255
        alpha[semi] = (
            (max_channel[semi] - low) * 255 + (high - low) // 2
        ) // (high - low)

        out_rgb = rgb.copy()
        non_zero_alpha = alpha > 0
        partially_transparent = non_zero_alpha & (~opaque)

        out_rgb[partially_transparent] = np.minimum(
            255,
            (
                rgb[partially_transparent] * 255
                + (alpha[partially_transparent, None] // 2)
            )
            // alpha[partially_transparent, None],
        )
        out_rgb[~non_zero_alpha] = 0

        arr[:, :, :3] = out_rgb.astype(np.uint8)
        arr[:, :, 3] = alpha.astype(np.uint8)

        encoder.stdin.write(arr.tobytes())
        frames += 1

    encoder.stdin.close()
    encoder.wait()
    decoder_code = decoder.wait()

    if decoder_code != 0 or encoder.returncode != 0:
        raise RuntimeError(
            f"ffmpeg failed for {input_path.name}: "
            f"decoder={decoder_code}, encoder={encoder.returncode}"
        )

    return frames


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Rebuild popup webp files from webm with threshold-based alpha recovery."
    )
    parser.add_argument(
        "names",
        nargs="*",
        default=["10", "30", "100", "200"],
        help="Popup basenames without extension, for example: 10 30 100 200",
    )
    parser.add_argument(
        "--resources-dir",
        default="resources/upgrade-popups",
        help="Directory containing .webm and .webp popup files",
    )
    parser.add_argument(
        "--low",
        type=int,
        default=4,
        help="Lower threshold for alpha reconstruction",
    )
    parser.add_argument(
        "--high",
        type=int,
        default=80,
        help="Upper threshold for alpha reconstruction",
    )
    args = parser.parse_args()

    resources_dir = Path(args.resources_dir)
    if args.high <= args.low:
        raise SystemExit("--high must be greater than --low")

    for name in args.names:
        input_path = resources_dir / f"{name}.webm"
        output_path = resources_dir / f"{name}.webp"
        frames = rebuild_file(input_path, output_path, args.low, args.high)
        print(f"{name}: {frames} frames")


if __name__ == "__main__":
    main()
