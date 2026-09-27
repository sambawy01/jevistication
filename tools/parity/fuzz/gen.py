#!/usr/bin/env python3
"""Differential host-parity fuzz: the case generator (deterministic).

Writes cases.jsonl: one {"id", "kind", "input"} per line.
  kind "href": the raw value of an <a href="..."> attribute as it stands in the HTML source (entity
               references and literal tabs/newlines included). The oracle is the browser: Chrome's
               HTMLAnchorElement.hostname with <base href="https://mail.example.com/inbox/">.
  kind "text": a line of plain-text mail. The oracles are linkify-it and NSDataDetector: every
               web host either of them links must be one Loupe judges.
Usage: gen.py [N_HREF] [N_TEXT] > cases.jsonl   (defaults 2400 and 1200; seed fixed)
"""
import json, random, sys

SEED = 20260927
N_HREF = int(sys.argv[1]) if len(sys.argv) > 1 else 2400
N_TEXT = int(sys.argv[2]) if len(sys.argv) > 2 else 1200

# --- href pieces -----------------------------------------------------------------------------------
LEAD = ["", "", "", " ", "\t", "\n", "\r\n", "\x01", "\x1f", "\u00a0", "\ufeff", "\u200b", "&#9;", "&Tab;", "&#x20;",
        "&#1;", "&nbsp;", "&NewLine;", "%20", " \t "]
SCHEME = ["https:", "https:", "https:", "http:", "HTTPS:", "hTtPs:", "ht\ttps:", "ht\ntps:", "h\tttps:", "https\n:", "http\rs:",
          "&#104;ttps:", "&#x68;ttps:", "https&#58;", "https&colon;", "", "", "", "ftp:", "wss:", "file:", "javascript:"]
SLASHES = ["//", "//", "//", "", "/", "\\\\", "/\\", "\\/", "///", "////", "\\", "/\t/", "/\n\\", "&#47;&#47;", "&#92;&#92;",
           "&sol;&sol;", "&bsol;&bsol;", "/&#92;"]
USERINFO = ["", "", "", "", "www.paypal.com@", "paypal.com:x@", "a@b@", "www.paypal.com＠", "www.paypal.com|x@",
            "www.paypal.com%40", "user:[x@", "www.paypal.com&#64;", "www.paypal.com&commat;", "@", ":@", "www.paypal.com\\@",
            "www.paypal.com^@", "www.paypal.com，x@"]
HOST = ["paypa1-secure.xyz", "paypa1-secure.xyz", "www.paypal.com", "paypal.com", "ｐａｙｐａｌ.com", "%70aypal.com",
        "paypal。com", "paypal．com", "paypal｡com", "xn--pypal-4ve.com", "p\u0430ypal.com", "PAYPAL.COM", "paypal.com.",
        "paypal.com%2Eevil.tk", "evil.com\\.paypal.com", "pay\u00adpal.com", "pay\u200dpal.com", "americanexpre\u00df.com",
        "www.example.com", "\u4f8b\u3048.jp", "b\u00fccher.de", "paypal.com%00.evil.com", "paypal.com%40evil.com",
        "evil|com", "[::1]", "192.168.0.1", "", ".", "%2e"]
PORT = ["", "", "", "", ":443", ":8080", ":x", ":"]
PATH = ["/login", "/login", "", "/", "?q=1", "#frag", "\\login", "/a b", "/%2F..%2F", "/@paypal.com"]
TAIL = ["", "", "", " ", "\n", "\t"]

# --- text pieces -----------------------------------------------------------------------------------
T_LEAD = ["", "Log in: ", "Visit ", "\u8bbf\u95ee", "\u8bbf\u95ee ", "\u7f51\u5740\uff1a", "\u0632\u0648\u0631\u0648\u0627 ", "\uff08", "\u300c",
          "\u3010\u5b98\u7f51\u3011", "\u8a73\u7d30\u306f", "(", "<", "\""]
T_PREFIX = ["https://", "https://", "http://", "www.", "HTTPS://", "https://www."]
T_USER = ["", "", "", "", "www.paypal.com|login@", "www.paypal.com\uff0clogin@", "www.paypal.com\uff20", "www.paypal.com^@",
          "www.paypal.com\u060c@", "www.paypal.com\u3001@", "www.paypal.com\uff1a443@", "paypal.com:x|y@", "www.paypal.com\uff5c@"]
T_HOST = ["paypa1-secure.xyz", "www.paypal.com", "paypal.com", "paypal\u3002com.evil.xyz", "paypal\uff0ecom\uff0eevil.xyz",
          "www.paypal.com\uff0eevil\uff0exyz", "www.paypal.com\u3002evil\u3002xyz", "www.example.com", "example.com", "\u4f8b\u3048.jp",
          "paypa1\u3002com", "www.example.co.jp", "ｐａｙｐａｌ.com"]
