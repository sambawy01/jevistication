#!/usr/bin/env python3
"""Fails when shared Kotlin main sources use a regex feature whose meaning differs between engines.

The JDK, Kotlin/Native (iOS) and Android's ICU compile the same pattern but disagree on what
`\\d \\w \\s \\b \\D \\W \\S \\B \\p{..} \\P{..}` and case-insensitive matching accept (docs/ANDROID-PLAN.md,
"Known parity gaps", B1). Shared code spells its classes out with the engine's `Rx` constants and
folds case and digits itself (`PortableText`), so the same text gets the same answer on every
phone. This check keeps it that way:

  * every string literal in code (not comments) of the shared modules' main source sets
    (commonMain, jvmCommonMain, jvmMain, androidMain, iosMain) must not contain one of those escapes
    (an escape, i.e. an odd number of backslashes before the letter) or an inline case flag
    `(?i` / `(?u` / `(?U`;
  * code must not name `IGNORE_CASE`, `UNICODE_CASE`, `CASE_INSENSITIVE` or
    `UNICODE_CHARACTER_CLASS`;
  * the one exception by construction: the first argument of `Baseline.Pattern(...)` (a template's
    baseline), which Baseline.kt compiles through `PortableRegex.translate`; there `\\p{..}`, `\\X`, `\\R`,
    `\\h`, `\\v` and `\\N`, which translate refuses, still fail here.

Anything else must be listed in lint-allowlist.txt, one entry per line:

    <path relative to the repo root> | <text that occurs in the offending literal or line> | <why>

The justification is required, and an entry that no longer matches anything fails the check too,
so the list cannot rot. Usage: python3 tools/android-regex-check/lint.py   (exit status 1 on findings)
"""
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import extract  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULES = ["engine", "templates", "persistence", "sources-common", "game", "backend-laya-common", "loupe-kit"]
SOURCE_SETS = ["commonMain", "jvmCommonMain", "jvmMain", "androidMain", "iosMain"]
ALLOWLIST = pathlib.Path(__file__).resolve().parent / "lint-allowlist.txt"

ESCAPE = re.compile(r"(?<!\\)(?:\\\\)*\\([dwsbDWSB]|[pP]\{)")
INLINE_CASE = re.compile(r"\(\?[a-zA-Z-]*[iuU]")
BASELINE_REFUSED = re.compile(r"(?<!\\)(?:\\\\)*\\([pPXRhHvVN])")
FLAG_NAMES = re.compile(r"\b(IGNORE_CASE|UNICODE_CASE|CASE_INSENSITIVE|UNICODE_CHARACTER_CLASS)\b")


def literals(src: str):
    """(start offset, decoded value) of every string literal in code, templates left as written."""
    is_code, ends = extract.code_mask(src)
    out = []
    for end, start in ends.items():
        parsed = extract.parse_literal(src, start, {})
        if parsed is None:
            continue
        out.append((start, parsed[0]))
    return sorted(out)


def baseline_argument_starts(src: str) -> set:
    """Offsets of the first literal argument of each `Baseline.Pattern(` / `Pattern(` call."""
    is_code, _ = extract.code_mask(src)
    starts = set()
    for m in re.finditer(r"(?<![\w.])(?:Baseline\.)?Pattern\(\s*", src):
        if is_code[m.start()] and not re.search(r"\bclass\s+$", src[:m.start()]):
            starts.add(m.end())
    return starts


def findings_in(path: str, src: str):
    """(line, what, text) for each problem in one file."""
    out = []
    is_code, _ = extract.code_mask(src)
    baseline = baseline_argument_starts(src)

    def line_of(i):
        return src.count("\n", 0, i) + 1
    for start, value in literals(src):
        if start in baseline:
            m = BASELINE_REFUSED.search(value)
            if m:
                out.append((line_of(start), f"baseline pattern uses \\{m.group(1)}, which PortableRegex.translate refuses", value))
            continue
        m = ESCAPE.search(value)
        if m:
            out.append((line_of(start), f"engine-defined class \\{m.group(1).rstrip('{')}", value))
            continue
        if INLINE_CASE.search(value):
            out.append((line_of(start), "inline case-insensitivity flag", value))
    for m in FLAG_NAMES.finditer(src):
        if is_code[m.start()]:
            line_start = src.rfind("\n", 0, m.start()) + 1
            line_end = src.find("\n", m.start())
            out.append((line_of(m.start()), f"engine case folding or classes ({m.group(1)})", src[line_start:line_end].strip()))
    return out


def load_allowlist():
    entries = []
    if not ALLOWLIST.exists():
        return entries, []
    problems = []
    for n, raw in enumerate(ALLOWLIST.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split(" | ")]
        if len(parts) != 3 or not all(parts):
            problems.append(f"lint-allowlist.txt:{n}: needs `path | text | justification`, was: {raw}")
            continue
        entries.append((n, parts[0], parts[1], parts[2]))
    return entries, problems


def main() -> int:
    entries, problems = load_allowlist()
    used = set()
    found = 0
    for module in MODULES:
        for source_set in SOURCE_SETS:
            base = ROOT / module / "src" / source_set
            if not base.is_dir():
                continue
            for path in sorted(base.rglob("*.kt")):
                rel = str(path.relative_to(ROOT))
                src = path.read_text(encoding="utf-8")
                for line, what, text in findings_in(rel, src):
                    allowed = [e for e in entries if e[1] == rel and e[2] in text]
                    if allowed:
                        used.update(e[0] for e in allowed)
                        continue
                    found += 1
                    shown = text if len(text) <= 160 else text[:157] + "..."
                    print(f"{rel}:{line}: {what}: {shown!r}")
    for n, rel, text, _ in entries:
        if n not in used:
            problems.append(f"lint-allowlist.txt:{n}: stale entry, nothing in {rel} matches {text!r}")
    for p in problems:
        print(p)
    total = found + len(problems)
    print(f"portable regex lint: {found} finding(s), {len(problems)} allowlist problem(s)", file=sys.stderr)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
