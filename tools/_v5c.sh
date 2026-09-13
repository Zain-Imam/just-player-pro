#!/bin/bash
# Item 1: with automatic search off, the button must ask which film first.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
long_press() { require_player; adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1; }

prepare
open_film
show_controls >/dev/null
AT="$(find_control "Open file" Open "Open…")"
if [ -z "$AT" ]; then
  echo "buttons: $(dump | grep -oE 'content-desc="[^"]+"' | sort -u | tr '\n' ' ')"
  exit 1
fi
echo "open button at $AT"
long_press $AT
sleep 2
shot sources
dump | grep -oE 'text="[^"]+"' | head -20

echo "--- choosing 'Search online subtitles…' ---"
AT2="$(centre text 'Search online subtitles…')"
[ -z "$AT2" ] && { echo "row not found"; exit 1; }
tap $AT2
sleep 3
shot asked
echo "what came up:"
dump | grep -oE '(text|class)="[^"]+"' | grep -E 'EditText|text="' | head -20
