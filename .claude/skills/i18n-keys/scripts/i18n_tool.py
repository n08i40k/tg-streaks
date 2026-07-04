#!/usr/bin/env python3
"""Manage the I18N_* translation dicts in plugin/tg-streaks.py.

Avoids hand-editing the (large) I18N block with Read/Edit: mutate keys via
this CLI instead. Every mutating command re-sorts all I18N_* dicts
recursively and reformats the file with `ruff format`.

Commands: list, get, find, add, set-text, rename, rm, sort. Run with -h on
any subcommand for details.
"""

from __future__ import annotations

import argparse
import ast
import re
import subprocess
import sys
from pathlib import Path
from typing import Optional

BLOCK_RE = re.compile(
    r"^(I18N_[A-Z0-9_]+): dict\[str, dict\[str, str\]\] = \{\n(.*?)^\}\n",
    re.MULTILINE | re.DOTALL,
)


def find_repo_root(start: Path) -> Path:
    for candidate in (start, *start.parents):
        if (candidate / "plugin" / "tg-streaks.py").is_file():
            return candidate
    raise SystemExit("Could not find repo root (no plugin/tg-streaks.py above this script)")


REPO_ROOT = find_repo_root(Path(__file__).resolve())
PLUGIN_DIR = REPO_ROOT / "plugin"
TARGET_FILE = PLUGIN_DIR / "tg-streaks.py"
TRANSLATION_KEY_KT = (
    REPO_ROOT
    / "dex"
    / "src"
    / "main"
    / "kotlin"
    / "ru"
    / "n08i40k"
    / "streaks"
    / "constants"
    / "TranslationKey.kt"
)


class ToolError(Exception):
    pass


class Block:
    def __init__(self, name: str, original_text: str, data: dict[str, dict[str, str]]):
        self.name = name
        self.original_text = original_text
        self.data = data


def parse_blocks(text: str) -> dict[str, Block]:
    blocks: dict[str, Block] = {}
    for m in BLOCK_RE.finditer(text):
        name, content = m.group(1), m.group(2)
        try:
            data = ast.literal_eval("{" + content + "}")
        except (ValueError, SyntaxError):
            # Aggregator dict (e.g. I18N_STRINGS built via `**GROUP,` spreads) - not editable here.
            continue
        blocks[name] = Block(name, m.group(0), data)
    return blocks


def recursive_sort(value):
    if isinstance(value, dict):
        return {k: recursive_sort(value[k]) for k in sorted(value.keys())}
    return value


def serialize_block(block: Block) -> str:
    lines = [f"{block.name}: dict[str, dict[str, str]] = {{"]
    for key, translations in block.data.items():
        inner = ", ".join(f"{lang!r}: {text!r}" for lang, text in translations.items())
        lines.append(f"    {key!r}: {{{inner}}},")
    lines.append("}\n")
    return "\n".join(lines)


def rebuild_text(text: str, blocks: dict[str, Block]) -> str:
    for block in blocks.values():
        block.data = recursive_sort(block.data)
        new_block_text = serialize_block(block)
        if new_block_text != block.original_text:
            text = text.replace(block.original_text, new_block_text, 1)
    return text


def locate_key(blocks: dict[str, Block], key: str) -> Optional[str]:
    for name, block in blocks.items():
        if key in block.data:
            return name
    return None


def normalize_group(blocks: dict[str, Block], group: str) -> str:
    candidate = group.upper()
    if not candidate.startswith("I18N_"):
        candidate = "I18N_" + candidate
    if candidate not in blocks:
        raise ToolError(
            f"Unknown group {group!r}. Available: {', '.join(sorted(blocks))}"
        )
    return candidate


def infer_group(blocks: dict[str, Block], new_key: str) -> str:
    new_parts = new_key.split(".")
    best_score = 0
    best_groups: set[str] = set()
    for name, block in blocks.items():
        for existing_key in block.data:
            parts = existing_key.split(".")
            score = 0
            for a, b in zip(new_parts, parts):
                if a != b:
                    break
                score += 1
            if score > best_score:
                best_score = score
                best_groups = {name}
            elif score == best_score and score > 0:
                best_groups.add(name)
    if best_score == 0 or len(best_groups) != 1:
        raise ToolError(
            "Could not infer --group for this key automatically, pass it explicitly. "
            f"Available: {', '.join(sorted(blocks))}"
        )
    return next(iter(best_groups))


