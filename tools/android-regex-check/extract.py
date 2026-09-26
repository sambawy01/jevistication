#!/usr/bin/env python3
"""Extracts every Regex(...) literal from the shared Kotlin main sources, for check.sh.

Android's java.util.regex is ICU, not the JDK's engine, and it rejects some patterns the JDK and
Kotlin/Native accept (an unescaped `}` is the one found at A0). Unit tests run on the host JDK and
cannot see this, so check.sh compiles every pattern on an emulator or phone instead.

Output: one line per pattern, `<file>:<line>\t<base64 of the pattern>`. Literals, `+` chains of
literals and templates naming a `const val` of the same file are resolved; a pattern built at run
time otherwise (Regex.escape, joinToString, data) is listed on stderr as DYNAMIC for review by hand.
"""
import base64
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULES = ["engine", "templates", "persistence", "sources-common", "game", "backend-laya-common", "loupe-kit"]
MAIN_SETS = ["commonMain", "jvmCommonMain", "androidMain"]

ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'", "$": "$"}


def parse_literal(src: str, i: int, consts: dict):
    """Parses one Kotlin string literal at src[i], substituting templates that name a `const val`
    of the same file. Returns (value, end, dynamic) or None when src[i] starts no literal."""
    def substitute(body: str, raw: bool):
        dynamic = False

        def repl(m):
            nonlocal dynamic
            name = m.group(1) or m.group(2)
            if name in consts:
                return consts[name]
            dynamic = True
            return m.group(0)
        if raw:
            body = body.replace("${'$'}", "\x00")
        body = re.sub(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)", repl, body)
        if re.search(r"\$\{", body):
            dynamic = True
        return body.replace("\x00", "$"), dynamic

    if src.startswith('"""', i):
        end = src.index('"""', i + 3)
        while src.startswith('"', end + 3):  # a raw string may end in extra quotes
            end += 1
        value, dynamic = substitute(src[i + 3:end], raw=True)
        return value, end + 3, dynamic
    if src.startswith('"', i):
        out, j = [], i + 1
        while src[j] != '"':
            c = src[j]
            if c == "\\":
                n = src[j + 1]
                if n == "u":
                    out.append(chr(int(src[j + 2:j + 6], 16)))
                    j += 6
                    continue
                # An escaped dollar is kept as a marker so templates are not substituted in it.
                out.append("\x00" if n == "$" else ESCAPES.get(n, "\\" + n))
                j += 2
                continue
            out.append(c)
            j += 1
        value, dynamic = substitute("".join(out), raw=False)
        return value.replace("\x00", "$"), j + 1, dynamic
    m = re.match(r"([A-Za-z_][A-Za-z0-9_]*)", src[i:])
    if m and m.group(1) in consts:
        return consts[m.group(1)], i + len(m.group(1)), False
    return None


def parse_expression(src: str, i: int, consts: dict):
    """A literal, or a `+` chain of literals and consts; returns (value, end, dynamic) or None."""
    first = parse_literal(src, i, consts)
    if first is None:
        return None
    value, end, dynamic = first
    while True:
        m = re.match(r"\s*\+\s*", src[end:])
        if not m:
            return value, end, dynamic
        nxt = parse_literal(src, end + m.end(), consts)
        if nxt is None:
            return value, end, True
        value, end, dynamic = value + nxt[0], nxt[1], dynamic or nxt[2]


def file_consts(src: str) -> dict:
    """The file's `const val NAME = <literal chain>` values, resolved in order of appearance."""
    consts = {}
    for m in re.finditer(r"\bconst val ([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*String\s*)?=\s*", src):
        parsed = parse_expression(src, m.end(), consts)
        if parsed and not parsed[2]:
            consts[m.group(1)] = parsed[0]
    return consts


def main() -> int:
    found = dynamic_count = 0
    for module in MODULES:
        for source_set in MAIN_SETS:
            base = ROOT / module / "src" / source_set
            if not base.is_dir():
                continue
            for path in sorted(base.rglob("*.kt")):
                src = path.read_text(encoding="utf-8")
                consts = file_consts(src)
                for m in re.finditer(r"\bRegex\(\s*", src):
                    line = src.count("\n", 0, m.start()) + 1
                    where = f"{path.relative_to(ROOT)}:{line}"
                    parsed = parse_expression(src, m.end(), consts)
                    if parsed is None:
                        print(f"DYNAMIC {where}: not a literal", file=sys.stderr)
                        dynamic_count += 1
                        continue
                    value, end, dynamic = parsed
                    if dynamic:
                        print(f"DYNAMIC {where}: built at run time", file=sys.stderr)
                        dynamic_count += 1
                        continue
                    print(f"{where}\t{base64.b64encode(value.encode('utf-8')).decode('ascii')}")
                    found += 1
    print(f"extracted {found} patterns, {dynamic_count} dynamic", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
