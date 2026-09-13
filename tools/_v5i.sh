#!/bin/bash
# Item 4, the other direction: put back together, one title governs both.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
has() { dump | grep -q "$1"; }
wait_for() { local n; for n in $(seq 1 "${2:-10}"); do has "$1" && return 0; sleep 2; done; return 1; }

prepare
open_settings
AT="$(scroll_to 'One title for both')"
tap $AT
sleep 1
if dump | grep -A3 'One title for both' | grep -q 'Correcting either corrects both'; then
  pass "the setting is back on, which is the default"
else
  fail "could not put the setting back"
fi

open_film
pause_player
sleep 5
shot linked-card
if has 'text="Inception (2010)"'; then
  pass "linked again, the card is back to the film's own title"
else
  fail "the card did not follow the shared title" \
       "$(dump | grep -oE 'text="[^"]+"' | head -3 | tr '\n' ' ')"
fi
