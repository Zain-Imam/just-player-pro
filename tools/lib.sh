#!/bin/bash
#
# Shared harness for the scripts that drive the player over adb.
#
# The interlock lives here so there is one copy of it: nothing is pressed
# unless the player is the thing in front. See docs/VERIFICATION.md.

set -u
export MSYS_NO_PATHCONV=1
PATH="$PATH:/c/Users/SC/AppData/Local/Android/Sdk/platform-tools"

PKG="${PKG:-${1:-app.justplayerpro.android.debug}}"
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
# Which of this app's screens the test is working on, so it can be brought back.
CURRENT_SCREEN="${CURRENT_SCREEN:-}"

in_front() {
  local app focus
  app="$(focused_app)"
  focus="$(focused)"
  case "$app" in
    *"$PKG"*) ;;
    *) return 1 ;;
  esac
  case "$focus" in
    *"$PKG"*)              return 0 ;;
    *PopupWindow*)         return 0 ;;
    *"Application Error"*) return 1 ;;
    *[a-z].[a-z]*)         return 1 ;;
    *)                     return 0 ;;
  esac
}

require_player() {
  local n
  in_front && return 0

  # A phone puts things in front of you unasked: a security scanner, a system
  # dialog, an update notice. Wait for it to go.
  for n in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    in_front && return 0
  done

  # Still not there. Bring this app's own screen back — which can only ever
  # start this app — and give it a moment. Nothing is pressed until it is in
  # front; this is a way of getting there, not a way round it.
  if [ -n "$CURRENT_SCREEN" ]; then
    adb shell "am start -n $CURRENT_SCREEN" >/dev/null 2>&1
    for n in 1 2 3 4 5 6 7 8 9 10; do
      sleep 2
      in_front && { echo "        (the player had to be brought back to the front)"; return 0; }
    done
  fi

  stop_pressing "$(focused_app)" "$(focused)"
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

open_film() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN=""
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
  # Force-stopped first so the list starts at the top. Resuming an activity that
  # is already there keeps wherever it was scrolled to, and then a hunt for a row
  # near the top scrolls away from it and reports it missing.
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 1
  CURRENT_SCREEN="$SETTINGS"
  adb shell "am start -n $SETTINGS" >/dev/null 2>&1

  # Wait for something only the settings screen has, not merely for the window
  # to name this package. During a force-stop the old window still carries the
  # name for a moment, and returning then means pressing at a screen that is on
  # its way out.
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in
      *"$PKG"*)
        if dump | grep -q 'text="Appearance"'; then
          return 0
        fi ;;
    esac
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

# Drag, do not fling.
#
# A fast swipe throws the list, and it keeps going long after the finger is up —
# so a row can go past between one look and the next, and a hunt for something
# that is plainly there reports it missing. A slow drag over a short distance
# moves exactly as far as it is told.
scroll_to() {
  local at n from to
  at="$(centre text "$1")"
  [ -n "$at" ] && { echo "$at"; return 0; }

  from=$(( (SCREEN_H * 65) / 100 ))
  to=$(( (SCREEN_H * 40) / 100 ))
  for n in $(seq 1 30); do
    swipe $((SCREEN_W / 2)) "$from" $((SCREEN_W / 2)) "$to" 700
    sleep 1
    at="$(centre text "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  echo ""
  return 1
}

