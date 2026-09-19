#!/bin/bash
# _vnextsetting.sh [on|off] -- the "play the next file automatically" switch
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
WANT="${1:-report}"
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}
is_on() { dump | grep -q 'the next one in the same folder starts'; }
prepare
open_settings
for n in $(seq 1 16); do
  AT="$(centre_like 'Play the next file automatically')"
  [ -n "$AT" ] && break
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
  sleep 1
done
[ -z "$AT" ] && { fail "no auto next row"; exit 1; }
if [ "$WANT" = "on" ] && ! is_on; then tap $AT; sleep 2; fi
if [ "$WANT" = "off" ] && is_on; then tap $AT; sleep 2; fi
if is_on; then STATE=on; else STATE=off; fi
if [ "$WANT" = "report" ] || [ "$STATE" = "$WANT" ]; then
  pass "the setting is $STATE"
else
  fail "the setting is $STATE, wanted $WANT"
fi
