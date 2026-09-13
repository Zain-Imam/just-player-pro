#!/bin/bash
# The label each orientation press puts on screen.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
reach_rotate() {
  show_controls >/dev/null
  local at row y n
  at="$(find_control Rotate)"
  [ -n "$at" ] && { echo "$at"; return 0; }
  row="$(bounds_of resource-id "$PKG:id/controls_scroll_view")"
  [ -z "$row" ] && return 1
  y="$(echo "$row" | awk '{print int(($2 + $4) / 2)}')"
  for n in 1 2 3 4 5; do
    swipe $((SCREEN_W - 60)) "$y" 100 "$y" 250
    sleep 1
    at="$(find_control Rotate)"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  return 1
}
prepare
open_film
for n in 1 2 3; do
  AT="$(reach_rotate)" || { echo "press $n: out of reach"; break; }
  tap $AT
  sleep 1
  shot "label-$n"
  # The label is the player's own message view, so it is in the dump too if it
  # is still up when uiautomator gets there.
  echo "press $n: $(dump | grep -oE 'text="(Landscape|Portrait|Auto-rotate)"' | head -1)"
  sleep 3
done
