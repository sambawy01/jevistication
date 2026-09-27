# Differential host-parity fuzz

Loupe judges links by their host. This fuzz checks that the host Loupe judges is the one a browser
opens, and that every host a linkifier links in plain text is one Loupe judges, against the real
programs rather than our reading of the specs.

- `gen.py` writes the cases (deterministic, seed fixed): about 2,400 `href` values and 1,200 lines
  of text. They mix scheme spellings and case, tabs, newlines, C0 controls, no-break spaces, BOM and
  ZWSP at every position, slashes and backslashes, userinfo with `@`, `＠` and stop characters, ports,
  percent-encoding, full-width and ideographic dots, CJK and Arabic punctuation, HTML character
  references, and brand, attacker and legitimate hosts. The rows the round-5 review named are
  always included.
- `oracle.py` asks the oracles and writes `pinned.json`:
  - hrefs: Chrome (headless) parses each value as HTML (`<a href="...">`, so character references
    and attribute rules are the browser's), then resolves it with the WHATWG URL parser against
    both kinds of base a mail client gives a message:
    - a web client's `https://mail.example.com/inbox/`: special, so `https:x` is relative;
    - Apple Mail's non-special `x-msg://base.invalid/`: `https:x` is host x.
  - text: linkify-it and NSDataDetector find the links, and Chrome canonicalises their hosts.
- `pinned.json` is committed. `HostParityFuzzTest` (loupe-kit commonTest: JVM, Android unit tests,
  iOS simulator) reads it and runs `HostParityFuzz`:
  - an href's judged host must be one of Chrome's two, or Loupe must flag the message (caution or
    worse);
  - every linked text host must be one Loupe judges (or share its registrable domain: linkify-it
    glues CJK text before `www.` into the name), or Loupe must flag the line.
  - Loupe judging a host where Chrome opens none is counted as *over-judged* and allowed. That
    happens with scheme-less hrefs read as http:// and a URL behind a no-break space: stricter than
    the browser, never hiding a host the browser opens.

Regenerate (macOS: Chrome, node with linkify-it, swift):

    cd tools/parity/fuzz
    python3 gen.py | LINKIFY_IT=/path/to/node_modules/linkify-it python3 oracle.py > pinned.json

`pinned.json` records the oracle versions. Review the diff before committing a new pin.
