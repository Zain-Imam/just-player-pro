#!/bin/bash
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
prepare
open_settings
echo "focus: $(focused)"
dump | grep -oE 'text="[^"]+"' | head -30
echo "--- scrolling down ---"
for n in 1 2 3 4 5 6 7 8; do
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 35 / 100)) 700
  sleep 1
done
dump | grep -oE 'text="[^"]+"' | head -30
