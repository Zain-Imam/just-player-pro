#!/bin/bash
# Shared harness for the scripts that drive the player over adb.
# The interlock lives here: nothing is pressed unless the player is in front,
# since a press would otherwise land on the launcher.

set -u
export MSYS_NO_PATHCONV=1

if ! command -v adb >/dev/null 2>&1; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
             "$HOME/AppData/Local/Android/Sdk" \
             "$HOME/Android/Sdk" \
             "$HOME/Library/Android/sdk"; do
    [ -n "$sdk" ] || continue
    if [ -x "$sdk/platform-tools/adb" ] || [ -x "$sdk/platform-tools/adb.exe" ]; then
      PATH="$PATH:$sdk/platform-tools"
      break
    fi
  done
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH. Set ANDROID_HOME to your SDK and try again." >&2
  exit 1
fi

PKG="${PKG:-${1:-app.justplayerpro.android.debug}}"
ACT="$PKG/com.brouken.player.PlayerActivity"
HOME_ACT="$PKG/com.brouken.player.HomeActivity"
SETTINGS="$PKG/com.brouken.player.SettingsActivity"
MEDIA="/sdcard/Movies/jpp-smoke.ts"
SUBS="/sdcard/Movies/jpp-smoke.srt"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/../.smoke-media"
mkdir -p "$WORK"

hostpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

PASSED=0
FAILED=0
pass() { echo "PASS  $1"; PASSED=$((PASSED + 1)); }
fail() { echo "FAIL  $1"; [ -n "${2:-}" ] && echo "        $2"; FAILED=$((FAILED + 1)); }
check() { if [ "$2" = "0" ]; then pass "$1"; else fail "$1" "${3:-}"; fi; }

