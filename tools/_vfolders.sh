#!/bin/bash
# The folder list exists, says what it is for, and offers to add one.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
prepare
open_settings
AT="$(scroll_to 'Folders the player may read')"
if [ -z "$AT" ]; then
  fail "no folders entry in settings"
  exit 1
fi
pass "the folders entry is in settings"
echo "  summary: $(dump | grep -A2 'Folders the player may read' | grep -oE 'text="[^"]+"' | head -2 | tr '\n' ' ')"
tap $AT
sleep 3
shot folders-screen
echo "the screen shows:"
dump | grep -oE 'text="[^"]+"' | sed 's/text=//;s/"//g' | head -10
if dump | grep -q 'Add a folder'; then
  pass "it offers to add one"
else
  fail "no way to add a folder"
fi
