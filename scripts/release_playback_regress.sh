#!/usr/bin/env bash
# Release / debug playback regression on a remote ADB device (home-rk).
# Usage:
#   ADB=100.109.142.62:5555 ./scripts/release_playback_regress.sh
#   ADB=... APK=path/to.apk BUILD=0 ./scripts/release_playback_regress.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB_SERIAL="${ADB:-100.109.142.62:5555}"
PKG="com.fongmi.android.tv"
BUILD="${BUILD:-1}"
APK="${APK:-$ROOT/app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk}"
LOG_DIR="${LOG_DIR:-/tmp/fongmi-regress-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$LOG_DIR"

LIVE_OK="${LIVE_OK:-http://10.0.0.1:7088/udp/239.10.0.48:1025}"
LIVE_BAD="${LIVE_BAD:-http://10.0.0.1:7088/udp/239.10.0.78:1025}"
DASH_URL="${DASH_URL:-https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd}"
HLS_URL="${HLS_URL:-https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8}"

adb_cmd() { adb -s "$ADB_SERIAL" "$@"; }

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

require_device() {
  adb_cmd get-state >/dev/null
  log "device ok: $ADB_SERIAL"
}

build_if_needed() {
  if [[ "$BUILD" != "1" ]]; then return; fi
  log "building leanback arm64 debug..."
  (cd "$ROOT" && ./gradlew assembleLeanbackArm64_v8aDebug)
}

install_apk() {
  [[ -f "$APK" ]] || { echo "missing APK: $APK" >&2; exit 1; }
  log "install $APK"
  adb_cmd install -r "$APK"
}

capture_slice() {
  local name="$1"
  adb_cmd logcat -d >"$LOG_DIR/${name}.logcat.txt" || true
  adb_cmd shell "dumpsys media.codec 2>/dev/null" >"$LOG_DIR/${name}.codec.txt" || true
  adb_cmd shell "ps -A | grep fongmi || true" >"$LOG_DIR/${name}.ps.txt" || true
  adb_cmd shell "dumpsys activity services $PKG 2>/dev/null" >"$LOG_DIR/${name}.services.txt" || true
}

grep_key() {
  local file="$1"
  rg -n "MpvSmoke:|ExoSmoke:|RENDERED_FIRST|Using hardware|VO:|HTTP error|FATAL EXCEPTION|bad ELF|c2\.rk\.avc|BACKGROUND_RELEASED|DESTROY_RELEASED|PlaybackService|error_play" "$file" || true
}

run_mpv() {
  local label="$1" url="$2" decode="$3" live="$4" wait="$5"
  log "MPV $label"
  adb_cmd logcat -c </dev/null
  adb_cmd shell am force-stop "$PKG" </dev/null
  adb_cmd shell am start -n "$PKG/.debug.MpvSmokeActivity" \
    --es url "$url" --ei decode "$decode" --ez formal_config true --ez live "$live" --ez stop_on_background true </dev/null >/dev/null
  sleep "$wait"
  capture_slice "mpv_${label}"
  grep_key "$LOG_DIR/mpv_${label}.logcat.txt" | tee "$LOG_DIR/mpv_${label}.summary.txt" >/dev/null
  local ok fail expect_fail="${6:-0}"
  ok=$(rg -c "RENDERED_FIRST_FRAME|RESULT ok=true" "$LOG_DIR/mpv_${label}.logcat.txt" || true)
  fail=$(rg -c "ERROR code=|HTTP error|RESULT ok=false" "$LOG_DIR/mpv_${label}.logcat.txt" || true)
  if [[ "$expect_fail" == "1" ]]; then
    if [[ "${fail:-0}" -gt 0 ]]; then
      echo "PASS mpv_$label (expected fail)" | tee -a "$LOG_DIR/summary.txt"
    else
      echo "FAIL mpv_$label (expected error missing)" | tee -a "$LOG_DIR/summary.txt"
    fi
  elif [[ "${ok:-0}" -gt 0 ]]; then
    echo "PASS mpv_$label" | tee -a "$LOG_DIR/summary.txt"
  else
    echo "FAIL mpv_$label" | tee -a "$LOG_DIR/summary.txt"
  fi
}

