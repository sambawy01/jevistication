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


META_OUTSIDE = "\\^$.|?*+()[]{}"
META_INSIDE = "\\^-[]&"


def _fail(what):
    raise TranslateError(f"{what} is not supported in a baseline pattern")


def _literal(ch: str) -> str:
    return "\\" + ch if ch in META_OUTSIDE else ch


def _member(ch: str) -> str:
    return "\\" + ch if ch in META_INSIDE else ch


def _char_escape(p: str, i: int):
    """(character, length) of a character escape at p[i] (the backslash), else None."""
    if i + 1 >= len(p):
        return None
    e = p[i + 1]

    def hexval(t):
        try:
            v = int(t, 16)
        except ValueError:
            _fail(f"a bad escape \\{e}{t}")
        if not 0 <= v <= 0x10FFFF:
            _fail(f"a bad escape \\{e}{t}")
        return v
    if e == "u":
        t = p[i + 2:i + 6]
        if len(t) < 4:
            _fail("a bad escape \\u")
        return chr(hexval(t)), 6
    if e == "x":
        if i + 2 < len(p) and p[i + 2] == "{":
            close = p.find("}", i + 2)
            if close < 0:
                _fail("a bad escape \\x{")
            return chr(hexval(p[i + 3:close])), close - i + 1
        t = p[i + 2:i + 4]
        if len(t) < 2:
            _fail("a bad escape \\x")
        return chr(hexval(t)), 4
    if e == "0":
        j = i + 2
        mx = i + 5 if i + 2 < len(p) and p[i + 2] in "0123" else i + 4
        while j < len(p) and j < mx and p[j] in "01234567":
            j += 1
        if j == i + 2:
            _fail("a bad escape \\0")
        return chr(int(p[i + 2:j], 8)), j - i
    simple = {"t": "\t", "n": "\n", "r": "\r", "f": "\f", "a": "\x07", "e": "\x1b"}
    if e in simple:
        return simple[e], 2
    if e.isascii() and e.isalnum():
        return None
    return e, 2


def _translate_class(p: str, start: int, out: list, rx: dict) -> int:
    i = start + 1
    out.append("[")
    if i < len(p) and p[i] == "^":
        out.append("^")
        i += 1
    first = True

    def atom(at):
        if p[at] == "\\" and at + 1 < len(p):
            r = _char_escape(p, at)
            return None if r is None else (r[0], at + r[1])
        return p[at], at + 1
    while i < len(p):
        c = p[i]
        if c == "]" and not first:
            out.append("]")
            return i + 1
        first = False
        if c == "[":
            _fail("a class inside [...]")
        if c == "&" and i + 1 < len(p) and p[i + 1] == "&":
            _fail("&& inside [...]")
        if c == "\\" and i + 1 < len(p) and _char_escape(p, i) is None:
            e = p[i + 1]
            if e == "d":
                out.append(rx["Rx.DIGITS"])
            elif e == "s":
                out.append(rx["Rx.SPACE"])
            elif e == "w":
                out.append(rx["Rx.WORDS"])
            elif e == "Q":
                end = p.find("\\E", i + 2)
                end = len(p) if end < 0 else end
                for ch in p[i + 2:end]:
                    out.append(_member(ch))
                    f = _match_form(ch)
                    if f != ch:
                        out.append(_member(f))
                i = end if end >= len(p) else end + 2
                continue
            else:
                _fail(f"\\{e} inside [...]")
            i += 2
            continue
        lo, after = atom(i)
        if after < len(p) and p[after] == "-" and after + 1 < len(p) and p[after + 1] != "]":
            hi_atom = atom(after + 1)
            if hi_atom is None:
                _fail("a class escape as a range end")
            hi = hi_atom[0]
            if ord(hi) < ord(lo):
                _fail("a reversed range")
            out.append(_member(lo) + "-" + _member(hi))
            if ord(hi) - ord(lo) <= 1024:
                extra = sorted({ord(_match_form(chr(c))) for c in range(ord(lo), ord(hi) + 1)} - set(range(ord(lo), ord(hi) + 1)))
                runs = []
                for c in extra:
                    if runs and runs[-1][1] == c - 1:
                        runs[-1][1] = c
                    else:
                        runs.append([c, c])
                for a, b in runs:
                    out.append(_member(chr(a)) + ("-" + _member(chr(b)) if b > a else ""))
            else:
                flo, fhi = _match_form(lo), _match_form(hi)
                if (flo != lo or fhi != hi) and ord(flo) <= ord(fhi):
                    out.append(_member(flo) + "-" + _member(fhi))
            i = hi_atom[1]
            continue
        out.append(_member(lo))
        f = _match_form(lo)
        if f != lo:
            out.append(_member(f))
        i = after
    _fail("an unterminated [...]")