def normalize_text(text: str) -> str:
    return text.replace("\\n", "\n")


def quoted_occurrences(haystack: str, key: str) -> list[tuple[int, str]]:
    pattern = re.compile(r"(['\"])" + re.escape(key) + r"\1")
    hits = []
    for i, line in enumerate(haystack.splitlines(), start=1):
        if pattern.search(line):
            hits.append((i, line.strip()))
    return hits


def replace_quoted(haystack: str, old_key: str, new_key: str) -> tuple[str, int]:
    pattern = re.compile(r"(['\"])" + re.escape(old_key) + r"\1")
    count = 0

    def _sub(m: re.Match) -> str:
        nonlocal count
        count += 1
        return f"{m.group(1)}{new_key}{m.group(1)}"

    return pattern.sub(_sub, haystack), count


def read_target() -> str:
    return TARGET_FILE.read_text(encoding="utf-8")


def write_target(text: str) -> None:
    TARGET_FILE.write_text(text, encoding="utf-8")


def run_post_processing() -> None:
    print("--- ruff format ---")
    subprocess.run(
        ["uv", "run", "ruff", "format", TARGET_FILE.name], cwd=PLUGIN_DIR, check=True
    )
    print("--- ty check (report only) ---")
    subprocess.run(["uv", "run", "ty", "check", TARGET_FILE.name], cwd=PLUGIN_DIR, check=False)


def cmd_list(args: argparse.Namespace) -> None:
    blocks = parse_blocks(read_target())
    if args.group:
        name = normalize_group(blocks, args.group)
        block = blocks[name]
        for key in sorted(block.data):
            t = block.data[key]
            print(f"{key}\n  en: {t.get('en', '')!r}\n  ru: {t.get('ru', '')!r}")
    else:
        for name in sorted(blocks):
            print(f"{name} ({len(blocks[name].data)} keys)")


def cmd_get(args: argparse.Namespace) -> None:
    blocks = parse_blocks(read_target())
    for key in args.keys:
        group = locate_key(blocks, key)
        if group is None:
            print(f"{key}: NOT FOUND")
            continue
        t = blocks[group].data[key]
        print(f"{key} [{group}]")
        for lang, text in t.items():
            print(f"  {lang}: {text!r}")


def cmd_find(args: argparse.Namespace) -> None:
    blocks = parse_blocks(read_target())
    needle = args.pattern.lower()
    found = False
    for name, block in blocks.items():
        for key, t in block.data.items():
            haystack = key + " " + " ".join(t.values())
            if needle in haystack.lower():
                found = True
                print(f"{key} [{name}]")
                for lang, text in t.items():
                    print(f"  {lang}: {text!r}")
    if not found:
        print("no matches")


def cmd_add(args: argparse.Namespace) -> None:
    blocks = parse_blocks(read_target())
    if locate_key(blocks, args.key) is not None:
        raise ToolError(f"Key {args.key!r} already exists (use set-text/rename instead)")

    group = normalize_group(blocks, args.group) if args.group else infer_group(blocks, args.key)
    blocks[group].data[args.key] = {
        "en": normalize_text(args.en),
        "ru": normalize_text(args.ru),
    }

    text = rebuild_text(read_target(), blocks)
    write_target(text)
    print(f"Added {args.key!r} to {group}")
    run_post_processing()


def cmd_set_text(args: argparse.Namespace) -> None:
    if args.en is None and args.ru is None:
        raise ToolError("Provide at least one of --en/--ru")

    blocks = parse_blocks(read_target())
    group = locate_key(blocks, args.key)
    if group is None:
        raise ToolError(f"Key {args.key!r} not found (use add instead)")

    translations = blocks[group].data[args.key]
    if args.en is not None:
        translations["en"] = normalize_text(args.en)
    if args.ru is not None:
        translations["ru"] = normalize_text(args.ru)

    text = rebuild_text(read_target(), blocks)
    write_target(text)
    print(f"Updated text for {args.key!r} in {group}")
    run_post_processing()


