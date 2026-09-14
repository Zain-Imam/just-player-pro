#!/bin/bash
# A downloaded subtitle keeps its release name in the picker.
#
# One search and one download, no more: the daily limits are small.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
long_press() { require_player; adb shell "input swipe $1 $2 $(($1+2)) $(($2+2)) 900" >/dev/null 2>&1; }

prepare
open_film

# The strip scrolls on a narrow screen, so reach for it the way tap_control
# does rather than only looking at what happens to be visible.
reach() {
  local at n row y
  show_controls >/dev/null
  at="$(find_control "$@")"
  [ -n "$at" ] && { echo "$at"; return 0; }
  row="$(bounds_of resource-id "$PKG:id/controls_scroll_view")"
  if [ -n "$row" ]; then
    y="$(echo "$row" | awk '{print int(($2 + $4) / 2)}')"
    for n in 1 2 3 4 5; do
      swipe $((SCREEN_W - 60)) "$y" 100 "$y" 250
      sleep 1
      at="$(find_control "$@")"
      [ -n "$at" ] && { echo "$at"; return 0; }
    done
  fi
  return 1
}

echo "--- subtitle sources, then search online ---"
OPEN="$(reach 'Open file' Open 'Open…')"
if [ -z "$OPEN" ]; then
  fail "no open button" "descs: $(dump | grep -oE 'content-desc="[^"]+"' | sort -u | tr '\n' ' ')"
  exit 1
fi
long_press $OPEN
sleep 3
SEARCH_ROW=""
for n in 1 2 3; do
  SEARCH_ROW="$(centre text 'Search online subtitles…')"
  [ -n "$SEARCH_ROW" ] && break
  echo "  (the sources dialog was not up yet; pressing and holding again)"
  long_press $OPEN
  sleep 3
done
if [ -z "$SEARCH_ROW" ]; then
  fail "the subtitle sources dialog never opened" \
       "on screen: $(dump | grep -oE 'text="[^"]+"' | head -6 | tr '\n' ' ')"
  exit 1
fi
tap $SEARCH_ROW
sleep 3

FIELD="$(centre class android.widget.EditText)"
[ -z "$FIELD" ] && { fail "no input box"; exit 1; }
CLEAR="$(centre text 'CLEAR')"
[ -n "$CLEAR" ] && { tap $CLEAR; sleep 1; }
tap $FIELD
sleep 1
adb shell "input text 'Inception'" >/dev/null 2>&1
sleep 2
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 2
GO="$(centre text 'SEARCH')"
[ -z "$GO" ] && { fail "could not reach Search"; exit 1; }
tap $GO
sleep 8
PICK="$(centre text 'Inception')"
[ -z "$PICK" ] && { fail "no Inception in the posters"; exit 1; }
tap $PICK
sleep 10

echo "--- the results ---"
shot subname-results
FIRST="$(dump | grep -oE 'text="[A-Za-z0-9][^"]*"' | sed -n '4p' | sed 's/text=//;s/"//g')"
echo "  first result row: $FIRST"
AT="$(centre text "$FIRST")"
[ -z "$AT" ] && { fail "could not find the first result row"; exit 1; }
echo "--- downloading it ---"
tap $AT
sleep 12
shot subname-downloaded

echo "--- what the subtitle picker calls it now ---"
show_controls >/dev/null
AT2="$(reach 'Disable subtitles' 'Enable subtitles' Subtitles Subtitle)"
[ -z "$AT2" ] && { fail "no subtitle button"; exit 1; }
tap $AT2
sleep 3
shot subname-picker
LIST="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
echo "  picker shows: $LIST"

if echo "$LIST" | grep -qE 'text="[0-9]{5,}"'; then
  fail "the picker is showing a row of digits"
else
  pass "no bare id in the picker"
fi
if echo "$LIST" | grep -qiF "$(echo "$FIRST" | cut -c1-12)"; then
  pass "the release name is what the track is called"
else
  echo "  (looked for: $(echo "$FIRST" | cut -c1-12))"
  fail "the release name is not in the picker"
fi
