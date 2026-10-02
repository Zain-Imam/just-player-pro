#!/bin/bash
# Each resize step shows its name and its own icon.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_film
# no dump between finding and pressing: the label lasts about a second
tap_control Resize >/dev/null 2>&1
sleep 2
for n in 2 3 4 5 6 7 8 9 10; do
  show_controls >/dev/null
  AT="$(find_control Resize)"
  [ -z "$AT" ] && { echo "  press $n: button out of reach"; break; }
  tap $AT
  shot "label-asp-$n"
  sleep 2
done
echo "shots written to $WORK/shots"
