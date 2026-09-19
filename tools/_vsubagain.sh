#!/bin/bash
#
# Going back to the subtitle results costs nothing the second time.
#
# What is under test is that the second press of "Search online subtitles…"
# shows the list already in hand rather than identifying the film again and
# asking every source again. One identify and one search in the whole run,
# which is the point: these services count what they hand out, and the second
# press must not spend anything.
#
# What proves it is the absence of both dialogs the first press needed, and the
# list arriving faster than a network round trip could manage.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"

FILM="${FILM:-Inception}"

SNAP="$WORK/snap-subagain.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
texts() { grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|'; }
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

# Whether either of the two working dialogs shows up in the next few seconds.
saw_working() {
  local n
  for n in 1 2 3 4 5 6; do
    dump > "$SNAP.probe" 2>/dev/null
    if grep -qF 'text="Searching…"' "$SNAP.probe" \
       || grep -qF 'text="Identifying…"' "$SNAP.probe" \
       || grep -qF 'text="Which is this?"' "$SNAP.probe"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

open_subtitle_search() {
  tap_control 'Disable subtitles' 'Enable subtitles' Subtitles Subtitle || return 1
  sleep 2
  local at
  at="$(centre text 'Search online subtitles…')"
  [ -z "$at" ] && return 1
  tap $at
  return 0
}

prepare
mkdir -p "$WORK/shots"
open_film

echo "--- the first search identifies the film and goes looking ---"
if ! open_subtitle_search; then
  fail "could not reach the online subtitle search" "$(snap; texts)"
  exit 1
fi
sleep 4
snap

# The test file is not named after a film, so the player asks -- which is what
# it is meant to do. Answering it once is what puts a film behind this file.
if grep -qF 'text="Which is this?"' "$SNAP"; then
  pass "an unrecognisable name is asked about rather than guessed at"
  FIELD="$(centre class android.widget.EditText)"
  CLEAR="$(centre text 'CLEAR')"
  [ -n "$CLEAR" ] && { tap $CLEAR; sleep 1; }
  [ -n "$FIELD" ] && { tap $FIELD; sleep 1; }
  adb shell "input text '$FILM'" >/dev/null 2>&1
  sleep 1
  adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
  sleep 2
  SEARCH="$(centre text 'SEARCH')"
  [ -z "$SEARCH" ] && { fail "no search button on the identify box"; exit 1; }
  tap $SEARCH
  sleep 10
  snap
  # A grid of posters to choose the right release from.
  POSTER="$(dump | grep -m1 -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' >/dev/null; centre text "$FILM")"
  if [ -n "$POSTER" ]; then
    tap $POSTER
    sleep 12
  fi
  snap
fi

shot subagain-first
FIRST="$(texts)"
echo "  first result screen: $(echo "$FIRST" | cut -c1-240)"
if echo "$FIRST" | grep -q 'subtitles'; then
  pass "the first search ends on a list of subtitles"
else
  fail "the first search did not end on a list of subtitles" \
       "check the keys in .env, and that $FILM can be identified"
  exit 1
fi

echo "--- out of the list, and straight back into it ---"
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 3

if ! open_subtitle_search; then
  fail "could not reopen the online subtitle search"
  exit 1
fi

if saw_working; then
  fail "the second press went and looked all over again" \
       "the held list was not used -- see OnlineController.hasResultsFor"
else
  pass "the second press neither identifies nor searches again"
fi

sleep 2
snap
shot subagain-second
SECOND="$(texts)"
echo "  second result screen: $(echo "$SECOND" | cut -c1-240)"

if echo "$SECOND" | grep -q 'subtitles'; then
  pass "the list comes straight back"
else
  fail "the list did not come back" "$SECOND"
fi

echo "--- and it is the same list, not a new one ---"
#
# Not timed. A stopwatch was the obvious check and measures the wrong thing:
# every reading of the screen here costs several seconds of uiautomator, so the
# number that came back was this script's own overhead and would have failed a
# player that answered instantly.
#
# What does distinguish the two cases is that the path to the network always
# puts a dialog up -- "Identifying…", then "Searching…" -- and the check above
# watched for both and saw neither. This adds the other half: the list is the
# one from before, down to how many and which first.
FIRST_COUNT="$(echo "$FIRST" | grep -oE '[0-9]+ subtitles' | head -1)"
SECOND_COUNT="$(echo "$SECOND" | grep -oE '[0-9]+ subtitles' | head -1)"
FIRST_TOP="$(echo "$FIRST" | cut -d'|' -f4)"
SECOND_TOP="$(echo "$SECOND" | cut -d'|' -f4)"
echo "  before: $FIRST_COUNT, top: $FIRST_TOP"
echo "  after:  $SECOND_COUNT, top: $SECOND_TOP"
if [ -n "$FIRST_COUNT" ] && [ "$FIRST_COUNT" = "$SECOND_COUNT" ] \
   && [ "$FIRST_TOP" = "$SECOND_TOP" ]; then
  pass "the same list, in the same order"
else
  fail "the list came back different" "$FIRST_COUNT/$FIRST_TOP vs $SECOND_COUNT/$SECOND_TOP"
fi

echo "--- and the way back to a fresh search is still there ---"
if echo "$SECOND" | grep -q 'Search again'; then
  pass "the results still offer a fresh search at the top"
else
  fail "the results no longer offer a fresh search"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the app crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
