#!/bin/bash
# The audio delay is reachable from the audio button, not only from the quick panel.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-audiopanel.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_film

echo "--- the audio button ---"
if ! tap_control 'Audio track' Audio 'Select audio track'; then
  fail "no audio button on the controls"
  exit 1
fi
sleep 2
snap
echo "  the list offers: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"
shot audiopanel-list

if grep -q '"Audio delay"' "$SNAP"; then
  pass "the audio list offers the delay"
else
  fail "the audio list has no delay row"
  exit 1
fi

echo "--- and it opens the control ---"
AT="$(centre text 'Audio delay')"
[ -z "$AT" ] && { fail "could not reach the delay row"; exit 1; }
tap $AT
sleep 3
snap
shot audiopanel-panel
echo "  the panel shows: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"
if grep -q '"Audio"' "$SNAP" && grep -q '"Audio delay"' "$SNAP"; then
  pass "the audio panel opened, with the delay in it"
else
  fail "the audio panel did not open"
  exit 1
fi

echo "--- and the arrows work there ---"
value() { snap; grep -A4 '"Audio delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g'; }
tenths() { echo "$1" | tr -d ' s' | awk -F. '{ v = ($1 < 0 || $0 ~ /^-/) ? -1 : 1; gsub(/[-+]/, "", $1); print v * ($1 * 10 + $2) }'; }
BEFORE="$(value)"
# Three presses, not one: the first key a freshly opened panel receives can be
# spent landing the focus on its first row.
key KEYCODE_DPAD_RIGHT
sleep 1
key KEYCODE_DPAD_RIGHT
sleep 1
key KEYCODE_DPAD_RIGHT
sleep 2
AFTER="$(value)"
echo "  $BEFORE -> $AFTER"
if [ -n "$AFTER" ] && [ "$(tenths "$AFTER")" -gt "$(tenths "$BEFORE")" ]; then
  pass "the delay moves from the audio panel"
else
  fail "the delay did not move" "$BEFORE -> $AFTER"
fi

echo "--- put it back ---"
for n in $(seq 1 20); do
  [ "$(tenths "$(value)")" = "$(tenths "$BEFORE")" ] && break
  key KEYCODE_DPAD_LEFT
  sleep 1
done
echo "  left at: $(value)"

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
