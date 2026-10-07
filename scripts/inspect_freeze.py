#!/usr/bin/env python3
"""Emit a compact report for the original floating Freeze implementation.

This is intentionally read-only. It maps the legacy touch/freeze path and any
existing input-injection primitives before the hold-to-freeze analog overlay is
wired to them.
"""
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
smali_roots = [p for p in root.iterdir() if p.is_dir() and p.name.startswith("smali")]
all_files = []
for base in smali_roots:
    all_files.extend(base.rglob("*.smali"))

KEYWORDS = re.compile(r"freeze|freese|frozen|ice", re.I)
MOTION = re.compile(r"MotionEvent|OnTouchListener|onTouch\(|dispatchTouchEvent", re.I)
FLOATING = re.compile(r"FloatingService", re.I)
INJECT = re.compile(
    r"injectInputEvent|InputManager|Instrumentation|UiAutomation|dispatchGesture|"
    r"AccessibilityService|MotionEvent;->obtain|InputDevice|sendevent|Shizuku|"
    r"FLAG_NOT_TOUCHABLE|TYPE_APPLICATION_OVERLAY|WindowManager\$LayoutParams",
    re.I,
)

print("=== LEGACY FREEZE DISCOVERY ===")

# 1) Direct semantic matches.
semantic = []
for path in all_files:
    text = path.read_text(errors="ignore")
    if KEYWORDS.search(text):
        semantic.append((path, text))
print(f"semantic_files={len(semantic)}")
for path, text in semantic[:30]:
    print(f"\n--- semantic: {path.relative_to(root)} ---")
    lines = text.splitlines()
    hits = [i for i, line in enumerate(lines) if KEYWORDS.search(line)]
    shown = set()
    for hit in hits[:12]:
        start = max(0, hit - 10); end = min(len(lines), hit + 18)
        key = (start, end)
        if key in shown:
            continue
        shown.add(key)
        for i in range(start, end):
            print(f"{i+1:05d}: {lines[i]}")

# 2) FloatingService inventory: fields/methods/nested classes and literals.
floating = []
for path in all_files:
    if "FloatingService" in path.name:
        text = path.read_text(errors="ignore")
        floating.append((path, text))
print(f"\nfloating_files={len(floating)}")
for path, text in floating:
    print(f"\n--- floating: {path.relative_to(root)} ---")
    for line in text.splitlines():
        if (line.startswith(".field") or line.startswith(".method") or
                line.startswith(".implements") or line.startswith(".super") or
                "const-string" in line):
            print(line)

# 3) Touch handlers inside FloatingService and its nested classes.
print("\n=== TOUCH HANDLERS RELATED TO FLOATING SERVICE ===")
count = 0
for path, text in floating:
    if MOTION.search(text):
        for m in re.finditer(r"(?ms)^\.method .*?^\.end method", text):
            body = m.group(0)
            if MOTION.search(body):
                count += 1
                print(f"\n--- touch method: {path.relative_to(root)} ---")
                print(body[:20000])

# 4) Other classes that both handle touch and call/reference FloatingService.
for path in all_files:
    if any(path == p for p, _ in floating):
        continue
    text = path.read_text(errors="ignore")
    if MOTION.search(text) and FLOATING.search(text):
        count += 1
        print(f"\n--- linked touch class: {path.relative_to(root)} ---")
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if MOTION.search(line) or FLOATING.search(line):
                start = max(0, i - 22); end = min(len(lines), i + 52)
                for j in range(start, end):
                    print(f"{j+1:05d}: {lines[j]}")
                print("...")

print(f"\nrelated_touch_sections={count}")

# 5) Existing input-injection / overlay primitives. Reuse these instead of
# creating a second privileged path if the legacy app already has one.
print("\n=== INPUT / OVERLAY PRIMITIVES ===")
injection_files = []
for path in all_files:
    text = path.read_text(errors="ignore")
    if INJECT.search(text):
        injection_files.append((path, text))
print(f"input_primitive_files={len(injection_files)}")
for path, text in injection_files[:60]:
    print(f"\n--- input primitive: {path.relative_to(root)} ---")
    lines = text.splitlines()
    hits = [i for i, line in enumerate(lines) if INJECT.search(line)]
    shown = set()
    for hit in hits[:16]:
        start = max(0, hit - 14); end = min(len(lines), hit + 30)
        key = (start, end)
        if key in shown:
            continue
        shown.add(key)
        for i in range(start, end):
            print(f"{i+1:05d}: {lines[i]}")

print("=== END LEGACY FREEZE DISCOVERY ===")
