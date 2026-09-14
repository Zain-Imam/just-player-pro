#!/bin/bash
# The Media3 audio delay is really applied, not merely displayed.
#
# Media3 has no audio delay of its own: the delay is the clock the audio
# renderer reports, and the picture follows that clock. So the delay is visible
# in the one place the clock is written down -- the elapsed time on the
# controls. Five seconds of delay is five seconds of clock that playback did not
# account for.
#
# Run with the engine set to Media3.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-audio3.txt"
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

# The elapsed time, in seconds.
position() {
  show_controls >/dev/null
  snap
  grep -F "resource-id=\"$PKG:id/exo_position\"" "$SNAP" | head -1 \
    | grep -oE 'text="[0-9:]+"' | sed 's/text=//;s/"//g' \
    | awk -F: '{ s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; print s }'
}
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
focus_row() {
  local n ry
  for n in $(seq 1 16); do
    snap
    if grep -q '"Audio delay"' "$SNAP"; then
      set -- $(bounds_of_text "Audio delay"); ry=$(( (${2:-0} + ${4:-0}) / 2 ))
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

echo "--- the engine under test ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
snap
ENGINE="$(grep -A2 -F '"Playback engine"' "$SNAP" | grep -oE 'text="(Media3|mpv|Auto)[^"]*"' | head -1 | sed 's/text=//;s/"//g')"
echo "  engine: $ENGINE"
case "$ENGINE" in
  Media3*) ;;
  *) fail "this one is for Media3" "the engine is $ENGINE"; exit 1 ;;
esac

echo "--- where the film thinks it is, with no delay ---"
key KEYCODE_BACK
sleep 2
BEFORE="$(position)"
echo "  position: ${BEFORE}s"

echo "--- five seconds of audio delay ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
focus_row || { fail "could not reach the audio delay row"; exit 1; }
for n in 1 2 3; do
  adb shell "input keyevent $(for i in $(seq 1 25); do printf 'KEYCODE_DPAD_RIGHT '; done)" >/dev/null 2>&1
  sleep 1
done
snap
SET="$(grep -A4 '"Audio delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g')"
echo "  set to:   $SET"
key KEYCODE_BACK
sleep 3

echo "--- play four seconds and look at the clock again ---"
key KEYCODE_MEDIA_PLAY
sleep 4
AFTER="$(position)"
echo "  position: ${AFTER}s"
shot audiodelay-media3-clock

MOVED=$(( AFTER - BEFORE ))
echo "  the clock moved ${MOVED}s over about four seconds of playing"
# Four of playing plus five of delay, less whatever the pauses cost. Seven is
# comfortably past anything four seconds of playback could account for.
if [ "$MOVED" -ge 7 ]; then
  pass "the delay reached the audio clock: ${MOVED}s where playing alone gives about 4"
else
  fail "the clock did not move by the delay" "${BEFORE}s -> ${AFTER}s"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

echo "--- put it back to nothing ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
focus_row >/dev/null
for n in $(seq 1 8); do
  adb shell "input keyevent $(for i in $(seq 1 25); do printf 'KEYCODE_DPAD_LEFT '; done)" >/dev/null 2>&1
  sleep 1
  snap
  NOW="$(grep -A4 '"Audio delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g')"
  case "$NOW" in *"- 5.0 s"*) break ;; esac
done
for n in $(seq 1 60); do
  snap
  NOW="$(grep -A4 '"Audio delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g')"
  case "$NOW" in "0.0 s") break ;; esac
  key KEYCODE_DPAD_RIGHT
done
echo "  left at: $NOW"
