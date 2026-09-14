#!/bin/bash
# The subtitle picker offers a file from storage, not only a search.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_film
if ! tap_control 'Disable subtitles' 'Enable subtitles' Subtitles Subtitle; then
  fail "no subtitle button"
  exit 1
fi
sleep 2
shot subrow
echo "the picker offers:"
dump | grep -oE 'text="[^"]+"' | sed 's/text=//;s/"//g' | head -14

if dump | grep -q 'Load a subtitle file'; then
  pass "a file from storage is offered"
else
  fail "no row for a file from storage"
fi
if dump | grep -q 'Search online subtitles'; then
  pass "searching online is still offered"
else
  fail "the online search row went missing"
fi
