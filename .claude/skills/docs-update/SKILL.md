---
name: docs-update
description: Bring the docs site in docs/ up to date with code changes, work out which screenshots the change invalidated, and shoot replacements off an adb-connected device. Use whenever a feature/setting/UI was added, renamed, reworked or removed and the user-facing docs must follow — or when the user asks "что нужно переснять", asks for screenshot placeholders, or asks to capture/crop app screenshots for the docs.
---

# docs-update

`docs/` is a Next.js + Nextra 4 site (MDX under `docs/content`, static export)
that documents the plugin **for end users, in Russian**. Code changes silently
rot it in two ways: the text stops matching what the app does, and screenshots
keep showing an old screen. This skill covers both, and hands the user a
concrete list of pictures to re-shoot — with placeholder files already sitting
at the right paths so replacing them is drag-and-drop.

Screenshot bookkeeping is done with the helper script, never by hand:

```
python3 .claude/skills/docs-update/scripts/docs_screens.py <command> ...
```

Stdlib only, no venv. Image generation needs ImageMagick (`magick`).

## Site facts

- Pages: `docs/content/**/*.mdx`. Nav titles and order: `docs/content/_meta.ts`
  and `docs/content/features/_meta.ts` — **a new page is invisible until it is
  added to the right `_meta.ts`**.
- Images: `docs/public/screenshots/<area>/<name>-{light,dark}.jpg`, referenced
  by path *relative to that folder*.
- Everything user-facing is **Russian**, in the voice of the existing pages:
  second person, short paragraphs hard-wrapped at ~80 columns, `**жирным**` for
  UI labels, no marketing tone, no version numbers in prose.
- Cross-links are absolute, anchor = the Russian heading slugified:
  `[«Пересчёт истории»](/features/rebuild#три-варианта-запуска)`. When you
  rename a heading, grep the whole `content/` tree for its old anchor.
- Components come from `nextra/components` (`Callout`, `Steps`, `Cards`) and
  must be imported at the top of the page; `Screenshot` is local:
  `import { Screenshot } from "../components/Screenshot";` (`../../` from
  `features/`).

`Screenshot` usage — `dark` is optional but every existing page has it, keep it
that way:

```mdx
<Screenshot
  file="settings/overview-light.jpg"
  dark="settings/overview-dark.jpg"
  caption="Экран настроек плагина Streaks целиком"
/>
```

The `caption` is what the picture must show — it is also what gets printed on
the placeholder image and in the user's checklist, so write it as an
instruction to the photographer, not as decoration.

## Workflow

### 1. Pin down what changed

Default to the current branch's diff against `master` plus the working tree;
if the user named commits, a range, or a feature, use that instead:

```
git diff master...HEAD --stat && git diff --stat
```

Read the actual diff of anything user-visible. Ignore refactors, DB migrations,
logging and build files unless behaviour changed.

### 2. Map code → pages

| Code | Page |
| --- | --- |
| `controller/StreaksController.kt`, `data/Streak*.kt`, `StreakLevel` | `features/streaks.mdx` |
| `controller/StreakPetsController.kt`, `ui/StreakPet*.kt`, `data/StreakPet*.kt` | `features/streak-pet.mdx` |
| `registry/StreakEmojiRegistry.kt`, `hook/impl/emoji`, `ui/emojiPack/**` | `features/emoji-badge.mdx` |
| `controller/ServiceMessage*.kt`, `hook/impl/ServiceMessagesHookBundle.kt`, `ui/ServiceMessageCategoriesFragment.kt` | `features/service-messages.mdx`, `control-panel.mdx` |
| `chat_history_fetcher/**`, `ui/rebuild/**` | `features/rebuild.mdx` |
| `ui/StreakControlFragment.kt`, `ui/TimeZoneSelectFragment.kt`, `controller/TimeZonesController.kt` | `control-panel.mdx` |
| plugin settings UI in `plugin/tg-streaks.py` (`I18N_SETTINGS`, `create_settings`) | `settings.mdx` |
| chat menu entries (`hook/impl/*HookBundle.kt`, menu items in `tg-streaks.py`) | `chat-menu.mdx` |
| `database/**` backup/restore, reset | `backups.mdx` |
| updater / release flow in `tg-streaks.py`, `scripts/prepare_release.py` | `updates.mdx`, `installation.mdx` |

