#!/usr/bin/env python3
"""Generates engine/src/commonMain/kotlin/dev/loupe/engine/UnicodeDataTable.kt: one pinned copy of the
Unicode data Loupe's mechanical text checks need, shared by the JVM, Android and iOS (and readable by
Loupe Station, which can import this module and use the reference implementation below).

Why: the platforms' own Unicode data differs. The JDK's java.net.IDN is Unicode 3.2, the JDK's
java.text.Normalizer is the JDK's Unicode (15.0 in JDK 21), Android's is the phone's ICU (Unicode 11 on
API 29), iOS's is the OS's. The same host or text therefore normalised differently per device
(docs/ANDROID-PLAN.md, "Known parity gaps", B2). This table pins one version for all of them.

Pinned version: Unicode 16.0.0 (see UNICODE_VERSION below for the reasoning, repeated in the table).

Usage (from the repo root; needs Python 3.14 (its unicodedata is 16.0.0, the cross-check) and, once,
network access to unicode.org and rfc-editor.org):

    python3 tools/unicode/gen_unicode_tables.py [--ucd DIR] [--check]

The source files are read from DIR (default build/ucd, downloaded when missing) and must match the
SHA-256 recorded in SOURCES; a mismatch fails. --check regenerates in memory and fails when the
committed Kotlin file differs. Before writing, the reference implementation here is
verified against NormalizationTest.txt (every line, all four forms) and against the NFKC_Casefold
property of DerivedNormalizationProps.txt (every code point); any disagreement fails the run.

What the table holds (all run- or delta-coded, base 36, see the Kotlin reader UnicodeData.kt):
  CATEGORY     the general-category group of every code point: letter, decimal digit, letter number,
               other number, nonspacing / spacing / enclosing mark, space separator, or other
  FOLD         the simple case fold used for keyword matching: lower(upper(c)) with the simple
               mappings of UnicodeData.txt (what java.util.regex CASE_INSENSITIVE|UNICODE_CASE
               compares); one UTF-16 unit per unit, so indexes into folded text stay valid
  LOWER        the simple lowercase mapping of UnicodeData.txt (PortableText.lowercase adds the one
               unconditional special casing, U+0130 to i + U+0307)
  CCC          canonical combining classes
  DECOMP       canonical and compatibility decomposition mappings (one level; applied recursively)
  EXCLUDED     Full_Composition_Exclusion (primary composites are the other two-character canonical
               decompositions)
  IGNORABLE    code points NFKC_Casefold (NFKC_CF) maps to nothing (default ignorables)
  CASEFOLD_NFKC  NFKC_CF where it differs otherwise from what the rule `NFKC(full case fold(NFKC(c)))`
               gives (none in 16.0). NFKC_CF is the IDNA mapping step (lower-casing, NFKC, dropping
               default ignorables) in one property
  UTS46_DISALLOWED  UTS #46 status `disallowed` (IdnaMappingTable.txt)
  UTS46_MAPPING     UTS #46 mappings (valid = itself, ignored = nothing, mapped) where they differ from
               NFKC_Casefold, which gives every other one; tag `e` = mapped to nothing. The four
               deviations (ß, ς, ZWNJ, ZWJ) are in the Kotlin code
  JOINING      Joining_Type (0 U, 1 L, 2 D, 3 R, 4 T), for the CONTEXTJ rule on ZWNJ (RFC 5892 A.1)
  STABLE32     code points whose IDNA mapping is the same under Unicode 3.2 nameprep (RFC 3491:
               table B.1 removed, table B.2 case map, NFKC on Unicode 3.2 data; what java.net.IDN
               and older resolvers use; a code point unassigned in 3.2 passes through unchanged, as
               with ALLOW_UNASSIGNED) and under the engine's pinned mapping (table B.1 removed, then
               NFKC_Casefold): a host label with a code point outside this set reads differently on
               old and new software (the `unicode_drift_host` signal).
               Validity rules (prohibited output, bidi) are not part of the comparison.
  FULLFOLD     CaseFolding.txt status C and F (full case folding), used only to derive CASEFOLD_NFKC
"""
import argparse
import hashlib
import re
import pathlib
import sys
import unicodedata
import urllib.request

