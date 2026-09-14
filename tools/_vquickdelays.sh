#!/bin/bash
# The quick panel carries both delays, and moving one there moves the same
# number the other panels show.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-quickdelays.txt"
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
value_under() { snap; grep -A4 -F "text=\"$1\"" "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g'; }
tenths() { echo "$1" | tr -d ' s' | awk -F. '{ v = ($1 < 0 || $0 ~ /^-/) ? -1 : 1; gsub(/[-+]/, "", $1); print v * ($1 * 10 + $2) }'; }
bounds_of_text() {
  grep -F "text=\"$1\"" "$SNAP" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
focus_bounds() {
  grep 'focused="true"' "$SNAP" | tail -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
focus_row() {
  # The name is kept in a variable of its own: "set --" further down replaces
  # the positional arguments, and $1 would stop being the row halfway through.
  local want="$1" n ry
  for n in $(seq 1 16); do
    snap
    if grep -q "text=\"$want\"" "$SNAP"; then
      set -- $(bounds_of_text "$want"); ry=$(( (${2:-0} + ${4:-0}) / 2 ))
      set -- $(focus_bounds)
      if [ $# -eq 4 ] && [ "$ry" -ge "$2" ] && [ "$ry" -le "$4" ] && [ $(( $4 - $2 )) -lt 300 ]; then
        return 0
      fi
    fi
    key KEYCODE_DPAD_DOWN
    sleep 1
  done
  return 1
}
open_panel() {
  local n
  for n in 1 2 3; do
    tap_control Settings && break
    sleep 2
  done
  sleep 2
  snap
  grep -q '"Quick settings"' "$SNAP"
}

prepare
open_film

echo "--- the quick panel carries both ---"
open_panel || { fail "the quick settings panel did not open"; exit 1; }
shot quickdelays-panel
echo "  rows: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"
for row in 'Audio delay' 'Subtitle delay'; do
  if grep -qF "text=\"$row\"" "$SNAP"; then
    pass "the quick panel has: $row"
  else
    fail "the quick panel is missing: $row"
  fi
done

echo "--- moving the subtitle delay from there ---"
focus_row 'Subtitle delay' || { fail "could not reach the subtitle delay row"; exit 1; }
BEFORE="$(value_under 'Subtitle delay')"
key KEYCODE_DPAD_RIGHT
sleep 1
key KEYCODE_DPAD_RIGHT
sleep 2
AFTER="$(value_under 'Subtitle delay')"
echo "  $BEFORE -> $AFTER"
if [ -n "$AFTER" ] && [ "$(tenths "$AFTER")" -gt "$(tenths "$BEFORE")" ]; then
  pass "the subtitle delay moves from the quick panel"
else
  fail "the subtitle delay did not move" "$BEFORE -> $AFTER"
fi

echo "--- and the subtitle panel agrees ---"
key KEYCODE_BACK
sleep 2
show_controls >/dev/null
AT="$(find_control 'Enable subtitles' 'Disable subtitles' Subtitles Subtitle)"
[ -z "$AT" ] && { fail "no subtitle button"; exit 1; }
set -- $AT
adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1
sleep 3
IN_PANEL="$(value_under 'Delay')"
echo "  the subtitle panel says: $IN_PANEL"
if [ "$(tenths "$IN_PANEL")" = "$(tenths "$AFTER")" ]; then
  pass "both panels show the same number"
else
  fail "the two panels disagree" "quick: $AFTER, subtitle: $IN_PANEL"
fi

echo "--- put it back ---"
focus_row 'Delay' >/dev/null
for n in $(seq 1 20); do
  [ "$(tenths "$(value_under 'Delay')")" = "$(tenths "$BEFORE")" ] && break
  key KEYCODE_DPAD_LEFT
  sleep 1
done
echo "  left at: $(value_under 'Delay')"

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
