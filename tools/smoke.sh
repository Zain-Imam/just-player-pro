#!/bin/bash
#
# Drive the player through the things that break, and say what failed.
#
# The unit tests cover what can be checked without a screen and the
# instrumented tests cover what only the device can answer. This covers the
# third kind: behaviour you can only see by using the thing — that a lock
# actually locks, that a panel opens on the edge it is supposed to, that a
# button saying "Identifying" eventually stops saying it.
#
# Every check prints PASS or FAIL and the script exits non-zero if any failed,
# so it can be run before a build goes out rather than after somebody complains.
#
# NOTHING IS PRESSED UNLESS THE PLAYER IS THE WINDOW IN FRONT. This script
# presses fixed coordinates; if the player is not there — it failed to start,
# it crashed, an install killed it — those presses land on the launcher and
# open whatever happens to be under them. That has happened once. Every press
# goes through require_player, and the run stops rather than guessing.
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
check() { if [ "$2" = "0" ]; then pass "$1"; else fail "$1" "${3:-}"; fi; }

dump() {
  adb shell "uiautomator dump /sdcard/jpp-ui.xml >/dev/null 2>&1; cat /sdcard/jpp-ui.xml" 2>/dev/null | tr '<' '\n'
}

bounds_of() {
  dump | grep -F "$1=\"$2\"" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
centre() { bounds_of "$@" | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'; }
onscreen() { [ -n "$(centre "$@")" ]; }

alive()   { adb shell "pidof $PKG" 2>/dev/null | tr -d '\r'; }
playing() { adb shell "dumpsys media_session | grep -o 'state=PLAYING' | head -1" 2>/dev/null | tr -d '\r'; }
crashed() { adb logcat -b crash -d 2>/dev/null | grep -c "FATAL EXCEPTION"; }
focused()     { adb shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'; }
focused_app() { adb shell "dumpsys window | grep -m1 mFocusedApp" 2>/dev/null | tr -d '\r'; }

# ------------------------------------------------------- the safety interlock

# Two questions, because one of them is not enough.
#
# mCurrentFocus names the focused window, which for one of this app's own
# panels is "PopupWindow:..." with no package in it at all — so trusting only
# that refuses to press the app's own menus. mFocusedApp names the activity
# behind whatever has focus, and that is always the package.
#
# Both have to agree: the activity in front must be ours, and the focused
# window must not belong to somebody else.
require_player() {
  local app focus
  app="$(focused_app)"
  focus="$(focused)"

  case "$app" in
    *"$PKG"*) ;;
    *) stop_pressing "$app" "$focus" ;;
  esac

  case "$focus" in
    *"$PKG"*)            return 0 ;;
    *PopupWindow*)       return 0 ;;
    *"Application Error"*) stop_pressing "$app" "$focus" ;;
    *[a-z].[a-z]*)       stop_pressing "$app" "$focus" ;;
  esac
  return 0
}

stop_pressing() {
  echo
  echo "STOPPING: the player is not in front, so nothing will be pressed."
  echo "  activity: $1"
  echo "  window:   $2"
  FAILED=$((FAILED + 1))
  exit 3
}

tap()   { require_player; adb shell "input tap $1 $2" >/dev/null 2>&1; }
key()   { require_player; adb shell "input keyevent $1" >/dev/null 2>&1; }
hold()  { require_player; adb shell "input keyevent --longpress $1" >/dev/null 2>&1; }
swipe() { require_player; adb shell "input swipe $1 $2 $3 $4 $5" >/dev/null 2>&1; }

# ---------------------------------------------------------------- test media

