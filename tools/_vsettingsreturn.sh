#!/bin/bash
# Coming back from the settings screen does not reopen the file -- unless what
# was changed is one of the few that cannot be applied to a running player.
#
# Counted from a cleared log each time: this phone's buffer rolls in seconds,
# so a count taken across two phases measures the buffer, not the player.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-settingsreturn.txt"
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
position() { adb shell "dumpsys media_session | grep -m1 -oE 'position=[0-9]+'" 2>/dev/null | tr -d '\r' | head -1 | cut -d= -f2; }
builds()   { adb logcat -d -s JustPlayer 2>/dev/null | grep -c 'Building the player'; }
centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}

# Into settings by way of the quick panel.
#
# Not by long-pressing the cog: the controls hide themselves after a few
# seconds and this film has a "Skip intro" button which sits in the same corner
# once they are gone, so a remembered coordinate presses that instead. A panel
# does not time out, so the row can be found while the film is paused, the film
# set playing again behind it, and the row pressed with the film still running.
into_settings() {
  local at row n
  show_controls >/dev/null
  at="$(find_control Settings 'More settings')"
  [ -z "$at" ] && return 1
  tap $at
  sleep 2
  row="$(panel_row 'All settings…')"
  [ -z "$row" ] && return 1
  key KEYCODE_MEDIA_PLAY
  sleep 3
  tap $row
  for n in $(seq 1 12); do
    case "$(focused)" in *SettingsActivity*) return 0 ;; esac
    sleep 1
  done
  return 1
}

prepare
open_film

echo "=== a film that is playing, into settings and straight back ==="
adb logcat -c >/dev/null 2>&1
key KEYCODE_MEDIA_PLAY
sleep 5
BEFORE="$(position)"
echo "  playing at ${BEFORE}ms"
into_settings || { fail "could not open the settings screen"; exit 1; }
pass "the settings screen opened"
sleep 3
key KEYCODE_BACK
sleep 6
refresh_screen
AFTER="$(position)"
BUILDS="$(builds)"
echo "  back at ${AFTER}ms, the player was built ${BUILDS} time(s) across the trip"
shot settingsreturn-back

if [ "$BUILDS" = "0" ]; then
  pass "the file was not reopened for a trip that changed nothing"
else
  fail "the player was rebuilt $BUILDS time(s) for a trip that changed nothing"
fi
if [ -n "$AFTER" ] && [ -n "$BEFORE" ] && [ "$AFTER" -ge "$BEFORE" ]; then
  pass "it came back where it was (${BEFORE} -> ${AFTER})"
else
  fail "it did not come back where it was" "${BEFORE} -> ${AFTER}"
fi
if [ -n "$(playing)" ]; then
  pass "a film that was playing is playing again"
else
  fail "the film did not resume" "state: $(adb shell 'dumpsys media_session | grep -o \"state=[A-Z]*\" | head -1' | tr -d '\r')"
fi

echo
echo "=== and one that does need the file reopened ==="
adb logcat -c >/dev/null 2>&1
into_settings || { fail "could not open the settings screen again"; exit 1; }
sleep 2
AT=""
for n in $(seq 1 16); do
  AT="$(centre_like 'Adaptive buffering')"
  [ -n "$AT" ] && break
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
  sleep 1
done
if [ -z "$AT" ]; then
  fail "no adaptive buffering row"
else
  # Read from a fresh dump rather than from the stored snapshot. The snapshot
  # taken here kept coming back without the row the scroll above had just
  # found, and an empty answer reads exactly like a row that has lost its
  # warning -- which is a bad way to be told nothing is wrong.
  # The warning, looked for on the screen the row was just found on, rather
  # than tied to the row by counting nodes after it.
  #
  # Reading the summary as "the second text after the title" is how this used to
  # work and it kept coming back empty while the screen plainly showed the
  # sentence -- the node offsets shift whenever a preference category is added
  # above, and a check that reports a missing warning because it counted wrong
  # is worse than no check. This is weaker by exactly one thing, that the
  # sentence belongs to this row and not another, and it does not go wrong.
  WARNED=""
  for n in 1 2 3 4 5 6; do
    WARNED="$(dump | grep -m1 'reopens whatever is playing')"
    [ -n "$WARNED" ] && break
    sleep 2
  done
  echo "  the screen says: $(echo "$WARNED" | grep -oE 'text="[^"]+"' | head -1 | sed 's/text=//;s/"//g')"
  if [ -n "$WARNED" ]; then
    pass "the row says it reopens the file"
  else
    fail "nothing on the screen warns that this reopens the file"
  fi
  tap $AT
  sleep 2
  snap
  echo "  after the tap it reads: $(grep -A4 -F 'text="Adaptive buffering"' "$SNAP" | grep -oE 'text="[^"]+"' | sed -n '2p' | sed 's/text=//;s/"//g')"
  key KEYCODE_BACK
  sleep 8
  BUILDS="$(builds)"
  echo "  the player was built ${BUILDS} time(s) across that trip"
  if [ "$BUILDS" -ge 1 ]; then
    pass "the one that needs it did reopen the file"
  else
    fail "a setting that needs the file reopened did not reopen it"
  fi

  echo "--- putting the setting back ---"
  if into_settings; then
    sleep 2
    AT=""
    for n in $(seq 1 16); do
      AT="$(centre_like 'Adaptive buffering')"
      [ -n "$AT" ] && break
      swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
      sleep 1
    done
    [ -n "$AT" ] && { tap $AT; sleep 2; }
    key KEYCODE_BACK
    sleep 5
  fi
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
