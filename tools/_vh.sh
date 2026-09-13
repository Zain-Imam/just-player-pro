#!/bin/bash
# Put "Info card when paused" back on -- an earlier run in this session turned
# it off -- and read the background slider while we are there.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"

centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}
find_row() {
  local at n
  at="$(centre_like "$1")"
  [ -n "$at" ] && { echo "$at"; return 0; }
  for n in $(seq 1 12); do
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
    sleep 1
    at="$(centre_like "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  return 1
}

prepare
open_settings
AT="$(find_row 'Info card when paused')"
[ -z "$AT" ] && { fail "could not find the info card row"; exit 1; }
if dump | grep -q 'No card on pause'; then
  tap $AT
  sleep 2
  if dump | grep -q 'Looks the film up as it opens'; then
    pass "the info card is on again"
  else
    fail "the switch did not take"
  fi
else
  pass "the info card was already on"
fi
echo "slider now: $(dump | grep -A2 -F 'How solid the card is' | grep -oE 'text="[0-9]+"' | head -1)"
