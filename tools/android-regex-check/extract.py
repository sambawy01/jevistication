#!/usr/bin/env python3
"""Extracts every regex literal from the Kotlin main sources that run on Android, for check.sh.

Android's java.util.regex is ICU, not the JDK's engine, and it rejects some patterns the JDK and
Kotlin/Native accept (an unescaped `}` is the one found at A0). Unit tests run on the host JDK and
cannot see this, so check.sh compiles every pattern on an emulator or phone instead.

Found: `Regex(<expr>[, options])`, `<literal>.toRegex([options])` and
`Pattern.compile(<expr>[, flags])`, in code only (not in comments or strings), in the shared
modules' commonMain / jvmCommonMain / androidMain and in :android-app's main sources.

Output: one line per pattern, `<file>:<line>\t<base64 of the pattern>\t<java.util.regex flags>`.
The flags are what Kotlin passes to Pattern.compile for the RegexOptions given (IGNORE_CASE is
CASE_INSENSITIVE | UNICODE_CASE), so options that change the syntax (COMMENTS, LITERAL) are
compiled as the app compiles them. Literals, `+` chains of literals and templates naming a
`const val` of the same file are resolved; a pattern built at run time otherwise (Regex.escape,
joinToString, data, a parenthesised expression) is listed on stderr as DYNAMIC for review by hand.
"""
import base64
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULES = ["engine", "templates", "persistence", "sources-common", "game", "backend-laya-common", "loupe-kit", "android-app"]
# The source sets compiled into the Android app: the shared modules' and :android-app's own "main".
MAIN_SETS = ["commonMain", "jvmCommonMain", "androidMain", "main"]

# java.util.regex.Pattern flag values, and what Kotlin's RegexOption passes for each.
JAVA_FLAGS = {
    "UNIX_LINES": 1, "CASE_INSENSITIVE": 2, "COMMENTS": 4, "MULTILINE": 8, "LITERAL": 16,
    "DOTALL": 32, "UNICODE_CASE": 64, "CANON_EQ": 128, "UNICODE_CHARACTER_CLASS": 256,
}
KOTLIN_OPTIONS = {
    "IGNORE_CASE": 2 | 64, "MULTILINE": 8, "LITERAL": 16, "UNIX_LINES": 1, "COMMENTS": 4,
    "DOT_MATCHES_ALL": 32, "CANON_EQ": 128,
}

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


def code_mask(src: str):
    """(is_code, literal_ends): which offsets are code (not in a comment or a string or char
    literal), and {end offset of a string literal: its start offset}."""
    is_code = bytearray(len(src))
    ends = {}
    i, n = 0, len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            depth, j = 1, i + 2  # Kotlin block comments nest
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            i = j
            continue
        if src[i] == '"':
            end = skip_string(src, i)
            ends[end] = i
            i = end
            continue
        if src[i] == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            i = j + 1
            continue
        is_code[i] = 1
        i += 1
    return is_code, ends


def skip_string(src: str, i: int) -> int:
    """The offset just past the string literal starting at src[i], templates `${...}` included."""
    if src.startswith('"""', i):
        j = i + 3
        while True:
            if src.startswith("${", j):
                j = skip_template(src, j + 2)
                continue
            if src.startswith('"""', j):
                j += 3
                while j < len(src) and src[j] == '"':  # a raw string may end in extra quotes
                    j += 1
                return j
            j += 1
    j = i + 1
    while src[j] != '"':
        if src[j] == "\\":
            j += 2
            continue
        if src.startswith("${", j):
            j = skip_template(src, j + 2)
            continue
        j += 1
    return j + 1


def skip_template(src: str, j: int) -> int:
    """Past the closing brace of a `${...}` template whose body starts at src[j]."""
    depth = 1
    while depth:
        if src[j] == '"':
            j = skip_string(src, j)
            continue
        depth += {"{": 1, "}": -1}.get(src[j], 0)
        j += 1
    return j


def call_args(src: str, i: int) -> str:
    """The text from src[i] up to the `)` closing the call whose `(` came before i."""
    depth, j = 1, i
    while j < len(src) and depth:
        if src[j] == '"':
            j = skip_string(src, j)
            continue
        depth += {"(": 1, ")": -1}.get(src[j], 0)
        j += 1
    return src[i:j - 1]


def flags_of(rest: str) -> int:
    """java.util.regex flags for the RegexOption / Pattern flag names in a call's remaining args."""
    flags = 0
    for name in re.findall(r"\bRegexOption\.([A-Z_]+)", rest):
        flags |= KOTLIN_OPTIONS.get(name, 0)
    for name in re.findall(r"\bPattern\.([A-Z_]+)", rest):
        flags |= JAVA_FLAGS.get(name, 0)
    return flags


def patterns_in(src: str):
    """(offset, value or None, dynamic reason or None, flags) for each regex construction in code."""
    consts = file_consts(src)
    is_code, literal_ends = code_mask(src)
    out = []
    for m in re.finditer(r"(?<![\w.])Regex\(\s*|\bPattern\.compile\(\s*", src):
        if not is_code[m.start()]:
            continue
        parsed = parse_expression(src, m.end(), consts)
        if parsed is None:
            out.append((m.start(), None, "not a literal", 0))
            continue
        value, end, dynamic = parsed
        flags = flags_of(call_args(src, end))
        out.append((m.start(), None if dynamic else value, "built at run time" if dynamic else None, flags))
    for m in re.finditer(r"\.toRegex\(", src):
        if not is_code[m.start()]:
            continue
        flags = flags_of(call_args(src, m.end()))
        k = m.start()
        while k > 0 and src[k - 1].isspace():
            k -= 1
        start = literal_ends.get(k)
        if start is not None:
            parsed = parse_expression(src, start, consts)
            dynamic = parsed is None or parsed[1] != k or parsed[2]
            out.append((start, None if dynamic else parsed[0], "built at run time" if dynamic else None, flags))
            continue
        ident = re.search(r"([A-Za-z_][A-Za-z0-9_]*)$", src[:k])
        if ident and ident.group(1) in consts and (ident.start() == 0 or src[ident.start() - 1] != "."):
            out.append((ident.start(), consts[ident.group(1)], None, flags))
        else:
            out.append((m.start(), None, "receiver is not a literal", flags))
    return sorted(out)


def main() -> int:
    found = dynamic_count = 0
    for module in MODULES:
        for source_set in MAIN_SETS:
            base = ROOT / module / "src" / source_set
            if not base.is_dir():
                continue
            for path in sorted(base.rglob("*.kt")):
                src = path.read_text(encoding="utf-8")
                for offset, value, why, flags in patterns_in(src):
                    where = f"{path.relative_to(ROOT)}:{src.count(chr(10), 0, offset) + 1}"
                    if value is None:
                        print(f"DYNAMIC {where}: {why}", file=sys.stderr)
                        dynamic_count += 1
                        continue
                    print(f"{where}\t{base64.b64encode(value.encode('utf-8')).decode('ascii')}\t{flags}")
                    found += 1
    print(f"extracted {found} patterns, {dynamic_count} dynamic", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
