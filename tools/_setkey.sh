#!/bin/bash
# Put a key back into a settings row: _setkey.sh "<row label>" "<value>"
#
# The value is typed, never echoed. The keyboard is put away before the dialog
# buttons are pressed, because it is drawn over them in a window the screen dump
# does not show -- so a tap at the button's own coordinates lands on a letter.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"

ROW="$1"
VALUE="$2"

centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}

prepare
open_settings
for n in $(seq 1 14); do
  AT="$(centre_like "$ROW")"
  [ -n "$AT" ] && break
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
  sleep 1
done
[ -z "$AT" ] && { fail "no row called $ROW"; exit 1; }
tap $AT
sleep 3

FIELD="$(centre class android.widget.EditText)"
[ -z "$FIELD" ] && { fail "no box to type in"; exit 1; }
tap $FIELD
sleep 1
CLEAR="$(centre text 'CLEAR')"
[ -n "$CLEAR" ] && { tap $CLEAR; sleep 1; tap $FIELD; sleep 1; }
adb shell "input text '$VALUE'" >/dev/null 2>&1
sleep 2
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 2
OK="$(centre text 'OK')"
[ -z "$OK" ] && { fail "no OK button"; exit 1; }
tap $OK
sleep 3
if dump | grep -q "$ROW"; then
  pass "$ROW set"
else
  fail "$ROW did not stick"
fi
