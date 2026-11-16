#!/usr/bin/env python3
"""Tiny adb + uiautomator driver for E2E tests (works with Compose: it exposes semantics to accessibility).

usage:
  ui.py dump                       # list visible nodes (text/desc/id/bounds)
  ui.py tap "<text>" [index]       # tap the node whose text or content-desc equals/contains <text>
  ui.py has "<text>"               # exit 0 if visible
  ui.py type "<text>"              # type into the focused field (adb input text)
  ui.py field "<label>" "<value>"  # tap a field by label/hint text, clear it, type value
  ui.py text-of "<prefix>"         # print the first node text starting with <prefix>
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")


def adb(*args, check=True):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=check).stdout


def nodes():
    xml = ""
    for attempt in range(3):
        # Remove the previous dump first: a failed dump leaves the old file, which would describe a stale screen.
        adb("shell", "rm", "-f", "/sdcard/ui.xml", check=False)
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if "<hierarchy" in xml:
            break
        time.sleep(1)
    if "<hierarchy" not in xml:
        # uiautomator needs the screen to be still for a second; a ticking countdown (e.g. a DroidHands
        # approval prompt in the shade) prevents that.
        sys.exit("could not read the screen (uiautomator found no idle moment)")
    root = ET.fromstring(xml[xml.index("<hierarchy"):])
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))
        x1, y1, x2, y2 = map(int, b)
        out.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""), "id": n.get("resource-id", ""),
            "cls": n.get("class", ""), "clickable": n.get("clickable") == "true",
            "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "w": x2 - x1, "h": y2 - y1,
        })
    return out


def find(label, index=0, exact_first=True):
    ns = [n for n in nodes() if n["w"] > 0 and n["h"] > 0]
    exact = [n for n in ns if label in (n["text"], n["desc"])]
    contains = [n for n in ns if label.lower() in (n["text"] + " " + n["desc"]).lower()]
    cands = exact if (exact_first and exact) else contains
    if len(cands) <= index:
        return None
    return cands[index]


def tap(label, index=0, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        n = find(label, index)
        if n:
            adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
            time.sleep(0.8)
            return n
        time.sleep(0.7)
    raise SystemExit(f"not found: {label!r}")


def type_text(value):
    # adb input text: spaces as %s; escape shell specials.
    esc = value.replace("\\", "\\\\").replace(" ", "%s")
    for ch in "&|<>;()$`'\"*?!#~":
        esc = esc.replace(ch, "\\" + ch)
    adb("shell", "input", "text", esc)
    time.sleep(0.5)


def main():
    cmd = sys.argv[1]
    if cmd == "dump":
        for n in nodes():
            if n["text"] or n["desc"] or n["id"]:
                print(f'{n["cls"].split(".")[-1]:<14} text={n["text"]!r} desc={n["desc"]!r} id={n["id"]} click={n["clickable"]} @({n["x"]},{n["y"]})')
    elif cmd == "tap":
        n = tap(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0)
        print(f"tapped {n['text'] or n['desc']!r} @({n['x']},{n['y']})")
    elif cmd == "has":
        sys.exit(0 if find(sys.argv[2], exact_first=False) else 1)
    elif cmd == "type":
        type_text(sys.argv[2])
    elif cmd == "field":
        n = tap(sys.argv[2])
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * 80))
        type_text(sys.argv[3])
        print(f"set {sys.argv[2]!r}")
    elif cmd == "text-of":
        for n in nodes():
            if n["text"].startswith(sys.argv[2]):
                print(n["text"])
                return
        raise SystemExit(f"no text starting with {sys.argv[2]!r}")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