UNICODE_VERSION = "16.0.0"
# Why 16.0.0 (checked 2026-09-27): Chrome stable 155 ships ICU 78.2 (Unicode 17.0); Apple's latest
# published ICU (apple-oss-distributions/ICU, ICU-76142.5.1.200) is ICU 76.1, Unicode 16.0, which
# Safari/WebKit use through the OS. 16.0 is the newest version both browsers implement, and by the
# normalization stability policy every 16.0 mapping is unchanged in 17.0, so a 16.0 answer is also
# Chrome's for every character that exists in 16.0. Characters new in 17.0 are unassigned here and
# pass through unmapped, as they do in 3.2 nameprep, so they are not drift. Python 3.14's
# unicodedata is also 16.0.0, which this script uses as an independent cross-check.
# name -> (URL, SHA-256). The Unicode 3.2 files and RFC 3454 define nameprep, for STABLE32.
UCD = f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/"
SOURCES = {
    "UnicodeData.txt": (UCD + "UnicodeData.txt", "ff58e5823bd095166564a006e47d111130813dcf8bf234ef79fa51a870edb48f"),
    "CaseFolding.txt": (UCD + "CaseFolding.txt", "6f1f9c588eb4a5c718d9e8f93b782685e5c7fec872cf05e8e6878053599e09bb"),
    "DerivedNormalizationProps.txt": (UCD + "DerivedNormalizationProps.txt", "4d4c03892dea9146d674b686e495df2d55a28d071ac474041d73518f887abddc"),
    "NormalizationTest.txt": (UCD + "NormalizationTest.txt", "d811971453e7075e1ad56fb1b301eece5aa80757b81f6156e74a1bfb3ae5ceb1"),
    "UnicodeData-3.2.0.txt": ("https://www.unicode.org/Public/3.2-Update/UnicodeData-3.2.0.txt", "5e444028b6e76d96f9dc509609c5e3222bf609056f35e5fcde7e6fb8a58cd446"),
    "CompositionExclusions-3.2.0.txt": ("https://www.unicode.org/Public/3.2-Update/CompositionExclusions-3.2.0.txt", "1d3a450d0f39902710df4972ac4a60ec31fbcb54ffd4d53cd812fc1200c732cb"),
    "rfc3454.txt": ("https://www.rfc-editor.org/rfc/rfc3454.txt", "eb722fa698fb7e8823b835d9fd263e4cdb8f1c7b0d234edf7f0e3bd2ccbb2c79"),
    # UTS #46 (what browsers resolve: WHATWG URL, non-transitional) and the joining types CONTEXTJ reads.
    "IdnaMappingTable.txt": (f"https://www.unicode.org/Public/idna/{UNICODE_VERSION}/IdnaMappingTable.txt", "6db2ef4ed35f3b3de74ebc2e00404a9607f76d499f576b8d4043cf14f1ed175c"),
    "IdnaTestV2.txt": (f"https://www.unicode.org/Public/idna/{UNICODE_VERSION}/IdnaTestV2.txt", "ffcf59f6e97d765caa5ac8315e9511ba3b42955a554cbc431e4c016f20d25ca9"),
    "DerivedJoiningType.txt": (UCD + "extracted/DerivedJoiningType.txt", "6bd08b97da66b70ccfdab105a352de2984e02625239ec5695422c99b33d854f0"),
}

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "engine/src/commonMain/kotlin/dev/loupe/engine/UnicodeDataTable.kt"

MAX = 0x10FFFF
S_BASE, L_BASE, V_BASE, T_BASE = 0xAC00, 0x1100, 0x1161, 0x11A7
L_COUNT, V_COUNT, T_COUNT = 19, 21, 28
N_COUNT = V_COUNT * T_COUNT
S_COUNT = L_COUNT * N_COUNT

# Category groups (the numbers are the table's; UnicodeData.kt names them).
CAT_OTHER, CAT_LETTER, CAT_ND, CAT_NL, CAT_NO, CAT_MN, CAT_MC, CAT_ME, CAT_ZS = range(9)
GC_GROUP = {"Nd": CAT_ND, "Nl": CAT_NL, "No": CAT_NO, "Mn": CAT_MN, "Mc": CAT_MC, "Me": CAT_ME, "Zs": CAT_ZS}


# ------------------------------------------------------------------------------------------ inputs
def load(ucd: pathlib.Path) -> dict:
    ucd.mkdir(parents=True, exist_ok=True)
    texts = {}
    for name, (url, want) in SOURCES.items():
        path = ucd / name
        if not path.exists():
            print(f"downloading {url}", file=sys.stderr)
            with urllib.request.urlopen(url) as r:
                path.write_bytes(r.read())
        data = path.read_bytes()
        got = hashlib.sha256(data).hexdigest()
        if got != want:
            sys.exit(f"{path}: SHA-256 {got}, expected {want}")
        texts[name] = data.decode("utf-8")
    return texts


def ranges(field: str):
    if ".." in field:
        a, b = field.split("..")
        return range(int(a, 16), int(b, 16) + 1)
    return range(int(field, 16), int(field, 16) + 1)


