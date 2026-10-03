#!/usr/bin/env python3
"""Resource-id based settings click test for mobile + summary report."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

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
    xml = adb("shell", "cat", "/sdcard/u.xml")
    return ET.fromstring(xml)


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


def tap_id(suffix, exact_text=None):
    """Tap node whose resource-id ends with suffix. Prefer exact_text match if given."""
    root = tree()
    candidates = []
    for n in root.iter("node"):
        rid = n.get("resource-id") or ""
        if not rid.endswith("/" + suffix) and not rid.endswith("id/" + suffix):
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
        joined = " / ".join(texts)
        score = 0
        if exact_text and any(exact_text == t for t in texts):
            score += 10
        if n.get("clickable") == "true":
            score += 5
        candidates.append((score, c, joined[:60], rid))
    if not candidates:
        return False, None
    candidates.sort(key=lambda x: -x[0])
    _, c, joined, rid = candidates[0]
    adb("shell", "input", "tap", str(c[0]), str(c[1]))
    time.sleep(0.8)
    return True, joined


def classify(before, after):
    new = [x for x in after if x not in before]
    gone = [x for x in before if x not in after]
    blob = " ".join(new)
    if any(k in blob for k in ("取消", "确定", "输入", "清除", "默认", "保存", "选择", "完成", "编辑")):
        return "dialog", new[:12]
    if len(new) + len(gone) >= 5:
        return "navigation", new[:12]
    if new or gone:
        return "value-change", new[:12]
    return "no-change", []


def back(n=1):
    for _ in range(n):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.55)


def go_settings_root():
    wake()
    adb("shell", "monkey", "-p", "com.fongmi.android.tv", "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(1.5)
    ok, hit = tap_id("setting")
    time.sleep(0.7)
    return ok, hit


def test(page, items, results):
    print(f"\n== {page} ==")
    for id_suffix, needle in items:
        wake()
        pre = labels(tree())
        ok, hit = tap_id(id_suffix, exact_text=needle)
        post = labels(tree())
        kind, new = classify(pre, post)
        results.append({
            "page": page, "id": id_suffix, "needle": needle, "tapped": ok,
            "hit": hit, "kind": kind, "new": new,
        })
        print(f"  [{kind:12}] {id_suffix:16} {needle!r:12} ok={ok} hit={hit} new={new[:4]}")
        if kind == "dialog":
            back(1)
        elif kind == "navigation":
            back(2)


def main():
    results = []
    go_settings_root()
    test("main", [
        ("vod", "点播"), ("live", "直播"), ("wall", "壁纸"),
        ("player", "播放设置"), ("danmaku", "弹幕设置"),
        ("incognito", "无痕模式"), ("size", "图片尺寸"),
        ("doh", "DoH"), ("cache", "缓存"), ("backup", "备份"), ("restore", "恢复"),
        ("version", "版本"),
    ], results)

    go_settings_root(); tap_id("player"); time.sleep(0.7)
    test("player", [
        ("engine", "播放引擎"), ("decode", "解码"), ("adblock", "智能去广"),
        ("buffer", "缓冲"), ("http", "HTTP"), ("liveLatency", "延迟"),
        ("mpvConf", "mpv.conf"), ("mpvGpuNext", "gpu-next"), ("mpvVulkan", "Vulkan"),
        ("render", "渲染"), ("scale", "缩放"), ("caption", "字幕"),
        ("speed", "倍速"), ("audioEffect", "音频"), ("videoEffect", "视频"),
        ("background", "后台"), ("preload", "预载"), ("ua", "User-Agent"),
        ("subtitleAssrt", "字幕搜索"), ("subtitleFont", "字幕字体"),
    ], results)

    go_settings_root(); tap_id("player"); time.sleep(0.5); tap_id("decode"); time.sleep(0.7)
    test("decode", [
        ("tunnel", "隧道"), ("audioPassThrough", "直通"),
        ("audioPrefer", "音频"), ("videoPrefer", "视频"),
        ("aac", "AAC"),
    ], results)

    go_settings_root(); tap_id("danmaku"); time.sleep(0.7)
    test("danmaku", [
        ("danmakuLoad", "加载"), ("danmakuApi", "API"),
        ("danmakuAuto", "自动"), ("danmakuSpider", "爬虫"),
    ], results)

    go_settings_root(); tap_id("player"); time.sleep(0.5); tap_id("preload"); time.sleep(0.7)
    test("preload", [
        ("preload", "预载"), ("preloadThread", "线程"), ("preloadSize", "大小"),
        ("preloadTime", "时间"), ("preloadMetered", "计量"), ("preloadDiagnostics", "诊断"),
    ], results)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    from collections import Counter
    print("\nSUMMARY", dict(Counter(r["kind"] for r in results)))
    print("TAPPED", sum(1 for r in results if r["tapped"]), "/", len(results))
    for r in results:
        if not r["tapped"] or r["kind"] == "no-change":
            print("  REVIEW", r["page"], r["id"], r["kind"], "tapped", r["tapped"], r["hit"])
    print("WROTE", OUT)


if __name__ == "__main__":
    main()