run_exo() {
  local label="$1" url="$2" wait="$3"
  log "EXO $label"
  adb_cmd logcat -c </dev/null
  adb_cmd shell am force-stop "$PKG" </dev/null
  adb_cmd shell am start -n "$PKG/.debug.ExoSmokeActivity" \
    --es url "$url" --ei decode 1 </dev/null >/dev/null
  sleep "$wait"
  capture_slice "exo_${label}"
  grep_key "$LOG_DIR/exo_${label}.logcat.txt" | tee "$LOG_DIR/exo_${label}.summary.txt" >/dev/null
  local ok
  ok=$(rg -c "RENDERED_FIRST_FRAME" "$LOG_DIR/exo_${label}.logcat.txt" || true)
  if [[ "${ok:-0}" -gt 0 ]]; then
    echo "PASS exo_$label" | tee -a "$LOG_DIR/summary.txt"
  else
    echo "FAIL exo_$label" | tee -a "$LOG_DIR/summary.txt"
  fi
}

check_decoder_release() {
  log "decoder-release probe"
  adb_cmd logcat -c </dev/null
  adb_cmd shell am force-stop "$PKG" </dev/null
  adb_cmd shell am start -n "$PKG/.debug.MpvSmokeActivity" \
    --es url "$LIVE_OK" --ei decode 2 --ez formal_config true --ez live true --ez stop_on_background true </dev/null >/dev/null
  sleep 8
  capture_slice "codec_during"
  local during
  during=$(rg -c "Created component \[c2\.rk|Using hardware decoding" "$LOG_DIR/codec_during.logcat.txt" || true)
  # Finish activity: send BACK then HOME to trigger onStop/onDestroy release path
  adb_cmd shell input keyevent KEYCODE_BACK </dev/null || true
  sleep 1
  adb_cmd shell am force-stop "$PKG" </dev/null || true
  # Better: destroy via finishing smoke by starting home after ensuring release log
  adb_cmd shell am start -n "$PKG/.debug.MpvSmokeActivity" \
    --es url "$LIVE_OK" --ei decode 2 --ez formal_config true --ez live true --ez stop_on_background true </dev/null >/dev/null
  sleep 6
  adb_cmd logcat -c </dev/null
  # Move to home without force-stop to exercise BACKGROUND stop/release
  adb_cmd shell input keyevent KEYCODE_HOME </dev/null
  sleep 3
  capture_slice "codec_after_home"
  local released
  released=$(rg -c "BACKGROUND_RELEASED|DESTROY_RELEASED" "$LOG_DIR/codec_after_home.logcat.txt" || true)
  local still_hw
  still_hw=$(rg -c "Using hardware decoding \(mediacodec\)" "$LOG_DIR/codec_after_home.logcat.txt" || true)
  {
    echo "decoder_during_alloc=$during"
    echo "decoder_release_logs=$released"
    echo "decoder_hw_lines_after_home=$still_hw"
  } | tee -a "$LOG_DIR/summary.txt"
  if [[ "${released:-0}" -gt 0 ]]; then
    echo "PASS decoder_release" | tee -a "$LOG_DIR/summary.txt"
  else
    echo "WARN decoder_release (no BACKGROUND_RELEASED seen; check device logs)" | tee -a "$LOG_DIR/summary.txt"
  fi
  adb_cmd shell am force-stop "$PKG" </dev/null
}

main() {
  : >"$LOG_DIR/summary.txt"
  require_device
  build_if_needed
  install_apk

  run_mpv "live_ok_perf" "$LIVE_OK" 2 true 12
  run_mpv "live_bad_503" "$LIVE_BAD" 2 true 10 1
  run_exo "dash" "$DASH_URL" 14
  run_exo "hls" "$HLS_URL" 14
  check_decoder_release

  log "done. logs: $LOG_DIR"
  echo "==== SUMMARY ===="
  cat "$LOG_DIR/summary.txt"
}

main "$@"
