#!/bin/bash
#
# The player's Open button leads to this application's own folder list.
#
# Nothing is changed in settings first: Auto is what people have, Auto now
# means this, and what is being checked is exactly what comes out of the box.
# The file chosen there has to reach the player, which is the half that a
# picker gets wrong -- returning an address the player cannot read looks
# identical to returning nothing.
. "$(dirname "$0")/lib.sh"
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
trap cleanup EXIT

SNAP="$WORK/snap-pick.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
focus_text() { grep 'focused="true"' "$SNAP" |if ! focus_row_by_dpad "$SNAP" Movies 20; then
  fail "the remote never reached the Movies folder" \n       "focus ended on $(grep 'focused="true"' "$SNAP" | tail -1 | grep -oE 'content-desc="[^"]*"')"
fi| grep -oE 'text="[^"]*"' | head -1 | sed 's/text=//;s/"//g'; }
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
mkdir -p "$WORK/shots"
open_film

echo "--- Open, then Local file ---"
if ! tap_control Open 'Play from'; then
  fail "no Open button in the player"
  exit 1
fi
sleep 3
snap
AT="$(centre text 'Local file')"
if [ -z "$AT" ]; then
  fail "the Open menu does not offer a local file" \
       "$(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"
  exit 1
fi
pass "the Open menu offers a local file"
tap $AT
sleep 5

WHERE="$(current_activity)"
shot pick-browser
echo "  in front: $WHERE"
if [ "$WHERE" = "HomeActivity" ]; then
  pass "Local file lands on this app's own browser"
else
  fail "Local file did not land on the browser" "$WHERE"
  exit 1
fi

echo "--- and it is a chooser, not the home screen ---"
snap
if grep -qF 'text="Choose a video"' "$SNAP" || grep -qF 'text="Choose a file"' "$SNAP"; then
  pass "it says what it is for"
else
  fail "the chooser is not titled as one"
fi
# The address box and the settings cog have no business in a question about
# which file on this device.
if grep -q 'content-desc="Settings"' "$SNAP"; then
  fail "the settings button is still there while choosing a file"
else
  pass "the settings button is out of the way while choosing"
fi

echo "--- choosing a file reaches the player ---"
#
# Tapped rather than driven by the remote. That the browser is navigable with a
# remote is proved by _vhome.sh, on this same screen and this same layout; what
# is being proved here is the plumbing either side of it -- that the player asks
# this screen for a file, and that the answer comes back and plays.
snap
AT="$(centre text 'Movies')"
if [ -z "$AT" ]; then
  # Scroll until it is on screen; a phone has more folders than fit.
  for n in $(seq 1 8); do
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 35 / 100)) 500
    sleep 1
    AT="$(centre text 'Movies')"
    [ -n "$AT" ] && break
  done
fi
if [ -z "$AT" ]; then
  fail "the Movies folder never appeared in the chooser"
  exit 1
fi
tap $AT
sleep 3
AT="$(centre text 'jpp-smoke.ts')"
if [ -z "$AT" ]; then
  fail "the test file is not in the chooser's folder" \
       "$(dump | grep -oE 'text="[^"]+"' | sed 's/text=//;s/"//g' | tr '\n' '|' | cut -c1-200)"
  exit 1
fi
tap $AT
# Waited for rather than slept through: handing the result back finishes the
# chooser and resumes the player, and a check taken between the two sees the
# screen on its way out and reads it as never having arrived.
wait_for_activity PlayerActivity 20
WHERE="$(current_activity)"
echo "  in front: $WHERE"
if [ "$WHERE" = "PlayerActivity" ]; then
  pass "the chosen file returns to the player"
else
  fail "the chosen file did not return to the player" "$WHERE"
fi

echo "--- and it actually plays, rather than erroring ---"
sleep 4
if [ -n "$(playing)" ]; then
  pass "the chosen file is playing"
else
  snap
  fail "the chosen file is not playing" \
       "$(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|' | cut -c1-200)"
fi


if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the app crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
