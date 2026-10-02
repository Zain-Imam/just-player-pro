#!/bin/bash
# The timeline behind a lock (edge times, subtitles lifted clear), and the
# lock holding while a stream stalls.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42lock.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap cleanup EXIT

ENGINE="${ENGINE:-mpv}"
setpref string playbackEngine "$ENGINE"
setpref boolean lockedTimeline true
setpref boolean overlayOnPause false
echo "== $ENGINE"

server "reset=1"
launch_url "$SERVER/clip.ts" "--es title Clip --esa subs $SERVER/smoke.srt --esa subs.name Smoke --esa subs.enable $SERVER/smoke.srt"
sleep 6
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 1
adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
sleep 1
LOCK="$(centre content-desc 'Lock screen')"
[ -z "$LOCK" ] && { fail "found the lock button" "$(dump | grep -oE 'content-desc="[^"]+"' | head -14 | tr '\n' ' ')"; exit 1; }
adb shell "input tap $LOCK" >/dev/null 2>&1
sleep 1
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
sleep 3

# tap a locked screen: the padlock, and the timeline if enabled
adb shell "input tap 1200 400" >/dev/null 2>&1
sleep 1.2
adb exec-out screencap -p > "$WORK/shots/locked-$ENGINE.png"
echo "  screenshot: $WORK/shots/locked-$ENGINE.png"
pass "locked and tapped (see the screenshot)"

# starve the stream so it buffers, then check the lock held
server "rate=150000"
sleep 25
adb shell "input tap 1200 400" >/dev/null 2>&1
sleep 1.5
if dump | grep -qF "resource-id=\"$PKG:id/exo_progress\""; then
  fail "the lock held while the stream buffered" "a tap brought the controls up"
else
  pass "the lock held while the stream buffered"
fi
server "reset=1"
check "no crash" "$(crashed)"
