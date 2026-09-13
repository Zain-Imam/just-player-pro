#!/bin/bash
# 2: can a remote reach the search icon on the info-card row?
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
focused_line() { dump | grep 'focused="true"' | tail -1 \
  | grep -oE '(text|content-desc|class)="[^"]*"' | tr '\n' ' '; }

prepare
open_film
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
echo "panel open. focus: $(focused_line)"
for n in $(seq 1 12); do
  key KEYCODE_DPAD_DOWN
  sleep 1
  f="$(focused_line)"
  echo "  down $n: $f"
  case "$f" in *"Show info card"*) break ;; esac
done
key KEYCODE_DPAD_RIGHT
sleep 1
echo "after right: $(focused_line)"
shot dpad-right
