#!/bin/bash
# Cycle the TV through every built-in wallpaper and log the resulting wall_color.
# Setting.getWallColor() is what CustomWallView writes AFTER sampling the drawable with
# Palette, so this reveals which indices the old hardcoded WALL_COLORS table got wrong.
set -u
DEV=home-rk:5555
PREFS=/data/data/com.fongmi.android.tv/shared_prefs/com.fongmi.android.tv_preferences.xml
TABLE=(x 40C090 4870E0 48B0C0 404040)
PY="C:/Users/zyq/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe"

decode() { "$PY" -c "u=$1 & 0xFFFFFFFF; print('#%08X' % u)"; }

for w in 1 2 3 4; do
  adb -s $DEV shell am force-stop com.fongmi.android.tv >/dev/null 2>&1
  sleep 1
  # Set the index and CLEAR the cached colour, so the app must re-sample on next launch.
  adb -s $DEV shell "sed -i 's|name=\"wall\" value=\"[0-9]*\"|name=\"wall\" value=\"$w\"|' $PREFS"
  adb -s $DEV shell "sed -i '/name=\"wall_color\"/d' $PREFS"
  adb -s $DEV shell "chown 10092:10092 $PREFS" >/dev/null 2>&1
  adb -s $DEV shell am start -n com.fongmi.android.tv/.ui.activity.HomeActivity >/dev/null 2>&1
  # Wait until wall_color reappears (sampled + written), up to ~15s.
  val=""
  for i in $(seq 1 15); do
    sleep 1
    val=$(adb -s $DEV shell "grep -oE 'name=\"wall_color\" value=\"[-0-9]+' $PREFS" | grep -oE '[-0-9]+$')
    [ -n "$val" ] && break
  done
  if [ -n "$val" ]; then
    echo "wall=$w   sampled=$(decode $val)   old-table=#${TABLE[$w]}"
  else
    echo "wall=$w   (no wall_color written within 15s)"
  fi
done
