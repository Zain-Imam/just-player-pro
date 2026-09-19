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
HOME_ACT="$PKG/com.brouken.player.HomeActivity"
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
# Crashes belonging to the app under test, and nothing else.
#
# This used to count every FATAL EXCEPTION in the buffer, whoever it belonged
# to — including uiautomator, which dies with "already registered" whenever two
# of these scripts dump the screen at the same moment. That reported five
# crashes against a player that had not crashed at all. Only lines naming this
# package, or the ones immediately under them, are ours.
# Crashes belonging to the app under test, and nothing else.
#
# This used to count every FATAL EXCEPTION in the buffer, whoever it belonged
# to — including uiautomator, which dies with "already registered" whenever two
# of these scripts dump the screen at the same moment. That reported five
# crashes against a player which had not crashed at all.
crashed() {
  adb logcat -b crash -d 2>/dev/null | grep -A3 "FATAL EXCEPTION" \
    | grep -c "Process: $PKG"
}
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

# The phone's own security page, not the player's.
#
# Motorola's Security Hub puts "Potentially risky website" in front whenever
# this app fetches from a host it does not recognise — a subtitle source, a test
# stream. It steals focus and the run grinds to a halt behind it.
#
# It is dismissed by DECLINING: "Cancel and exit" only. Never "Continue anyway",
# never "Add site to allow list" — a test does not get to change what a phone
# trusts. This is the one place anything outside the player is pressed, it is
# named here so it can be audited, and it only ever says no.
dismiss_security_prompt() {
  case "$(focused)" in
    *securityhub*|*PhishingDetection*) ;;
    *) return 1 ;;
  esac
  local at
  at="$(bounds_of text 'Cancel and exit' | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}')"
  if [ -z "$at" ]; then
    return 1
  fi
  adb shell "input tap $at" >/dev/null 2>&1
  sleep 3
  echo "        (declined the phone's risky-site warning)"
  return 0
}

require_player() {
  local n
  in_front && return 0
  dismiss_security_prompt >/dev/null 2>&1 && in_front && return 0

  # A phone puts things in front of you unasked: a security scanner, a system
  # dialog, an update notice. Wait for it to go.
  for n in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    dismiss_security_prompt
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

# The window as it is held right now, not the panel the phone was built with.
#
# `wm size` reports the physical panel and never turns, so on a player that asks
# for landscape every script was working from 1080x2400 while the window was
# 2400x1080 -- and a list dragged from 70% of 2400 was dragged from a point
# below the bottom of the screen, which does nothing at all. Five rows that were
# plainly there came back as missing.
#
# Scripts set these two at the top before anything is open; this corrects them
# once there is a window to measure.
refresh_screen() {
  local wh
  wh="$(dump | grep -m1 -oE 'bounds="\[0,0\]\[[0-9]+,[0-9]+\]"' \
        | sed -E 's/.*\[0,0\]\[([0-9]+),([0-9]+)\].*/\1 \2/')"
  set -- $wh
  if [ $# -eq 2 ] && [ "$1" -gt 0 ] && [ "$2" -gt 0 ]; then
    SCREEN_W="$1"
    SCREEN_H="$2"
  fi
  return 0
}

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
  # Something for the interlock to bring back.
  #
  # This used to be cleared, and clearing it is what turns a stray Back into
  # the end of the run: with nothing recorded, require_player has no screen of
  # this app to restore, so it gives up and stops instead of recovering. The
  # player activity is this app's own, so starting it can only ever start this
  # app — which is the whole of what the interlock is protecting.
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --esa subs file://$SUBS --esa subs.name Smoke" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) sleep 4; refresh_screen; return 0 ;; esac
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
          refresh_screen
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

# Pause, and make sure of it.
#
# Everything else here rests on this. A paused player keeps its controls up for
# as long as you leave them, so a dump -- which takes a second or two -- reads a
# screen that is standing still. A playing one takes them away on a timer, and
# then a button that was plainly there when the search began has gone by the
# time uiautomator reads the screen, and is reported as a button that does not
# exist. Two false failures in an otherwise clean run were exactly that.
#
# It used to press the centre key, which is a toggle, and only when the media
# session said the film was playing -- so when the session had not caught up it
# pressed nothing at all, and when the session was stale it pressed play. The
# pause key is not a toggle: sending it twice still pauses.
pause_player() {
  local n
  for n in 1 2 3 4 5; do
    [ -z "$(playing)" ] && return 0
    key KEYCODE_MEDIA_PAUSE
    sleep 1
  done
  return 0
}

# Up, and asked about the strip itself rather than about one button on it. The
# buttons at the far end are off-screen until the strip is dragged, so asking
# for one of those is really asking whether the controls are up *and* already
# scrolled to the end -- which sent it looking for them all over again.
show_controls() {
  pause_player
  if ! onscreen resource-id "$PKG:id/controls_scroll_view"; then
    key KEYCODE_DPAD_UP
    sleep 1
  fi
  return 0
}

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

# A row in the quick panel.
#
# The panel is its own list down one side of the screen, so it is dragged there
# rather than down the middle of the film -- and in landscape it is short enough
# that the rows at the bottom of it are off the end until it is.
panel_row() {
  local want at n px from to
  want="$1"
  at="$(centre text "$want")"
  [ -n "$at" ] && { echo "$at"; return 0; }

  # The panel's list is the framework's id, not one of this app's.
  set -- $(bounds_of resource-id "android:id/list")
  if [ $# -ne 4 ]; then
    set -- $(bounds_of resource-id "$PKG:id/list")
  fi
  if [ $# -ne 4 ]; then
    echo ""
    return 1
  fi
  px=$(( ($1 + $3) / 2 ))
  from=$(( $2 + ($4 - $2) * 80 / 100 ))
  to=$(( $2 + ($4 - $2) * 25 / 100 ))
  for n in 1 2 3 4 5 6; do
    swipe "$px" "$from" "$px" "$to" 700
    sleep 1
    at="$(centre text "$want")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  echo ""
  return 1
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

  # Most of the window, but never all of it.
  #
  # It used to move a quarter of the height at a time, which in landscape is a
  # quarter of 1080 rather than of 2400 -- so thirty drags fell short of the
  # bottom of the settings list and rows that were plainly there were reported
  # missing. Two thirds is still less than one screenful, so no row can pass
  # through the visible area between one look and the next, which is the thing
  # a long drag would otherwise get wrong.
  from=$(( (SCREEN_H * 85) / 100 ))
  to=$(( (SCREEN_H * 20) / 100 ))
  for n in $(seq 1 30); do
    swipe $((SCREEN_W / 2)) "$from" $((SCREEN_W / 2)) "$to" 700
    sleep 1
    at="$(centre text "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  echo ""
  return 1
}


# The home screen, from a cold start.
#
# Force-stopped first for the same reason open_settings is: an activity that is
# already there comes back wherever it was left, and a test that expects the
# folder list would find whatever folder was last opened.
open_home() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$HOME_ACT"
  adb shell "am start -n $HOME_ACT" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) sleep 3; decline_resume; return 0 ;; esac
    sleep 1; waited=$((waited + 1))
  done
  echo
  echo "STOPPING: the home screen did not come to the front within 25s."
  echo "  focus is: $(focused)"
  FAILED=$((FAILED + 1))
  exit 3
}

# Which activity of ours is in front, by class name alone.
#
# The filtering is done here rather than on the device: the phone's grep does
# not take an alternation written this way, and -m1 closes the pipe under
# dumpsys, which prints a broken-pipe warning and returns nothing at all. It
# looked exactly like "no activity of ours is in front".
current_activity() {
  adb shell "dumpsys activity activities" 2>/dev/null \
    | grep -m1 "topResumedActivity" \
    | grep -oE 'com\.brouken\.player\.[A-Za-z]+' | head -1 | tr -d '\r' \
    | sed 's/.*\.//'
}

# Whether the focused thing is the row for this name.
#
# Read off the focused node's own description rather than inferred from where it
# sits. Every row on the home screen describes itself as "<name>, <details>"
# for the benefit of a screen reader, and that turns out to be the only reliable
# way to tell a row apart from the star beside it: the two share a line, so any
# check based on position matches both, and a test meaning to open a folder
# pressed the centre key on its star and quietly favourited it instead.
focused_row_is() {   # focused_row_is <snapshot> <name>
  local line
  line="$(grep 'focused="true"' "$1" | tail -1)"
  case "$line" in
    *"content-desc=\"$2,"*) return 0 ;;
    *"content-desc=\"$2\""*) return 0 ;;
    *) return 1 ;;
  esac
}

