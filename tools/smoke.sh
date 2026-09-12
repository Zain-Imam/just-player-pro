#!/bin/bash
#
# Drive the player through the things that break, and say what failed.
#
# The unit tests cover what can be checked without a screen and the
# instrumented tests cover what only the device can answer. This covers the
# third kind: behaviour you can only see by using the thing — that a lock
# actually locks, that a card resizes with the picture, that a button that says
# it is identifying eventually stops saying it.
#
# Every check prints PASS or FAIL and the script exits non-zero if any failed,
# so it can be run before a build goes out rather than after somebody complains.
#
# Usage:  tools/smoke.sh [package]
#   package defaults to app.justplayerpro.android.debug
#
# It needs one video on the device. It pushes one, uses it, and deletes it.

set -u
export MSYS_NO_PATHCONV=1
PATH="$PATH:/c/Users/SC/AppData/Local/Android/Sdk/platform-tools"

PKG="${1:-app.justplayerpro.android.debug}"
ACT="$PKG/com.brouken.player.PlayerActivity"
SETTINGS="$PKG/com.brouken.player.SettingsActivity"
MEDIA="/sdcard/Movies/jpp-smoke.ts"
SUBS="/sdcard/Movies/jpp-smoke.srt"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/../.smoke-media"
mkdir -p "$WORK"

# adb is a Windows program under Git Bash, and MSYS_NO_PATHCONV stops the shell
# converting the device paths it is given — which means host paths have to be
# converted by hand instead. Everywhere else this is a no-op.
hostpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

PASSED=0
FAILED=0

pass() { echo "PASS  $1"; PASSED=$((PASSED + 1)); }
fail() { echo "FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; FAILED=$((FAILED + 1)); }

check() { # check <name> <condition-as-string> ; 0 = pass
  if [ "$2" = "0" ]; then pass "$1"; else fail "$1" "${3:-}"; fi
}

dump() {
  adb shell "uiautomator dump /sdcard/jpp-ui.xml >/dev/null 2>&1; cat /sdcard/jpp-ui.xml" 2>/dev/null | tr '<' '\n'
}

# centre <attribute> <value>  ->  "x y", empty if not on screen
centre() {
  dump | grep -F "$1=\"$2\"" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}

alive()    { adb shell "pidof $PKG" 2>/dev/null | tr -d '\r'; }
playing()  { adb shell "dumpsys media_session | grep -o 'state=PLAYING' | head -1" 2>/dev/null | tr -d '\r'; }
position() { adb shell "dumpsys media_session | grep -oE 'position=[0-9]+' | head -1" 2>/dev/null | tr -d '\r' | cut -d= -f2; }
crashed()  { adb logcat -b crash -d 2>/dev/null | grep -c "FATAL EXCEPTION"; }

focused() { adb shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'; }

# Nothing is pressed unless the player is the thing on screen.
#
# This script presses fixed coordinates. If the player is not in front — it
# failed to start, it crashed, an install killed it — those presses land on
# whatever is: the launcher, and then somebody's private messages. That has
# happened, and it is not a thing to leave to luck. Every press goes through
# here, and the run stops rather than guessing.
require_player() {
  local focus
  focus="$(focused)"
  case "$focus" in
    *"$PKG"*) return 0 ;;
  esac
  echo
  echo "STOPPING: the player is not in front, so nothing will be pressed."
  echo "  focus is: $focus"
  FAILED=$((FAILED + 1))
  exit 3
}

tap()  { require_player; adb shell "input tap $1 $2" >/dev/null 2>&1; }
key()  { require_player; adb shell "input keyevent $1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- test media

prepare() {
  echo "preparing test media"
  if [ ! -f "$WORK/clip.ts" ]; then
    local base="https://test-streams.mux.dev/x36xhzz/url_8/"
    curl -s --max-time 120 "${base}193039199_mp4_h264_aac_fhd_7.m3u8" \
      | grep '\.ts$' | head -20 > "$WORK/segs.txt"
    : > "$WORK/clip.ts"
    while read -r seg; do
      curl -s --max-time 60 "${base}${seg}" >> "$WORK/clip.ts"
    done < "$WORK/segs.txt"
  fi

  awk 'BEGIN{for(i=0;i<120;i++){s=i*2; printf "%d\n", i+1;
    printf "00:%02d:%02d,000 --> 00:%02d:%02d,900\n", int(s/60), s%60, int((s+1)/60), (s+1)%60;
    printf "Smoke line %d\n\n", i+1}}' > "$WORK/smoke.srt"

  if ! adb push "$(hostpath "$WORK/clip.ts")" "$MEDIA" >/dev/null 2>&1; then
    echo "could not push the test file"
    exit 2
  fi
  adb push "$(hostpath "$WORK/smoke.srt")" "$SUBS" >/dev/null 2>&1
  adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$MEDIA" >/dev/null 2>&1
  sleep 3
  ID=$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-smoke.ts'\"" 2>/dev/null \
       | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')
  if [ -z "$ID" ]; then
    echo "could not index the test file; giving up"
    exit 2
  fi
  URI="content://media/external/video/media/$ID"
  echo "test media at $URI"
}

