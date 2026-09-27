#!/usr/bin/env python3
"""Anchor-parity fuzz: the oracle. Reads anchor_gen.py's cases, writes anchor-pinned.json.

Chrome (headless) parses each document with DOMParser as text/html, exactly as a browser parses a mail
body, and lists the links in the document it built that a reader can follow: HTML <a> and <area> with href, an SVG <a> with href or
xlink:href (an element merely named "a" or "area" in MathML, or "area" in SVG, is not a link), and an
HTML <meta http-equiv=refresh> URL, resolved against the document's base (its <base href>).
Links inside <template> content are not in the document and are not listed; a relative link with no
web base has no host. "hosts" is the set of web hosts those links open: Loupe must judge every one.
Usage: python3 anchor_gen.py | python3 anchor_oracle.py > anchor-pinned.json
"""
import html, json, os, shutil, subprocess, sys, tempfile

CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
cases = [json.loads(l) for l in sys.stdin if l.strip()]
work = tempfile.mkdtemp(prefix="loupe-anchor-fuzz-")
try:
    js = r"""var D=%s;var X='http://www.w3.org/1999/xlink';var out=[];
for(var i=0;i<D.length;i++){var d=new DOMParser().parseFromString(D[i],'text/html');var hosts={};
 var H='http://www.w3.org/1999/xhtml',S='http://www.w3.org/2000/svg';
 d.querySelectorAll('a,area,meta').forEach(function(e){var t=e.localName;var raw=null;var ns=e.namespaceURI;
  if(!((ns==H)||(ns==S&&t=='a')))return;
  if(t=='meta'){var he=(e.getAttribute('http-equiv')||'').toLowerCase();if(he!='refresh')return;
   var c=e.getAttribute('content')||'';var m=c.match(/url\s*=\s*['"]?([^'"]*)/i);if(!m)return;raw=m[1].trim();}
  else{raw=e.getAttribute('href');if(raw===null&&t=='a'&&e.namespaceURI=='http://www.w3.org/2000/svg')raw=e.getAttributeNS(X,'href');}
  if(raw===null)return;var u;try{u=new URL(raw,d.baseURI)}catch(x){return}
  if(u.protocol=='http:'||u.protocol=='https:'){if(u.hostname)hosts[u.hostname]=1}});
 out.push(Object.keys(hosts).sort());}
document.getElementById('o').textContent=JSON.stringify(out);""" % json.dumps([c["html"] for c in cases]).replace("</", "<\\/")
    page = os.path.join(work, "p.html")
    open(page, "w", encoding="utf-8").write('<!doctype html><meta charset="utf-8"><pre id="o"></pre><script>%s</script>' % js)
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
    dom = "".join(buf)
    res = json.loads(html.unescape(dom[dom.index('<pre id="o">') + 12: dom.index("</pre>")]))
    chrome = subprocess.run([CHROME, "--version"], capture_output=True, text=True).stdout.strip()
finally:
    shutil.rmtree(work, ignore_errors=True)
s = '{\n  "format": "loupe-anchor-parity-fuzz",\n  "version": 1,\n  "oracle": %s,\n  "cases": [\n' % json.dumps(chrome)
s += ",\n".join("    " + json.dumps({"id": c["id"], "html": c["html"], "hosts": h}, ensure_ascii=True) for c, h in zip(cases, res)) + "\n  ]\n}\n"
sys.stdout.write(s)
