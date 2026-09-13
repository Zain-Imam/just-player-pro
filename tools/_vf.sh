#!/bin/bash
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
prepare
open_film
sleep 4
echo "playing: $(playing)"
echo "on screen: $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
shot diag-1
sleep 8
echo "8s later: $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
shot diag-2
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 6
echo "paused:   $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
shot diag-3
