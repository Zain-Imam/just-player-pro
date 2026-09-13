#!/bin/bash
# 3: the shape survives a trip to the settings screen.
# 4: every step has its own icon.
# 5: the first step is Default, and a forced ratio does not follow a new file.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
picture() { bounds_of resource-id "$PKG:id/exo_content_frame"; }

prepare
open_film
echo "== 5. a new file opens at its own shape =="
echo "  on opening: [$(picture)]"
shot asp-open

echo
echo "== 4/5. round the cycle, naming each step =="
for n in $(seq 1 10); do
  tap_control Resize >/dev/null 2>&1
  sleep 1
  label="$(dump | grep -oE 'text="(Default|Crop|Stretch|16:9|4:3|16:10|2:1|2\.35:1|2\.39:1|5:4)"' | head -1)"
  echo "  press $n: $label  picture [$(picture)]"
  shot "asp-step-$n"
done
