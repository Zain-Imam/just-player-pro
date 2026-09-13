#!/bin/bash
# Item 5: the rotation button offers landscape, portrait and auto-rotate.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"

shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
held() {
  set -- $(dump | grep -m1 -oE 'bounds="\[0,0\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/.*\[0,0\]\[([0-9]+),([0-9]+)\].*/\1 \2/')
  if [ "${1:-0}" -gt "${2:-0}" ]; then echo landscape; else echo portrait; fi
}

prepare
open_film
echo "opened: $(held)"

# Find the button once, then press it without a dump in between: the label it
# puts on screen only stays for a couple of seconds and a dump takes longer.
AT="$(find_control Rotate)"
if [ -z "$AT" ]; then
  show_controls
  row="$(bounds_of resource-id "$PKG:id/controls_scroll_view")"
  y="$(echo "$row" | awk '{print int(($2 + $4) / 2)}')"
  for n in 1 2 3 4 5; do
    swipe $((SCREEN_W - 60)) "$y" 100 "$y" 250
    sleep 1
    AT="$(find_control Rotate)"
    [ -n "$AT" ] && break
  done
fi
[ -z "$AT" ] && { fail "the rotate button was not found"; exit 1; }
echo "rotate button at $AT"

for n in 1 2 3 4; do
  show_controls >/dev/null
  AT="$(find_control Rotate)"
  [ -z "$AT" ] && { echo "  press $n: button moved out of reach"; break; }
  tap $AT
  shot "rotate-$n"
  sleep 3
  echo "  press $n -> $(held)"
done
