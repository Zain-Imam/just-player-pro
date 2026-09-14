#!/bin/bash
# The sound carries on when the player is put away -- but only if asked.
#
# Home, not the power button: it is the same onStop the screen going off gives,
# and it neither locks the phone nor touches a single setting of its own. The
# only thing this script switches is the player's own preference, and it puts
# it back at the end.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SETTING='Keep playing the sound'

centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}

# What the switch says about itself, from the summary under its title.
setting_state() {
  local summary
  summary="$(dump | grep -A6 -F "$SETTING" | grep -oE 'text="(The screen may go off[^"]*|Playback stops[^"]*)"' | head -1)"
  case "$summary" in
    *"The screen may go off"*) echo on ;;
    *"Playback stops"*)        echo off ;;
    *)                         echo unknown ;;
  esac
}

reach_setting() {
  local n at
  open_settings
  for n in $(seq 1 16); do
    at="$(centre_like "$SETTING")"
    [ -n "$at" ] && { echo "$at"; return 0; }
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
    sleep 1
  done
  echo ""
  return 1
}

set_background_audio() {
  local want="$1" at state
  at="$(reach_setting)"
  [ -z "$at" ] && { fail "no '$SETTING' row in settings"; exit 1; }
  state="$(setting_state)"
  if [ "$state" != "$want" ]; then
    tap $at
    sleep 2
    state="$(setting_state)"
  fi
  if [ "$state" = "$want" ]; then
    pass "the setting is $want"
  else
    fail "the setting would not go $want" "it reads $state"
  fi
}

# Put the player away the way a person does, and leave it away for a while.
go_home() {
  key KEYCODE_HOME
  sleep "${1:-5}"
}

come_back() {
  CURRENT_SCREEN="$ACT"
  adb shell "am start -n $ACT" >/dev/null 2>&1
  sleep 4
}

start_playing() {
  local n
  for n in 1 2 3 4 5; do
    [ -n "$(playing)" ] && return 0
    key KEYCODE_MEDIA_PLAY
    sleep 2
  done
  return 1
}

prepare

echo "=== with the setting off, putting it away stops it ==="
set_background_audio off
open_film
start_playing || { fail "the film would not start"; exit 1; }
pass "the film is playing"
go_home 5
if [ -z "$(playing)" ]; then
  pass "off: the sound stopped when the player was put away"
else
  fail "off: the sound carried on with the setting off" \
       "state: $(adb shell 'dumpsys media_session | grep -o \"state=[A-Z]*\" | head -1' | tr -d '\r')"
fi
come_back

echo
echo "=== with the setting on, it carries on ==="
set_background_audio on
open_film
start_playing || { fail "the film would not start"; exit 1; }
pass "the film is playing"
go_home 8
if [ -n "$(playing)" ]; then
  pass "on: the sound carried on with the player put away"
else
  fail "on: the sound stopped anyway" \
       "state: $(adb shell 'dumpsys media_session | grep -o \"state=[A-Z]*\" | head -1' | tr -d '\r')"
fi

echo "--- and coming back does not start it over ---"
come_back
if [ -n "$(playing)" ]; then
  pass "it is still playing on the way back"
else
  fail "coming back stopped it"
fi
if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

echo
echo "=== put the setting back as it was ==="
set_background_audio off
