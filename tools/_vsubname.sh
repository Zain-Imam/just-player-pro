#!/bin/bash
# A downloaded subtitle is known by its release name, not by the digits the file
# was saved under.
#
# One search and one download, no more: these services count what they hand out.
# The search is done through Wyzie, which is the one with room to spare.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-subname.txt"
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
texts() { snap; grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g'; }

prepare
open_film

echo "--- the subtitle picker, and the search in it ---"
if ! tap_control 'Disable subtitles' 'Enable subtitles' Subtitles Subtitle; then
  fail "no subtitle button"
  exit 1
fi
sleep 2
SEARCH="$(centre text 'Search online subtitles…')"
if [ -z "$SEARCH" ]; then
  fail "the picker does not offer an online search" "$(texts | tr '\n' '|')"
  exit 1
fi
pass "the picker offers an online search"
tap $SEARCH
sleep 4

echo "--- asking for a film by name ---"
FIELD="$(centre class android.widget.EditText)"
[ -z "$FIELD" ] && { fail "no box to type a title in" "$(texts | tr '\n' '|')"; exit 1; }
CLEAR="$(centre text 'CLEAR')"
[ -n "$CLEAR" ] && { tap $CLEAR; sleep 1; }
tap $FIELD
sleep 1
adb shell "input text 'Inception'" >/dev/null 2>&1
sleep 2
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 2
GO="$(centre text 'SEARCH')"
[ -z "$GO" ] && GO="$(centre text 'Search')"
[ -z "$GO" ] && { fail "could not reach the search button"; exit 1; }
tap $GO
sleep 8
shot subname-posters
PICK="$(centre text 'Inception')"
[ -z "$PICK" ] && { fail "nothing came back for that title" "$(texts | tr '\n' '|')"; exit 1; }
tap $PICK
sleep 12

echo "--- what came back ---"
shot subname-results
LIST="$(texts)"
echo "$LIST" | head -12 | sed 's/^/    /'
# The first row that is actually a subtitle.
#
# Not simply the first line with letters in it: the list begins with a count
# ("149 subtitles") and two actions, and tapping one of those downloads nothing
# and wastes the run. A result is a title with its source and language on the
# line under it, so that pairing is what is looked for.
FIRST="$(echo "$LIST" | awk '
  NR > 1 && $0 ~ /·/ && $0 ~ /(Wyzie|OpenSubtitles|SubDL|addon)/ { print previous; exit }
  { previous = $0 }')"
if [ -z "$FIRST" ]; then
  fail "no subtitles were offered"
  exit 1
fi
echo "  the first one offered: $FIRST"
AT="$(centre text "$FIRST")"
[ -z "$AT" ] && { fail "could not reach the first result"; exit 1; }

echo "--- downloading exactly one ---"
tap $AT
sleep 14
shot subname-downloaded

echo "--- and what the picker calls it now ---"
show_controls >/dev/null
if ! tap_control 'Disable subtitles' 'Enable subtitles' Subtitles Subtitle; then
  fail "no subtitle button after the download"
  exit 1
fi
sleep 3
shot subname-picker
NOW="$(texts | tr '\n' '|')"
echo "  the picker shows: $NOW"

if echo "$NOW" | grep -qE '\|[0-9]{5,}\|'; then
  fail "the picker is showing a row of digits" "$NOW"
else
  pass "no bare file id in the picker"
fi

# The release name it was picked under, or at least the start of it: the row in
# the picker may be shortened, and a release name is long.
STEM="$(echo "$FIRST" | cut -c1-12)"
if echo "$NOW" | grep -qF "$STEM"; then
  pass "the track is called what the subtitle was called: $STEM…"
else
  echo "  (looked for: $STEM)"
  fail "the downloaded subtitle is not named after the release" "$NOW"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