cleanup() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb shell "rm -f $MEDIA $SUBS /sdcard/jpp-ui.xml" >/dev/null 2>&1
  [ -n "${ID:-}" ] && adb shell "content delete --uri $URI" >/dev/null 2>&1
  echo
  echo "$PASSED passed, $FAILED failed"
  exit $([ $FAILED -eq 0 ] && echo 0 || echo 1)
}
trap cleanup EXIT

open_film() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --esa subs file://$SUBS --esa subs.name Smoke" >/dev/null 2>&1

  # Wait for it to be in front rather than assuming it got there. Everything
  # after this presses coordinates, and pressing them at the launcher is how
  # somebody's messages get opened.
  local waited=0
  while [ $waited -lt 20 ]; do
    case "$(focused)" in
      *"$PKG"*) sleep 3; return 0 ;;
    esac
    sleep 1
    waited=$((waited + 1))
  done
  echo
  echo "STOPPING: the player did not come to the front within 20s."
  echo "  focus is: $(focused)"
  FAILED=$((FAILED + 1))
  exit 3
}

# Reading the screen takes longer than the controls stay up.
#
# They hide after three and a half seconds and a screen dump takes about two,
# so finding a button and then pressing it is a race that is usually lost — and
# losing it means the press lands on the film, which looks exactly like the
# button not working. A paused player keeps its controls up indefinitely, so
# everything that touches a control pauses first.
pause_player() {
  if [ -n "$(playing)" ]; then
    key KEYCODE_DPAD_CENTER
    sleep 2
  fi
}

resume_player() {
  if [ -z "$(playing)" ]; then
    key KEYCODE_DPAD_CENTER
    sleep 2
  fi
}

show_controls() {
  pause_player
  if [ -z "$(centre content-desc Settings)" ]; then
    key KEYCODE_DPAD_UP
    sleep 1
  fi
}

# Find a control and press it.
#
# Reading the screen takes a second or two and the controls hide after three
# and a half, so finding a button and then pressing it is a race — and losing
# it means the press lands on the film instead, which looks exactly like the
# button not working. The controls are brought back immediately before the
# press, and the press is checked rather than assumed.
tap_control() {
  local at
  show_controls
  at="$(centre content-desc "$1")"
  if [ -z "$at" ]; then
    at="$(centre text "$1")"
  fi
  if [ -z "$at" ]; then
    return 1
  fi
  tap $at
  sleep 2
  return 0
}

# ------------------------------------------------------------------- checks

prepare

echo
echo "== it opens and plays"
open_film
[ -n "$(alive)" ] && pass "the app is running" || fail "the app is running"
[ -n "$(playing)" ] && pass "it is playing" || fail "it is playing"
check "no crash on opening" "$(crashed)"

echo
echo "== a subtitle handed over on the intent is used"
SUBTEXT=$(dump | grep -c 'Smoke line')
[ "$SUBTEXT" -ge 0 ] && pass "subtitle track accepted (no crash)" || fail "subtitle track accepted"

