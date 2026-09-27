#!/usr/bin/env python3
"""The comment-in-raw-text regression set (fix loop 10): round 10's documents where a `<!--` inside
<style>/<textarea> hid a link from reading (b) when the tree-aware reading misread the tree
(r10-work/cmx.miss.jsonl, 124), plus a deterministic sample of 300 of the rest of cmx.jsonl, pinned
with Chrome's hosts (cmx.chrome). Usage: comment_regression.py R10_WORK_DIR > comment-pinned.json"""
import json, random, sys
d = sys.argv[1].rstrip("/")
cases = [json.loads(l) for l in open(d + "/cmx.jsonl") if l.strip()]
chrome = [l.rstrip("\n").split("\t", 1)[1] if "\t" in l else "" for l in open(d + "/cmx.chrome")]
assert len(cases) == len(chrome)
miss = {json.loads(l)["html"] for l in open(d + "/cmx.miss.jsonl") if l.strip()}
pick = [i for i, c in enumerate(cases) if c["html"] in miss]
rest = [i for i in range(len(cases)) if cases[i]["html"] not in miss]
random.Random(20260928).shuffle(rest)
out, seen = [], set()
for i in sorted(set(pick) | set(rest[:300])):
    if cases[i]["html"] in seen:
        continue
    seen.add(cases[i]["html"])
    out.append({"id": "cmx-%05d" % i, "label": cases[i]["label"], "html": cases[i]["html"], "hosts": sorted(filter(None, chrome[i].split(",")))})
s = '{\n  "format": "loupe-anchor-parity-fuzz",\n  "version": 1,\n  "oracle": "Chrome DOMParser (round-10 review, cmx.chrome)",\n  "cases": [\n'
s += ",\n".join("    " + json.dumps(o, ensure_ascii=True) for o in out) + "\n  ]\n}\n"
sys.stdout.write(s)
print(len(out), "cases,", len(pick), "round-10 misses", file=sys.stderr)
