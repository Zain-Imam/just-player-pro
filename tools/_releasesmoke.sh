#!/bin/bash
# The real release build (minified, renamed) installed beside the user's app as
# .rtest: every screen opened once, the crash log read after each.
# Usage: tools/_releasesmoke.sh <apk>
APK="$1"
export PKG=app.justplayerpro.android.rtest
set --
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
OUT="$WORK/release-smoke"
rm -rf "$OUT"; mkdir -p "$OUT"
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill $SERVER_PID 2>/dev/null; adb reverse --remove tcp:8090 >/dev/null 2>&1; cleanup' EXIT
adb install -r -d -t "$(hostpath "$APK")" | tail -1
adb logcat -b crash -c >/dev/null 2>&1
crashes() { adb logcat -b crash -d 2>/dev/null | grep -c "Process: $PKG"; }
step() {   # step <name>: a screenshot, and no crash so far
  adb exec-out screencap -p > "$OUT/$1.png"
  local c; c="$(crashes)"
  if [ "$c" = "0" ]; then pass "$1"; else fail "$1" "crashed"; adb logcat -b crash -d | grep -A8 "Process: $PKG" | head -12; exit 1; fi
}

prepare >/dev/null
setpref boolean introSeen true
setpref string startOn home
setpref boolean askResume false

echo "== Home"
CURRENT_SCREEN="$HOME_ACT"
adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 5
step home-list
setpref string homeLayout wall
adb shell "am force-stop $PKG"; adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 5
step home-wall
setpref string homeLayout list

if [ -z "${ONLY_HOME:-}" ]; then
echo "== Settings, every page"
open_settings
step settings-hub
for page in "Appearance" "Home screen" "Playback" "Audio" "Gestures and controls" "Subtitles" "Online info" "Storage and backup" "Advanced" "About"; do
  open_settings
  at="$(centre text "$page")"
  [ -z "$at" ] && { set -- $(screen_now); swipe $(( $1 / 2 )) $(( $2 * 75 / 100 )) $(( $1 / 2 )) $(( $2 * 30 / 100 )) 600; sleep 1; at="$(centre text "$page")"; }
  [ -n "$at" ] && { tap $at; sleep 2; } || fail "found $page"
  step "settings-$(echo "$page" | tr ' ' '-')"
done
open_settings
tap $(centre text "Appearance"); sleep 2
tap $(centre text "Player style"); sleep 2
step settings-style-pictures
key KEYCODE_BACK; sleep 1
tap $(centre text "Language"); sleep 2
step settings-language
key KEYCODE_BACK; sleep 1
open_settings
at="$(centre content-desc 'Search settings')"; [ -n "$at" ] && { tap $at; sleep 1; adb shell input text "subtitle"; sleep 2; }
step settings-search

fi
echo "== Introduction"
setpref boolean introSeen false
adb shell "am force-stop $PKG"; CURRENT_SCREEN="$HOME_ACT"; adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 5
step intro-1
for n in 2 3 4 5 6; do
  at="$(centre text 'Next')"; [ -n "$at" ] && tap $at; sleep 2
  step "intro-$n"
done
at="$(centre text 'Done')"; [ -n "$at" ] && tap $at; sleep 2
step intro-done
setpref boolean introSeen true

if [ -z "${ONLY_HOME:-}" ]; then
echo "== Player"
for engine in media3 mpv; do
  for style in classic glass material; do
    [ "$engine" = mpv ] && [ "$style" != classic ] && continue
    setpref string playbackEngine "$engine"
    setpref string playerStyle "$style"
    open_film
    show_controls
    step "player-$engine-$style"
  done
  tap_control "Settings" >/dev/null; sleep 2; step "quick-$engine"; key KEYCODE_BACK; sleep 1
  tap_control "Subtitles" "Enable subtitles" "Disable subtitles" >/dev/null; sleep 2; step "subtitles-$engine"; key KEYCODE_BACK; sleep 1
  tap_control "Audio track" >/dev/null; sleep 2; step "audio-$engine"; key KEYCODE_BACK; sleep 1
  tap_control "Lock screen" >/dev/null; sleep 2; step "locked-$engine"
  adb shell "am force-stop $PKG"
done
setpref string playbackEngine auto
setpref string playerStyle classic

fi
echo "== Recent, its menu and Film info"
node "$(hostpath "$HERE/faultserver.js")" 8090 >/dev/null 2>&1 &
SERVER_PID=$!
sleep 2
adb reverse tcp:8090 tcp:8090 >/dev/null 2>&1
launch_url "$SERVER/showcase/download/Sintel.2010.1080p.mkv" "-t video/x-matroska"
for n in $(seq 1 20); do adb logcat -d 2>/dev/null | grep -q "First frame on" && break; sleep 1; done
sleep 5
pause_player; key KEYCODE_BACK; sleep 1; key KEYCODE_BACK; sleep 2
adb shell "am force-stop $PKG"; CURRENT_SCREEN="$HOME_ACT"; adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 5
at="$(dump | grep 'id/video_name' | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')"
if [ -n "$at" ]; then
  set -- $at; adb shell input swipe "$1" "$2" "$1" "$2" 900; sleep 2
  step recent-menu
  at="$(centre resource-id "$PKG:id/recent_menu_info")"; [ -n "$at" ] && { tap $at; sleep 4; }
  step film-info
  at="$(centre resource-id "$PKG:id/film_change")"; [ -n "$at" ] && { tap $at; sleep 3; }
  step film-change
else
  fail "a Recent row"
fi
adb shell "am force-stop $PKG"; CURRENT_SCREEN="$HOME_ACT"; adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 4
at="$(centre text 'Movies')"; [ -n "$at" ] && { tap $at; sleep 3; step folder; }
adb shell "am force-stop $PKG"; adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 4
at="$(centre content-desc 'Search')"; [ -n "$at" ] && { tap $at; sleep 1; adb shell input text "s"; sleep 2; step home-search; }
adb shell "am force-stop $PKG"; adb shell "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 4
at="$(centre content-desc 'Sort')"; [ -n "$at" ] && { tap $at; sleep 2; step home-sort; }
check "no crash anywhere" "$(crashes)"
