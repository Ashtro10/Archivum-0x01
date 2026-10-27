#!/usr/bin/env python3
"""Compiles every regex literal in the Kotlin sources with ICU, the regex engine Android uses.

Desktop Java (and therefore the JVM/Robolectric tests) accepts some patterns that ICU rejects, for example an
unescaped '}' as in "\\{owner}". Such a pattern crashes the app on a device with PatternSyntaxException, so this
check runs ICU itself via the system libicu (ctypes, no build step).

usage: check_icu_regex.py [source dirs...]   (default: core/src/main app/src/main)
"""
import ctypes
import ctypes.util
import glob
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
dirs = sys.argv[1:] or ["core/src/main/kotlin", "app/src/main/kotlin"]


def load_icu():
    for name in ["icui18n", "icuin"]:
        path = ctypes.util.find_library(name)
        if path:
            lib = ctypes.CDLL(path)
            break
    else:
        cands = sorted(glob.glob("/usr/lib/*/libicui18n.so.*")) + sorted(glob.glob("/usr/lib/libicui18n.so.*"))
        if not cands:
            sys.exit("libicui18n not found (install libicu)")
        lib = ctypes.CDLL(cands[0])
    m = re.search(r"\.so\.(\d+)", lib._name)
    for suffix in ([f"_{m.group(1)}"] if m else []) + [""]:  # ICU exports version-suffixed symbols, e.g. uregex_open_74
        try:
            return getattr(lib, "uregex_open" + suffix), getattr(lib, "uregex_close" + suffix)
        except AttributeError:
            continue
    sys.exit("uregex_open not found in " + lib._name)


uregex_open, uregex_close = load_icu()


class UParseError(ctypes.Structure):
    _fields_ = [("line", ctypes.c_int32), ("offset", ctypes.c_int32),
                ("preContext", ctypes.c_uint16 * 16), ("postContext", ctypes.c_uint16 * 16)]


uregex_open.restype = ctypes.c_void_p
uregex_open.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32, ctypes.POINTER(UParseError), ctypes.POINTER(ctypes.c_int)]
uregex_close.argtypes = [ctypes.c_void_p]


def icu_compile(pattern: str):
    data = pattern.encode("utf-16-le")
    buf = ctypes.create_string_buffer(data, len(data) + 2)
    pe, status = UParseError(), ctypes.c_int(0)
    h = uregex_open(buf, len(data) // 2, 0, ctypes.byref(pe), ctypes.byref(status))
    if h:
        uregex_close(h)
    return status.value, pe.offset


# Kotlin string literal (plain or raw) directly passed to Regex(...), r(...) in IntentRouter, .toRegex(), Pattern.compile(...)
LIT = r'"((?:[^"\\]|\\.)*)"'
CALLS = re.compile(r'(?:\bRegex\(|\br\(|Pattern\.compile\()\s*(?:"""(.*?)"""|' + LIT + r')|' + LIT + r'\.toRegex\(', re.S)


MORE = re.compile(r'\s*\+\s*' + LIT)


def unescape(s: str) -> str:
    s = re.sub(r"\$\{[^}]*\}", "x", s)  # string templates -> literal text (Regex.escape output is \Q..\E, valid in ICU)
    return re.sub(r'\\(.)', lambda m: {"n": "\n", "t": "\t", "r": "\r", "b": "\b"}.get(m.group(1), m.group(1)), s)


failures, count = [], 0
for d in dirs:
    for f in sorted((root / d).rglob("*.kt")):
        if "com/k2fsa" in str(f):
            continue
        text = f.read_text()
        for m in CALLS.finditer(text):
            raw = m.group(1)
            pattern = raw if raw is not None else unescape(m.group(2) if m.group(2) is not None else m.group(3))
            if raw is None and m.group(2) is not None:  # a pattern split over lines: "..." + "..." + ...
                pos = m.end()
                while (more := MORE.match(text, pos)):
                    pattern += unescape(more.group(1)); pos = more.end()
            count += 1
            status, offset = icu_compile(pattern)
            if status > 0:  # U_ZERO_ERROR = 0, warnings < 0
                line = text.count("\n", 0, m.start()) + 1
                failures.append(f"{f.relative_to(root)}:{line}: ICU error {status} at offset {offset}: {pattern!r}")

print(f"ICU regex check: {count} patterns compiled, {len(failures)} rejected")
for x in failures:
    print("  " + x)
sys.exit(1 if failures else 0)
