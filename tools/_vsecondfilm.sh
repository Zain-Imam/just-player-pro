#!/bin/bash
# One player, however the second film arrives. The single instance is kept by
# PlayerActivity.onCreate; a failure is two players sounding at once.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -1 | cut -dx -f2 | tr -d "\r")"

# How many of this app's players the system is actually holding.
# counted from the task history entries ("* Hist #0:") for this package only:
# dumpsys mentions the class elsewhere, and debug and release builds share it
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
# picture-in-picture is off: with a film in a corner the launcher has focus
# and the interlock refuses to press anything, so that case is left for a person
# set explicitly: an earlier run may have left it on
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

# tapped: am start carries NEW_TASK, which brings the old task forward instead
# of starting a second player; a tap starts it from inside the task
tap_row() {   # tap_row <label>
  local at n
  # re-measure: the home screen is portrait, the size was taken in landscape
  refresh_screen
  for n in $(seq 1 10); do
    at="$(centre text "$1")"
    [ -n "$at" ] && { adb shell "input tap $at" >/dev/null 2>&1; return 0; }
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 35 / 100)) 500
    sleep 1
  done
  return 1
}

tap_row Movies || { fail "could not reach the Movies folder on the home screen" "$(focused) | $(dump | grep -oE 'text="[^"]+"' | tr "
" " " | cut -c1-400)"; exit 1; }
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
# told to play: whether a film starts by itself depends on how it was left
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

# the device's Settings stands in for another app; nothing in it is pressed
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
