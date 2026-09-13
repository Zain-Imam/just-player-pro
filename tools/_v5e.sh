#!/bin/bash
# Item 3: a title chosen by hand on the info card wins, and keeps winning.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_film
for try in 1 2 3; do tap_control Settings && break; echo "  (retry  for the settings button)"; sleep 2; done
sleep 2
AT="$(centre content-desc 'Search again')"
[ -z "$AT" ] && { echo "no search icon on the info card row"; exit 1; }
echo "search icon at $AT"
tap $AT
sleep 3

FIELD="$(centre class android.widget.EditText)"
[ -z "$FIELD" ] && { echo "no input box"; dump | grep -oE 'text="[^"]+"' | head; exit 1; }
tap $FIELD
adb shell "input keyevent KEYCODE_MOVE_END" >/dev/null 2>&1
for n in $(seq 1 12); do adb shell "input keyevent KEYCODE_DEL" >/dev/null 2>&1; done
adb shell "input text 'Inception'" >/dev/null 2>&1
sleep 1
tap $(centre text 'SEARCH')
sleep 7
shot pick
PICK="$(centre text 'Inception')"
[ -z "$PICK" ] && { echo "no Inception row"; dump | grep -oE 'text="[^"]+"' | head; exit 1; }
tap $PICK
sleep 5
shot card-after-choice
echo "--- the card now ---"
dump | grep -oE 'text="[^"]+"' | head -8

echo
echo "--- and after closing and opening the file again ---"
open_film
# The card comes up on a pause, which is what show_controls does.
show_controls >/dev/null
sleep 4
shot card-after-reopen
dump | grep -oE 'text="[^"]+"' | head -8
