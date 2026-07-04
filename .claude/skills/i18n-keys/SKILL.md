---
name: i18n-keys
description: Manage translation keys (I18N_* dicts) in plugin/tg-streaks.py — create, delete, rename, and edit translated text — without reading/editing the raw file by hand. Use this whenever a task touches a "sheet.*", "menu.*", "status.*", "dialog.*" etc. translation key in plugin/tg-streaks.py.
---

# i18n-keys

`plugin/tg-streaks.py` has a large block of `I18N_*` dicts (`I18N_SETTINGS`,
`I18N_STATUS`, `I18N_SHEETS`, ...) merged into `I18N_STRINGS`, each mapping a
dotted key like `"sheet.pet.streak_days"` to `{"en": ..., "ru": ...}`. Editing
these by hand with Read/Edit burns a lot of context on a 2800+ line file.
Use `scripts/i18n_tool.py` instead for every add/remove/rename/text-edit of a
translation key.

Run it with plain `python3` (stdlib only, no venv needed):

```
python3 .claude/skills/i18n-keys/scripts/i18n_tool.py <command> ...
```

## Commands

- `list [--group SHEETS]` — list group names + key counts, or all keys in one group.
- `get <key> [<key> ...]` — print en/ru text for specific keys.
- `find <substring>` — search key names and en/ru text for a substring.
- `add <key> --en "..." --ru "..." [--group NAME]` — add a new key. Group is
  inferred from the longest matching dotted prefix among existing keys if
  `--group` is omitted; pass it explicitly if inference fails or is ambiguous.
- `set-text <key> [--en "..."] [--ru "..."]` — change the translated text of
  an existing key (leaves an omitted language untouched).
- `rename <old_key> <new_key>` — renames the dict key and rewrites every
  quoted reference to it elsewhere in `tg-streaks.py`, **and** in
  `dex/src/main/kotlin/ru/n08i40k/streaks/constants/TranslationKey.kt` (the
  Kotlin side mirrors these keys as `const val` string literals).
- `rm <key> [--force]` — deletes a key. Refuses (exit 1) and prints every
  remaining reference in `tg-streaks.py`/`TranslationKey.kt` if the key is
  still used anywhere; pass `--force` to delete anyway and leave dangling refs.
- `sort` — no key changes, just the post-processing step below (useful to run
  standalone after a manual edit).

Use literal `\n` in `--en`/`--ru` values for embedded newlines (e.g.
`--en "line one\nline two"`), it's converted to a real newline.

## What every mutating command does automatically

1. Recursively re-sorts **all** `I18N_*` dicts (top-level keys, and the
   per-language keys within each) alphabetically — not just the group you
   touched.
2. Runs `uv run ruff format tg-streaks.py` (cwd `plugin/`) to normalize style.
3. Runs `uv run ty check tg-streaks.py` and prints diagnostics for review —
   informational only, it does not block the operation (there are 2
   pre-existing unrelated diagnostics in this file as of writing).

Because step 1 sorts the *whole* file, the very first time this tool is run
against a file whose keys aren't already alphabetically ordered, expect a
large diff (every `I18N_*` block gets reordered, not just the key you
touched). That's expected and intentional — flag it to the user before
running a mutating command on a fresh/unsorted file rather than surprising
them with a huge diff.

## Scope / limitations

- Only understands dict literals shaped `I18N_NAME: dict[str, dict[str, str]] = {...}`.
  `I18N_STRINGS` (built via `**I18N_X` spreads) is intentionally left alone.
- Reference scanning for `rename`/`rm` is a literal quoted-string search
  (`"key"` / `'key'`) across `tg-streaks.py` and `TranslationKey.kt` — it
  does not understand f-strings or string concatenation. There are currently
  no such dynamic key usages in this codebase, so this is safe today; if that
  ever changes, double-check `rm`/`rename` results manually.
- `add`/`set-text` never touch `TranslationKey.kt` — wiring a new key into
  Kotlin UI code is a separate manual step.
