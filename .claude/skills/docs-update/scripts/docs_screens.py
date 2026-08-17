#!/usr/bin/env python3
"""Screenshot bookkeeping for the tg-streaks docs site (docs/, Next.js + Nextra).

Parses every `<Screenshot .../>` usage in docs/content/**/*.mdx, compares it with
what actually lives in docs/public/screenshots, generates replace-me placeholder
images for the ones that do not exist yet, and can retire an existing screenshot
that a code change made outdated.

Stdlib only. Image generation shells out to ImageMagick 7 (`magick`).
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
DOCS = REPO / "docs"
CONTENT = DOCS / "content"
SHOTS = DOCS / "public" / "screenshots"
ARCHIVE = DOCS / ".screenshots-outdated"

MARKER = "tg-streaks-doc-placeholder"
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp"}
DEFAULT_SIZE = (1080, 1560)

THEMES = {
    "light": {
        "bg": "#f4f4f5",
        "panel": "#e4e4e7",
        "border": "#a1a1aa",
        "title": "#3f3f46",
        "muted": "#71717a",
        "accent": "#b45309",
        "label": "светлая тема",
    },
    "dark": {
        "bg": "#17212b",
        "panel": "#1f2c39",
        "border": "#4b5b6b",
        "title": "#dfe6ec",
        "muted": "#8d9aa8",
        "accent": "#e0a458",
        "label": "тёмная тема",
    },
}

SCREENSHOT_RE = re.compile(r"<Screenshot\b(?P<attrs>.*?)/>", re.DOTALL)
ATTR_RE = re.compile(r'(?P<name>[A-Za-z][\w-]*)\s*=\s*"(?P<value>[^"]*)"')
HEADING_RE = re.compile(r"^(#{1,4})\s+(.*)$")


# --------------------------------------------------------------------------- model


@dataclass
class Shot:
    """One image path referenced by one `<Screenshot/>` usage."""

    rel: str  # path inside public/screenshots
    theme: str  # "light" | "dark" | "single"
    page: str  # content-relative .mdx path
    line: int
    caption: str

    @property
    def path(self) -> Path:
        return SHOTS / self.rel

    @property
    def exists(self) -> bool:
        return self.path.is_file()


@dataclass
class Usage:
    page: str
    line: int
    caption: str
    file: str
    dark: str | None
    warnings: list[str] = field(default_factory=list)


def die(msg: str) -> None:
    print(f"error: {msg}", file=sys.stderr)
    raise SystemExit(1)


def rel_repo(p: Path) -> str:
    try:
        return str(p.relative_to(REPO))
    except ValueError:
        return str(p)


# --------------------------------------------------------------------------- parsing


def mdx_pages() -> list[Path]:
    if not CONTENT.is_dir():
        die(f"{rel_repo(CONTENT)} not found — is this the tg-streaks repo?")
    return sorted(CONTENT.rglob("*.mdx"))


def parse_usages(pages: list[Path] | None = None) -> list[Usage]:
    out: list[Usage] = []
    for page in pages if pages is not None else mdx_pages():
        text = page.read_text(encoding="utf-8")
        rel = str(page.relative_to(CONTENT))
        for m in SCREENSHOT_RE.finditer(text):
            attrs = dict(
                (a.group("name"), a.group("value"))
                for a in ATTR_RE.finditer(m.group("attrs"))
            )
            line = text.count("\n", 0, m.start()) + 1
            file = attrs.get("file", "")
            dark = attrs.get("dark")
            u = Usage(
                page=rel,
                line=line,
                caption=attrs.get("caption", ""),
                file=file,
                dark=dark,
            )
            if not file:
                u.warnings.append("нет обязательного пропа file")
            if not u.caption:
                u.warnings.append("нет обязательного пропа caption")
            if file and not dark:
                u.warnings.append("нет тёмного варианта (проп dark)")
            if file and dark:
                if not file.endswith("-light.jpg") or not dark.endswith("-dark.jpg"):
                    u.warnings.append("имена вне конвенции <name>-light.jpg / -dark.jpg")
            out.append(u)
    return out


def shots_of(usages: list[Usage]) -> list[Shot]:
    out: list[Shot] = []
    for u in usages:
        if u.file:
            out.append(
                Shot(u.file, "light" if u.dark else "single", u.page, u.line, u.caption)
            )
        if u.dark:
            out.append(Shot(u.dark, "dark", u.page, u.line, u.caption))
    return out


def on_disk() -> list[str]:
    if not SHOTS.is_dir():
        return []
    return sorted(
        str(p.relative_to(SHOTS))
        for p in SHOTS.rglob("*")
        if p.is_file() and p.suffix.lower() in IMAGE_EXTS
    )


# --------------------------------------------------------------------------- imagemagick


def magick(args: list[str], *, capture: bool = False) -> str:
    exe = shutil.which("magick") or shutil.which("convert")
    if not exe:
        die("ImageMagick не найден (нужен `magick`)")
    proc = subprocess.run(
        [exe, *args], capture_output=True, text=True, check=False
    )
    if proc.returncode != 0:
        die(f"magick failed: {proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout if capture else ""


def identify(path: Path, fmt: str) -> str:
    exe = shutil.which("identify")
    if not exe:
        return magick(["identify", "-format", fmt, str(path)], capture=True)
    proc = subprocess.run(
        [exe, "-format", fmt, str(path)], capture_output=True, text=True, check=False
    )
    return proc.stdout if proc.returncode == 0 else ""


_font_cache: dict[str, str] = {}


def font(kind: str) -> str:
    if kind in _font_cache:
        return _font_cache[kind]
    patterns = {
        "regular": ["DejaVu Sans", "Noto Sans", "Roboto", "sans-serif:lang=ru"],
        "bold": ["DejaVu Sans:bold", "Noto Sans:bold", "sans-serif:bold:lang=ru"],
        "mono": ["DejaVu Sans Mono", "Noto Sans Mono", "monospace:lang=ru"],
    }[kind]
    exe = shutil.which("fc-match")
    for pat in patterns:
        if not exe:
            break
        proc = subprocess.run(
            [exe, "-f", "%{file}", pat], capture_output=True, text=True, check=False
        )
        path = proc.stdout.strip()
        if proc.returncode == 0 and path and Path(path).is_file():
            _font_cache[kind] = path
            return path
    die("не нашёл шрифт через fc-match; задай TG_DOCS_FONT вручную в скрипте")
    return ""  # unreachable


def placeholder_meta(path: Path) -> dict | None:
    """Return placeholder metadata if `path` is one of our generated stubs."""
    if not path.is_file():
        return None
    comment = identify(path, "%c").strip()
    if not comment.startswith(MARKER):
        return None
    payload = comment[len(MARKER) :].strip()
    try:
        return json.loads(payload) if payload else {}
    except json.JSONDecodeError:
        return {}


def size_hint(rel: str) -> tuple[int, int]:
    """Reuse the dimensions of the archived original, or of the light/dark twin."""
    candidates = [ARCHIVE / rel]
    for other in _counterparts(rel):
        candidates += [SHOTS / other, ARCHIVE / other]
    for p in candidates:
        if p.is_file() and not placeholder_meta(p):
            wh = identify(p, "%w %h").split()
            if len(wh) == 2 and all(x.isdigit() for x in wh):
                return int(wh[0]), int(wh[1])
    return DEFAULT_SIZE


def _counterparts(rel: str) -> list[str]:
    if rel.endswith("-light.jpg"):
        return [rel[: -len("-light.jpg")] + "-dark.jpg"]
    if rel.endswith("-dark.jpg"):
        return [rel[: -len("-dark.jpg")] + "-light.jpg"]
    return []


def _text_layer(
    text: str, *, width: int, pointsize: int, color: str, font_path: str, y: int
) -> list[str]:
    return [
        "(",
        "-background", "none",
        "-fill", color,
        "-font", font_path,
        "-pointsize", str(pointsize),
        "-size", f"{width}x",
        "-gravity", "center",
        f"caption:{text}",
        ")",
        "-gravity", "north",
        "-geometry", f"+0+{y}",
        "-composite",
    ]


def render_placeholder(
    rel: str, caption: str, theme: str, *, reason: str | None, pages: list[str]
) -> None:
    t = THEMES[theme if theme in THEMES else "light"]
    w, h = size_hint(rel)
    inner_w = int(w * 0.82)
    dest = SHOTS / rel
    dest.parent.mkdir(parents=True, exist_ok=True)

    head = "УСТАРЕЛ — ПЕРЕСНЯТЬ" if reason else "НУЖЕН СКРИНШОТ"
    meta = {
        "caption": caption,
        "theme": theme,
        "pages": pages,
        "reason": reason,
        "v": 1,
    }

    args: list[str] = [
        "-size", f"{w}x{h}",
        f"canvas:{t['bg']}",
        "-fill", t["panel"],
        "-draw", f"roundrectangle 60,60 {w - 60},{h - 60} 36,36",
        "-draw",
        (
            "stroke-dasharray 30 22 "
            f"fill none stroke {t['border']} stroke-width 6 "
            f"roundrectangle 60,60 {w - 60},{h - 60} 36,36"
        ),
    ]

    y = int(h * 0.20)
    args += _text_layer(
        head, width=inner_w, pointsize=max(34, w // 22), color=t["accent"],
        font_path=font("bold"), y=y,
    )
    y += int(h * 0.07)
    args += _text_layer(
        caption or rel, width=inner_w, pointsize=max(30, w // 26), color=t["title"],
        font_path=font("bold"), y=y,
    )
    y += int(h * 0.14)
    args += _text_layer(
        rel, width=inner_w, pointsize=max(22, w // 38), color=t["muted"],
        font_path=font("mono"), y=y,
    )
    y += int(h * 0.06)
    args += _text_layer(
        t["label"], width=inner_w, pointsize=max(22, w // 40), color=t["muted"],
        font_path=font("regular"), y=y,
    )
    if pages:
        y += int(h * 0.06)
        args += _text_layer(
            "\n".join(pages), width=inner_w, pointsize=max(20, w // 44),
            color=t["muted"], font_path=font("mono"), y=y,
        )
    if reason:
        y += int(h * 0.08)
        args += _text_layer(
            reason, width=inner_w, pointsize=max(22, w // 38), color=t["accent"],
            font_path=font("regular"), y=y,
        )

    args += [
        "-set", "comment", f"{MARKER} {json.dumps(meta, ensure_ascii=False)}",
        "-quality", "88",
        str(dest),
    ]
    magick(args)


# --------------------------------------------------------------------------- commands


def group_shots(shots: list[Shot]) -> dict[str, list[Shot]]:
    by_rel: dict[str, list[Shot]] = {}
    for s in shots:
        by_rel.setdefault(s.rel, []).append(s)
    return by_rel


def classify(by_rel: dict[str, list[Shot]]) -> dict[str, str]:
    out: dict[str, str] = {}
    for rel, group in by_rel.items():
        p = SHOTS / rel
        if not p.is_file():
            out[rel] = "missing"
        elif placeholder_meta(p) is not None:
            out[rel] = "placeholder"
        else:
            out[rel] = "ok"
    return out


def cmd_audit(args: argparse.Namespace) -> int:
    usages = parse_usages()
    shots = shots_of(usages)
    by_rel = group_shots(shots)
    status = classify(by_rel)
    referenced = set(by_rel)
    orphans = [f for f in on_disk() if f not in referenced]

    if args.json:
        print(
            json.dumps(
                {
                    "usages": [
                        {
                            "page": u.page,
                            "line": u.line,
                            "caption": u.caption,
                            "file": u.file,
                            "dark": u.dark,
                            "warnings": u.warnings,
                        }
                        for u in usages
                    ],
                    "images": [
                        {
                            "rel": rel,
                            "status": status[rel],
                            "meta": placeholder_meta(SHOTS / rel),
                            "used_by": [f"{s.page}:{s.line}" for s in group],
                        }
                        for rel, group in sorted(by_rel.items())
                    ],
                    "orphans": orphans,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    counts = {k: 0 for k in ("ok", "placeholder", "missing")}
    for st in status.values():
        counts[st] += 1
    print(
        f"страниц: {len({u.page for u in usages})}   "
        f"<Screenshot/>: {len(usages)}   файлов: {len(by_rel)} "
        f"(ok {counts['ok']}, плейсхолдер {counts['placeholder']}, нет {counts['missing']})"
    )

    for state, title in (
        ("missing", "НЕТ ФАЙЛА"),
        ("placeholder", "ПЛЕЙСХОЛДЕР"),
        ("ok", "ГОТОВО"),
    ):
        rels = sorted(r for r, s in status.items() if s == state)
        if not rels or (state == "ok" and not args.verbose):
            if rels and state == "ok":
                print(f"\n{title}: {len(rels)} (--verbose чтобы показать)")
            continue
        print(f"\n{title}:")
        for rel in rels:
            group = by_rel[rel]
            where = ", ".join(f"{s.page}:{s.line}" for s in group)
            meta = placeholder_meta(SHOTS / rel) or {}
            note = f"  ⟵ {meta['reason']}" if meta.get("reason") else ""
            print(f"  {rel}")
            print(f"      {group[0].caption}")
            print(f"      {where}{note}")

    warned = [u for u in usages if u.warnings]
    if warned:
        print("\nЗАМЕЧАНИЯ:")
        for u in warned:
            print(f"  {u.page}:{u.line}  {'; '.join(u.warnings)}")

    if orphans:
        print("\nНЕ ИСПОЛЬЗУЕТСЯ НИ НА ОДНОЙ СТРАНИЦЕ:")
        for f in orphans:
            print(f"  {f}")

    if args.strict and counts["missing"]:
        return 1
    return 0


def cmd_outline(args: argparse.Namespace) -> int:
    pages = mdx_pages()
    if args.page:
        wanted = {p.lstrip("/") for p in args.page}
        pages = [p for p in pages if str(p.relative_to(CONTENT)) in wanted]
        if not pages:
            die(f"страницы не найдены: {', '.join(args.page)}")
    usages = parse_usages(pages)
    by_page: dict[str, list[Usage]] = {}
    for u in usages:
        by_page.setdefault(u.page, []).append(u)

    for page in pages:
        rel = str(page.relative_to(CONTENT))
        print(f"\n=== {rel}")
        text = page.read_text(encoding="utf-8").splitlines()
        in_code = False
        for i, line in enumerate(text, 1):
            if line.startswith("```"):
                in_code = not in_code
            if in_code:
                continue
            m = HEADING_RE.match(line)
            if m:
                depth = len(m.group(1))
                print(f"  {'  ' * (depth - 1)}{'#' * depth} {m.group(2)}  :{i}")
        for u in by_page.get(rel, []):
            files = u.file + (f" + {u.dark}" if u.dark else "")
            print(f"    [img :{u.line}] {u.caption}")
            print(f"            {files}")
    return 0


def cmd_placeholders(args: argparse.Namespace) -> int:
    usages = parse_usages()
    by_rel = group_shots(shots_of(usages))
    status = classify(by_rel)

    targets: list[str] = []
    for rel in sorted(by_rel):
        if args.only and args.only not in rel and not any(
            args.only in s.page for s in by_rel[rel]
        ):
            continue
        st = status[rel]
        if st == "missing":
            targets.append(rel)
        elif st == "placeholder" and args.refresh:
            targets.append(rel)

    if not targets:
        print("нечего создавать — все нужные файлы на месте")
        return 0

    for rel in targets:
        group = by_rel[rel]
        theme = group[0].theme
        if theme == "single":
            theme = "dark" if "-dark" in rel else "light"
        meta = placeholder_meta(SHOTS / rel) or {}
        render_placeholder(
            rel,
            group[0].caption,
            theme,
            reason=meta.get("reason"),
            pages=sorted({f"{s.page}:{s.line}" for s in group}),
        )
        print(f"создан плейсхолдер  {rel_repo(SHOTS / rel)}")
    print(f"\nвсего: {len(targets)}")
    return 0


def cmd_stale(args: argparse.Namespace) -> int:
    usages = parse_usages()
    by_rel = group_shots(shots_of(usages))
    rels: list[str] = []
    for raw in args.path:
        rel = raw
        for prefix in (str(SHOTS) + "/", "docs/public/screenshots/", "public/screenshots/"):
            if rel.startswith(prefix):
                rel = rel[len(prefix) :]
        if args.both:
            rels.extend([rel, *_counterparts(rel)])
        else:
            rels.append(rel)

    seen: set[str] = set()
    for rel in rels:
        if rel in seen:
            continue
        seen.add(rel)
        src = SHOTS / rel
        group = by_rel.get(rel, [])
        if not group:
            print(f"пропуск (не используется ни на одной странице): {rel}")
            continue
        if src.is_file() and placeholder_meta(src) is None:
            dest = ARCHIVE / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(src), str(dest))
            print(f"старый файл сохранён в {rel_repo(dest)}")
        theme = group[0].theme
        if theme == "single":
            theme = "dark" if "-dark" in rel else "light"
        render_placeholder(
            rel,
            group[0].caption,
            theme,
            reason=args.reason,
            pages=sorted({f"{s.page}:{s.line}" for s in group}),
        )
        print(f"помечен как устаревший  {rel_repo(SHOTS / rel)}")
    return 0


def cmd_restore(args: argparse.Namespace) -> int:
    for raw in args.path:
        rel = raw
        for prefix in (str(SHOTS) + "/", "docs/public/screenshots/", "public/screenshots/"):
            if rel.startswith(prefix):
                rel = rel[len(prefix) :]
        src = ARCHIVE / rel
        if not src.is_file():
            print(f"нет архивной копии: {rel}")
            continue
        dest = SHOTS / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dest))
        print(f"восстановлен  {rel_repo(dest)}")
    _prune_empty(ARCHIVE)
    return 0


def _prune_empty(root: Path) -> None:
    if not root.is_dir():
        return
    for d in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if d.is_dir() and not any(d.iterdir()):
            d.rmdir()
    if not any(root.iterdir()):
        root.rmdir()


def cmd_todo(args: argparse.Namespace) -> int:
    usages = parse_usages()
    by_rel = group_shots(shots_of(usages))
    status = classify(by_rel)
    pending = [rel for rel, st in sorted(status.items()) if st != "ok"]

    lines = ["# Скриншоты к съёмке", ""]
    if not pending:
        lines.append("Всё на месте — снимать нечего.")
    by_page: dict[str, list[str]] = {}
    for rel in pending:
        for s in by_rel[rel]:
            by_page.setdefault(s.page, []).append(rel)
    for page in sorted(by_page):
        lines.append(f"## `content/{page}`")
        lines.append("")
        for rel in sorted(set(by_page[page])):
            group = by_rel[rel]
            meta = placeholder_meta(SHOTS / rel) or {}
            theme = "тёмная" if "-dark" in rel else "светлая"
            why = f" — _{meta['reason']}_" if meta.get("reason") else ""
            lines.append(
                f"- [ ] `docs/public/screenshots/{rel}` ({theme}) — "
                f"{group[0].caption}{why}  \n"
                f"      строка {group[0].line}"
            )
        lines.append("")

    text = "\n".join(lines).rstrip() + "\n"
    if args.write:
        out = Path(args.write)
        if not out.is_absolute():
            out = REPO / out
        out.write_text(text, encoding="utf-8")
        print(f"записано в {rel_repo(out)}")
    else:
        print(text, end="")
    return 0


# --------------------------------------------------------------------------- cli


def main() -> int:
    ap = argparse.ArgumentParser(prog="docs_screens.py", description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("audit", help="что есть, чего нет, что плейсхолдер")
    p.add_argument("--json", action="store_true")
    p.add_argument("--verbose", action="store_true", help="показать и готовые файлы")
    p.add_argument("--strict", action="store_true", help="exit 1 если чего-то нет")
    p.set_defaults(func=cmd_audit)

    p = sub.add_parser("outline", help="заголовки страниц + где какие скриншоты")
    p.add_argument("page", nargs="*", help="например features/streaks.mdx")
    p.set_defaults(func=cmd_outline)

    p = sub.add_parser("placeholders", help="создать заглушки для отсутствующих файлов")
    p.add_argument("--refresh", action="store_true", help="перерисовать существующие заглушки")
    p.add_argument("--only", help="подстрока пути к файлу или страницы")
    p.set_defaults(func=cmd_placeholders)

    p = sub.add_parser("stale", help="пометить снятый скриншот как устаревший")
    p.add_argument("path", nargs="+", help="путь внутри public/screenshots")
    p.add_argument("--reason", help="что изменилось (попадёт на картинку и в чеклист)")
    p.add_argument("--both", action="store_true", help="и light, и dark вариант")
    p.set_defaults(func=cmd_stale)

    p = sub.add_parser("restore", help="вернуть файл из .screenshots-outdated")
    p.add_argument("path", nargs="+")
    p.set_defaults(func=cmd_restore)

    p = sub.add_parser("todo", help="markdown-чеклист того, что снять")
    p.add_argument("--write", nargs="?", const="docs/SCREENSHOTS-TODO.md", help="записать в файл")
    p.set_defaults(func=cmd_todo)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
