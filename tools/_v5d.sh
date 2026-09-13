#!/bin/bash
# Item 1, second half: "Search again" sits at the top of the results.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
long_press() { require_player; adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1; }

prepare
open_film
show_controls >/dev/null
long_press $(find_control "Open file" Open "Open…")
sleep 2
tap $(centre text 'Search online subtitles…')
sleep 3

FIELD="$(centre class android.widget.EditText)"
[ -z "$FIELD" ] && { echo "no input box"; exit 1; }
tap $FIELD
adb shell "input keyevent KEYCODE_MOVE_END" >/dev/null 2>&1
for n in 1 2 3 4 5 6 7 8; do adb shell "input keyevent KEYCODE_DEL" >/dev/null 2>&1; done
adb shell "input text 'Batman%sBegins'" >/dev/null 2>&1
sleep 1
tap $(centre text 'SEARCH')
sleep 7
shot posters

PICK="$(centre text 'Batman Begins')"
[ -z "$PICK" ] && { echo "no Batman Begins row"; dump | grep -oE 'text="[^"]+"' | head; exit 1; }
echo "picking Batman Begins at $PICK"
tap $PICK
sleep 10
shot results
echo "--- the results list ---"
dump | grep -oE 'text="[^"]+"' | head -14
