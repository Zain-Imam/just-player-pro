#!/bin/bash
# _setengine.sh <Auto|Media3|mpv>
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
WANT="${1:-Auto}"
centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}
prepare
open_settings
for n in $(seq 1 14); do
  AT="$(centre_like 'Playback engine')"
  [ -n "$AT" ] && break
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
  sleep 1
done
[ -z "$AT" ] && { fail "no playback engine row"; exit 1; }
tap $AT
sleep 3
PICK="$(centre_like "$WANT")"
[ -z "$PICK" ] && { fail "no choice called $WANT"; dump | grep -oE 'text="[^"]+"' | head -8; exit 1; }
tap $PICK
sleep 3
if dump | grep -A2 -F 'Playback engine' | grep -q "$WANT"; then
  pass "the engine is $WANT"
else
  fail "the engine did not change" "$(dump | grep -A2 -F 'Playback engine' | grep -oE 'text="[^"]+"' | head -2 | tr '\n' ' ')"
fi
