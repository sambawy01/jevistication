#!/usr/bin/env python3
"""Differential host-parity fuzz: the oracles. Reads cases.jsonl (gen.py), writes pinned.json.

href cases: every value is written into one HTML page as <a href="VALUE">, and Chrome (headless)
decodes it as the browser does (HTML parsing: entity references, attribute rules) and resolves it
with WHATWG URL parsing against the two kinds of base a mail client gives a message:
  https://mail.example.com/inbox/   a web client (special scheme: `https:x` is then relative), and
  x-msg://base.invalid/             Apple Mail's kind (non-special: `https:x` is host x).
"chrome" holds the two hostnames, and "own" the hostname of the href on its own (new URL(v), no
base: null when the href has no scheme of its own). A relative href resolves to the base's own host: "no host of its own".
text cases: linkify-it (Node) and NSDataDetector (macOS, via swift) find the links in each line;
the http(s) ones are canonicalised by Chrome (new URL(u).hostname).

Needs: Google Chrome, node with linkify-it (LINKIFY_IT=<path to the linkify-it package>), swift.
Usage: gen.py | oracle.py > pinned.json      (tools/parity/fuzz/README.md)
"""
import html, json, os, shutil, subprocess, sys, tempfile

CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
LINKIFY = os.environ.get("LINKIFY_IT", "linkify-it")
BASE = "https://mail.example.com/inbox/"
BASE2 = "x-msg://base.invalid/"

cases = [json.loads(l) for l in sys.stdin if l.strip()]
work = tempfile.mkdtemp(prefix="loupe-fuzz-")
texts = [c for c in cases if c["kind"] == "text"]
hrefs = [c for c in cases if c["kind"] == "href"]

# linkify-it
tf = os.path.join(work, "texts.json"); json.dump([c["input"] for c in texts], open(tf, "w"), ensure_ascii=False)
js = """const L=require(%s)();const t=JSON.parse(require('fs').readFileSync(%s,'utf8'));
console.log(JSON.stringify(t.map(s=>(L.match(s)||[]).map(m=>m.url))));""" % (json.dumps(LINKIFY), json.dumps(tf))
lk = json.loads(subprocess.run(["node", "-e", js], capture_output=True, text=True, check=True).stdout)
lk_version = json.loads(subprocess.run(["node", "-e", "console.log(JSON.stringify(require(%s+'/package.json').version))" % json.dumps(LINKIFY)],
                                       capture_output=True, text=True, check=True).stdout)

# NSDataDetector
sw = os.path.join(work, "dd.swift")
open(sw, "w").write("""import Foundation
let d = try! NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
let data = try! Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let texts = try! JSONSerialization.jsonObject(with: data) as! [String]
var out: [[String]] = []
for t in texts { out.append(d.matches(in: t, range: NSRange(t.startIndex..., in: t)).map { $0.url?.absoluteString ?? "" }) }
print(String(data: try! JSONSerialization.data(withJSONObject: out), encoding: .utf8)!)
""")
dd = json.loads(subprocess.run(["swift", sw, tf], capture_output=True, text=True, check=True).stdout)
os_version = subprocess.run(["sw_vers", "-productVersion"], capture_output=True, text=True).stdout.strip()

def web(u):
    return u.lower().startswith(("http://", "https://"))
linked = [sorted(set(u for u in a + b if web(u))) for a, b in zip(lk, dd)]
to_canon = sorted(set(u for l in linked for u in l))

# Chrome: anchors (hrefs) and new URL() (linked text URLs)
anchors = "".join('<a id="h%d" href="%s">x</a>\n' % (i, c["input"]) for i, c in enumerate(hrefs))
script = """function H(v,b){try{return new URL(v,b).hostname}catch(x){return ''}}
var r={a:[],u:{}};for(var i=0;i<%d;i++){var e=document.getElementById('h'+i);var v=e.getAttribute('href');
var o=null;try{var u=new URL(v);o=u.hostname}catch(x){}
r.a.push([H(v,%s),H(v,%s),e.href,o]);}
var U=%s;for(var j=0;j<U.length;j++){try{r.u[U[j]]=new URL(U[j]).hostname}catch(x){r.u[U[j]]=null}}
document.getElementById('o').textContent=JSON.stringify(r);""" % (len(hrefs), json.dumps(BASE), json.dumps(BASE2), json.dumps(to_canon).replace("<", "\\u003c"))
page = os.path.join(work, "page.html")
open(page, "w", encoding="utf-8").write('<!doctype html><html><head><meta charset="utf-8"><base href="%s"></head><body>%s<pre id="o"></pre><script>%s</script></body></html>' % (BASE, anchors, script))
def dump_dom(page):
    """Chrome's --dump-dom of [page]. Chrome sometimes stays up after printing the DOM: read until
    the result is complete, then end that (our own) Chrome process."""
    proc = subprocess.Popen([CHROME, "--headless=new", "--disable-gpu", "--no-first-run", "--user-data-dir=" + os.path.join(work, "prof"),
                             "--dump-dom", "file://" + page], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
    buf = []
    for line in proc.stdout:
        buf.append(line)
        if "</html>" in line:
            break
    proc.terminate()
    try:
        proc.wait(timeout=20)
    except subprocess.TimeoutExpired:
        proc.kill()
    return "".join(buf)

dom = dump_dom(page)
res = json.loads(html.unescape(dom[dom.index('<pre id="o">') + 12: dom.index("</pre>")]))
chrome_version = subprocess.run([CHROME, "--version"], capture_output=True, text=True).stdout.strip()

out = []
for i, c in enumerate(hrefs):
    h1, h2, href, own = res["a"][i]
    out.append({"id": c["id"], "kind": "href", "input": c["input"], "chrome": [h1, h2], "own": own, "href": href})
for c, l in zip(texts, linked):
    out.append({"id": c["id"], "kind": "text", "input": c["input"], "linked": sorted(set(h for h in (res["u"].get(u) for u in l) if h))})
doc = {"format": "loupe-host-parity-fuzz", "version": 1, "base": [BASE, BASE2], "oracles": {"chrome": chrome_version, "linkify-it": lk_version,
       "NSDataDetector": "macOS " + os_version}, "cases": out}
s = '{\n  "format": "loupe-host-parity-fuzz",\n  "version": 1,\n  "base": %s,\n  "oracles": %s,\n  "cases": [\n' % (json.dumps([BASE, BASE2]), json.dumps(doc["oracles"], ensure_ascii=True))
s += ",\n".join("    " + json.dumps(o, ensure_ascii=True) for o in out) + "\n  ]\n}\n"
sys.stdout.write(s)
shutil.rmtree(work, ignore_errors=True)  # the Chrome profile and pages