dump() {
  local lock="${WORK:-/tmp}/dump.lock" waited=0
  while [ -e "$lock" ] && [ $waited -lt 30 ]; do
    sleep 0.2
    waited=$((waited + 1))
  done
  : > "$lock"
  adb shell "uiautomator dump /sdcard/jpp-ui.xml >/dev/null 2>&1; cat /sdcard/jpp-ui.xml" 2>/dev/null | tr '<' '\n'
  rm -f "$lock"
  sleep 0.3
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
crashed() {
  adb logcat -b crash -d 2>/dev/null | grep -A3 "FATAL EXCEPTION" \
    | grep -c "Process: $PKG"
}
focused()     { adb shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'; }
focused_app() { adb shell "dumpsys window | grep -m1 mFocusedApp" 2>/dev/null | tr -d '\r'; }

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

  for n in 1 2 3 4 5 6 7 8 9 10; do
    sleep 2
    dismiss_security_prompt
    in_front && return 0
  done

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

prepare() {
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

  adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null > "$WORK/prefs-before.xml"
}

density_reset() {
  local scale
  adb shell wm density reset >/dev/null 2>&1
  scale="$(adb shell settings get system font_scale | tr -d '\r')"
  adb shell settings put system font_scale "$(awk -v s="${scale/null/1.0}" 'BEGIN { printf "%.2f", s + 0.01 }')" >/dev/null 2>&1
  sleep 2
  if [ "$scale" = "null" ] || [ -z "$scale" ]; then
    adb shell settings delete system font_scale >/dev/null 2>&1
  else
    adb shell settings put system font_scale "$scale" >/dev/null 2>&1
  fi
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
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 1
  CURRENT_SCREEN="$SETTINGS"
  adb shell "am start -n $SETTINGS" >/dev/null 2>&1

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

pause_player() {
  local n
  for n in 1 2 3 4 5; do
    [ -z "$(playing)" ] && return 0
    key KEYCODE_MEDIA_PAUSE
    sleep 1
  done
  return 0
}

show_controls() {
  pause_player
  if ! onscreen resource-id "$PKG:id/controls_scroll_view"; then
    key KEYCODE_DPAD_UP
    sleep 1
  fi
  return 0
}

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

panel_row() {
  local want at n px from to
  want="$1"
  at="$(centre text "$want")"
  [ -n "$at" ] && { echo "$at"; return 0; }

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

screen_now() {   # echoes "<width> <height>"
  local size
  size="$(adb shell dumpsys window displays 2>/dev/null \
        | grep -m1 -oE 'cur=[0-9]+x[0-9]+' | cut -d= -f2 | tr -d '\r')"
  [ -z "$size" ] && size="$(adb shell wm size 2>/dev/null \
        | grep -oE '[0-9]+x[0-9]+' | head -1 | tr -d '\r')"
  [ -z "$size" ] && { echo "${SCREEN_W:-1080} ${SCREEN_H:-2400}"; return 0; }
  echo "${size%x*} ${size#*x}"
}

panel_row() {   # panel_row <row text>
  local at n anchor px w h
  at="$(centre text "$1")"
  [ -n "$at" ] && { echo "$at"; return 0; }

  anchor="$(centre text 'Quick settings')"
  [ -z "$anchor" ] && anchor="$(centre text 'Speed')"
  [ -z "$anchor" ] && { echo ""; return 1; }

  set -- $(screen_now) "$1"
  w="$1"; h="$2"; shift 2
  px="$(echo "$anchor" | awk '{print $1}')"

  for n in $(seq 1 8); do
    swipe "$px" $(( (h * 75) / 100 )) "$px" $(( (h * 30) / 100 )) 600
    sleep 1
    at="$(centre text "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  echo ""
  return 1
}

settings_page_of() {
  local list="$WORK/settings-list.tsv"
  [ -s "$list" ] || node "$(hostpath "$HERE/_settings_list.js")" "$(hostpath "$HERE/../app/src/main/res")" > "$list" 2>/dev/null
  awk -F'\t' -v t="$1" '$4 == t {print $1; exit}' "$list"
}

scroll_to() {
  local at n from to w h page
  at="$(centre text "$1")"
  [ -n "$at" ] && { echo "$at"; return 0; }
  if [ "${CURRENT_SCREEN:-}" = "$SETTINGS" ] && onscreen text "Storage and backup"; then
    page="$(settings_page_of "$1")"
    if [ -n "$page" ]; then
      adb shell "am start -n $SETTINGS --es com.brouken.player.SETTINGS_PAGE $page" >/dev/null 2>&1
      sleep 2
      at="$(centre text "$1")"
      [ -n "$at" ] && { echo "$at"; return 0; }
    fi
  fi

  set -- $(screen_now) "$1"
  w="$1"; h="$2"; shift 2
  from=$(( (h * 85) / 100 ))
  to=$(( (h * 20) / 100 ))
  for n in $(seq 1 30); do
    swipe $((w / 2)) "$from" $((w / 2)) "$to" 700
    sleep 1
    at="$(centre text "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
  done
  echo ""
  return 1
}

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

current_activity() {
  adb shell "dumpsys activity activities" 2>/dev/null \
    | grep -m1 "topResumedActivity" \
    | grep -oE 'com\.brouken\.player\.[A-Za-z]+' | head -1 | tr -d '\r' \
    | sed 's/.*\.//'
}

focused_row_is() {   # focused_row_is <snapshot> <name>
  local line
  line="$(grep 'focused="true"' "$1" | tail -1)"
  case "$line" in
    *"content-desc=\"$2,"*) return 0 ;;
    *"content-desc=\"$2\""*) return 0 ;;
    *) return 1 ;;
  esac
}

focused_desc_is() {  # focused_desc_is <snapshot> <content-desc>
  grep 'focused="true"' "$1" | tail -1 | grep -qF "content-desc=\"$2\""
}

decline_resume() {
  local at
  at="$(centre text 'NOT NOW')"
  [ -z "$at" ] && at="$(centre text 'Not now')"
  [ -z "$at" ] && return 1
  adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
  sleep 2
  return 0
}

wait_for_activity() {   # wait_for_activity <ClassName> [seconds]
  local want="$1" limit="${2:-15}" n=0
  while [ $n -lt "$limit" ]; do
    [ "$(current_activity)" = "$want" ] && return 0
    sleep 1
    n=$((n + 1))
  done
  return 1
}

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
