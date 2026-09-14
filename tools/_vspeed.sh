#!/bin/bash
# A file opens at the speed it was last watched at.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-speed.txt"
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

speed_now() { snap; grep -A4 '"Speed"' "$SNAP" | grep -oE 'text="(Normal|[0-9.]+×)"' | head -1 | sed 's/text=//;s/"//g'; }
bounds_of_text() {
  grep -F "text=\"$1\"" "$SNAP" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
focus_bounds() {
  grep 'focused="true"' "$SNAP" | tail -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
position() {
  show_controls >/dev/null
  snap
  grep -F "resource-id=\"$PKG:id/exo_position\"" "$SNAP" | head -1 \
    | grep -oE 'text="[0-9:]+"' | sed 's/text=//;s/"//g' \
    | awk -F: '{ s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; print s }'
}
open_panel() {
  local n
  for n in 1 2 3; do
    tap_control Settings && break
    sleep 2
  done
  sleep 2
  snap
  grep -q '"Quick settings"' "$SNAP"
}
focus_speed() {
  local n ry
  for n in $(seq 1 12); do
    snap
    if grep -q '"Speed"' "$SNAP"; then
      set -- $(bounds_of_text "Speed"); ry=$(( (${2:-0} + ${4:-0}) / 2 ))
      set -- $(focus_bounds)
      if [ $# -eq 4 ] && [ "$ry" -ge "$2" ] && [ "$ry" -le "$4" ] && [ $(( $4 - $2 )) -lt 300 ]; then
        return 0
      fi
    fi
    key KEYCODE_DPAD_DOWN
    sleep 1
  done
  return 1
}

prepare
open_film

echo "--- one and a half times ---"
open_panel || { fail "the quick settings panel did not open"; exit 1; }
focus_speed || { fail "could not reach the speed row"; exit 1; }
echo "  speed before: $(speed_now)"
key KEYCODE_DPAD_RIGHT
sleep 1
key KEYCODE_DPAD_RIGHT
sleep 2
SET="$(speed_now)"
echo "  speed set to: $SET"
if [ "$SET" = "1.5×" ]; then
  pass "the speed can be set from the panel"
else
  fail "the speed did not reach 1.5×" "$SET"
fi
key KEYCODE_BACK
sleep 2

#
# Measured at half speed rather than at one and a half.
#
# The smoke clip is 1080p60, and asking for one and a half times that is 90
# frames a second through the decoder and onto a 60Hz panel. Media3 keeps the
# clock and drops what it cannot draw; mpv holds the picture and the sound
# together, so it plays what the hardware can and no faster -- which is right,
# and means the rate says nothing about whether the speed was set. Half speed
# is 30 frames a second, which every device here manages, so it measures the
# speed control instead of the panel.
#
echo "--- set it to half speed ---"
open_panel || { fail "the quick settings panel did not open"; exit 1; }
focus_speed || { fail "could not reach the speed row"; exit 1; }
for n in $(seq 1 8); do
  [ "$(speed_now)" = "0.5×" ] && break
  key KEYCODE_DPAD_LEFT
  sleep 1
done
echo "  speed now: $(speed_now)"
key KEYCODE_BACK
sleep 2

echo "--- and the film really runs at it ---"
BEFORE="$(position)"
key KEYCODE_MEDIA_PLAY
sleep 15
AFTER="$(position)"
MOVED=$(( AFTER - BEFORE ))  # the resume and the pause each cost a moment of the window
echo "  ${BEFORE}s -> ${AFTER}s over about fifteen seconds"
if [ "$MOVED" -le 10 ] && [ "$MOVED" -ge 4 ]; then
  pass "fifteen seconds of watching moved the film ${MOVED}s, about half"
else
  fail "the film did not run at half speed" "${MOVED}s over fifteen seconds"
fi
shot speed-running

echo "--- back to one and a half, which is what gets remembered ---"
open_panel || { fail "the quick settings panel did not open"; exit 1; }
focus_speed || { fail "could not reach the speed row"; exit 1; }
for n in $(seq 1 8); do
  [ "$(speed_now)" = "$SET" ] && break
  key KEYCODE_DPAD_RIGHT
  sleep 1
done
echo "  speed now: $(speed_now)"
key KEYCODE_BACK
sleep 2

echo "--- close it and open it again ---"
key KEYCODE_BACK
sleep 2
open_film
open_panel || { fail "the quick settings panel did not open"; exit 1; }
focus_speed >/dev/null
AGAIN="$(speed_now)"
echo "  on reopening: $AGAIN"
if [ "$AGAIN" = "$SET" ]; then
  pass "the file kept its speed"
else
  fail "the speed was not remembered" "$SET -> $AGAIN"
fi
shot speed-remembered

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

echo "--- put it back to normal ---"
focus_speed >/dev/null
for n in $(seq 1 8); do
  [ "$(speed_now)" = "Normal" ] && break
  key KEYCODE_DPAD_LEFT
  sleep 1
done
echo "  left at: $(speed_now)"
