#!/bin/bash
# Item 4: the setting that keeps the card's title and the subtitle title apart.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_settings
AT="$(scroll_to 'One title for both')"
if [ -z "$AT" ]; then
  fail "the new setting is not on the settings screen"
  exit 1
fi
pass "the setting is on the settings screen"
shot setting
dump | grep -B2 -A2 'One title for both' | grep -oE 'text="[^"]+"' | head -4
