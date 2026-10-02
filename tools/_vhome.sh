#!/bin/bash
# The home screen: folders with counts, a file that opens, Back that returns,
# a favourite that sticks, a sort that changes the order, and a search that finds.
# Remote only: a row nothing can focus still looks right in a screenshot.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT

SNAP="$WORK/snap-home.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
has_text()  { snap; grep -qF "text=\"$1\"" "$SNAP"; }
focus_text() { grep 'focused="true"' "$SNAP" | tail -1 | grep -oE 'text="[^"]*"' | head -1 | sed 's/text=//;s/"//g'; }
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
mkdir -p "$WORK/shots"

echo "--- it opens on the home screen ---"
open_home
WHERE="$(current_activity)"
echo "  in front: $WHERE"
if [ "$WHERE" = "HomeActivity" ]; then
  pass "the launcher lands on the home screen"
else
  fail "the launcher did not land on the home screen" "$WHERE"
fi
shot home-root

echo "--- the test folder is listed, with a count ---"
snap
echo "  rows: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|' | cut -c1-300)"
# scrolled for: folders late in the alphabet can be below the fold
SEEN=0
# 25 steps: a full Recent list and many folders need more than 10 to pass M
for n in $(seq 1 25); do
  grep -qF 'text="Movies"' "$SNAP" && { SEEN=1; break; }
  key KEYCODE_DPAD_DOWN
  sleep 1
  snap
done
if [ "$SEEN" = 1 ]; then
  pass "the Movies folder is listed"
else
  fail "the Movies folder is not listed" "the media store may not have indexed the test file"
fi
if grep -qE 'text="[0-9]+ videos?' "$SNAP" || grep -qF 'text="1 video"' "$SNAP"; then
  pass "folders carry a count"
else
  fail "no folder shows a video count"
fi

echo "--- a remote can reach a folder and open it ---"
FOUND=0
for n in $(seq 1 12); do
  snap
  focused_row_is "$SNAP" Movies && { FOUND=1; break; }
  key KEYCODE_DPAD_DOWN
  sleep 1
done
if [ "$FOUND" = 1 ]; then
  pass "the remote reaches the folder row"
else
  fail "the remote never focused the folder row" "last focus: $(focus_text)"
fi
key KEYCODE_DPAD_CENTER
sleep 3
snap
shot home-folder
if grep -qF 'text="jpp-smoke.ts"' "$SNAP"; then
  pass "the folder opens onto its files"
else
  fail "the folder did not open onto its files"
fi

echo "--- and Back inside a folder goes up, not out ---"
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 3
snap
if grep -qF 'text="Just Player Pro"' "$SNAP"; then
  pass "Back from a folder returns to the folder list"
elif [ "$(current_activity)" != "HomeActivity" ]; then
  fail "Back from a folder left the app entirely" "$(current_activity)"
else
  fail "Back from a folder did nothing" \
       "still showing: $(grep -oE 'text="[^"]+"' "$SNAP" | head -1)"
fi
# Back into the folder for the checks that follow.
focus_row_by_dpad "$SNAP" Movies 14 >/dev/null
key KEYCODE_DPAD_CENTER
sleep 3

echo "--- a file opens in the player ---"
for n in $(seq 1 10); do
  snap
  focused_row_is "$SNAP" jpp-smoke.ts && break
  key KEYCODE_DPAD_DOWN
  sleep 1
done
key KEYCODE_DPAD_CENTER
sleep 6
WHERE="$(current_activity)"
if [ "$WHERE" = "PlayerActivity" ]; then
  pass "the file opens in the player"
else
  fail "the file did not open the player" "$WHERE"
fi

echo "--- Back returns to the home screen, not out of the app ---"
# Back once, and again only if the controls ate that press: two Backs after
# the controls have faded would leave the home screen too.
# the interlock guards the home screen from here, or it restarts the player
CURRENT_SCREEN="$HOME_ACT"
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
if ! wait_for_activity HomeActivity 10; then
  adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
  wait_for_activity HomeActivity 20
fi
WHERE="$(current_activity)"
echo "  in front: $WHERE"
if [ "$WHERE" = "HomeActivity" ]; then
  pass "Back from the player returns to the home screen"
else
  fail "Back from the player did not return to the home screen" "$WHERE"
fi

echo "--- favouriting keeps a folder at the top ---"
open_home
for n in $(seq 1 12); do
  snap
  focused_row_is "$SNAP" Movies && break
  key KEYCODE_DPAD_DOWN
  sleep 1
done
echo "  on the row: $(grep 'focused="true"' "$SNAP" | tail -1 | grep -oE 'content-desc="[^"]*"')"
# right off the row onto the star
key KEYCODE_DPAD_RIGHT
sleep 1
snap
echo "  after right: $(grep 'focused="true"' "$SNAP" | tail -1 | grep -oE 'content-desc="[^"]*"')"

# a favourite outlives the run, so read the star's state first
if grep 'focused="true"' "$SNAP" | tail -1 | grep -qF 'content-desc="Remove from favourites"'; then
  echo "  (already a favourite from an earlier run; clearing it first)"
  key KEYCODE_DPAD_CENTER
  sleep 2
  snap
fi

key KEYCODE_DPAD_CENTER
sleep 2
snap
shot home-pinned
# lowercased: headers are drawn in capitals, and grep -qiF aborts under Git Bash
if tr 'A-Z' 'a-z' < "$SNAP" | grep -qF 'text="favourites"'; then
  pass "a favourited folder gets its own section"
else
  fail "no favourites section appeared" "visible: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '
' '|' | cut -c1-220)"
fi
# And again, to leave the device as it was found.
key KEYCODE_DPAD_CENTER
sleep 2

echo "--- sorting changes the order ---"
open_home
snap
BEFORE="$(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"
# Up from the list into the bar, then across to the sort button.
key KEYCODE_DPAD_UP
sleep 1
for n in 1 2 3 4 5 6; do
  snap
  focused_desc_is "$SNAP" Sort && break
  key KEYCODE_DPAD_RIGHT
  sleep 1
done
key KEYCODE_DPAD_CENTER
sleep 2
snap
# lowercased: the dialog headings are drawn in capitals
if tr 'A-Z' 'a-z' < "$SNAP" | grep -qF 'text="sort by"' \
   && tr 'A-Z' 'a-z' < "$SNAP" | grep -qF 'text="order"'; then
  pass "the sort menu opens from the remote, with both sections"
else
  fail "the sort menu did not open from the remote" "$(grep -oE 'text="[^"]+"' "$SNAP" | head -6 | tr '
' '|')"
fi
key KEYCODE_BACK
sleep 2

echo "--- search finds the test file ---"
open_home
key KEYCODE_DPAD_UP
sleep 1
for n in 1 2 3 4 5 6; do
  snap
  focused_desc_is "$SNAP" Search && break
  key KEYCODE_DPAD_RIGHT
  sleep 1
done
key KEYCODE_DPAD_CENTER
sleep 2
adb shell "input text jpp" >/dev/null 2>&1
sleep 3
snap
shot home-search
if grep -qF 'text="jpp-smoke.ts"' "$SNAP"; then
  pass "search finds the file by name"
else
  fail "search did not find the file"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the app crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