def translate(pattern: str, rx: dict) -> str:
    """PortableRegex.translate, line for line (its fold is ASCII-only here: see the module doc)."""
    p = pattern
    out = []
    consumed_start = re.search(r"\)[*+?{]", p) is None and re.search(r"\\[1-9]|\\k<", p) is None
    group_leading = []
    leading = True
    i = 0
    n = len(p)
    while i < n:
        c = p[i]
        if c == "[":
            leading = False
            i = _translate_class(p, i, out, rx)
        elif c == "\\" and i + 1 < n:
            e = p[i + 1]
            was_leading = leading
            leading = False
            ch = _char_escape(p, i)
            if ch is not None:
                out.append(_literal(_match_form(ch[0])))
                i += ch[1]
                continue
            if e == "d":
                out.append(rx["Rx.DIGIT"])
            elif e == "s":
                out.append(rx["Rx.SP"])
            elif e == "w":
                out.append(rx["Rx.W"])
            elif e == "D":
                out.append("[^" + rx["Rx.DIGITS"] + "]")
            elif e == "S":
                out.append(rx["Rx.NSP"])
            elif e == "W":
                out.append("[^" + rx["Rx.WORDS"] + "]")
            elif e in "bB":
                if was_leading and consumed_start:
                    out.append(leading_wb(rx, e == "B"))
                else:
                    out.append(rx["Rx.WB"] if e == "b" else rx["Rx.NWB"])
            elif e in "pPXRhHvVN":
                _fail("\\" + e)
            elif e == "Q":
                end = p.find("\\E", i + 2)
                end = n if end < 0 else end
                out.append("\\Q" + _match_form(p[i + 2:end]) + "\\E")
                i = end if end >= n else end + 2
                continue
            elif e == "k":
                close = p.find(">", i + 2)
                if close < 0:
                    _fail("\\k without a name")
                out.append(p[i:close + 1])
                i = close + 1
                continue
            elif e == "c":
                out.append(p[i:i + 3])
                i += 3
                continue
            else:
                out.append(c + e)
            i += 2
        elif c == "(" and p.startswith("(?", i):
            nxt = p[i + 2] if i + 2 < n else None
            if nxt == "<" and i + 3 < n and p[i + 3].isalpha():
                close = p.find(">", i)
                if close < 0:
                    _fail("an unterminated group name")
                out.append(p[i:close + 1])
                i = close + 1
                group_leading.append(leading)
            elif nxt is not None and (nxt.isalpha() or nxt == "-"):
                j = i + 2
                while j < n and (p[j].isalpha() or p[j] == "-"):
                    j += 1
                if "x" in p[i + 2:j]:
                    _fail("the comments flag (?x)")
                flags = "".join(f for f in p[i + 2:j] if f not in "iuU")
                kept = flags.rstrip("-")
                if kept == "-":
                    kept = ""
                nx = p[j] if j < n else None
                if nx == ")":
                    if kept:
                        out.append("(?" + kept + ")")
                elif nx == ":":
                    out.append("(?" + kept + ":")
                    group_leading.append(leading)
                else:
                    _fail("a malformed flag group")
                i = j + 1
            else:
                look = nxt in ("=", "!", "<")
                out.append("(?")
                i += 2
                group_leading.append(leading and not look)
                if look:
                    leading = False
        elif c == "(":
            out.append(c)
            i += 1
            group_leading.append(leading)
        elif c == "|":
            out.append(c)
            i += 1
            leading = group_leading[-1] if group_leading else True
        elif c == ")":
            out.append(c)
            i += 1
            if group_leading:
                group_leading.pop()
            leading = False
        elif c in "^$":
            out.append(c)
            i += 1
        else:
            leading = False
            out.append(c if c in META_OUTSIDE else _literal(_match_form(c)))
            i += 1
    return "".join(out)
