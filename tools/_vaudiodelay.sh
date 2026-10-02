#!/bin/bash
# The audio delay: in the quick panel, on either engine, remembered per file.
# Run once per engine; set it beforehand with _setengine.sh.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-audio.txt"
snap() { dump > "$SNAP"; }
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

# The value sits in the row under the "Audio delay" title.
delay_now() { snap; grep -A4 '"Audio delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g'; }
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
# signed tenths, so checks measure movement: the delay is kept per file
tenths() { echo "$1" | tr -d ' s' | awk -F. '{ v = ($1 < 0 || $0 ~ /^-/) ? -1 : 1; gsub(/[-+]/, "", $1); print v * ($1 * 10 + $2) }'; }

# uiautomator sometimes returns an empty dump, so ask again
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
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

# d-pad down the panel, as a TV does; it also scrolls the row into view
focus_row() {
  local n ry
  for n in $(seq 1 16); do
    snap
    if grep -q '"Audio delay"' "$SNAP"; then
      set -- $(bounds_of_text "Audio delay"); ry=$(( (${2:-0} + ${4:-0}) / 2 ))
      set -- $(focus_bounds)
      if [ $# -eq 4 ] && [ "$ry" -ge "$2" ] && [ "$ry" -le "$4" ] && [ $(( $4 - $2 )) -lt 300 ]; then
        echo "  on the Audio delay row after $n presses"
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

echo "--- the quick settings panel opens ---"
if open_panel; then
  pass "the quick settings panel is open"
else
  fail "the quick settings panel did not open" \
       "rows: $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  exit 1
fi
shot audiodelay-panel

echo "--- the remote can reach the audio delay ---"
if focus_row; then
  pass "the panel offers an audio delay, reachable from the remote"
else
  fail "the focus never landed on an audio delay row" \
       "rows: $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  exit 1
fi

BEFORE="$(delay_now)"
echo "  delay before:    $BEFORE"

echo "--- one press is one step (0.1 s), later ---"
key KEYCODE_DPAD_RIGHT
sleep 1
ONE="$(delay_now)"
echo "  after one press: $ONE"
MOVED=$(( $(tenths "$ONE") - $(tenths "$BEFORE") ))
if [ "$MOVED" = 1 ]; then
  pass "a single press moves one step"
else
  fail "a single press moved $MOVED tenths, not 1" "$BEFORE -> $ONE"
fi

echo "--- and the other way ---"
key KEYCODE_DPAD_LEFT
key KEYCODE_DPAD_LEFT
sleep 2
BACK="$(delay_now)"
echo "  after two lefts: $BACK"
MOVED=$(( $(tenths "$BACK") - $(tenths "$ONE") ))
if [ "$MOVED" = -2 ]; then
  pass "the sound moves earlier as well as later"
else
  fail "two presses left moved $MOVED tenths, not -2" "$ONE -> $BACK"
fi

echo "--- a held arrow accelerates, as the subtitle delay does ---"
adb shell "input keyevent $(for i in $(seq 1 20); do printf 'KEYCODE_DPAD_RIGHT '; done)" >/dev/null 2>&1
sleep 2
HELD="$(delay_now)"
echo "  after the run:   $HELD"
RUN=$(( $(tenths "$HELD") - $(tenths "$BACK") ))
echo "  the run moved:   $RUN tenths, for 20 presses"
if [ "$RUN" -gt 20 ]; then
  pass "the run accelerated: $RUN tenths where twenty plain steps give 20"
else
  fail "the run did not accelerate" "$RUN tenths for 20 presses"
fi

echo "--- it stops at five seconds ---"
for n in 1 2 3 4; do
  adb shell "input keyevent $(for i in $(seq 1 25); do printf 'KEYCODE_DPAD_RIGHT '; done)" >/dev/null 2>&1
  sleep 1
done
sleep 1
LIMIT="$(delay_now)"
echo "  at the end:      $LIMIT"
if [ "$(tenths "$LIMIT")" = 50 ]; then
  pass "the delay stops at + 5.0 s"
else
  fail "the delay ran past five seconds" "$LIMIT"
fi

# a debug build logs what mpv holds for audio-delay;
# "-> null" means mpv has no such property
echo "--- what the engine did with the number ---"
READBACK="$(adb logcat -d 2>/dev/null | grep -oE 'mpv audio-delay = [-0-9.]+ -> [^ ]+' | tail -1 | tr -d '\r')"
if [ -n "$READBACK" ]; then
  echo "  mpv says:        $READBACK"
  case "$READBACK" in
    *"-> null"*) fail "mpv would not take the audio delay" "$READBACK" ;;
    *)           pass "mpv holds the audio delay it was given" ;;
  esac
else
  echo "  (nothing to read back -- either Media3, or mpv kept it verbatim)"
fi

echo "--- and the film is still playing ---"
key KEYCODE_BACK
sleep 2
key KEYCODE_MEDIA_PLAY
sleep 6
if [ -n "$(playing)" ]; then
  pass "playback survived the delay"
else
  fail "the film is not playing after the delay was applied" \
       "state: $(adb shell 'dumpsys media_session | grep -o "state=[A-Z]*" | head -1' | tr -d '\r')"
fi
shot audiodelay-playing
if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

echo "--- set it to a tenth and reopen the file ---"
open_panel >/dev/null
focus_row >/dev/null
adb shell "input keyevent $(for i in $(seq 1 4); do printf 'KEYCODE_DPAD_LEFT '; done)" >/dev/null 2>&1
sleep 2
SET="$(delay_now)"
echo "  left at:         $SET"
key KEYCODE_BACK
sleep 2
open_film
open_panel >/dev/null
# on a phone the row starts below the fold
focus_row >/dev/null
AGAIN="$(delay_now)"
echo "  on reopening:    $AGAIN"
if [ "$(tenths "$AGAIN")" = "$(tenths "$SET")" ] && [ -n "$AGAIN" ]; then
  pass "the file kept its audio delay"
else
  fail "the audio delay was not remembered" "$SET -> $AGAIN"
fi

echo "--- put it back to nothing ---"
focus_row >/dev/null
for n in $(seq 1 60); do
  [ "$(tenths "$(delay_now)")" = 0 ] && break
  if [ "$(tenths "$(delay_now)")" -gt 0 ]; then key KEYCODE_DPAD_LEFT; else key KEYCODE_DPAD_RIGHT; fi
done
sleep 1
echo "  left at:         $(delay_now)"
