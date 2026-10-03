#!/usr/bin/env python3
"""Per-item settings test: reopen the correct page before every click."""
from __future__ import annotations

import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import Counter

DEVICE = os.environ.get("PHONE_SERIAL", "192.168.1.202:5555")
OUT = "Release/device-test/settings-report-phone.json"


def adb(*args, timeout=45):
    return subprocess.run(
        ["adb", "-s", DEVICE, *args],
        capture_output=True, text=True, encoding="utf-8", errors="ignore", timeout=timeout
    ).stdout


def wake():
    adb("shell", "svc power stayon true; input keyevent KEYCODE_WAKEUP; input keyevent 82; wm dismiss-keyguard")


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/u.xml"))


def labels(root):
    out = []
    for n in root.iter("node"):
        for a in ("text", "content-desc"):
            v = (n.get(a) or "").strip()
            if v and v not in out:
                out.append(v)
    return out


def center_of(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_id(suffix):
    root = tree()
    best = None
    for n in root.iter("node"):
        rid = n.get("resource-id") or ""
        if not rid.endswith("/" + suffix):
            continue
        c = center_of(n)
        if not c:
            continue
        texts = []
        for ch in n.iter():
            for a in ("text", "content-desc"):
                v = (ch.get(a) or "").strip()
                if v:
                    texts.append(v)
        score = (5 if n.get("clickable") == "true" else 0)
        cand = (score, c, " / ".join(texts)[:70], rid)
        if best is None or cand[0] > best[0]:
            best = cand
    if not best:
        return False, None
    adb("shell", "input", "tap", str(best[1][0]), str(best[1][1]))
    time.sleep(0.85)
    return True, best[2]


def classify(before, after):
    new = [x for x in after if x not in before]
    gone = [x for x in before if x not in after]
    blob = " ".join(new)
    if any(k in blob for k in ("取消", "确定", "输入", "清除", "默认", "保存", "选择", "完成", "编辑", "粘贴")):
        return "dialog", new[:12]
    # pure toggle/value of one row: small delta
    if len(new) + len(gone) <= 6 and (new or gone):
        return "value-change", new[:8] + ["-"] + gone[:4]
    if len(new) + len(gone) >= 5:
        return "page-change", new[:12]
    return "no-change", []


def back(n=1):
    for _ in range(n):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.55)


def open_app_settings():
    wake()
    adb("shell", "monkey", "-p", "com.fongmi.android.tv", "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(1.4)
    tap_id("setting")
    time.sleep(0.6)


def open_player():
    open_app_settings()
    tap_id("player")
    time.sleep(0.6)


def open_decode():
    open_player()
    ok, _ = tap_id("decode")
    if not ok:
        # maybe still on player
        pass
    time.sleep(0.6)


def open_danmaku():
    open_app_settings()
    tap_id("danmaku")
    time.sleep(0.6)


def open_preload():
    open_player()
    tap_id("preload")
    time.sleep(0.6)


PAGES = {
    "main": (open_app_settings, [
        "vod", "live", "wall", "player", "danmaku", "incognito",
        "size", "doh", "cache", "backup", "restore", "version",
    ]),
    "player": (open_player, [
        "engine", "decode", "adblock", "buffer", "http", "liveLatency",
        "mpvConf", "mpvGpuNext", "mpvVulkan", "render", "scale", "caption",
        "speed", "audioEffect", "videoEffect", "background", "preload", "ua",
        "subtitleAssrt", "subtitleFont",
    ]),
    "decode": (open_decode, [
        "tunnel", "audioPassThrough", "audioPrefer", "videoPrefer", "aac",
    ]),
    "danmaku": (open_danmaku, [
        "danmakuLoad", "danmakuApi", "danmakuAuto", "danmakuSpider",
    ]),
    "preload": (open_preload, [
        "preload", "preloadThread", "preloadSize", "preloadTime",
        "preloadMetered", "preloadDiagnostics",
    ]),
}


def main():
    results = []
    for page, (opener, ids) in PAGES.items():
        print(f"\n===== {page} =====")
        for id_suffix in ids:
            opener()
            pre = labels(tree())
            ok, hit = tap_id(id_suffix)
            post = labels(tree())
            kind, new = classify(pre, post)
            results.append({
                "page": page, "id": id_suffix, "tapped": ok, "hit": hit,
                "kind": kind, "new": new,
            })
            print(f"  [{kind:12}] {id_suffix:16} ok={ok} hit={hit} new={new}")
            if kind == "dialog":
                back(1)
            elif kind == "page-change":
                back(1)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print("\nSUMMARY", dict(Counter(r["kind"] for r in results)))
    print("TAPPED_OK", sum(1 for r in results if r["tapped"]), "/", len(results))
    print("RESPONDED", sum(1 for r in results if r["kind"] != "no-change"), "/", len(results))
    for r in results:
        if r["kind"] == "no-change":
            print("  IDLE", r["page"], r["id"], "tapped", r["tapped"], r["hit"])
    print("WROTE", OUT)


if __name__ == "__main__":
    main()
