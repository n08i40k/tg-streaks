#!/usr/bin/env python3
"""Capture docs screenshots straight off an adb-connected device.

Grabs the framebuffer, crops it, and writes it into docs/public/screenshots
under the name a `<Screenshot/>` usage expects — same JPEG settings as the
screenshots already in the repo. Also carries the small device helpers needed
to line a shot up (state, ui dump, tap, back, swipe, system night mode).

It never decides *what* to photograph: whatever is on the screen right now is
what gets saved.

Stdlib only. Needs `adb` and ImageMagick (`magick`).
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import shutil
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
SHOTS = REPO / "docs" / "public" / "screenshots"

# matches the JPEGs already committed under docs/public/screenshots
JPEG_ARGS = [
    "-strip",
    "-sampling-factor", "2x2",
    "-interlace", "JPEG",
    "-quality", "87",
]

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def preview_dir() -> Path:
    job = os.environ.get("CLAUDE_JOB_DIR")
    if job and (Path(job) / "tmp").is_dir():
        return Path(job) / "tmp"
    return Path(tempfile.gettempdir())


# --------------------------------------------------------------------------- adb


class Device:
    def __init__(self, serial: str | None):
        if not shutil.which("adb"):
            die("adb не найден")
        self.serial = serial or self._pick()

    @staticmethod
    def _pick() -> str:
        out = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, check=False
        ).stdout
        devs = [
            line.split()[0]
            for line in out.splitlines()[1:]
            if line.strip() and line.split()[-1] == "device"
        ]
        if not devs:
            die("нет подключённых устройств (adb devices)")
        if len(devs) > 1:
            die(f"несколько устройств: {', '.join(devs)} — укажи --serial")
        return devs[0]

    def _args(self, *rest: str) -> list[str]:
        return ["adb", "-s", self.serial, *rest]

    def shell(self, cmd: str) -> str:
        proc = subprocess.run(
            self._args("shell", cmd), capture_output=True, text=True, check=False
        )
        if proc.returncode != 0:
            die(f"adb shell failed: {proc.stderr.strip()}")
        return proc.stdout

    def screencap(self) -> bytes:
        proc = subprocess.run(
            self._args("exec-out", "screencap", "-p"), capture_output=True, check=False
        )
        if proc.returncode != 0 or not proc.stdout.startswith(b"\x89PNG"):
            die("screencap не отдал PNG (экран заблокирован? FLAG_SECURE?)")
        return proc.stdout

    def pull(self, remote: str, local: Path) -> None:
        subprocess.run(
            self._args("pull", remote, str(local)), capture_output=True, check=False
        )


def magick_run(args: list[str], stdin: bytes | None = None) -> bytes:
    exe = shutil.which("magick") or shutil.which("convert")
    if not exe:
        die("ImageMagick не найден (нужен `magick`)")
    proc = subprocess.run([exe, *args], input=stdin, capture_output=True, check=False)
    if proc.returncode != 0:
        die(f"magick failed: {proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout


# --------------------------------------------------------------------------- ui dump


def dump_nodes(dev: Device) -> list[dict]:
    remote = "/sdcard/.tg-streaks-ui.xml"
    dev.shell(f"uiautomator dump {remote} >/dev/null 2>&1")
    local = preview_dir() / "ui.xml"
    dev.pull(remote, local)
    dev.shell(f"rm -f {remote}")
    if not local.is_file():
        die("не удалось получить дамп uiautomator")
    nodes: list[dict] = []
    for e in ET.parse(local).iter("node"):
        m = BOUNDS_RE.fullmatch(e.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        nodes.append(
            {
                "cls": e.get("class", "").split(".")[-1],
                "text": (e.get("text") or "").strip(),
                "desc": (e.get("content-desc") or "").strip(),
                "clickable": e.get("clickable") == "true",
                "box": (x1, y1, x2 - x1, y2 - y1),
            }
        )
    return nodes


def find_node(nodes: list[dict], needle: str) -> dict:
    low = needle.lower()
    exact = [n for n in nodes if low in (n["text"].lower(), n["desc"].lower())]
    partial = [
        n for n in nodes if low in n["text"].lower() or low in n["desc"].lower()
    ]
    hits = exact or partial
    if not hits:
        die(f"не нашёл элемент с текстом {needle!r} — посмотри `ui`")
    clickable = [n for n in hits if n["clickable"]] or hits
    return clickable[0]


# --------------------------------------------------------------------------- crop


def parse_crop(spec: str | None, band: str | None, w: int, h: int) -> tuple[int, ...] | None:
    if spec and band:
        die("--crop и --band взаимоисключающие")
    if band:
        try:
            y1, y2 = (int(v) for v in band.split("-", 1))
        except ValueError:
            die("--band ждёт Y1-Y2, например 300-550")
        return (0, max(0, y1), w, min(h, y2) - max(0, y1))
    if spec:
        parts = spec.split(",")
        if len(parts) != 4 or not all(p.strip().lstrip("-").isdigit() for p in parts):
            die("--crop ждёт X,Y,W,H")
        return tuple(int(p) for p in parts)
    return None


def resolve_target(rel: str, theme: str | None) -> str:
    """`settings/emoji-packs` + theme -> `settings/emoji-packs-light.jpg`."""
    if rel.endswith(".jpg") or rel.endswith(".jpeg") or rel.endswith(".png"):
        return rel
    if not theme:
        die("укажи --theme light|dark или полное имя файла")
    return f"{rel}-{theme}.jpg"


# --------------------------------------------------------------------------- commands


def cmd_state(dev: Device, args: argparse.Namespace) -> int:
    print(f"serial: {dev.serial}")
    print(dev.shell('dumpsys power | grep -m1 "mWakefulness="').strip())
    print("night mode:", dev.shell("cmd uimode night").strip().splitlines()[-1])
    print(dev.shell("wm size").strip())
    focus = dev.shell('dumpsys window | grep -m1 "mCurrentFocus"').strip()
    print(focus or "mCurrentFocus=?")
    return 0


def cmd_screen(dev: Device, args: argparse.Namespace) -> int:
    png = dev.screencap()
    out = Path(args.out) if args.out else preview_dir() / "screen.png"
    out.write_bytes(png)
    small = out.with_name(out.stem + "-small.png")
    magick_run(["png:-", "-resize", f"{args.preview_width}x", str(small)], stdin=png)
    dims = magick_run(["identify", "-format", "%wx%h", str(out)]).decode()
    print(f"снимок:  {out}  ({dims})")
    print(f"превью:  {small}")
    return 0


def cmd_grab(dev: Device, args: argparse.Namespace) -> int:
    rel = resolve_target(args.rel, args.theme)
    dest = SHOTS / rel
    if dest.exists() and not args.force:
        from_ = magick_run(["identify", "-format", "%c", str(dest)]).decode()
        if "tg-streaks-doc-placeholder" not in from_:
            print(f"замена настоящего скриншота: {rel}")

    png = dev.screencap()
    w, h = (
        int(v)
        for v in magick_run(["identify", "-format", "%w %h", "png:-"], stdin=png)
        .decode()
        .split()
    )
    box = parse_crop(args.crop, args.band, w, h)

    argv: list[str] = ["png:-"]
    if box:
        x, y, cw, ch = box
        if cw <= 0 or ch <= 0:
            die(f"пустая область обрезки: {box}")
        argv += ["-crop", f"{cw}x{ch}+{x}+{y}", "+repage"]
    if args.width:
        argv += ["-filter", "Lanczos", "-resize", f"{args.width}x"]
    argv += JPEG_ARGS + [f"jpg:{dest}"]

    dest.parent.mkdir(parents=True, exist_ok=True)
    magick_run(argv, stdin=png)

    dims = magick_run(["identify", "-format", "%wx%h", str(dest)]).decode()
    print(f"сохранено  docs/public/screenshots/{rel}  ({dims})")
    if args.preview:
        small = preview_dir() / (dest.stem + "-preview.png")
        magick_run([str(dest), "-resize", f"{args.preview_width}x", str(small)])
        print(f"превью     {small}")
    return 0


def cmd_ui(dev: Device, args: argparse.Namespace) -> int:
    nodes = dump_nodes(dev)
    shown = 0
    for n in nodes:
        if not (n["text"] or n["desc"] or (args.all and n["clickable"])):
            continue
        x, y, w, h = n["box"]
        mark = "*" if n["clickable"] else " "
        label = n["text"] or n["desc"]
        print(f"{mark} {n['cls']:<18} {x},{y} {w}x{h}  center={x + w // 2},{y + h // 2}")
        print(f"    {label[:90]!r}")
        shown += 1
    print(f"\nузлов показано: {shown} из {len(nodes)}  (* = кликабельный)")
    return 0


def cmd_tap(dev: Device, args: argparse.Namespace) -> int:
    if args.text:
        node = find_node(dump_nodes(dev), args.text)
        x, y, w, h = node["box"]
        cx, cy = x + w // 2, y + h // 2
        print(f"тап по {node['text'] or node['desc']!r} → {cx},{cy}")
    else:
        try:
            cx, cy = (int(v) for v in args.at.split(","))
        except (ValueError, AttributeError):
            die("укажи --text ... или координаты X,Y")
        print(f"тап → {cx},{cy}")
    dev.shell(f"input tap {cx} {cy}")
    time.sleep(args.settle)
    return 0


def cmd_back(dev: Device, args: argparse.Namespace) -> int:
    dev.shell("input keyevent KEYCODE_BACK")
    time.sleep(args.settle)
    return 0


def cmd_swipe(dev: Device, args: argparse.Namespace) -> int:
    size = dev.shell("wm size")
    m = re.search(r"(\d+)x(\d+)", size)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    cx = w // 2
    y1, y2 = (int(h * 0.7), int(h * 0.7) - args.px)
    if args.direction == "down":
        y1, y2 = y2, y1
    dev.shell(f"input swipe {cx} {y1} {cx} {y2} {args.duration}")
    time.sleep(args.settle)
    return 0


def cmd_night(dev: Device, args: argparse.Namespace) -> int:
    print(dev.shell(f"cmd uimode night {'yes' if args.mode == 'on' else 'no'}").strip())
    print(
        "напоминание: exteraGram переключится вслед за системой только если в нём\n"
        "включён автоночной режим «по системе» — иначе тему меняет пользователь"
    )
    time.sleep(args.settle)
    return 0


# --------------------------------------------------------------------------- cli


def main() -> int:
    ap = argparse.ArgumentParser(prog="shoot.py", description=__doc__)
    ap.add_argument("--serial", help="adb serial, если устройств несколько")
    ap.add_argument("--settle", type=float, default=1.2, help="пауза после действия, с")
    ap.add_argument("--preview-width", type=int, default=420)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("state", help="устройство, экран, тема, текущее окно")
    p.set_defaults(func=cmd_state)

    p = sub.add_parser("screen", help="полный снимок экрана + уменьшенное превью")
    p.add_argument("--out", help="куда положить PNG (по умолчанию во временную папку)")
    p.set_defaults(func=cmd_screen)

    p = sub.add_parser("grab", help="снять и сохранить в docs/public/screenshots")
    p.add_argument("rel", help="settings/emoji-packs  или  settings/emoji-packs-light.jpg")
    p.add_argument("--theme", choices=["light", "dark"], help="суффикс имени файла")
    p.add_argument("--band", help="полоса по вертикали во всю ширину: Y1-Y2")
    p.add_argument("--crop", help="произвольная область: X,Y,W,H")
    p.add_argument(
        "--width",
        type=int,
        help="масштабировать после обрезки до этой ширины (в доках принято 1080)",
    )
    p.add_argument("--preview", action="store_true", help="сохранить уменьшённую копию")
    p.add_argument("--force", action="store_true")
    p.set_defaults(func=cmd_grab)

    p = sub.add_parser("ui", help="дамп элементов с текстом и координатами")
    p.add_argument("--all", action="store_true", help="и кликабельные без текста")
    p.set_defaults(func=cmd_ui)

    p = sub.add_parser("tap", help="тап по координатам или по тексту элемента")
    p.add_argument("at", nargs="?", help="X,Y")
    p.add_argument("--text", help="текст или content-desc элемента")
    p.set_defaults(func=cmd_tap)

    p = sub.add_parser("back", help="системная кнопка «назад»")
    p.set_defaults(func=cmd_back)

    p = sub.add_parser("swipe", help="прокрутка")
    p.add_argument("direction", choices=["up", "down"], nargs="?", default="up")
    p.add_argument("--px", type=int, default=800)
    p.add_argument("--duration", type=int, default=300)
    p.set_defaults(func=cmd_swipe)

    p = sub.add_parser("night", help="системный ночной режим")
    p.add_argument("mode", choices=["on", "off"])
    p.set_defaults(func=cmd_night)

    args = ap.parse_args()
    return args.func(Device(args.serial), args)


if __name__ == "__main__":
    raise SystemExit(main())
