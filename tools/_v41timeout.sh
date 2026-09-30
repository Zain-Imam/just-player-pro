#!/bin/bash
#
# How long the controls actually stay on screen, read from the player's own log.
#
# Counting by hand is how this was got wrong in the first place. The player logs
# a timestamped line when the controls appear and another when they have
# finished going, so what is measured here is the whole of what a viewer sees,
# fade included -- which is the only reading of the setting anybody has.
#
# ENGINE=mpv runs the same measurement on the other engine.
#
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT

PREFS="/data/data/$PKG/shared_prefs/${PKG}_preferences.xml"

set_timeout() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb shell "run-as $PKG sh -c 'grep -q controlsTimeoutSeconds $PREFS \
      && sed -i \"s#name=\\\"controlsTimeoutSeconds\\\" value=\\\"[0-9]*\\\"#name=\\\"controlsTimeoutSeconds\\\" value=\\\"$1\\\"#\" $PREFS \
      || sed -i \"s#</map>#<int name=\\\"controlsTimeoutSeconds\\\" value=\\\"$1\\\" />\\n</map>#\" $PREFS'" >/dev/null 2>&1
}

measure() {   # measure <seconds>
  set_timeout "$1"
  open_film
  sleep 6
  adb logcat -c >/dev/null 2>&1
  require_player
  # High on the picture: the middle is play/pause and the bottom is the bar.
  adb shell input tap $((SCREEN_W / 2)) $((SCREEN_H / 5)) >/dev/null 2>&1
  sleep $(( $1 + 12 ))
  adb logcat -d -v epoch -s JustPlayer 2>/dev/null | grep 'Controls ' > "$WORK/ctl.log"
  local got
  got="$(awk '{t = $1 + 0
               if (first == 0 && $0 ~ /shown/)  first = t
               if ($0 ~ /hidden/)               last  = t }
              END { if (first > 0 && last > first) printf "%.2f", last - first }' "$WORK/ctl.log")"
  if [ -z "$got" ]; then
    fail "asked for ${1}s, but the controls never hid" "$(tail -3 "$WORK/ctl.log")"
    return
  fi
  # Within three quarters of a second of what was asked for. The fade itself is
  # a fixed cost that cannot be made shorter, so this is as close as it gets.
  local ok
  ok="$(awk -v g="$got" -v w="$1" 'BEGIN { d = g - w; if (d < 0) d = -d; print (d <= 0.75) ? "y" : "n" }')"
  if [ "$ok" = "y" ]; then
    pass "asked for ${1}s, on screen for ${got}s"
  else
    fail "asked for ${1}s, on screen for ${got}s" "more than three quarters of a second out"
  fi
}

SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"

prepare
echo "=== how long the controls stay, on ${ENGINE:-media3} ==="
for s in 3 6 15 30; do measure "$s"; done

echo
echo "PASSED $PASSED   FAILED $FAILED"
[ "$FAILED" -eq 0 ]
