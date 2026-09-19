#!/bin/bash
#
# One player, however the second film arrives.
#
# This is the half of the launch-mode change that cannot be reasoned about.
# The manifest used to say singleTask, which routed every launch to the one
# instance; it says singleTop now, so that Back from an external launch returns
# to whoever sent the film. The guarantee singleTask gave for free is made by
# hand in PlayerActivity.onCreate, and this is what proves it.
#
# Two ways it could go wrong, and both of them are quiet:
#
#   * picture-in-picture -- a film left in a corner while the home screen is
#     used to start another one;
#   * "keep playing the sound" -- a film that goes on playing after its window
#     is gone, with a second film arriving from another application.
#
# In both cases the failure is two players sounding at once, which a screenshot
# cannot show and a passing test suite would never notice.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -1 | cut -dx -f2 | tr -d "\r")"

# How many of this app's players the system is actually holding.
#
# Counted off the history entries inside the tasks -- "* Hist #0:" and so on --
# and nothing else. Grepping the whole of dumpsys for the class name counts
# every passing mention of it: the resolved intent filters, the pending
# intents, the last orientation source. That came back as twelve players on a
# device running one, which reads as a catastrophic bug and is a broken ruler.
#
# Counted for THIS package and no other. A debug build and a release build of
# this player share a class name, differing only in the application id, so a
# device with both installed counted the other one's leftover task as a second
# player and reported the launch-mode guard broken when it was not.
players() {
  adb shell "dumpsys activity activities" 2>/dev/null \
    | grep -E '\* Hist +#' \
    | grep -c "$PKG/com.brouken.player.PlayerActivity" | tr -d '\r'
}

pref() {   # pref <key> <true|false>
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 1
  local file="shared_prefs/${PKG}_preferences.xml" tmp="$WORK/prefs-edit.xml"
  adb shell "run-as $PKG cat $file" > "$tmp" 2>/dev/null
  [ -s "$tmp" ] || return 1
  grep -q "name=\"$1\"" "$tmp" \
    && sed -i "s|<boolean name=\"$1\" value=\"[a-z]*\" />|<boolean name=\"$1\" value=\"$2\" />|" "$tmp" \
    || sed -i "s|</map>|    <boolean name=\"$1\" value=\"$2\" />\n</map>|" "$tmp"
  adb shell "run-as $PKG sh -c 'cat > $file'" < "$tmp"
}

# Playing, given a moment to get there.
#
# A film just handed over has a decoder to build and a first frame to render
# before the session says anything, and how long that takes is the device's
# business. Asked repeatedly rather than once after a guess.
playing_soon() {
  local n
  for n in $(seq 1 12); do
    [ -n "$(playing)" ] && return 0
    sleep 2
  done
  return 1
}

session_state() {
  adb shell "dumpsys media_session" 2>/dev/null \
    | grep -oE 'state=[A-Z_]+' | head -1 | tr -d '\r'
}

prepare

echo "=== 33. a second film started from the home screen while the first is away ==="
#
# Picture-in-picture is deliberately NOT switched on for this one, and that is
# worth saying out loud rather than leaving as a gap.
#
# With a film in a corner the launcher is the focused application, and the
# interlock in lib.sh refuses to press anything while that is true -- which is
# the whole point of it, and not something to be worked around on somebody's own
# phone. So the corner case is left for a person: play something, press Home,
# tap another video, and count the films you can hear.
#
# What is automated is the part that carries the same risk and can be driven
# safely: the first film backgrounded and released, a second started the way the
# home screen starts one, and exactly one player left holding it.
#
# Set explicitly rather than assumed: an earlier run of this script left it on,
# and the corner it put the film into is what the interlock then refused to
# drive -- which looked like the home screen failing to come forward.
pref autoPiP false || { fail "could not settle picture-in-picture"; exit 1; }
open_film
sleep 4
[ -n "$(playing)" ] && pass "the first film is playing" || fail "the first film is not playing"

adb shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
sleep 5
echo "  players held while away: $(players)"

CURRENT_SCREEN="$HOME_ACT"
adb shell "am start -n $HOME_ACT" >/dev/null 2>&1
sleep 6
decline_resume

#
# Tapped, rather than started with an intent.
#
# "am start" is not the path under test and cannot be: it carries NEW_TASK, and
# with NEW_TASK the system finds a task whose root is already this component and
# brings that task forward instead -- which left the home screen sitting on top
# of the first film and no second player at all. Nothing a person does produces
# that. Tapping a row calls startActivity from inside the task, which is the
# thing this test exists to exercise.
#
tap_row() {   # tap_row <label>
  local at n
  for n in $(seq 1 10); do
    at="$(centre text "$1")"
    [ -n "$at" ] && { adb shell "input tap $at" >/dev/null 2>&1; return 0; }
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 35 / 100)) 500
    sleep 1
  done
  return 1
}

tap_row Movies || { fail "could not reach the Movies folder on the home screen"; exit 1; }
sleep 3
tap_row jpp-smoke.ts || { fail "could not reach the test file"; exit 1; }
CURRENT_SCREEN="$ACT"
sleep 8

HELD="$(players)"
echo "  players held after the second film: $HELD  in front: $(current_activity)"
if [ "$HELD" -le 1 ]; then
  pass "one player, not two, after a second film from the home screen"
else
  fail "there are $HELD players" "the second film stacked on top of the first"
fi
if [ "$(current_activity)" = "PlayerActivity" ]; then
  pass "the player is the screen in front"
else
  fail "the player did not come forward" "$(current_activity)"
fi
# Told to play, then asked: whether a film starts by itself depends on how it
# was last left, which is a different feature with its own answer.
key KEYCODE_MEDIA_PLAY
sleep 2
if playing_soon; then
  pass "the second film plays when asked to"
else
  fail "the second film will not play" "session says: $(session_state)"
fi
echo
echo "=== 34. keep playing the sound, and a film sent from another application ==="
pref backgroundAudio true || { fail "could not set keep playing the sound"; exit 1; }
open_film
sleep 4
key KEYCODE_MEDIA_PLAY
sleep 3
[ -n "$(playing)" ] && pass "the first film is playing" || fail "the first film is not playing"

# Put away, so the sound is the thing being kept.
adb shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
sleep 5
echo "  still sounding with the window gone: $([ -n "$(playing)" ] && echo yes || echo no)"

# The device's own settings stand in for another application: nothing in it is
# pressed, it is only somewhere else for the launch to come from.
adb shell "am start -a android.settings.SETTINGS" >/dev/null 2>&1
sleep 4
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
sleep 8

HELD="$(players)"
echo "  players held: $HELD"
if [ "$HELD" -le 1 ]; then
  pass "the film that was still sounding was closed for the new one"
else
  fail "there are $HELD players" "two films can sound at once -- see the WeakReference in onCreate"
fi
if playing_soon; then
  pass "the second film is playing"
else
  fail "nothing is playing after the second film was sent" "session says: $(session_state)"
fi

echo "--- putting the settings back ---"
pref backgroundAudio false >/dev/null 2>&1
pref autoPiP false >/dev/null 2>&1

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the app crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
