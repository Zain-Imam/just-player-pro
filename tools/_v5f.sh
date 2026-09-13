#!/bin/bash
# The chosen title survives reopening (item 3), and the undo offer in the real
# player goes after three seconds (item 2).
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
seen() { dump | grep -q "text=\"$1\""; }

prepare
open_film

echo "--- 3. the card after reopening the file ---"
pause_player
sleep 5
shot reopened-card
if seen "Inception (2010)"; then
  pass "the hand-picked title is still on the card after reopening"
else
  fail "the card lost the hand-picked title" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi

echo
echo "--- 2. the undo offer in the player itself ---"
play_player 2>/dev/null || tap_control "Play" >/dev/null 2>&1
sleep 1
# Make sure something is running: the skip button only shows over a playing film.
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
sleep 3
for n in $(seq 1 12); do
  seen "Skip intro" && break
  sleep 2
done
if ! seen "Skip intro"; then
  fail "the skip offer never appeared" "no segment for this identity"
else
  pass "the skip offer appeared from the online markers"
  AT="$(centre text 'Skip intro')"
  tap $AT
  START=$(date +%s%N)
  shot undo-0
  if seen "Undo skip"; then
    T=$(( ($(date +%s%N) - START) / 1000000 ))
    pass "undo is offered right after the skip (seen at ${T}ms)"
  else
    fail "no undo offer after skipping"
  fi
  # Look again once the window must have closed.
  while [ $(( ($(date +%s%N) - START) / 1000000 )) -lt 4200 ]; do sleep 0.3; done
  shot undo-late
  T=$(( ($(date +%s%N) - START) / 1000000 ))
  if seen "Undo skip"; then
    fail "undo is still offered at ${T}ms" "it should go after 3000ms"
  else
    pass "undo has gone by ${T}ms"
  fi
fi
