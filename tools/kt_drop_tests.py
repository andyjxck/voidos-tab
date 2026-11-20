#!/usr/bin/env python3
"""Remove whole `@Test fun name()` blocks (and the KDoc just above them) from a Kotlin test file.

Usage: kt_drop_tests.py File.kt name1 name2 ...
"""
import re, sys


def drop(src: str, name: str) -> str:
    m = re.search(r"\n([ \t]*/\*\*(?:(?!\*/).)*\*/\s*\n)?([ \t]*)@Test fun " + re.escape(name) + r"\(\)", src, re.S)
    if not m:
        raise SystemExit(f"no test {name}")
    start = m.start()
    i = src.index("{", m.end())
    depth = 0
    while True:
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    end = i + 1
    # one-liners `@Test fun x() { ... }` end on the same line; eat the newline too
    return src[:start] + src[end:]


if __name__ == "__main__":
    path, names = sys.argv[1], sys.argv[2:]
    s = open(path).read()
    for n in names:
        s = drop(s, n)
    open(path, "w").write(s)
