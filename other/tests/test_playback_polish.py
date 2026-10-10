#!/usr/bin/env python3
"""Static checks for cast seek, DoH fallback, speed presets, and HLS cache keys."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def hls_references(text: str):
    refs = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            refs.extend(re.findall(r'URI="([^"]+)"', line))
            continue
        refs.append(line)
    return refs


def main():
    transport = (ROOT / "app/src/main/java/com/fongmi/android/tv/dlna/DLNAAvTransportImpl.java").read_text(encoding="utf-8")
    assert "else pendingSeekMs = ms;" in transport or "pendingSeekMs = ms;" in transport
    assert "if (dlnaActive && local != null)" in transport
    assert "if (this.player == player) player.seekTo(ms);" in transport
    assert "return !currentURI.isEmpty() && hasNext();" in transport

    dns = (ROOT / "catvod/src/main/java/com/github/catvod/net/OkDns.java").read_text(encoding="utf-8")
    assert "callTimeout(3, TimeUnit.SECONDS)" in dns
    assert "Dns.SYSTEM.lookup" in dns
    assert "DOH_FAILURE_LIMIT" in dns
    doh = (ROOT / "catvod/src/main/java/com/github/catvod/bean/Doh.java").read_text(encoding="utf-8")
    assert "catch (RuntimeException ignored)" in doh

    setting = (ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java").read_text(encoding="utf-8")
    assert "SPEED_PRESETS" in setting
    assert "nextSpeed" in setting
    player = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java").read_text(encoding="utf-8")
    assert "PlayerSetting.nextSpeed(getSpeed())" in player
    notify = (ROOT / "app/src/main/java/com/fongmi/android/tv/utils/Notify.java").read_text(encoding="utf-8")
    assert "Gravity.TOP" in notify

    diag = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/PreloadDiagnostics.java").read_text(encoding="utf-8")
    assert "UriUtil.resolve" in diag
    assert "hlsReferences" in diag
    assert "cache.getKeys()" not in diag
    scan = (ROOT / "app/src/main/java/com/fongmi/android/tv/utils/ScanTask.java").read_text(encoding="utf-8")
    assert "newFixedThreadPool" in scan
    assert "callTimeout" in scan
    assert "onFinished" in scan
    assert "submitLarge" not in scan
    assert "PreloadKeyCache" in (ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/PreCache.java").read_text(encoding="utf-8")
    assert "noteWatchedKey" in diag
    engine = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayerEngine.java").read_text(encoding="utf-8")
    assert "shouldHintHls" in engine
    assert "endsWithContainer" in engine
    assert 'return live && !leaf.contains(".")' in engine
    handle = engine[engine.find("public ErrorAction handleError"):engine.find("private ErrorAction retryHls")]
    assert "ERROR_CODE_IO_BAD_HTTP_STATUS" not in handle
    assert "ERROR_CODE_TIMEOUT" in handle
    provider = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvErrorMsgProvider.java").read_text(encoding="utf-8")
    assert "e.errorCode" in provider
    assert "e.getMessage()" in provider
    mpv = (ROOT / "media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java").read_text(encoding="utf-8")
    assert "demuxer-cache-time" in mpv
    assert '"cache-buffering-state"' not in mpv
    assert "pendingSeekMs = this.positionMs" in mpv
    assert 'live ? "absolute"' in mpv
    assert "level <= 20" in mpv
    # The container-side decoder deadlock is fixed in libcodec2_rk_component.so, so the
    # in-app dead-codec rebuild loop must not come back.
    assert "recoverDeadHardwareDecoder" not in mpv
    assert "isDeadHardwareDecoder" not in mpv
    assert "video output stuck without first frame" not in mpv
    assert "isEmbedVo()" in mpv
    util = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvUtil.java").read_text(encoding="utf-8")
    fallback = util.split("void addApplicationOptions", 1)[1].split("void addStreamOptions", 1)[0]
    # Hard mode must keep libmpv's own software fallback. Writing "no" disables it and libmpv then
    # force-EOFs the video track, which shows up as audio over a black surface with no error.
    assert 'addPreInitStringOption("hwdec-software-fallback"' not in fallback
    assert 'addPreInitStringOption("hwdec-software-fallback", "no")' not in util
    preload = util.split("void addPreloadOptions", 1)[1].split("void addSubtitleStyleOptions", 1)[0]
    assert 'addPreInitStringOption("cache-secs"' not in preload
    assert "getPreloadTimeSeconds" not in util
    setting = (ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java").read_text(encoding="utf-8")
    assert 'Prefers.put("mpv_vulkan", false)' in setting
    assert 'Prefers.put("mpv_gpu_next", false)' in setting
    exo = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java").read_text(encoding="utf-8")
    assert "Math.max(bufferMs, live ? 3000 : 5000)" not in exo
    assert "playbackConfig" in exo
    assert "PcmOnlyAudioOutputProvider" in exo
    assert "videoSoftware ? MediaCodecSelector.PREFER_SOFTWARE" in exo
    assert "boolean audioSoftware = softwareDecode || audioPrefer" in exo
    assert "setTunnelingEnabled(PlayerSetting.isTunnelingEnabled() && !software)" in exo
    assert "live && PlayerSetting.isLiveLowLatency()" in util
    mpv_player = (ROOT / "media3compat/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java").read_text(encoding="utf-8")
    assert "could not open codec" in mpv_player
    assert "ERROR_CODE_DECODER_INIT_FAILED" in mpv_player
    manager = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java").read_text(encoding="utf-8")
    assert "engine.refreshConfig()" in manager
    assert "PlayerSetting.putVideoPrefer(false)" in manager
    activity = (ROOT / "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java").read_text(encoding="utf-8")
    assert "appliedRender != PlayerSetting.getRender()" in activity
    font = (ROOT / "app/src/main/java/com/fongmi/android/tv/player/subtitle/ExternalFont.java").read_text(encoding="utf-8")
    assert ":style=" in font
    playlist = """#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="key.key"
#EXTINF:6.0,
seg0.ts
http://cdn.example/a/seg1.ts
"""
    assert hls_references(playlist) == ["key.key", "seg0.ts", "http://cdn.example/a/seg1.ts"]
    print("test_playback_polish: ok")


if __name__ == "__main__":
    main()
