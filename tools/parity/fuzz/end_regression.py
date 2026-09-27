#!/usr/bin/env python3
"""The end-tag regression set (fix loop 9): from round 9's 71,429 generated documents
(r9-work/end.jsonl, Chrome's hosts in end.chrome), every hand-written case ("x:" labels), every
document 77bb9c5 missed (blockers-77bb9c5.txt) and a deterministic sample of the rest, pinned with
Chrome's hosts in anchor-parity format. Usage: end_regression.py R9_WORK_DIR > end-pinned.json"""
import json, random, sys
d = sys.argv[1].rstrip("/")
cases = [json.loads(l) for l in open(d + "/end.jsonl") if l.strip()]
chrome = [l.rstrip("\n").split("\t", 1)[1] if "\t" in l else "" for l in open(d + "/end.chrome")]
assert len(cases) == len(chrome)
blockers = set()
for l in open(d + "/blockers-77bb9c5.txt"):
    p = l.split(" ")
    if len(p) > 1 and l.startswith("BLOCKER"):
        blockers.add(l.split(" chrome=")[0].split(" ", 1)[1])
pick = [i for i, c in enumerate(cases) if c["label"].startswith("x:") or c["label"] in blockers]
rest = [i for i in range(len(cases)) if i not in set(pick)]
random.Random(20260927).shuffle(rest)
pick = sorted(set(pick) | set(rest[:300]))
out = []
seen = set()
for i in pick:
    if cases[i]["html"] in seen:
        continue
    seen.add(cases[i]["html"])
    out.append({"id": "end-%05d" % i, "label": cases[i]["label"], "html": cases[i]["html"], "hosts": sorted(filter(None, chrome[i].split(",")))})
s = '{\n  "format": "loupe-anchor-parity-fuzz",\n  "version": 1,\n  "oracle": "Chrome DOMParser (round-9 review, end.chrome)",\n  "cases": [\n'
s += ",\n".join("    " + json.dumps(o, ensure_ascii=True) for o in out) + "\n  ]\n}\n"
sys.stdout.write(s)
print(len(out), "cases,", sum(1 for o in out if o["label"].startswith("x:")), "hand-written,", len(blockers), "blocker labels", file=sys.stderr)
