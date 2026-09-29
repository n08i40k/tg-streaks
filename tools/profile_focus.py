"""Срез simpleperf-профиля по коду плагина.

Оставляет только сэмплы, в стеке которых есть фреймы плагина, и обрезает каждый стек
до внешнего фрейма плагина (плюс один фрейм вызывающего кода для контекста). Результат:

* таблица методов плагина со временем inclusive / plugin-self в миллисекундах;
* folded-стеки (формат flamegraph.pl) для speedscope, где корнем служит поток.

Время оценивается как число сэмплов * период сэмплирования (из `-f` в команде записи).
"""

import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

PLUGIN_FRAME = re.compile(r"ru\.n08i40k\.")

# фреймы хук-фреймворка между кодом клиента и колбэком хука ничего не говорят о вызывающем
BRIDGE_FRAME = re.compile(r"de\.robv\.|lsplant|LSPHooker|java\.lang\.reflect\.|^art_|^Nterp|^ExecuteNterp")

SHORT_NAMES = [
    ("ru.n08i40k.streaks_shaded.ru.n08i40k.badges.", "badges."),
    ("ru.n08i40k.streaks_shaded.", "shaded."),
    ("ru.n08i40k.streaks.", "streaks."),
]

DEFAULT_FREQUENCY = 4000


def shorten(name: str) -> str:
    for prefix, short in SHORT_NAMES:
        if name.startswith(prefix):
            return short + name[len(prefix):]
    return name


def sampling_frequency(record_cmd: str) -> int:
    match = re.search(r"(?:^|\s)-f\s+(\d+)", record_cmd)
    return int(match.group(1)) if match else DEFAULT_FREQUENCY


def load_report_lib(simpleperf_dir: Path):
    sys.path.insert(0, str(simpleperf_dir))
    from simpleperf_report_lib import ReportLib  # noqa: E402

    return ReportLib


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("record_file", type=Path)
    parser.add_argument("--simpleperf-dir", type=Path, required=True)
    parser.add_argument("--folded", type=Path, required=True, help="куда записать folded-стеки")
    parser.add_argument("--limit", type=int, default=40, help="сколько методов показать в таблице")
    args = parser.parse_args()

    report_lib = load_report_lib(args.simpleperf_dir)()
    report_lib.SetLogSeverity("error")
    report_lib.SetRecordFile(str(args.record_file))

    sample_ms = 1000 / sampling_frequency(report_lib.GetRecordCmd())

    thread_samples: Counter[str] = Counter()
    plugin_samples: Counter[str] = Counter()
    inclusive: Counter[str] = Counter()
    plugin_self: Counter[str] = Counter()
    method_threads: defaultdict[str, Counter[str]] = defaultdict(Counter)
    folded: Counter[str] = Counter()

    while (sample := report_lib.GetNextSample()) is not None:
        thread = sample.thread_comm
        thread_samples[thread] += 1

        callchain = report_lib.GetCallChainOfCurrentSample()
        # от листа к корню
        frames = [report_lib.GetSymbolOfCurrentSample().symbol_name]
        frames += [callchain.entries[i].symbol.symbol_name for i in range(callchain.nr)]

        plugin_indices = [i for i, frame in enumerate(frames) if PLUGIN_FRAME.match(frame)]
        if not plugin_indices:
            continue

        plugin_samples[thread] += 1

        outermost = plugin_indices[-1]
        innermost = plugin_indices[0]

        caller = next(
            (frame for frame in frames[outermost + 1:] if not BRIDGE_FRAME.search(frame)),
            None,
        )

        for name in {shorten(frames[i]) for i in plugin_indices}:
            inclusive[name] += 1
            method_threads[name][thread] += 1
        plugin_self[shorten(frames[innermost])] += 1

        stack = [thread]
        if caller is not None:
            stack.append(shorten(caller))
        stack += [shorten(frame) for frame in reversed(frames[: outermost + 1])]
        folded[";".join(frame.replace(";", ":") for frame in stack)] += 1

    report_lib.Close()

    args.folded.parent.mkdir(parents=True, exist_ok=True)
    args.folded.write_text("".join(f"{stack} {count}\n" for stack, count in folded.most_common()))

    print(f"~{sample_ms:.2f} ms CPU на сэмпл\n")
    print(f"{'поток':<32} {'всего ms':>10} {'плагин ms':>10} {'доля':>7}")
    for thread, total in thread_samples.most_common():
        ours = plugin_samples[thread]
        if ours == 0:
            continue
        print(f"{thread[:32]:<32} {total * sample_ms:>10.1f} {ours * sample_ms:>10.1f} {ours / total:>7.2%}")

    print(f"\n{'incl ms':>9} {'self ms':>9}  метод (self = метод — самый глубокий фрейм плагина)")
    for name, count in inclusive.most_common(args.limit):
        threads = ", ".join(t for t, _ in method_threads[name].most_common(2))
        print(f"{count * sample_ms:>9.1f} {plugin_self[name] * sample_ms:>9.1f}  {name}  [{threads}]")

    print(f"\nfolded-стеки: {args.folded}")


if __name__ == "__main__":
    main()