class Data:
    """One version's normalization and case data. [texts] holds UnicodeData.txt and, for the pinned
    version, CaseFolding.txt and DerivedNormalizationProps.txt; for Unicode 3.2 ([exclusions] given)
    the full composition exclusions are derived as UAX #15 defines them."""

    def __init__(self, texts: dict, unicode_data: str = "UnicodeData.txt", exclusions: str = None):
        self.cat = bytearray(MAX + 1)
        self.ccc = bytearray(MAX + 1)
        self.decomp = {}      # cp -> (compat, [cps])
        self.upper = {}
        self.lower = {}
        self.assigned = bytearray(MAX + 1)
        first = None
        for line in texts[unicode_data].splitlines():
            f = line.split(";")
            cp = int(f[0], 16)
            if f[1].endswith(", First>"):
                first = cp
                continue
            span = range(first, cp + 1) if f[1].endswith(", Last>") else range(cp, cp + 1)
            first = None
            gc = f[2]
            group = CAT_LETTER if gc.startswith("L") else GC_GROUP.get(gc, CAT_OTHER)
            for c in span:
                self.cat[c] = group
                self.ccc[c] = int(f[3])
                self.assigned[c] = 1
            if f[5]:
                parts = f[5].split()
                compat = parts[0].startswith("<")
                if compat:
                    parts = parts[1:]
                self.decomp[cp] = (compat, [int(p, 16) for p in parts])
            if f[12]:
                self.upper[cp] = int(f[12], 16)
            if f[13]:
                self.lower[cp] = int(f[13], 16)
        self.full_fold = {}
        self.excluded = set()
        self.nfkc_cf = {}
        self.compose = {}
        if exclusions is not None:
            # Full_Composition_Exclusion = the listed exclusions + singletons + non-starter decompositions
            for line in texts[exclusions].splitlines():
                body = line.split("#")[0].strip()
                if body:
                    self.excluded.update(ranges(body))
            for cp, (compat, seq) in self.decomp.items():
                if not compat and (len(seq) == 1 or self.ccc[seq[0]] != 0):
                    self.excluded.add(cp)
            self._compositions()
            return
        for line in texts["CaseFolding.txt"].splitlines():
            line = line.split("#")[0].strip()
            if not line:
                continue
            code, status, mapping = [x.strip() for x in line.split(";")[:3]]
            if status in ("C", "F"):
                self.full_fold[int(code, 16)] = [int(x, 16) for x in mapping.split()]
        for line in texts["DerivedNormalizationProps.txt"].splitlines():
            body = line.split("#")[0].strip()
            if not body:
                continue
            f = [x.strip() for x in body.split(";")]
            if f[1] == "Full_Composition_Exclusion":
                self.excluded.update(ranges(f[0]))
            elif f[1] == "NFKC_CF":
                value = [int(x, 16) for x in f[2].split()] if len(f) > 2 and f[2] else []
                for c in ranges(f[0]):
                    self.nfkc_cf[c] = value
        self._compositions()

    def _compositions(self):
        for cp, (compat, seq) in self.decomp.items():
            if not compat and len(seq) == 2 and cp not in self.excluded:
                self.compose[(seq[0], seq[1])] = cp


