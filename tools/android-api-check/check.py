#!/usr/bin/env python3
"""Checks the compiled Android classes of Loupe against the Android API database at minSdk.

Why: the shared JVM actuals live in `jvmCommonMain`, compiled for Android too, and AGP's lint does
not analyse that source set (verified at A0: a planted `Path.of`, API 34, passed lint). Host-JVM unit
tests cannot see a missing Android API either. This reads every class file the Android build
produced for our own modules and looks up each JDK/Android method and field they reference in the
SDK's `api-versions.xml`, walking superclasses and interfaces.

Each method and field reference is looked up on its owner; each class reference (CONSTANT_Class:
`is`/`as` checks, catch clauses, class literals, `new`, supertypes) on the class itself.

It reports:
  NEWAPI   a reference whose API level is above minSdk (a crash on older phones), and
  MISSING  a reference to a java.*/javax.*/android.* class or member Android does not have at all.

Usage: tools/android-api-check/check.py [--min-sdk 29] [--sdk-platform 36]
Needs ANDROID_HOME and a prior Android build (e.g. ./gradlew :android-app:assembleDebug).
Exit status 1 when anything is reported.
"""
import argparse
import os
import pathlib
import re
import struct
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
MODULES = ["engine", "templates", "persistence", "sources-common", "game", "backend-laya-common", "loupe-kit", "android-app"]
CHECKED_PREFIXES = ("java/", "javax/", "android/")
# invokedynamic bootstraps (Java 8+ lambdas, Java 9+ string concatenation): D8 desugars them away.
DESUGARED = {"java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory"}
# Classes whose public methods partly live in a hidden superclass the database omits
# (java.lang.AbstractStringBuilder: substring, codePointAt, ...), present since API 1.
HIDDEN_SUPER = {"java/lang/StringBuilder", "java/lang/StringBuffer"}


def load_api(path):
    """{class: (since, {member: since}, [supertypes])} from api-versions.xml."""
    api = {}
    for cls in ET.parse(path).getroot().iter("class"):
        since = int(cls.get("since", "1"))
        members = {}
        for m in cls:
            if m.tag in ("method", "field") and m.get("removed") is None:
                members[m.get("name")] = int(m.get("since", str(since)))
        supers = [e.get("name") for e in cls if e.tag in ("extends", "implements")]
        api[cls.get("name")] = (since, members, supers)
    return api


def class_name(name):
    """The class a CONSTANT_Class name denotes: arrays (`[[Ljava/time/Instant;`) name their element
    type; an array of a primitive (`[I`) names none."""
    stripped = name.lstrip("[")
    if stripped == name:
        return name
    return stripped[1:-1] if stripped.startswith("L") and stripped.endswith(";") else None


def refs_in_class(data):
    """(kind, owner, name, descriptor) for each Methodref/InterfaceMethodref/Fieldref in a class
    file, and ("class", name, None, None) for each CONSTANT_Class: the classes of type checks,
    casts, catch clauses, class literals, `new` and the class's own supertypes, which reference no
    member and would otherwise go unchecked."""
    count = struct.unpack(">H", data[8:10])[0]
    pool = [None] * count
    i, idx = 10, 1
    while idx < count:
        tag = data[i]
        if tag == 1:  # Utf8
            length = struct.unpack(">H", data[i + 1:i + 3])[0]
            pool[idx] = ("utf8", data[i + 3:i + 3 + length].decode("utf-8", "replace"))
            i += 3 + length
        elif tag in (3, 4):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        elif tag == 7:
            pool[idx] = ("class", struct.unpack(">H", data[i + 1:i + 3])[0])
            i += 3
        elif tag in (8, 16, 19, 20):
            i += 3
        elif tag in (9, 10, 11):
            a, b = struct.unpack(">HH", data[i + 1:i + 5])
            pool[idx] = ({9: "field", 10: "method", 11: "method"}[tag], a, b)
            i += 5
        elif tag == 12:
            pool[idx] = ("nat",) + struct.unpack(">HH", data[i + 1:i + 5])
            i += 5
        elif tag == 15:
            i += 4
        elif tag in (17, 18):
            i += 5
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        idx += 1
    out = []
    for entry in pool:
        if entry and entry[0] in ("field", "method"):
            owner = pool[pool[entry[1]][1]][1]
            nat = pool[entry[2]]
            out.append((entry[0], owner, pool[nat[1]][1], pool[nat[2]][1]))
        elif entry and entry[0] == "class":
            name = class_name(pool[entry[1]][1])
            if name is not None:
                out.append(("class", name, None, None))
    return out


def lookup(api, owner, key, seen=None):
    """The API level of member [key] on [owner] or a supertype; None when it is not there."""
    seen = seen or set()
    if owner in seen or owner not in api:
        return None
    seen.add(owner)
    since, members, supers = api[owner]
    if key in members:
        return max(since, members[key])
    for s in supers:
        found = lookup(api, s, key, seen)
        if found is not None:
            return found
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--min-sdk", type=int, default=29)
    ap.add_argument("--sdk-platform", default="36")
    args = ap.parse_args()
    sdk = os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]
    db = pathlib.Path(sdk) / "platforms" / f"android-{args.sdk_platform}" / "data" / "api-versions.xml"
    api = load_api(db)
    problems, classes = set(), 0
    for module in MODULES:
        base = ROOT / module / "build" / "tmp" / "kotlin-classes" / "debug"
        if not base.is_dir():
            print(f"missing {base.relative_to(ROOT)}: build the Android variant first", file=sys.stderr)
            return 2
        for path in base.rglob("*.class"):
            classes += 1
            for kind, owner, name, desc in refs_in_class(path.read_bytes()):
                if owner.startswith("[") or not owner.startswith(CHECKED_PREFIXES) or owner in DESUGARED:
                    continue
                where = f"{path.relative_to(ROOT)}"
                if owner not in api:
                    problems.add(f"MISSING class {owner} ({where})")
                    continue
                if kind == "class":
                    if api[owner][0] > args.min_sdk:
                        problems.add(f"NEWAPI {api[owner][0]} class {owner} ({where})")
                    continue
                key = name if kind == "field" else name + desc
                level = lookup(api, owner, key)
                if level is None and owner in HIDDEN_SUPER:
                    continue
                if level is None:
                    problems.add(f"MISSING {owner}.{key} ({where})")
                elif level > args.min_sdk:
                    problems.add(f"NEWAPI {level} {owner}.{key} ({where})")
    for p in sorted(problems):
        print(p)
    print(f"android api check: {classes} classes in {len(MODULES)} modules, {len(problems)} problems (minSdk {args.min_sdk})")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
