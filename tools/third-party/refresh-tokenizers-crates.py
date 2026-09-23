#!/usr/bin/env python3
"""Refreshes third-party/tokenizers-crates.tsv and the crate licence texts it points at.

Authoring-time only, and the only step of the notices pipeline that uses the network. The Gradle
task :loupe-desktop:generateThirdPartyNotices reads what this writes and never goes online.

Source of truth: DJL's own Cargo.lock for the JNI library shipped inside
ai.djl.huggingface:tokenizers, at the git tag matching the Maven version. The crate set is the
normal (linked, not build-only) dependency closure of djl_tokenizer for the four targets DJL ships
(linux x86_64/aarch64, macOS aarch64, Windows x86_64 GNU), from `cargo tree --locked`. Licence
expressions come from each crate's Cargo.toml via `cargo metadata`; licence texts are the
LICENSE*/COPYING*/NOTICE* files in the crate source (and its git workspace root for git crates, and
vendored C sources one level down, e.g. onig_sys/oniguruma/COPYING).

The Kotlin test ThirdPartyNoticesTest cross-checks the result against the jar itself: every crate
whose source path is embedded in the four native libraries must be listed here at that version.

Usage: tools/third-party/refresh-tokenizers-crates.py [DJL_VERSION]   (needs cargo and network)
"""
import hashlib, json, os, re, subprocess, sys, tempfile, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_TSV = os.path.join(ROOT, "third-party", "tokenizers-crates.tsv")
TEXTS = os.path.join(ROOT, "third-party", "texts")
TARGETS = ["x86_64-unknown-linux-gnu", "aarch64-unknown-linux-gnu", "aarch64-apple-darwin",
           "x86_64-pc-windows-gnu"]
NAMES = ("LICEN", "COPYING", "NOTICE", "COPYRIGHT", "UNLICENSE")


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def norm(text):
    return text.replace("\r\n", "\n").replace("\r", "\n").rstrip() + "\n"


def store(text):
    text = norm(text)
    h = hashlib.sha256(text.encode()).hexdigest()[:16]
    with open(os.path.join(TEXTS, h + ".txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    return h


def licence_files(d, git_root):
    found = []
    for base, depth in ((d, 0),):
        for name in sorted(os.listdir(base)):
            p = os.path.join(base, name)
            if os.path.isfile(p) and name.upper().startswith(NAMES):
                found.append(p)
            elif os.path.isdir(p) and name not in ("tests", "test", "benches", "examples", "src"):
                for sub in sorted(os.listdir(p)):
                    q = os.path.join(p, sub)
                    if os.path.isfile(q) and sub.upper().startswith(NAMES):
                        found.append(q)
    if not found and git_root:
        found = [os.path.join(git_root, n) for n in sorted(os.listdir(git_root))
                 if os.path.isfile(os.path.join(git_root, n)) and n.upper().startswith(NAMES)]
    return found


def main():
    djl = sys.argv[1] if len(sys.argv) > 1 else "0.38.0"
    base = f"https://raw.githubusercontent.com/deepjavalibrary/djl/v{djl}/extensions/tokenizers/rust"
    os.makedirs(TEXTS, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for n in ("Cargo.toml", "Cargo.lock"):
            open(os.path.join(tmp, n), "wb").write(fetch(f"{base}/{n}"))
        os.makedirs(os.path.join(tmp, "src"))
        open(os.path.join(tmp, "src", "lib.rs"), "w").close()
        want = set()
        for t in TARGETS:
            out = subprocess.run(["cargo", "tree", "--locked", "-e", "normal", "--target", t,
                                  "--prefix", "none", "-f", "{p}"], cwd=tmp, check=True,
                                 capture_output=True, text=True).stdout
            for line in out.splitlines():
                name, ver = line.split(" ")[:2]
                want.add((name, ver.lstrip("v")))
        meta = json.loads(subprocess.run(["cargo", "metadata", "--locked", "--format-version", "1"],
                                         cwd=tmp, check=True, capture_output=True, text=True).stdout)
    rows = []
    for p in meta["packages"]:
        key = (p["name"], p["version"])
        if key not in want or p["name"] == "djl_tokenizer":
            continue  # djl_tokenizer is DJL's own code, attributed with the Maven artifact
        d = os.path.dirname(p["manifest_path"])
        src = p.get("source") or ""
        git_root = None
        if src.startswith("git+"):
            m = re.match(r"(.*/git/checkouts/[^/]+/[^/]+)", d)
            git_root = m.group(1) if m else None
            origin = src.split("?")[0][4:] + "@" + src.split("#")[-1][:12]
        else:
            origin = "crates.io"
        hashes = []
        for f in licence_files(d, git_root):
            with open(f, encoding="utf-8", errors="replace") as fh:
                hashes.append(store(fh.read()))
        rows.append((p["name"], p["version"], p["license"] or "", origin,
                     ",".join(dict.fromkeys(hashes)) or "-"))
    rows.sort()
    with open(OUT_TSV, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Rust crates linked into libtokenizers inside ai.djl.huggingface:tokenizers:{djl}\n")
        f.write(f"# Generated by tools/third-party/refresh-tokenizers-crates.py from DJL v{djl}'s\n")
        f.write("# extensions/tokenizers/rust/Cargo.lock (normal deps, targets: " + ", ".join(TARGETS) + ").\n")
        f.write("# Columns: crate, version, licence (Cargo.toml), origin, licence text ids in third-party/texts\n")
        f.write("# ('-' = the crate ships no licence file; the generator uses the canonical SPDX text).\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    print(f"{len(rows)} crates -> {OUT_TSV}")


if __name__ == "__main__":
    main()
