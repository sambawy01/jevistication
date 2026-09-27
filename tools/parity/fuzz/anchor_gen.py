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
        "<div><svg><circle r=1>", "<table><tr><td><svg>", "<svg><a>", "<math><ms>",
        # fix loop 8: mglyph/malignmark stay MathML inside a text integration point; annotation-xml's encoding
        "<math><mi><mglyph>", "<math><mtext><malignmark>", "<math><mo><mglyph><svg>", "<math><mn><malignmark><p>",
        "<math><annotation-xml encoding='TEXT/HTML'>", "<math><annotation-xml encoding='application/xhtml+xml'>",
        "<math><annotation-xml encoding=' text/html '>", "<math><annotation-xml encoding='image/svg+xml'>",
        "<math><annotation-xml encoding='text/html'><svg>", "<math><annotation-xml><svg>", "<math><annotation-xml encoding=text&#47;html>"]
BREAK = ["<p>", "<div>", "<br>", "<b>", "<span>", "<img src=x>", "<table>", "<font color=red>", "<font>", "</p>", "</br>", "</div>",
         "</svg>", "</math>", "</foreignObject>", "</desc>", "</mtext>", "<ul><li>", "<h1>", "<pre>", "<i>", "<em>", "<body>", "",
         "<mglyph>", "<malignmark>", "</annotation-xml>", "</mi>"]
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

# Fix loop 9 (round 9): an OPEN axis and an END-TAG axis around the foreign wrapper, then a raw-text
# hider: <OPEN><svg|math…></END><style|textarea|plaintext…><p><a href=E>. Chrome often stays in foreign
# content (the end tag is ignored), where <style> is not raw text.
OPEN = ["<html>", "<body>", "<head>", "<li><ul>", "<li><ol>", "<ul><li>", "<form>", "<td>", "<th>", "<tr>", "<caption>", "<colgroup>",
        "<div>", "<p>", "<table>", "<noscript>", "<tbody>", "<button>", "<span>", "<b>", "<a href='https://good.example/'>", "<table><tr><td>", ""]
ENDT = ["</html>", "</body>", "</head>", "</li>", "</ul>", "</form>", "</td>", "</th>", "</tr>", "</caption>", "</colgroup>", "</div>", "</p>",
        "</table>", "</noscript>", "</tbody>", "</button>", "</span>", "</b>", "</a>", "</svg>", "</math>", "</br>", "</foreignObject>", "</mi>", ""]
FOREIGN = ["<svg>", "<math>", "<svg><foreignObject>", "<svg><foreignObject><svg>", "<math><mi>", "<math><mi><svg>", "<math><annotation-xml encoding=text/html><svg>",
           "<svg><desc>", "<svg><g>", "<math><mtext><svg>"]
RAWHIDE = ["<style>", "<textarea>", "<plaintext>", "<title>", "<xmp>", "<script>", "<noembed>", "<noframes>", "<iframe>", "<noscript>", ""]
N_AXIS = 1000
# Fix loop 10 (round 10): a comment opener inside raw text, after a foreign wrapper and an end tag:
# <OPEN><svg…></END><style|textarea><!--</style|/textarea><p><a href=E>. Chrome keeps the `<!--` as text
# when the raw text is HTML; a reading that took it for a comment lost the link.
N_COMMENT = 500

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
    # the axis documents: a separate generator, so the first N documents stay as they were
    r2 = random.Random(SEED + 9)
    n0 = len(out)
    while len(out) < n0 + N_AXIS:
        html = (r2.choice(BASE[:3]) + r2.choice(BENIGN) + r2.choice(OPEN) + r2.choice(FOREIGN) + r2.choice(ENDT) + r2.choice(ENDT[-8:]) +
                r2.choice(RAWHIDE) + r2.choice(["<p>", ""]) + r2.choice(EVIL))
        if html in seen:
            continue
        seen.add(html)
        out.append({"id": "axis-%04d" % (len(out) - n0), "html": html})
    r3 = random.Random(SEED + 10)
    n1 = len(out)
    while len(out) < n1 + N_COMMENT:
        hider = r3.choice(["style", "textarea", "title", "xmp", "script", "noembed", "iframe"])
        html = (r3.choice(OPEN) + r3.choice(FOREIGN) + r3.choice(ENDT) + "<" + hider + ">" + r3.choice(["<!--", "<!-- ", "<!--<a href='https://good.example/'>", "<![CDATA["]) +
                r3.choice(["</" + hider + ">", ""]) + r3.choice(["<p>", ""]) + r3.choice(EVIL) + r3.choice(["", " -->", "</" + hider + ">"]))
        if html in seen:
            continue
        seen.add(html)
        out.append({"id": "comment-%04d" % (len(out) - n1), "html": html})
    for o in out:
        print(json.dumps(o, ensure_ascii=False))

if __name__ == "__main__":
    main()
