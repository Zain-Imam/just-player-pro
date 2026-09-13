#!/bin/bash
# Item 4, the behaviour: with the titles kept apart, changing the card must
# leave the film alone -- which is visible in the skip markers, since those
# follow the film's own title and not the card's.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
seen() { dump | grep -q "text=\"$1\""; }
wait_for() { local n; for n in $(seq 1 "${2:-10}"); do seen "$1" && return 0; sleep 2; done; return 1; }

echo "--- turning the titles apart ---"
prepare
open_settings
AT="$(scroll_to 'One title for both')"
[ -z "$AT" ] && { fail "setting missing"; exit 1; }
tap $AT
sleep 1
if dump | grep -A3 'One title for both' | grep -q 'Kept apart'; then
  pass "the setting turned off and says what that means"
else
  fail "the summary did not change" "$(dump | grep -A3 'One title for both' | grep -oE 'text="[^"]+"' | head -3 | tr '\n' ' ')"
fi

echo
echo "--- the markers before the card is changed ---"
open_film
if wait_for "Skip intro" 12; then
  pass "the film's own markers are loaded"
else
  fail "no skip markers to start from"
  exit 1
fi

echo
echo "--- changing only the card ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
AT="$(centre content-desc 'Search again')"
[ -z "$AT" ] && { fail "no search icon"; exit 1; }
tap $AT
sleep 3
FIELD="$(centre class android.widget.EditText)"
tap $FIELD
adb shell "input keyevent KEYCODE_MOVE_END" >/dev/null 2>&1
for n in $(seq 1 14); do adb shell "input keyevent KEYCODE_DEL" >/dev/null 2>&1; done
adb shell "input text 'Batman%sBegins'" >/dev/null 2>&1
sleep 1
tap $(centre text 'SEARCH')
sleep 7
PICK="$(centre text 'Batman Begins')"
[ -z "$PICK" ] && { fail "no Batman Begins to pick"; exit 1; }
tap $PICK
sleep 5
shot apart-card
if seen "Batman Begins"; then
  pass "the card shows the title chosen for it"
else
  fail "the card did not take the new title" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi

echo
echo "--- and the film is untouched ---"
# Back to playing, where the skip offer lives.
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
sleep 2
if wait_for "Skip intro" 10; then
  pass "the film's markers survived a change to the card alone"
else
  fail "changing the card took the film's markers with it"
fi
shot apart-markers