prepare() {
  # Nothing works if the thing under test is not installed, and starting a
  # missing package fails quietly enough to look like a bug in the player.
  local installed
  installed="$(adb shell 'pm list packages' 2>/dev/null | tr -d '\r')"
  if ! echo "$installed" | grep -qx "package:$PKG"; then
    echo "STOPPING: $PKG is not installed on this device."
    echo "  players installed: $(echo "$installed" | grep justplayer | tr '\n' ' ')"
    exit 2
  fi

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
    echo "could not push the test file"; exit 2
  fi
  adb push "$(hostpath "$WORK/smoke.srt")" "$SUBS" >/dev/null 2>&1
  adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$MEDIA" >/dev/null 2>&1
  sleep 3
  ID=$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-smoke.ts'\"" 2>/dev/null \
       | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')
  [ -z "$ID" ] && { echo "could not index the test file"; exit 2; }
  URI="content://media/external/video/media/$ID"
  echo "test media at $URI"

  # What the settings looked like before any of this, so drift is visible.
  adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null > "$WORK/prefs-before.xml"
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
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) sleep 4; return 0 ;; esac
    sleep 1; waited=$((waited + 1))
  done
  echo
  echo "STOPPING: the player did not come to the front within 25s."
  echo "  focus is: $(focused)"
  FAILED=$((FAILED + 1))
  exit 3
}

open_settings() {
  adb shell "am start -n $SETTINGS" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 20 ]; do
    case "$(focused)" in *"$PKG"*) sleep 2; return 0 ;; esac
    sleep 1; waited=$((waited + 1))
  done
  echo
  echo "STOPPING: settings did not come to the front."
  echo "  focus is: $(focused)"
  FAILED=$((FAILED + 1))
  exit 3
}

# A paused player keeps its controls up; a playing one hides them in three and
# a half seconds, which is less time than reading the screen takes. Everything
# that presses a control pauses first.
pause_player()  { if [ -n "$(playing)" ]; then key KEYCODE_DPAD_CENTER; sleep 2; fi; return 0; }
show_controls() { pause_player; if ! onscreen content-desc Settings; then key KEYCODE_DPAD_UP; sleep 1; fi; return 0; }

# The button strip scrolls sideways on a narrow screen.
#
# In portrait the row of buttons is wider than the phone, so the padlock, the
# rotate button and picture-in-picture are past the right edge until the strip
# is dragged. A test that only looks at what is visible decides they do not
# exist.
# Find one of several possible names and press it.
#
# Two reasons for the list. A button that toggles has two descriptions — the
# subtitle button is "Enable subtitles" or "Disable subtitles" depending on
# what is on — and looking for one, failing, then looking for the other means
# the strip has already been dragged to the far end by the first search.
#
# And the strip itself scrolls sideways on a narrow screen: in portrait the
# padlock, the rotate button and picture-in-picture sit past the right edge
# until it is dragged, and a test that only reads what is visible decides they
# do not exist.
find_control() {
  local name at
  for name in "$@"; do
    at="$(centre content-desc "$name")"
    [ -z "$at" ] && at="$(centre text "$name")"
    if [ -n "$at" ]; then echo "$at"; return 0; fi
  done
  echo ""
  return 1
}

tap_control() {
  local at n row y
  show_controls

  at="$(find_control "$@")"
  if [ -z "$at" ]; then
    row="$(bounds_of resource-id "$PKG:id/controls_scroll_view")"
    if [ -n "$row" ]; then
      y="$(echo "$row" | awk '{print int(($2 + $4) / 2)}')"
      for n in 1 2 3 4 5; do
        swipe $(( SCREEN_W - 60 )) "$y" 100 "$y" 250
        sleep 1
        at="$(find_control "$@")"
        [ -n "$at" ] && break
      done
    fi
  fi

  [ -z "$at" ] && return 1
  tap $at
  sleep 2
  return 0
}

scroll_to() {
  local at n from to
  at="$(centre text "$1")"
  [ -n "$at" ] && { echo "$at"; return 0; }

  from=$(( (SCREEN_H * 75) / 100 ))
  to=$(( (SCREEN_H * 30) / 100 ))
  for n in $(seq 1 14); do
    swipe $((SCREEN_W / 2)) "$from" $((SCREEN_W / 2)) "$to" 200
    sleep 1
    at="$(centre text "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  echo ""
  return 1
}