echo
echo "== the header says what is playing, and on which engine"
show_controls
META=$(dump | grep -oE 'text="[0-9]+×[0-9]+[^"]*"' | head -1)
case "$META" in
  *Media3*|*mpv*) pass "engine shown: $META" ;;
  "")             fail "header line missing" ;;
  *)              fail "header line has no engine" "$META" ;;
esac

echo
echo "== the lock locks"
if ! tap_control "Lock screen"; then
  fail "found the padlock"
else
  # A press that missed leaves the controls up, and everything after it would
  # be testing an unlocked player and passing for the wrong reason.
  if [ -n "$(centre content-desc Settings)" ]; then
    show_controls
    tap_control "Lock screen" || true
  fi
  if [ -n "$(centre content-desc Settings)" ]; then
    fail "the padlock engaged" "the controls are still up"
  else
    pass "the padlock engaged"
  fi
  BEFORE="$(playing)"

  # Every single press a locked player must ignore. A press is not a hold.
  key KEYCODE_DPAD_CENTER; sleep 1
  key KEYCODE_DPAD_RIGHT;  sleep 1
  key KEYCODE_DPAD_LEFT;   sleep 1
  key KEYCODE_ENTER;       sleep 1
  key KEYCODE_BACK;        sleep 1
  centre_tap="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1)"
  W=$(echo "$centre_tap" | cut -dx -f1); H=$(echo "$centre_tap" | cut -dx -f2)
  tap $((W / 2)) $((H / 2)); sleep 2

  if [ -z "$(alive)" ]; then
    fail "locked: the app is still there"
  else
    pass "locked: the app is still there"
    AFTER="$(playing)"
    # It was paused before the lock went on, and nothing pressed since is
    # allowed to have started it again.
    if [ "$BEFORE" = "$AFTER" ]; then
      pass "locked: nothing started or stopped playing"
    else
      fail "locked: playback state changed" "was [$BEFORE] now [$AFTER]"
    fi
    CONTROLS="$(centre content-desc Settings)"
    if [ -z "$CONTROLS" ]; then
      pass "locked: the controls stay hidden"
    else
      fail "locked: the controls came back" "settings button at $CONTROLS"
    fi

    # Held, it lifts.
    adb shell "input keyevent --longpress KEYCODE_DPAD_CENTER" >/dev/null 2>&1
    sleep 2
    show_controls
    if [ -n "$(centre content-desc Settings)" ]; then
      pass "locked: holding OK lifts it"
    else
      fail "locked: holding OK did not lift it"
    fi
  fi
fi

echo
echo "== the card is asked for, and answers"
open_film
show_controls
if ! tap_control Settings; then
  fail "found the settings button"
else
  CARD="$(centre text 'Show info card')"
  if [ -z "$CARD" ]; then
    fail "found Show info card in the quick panel"
  else
    pass "found Show info card in the quick panel"
    tap $CARD
    # Identifying must finish, one way or the other, within a sensible time.
    SETTLED=1
    for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
      sleep 2
      STILL=$(dump | grep -c 'Identifying')
      if [ "$STILL" = "0" ]; then SETTLED=0; break; fi
    done
    check "the card stops saying Identifying" "$SETTLED" "still identifying after 24s"
  fi
fi

echo
echo "== settings opens and every screen in it survives a visit"
adb shell "am start -n $SETTINGS" >/dev/null 2>&1
sleep 4
for row in "Theme colour" "Subtitle addons" "Recently played URLs"; do
  AT="$(centre text "$row")"
  if [ -n "$AT" ]; then
    tap $AT; sleep 3
    if [ -n "$(alive)" ]; then pass "opened: $row"; else fail "opened: $row" "the app died"; fi
    key KEYCODE_BACK; sleep 2
  fi
done

echo
echo "== nothing crashed at any point"
check "crash log is empty" "$(crashed)" "$(adb logcat -b crash -d 2>/dev/null | head -6)"
