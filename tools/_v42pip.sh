#!/bin/bash
# Back from picture-in-picture on mpv, the video surface's buffer must match
# the full screen. Reads SurfaceFlinger's buffer sizes and takes a screenshot.
# Usage: PKG=<package> tools/_v42pip.sh       (engine already set)

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap cleanup EXIT
SHOTS="$WORK/shots"
mkdir -p "$SHOTS"
TAG="${TAG:-$PKG}"

surface_sizes() {
  adb shell dumpsys SurfaceFlinger | tr -d '\r' \
    | grep -F "name:SurfaceView[$PKG/" \
    | grep -oE 'w/h:[0-9]+x[0-9]+' | sort | uniq -c | sort -rn | head -3 | tr '\n' ' '
}

server "reset=1"
launch_url "$SERVER/clip.ts" "--es title Clip" || { fail "opened"; exit 1; }
sleep 8
echo "before picture-in-picture: $(surface_sizes)"

adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 1
adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
sleep 1
AT="$(centre content-desc Picture-in-picture)"
if [ -z "$AT" ]; then
  fail "found the picture-in-picture button" "$(dump | grep -oE 'content-desc="[^"]+"' | head -16 | tr '\n' ' ')"
  exit 1
fi
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
adb shell "input tap $AT" >/dev/null 2>&1
sleep 5
echo "in picture-in-picture: $(adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -oE 'mode=pinned|windowingMode=pinned')"

# back to full screen, as tapping the window does
adb shell "am start -n $ACT" >/dev/null 2>&1
sleep 6
adb logcat -c >/dev/null 2>&1
sleep 4
AFTER="$(surface_sizes)"
echo "after picture-in-picture: $AFTER"
adb exec-out screencap -p > "$SHOTS/pip-after-$TAG.png"
echo "screenshot: $SHOTS/pip-after-$TAG.png"

# The newest buffers are the ones being drawn; full screen is 1080x2400 here.
NEWEST="$(adb shell dumpsys SurfaceFlinger | tr -d '\r' \
    | grep -F "name:SurfaceView[$PKG/" \
    | sed -E 's/.*id:([0-9]+).*w\/h:([0-9]+x[0-9]+).*/\1 \2/' | sort -n | tail -1 | cut -d' ' -f2)"
echo "newest buffer: $NEWEST"
ENGINE_NOW="$(getpref playbackEngine | grep -oE '>[a-z0-9]+<' | tr -d '<>')"
if [ "$ENGINE_NOW" = "media3" ]; then
  # Media3's surface is the film's frame, so its buffer must match the frame
  adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
  sleep 1
  FRAME="$(bounds_of resource-id "$PKG:id/exo_content_frame" | awk '{print ($3-$1) "x" ($4-$2)}')"
  echo "media3 frame: $FRAME"
  if [ -n "$FRAME" ] && [ "$NEWEST" = "$FRAME" ]; then
    pass "the video surface matches the frame it is drawn in"
  else
    fail "the video surface matches the frame it is drawn in" "buffer $NEWEST, frame $FRAME"
  fi
elif [ "$NEWEST" = "1080x2400" ] || [ "$NEWEST" = "2400x1080" ]; then
  pass "the video surface is back to the whole screen"
else
  fail "the video surface is back to the whole screen" "newest buffer $NEWEST"
fi
check "no crash" "$(crashed)"