def cmd_rename(args: argparse.Namespace) -> None:
    old_key, new_key = args.old_key, args.new_key
    blocks = parse_blocks(read_target())
    group = locate_key(blocks, old_key)
    if group is None:
        raise ToolError(f"Key {old_key!r} not found")
    if locate_key(blocks, new_key) is not None:
        raise ToolError(f"Key {new_key!r} already exists")

    blocks[group].data[new_key] = blocks[group].data.pop(old_key)

    text = rebuild_text(read_target(), blocks)
    text, py_hits = replace_quoted(text, old_key, new_key)
    write_target(text)

    kt_hits = 0
    if TRANSLATION_KEY_KT.is_file():
        kt_text = TRANSLATION_KEY_KT.read_text(encoding="utf-8")
        kt_text, kt_hits = replace_quoted(kt_text, old_key, new_key)
        if kt_hits:
            TRANSLATION_KEY_KT.write_text(kt_text, encoding="utf-8")

    print(
        f"Renamed {old_key!r} -> {new_key!r} in {group} "
        f"({py_hits} reference(s) in {TARGET_FILE.name}, {kt_hits} in TranslationKey.kt)"
    )
    run_post_processing()


def cmd_rm(args: argparse.Namespace) -> None:
    key = args.key
    blocks = parse_blocks(read_target())
    group = locate_key(blocks, key)
    if group is None:
        raise ToolError(f"Key {key!r} not found")

    del blocks[group].data[key]
    text = rebuild_text(read_target(), blocks)

    py_hits = quoted_occurrences(text, key)
    kt_hits: list[tuple[int, str]] = []
    if TRANSLATION_KEY_KT.is_file():
        kt_hits = quoted_occurrences(TRANSLATION_KEY_KT.read_text(encoding="utf-8"), key)

    if (py_hits or kt_hits) and not args.force:
        print(f"Refusing to delete {key!r}: still referenced.")
        for line_no, line in py_hits:
            print(f"  {TARGET_FILE.name}:{line_no}: {line}")
        for line_no, line in kt_hits:
            print(f"  TranslationKey.kt:{line_no}: {line}")
        print("Pass --force to delete anyway.")
        raise SystemExit(1)

    write_target(text)
    print(f"Removed {key!r} from {group}")
    if py_hits or kt_hits:
        print(f"WARNING: left {len(py_hits) + len(kt_hits)} dangling reference(s) (--force used)")
    run_post_processing()


def cmd_sort(_args: argparse.Namespace) -> None:
    blocks = parse_blocks(read_target())
    text = rebuild_text(read_target(), blocks)
    write_target(text)
    print("Sorted all I18N_* dicts")
    run_post_processing()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("list", help="list groups, or keys within one group")
    sp.add_argument("--group", help="e.g. SETTINGS or I18N_SETTINGS")
    sp.set_defaults(func=cmd_list)

    sp = sub.add_parser("get", help="print translations for one or more keys")
    sp.add_argument("keys", nargs="+")
    sp.set_defaults(func=cmd_get)

    sp = sub.add_parser("find", help="search keys/text by substring")
    sp.add_argument("pattern")
    sp.set_defaults(func=cmd_find)

    sp = sub.add_parser("add", help="add a new translation key")
    sp.add_argument("key")
    sp.add_argument("--en", required=True)
    sp.add_argument("--ru", required=True)
    sp.add_argument("--group", help="target I18N_ group; inferred from key prefix if omitted")
    sp.set_defaults(func=cmd_add)

    sp = sub.add_parser("set-text", help="replace the en/ru text of an existing key")
    sp.add_argument("key")
    sp.add_argument("--en")
    sp.add_argument("--ru")
    sp.set_defaults(func=cmd_set_text)

    sp = sub.add_parser("rename", help="rename a key and update all references")
    sp.add_argument("old_key")
    sp.add_argument("new_key")
    sp.set_defaults(func=cmd_rename)

    sp = sub.add_parser("rm", help="delete a key (refuses if still referenced)")
    sp.add_argument("key")
    sp.add_argument("--force", action="store_true", help="delete even if still referenced")
    sp.set_defaults(func=cmd_rm)

    sp = sub.add_parser("sort", help="just re-sort + reformat, no key changes")
    sp.set_defaults(func=cmd_sort)

    return p


def main(argv: list[str]) -> int:
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except ToolError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
