#!/bin/bash
# 3: from the player, into All settings, and back.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
picture() { bounds_of resource-id "$PKG:id/exo_content_frame"; }

prepare
open_film
echo "on opening:   $(picture)"
for n in 1 2 3; do tap_control Resize >/dev/null 2>&1; sleep 1; done
sleep 2
shot asp-before
echo "after 3:      $(picture)"

echo "--- quick panel, All settings…, then back ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
AT="$(centre text 'All settings…')"
if [ -z "$AT" ]; then
  AT="$(scroll_to 'All settings…')"
fi
[ -z "$AT" ] && { echo "could not find All settings"; exit 1; }
CURRENT_SCREEN="$SETTINGS"
tap $AT
sleep 4
echo "now in: $(focused)"
CURRENT_SCREEN="$ACT"
key KEYCODE_BACK
sleep 6
echo "back in: $(focused)"
shot asp-after
echo "after back:   $(picture)"
tap_control Resize >/dev/null 2>&1
sleep 2
shot asp-next
echo "one more:     $(picture)"