Not sure where something is described? `outline` prints every page's headings
and screenshots cheaply — use it instead of reading whole pages:

```
python3 .claude/skills/docs-update/scripts/docs_screens.py outline
python3 .claude/skills/docs-update/scripts/docs_screens.py outline features/streaks.mdx
```

A feature with no page at all is a new page + a `_meta.ts` entry. Flag that to
the user rather than silently inventing a large page.

### 3. Quote the real UI wording

Docs name buttons and settings exactly as the app does. Do not invent labels:

- DEX strings: `dex/src/main/i18n/Strings_ru.properties`.
- Python-side strings: use the **i18n-keys** skill (`i18n_tool.py get|find`),
  never read the raw dicts.

### 4. Edit the MDX

Match the surrounding page: same heading depth, same `Callout` types, same link
style. Keep diffs minimal — rewrite the paragraphs that became wrong, don't
restyle the page. If a feature was removed, delete its section, its screenshots
from the page, and every link/anchor pointing at it.

### 5. Screenshots

First see the current state:

```
python3 .claude/skills/docs-update/scripts/docs_screens.py audit
```

It lists, per image path, whether the file exists, is one of our placeholders,
or is a real screenshot — plus which page and line uses it, refs that lack a
`dark` twin, and images nobody references anymore.

**Mark what the change invalidated.** A screenshot is stale when the change
alters what that specific frame shows: a cell/button added, removed, reordered
or renamed on that screen; its wording, icon, colour or layout changed; a
dialog/sheet reworked; default values shown in the picture changed. It is *not*
stale for internal refactors, or changes to a different screen.

```
python3 .claude/skills/docs-update/scripts/docs_screens.py stale \
  settings/overview-light.jpg --both \
  --reason "добавлен пункт «Эмодзи-паки»"
```

`stale` moves the real file to `docs/.screenshots-outdated/` (gitignored,
reversible with `restore`) and puts a marked placeholder in its place, so the
site itself shows what still needs shooting. `--both` handles the light+dark
pair at once. `--reason` lands on the picture and in the checklist.

**Materialise new ones.** Add the `<Screenshot/>` block to the MDX first, then:

```
python3 .claude/skills/docs-update/scripts/docs_screens.py placeholders
```

Every referenced-but-missing file is created as a labelled stub of the right
size, so the user never types an image name by hand — they overwrite files.

Then either the user replaces the stubs by hand, or — if a device is connected —
shoot them yourself, see below. Never hand-craft, mock up or fake a screenshot
of the app: the only images this skill invents are placeholders.

### 5b. Shooting from a connected device (optional)

`scripts/shoot.py` grabs the framebuffer over adb, crops it and writes the file
under the exact name the MDX expects, with the same JPEG settings as the
screenshots already committed (q=87, 2x2, progressive).

```
python3 .claude/skills/docs-update/scripts/shoot.py state
python3 .claude/skills/docs-update/scripts/shoot.py screen        # full frame + preview
python3 .claude/skills/docs-update/scripts/shoot.py grab settings/emoji-packs \
    --theme light --band 640-890 --preview
```

The loop that works: get the app onto the target screen → `screen` → **look at
the preview image** → pick the crop → `grab` → **look at the saved file** to
confirm the framing.

**Match the framing of the committed screenshots.** They are all 1080 wide, but
that is not the raw frame: the ones showing a single row or cell are a crop of
the *left* part of it — no timestamp, no read marks — scaled back up to 1080.
`docs/public/screenshots/features/streaks/dialog-emoji-light.jpg` (1080x344) is
reproduced by `--crop 0,382,470,150 --width 1080`, and settings rows follow the
same idea. Full-width vertical bands (`--band Y1-Y2`) are right for whole
screens and dialogs. Check the file you are replacing with `identify` first and
aim for its proportions, otherwise one page ends up with rows at two different
zoom levels.

Hard constraints, learned on this setup — do not paper over them:

