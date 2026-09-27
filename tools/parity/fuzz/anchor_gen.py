#!/usr/bin/env python3
"""Anchor-parity fuzz: the case generator (deterministic). Writes one {"id", "html"} per line.

Each document mixes what moves an anchor in or out of the document a browser builds: SVG and MathML,
breakout tags, integration points, <template>, CDATA, comments, raw text, open quotes, malformed tags
and <base>, then an attacker's link (absolute, relative under a <base>, or an SVG link), sometimes
after a harmless PayPal link. Usage: anchor_gen.py [N] > anchor-cases.jsonl (default 2000)."""
import json, random, sys

SEED = 20260927
N = int(sys.argv[1]) if len(sys.argv) > 1 else 2000

WRAP = ["<svg>", "<math>", "<svg><foreignObject>", "<svg><desc>", "<svg><title>", "<math><mtext>", "<math><mi>", "<math><mo>",
        "<math><annotation-xml encoding='text/html'>", "<math><annotation-xml>", "<math><svg>", "<svg><math>", "<svg><g><g>",
        "<div><svg><circle r=1>", "<table><tr><td><svg>", "<svg><a>", "<math><ms>"]
BREAK = ["<p>", "<div>", "<br>", "<b>", "<span>", "<img src=x>", "<table>", "<font color=red>", "<font>", "</p>", "</br>", "</div>",
         "</svg>", "</math>", "</foreignObject>", "</desc>", "</mtext>", "<ul><li>", "<h1>", "<pre>", "<i>", "<em>", "<body>", ""]
HIDE = ["<![CDATA[ ", "<![CDATA[ > ", "<template>", "<!-- ", "<!-- > ", "<script>", "<style>", "<textarea>", "<title>", "<xmp>",
        "<noscript>", "<iframe>", "<noembed>", "<noframes>", "<plaintext>", "<img alt='", "<div title=\"", "<!", "<?x ", "</ ", "<!-->",
        "<select>", "<object>", "<svg><style>", "<math><style>", "<svg><script>", ""]
CLOSE = [" ]]>", "</template>", " -->", "</script>", "</style>", "</textarea>", "</title>", "'>", "\">", ">", "</xmp>", "</noscript>",
         "</iframe>", "</svg>", "</math>", "", ""]
BASE = ["", "", "", '<base href="https://paypa1-secure.xyz/">', '<svg><base href="https://www.paypal.com/"></svg>',
        '<base href="https://www.paypal.com/">', '<math><base href="https://www.paypal.com/"></math>', '<template><base href="https://paypa1-secure.xyz/"></template>']
EVIL = ['<a href="https://paypa1-secure.xyz/login">Sign in to PayPal</a>', '<a href="/login">Sign in to PayPal</a>',
        '<svg><a xlink:href="https://paypa1-secure.xyz/login"><text>PayPal</text></a></svg>', '<a href="https://paypa1-secure.xyz/login">Pay<!-- -->Pal</a>',
        '<area href="https://paypa1-secure.xyz/login" alt="PayPal">', '<meta http-equiv="refresh" content="0;url=https://paypa1-secure.xyz/login">',
        '<a href=https://paypa1-secure.xyz/login>PayPal</a>']
BENIGN = ['<a href="https://www.paypal.com/">PayPal</a>', ""]

def main():
    r = random.Random(SEED)
    seen, out = set(), []
    while len(out) < N:
        parts = [r.choice(BASE), r.choice(BENIGN)]
        for _ in range(r.randint(1, 4)):
            parts.append(r.choice([r.choice(WRAP), r.choice(BREAK), r.choice(HIDE), r.choice(CLOSE)]))
        parts.append(r.choice(EVIL))
        if r.random() < 0.5:
            parts.append(r.choice(CLOSE))
        html = "".join(parts)
        if html in seen:
            continue
        seen.add(html)
        out.append({"id": "doc-%04d" % len(out), "html": html})
    for o in out:
        print(json.dumps(o, ensure_ascii=False))

if __name__ == "__main__":
    main()