T_PATH = ["", "", "/login", "/a|b", "/path?x=1|2", "/"]
T_TRAIL = ["", "", " now", "\uff0c\u4e86\u89e3\u66f4\u591a", "\u3002\u8c22\u8c22", "\u3002", "\uff1b\u7535\u8bdd", "\uff09\u3002", "\uff01", "\uff1f",
           "\u3001\u8c22\u8c22", "|Unsubscribe", "\uff5c\u5ba2\u670d", "\u060c \u0634\u0643\u0631\u0627", "\u061f", ".", ",", "\uff0cevil.com/login",
           "\uff0c\u90ae\u7bb1\uff1ainfo@example.com", "\u300d", "\u3011", ")", ">", "\"", "\uff0e", "\uff61", "|evil.tk", "\uff5cevil.tk"]

# Rows the round-5 review named, always included.
PINNED_HREF = ["ht\ntps://paypa1-secure.xyz/login", "h\tttps://paypa1-secure.xyz/login", "https\n://paypa1-secure.xyz/login",
    "https:\n//paypa1-secure.xyz/login", "http\rs://paypa1-secure.xyz/login", "ht\ntps://www.paypal.com@paypa1-secure.xyz/login",
    "\\\\www.paypal.com@paypa1-secure.xyz/login", "\\/www.paypal.com@paypa1-secure.xyz/login", "/\\www.paypal.com@paypa1-secure.xyz/login",
    "\\\\paypa1-secure.xyz/login", "/\\paypa1-secure.xyz/login", "\\/paypa1-secure.xyz/login", "///paypa1-secure.xyz/login",
    "&#92;&#92;paypa1-secure.xyz/login", "/&#92;paypa1-secure.xyz/login", "&#104;ttps://paypa1-secure.xyz/login",
    "&#104;ttps://www.paypal.com@paypa1-secure.xyz/login", "&#x68;ttps://paypa1-secure.xyz/login", "&#47;&#47;paypa1-secure.xyz/login",
    "https&#58;//paypa1-secure.xyz/login", "ht&#10;tps://paypa1-secure.xyz/login", "&Tab;https://paypa1-secure.xyz/login",
    "&NewLine;//paypa1-secure.xyz/login", "&#x20;https://paypa1-secure.xyz/login", "&#1;https://paypa1-secure.xyz/login",
    "&nbsp;https://paypa1-secure.xyz/login", "&#xFEFF;https://paypa1-secure.xyz/login", "/", "?utm=1", "./x.html", "*|UNSUB|*",
    "%%unsubscribe%%", "https://", "https:///", "mailto:x@example.com", "https://paypa1-secure.xyz%00/", "https://paypal.com%40evil.com/login"]
PINNED_TEXT = ["https://www.paypal.com|login@paypa1-secure.xyz/login", "https://www.paypal.com\uff0clogin@paypa1-secure.xyz/login",
    "https://www.paypal.com\uff20paypa1-secure.xyz/login", "https://paypal.com\uff0cevil.com/login", "https://paypal.com:x|y@paypa1-secure.xyz/login",
    "https://www.paypal.com^@paypa1-secure.xyz/login", "www.paypal.com\uff0eevil\uff0exyz/login", "www.paypal.com\u3002evil\u3002xyz/login",
    "\u8bbf\u95ee https://www.example.com\uff0c\u90ae\u7bb1\uff1ainfo@example.com", "\u8bbf\u95eewww.example.com\uff0c\u4e86\u89e3\u66f4\u591a",
    "\u7f51\u5740\uff1ahttps://www.example.com\uff1b\u7535\u8bdd", "\u8be6\u89c1www.example.com\u3002\u8c22\u8c22", "Visit https://example.com|Unsubscribe",
    "www.paypal.com|evil.tk", "www.paypal.com\uff5cevil.tk"]

def main():
    r = random.Random(SEED)
    seen, out = set(), []
    def add(kind, s):
        if (kind, s) in seen or '"' in s:
            return
        seen.add((kind, s))
        out.append({"id": "%s-%04d" % (kind, sum(1 for o in out if o["kind"] == kind)), "kind": kind, "input": s})
    for s in PINNED_HREF:
        add("href", s)
    for s in PINNED_TEXT:
        add("text", s)
    while sum(1 for o in out if o["kind"] == "href") < N_HREF:
        add("href", r.choice(LEAD) + r.choice(SCHEME) + r.choice(SLASHES) + r.choice(USERINFO) + r.choice(HOST) + r.choice(PORT) + r.choice(PATH) + r.choice(TAIL))
    while sum(1 for o in out if o["kind"] == "text") < N_TEXT:
        add("text", r.choice(T_LEAD) + r.choice(T_PREFIX) + r.choice(T_USER) + r.choice(T_HOST) + r.choice(T_PATH) + r.choice(T_TRAIL))
    for o in out:
        print(json.dumps(o, ensure_ascii=False))

if __name__ == "__main__":
    main()
