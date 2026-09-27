"""Python mirror of the engine's portable regex pieces (engine/.../PortableText.kt): the Rx constants
and PortableRegex.translate, which compiles a baseline pattern (Baseline.Pattern) so it means the same
on every regex engine. extract.py uses it to send the patterns Android really compiles to the device;
the parity corpus (tools/parity/corpus.json, `regex.translate` cases) pins the Kotlin original.

Only ASCII letters are folded here (`fold` in Kotlin folds every script with the pinned Unicode
table); the patterns this sees are the repo's own, and a test in test_extract.py keeps them ASCII
or caseless, so the two agree on them.
"""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PORTABLE_TEXT = ROOT / "engine/src/commonMain/kotlin/dev/loupe/engine/PortableText.kt"


def rx_constants() -> dict:
    """The `const val`s of PortableText.kt (object PortableText and object Rx), by simple and by
    qualified name (`Rx.SP`, `PortableText.SPACE_CHARS`)."""
    import extract  # noqa: E402  (extract imports this module lazily too)
    src = PORTABLE_TEXT.read_text(encoding="utf-8")
    consts = {}
    owner = None
    for line_start in [0] + [m.end() for m in re.finditer(r"\n", src)]:
        line = src[line_start:src.find("\n", line_start) if "\n" in src[line_start:] else len(src)]
        m = re.match(r"(?:object|internal object)\s+(\w+)", line)
        if m:
            owner = m.group(1)
        m = re.match(r"\s*const val ([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*\w+\s*)?=\s*", line)
        if m and owner:
            parsed = extract.parse_expression(src, line_start + m.end(), consts)
            if parsed and not parsed[2]:
                consts[m.group(1)] = parsed[0]
                consts[f"{owner}.{m.group(1)}"] = parsed[0]
    return consts


def _match_form(s: str) -> str:
    out = []
    for ch in s:
        c = ord(ch)
        if 0x660 <= c <= 0x669:
            ch = chr(0x30 + c - 0x660)
        elif 0x6F0 <= c <= 0x6F9:
            ch = chr(0x30 + c - 0x6F0)
        elif "A" <= ch <= "Z":
            ch = ch.lower()
        out.append(ch)
    return "".join(out)


class TranslateError(ValueError):
    pass


def leading_wb(rx: dict, negated: bool) -> str:
    """PortableRegex's LEADING_WB / LEADING_NWB: a `\\b` / `\\B` that nothing precedes, consumed."""
    w = rx["Rx.WORDS"]
    if negated:
        return f"(?:(?:^|[^{w}])(?![{w}])|[{w}](?=[{w}]))"
    return f"(?:(?:^|[^{w}])(?=[{w}])|[{w}](?![{w}]))"


def translate(pattern: str, rx: dict) -> str:
    """PortableRegex.translate, line for line."""
    out = []
    i = 0
    depth = 0
    n = len(pattern)
    group_leading = []
    leading = True

    def fail(what):
        raise TranslateError(f"{what} is not supported in a baseline pattern")
    while i < n:
        c = pattern[i]
        if c == "\\" and i + 1 < n:
            e = pattern[i + 1]
            in_class = depth > 0
            was_leading = leading
            if not in_class:
                leading = False
            if e == "d":
                out.append(rx["Rx.DIGITS"] if in_class else rx["Rx.DIGIT"])
            elif e == "s":
                out.append(rx["Rx.SPACE"] if in_class else rx["Rx.SP"])
            elif e == "w":
                out.append(rx["Rx.WORDS"] if in_class else rx["Rx.W"])
            elif e in "DSW":
                if in_class:
                    fail(f"\\{e} inside [...]")
                out.append({"D": "[^" + rx["Rx.DIGITS"] + "]", "S": rx["Rx.NSP"], "W": "[^" + rx["Rx.WORDS"] + "]"}[e])
            elif e in "bB":
                if in_class:
                    fail(f"\\{e} inside [...]")
                if was_leading:
                    out.append(leading_wb(rx, e == "B"))
                else:
                    out.append(rx["Rx.WB"] if e == "b" else rx["Rx.NWB"])
            elif e in "pPXRhHvVN":
                fail("\\" + e)
            elif e == "Q":
                end = pattern.find("\\E", i + 2)
                end = n if end < 0 else end
                out.append("\\Q" + _match_form(pattern[i + 2:end]) + "\\E")
                i = end if end >= n else end + 2
                continue
            elif e == "k":
                close = pattern.find(">", i + 2)
                if close < 0:
                    fail("\\k without a name")
                out.append(pattern[i:close + 1])
                i = close + 1
                continue
            elif e == "x":
                if i + 2 < n and pattern[i + 2] == "{":
                    j = pattern.find("}", i + 2)
                    length = 2 if j < 0 else j - i + 1
                else:
                    length = 4
                out.append(pattern[i:i + length])
                i += length
                continue
            elif e == "u":
                out.append(pattern[i:i + 6])
                i += 6
                continue
            elif e == "c":
                out.append(pattern[i:i + 3])
                i += 3
                continue
            else:
                out.append(c + e)
            i += 2
        elif c == "[":
            leading = False
            depth += 1
            out.append(c)
            i += 1
            if i < n and pattern[i] == "^":
                out.append(pattern[i])
                i += 1
            if i < n and pattern[i] == "]":
                out.append(pattern[i])
                i += 1
        elif c == "]" and depth > 0:
            depth -= 1
            out.append(c)
            i += 1
        elif c == "(" and depth == 0 and pattern.startswith("(?", i):
            nxt = pattern[i + 2] if i + 2 < n else None
            if nxt == "<" and i + 3 < n and pattern[i + 3].isalpha():
                close = pattern.find(">", i)
                if close < 0:
                    fail("an unterminated group name")
                out.append(pattern[i:close + 1])
                i = close + 1
                group_leading.append(leading)
            elif nxt is not None and (nxt.isalpha() or nxt == "-"):
                j = i + 2
                while j < n and (pattern[j].isalpha() or pattern[j] == "-"):
                    j += 1
                flags = "".join(f for f in pattern[i + 2:j] if f not in "iuU")
                kept = flags.rstrip("-")
                if kept == "-":
                    kept = ""
                nx = pattern[j] if j < n else None
                if nx == ")":
                    if kept:
                        out.append("(?" + kept + ")")
                elif nx == ":":
                    out.append("(?" + kept + ":")
                    group_leading.append(leading)
                else:
                    fail("a malformed flag group")
                i = j + 1
            else:
                look = nxt in ("=", "!", "<")
                out.append("(?")
                i += 2
                group_leading.append(leading and not look)
                if look:
                    leading = False
        elif c == "(" and depth == 0:
            out.append(c)
            i += 1
            group_leading.append(leading)
        elif c == "|" and depth == 0:
            out.append(c)
            i += 1
            leading = group_leading[-1] if group_leading else True
        elif c == ")" and depth == 0:
            out.append(c)
            i += 1
            if group_leading:
                group_leading.pop()
            leading = False
        elif depth == 0 and c in "^$":
            out.append(c)
            i += 1
        else:
            if depth == 0:
                leading = False
            out.append(_match_form(c))
            i += 1
    return "".join(out)