# Whether the focused thing is the button described this way. Buttons are
# focused directly, so unlike a row their own node carries the description.
focused_desc_is() {  # focused_desc_is <snapshot> <content-desc>
  grep 'focused="true"' "$1" | tail -1 | grep -qF "content-desc=\"$2\""
}

# Say "not now" to the offer of the last video.
#
# The home screen makes that offer every time it opens, which is what it is for
# and what the setting says it does -- but it sits over the folder list, so a
# test that went looking for folders found a dialog instead. This is the app's
# own dialog, and declining leaves the device exactly as it was found.
#
# Dismissed with Back rather than by tapping the button, which matters more
# than it looks: a tap puts the window into touch mode, and in touch mode a
# list row is not focusable at all, so the first arrow press afterwards lands
# on the toolbar instead of the list and every D-pad check that follows is
# walking the wrong part of the screen. Back leaves the window where a remote
# left it.
decline_resume() {
  local at
  at="$(centre text 'NOT NOW')"
  [ -z "$at" ] && at="$(centre text 'Not now')"
  [ -z "$at" ] && return 1
  adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
  sleep 2
  return 0
}

# Wait for one of this app's screens to settle in front.
#
# A fixed sleep is not enough after leaving the player: releasing a decoder and
# handing the window back takes as long as it takes, and a check a moment too
# early sees neither screen resumed and reads exactly like "we left the app".
wait_for_activity() {   # wait_for_activity <ClassName> [seconds]
  local want="$1" limit="${2:-15}" n=0
  while [ $n -lt "$limit" ]; do
    [ "$(current_activity)" = "$want" ] && return 0
    sleep 1
    n=$((n + 1))
  done
  return 1
}

# Walk the remote down a list until the named row has focus.
#
# Left first, always. A folder row is two focusable things side by side, the
# row and its star, and once focus is in the star column pressing down moves
# star to star for the whole length of the list -- correct behaviour, and it
# means a search for a row by pressing down alone can run to the end without
# ever touching one. Left steps back into the row column; on a row that is
# already in it, it does nothing.
focus_row_by_dpad() {   # focus_row_by_dpad <snapshot> <name> [presses]
  local file="$1" want="$2" limit="${3:-16}" n=0
  adb shell "input keyevent KEYCODE_DPAD_LEFT" >/dev/null 2>&1
  sleep 1
  while [ $n -lt "$limit" ]; do
    dump > "$file"
    focused_row_is "$file" "$want" && return 0
    adb shell "input keyevent KEYCODE_DPAD_DOWN" >/dev/null 2>&1
    sleep 1
    n=$((n + 1))
  done
  return 1
}
