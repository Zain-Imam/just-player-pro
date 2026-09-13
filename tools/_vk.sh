#!/bin/bash
# 7: the info card background follows the slider.  _vk.sh <0..100>
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
WANT="${1:-50}"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}

prepare
open_settings
for n in $(seq 1 14); do
  AT="$(centre_like 'How solid the card is')"
  [ -n "$AT" ] && break
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
  sleep 1
done
[ -z "$AT" ] && { fail "no background slider on the settings screen"; exit 1; }

# The bar itself, dragged to where the value should be.
set -- $(bounds_of class android.widget.SeekBar)
if [ $# -ne 4 ]; then
  set -- $(dump | grep -F 'SeekBar' | tail -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')
fi
[ $# -ne 4 ] && { fail "could not find the slider"; exit 1; }
X1=$1; Y1=$2; X2=$3; Y2=$4
MID=$(( (Y1 + Y2) / 2 ))
TO=$(( X1 + (X2 - X1) * WANT / 100 ))
echo "slider [$X1 $Y1 $X2 $Y2] -> dragging to $TO for $WANT%"
swipe $(( (X1 + X2) / 2 )) "$MID" "$TO" "$MID" 600
sleep 2
echo "reads: $(dump | grep -A3 -F 'How solid the card is' | grep -oE 'text="[0-9]+"' | head -1)"
shot "slider-$WANT-settings"

open_film
sleep 3
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 7
shot "card-at-$WANT"
echo "card on screen: $(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
