#!/bin/bash
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
has() { dump | grep -q "$1"; }
prepare
open_film
sleep 3
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 6
shot linked-card
if has 'text="Inception (2010)"'; then
  pass "linked again, the card is back to the film's own title"
else
  fail "the card is not showing the shared title" \
       "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi
