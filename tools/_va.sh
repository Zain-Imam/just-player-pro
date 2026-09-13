#!/bin/bash
# 2: a remote reaching the search icon on the info-card row.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
SNAP="$WORK/snap.txt"
snap() { dump > "$SNAP"; }
bounds_from() { grep -F "$1" "$SNAP" | head -1 \
  | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
  | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'; }
focus_from() { grep 'focused="true"' "$SNAP" | tail -1 \
  | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
  | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'; }

prepare
open_film
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2

found=0
for n in $(seq 1 16); do
  snap
  set -- $(focus_from); fx1=${1:-0}; fy1=${2:-0}; fx2=${3:-0}; fy2=${4:-0}
  set -- $(bounds_from 'content-desc="Search again"'); ix1=${1:-}; iy1=${2:-}; ix2=${3:-}; iy2=${4:-}
  if [ -n "$ix1" ]; then
    icx=$(( (ix1 + ix2) / 2 )); icy=$(( (iy1 + iy2) / 2 ))
    if [ "$icy" -ge "$fy1" ] && [ "$icy" -le "$fy2" ] && [ $(( fy2 - fy1 )) -lt 300 ] && [ $(( fx2 - fx1 )) -gt 400 ]; then
      echo "  focus is on the info-card row after $n presses [$fx1 $fy1 $fx2 $fy2]"
      echo "  its button is at [$ix1 $iy1 $ix2 $iy2]"
      found=1
      break
    fi
  fi
  key KEYCODE_DPAD_DOWN
  sleep 1
done
[ "$found" = 0 ] && { fail "never landed on the info-card row"; exit 1; }

key KEYCODE_DPAD_RIGHT
sleep 1
snap
set -- $(focus_from)
echo "  after right: [$*]"
shot dpad-icon
if [ "$1" = "$ix1" ] && [ "$2" = "$iy1" ]; then
  pass "right moves onto the search icon itself"
else
  fail "right did not reach the search icon" "focus [$*], icon [$ix1 $iy1 $ix2 $iy2]"
fi