- **Themes switch from here, as long as the app's auto-night mode is «по
  системе».** It is on this device, so `night off` / `night on` drives both
  frames of a pair, and the same `--band` fits both. But **the switch recreates
  the activity**: the chat list and an open chat come back, a deeper screen
  (the streak control panel, a settings sub-screen) is popped back to the chat
  list, and the frame right after the toggle can catch the cross-fade. So set
  the theme *first*, then navigate, then `grab` — for a pair that means walking
  the same path twice, not toggling in place. Always confirm with the preview
  that you got the theme and the screen you meant; a washed-out grey frame is a
  transition, re-shoot it.
- **Navigation cannot be trusted to the accessibility tree.** `ui` shows only a
  handful of nodes on custom-drawn Telegram screens (the chat list exposes
  almost nothing), so `tap`/`swipe` are coordinate-based, aimed by eye from a
  `screen` preview.
- **This is the user's real phone, with their real correspondence.** Ask before
  driving the UI, never open chats, scroll private content or capture other
  apps on your own initiative, and let the user choose which chat appears in a
  screenshot. If the screen is off or locked (`state` says `Dozing`), stop and
  ask — do not photograph a lock screen.
- **The device usually runs a debug build.** The plugin's chat-menu submenu
  then carries `[DEBUG]` entries (streak to 3 days, level up, freeze, break,
  delete, plugin crash) and `Toggle log overlay`, which must never reach a
  docs screenshot. Frame around them or shoot on a release build.
- App state you cannot manufacture (a real streak of N days, a pet level, a
  specific service message) is the user's to set up. Say what is needed instead
  of shooting something approximate.

### 6. Verify

```
python3 .claude/skills/docs-update/scripts/docs_screens.py audit --strict
```

If `docs/node_modules` exists, also build — it catches broken MDX and bad
imports:

```
cd docs && npm run build
```

Say plainly if you skipped the build (no deps installed).

### 7. Report to the user

End with two things:

1. What changed in the docs — page by page, one line each.
2. The shooting checklist, straight from:

```
python3 .claude/skills/docs-update/scripts/docs_screens.py todo
```

Grouped by page, each entry gives the exact file to overwrite, the theme, what
must be in frame, and why it's on the list. `todo --write` dumps the same into
`docs/SCREENSHOTS-TODO.md` when the user wants it as a file.

Both light and dark variants are always separate shots — the site swaps them by
theme, so the app has to be re-shot in both themes.

## Script reference

`scripts/docs_screens.py` — bookkeeping:

| Command | What it does |
| --- | --- |
| `audit [--verbose] [--json] [--strict]` | state of every referenced image, lint warnings, unused files; `--strict` exits 1 if something is missing |
| `outline [page.mdx ...]` | headings + screenshot refs with line numbers |
| `placeholders [--only SUBSTR] [--refresh]` | create stubs for referenced-but-missing files; `--refresh` redraws existing stubs after a caption edit |
| `stale <path> [...] [--both] [--reason TEXT]` | archive a real screenshot and replace it with a "переснять" stub |
| `restore <path> [...]` | undo `stale` |
| `todo [--write [PATH]]` | markdown checklist of everything to shoot |

Paths are relative to `docs/public/screenshots` (a full repo path is accepted
too). Placeholders are tagged with a JPEG comment, which is how `audit` tells a
stub from a real screenshot — so a user's real screenshot dropped over a stub is
detected automatically, with nothing to reset by hand.

`scripts/shoot.py` — device capture (needs `adb`):

| Command | What it does |
| --- | --- |
| `state` | serial, screen awake/dozing, system night mode, resolution, focused window |
| `screen [--out PATH]` | full frame + downscaled preview to look at |
| `grab <rel> --theme light\|dark [--band Y1-Y2 \| --crop X,Y,W,H] [--width 1080] [--preview]` | capture, crop, optionally rescale, save as `<rel>-<theme>.jpg` in the docs |
| `ui [--all]` | accessibility nodes with text and tap coordinates |
| `tap X,Y` / `tap --text "..."`, `back`, `swipe up\|down` | line the screen up |
| `night on\|off` | Android night mode; the app follows it (auto-night «по системе»), so this is how light/dark pairs get shot |

`grab` accepts a bare name (`settings/emoji-packs` + `--theme`) or a full file
name, and prints when it is overwriting a real screenshot rather than a stub.