# ------------------------------------------------------------------------ reference implementation
# The Kotlin code in UnicodeData.kt / UnicodeNormalizer.kt is a line-for-line port of these functions.
def decompose(d: Data, cps, compat: bool):
    out = []

    def one(c):
        if S_BASE <= c < S_BASE + S_COUNT:
            s = c - S_BASE
            out.append(L_BASE + s // N_COUNT)
            out.append(V_BASE + (s % N_COUNT) // T_COUNT)
            if s % T_COUNT:
                out.append(T_BASE + s % T_COUNT)
            return
        m = d.decomp.get(c)
        if m is not None and (compat or not m[0]):
            for x in m[1]:
                one(x)
        else:
            out.append(c)
    for c in cps:
        one(c)
    # canonical ordering: a stable sort of each run of non-starters by combining class
    i = 0
    while i < len(out):
        if d.ccc[out[i]] == 0:
            i += 1
            continue
        j = i
        while j < len(out) and d.ccc[out[j]] != 0:
            j += 1
        out[i:j] = sorted(out[i:j], key=lambda x: d.ccc[x])
        i = j
    return out


def compose_pair(d: Data, a: int, b: int):
    if L_BASE <= a < L_BASE + L_COUNT and V_BASE <= b < V_BASE + V_COUNT:
        return S_BASE + ((a - L_BASE) * V_COUNT + (b - V_BASE)) * T_COUNT
    if S_BASE <= a < S_BASE + S_COUNT and (a - S_BASE) % T_COUNT == 0 and T_BASE < b < T_BASE + T_COUNT:
        return a + (b - T_BASE)
    return d.compose.get((a, b))


def compose(d: Data, cps):
    """Canonical composition (UAX #15 section 1.3, the sample algorithm of its annex)."""
    if not cps:
        return []
    out = [cps[0]]
    starter = 0
    last = d.ccc[cps[0]]
    if last != 0:
        last = 256  # a leading non-starter blocks everything until the next starter
    for c in cps[1:]:
        cc = d.ccc[c]
        comp = compose_pair(d, out[starter], c)
        if comp is not None and (last < cc or last == 0):
            out[starter] = comp
            continue
        if cc == 0:
            starter = len(out)
        last = cc
        out.append(c)
    return out


def nfd(d, cps): return decompose(d, cps, False)
def nfkd(d, cps): return decompose(d, cps, True)
def nfc(d, cps): return compose(d, decompose(d, cps, False))
def nfkc(d, cps): return compose(d, decompose(d, cps, True))


def full_fold(d, cps):
    out = []
    for c in cps:
        out.extend(d.full_fold.get(c, [c]))
    return out


def derived_nfkc_cf(d, c):
    """The rule CASEFOLD_NFKC corrects: NFKC(full case fold(NFKC(c)))."""
    return nfkc(d, full_fold(d, nfkc(d, [c])))


def nfkc_casefold_char(d, c, exceptions):
    if c in exceptions:
        return exceptions[c]
    return derived_nfkc_cf(d, c)


def to_nfkc_casefold(d, cps, exceptions):
    """toNFKC_Casefold (Unicode ch. 3.13): NFKC_CF of every character, then NFC."""
    mapped = []
    for c in cps:
        mapped.extend(nfkc_casefold_char(d, c, exceptions))
    return nfc(d, mapped)


# RFC 3454 table B.1 (commonly mapped to nothing): the engine's Idna removes these before NFKC_CF,
# as nameprep does, so a label keeps java.net.IDN's answer wherever the two versions agree.
B1 = {0x00AD, 0x034F, 0x1806, 0x180B, 0x180C, 0x180D, 0x200B, 0x200C, 0x200D, 0x2060,
      *range(0xFE00, 0xFE10), 0xFEFF}


def idna_map(d, cps, exceptions):
    """The engine's IDNA mapping (Idna.toAsciiLabel before Punycode): B.1 removed, then toNFKC_Casefold."""
    return to_nfkc_casefold(d, [c for c in cps if c not in B1], exceptions)


def simple_fold(d, c):
    u = d.upper.get(c, c)
    return d.lower.get(u, u)


# ------------------------------------------------------------------------------ Unicode 3.2 nameprep
def rfc3454_table(texts: dict, name: str) -> dict:
    """An RFC 3454 mapping table (`B.1`, `B.2`): code point -> mapped code points."""
    text = texts["rfc3454.txt"]
    body = text[text.index(f"----- Start Table {name} -----"):text.index(f"----- End Table {name} -----")]
    out = {}
    for line in body.splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("Hoffman") or line.startswith("RFC 3454"):
            continue
        f = [x.strip() for x in line.split(";")]
        if len(f) < 2 or not re.fullmatch(r"[0-9A-F]{4,6}", f[0]):
            continue
        out[int(f[0], 16)] = [int(x, 16) for x in f[1].split()]
    return out


class Nameprep32:
    """RFC 3491 nameprep's mapping on Unicode 3.2 data: B.1 to nothing, the B.2 case map, NFKC (3.2)."""

    def __init__(self, texts: dict):
        self.d = Data(texts, "UnicodeData-3.2.0.txt", "CompositionExclusions-3.2.0.txt")
        self.b1 = rfc3454_table(texts, "B.1")
        self.b2 = rfc3454_table(texts, "B.2")
        assert set(self.b1) == B1, "RFC 3454 B.1 differs from the engine's list"
        assert len(self.b2) > 1300, f"only {len(self.b2)} B.2 rows parsed"

    def assigned(self, c: int) -> bool:
        return bool(self.d.assigned[c]) and not (0xD800 <= c <= 0xDFFF)

    def map(self, cps):
        out = []
        for c in cps:
            if c in self.b1:
                continue
            out.extend(self.b2.get(c, [c]))
        return nfkc(self.d, out)


# ------------------------------------------------------------------------------------------ UTS #46
DEVIATIONS = {0x00DF: [0x73, 0x73], 0x03C2: [0x03C3], 0x200C: [], 0x200D: []}
JT = {"U": 0, "L": 1, "D": 2, "R": 3, "T": 4}


class Uts46:
    """UTS #46 IDNA processing as WHATWG's `domain to ASCII` does it (UseSTD3ASCIIRules false,
    CheckHyphens false, CheckJoiners true; CheckBidi and DNS length are not checked here), over the
    table data, plus the reference implementation the Kotlin Uts46 object ports."""

    def __init__(self, texts: dict, d: Data, exceptions: dict):
        self.d = d
        self.status = {}
        self.mapping = {}
        for line in texts["IdnaMappingTable.txt"].splitlines():
            body = line.split("#")[0].strip()
            if not body:
                continue
            f = [x.strip() for x in body.split(";")]
            for c in ranges(f[0]):
                self.status[c] = f[1]
                if f[1] in ("mapped", "deviation") and len(f) > 2:
                    self.mapping[c] = [int(x, 16) for x in f[2].split()] if f[2] else []
        self.joining = bytearray(MAX + 1)
        for line in texts["DerivedJoiningType.txt"].splitlines():
            body = line.split("#")[0].strip()
            if not body:
                continue
            f = [x.strip() for x in body.split(";")]
            if f[1] in JT:
                for c in ranges(f[0]):
                    self.joining[c] = JT[f[1]]
        # The table's mapping of a code point, derived as the Kotlin side derives it (NFKC_Casefold)
        # unless listed: every difference is stored.
        self.differs = {}
        for c in range(MAX + 1):
            if 0xD800 <= c <= 0xDFFF:
                continue
            st = self.status.get(c, "disallowed")
            if st in ("disallowed", "deviation"):
                continue
            want = [c] if st == "valid" else ([] if st == "ignored" else self.mapping[c])
            if nfkc_casefold_char(d, c, exceptions) != want:
                self.differs[c] = want
        self.exceptions = exceptions
        for c, m in DEVIATIONS.items():
            assert self.status[c] == "deviation" and self.mapping.get(c, []) == m, f"U+{c:04X} deviation"

    def map_cp(self, c: int, transitional: bool):
        st = self.status.get(c, "disallowed")
        if st == "deviation":
            return (DEVIATIONS[c] if transitional else [c]), False
        if st == "disallowed":
            return [c], True
        if c in self.differs:
            return self.differs[c], False
        return nfkc_casefold_char(self.d, c, self.exceptions), False

    def contextj_ok(self, label: list, i: int) -> bool:
        c = label[i]
        if i > 0 and self.d.ccc[label[i - 1]] == 9:
            return True
        if c == 0x200D:
            return False
        j = i - 1
        while j >= 0 and self.joining[label[j]] == JT["T"]:
            j -= 1
        if j < 0 or self.joining[label[j]] not in (JT["L"], JT["D"]):
            return False
        k = i + 1
        while k < len(label) and self.joining[label[k]] == JT["T"]:
            k += 1
        return k < len(label) and self.joining[label[k]] in (JT["R"], JT["D"])

    def to_ascii(self, host: str, transitional: bool):
        """(ASCII host, errors)."""
        errors = set()
        mapped = []
        for ch in host:
            m, bad = self.map_cp(ord(ch), transitional)
            if bad:
                errors.add("P1")
            for x in m:
                # transitional: a deviation the mapping produced (U+1E9E -> ß) is mapped too
                mapped.extend(DEVIATIONS[x] if transitional and x in DEVIATIONS else [x])
        mapped = nfc(self.d, mapped)
        out = []
        label = []
        for c in mapped + [0x2E]:
            if c != 0x2E:
                label.append(c)
                continue
            text = "".join(chr(x) for x in label)
            uni = label
            if text.startswith("xn--"):
                try:
                    uni = [ord(x) for x in text[4:].encode("ascii").decode("punycode")]
                except Exception:
                    errors.add("P4")
            for i, x in enumerate(uni):
                if x in (0x200C, 0x200D) and not self.contextj_ok(uni, i):
                    errors.add("C")
                # (a decoded xn-- label is validated non-transitionally in either mode, as UTS #46 15.1+ says)
                if self.status.get(x, "disallowed") == "disallowed":
                    errors.add("V6")
            if all(x < 0x80 for x in label):
                out.append(text)
            else:
                out.append("xn--" + text.encode("punycode").decode("ascii"))
            label = []
        return ".".join(out), errors


UTS46_VECTORS_OUT = ROOT / "engine/src/commonTest/kotlin/dev/loupe/engine/Uts46TestVectors.kt"


def kotlin_escape(t: str) -> str:
    out = []
    for ch in t:
        c = ord(ch)
        if ch in '\\"$':
            out.append("\\" + ch)
        elif 0x20 <= c < 0x7F:
            out.append(ch)
        elif c > 0xFFFF:
            v = c - 0x10000
            out.append("\\u%04x\\u%04x" % (0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF)))
        else:
            out.append("\\u%04x" % c)
    return "".join(out)


def uts46_vectors_source(rows: list) -> str:
    """The verified IdnaTestV2 rows as a commonTest constant, so the Kotlin port is held to them on
    every platform (JVM, iOS simulator, Android unit tests)."""
    lines = [
        "// GENERATED by tools/unicode/gen_unicode_tables.py from IdnaTestV2.txt " + UNICODE_VERSION + ". Do not edit.",
        "package dev.loupe.engine",
        "",
        "/** (source, non-transitional ToASCII or null when it has errors, transitional ToASCII or null). */",
        "internal val UTS46_TEST_VECTORS: List<Triple<String, String?, String?>> = listOf(",
    ]
    for src, n, t in rows:
        q = lambda x: "null" if x is None else '"' + kotlin_escape(x) + '"'
        lines.append(f"    Triple({q(src)}, {q(n)}, {q(t)}),")
    lines.append(")")
    return "\n".join(lines) + "\n"


def verify_uts46(u: Uts46, texts: dict) -> int:
    """Every IdnaTestV2.txt line whose expected ToASCII (non-transitional and transitional) has no
    error must give that answer."""
    import re as _re

    def unesc(t):
        return _re.sub(r"\\u([0-9A-Fa-f]{4})|\\x\{([0-9A-Fa-f]+)\}", lambda m: chr(int(m.group(1) or m.group(2), 16)), t)
    n = 0
    rows = []
    for line in texts["IdnaTestV2.txt"].splitlines():
        body = line.split("#")[0].rstrip()
        if not body.strip():
            continue
        f = [x.strip() for x in body.split(";")]
        src = unesc(f[0])
        to_uni = unesc(f[1]) if f[1] else src
        uni_status = f[2] or "[]"
        ascii_n = unesc(f[3]) if f[3] else to_uni
        n_status = f[4] or uni_status
        ascii_t = unesc(f[5]) if f[5] else ascii_n
        t_status = f[6] or n_status
        rows.append((src, ascii_n if n_status == "[]" else None, ascii_t if t_status == "[]" else None))
        for transitional, want, status in ((False, ascii_n, n_status), (True, ascii_t, t_status)):
            if status != "[]":
                continue
            got, errs = u.to_ascii(src, transitional)
            if got != want:
                sys.exit(f"IdnaTestV2 {'T' if transitional else 'N'}: {f[0]!r} -> {got!r}, expected {want!r}")
            if errs:
                sys.exit(f"IdnaTestV2 {'T' if transitional else 'N'}: {f[0]!r} reported {errs}, expected none")
            n += 1
    u.vectors = [r for r in rows if r[1] is not None or r[2] is not None]
    return n


# ---------------------------------------------------------------------------------- verification
def verify(d: Data, exceptions: dict, texts: dict):
    lines = 0
    for line in texts["NormalizationTest.txt"].splitlines():
        body = line.split("#")[0].strip()
        if not body or body.startswith("@"):
            continue
        cols = [[int(x, 16) for x in col.split()] for col in body.split(";")[:5]]
        src, c2, c3, c4, c5 = cols
        for got, want, what in (
            (nfc(d, src), c2, "NFC"), (nfd(d, src), c3, "NFD"), (nfkc(d, src), c4, "NFKC"), (nfkd(d, src), c5, "NFKD"),
            (nfc(d, c4), c4, "NFC(c4)"), (nfkc(d, c2), c4, "NFKC(c2)"),
        ):
            if got != want:
                sys.exit(f"NormalizationTest: {what}({body}) = {got}, expected {want}")
        lines += 1
    # every code point: NFKC_Casefold against the property, and the forms against Python's own 16.0
    assert unicodedata.unidata_version == UNICODE_VERSION, "cross-check needs Python's unicodedata " + UNICODE_VERSION
    for c in range(MAX + 1):
        if 0xD800 <= c <= 0xDFFF:
            continue
        want = d.nfkc_cf.get(c, [c])
        got = nfkc_casefold_char(d, c, exceptions)
        if got != want:
            sys.exit(f"NFKC_CF U+{c:04X}: {got}, expected {want}")
        s = chr(c)
        for form, fn in (("NFC", nfc), ("NFD", nfd), ("NFKC", nfkc), ("NFKD", nfkd)):
            if [ord(x) for x in unicodedata.normalize(form, s)] != fn(d, [c]):
                sys.exit(f"{form} U+{c:04X} disagrees with Python's unicodedata {unicodedata.unidata_version}")
    return lines


# --------------------------------------------------------------------------------------- encoding
def b36(n: int) -> str:
    if n < 0:
        return "-" + b36(-n)
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    out = ""
    while True:
        out = digits[n % 36] + out
        n //= 36
        if n == 0:
            return out


def runs(values) -> str:
    """`length:value;` for each run of equal values, from code point 0 to U+10FFFF."""
    out, prev, start = [], values[0], 0
    for c in range(1, len(values)):
        if values[c] != prev:
            out.append(f"{b36(c - start)}:{b36(prev)}")
            prev, start = values[c], c
    out.append(f"{b36(len(values) - start)}:{b36(prev)}")
    return ";".join(out)


def flag_runs(values) -> str:
    """A set of code points as alternating run lengths `out;in;out;in;...`, starting outside."""
    out, prev, start = [], 0, 0
    for c in range(len(values)):
        if values[c] != prev:
            out.append(b36(c - start))
            prev, start = values[c], c
    out.append(b36(len(values) - start))
    return ";".join(out)


def delta_runs(mapping: dict) -> str:
    """`gap:count:step:delta;`: [count] code points start, start+step, ... each mapped to itself+delta,
    where start is [gap] past the end (last code point + 1) of the previous entry."""
    items = sorted(mapping.items())
    out, i, end = [], 0, 0
    while i < len(items):
        c, t = items[i]
        delta, step, n = t - c, 1, 1
        if i + 1 < len(items) and items[i + 1][1] - items[i + 1][0] == delta and items[i + 1][0] - c in (1, 2):
            step = items[i + 1][0] - c
            while i + n < len(items) and items[i + n][0] == c + n * step and items[i + n][1] - items[i + n][0] == delta:
                n += 1
        out.append(f"{b36(c - end)}:{b36(n)}:{step}:{b36(delta)}")
        end = c + (n - 1) * step + 1
        i += n
    return ";".join(out)


def seq_runs(mapping: dict) -> str:
    """Mappings to code point sequences, each entry starting [gap] past the previous entry's end:
    `gap:t:t1,t2,...` for one code point with tag t (a lowercase letter), or `gap:T:count:target`
    (tag uppercased) for [count] consecutive code points mapped to consecutive single code points."""
    items = sorted(mapping.items())
    out, i, end = [], 0, 0
    while i < len(items):
        c, (tg, seq) = items[i]
        n = 1
        if len(seq) == 1:
            while i + n < len(items):
                c2, (tg2, seq2) = items[i + n]
                if c2 != c + n or tg2 != tg or len(seq2) != 1 or seq2[0] != seq[0] + n:
                    break
                n += 1
        if n > 1:
            out.append(f"{b36(c - end)}:{tg.upper()}:{b36(n)}:{b36(seq[0])}")
        else:
            out.append(f"{b36(c - end)}:{tg}:" + ",".join(b36(x) for x in seq))
        end = c + n
        i += n
    return ";".join(out)


def kotlin_chunks(text: str, chunk: int = 8000) -> str:
    return "".join(f'        "{text[i:i + chunk]}",\n' for i in range(0, len(text), chunk))


def generate(texts: dict) -> str:
    d = Data(texts)
    # simple fold: one UTF-16 unit per unit (asserted), so indexes stay valid
    fold = {}
    for c in range(MAX + 1):
        f = simple_fold(d, c)
        if f != c:
            assert (c > 0xFFFF) == (f > 0xFFFF), f"fold of U+{c:04X} changes UTF-16 length"
            fold[c] = f
    # simple lowercase (UnicodeData.txt field 13), for PortableText.lowercase
    lower = {}
    for c, t in d.lower.items():
        assert (c > 0xFFFF) == (t > 0xFFFF), f"lowercase of U+{c:04X} changes UTF-16 length"
        lower[c] = t
    # NFKC_CF exceptions to the derived rule
    exceptions = {}
    for c in range(MAX + 1):
        if 0xD800 <= c <= 0xDFFF:
            continue
        want = d.nfkc_cf.get(c, [c])
        if derived_nfkc_cf(d, c) != want:
            exceptions[c] = want
    lines = verify(d, exceptions, texts)
    # A code point drifts when Unicode 3.2 nameprep (an unassigned one passes through unchanged, as
    # IDNA 2003 with ALLOW_UNASSIGNED does) and the pinned mapping give different results. A new
    # character that maps to itself (Burmese medials, emoji, new CJK) does not drift; one that maps
    # to something else now (U+1F130 -> a, U+1E030 -> Cyrillic a) does.
    prep32 = Nameprep32(texts)
    stable = bytearray(MAX + 1)
    drift_assigned = drift_new = 0
    for c in range(MAX + 1):
        if 0xD800 <= c <= 0xDFFF:
            stable[c] = 1
            continue
        if prep32.map([c]) == idna_map(d, [c], exceptions):
            stable[c] = 1
        elif prep32.assigned(c):
            drift_assigned += 1
        else:
            drift_new += 1
    decomp = {c: ("k" if compat else "c", seq) for c, (compat, seq) in d.decomp.items()}
    fullfold = {c: ("f", seq) for c, seq in d.full_fold.items()}
    # Every NFKC_CF exception in 16.0 is a code point mapped to nothing (a default ignorable, e.g.
    # U+200B, U+FE0F, the tag characters): kept as a set; any other exception as a mapping.
    ignorable = bytearray(MAX + 1)
    exc = {}
    for c, seq in exceptions.items():
        if seq:
            exc[c] = ("x", seq)
        else:
            ignorable[c] = 1
    excluded = [1 if c in d.excluded else 0 for c in range(MAX + 1)]
    u46 = Uts46(texts, d, exceptions)
    uts46_lines = verify_uts46(u46, texts)
    disallowed = bytearray(MAX + 1)
    for c in range(MAX + 1):
        if 0xD800 <= c <= 0xDFFF or u46.status.get(c, "disallowed") == "disallowed":
            disallowed[c] = 1
    u46_differs = {c: (("e", [0]) if not m else ("m", m)) for c, m in u46.differs.items()}
    generate.vectors = uts46_vectors_source(u46.vectors)
    fields = [
        ("CATEGORY", runs(d.cat)),
        ("FOLD", delta_runs(fold)),
        ("LOWER", delta_runs(lower)),
        ("CCC", runs(d.ccc)),
        ("DECOMP", seq_runs(decomp)),
        ("EXCLUDED", flag_runs(excluded)),
        ("FULLFOLD", seq_runs(fullfold)),
        ("IGNORABLE", flag_runs(ignorable)),
        ("CASEFOLD_NFKC", seq_runs(exc)),
        ("STABLE32", flag_runs(stable)),
        ("UTS46_DISALLOWED", flag_runs(disallowed)),
        ("UTS46_MAPPING", seq_runs(u46_differs)),
        ("JOINING", runs(u46.joining)),
    ]
    src_lines = "\n".join(f" *   {n}  sha256 {h}" for n, (u, h) in SOURCES.items() if u.startswith(UCD))
    out = [
        "// GENERATED by tools/unicode/gen_unicode_tables.py from the Unicode " + UNICODE_VERSION + " UCD. Do not edit.",
        "package dev.loupe.engine",
        "",
        "/*",
        " * Sources (https://www.unicode.org/Public/" + UNICODE_VERSION + "/ucd/):",
        src_lines,
        " * STABLE32 (Unicode 3.2 nameprep): https://www.unicode.org/Public/3.2-Update/ UnicodeData-3.2.0.txt,",
        " *   CompositionExclusions-3.2.0.txt, and RFC 3454 tables B.1 / B.2 (sha256 in the generator).",
        f" * Verified at generation: {lines} NormalizationTest.txt lines (NFC, NFD, NFKC, NFKD); NFKC_CF of every",
        " * code point; NFC/NFD/NFKC/NFKD of every code point against Python's unicodedata " + unicodedata.unidata_version + ".",
        f" * {len(fold)} simple folds, {len(lower)} lowercase mappings, {len(d.decomp)} decompositions,",
        f" * {int(sum(ignorable))} default ignorables, {len(exc)} other NFKC_CF exceptions to the derived rule,",
        f" * drift: {drift_assigned} code points assigned in 3.2 whose IDNA mapping changed since, and",
        f" * {drift_new} added later that the pinned mapping changes (3.2 passes them through unchanged).",
        f" * UTS #46 ({UNICODE_VERSION}): {int(sum(disallowed))} disallowed code points, {len(u46.differs)} mappings that differ from",
        f" * NFKC_Casefold; verified on {uts46_lines} error-free IdnaTestV2.txt answers (non-transitional and transitional).",
        " * Format: see tools/unicode/gen_unicode_tables.py and UnicodeData.kt.",
        " */",
        "internal object UnicodeDataTable {",
        f'    const val UNICODE_VERSION: String = "{UNICODE_VERSION}"',
        "",
    ]
    for name, value in fields:
        if not value:
            out.append(f'    val {name}: String = ""')
            out.append("")
            continue
        out.append(f"    val {name}: String = arrayOf(")
        out.append(kotlin_chunks(value).rstrip("\n"))
        out.append('    ).joinToString("")')
        out.append("")
    out[-1] = "}"
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ucd", default=str(ROOT / "build/ucd"))
    ap.add_argument("--check", action="store_true", help="fail when the committed table differs")
    args = ap.parse_args()
    text = generate(load(pathlib.Path(args.ucd)))
    vectors = generate.vectors
    if args.check:
        if OUT.read_text(encoding="utf-8") != text or UTS46_VECTORS_OUT.read_text(encoding="utf-8") != vectors:
            print(f"{OUT.relative_to(ROOT)} is stale: rerun tools/unicode/gen_unicode_tables.py", file=sys.stderr)
            return 1
        print("unicode table up to date")
        return 0
    OUT.write_text(text, encoding="utf-8")
    UTS46_VECTORS_OUT.write_text(vectors, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(text.encode())} bytes", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
