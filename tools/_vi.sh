#!/bin/bash
# What the identify dialog actually does, one step at a time.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
texts() { dump | grep -oE 'text="[^"]*"' | tr '\n' ' '; }

prepare
open_film
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
tap $(centre content-desc 'Search again')
sleep 3
echo "1 opened:  $(texts)"
shot dlg-1

CLEAR="$(centre text 'CLEAR')"
echo "   clear at: $CLEAR"
tap $CLEAR
sleep 2
echo "2 cleared: $(texts)"
shot dlg-2

adb shell "input text 'Inception'" >/dev/null 2>&1
sleep 2
echo "3 typed:   $(texts)"
shot dlg-3

adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 2
echo "4 keyboard away: $(texts)"
shot dlg-4
GO="$(centre text 'SEARCH')"
echo "   search at: $GO"
tap $GO
sleep 4
shot dlg-5a
sleep 6
echo "5 searched: $(texts)"
shot dlg-5
