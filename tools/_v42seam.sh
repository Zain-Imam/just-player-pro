#!/bin/bash
# On mpv, a far tap while the server is busy, then a tap back, must not jump
# to the far point later.
# Usage: PKG=<package> tools/_v42seam.sh      (engine must already be mpv)

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap cleanup EXIT

DURATION_MS=888000
server "reset=1"
server "rate=2500000"
launch_url "$SERVER/sintel.mkv" "--ei position 60000 --es title Sintel" || { fail "opened"; exit 1; }
sleep 12
# uiautomator cannot read a playing film, so measure the bar once while paused
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 1
adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
sleep 1
BAR="$(adb shell "uiautomator dump /sdcard/jpp-ui.xml >/dev/null 2>&1; cat /sdcard/jpp-ui.xml" \
    | tr '<' '\n' | grep -F "resource-id=\"$PKG:id/exo_progress\"" \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')"
echo "seek bar at: $BAR"
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
sleep 12
P0="$(position_ms)"
echo "playing at ${P0}ms"

server "holdFrom=0.85&slowRate=${SLOW:-400000}&refuseWhileHeld=1"

# Controls up, then the accidental tap and the tap back.
W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1 | tr -d '\r')"
# A key only ever shows the controls; a tap can hide them.
[ -z "$BAR" ] && { fail "found the seek bar"; exit 1; }
adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
sleep 0.8
set -- $BAR
# bounds include touch margin and inset; the line is 26px up, 6% in from each end
BX1=$(( $1 + ($3 - $1) * 6 / 100 )); BY=$(( $4 - 26 )); BW=$(( ($3 - $1) * 88 / 100 ))
P0="$(position_ms)"
BACK_SHARE=$(( (P0 + 4000) * 1000 / DURATION_MS ))
adb shell "input tap $(( BX1 + BW * 920 / 1000 )) $BY" >/dev/null 2>&1
sleep 1.2
adb shell "input tap $(( BX1 + BW * BACK_SHARE / 1000 )) $BY" >/dev/null 2>&1
echo "tapped 92%, then back at $((BACK_SHARE / 10)).$((BACK_SHARE % 10))%"

LAST=""
JUMPED=0
FARTHEST=0
for i in $(seq 1 ${SAMPLES:-45}); do
  sleep 2
  # The busy server recovers after a while, as a real one does.
  [ $i -eq ${RECOVER_AT:-20} ] && server "refuseWhileHeld=0"
  NOW="$(position_ms)"
  [ -z "$NOW" ] && continue
  [ $((i % 5)) -eq 0 ] && echo "  t=$((i*2))s pos=${NOW}ms $(adb shell dumpsys media_session | tr -d "" | grep -A12 "package=$PKG$" | grep -m1 -oE "state=[A-Z_]+[(]" )"
  if [ -n "$LAST" ] && [ $((NOW - LAST)) -gt 60000 ]; then
    echo "  jump at sample $i: ${LAST}ms -> ${NOW}ms"
    JUMPED=$((JUMPED + 1))
  fi
  [ "$NOW" -gt "$FARTHEST" ] && FARTHEST=$NOW
  LAST="$NOW"
done
echo "position at the end: ${LAST}ms (farthest ${FARTHEST}ms)"
curl -s "$SERVER/log" | tail -25
player_log 25 | grep -iE "seek|jump|drop|landed" | tail -12

server "reset=1"
if [ $JUMPED -eq 0 ] && [ "${LAST:-0}" -lt $((DURATION_MS * 80 / 100)) ]; then
  pass "no jump to the far point after tapping back"
else
  fail "playback jumped to the far point ($JUMPED jumps, ended at ${LAST}ms)"
fi