# ------------------------------------------------------------------- checks

prepare
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"

echo
echo "== it opens and plays"
open_film
[ -n "$(alive)" ]   && pass "the app is running"  || fail "the app is running"
[ -n "$(playing)" ] && pass "it is playing"       || fail "it is playing"
check "no crash on opening" "$(crashed)"

echo
echo "== the header names the file, the format and the engine"
show_controls
META=$(dump | grep -oE 'text="[0-9]+×[0-9]+[^"]*"' | head -1)
case "$META" in
  *Media3*|*mpv*) pass "header: $META" ;;
  "")             fail "header line missing" ;;
  *)              fail "header line names no engine" "$META" ;;
esac

echo
echo "== the subtitle handed over on the intent is there"
tap_control "Disable subtitles" "Enable subtitles" || fail "found the subtitle button"
if onscreen text "Smoke"; then
  pass "listed under the name the launcher gave it"
else
  fail "listed under the name the launcher gave it" \
       "$(dump | grep -oE 'text="[^"]+"' | head -6 | tr '\n' ' ')"
fi
SB="$(bounds_of text Subtitles)"
if [ -n "$SB" ]; then
  set -- $SB
  if [ "$3" -gt $(((SCREEN_W * 85) / 100)) ]; then
    pass "the picker reaches the trailing edge (to x=$3 of $SCREEN_W)"
  else
    fail "the picker does not reach the trailing edge" "ends at x=$3 of $SCREEN_W"
  fi
fi
onscreen text "Off" && pass "Off is offered" || fail "Off is offered"
OFFAT="$(centre text Off)"
if [ -n "$OFFAT" ]; then tap $OFFAT; sleep 2; fi
[ -n "$(alive)" ] && pass "turning subtitles off survives" || fail "turning subtitles off survives"

echo
echo "== the audio picker describes the track rather than numbering it"
if tap_control "Audio track"; then
  if dump | grep -qE 'Stereo|Mono|5\.1|7\.1|[0-9]ch'; then
    pass "the channel layout is shown"
  else
    fail "the channel layout is shown" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
  fi
  if dump | grep -qE 'AAC|AC-3|E-AC-3|DTS|Opus|FLAC|MP3'; then
    pass "the codec is shown"
  else
    fail "the codec is shown"
  fi
  key KEYCODE_BACK; sleep 2
else
  fail "found the audio button"
fi

echo
echo "== the quick panel opens and carries everything it should"
if tap_control Settings; then
  for row in "Speed" "Playback engine" "Sleep timer" "Show info card" "Audio track"; do
    onscreen text "$row" && pass "quick panel has: $row" || fail "quick panel has: $row"
  done
  QP="$(bounds_of text 'Quick settings')"
  if [ -n "$QP" ]; then
    set -- $QP
    if [ "$3" -gt $(((SCREEN_W * 85) / 100)) ]; then
      pass "the quick panel reaches the trailing edge (to x=$3 of $SCREEN_W)"
    else
      fail "the quick panel does not reach the trailing edge" "ends at x=$3"
    fi
  fi
  key KEYCODE_BACK; sleep 2
else
  fail "found the settings button"
fi

echo
echo "== asking for the info card always finishes"
if tap_control Settings; then
  CARD="$(centre text 'Show info card')"
  if [ -z "$CARD" ]; then
    fail "found Show info card"
  else
    pass "found Show info card"
    tap $CARD
    SETTLED=1
    for i in $(seq 1 14); do
      sleep 2
      if [ "$(dump | grep -c 'Identifying')" = "0" ]; then SETTLED=0; break; fi
    done
    check "it stops saying Identifying" "$SETTLED" "still identifying after 28s"
    [ -n "$(alive)" ] && pass "the app survived asking" || fail "the app survived asking"
  fi
fi

echo
echo "== stepping through every scaling mode breaks nothing"
open_film
BROKE=0
for i in 1 2 3 4 5 6 7 8 9 10 11; do
  tap_control Resize >/dev/null 2>&1 || BROKE=1
  if [ -z "$(alive)" ]; then BROKE=1; break; fi
done
check "eleven presses of the frame button" "$BROKE"
[ -n "$(alive)" ] && pass "still running after every scaling mode" || fail "still running after every scaling mode"
check "no crash from resizing" "$(crashed)"

echo
echo "== the lock locks"
open_film
if ! tap_control "Lock screen"; then
  fail "found the padlock"
else
  if onscreen content-desc Settings; then tap_control "Lock screen" || true; fi
  if onscreen content-desc Settings; then
    fail "the padlock engaged" "the controls are still up"
  else
    pass "the padlock engaged"
    BEFORE="$(playing)"
    key KEYCODE_DPAD_CENTER; sleep 1
    key KEYCODE_DPAD_RIGHT;  sleep 1
    key KEYCODE_DPAD_LEFT;   sleep 1
    key KEYCODE_ENTER;       sleep 1
    key KEYCODE_BACK;        sleep 2
    if [ -n "$(alive)" ]; then
      pass "locked: back did not leave the player"
    else
      fail "locked: back left the player"
    fi
    tap $((SCREEN_W / 2)) $((SCREEN_H / 2)); sleep 2

    if [ -z "$(alive)" ]; then
      fail "locked: the app is still there"
    else
      pass "locked: the app is still there"
      if [ "$BEFORE" = "$(playing)" ]; then
        pass "locked: nothing started or stopped playing"
      else
        fail "locked: playback state changed"
      fi
      if onscreen content-desc Settings; then
        fail "locked: the controls came back"
      else
        pass "locked: the controls stay hidden"
      fi

      hold KEYCODE_DPAD_CENTER
      sleep 2
      show_controls
      if onscreen content-desc Settings; then
        pass "locked: holding OK lifts it"
      else
        fail "locked: holding OK did not lift it"
      fi
    fi
  fi
fi

echo
echo "== every screen in settings opens and comes back"
open_settings
for row in "Theme colour" "Playback engine" "Subtitle addons" "Recently played URLs"; do
  open_settings
  AT="$(scroll_to "$row")"
  if [ -z "$AT" ]; then
    fail "found in settings: $row"
  else
    pass "found in settings: $row"
    tap $AT; sleep 3
    [ -n "$(alive)" ] && pass "opened without dying: $row" || fail "opened without dying: $row"
    key KEYCODE_BACK; sleep 2
  fi
done
open_settings
AT="$(scroll_to 'Built by')"
[ -n "$AT" ] && pass "found in settings: Built by" || fail "found in settings: Built by"

echo
echo "== the update check answers rather than hanging"
open_settings
AT="$(scroll_to 'Check for updates')"
if [ -n "$AT" ]; then
  tap $AT
  sleep 10
  [ -n "$(alive)" ] && pass "the update check came back" || fail "the update check came back"
else
  fail "found Check for updates"
fi

echo
echo "== nothing crashed at any point"
check "crash log is empty" "$(crashed)" "$(adb logcat -b crash -d 2>/dev/null | head -8)"

echo
echo "== the run left the settings as it found them"
adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null > "$WORK/prefs-after.xml"
if [ -s "$WORK/prefs-before.xml" ]; then
  DRIFT="$(diff "$WORK/prefs-before.xml" "$WORK/prefs-after.xml" 2>/dev/null \
           | grep -E '^[<>]' | grep -oE 'name="[^"]+"' | sort -u \
           | grep -vE 'mediaUri|mediaType|position|urlHistory|onlineIdentities|subtitleUri|subtitleTrackId|resizeMode|aspectStep|brightness|scale|speed' \
           | tr '\n' ' ')"
  if [ -z "$DRIFT" ]; then
    pass "no lasting setting was changed"
  else
    fail "the run changed settings" "$DRIFT"
  fi
fi
