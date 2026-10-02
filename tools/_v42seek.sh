#!/bin/bash
# Seek checks on one engine (F/G: a drag while paused, then a double tap;
# H: taps during a slow seek; I: a far request that fails).
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42seek.sh [F G H I]

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap 'server "reset=1"; cleanup' EXIT

ENGINE="${ENGINE:-mpv}"
CASES="${*:-F G H I}"
DURATION_MS=888000
setpref string playbackEngine "$ENGINE"
setpref boolean overlayOnPause false
setpref boolean lockedTimeline false

seeks() { adb logcat -d 2>/dev/null | tr -d '\r' | grep -oE "MpvPlayer.*: seek [0-9]+ [a-z+]+" | sed -E 's/.*: seek //'; }

# Open paused at a minute in, measure the seek bar, leave the controls up.
open_paused() {
  server "reset=1"
  server "rate=${1:-2500000}"
  launch_url "$SERVER/sintel.mkv" "--ei position 60000 --es title Sintel" || { fail "opened"; exit 1; }
  sleep 10
  adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
  sleep 1
  adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  sleep 1
  BAR="$(adb shell "uiautomator dump /sdcard/jpp-ui.xml >/dev/null 2>&1; cat /sdcard/jpp-ui.xml" \
      | tr '<' '\n' | grep -F "resource-id=\"$PKG:id/exo_progress\"" \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
      | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')"
  [ -z "$BAR" ] && { fail "found the seek bar"; exit 1; }
  set -- $BAR
  BX1=$(( $1 + ($3 - $1) * 6 / 100 )); BY=$(( $4 - 26 )); BW=$(( ($3 - $1) * 88 / 100 ))
  SW="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
  SH="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1 | tr -d '\r')"
}

share_x() { echo $(( BX1 + BW * $1 / 1000 )); }

drag() {
  local from="$1" to="$2" steps=8 i x
  adb shell "input motionevent DOWN $(share_x $from) $BY" >/dev/null 2>&1
  for i in $(seq 1 $steps); do
    x=$(( from + (to - from) * i / steps ))
    adb shell "input motionevent MOVE $(share_x $x) $BY" >/dev/null 2>&1
    sleep 0.4
  done
  DURING="$(seeks | wc -l)"
  adb shell "input motionevent UP $(share_x $to) $BY" >/dev/null 2>&1
}

# Two taps close enough together to be a double tap, on the right of the picture.
double_tap_right() {
  double_tap_at $(( SW * 80 / 100 )) $(( SH * 45 / 100 )) "${1:-2}"
}

echo "== $ENGINE"

case " $CASES " in *" F "*|*" G "*)
  echo
  echo "-- F/G: a drag while paused, then a double tap"
  open_paused
  adb logcat -c >/dev/null 2>&1
  drag 100 300
  sleep 3
  echo "  seeks while the finger was down: $DURING"
  if [ "$ENGINE" = "mpv" ]; then
    seeks | sed 's/^/    /' | tail -12
    [ "$DURING" -ge 3 ] && pass "F: the picture follows the drag (each frame asks for the next)" \
      || fail "F: the picture follows the drag" "only $DURING seek(s) sent before the finger lifted"
  fi
  # Let the controls go so the taps reach the picture.
  sleep 8
  BEFORE="$(position_ms)"
  adb logcat -c >/dev/null 2>&1
  # adb double taps sometimes miss; retry until one registers
  for attempt in 1 2 3; do
    double_tap_right 2
    sleep 4
    AFTER="$(position_ms)"
    [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ] && break
  done
  echo "  double tap: ${BEFORE}ms -> ${AFTER}ms"
  if [ "$ENGINE" = "mpv" ]; then
    LAST="$(seeks | tail -1)"
    echo "  mpv was asked: $LAST"
    case "$LAST" in
      *keyframes*) fail "G: the double tap lands exactly" "sent as $LAST" ;;
      "") fail "G: the double tap lands exactly" "no seek logged" ;;
      *) pass "G: the double tap is an exact seek ($LAST)" ;;
    esac
  fi
  D=$(( ${AFTER:-0} - ${BEFORE:-0} ))
  if [ $D -ge 9700 ] && [ $D -le 10300 ]; then
    pass "G: it moved exactly ten seconds (${D}ms)"
  else
    fail "G: it moved exactly ten seconds" "moved ${D}ms"
  fi
  ;;
