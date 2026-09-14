#!/bin/bash
# Holding the arrow moves the subtitle delay fast, and a single press still
# moves it by exactly one step.
#
# Driven through the remote path -- a held d-pad key -- because that is what a
# television has, and adb can hold a key honestly. The touch buttons share the
# same accelerating step.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap.txt"
snap() { dump > "$SNAP"; }
# The value sits in the row under the "Delay" title.
delay_now() { snap; grep -A4 '"Delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g'; }
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

prepare
open_film

echo "--- long press the subtitle button for the subtitle settings ---"
show_controls >/dev/null
AT="$(find_control 'Enable subtitles' 'Disable subtitles' Subtitles Subtitle)"
[ -z "$AT" ] && { fail "no subtitle button"; exit 1; }
set -- $AT
adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1
sleep 3
snap
if ! grep -q '"Delay"' "$SNAP"; then
  fail "the subtitle settings panel did not open"
  exit 1
fi
pass "the subtitle settings panel is open"

echo "--- put the focus on the Delay row ---"
found=0
for n in $(seq 1 10); do
  snap
  set -- $(bounds_of_text "Delay"); ry=$(( (${2:-0} + ${4:-0}) / 2 ))
  set -- $(focus_bounds)
  if [ $# -eq 4 ] && [ "$ry" -ge "$2" ] && [ "$ry" -le "$4" ] && [ $(( $4 - $2 )) -lt 300 ]; then
    echo "  on the Delay row after $n presses"
    found=1
    break
  fi
  key KEYCODE_DPAD_DOWN
  sleep 1
done
[ "$found" = 0 ] && { fail "never landed on the Delay row"; exit 1; }

# In tenths, signed, so the assertions are about how far it moved rather than
# where it happens to have started -- the delay is remembered per file and a
# previous run leaves it wherever it left it.
tenths() { echo "$1" | tr -d ' s' | awk -F. '{ v = ($1 < 0 || $0 ~ /^-/) ? -1 : 1; gsub(/[-+]/, "", $1); print v * ($1 * 10 + $2) }'; }

BEFORE="$(delay_now)"
echo "  delay before:    $BEFORE"

echo "--- one press should move exactly one step (0.1 s) ---"
key KEYCODE_DPAD_RIGHT
sleep 1
ONE="$(delay_now)"
echo "  after one press: $ONE"
MOVED=$(( $(tenths "$ONE") - $(tenths "$BEFORE") ))
if [ "$MOVED" = 1 ]; then
  pass "a single press is still one step"
else
  fail "a single press moved $MOVED tenths, not 1" "$BEFORE -> $ONE"
fi

# A real hold is a fast run of key events, which is what a d-pad sends while it
# is down. `input keyevent --longpress` is not that: it is one synthetic press.
# Twenty keycodes in a single call arrive back to back, which is the run.
echo "--- a run of 20, as a held arrow sends ---"
adb shell "input keyevent $(for i in $(seq 1 20); do printf 'KEYCODE_DPAD_RIGHT '; done)" >/dev/null 2>&1
sleep 1
HELD="$(delay_now)"
echo "  after the run:   $HELD"

# Twenty presses at the base step would move it 20 tenths and no more.
# Accelerating, it has to be well past that.
RUN=$(( $(tenths "$HELD") - $(tenths "$ONE") ))
echo "  the run moved:   $RUN tenths, for 20 presses"
if [ "$RUN" -gt 20 ]; then
  pass "the run accelerated: $RUN tenths where twenty plain steps give 20"
else
  fail "the run did not accelerate" "$RUN tenths for 20 presses"
fi
