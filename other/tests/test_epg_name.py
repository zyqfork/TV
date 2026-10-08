#!/usr/bin/env python3
"""Static checks for EPG name normalization and live track-key stability."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def java_fold(raw: str) -> str:
    """Punctuation fold only. 频道 and 4K/8K stay in the indexed key."""
    if not raw:
        return ""
    sb = []
    for c in raw:
        if "\uff10" <= c <= "\uff19":
            c = chr(ord("0") + (ord(c) - ord("\uff10")))
        if c.isspace() or c in "-_·—－./":
            continue
        sb.append(c.upper())
    return "".join(sb)


def java_strip_quality(folded: str) -> str:
    if not folded:
        return ""
    suffixes = ["高清", "超清", "标清", "综合", "綜合", "HD", "FHD", "UHD", "HDR", "HEVC", "H265"]
    out = folded
    trimmed = True
    while trimmed:
        trimmed = False
        for suf in suffixes:
            upper = suf.upper()
            if out.endswith(upper) and len(out) > len(upper):
                out = out[: -len(upper)]
                trimmed = True
    return out


def java_normalize(raw: str) -> str:
    """Mirror EpgName.normalize for regression without a JVM."""
    return java_strip_quality(java_fold(raw))


class ChannelIndex:
    """Mirror EpgParser exact/fuzzy registration."""

    def __init__(self):
        self.exact = {}
        self.fuzzy = {}
        self.pending = {}
        self.ambiguous = set()

    def put_exact(self, key, channel):
        if key and key not in self.exact:
            self.exact[key] = channel

    def note_fuzzy(self, key, channel):
        normalized = java_fold(key)
        if not normalized:
            return
        previous = self.pending.get(normalized)
        if previous is None:
            self.pending[normalized] = channel
        elif previous is not channel:
            self.ambiguous.add(normalized)

    def finish(self):
        self.fuzzy = {key: channel for key, channel in self.pending.items() if key not in self.ambiguous}

    def lookup(self, key):
        if not key:
            return None
        if key in self.exact:
            return self.exact[key]
        folded = java_fold(key)
        if not folded:
            return None
        hit = self.fuzzy.get(folded)
        if hit is not None:
            return hit
        stripped = java_strip_quality(folded)
        return None if stripped == folded else self.fuzzy.get(stripped)


def main():
    epg = (ROOT / "app/src/main/java/com/fongmi/android/tv/api/parser/EpgName.java").read_text(encoding="utf-8")
    parser = (ROOT / "app/src/main/java/com/fongmi/android/tv/api/parser/EpgParser.java").read_text(encoding="utf-8")
    interceptor = (ROOT / "catvod/src/main/java/com/github/catvod/net/interceptor/ResponseInterceptor.java").read_text(encoding="utf-8")
    assert "class EpgName" in epg
    assert "EpgName.fold" in parser
    assert "EpgName.stripQuality" in parser
    assert "lookupChannel" in parser
    assert "BrotliInputStream" in interceptor
    assert '"br".equalsIgnoreCase' in interceptor

    cases = [
        ("CCTV-1 综合", "CCTV1"),
        ("CCTV１高清", "CCTV1"),
        ("湖南卫视HD", "湖南卫视"),
        ("BBC One", "BBCONE"),
        ("电影频道", "电影频道"),
        ("CCTV-4K", "CCTV4K"),
    ]
    for raw, expected in cases:
        got = java_normalize(raw)
        assert got == expected, (raw, got, expected)

    # Suffix folding still matches a unique channel. Two channels that fold together stay exact-only.
    hunan = object()
    movie = object()
    movie_hd = object()
    index = ChannelIndex()
    for channel, names in ((hunan, ["湖南卫视"]), (movie, ["电影"]), (movie_hd, ["电影频道"])):
        for name in names:
            index.put_exact(name, channel)
            index.note_fuzzy(name, channel)
    index.finish()
    assert index.lookup("湖南卫视HD") is hunan
    assert index.lookup("电影") is movie
    assert index.lookup("电影频道") is movie_hd
    assert index.lookup("电影高清") is movie
    lone = ChannelIndex()
    only = object()
    lone.put_exact("电影频道", only)
    lone.note_fuzzy("电影频道", only)
    lone.finish()
    assert lone.lookup("电影高清") is None
    assert lone.lookup("电影频道") is only
    cctv = object()
    cctv4k = object()
    tv = ChannelIndex()
    for channel, names in ((cctv, ["CCTV"]), (cctv4k, ["CCTV-4K"])):
        for name in names:
            tv.put_exact(name, channel)
            tv.note_fuzzy(name, channel)
    tv.finish()
    assert tv.lookup("CCTV-4K") is cctv4k
    assert tv.lookup("CCTV") is cctv
    assert "ambiguous" in parser
    assert "liveChannels.fuzzy.get" in parser

    for flavor in ("leanback", "mobile"):
        live = (ROOT / f"app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/LiveActivity.java").read_text(encoding="utf-8")
        assert 'return "live@@@" + id' in live
        assert "trackKey(mChannel, result)" in live

    sync = (ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SyncDialog.java").read_text(encoding="utf-8")
    assert "class SyncDialog" in sync
    assert "ScanTask" in sync
    scan = (ROOT / "app/src/main/java/com/fongmi/android/tv/utils/ScanTask.java").read_text(encoding="utf-8")
    assert "callTimeout(CALL_MS" in scan
    keep_layout = (ROOT / "app/src/leanback/res/layout/activity_keep.xml").read_text(encoding="utf-8")
    assert 'android:id="@+id/sync"' in keep_layout

    seek = (ROOT / "app/src/main/java/com/fongmi/android/tv/dlna/DLNAAvTransportImpl.java").read_text(encoding="utf-8")
    assert "if (ms < 0) return;" in seek
    assert "return -1;" in seek

    local = (ROOT / "app/src/main/java/com/fongmi/android/tv/server/process/Local.java").read_text(encoding="utf-8")
    assert "ZIP_MAX_ENTRIES" in local
    assert "zipDecompress(source, directory, ZIP_MAX_ENTRIES, ZIP_MAX_BYTES)" in local

    backup = (ROOT / "app/src/main/java/com/fongmi/android/tv/db/BackupManager.java").read_text(encoding="utf-8")
    assert "snapshot.restore()" in backup

    history = (ROOT / "app/src/main/java/com/fongmi/android/tv/bean/History.java").read_text(encoding="utf-8")
    assert "parts.length - 1" in history

    airplay = (ROOT / "app/src/main/java/com/fongmi/android/tv/ui/activity/AirPlayCastActivity.java").read_text(encoding="utf-8")
    assert "onUserLeaveHint" in airplay
    assert "mPiP.enter" in airplay
    manifest = (ROOT / "app/src/mobile/AndroidManifest.xml").read_text(encoding="utf-8")
    assert re.search(r'AirPlayCastActivity[\s\S]*?supportsPictureInPicture="true"', manifest)

    print("test_epg_name: ok")


if __name__ == "__main__":
    main()
