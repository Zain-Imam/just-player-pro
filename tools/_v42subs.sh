#!/bin/bash
# Subtitle size steps by one per press: the panel shows this engine's value,
# quick presses do not jump, and a trip to settings keeps the engine's size.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42subs.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap cleanup EXIT

ENGINE="${ENGINE:-mpv}"
if [ "$ENGINE" = "mpv" ]; then OWN=subtitleSize_mpv; OTHER=subtitleSize; else OWN=subtitleSize; OTHER=subtitleSize_mpv; fi
setpref string playbackEngine "$ENGINE"
setpref int "$OTHER" -9
setpref int "$OWN" 2
echo "== $ENGINE: own size 2, the other engine's -9"

server "reset=1"
launch_url "$SERVER/clip.ts" "--es title Clip --esa subs $SERVER/smoke.srt --esa subs.name Smoke"
sleep 8
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 1

open_panel() {
  adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  sleep 1
  local at
  at="$(dump | grep -E 'content-desc="[^"]*[Ss]ubtitle' | head -1 \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
      | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
      | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}')"
  [ -z "$at" ] && return 1
  set -- $at
  adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1
  sleep 2
}

# The summary under the row titled $1, and the row's bounds.
row_summary() {
  dump > "$WORK/panel.txt"
  awk -v t="text=\"$1\"" '
    index($0, t) && /android:id\/title/ { found = 1; next }
    found && /android:id\/summary/ { match($0, /text="[^"]*"/); print substr($0, RSTART+6, RLENGTH-7); exit }
  ' "$WORK/panel.txt"
}
row_bounds() {
  grep -F "text=\"$1\"" "$WORK/panel.txt" | grep -F 'android:id/title' | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}

if ! open_panel; then
  fail "opened the subtitle panel" "$(dump | grep -oE 'content-desc="[^"]+"' | head -12 | tr '\n' ' ')"
  exit 1
fi
SIZE="$(row_summary Size)"
if [ "$SIZE" = "+2" ]; then
  pass "the panel shows this engine's size (+2), not the other's"
else
  fail "the panel shows this engine's size" "it shows '$SIZE'"
fi

# the minus half of the row: from the panel's left edge to the title
B="$(row_bounds Size)"
set -- $B
Y=$(( ($2 + $4) / 2 ))
PANEL_LEFT="$(grep -F 'android:id/list' "$WORK/panel.txt" | head -1 | grep -oE 'bounds="\[[0-9]+' | grep -oE '[0-9]+$')"
MINUS_X=$(( ${PANEL_LEFT:-1400} + 40 ))

adb shell "input tap $MINUS_X $Y" >/dev/null 2>&1
sleep 1.5
V="$(getpref "$OWN" | grep -oE 'value="-?[0-9]+"' | grep -oE -- '-?[0-9]+')"
[ "$V" = "1" ] && pass "one press goes from 2 to 1" || fail "one press goes from 2 to 1" "stored $V"

# Six quick presses: one step each, never five or ten.
for n in 1 2 3 4 5 6; do adb shell "input tap $MINUS_X $Y" >/dev/null 2>&1; done
sleep 1.5
V="$(getpref "$OWN" | grep -oE 'value="-?[0-9]+"' | grep -oE -- '-?[0-9]+')"
[ "$V" = "-5" ] && pass "six quick presses go from 1 to -5" || fail "six quick presses go from 1 to -5" "stored $V"
O="$(getpref "$OTHER" | grep -oE 'value="-?[0-9]+"' | grep -oE -- '-?[0-9]+')"
[ "$O" = "-9" ] && pass "the other engine's size is untouched" || fail "the other engine's size is untouched" "stored $O"

# Out to the settings screen and back: the size must stay this engine's.
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 1
adb logcat -c >/dev/null 2>&1
adb shell "am start -n $SETTINGS" >/dev/null 2>&1
sleep 3
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 4
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 1
if open_panel; then
  SIZE="$(row_summary Size)"
  [ "$SIZE" = "-5" ] && pass "after the settings screen the panel still says -5" \
    || fail "after the settings screen the panel still says -5" "it says '$SIZE'"
  adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
else
  fail "reopened the panel after the settings screen" "focus: $(focused)"
fi
if [ "$ENGINE" = "mpv" ]; then
  SCALE="$(player_log 300 | grep -oE 'mpv sub-scale = [0-9.]+' | tail -1)"
  echo "  last $SCALE (size -5 is about 1.107; Media3's -9 would be about 1.016)"
  case "$SCALE" in
    *"= 1.10"*) pass "mpv kept its own size after the settings screen" ;;
    "") pass "mpv was not restyled with another size" ;;
    *) fail "mpv kept its own size after the settings screen" "$SCALE" ;;
  esac
fi
check "no crash" "$(crashed)"
