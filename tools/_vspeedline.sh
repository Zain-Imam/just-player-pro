#!/bin/bash
# The top line says how fast a stream is arriving -- and says nothing about a
# file on the device.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-speedline.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

# The line under the title: "1920x1080 · H.264 · ... · Media3 · 2.1 MB/s".
meta_line() {
  snap
  grep -oE 'text="[0-9]+×[0-9]+[^"]*"' "$SNAP" | head -1 | sed 's/text=//;s/"//g'
}

prepare

echo "=== a file on the device says nothing about speed ==="
open_film
show_controls >/dev/null
LOCAL="$(meta_line)"
echo "  the line reads: $LOCAL"
shot speedline-local
if [ -z "$LOCAL" ]; then
  fail "no meta line at all on a local file"
elif echo "$LOCAL" | grep -qE '[0-9]+(\.[0-9])? *(B|KB|MB)/s'; then
  fail "a local file is being given a speed" "$LOCAL"
else
  pass "a local file is given no speed"
fi

echo
echo "=== a stream is measured ==="
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d 'https://demo.unified-streaming.com/k8s/live/stable/scte35.isml/.m3u8' -t 'application/x-mpegURL' -n $ACT" >/dev/null 2>&1
for n in $(seq 1 20); do
  [ -n "$(playing)" ] && break
  sleep 2
done
sleep 5
refresh_screen
# Read it while the film is playing, not paused: a paused film with a full
# buffer is not downloading anything, and the honest answer then is no speed at
# all. The controls are brought up with the d-pad, which does not pause.
STREAM=""
for n in $(seq 1 16); do
  # The controls hide themselves after a few seconds and a screen dump takes
  # two of them, so they are asked for again before every attempt.
  key KEYCODE_DPAD_UP
  STREAM="$(meta_line)"
  [ -n "$STREAM" ] && echo "  the line reads: $STREAM"
  echo "$STREAM" | grep -qE '[0-9]+(\.[0-9])? *(B|KB|MB)/s' && break
  if [ "$n" = 3 ]; then
    # uiautomator will not describe a screen that never stops moving, and on
    # this engine a live stream is exactly that: the dumps come back empty. A
    # paused live stream still fills its cache, so the speed is still there to
    # be read, and the screen holds still long enough to be read from.
    echo "  (pausing so the screen can be read)"
    key KEYCODE_MEDIA_PAUSE
    sleep 2
  fi
  sleep 1
done
shot speedline-stream
if echo "$STREAM" | grep -qE '[0-9]+(\.[0-9])? *(B|KB|MB)/s'; then
  pass "the stream's speed is on the line"
else
  fail "no speed on the line for a stream" \
       "line: [$STREAM]  playing: [$(playing)]  on screen: $(grep -oE 'text="[^"]+"' "$SNAP" | head -6 | tr '\n' ' ')"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