esac

case " $CASES " in *" H "*)
  if [ "$ENGINE" = "mpv" ]; then
    echo
    echo "-- H: a double tap while a far seek is still loading"
    open_paused
    # Every new request is held five seconds, so the far seek stays in flight.
    server "holdFrom=0.0001&holdMs=5000"
    adb logcat -c >/dev/null 2>&1
    adb shell "input tap $(share_x 500) $BY" >/dev/null 2>&1
    sleep 1.5
    double_tap_right 2
    sleep 8
    T="$(seeks | awk '{print $1}' | tr '
' ' ')"
    echo "  targets: $T"
    OK="$(echo "$T" | awk '{ if (NF < 2) { print "few"; exit }
        d = $NF - $1; print (d >= 9000 && d <= 11000) ? "ok" : "bad" }')"
    [ "$OK" = "ok" ] && pass "H: the double tap counted on from the far seek's target"       || fail "H: the double tap counted on from the far seek's target" "targets: $T"
    server "reset=1"
  fi
  ;;
esac

case " $CASES " in *" I "*)
  if [ "$ENGINE" = "media3" ]; then
    echo
    echo "-- I: the far request fails"
    open_paused
    adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
    sleep 6
    P0="$(position_ms)"
    echo "  playing at ${P0}ms"
    # One far request fails (it times out); the server is otherwise fine.
    server "holdFrom=0.85&holdMs=600000&holdCount=1"
    # The tap has to reach the bar: checked by the far request arriving.
    for attempt in 1 2 3; do
      adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
      sleep 0.8
      adb shell "input tap $(share_x 920) $BY" >/dev/null 2>&1
      sleep 2
      curl -s "$SERVER/log" | tail -5 | grep -q "HOLD /sintel.mkv" && break
    done
    echo "  tapped 92% ($(curl -s "$SERVER/log" | grep -c "HOLD /sintel.mkv") far request(s) held)"
    for i in $(seq 1 30); do
      sleep 2
      adb logcat -d 2>/dev/null | grep -q "Source error, rebuilding once" && break
    done
    adb logcat -d 2>/dev/null | tr -d '\r' | grep -m1 "Source error, rebuilding once" | sed 's/^.*Source/  Source/'
    server "holdFrom=0&holdMs=0&refuseWhileHeld=0"
    # a rebuild during the refusals may have read from the start; allow a minute
    P1=""; P2=""
    for i in $(seq 1 15); do
      sleep 4
      A="$(position_ms)"; sleep 3; B="$(position_ms)"
      [ -z "$P1" ] && P1="$A"
      if [ -n "$A" ] && [ -n "$B" ] && [ $((B - A)) -ge 1500 ]; then P1="$A"; P2="$B"; break; fi
      P2="$B"
    done
    echo "  after the rebuild: ${P1}ms then ${P2}ms"
    if adb logcat -d 2>/dev/null | grep -q "Source error, rebuilding once"; then
      pass "I: the failed stream was rebuilt rather than left released"
    else
      fail "I: the failed stream was rebuilt" "no rebuild in the log within a minute"
    fi
    if [ -n "$P1" ] && [ "$P1" -lt $((DURATION_MS * 50 / 100)) ] && [ "$P1" -ge $((${P0:-60000} - 15000)) ]; then
      pass "I: it came back near where it was playing, not at the tap (${P1}ms)"
    else
      fail "I: it came back near where it was playing" "at ${P1}ms, was ${P0}ms"
    fi
    [ -n "$P2" ] && [ $((P2 - ${P1:-0})) -ge 2000 ] && pass "I: and it is playing" \
      || fail "I: and it is playing" "${P1}ms then ${P2}ms"
  fi
  ;;
esac
check "no crash" "$(crashed)"
echo
echo "$PASSED passed, $FAILED failed"
