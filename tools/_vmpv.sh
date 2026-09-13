#!/bin/bash
# 3 and 5 on mpv.
#
# The frame cannot be measured here: on mpv the surface is given the whole
# player and mpv letterboxes inside it, so exo_content_frame is always the full
# window whatever shape the picture is. The pictures are the evidence, taken
# once mpv has had a moment to redraw.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_film
sleep 3
echo "engine: $(dump | grep -oE 'text="[^"]*mpv[^"]*"' | head -1)"
shot mpv-open

for n in $(seq 1 10); do
  show_controls >/dev/null
  AT="$(find_control Resize)"
  [ -z "$AT" ] && { echo "  press $n: out of reach"; break; }
  tap $AT
  sleep 3
  shot "mpv-step-$n"
  echo "  press $n captured"
done

echo "--- 3: through the settings screen and back, on 4:3 ---"
# Back to Default, then four presses to 4:3.
for n in 1 2 3 4; do
  show_controls >/dev/null
  AT="$(find_control Resize)"
  [ -z "$AT" ] && break
  tap $AT
  sleep 2
done
sleep 2
shot mpv-trip-before
CURRENT_SCREEN="$SETTINGS"
adb shell "am start -n $SETTINGS" >/dev/null 2>&1
sleep 5
CURRENT_SCREEN="$ACT"
key KEYCODE_BACK
sleep 9
shot mpv-trip-after
echo "focus: $(focused)"
