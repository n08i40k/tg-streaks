#!/usr/bin/env python3
from __future__ import annotations

import colorsys
import copy
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE_JSON_PATH = ROOT / "base.json"
LEVELS_SOURCE_PATH = ROOT / "tg-streaks.py"
OUTPUT_DIR = ROOT / "emoji_variants"

LEVEL_PATTERN = re.compile(
    r"^\s*([A-Z0-9_]+)\s*=\s*StreakLevel\(\s*\d+\s*,\s*\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)\s*\)"
)


def parse_streak_levels(path: Path) -> list[tuple[str, tuple[int, int, int]]]:
    levels: list[tuple[str, tuple[int, int, int]]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = LEVEL_PATTERN.match(line)
        if not match:
            continue
        name = match.group(1).lower()
        rgb = (int(match.group(2)), int(match.group(3)), int(match.group(4)))
        levels.append((name, rgb))
    if not levels:
        raise RuntimeError("No streak levels found in tg-streaks.py")
    return levels


def _recolor_rgb(rgb: tuple[float, float, float], target: tuple[float, float, float]) -> tuple[float, float, float]:
    src_h, src_l, src_s = colorsys.rgb_to_hls(*rgb)
    tgt_h, _, tgt_s = colorsys.rgb_to_hls(*target)

    if tgt_s < 1e-6:
        # Cold tier is grayscale; preserve source luminance.
        return src_l, src_l, src_l

    new_h = tgt_h
    # Keep source lightness for existing shading while nudging saturation toward the target.
    new_l = src_l
    new_s = max(0.0, min(1.0, tgt_s * (0.35 + 0.65 * src_s)))
    return colorsys.hls_to_rgb(new_h, new_l, new_s)


def _recolor_color_array(arr: list[float], target_rgb: tuple[float, float, float]) -> list[float]:
    if len(arr) < 3:
        return arr

    rgb = (float(arr[0]), float(arr[1]), float(arr[2]))
    nr, ng, nb = _recolor_rgb(rgb, target_rgb)
    result = list(arr)
    result[0] = round(nr, 12)
    result[1] = round(ng, 12)
    result[2] = round(nb, 12)
    return result


def _recolor_color_property(prop: dict, target_rgb: tuple[float, float, float]) -> None:
    k = prop.get("k")
    if prop.get("a") == 0 and isinstance(k, list) and len(k) >= 3 and all(
        isinstance(v, (int, float)) for v in k[:3]
    ):
        prop["k"] = _recolor_color_array(k, target_rgb)
        return

    if prop.get("a") == 1 and isinstance(k, list):
        for keyframe in k:
            if not isinstance(keyframe, dict):
                continue
            if isinstance(keyframe.get("s"), list):
                keyframe["s"] = _recolor_color_array(keyframe["s"], target_rgb)
            if isinstance(keyframe.get("e"), list):
                keyframe["e"] = _recolor_color_array(keyframe["e"], target_rgb)


def _recolor_gradient_stops(stops: list[float], color_points: int, target_rgb: tuple[float, float, float]) -> list[float]:
    recolored = list(stops)
    max_colors = min(color_points, len(recolored) // 4)
    for i in range(max_colors):
        base = i * 4
        rgb = (
            float(recolored[base + 1]),
            float(recolored[base + 2]),
            float(recolored[base + 3]),
        )
        nr, ng, nb = _recolor_rgb(rgb, target_rgb)
        recolored[base + 1] = round(nr, 12)
        recolored[base + 2] = round(ng, 12)
        recolored[base + 3] = round(nb, 12)
    return recolored


def _recolor_gradient_property(prop: dict, target_rgb: tuple[float, float, float]) -> None:
    p = prop.get("p")
    k_prop = prop.get("k")
    if not isinstance(p, int) or not isinstance(k_prop, dict):
        return

    k = k_prop.get("k")
    if k_prop.get("a") == 0 and isinstance(k, list) and all(
        isinstance(v, (int, float)) for v in k
    ):
        k_prop["k"] = _recolor_gradient_stops(k, p, target_rgb)
        return

    if k_prop.get("a") == 1 and isinstance(k, list):
        for keyframe in k:
            if not isinstance(keyframe, dict):
                continue
            s = keyframe.get("s")
            e = keyframe.get("e")
            if isinstance(s, list) and all(isinstance(v, (int, float)) for v in s):
                keyframe["s"] = _recolor_gradient_stops(s, p, target_rgb)
            if isinstance(e, list) and all(isinstance(v, (int, float)) for v in e):
                keyframe["e"] = _recolor_gradient_stops(e, p, target_rgb)


def recolor_document(node: object, target_rgb: tuple[float, float, float]) -> None:
    if isinstance(node, dict):
        color_prop = node.get("c")
        if isinstance(color_prop, dict):
            _recolor_color_property(color_prop, target_rgb)

        gradient_prop = node.get("g")
        if isinstance(gradient_prop, dict):
            _recolor_gradient_property(gradient_prop, target_rgb)

        for key, value in node.items():
            if key in {"c", "g"}:
                continue
            recolor_document(value, target_rgb)
    elif isinstance(node, list):
        for item in node:
            recolor_document(item, target_rgb)


def main() -> None:
    levels = parse_streak_levels(LEVELS_SOURCE_PATH)
    base_doc = json.loads(BASE_JSON_PATH.read_text(encoding="utf-8"))

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    for level_name, rgb_int in levels:
        target_rgb = tuple(channel / 255.0 for channel in rgb_int)
        doc = copy.deepcopy(base_doc)
        doc["nm"] = f"streak-{level_name}"
        recolor_document(doc, target_rgb)

        out_path = OUTPUT_DIR / f"{level_name}.json"
        out_path.write_text(
            json.dumps(doc, separators=(",", ":"), ensure_ascii=False),
            encoding="utf-8",
        )
        print(f"wrote {out_path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
