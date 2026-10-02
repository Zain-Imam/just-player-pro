#!/bin/bash
# Helpers for the device checks. Sourced after lib.sh.
# Preferences are written straight into the debug build's prefs file; the app
# is stopped first, since a running app would write its own copy over it.

PREFS_FILE="shared_prefs/${PKG}_preferences.xml"
V42_SCRATCH="${V42_SCRATCH:-$WORK}"
SERVER="http://127.0.0.1:8090"

prefs_read() {
  adb shell "run-as $PKG cat $PREFS_FILE" 2>/dev/null | tr -d '\r'
}

# setpref <string|boolean|int|float> <name> <value>
setpref() {
  local type="$1" name="$2" value="$3" current tmp
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  current="$(prefs_read)"
  case "$current" in
    *"<map"*) ;;
    *) current="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
</map>" ;;
  esac
  tmp="$V42_SCRATCH/prefs.xml"
  # Drop any line for this name, then add the new one before </map>.
  echo "$current" | grep -v "name=\"$name\"" | grep -v '^<map />$' | sed '/<\/map>/d' > "$tmp"
  grep -q '<map>' "$tmp" || echo "<map>" >> "$tmp"
  if [ "$type" = "string" ]; then
    echo "    <string name=\"$name\">$value</string>" >> "$tmp"
  else
    echo "    <$type name=\"$name\" value=\"$value\" />" >> "$tmp"
  fi
  echo "</map>" >> "$tmp"
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > $PREFS_FILE'" < "$tmp"
}

getpref() {
  prefs_read | grep -F "name=\"$1\"" | head -1
}

# launch_url <url> [extra am arguments...]: as another app would hand it over
launch_url() {
  local url="$1"
  shift
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$url' -n $ACT $*" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 20 ]; do
    case "$(focused)" in *"$PKG"*) sleep 2; refresh_screen; return 0 ;; esac
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# playback position in ms from the media session, moved on by the time since
# it was reported when playing
position_ms() {
  local now
  now="$(adb shell 'cat /proc/uptime' | tr -d '\r' | awk '{print int($1*1000)}')"
  adb shell dumpsys media_session 2>/dev/null | tr -d '\r'     | awk -v want="package=$PKG" -v now="$now" '
      { line = $0; sub(/^[ \t]+/, "", line) }
      line == want { mine = 1; next }
      line ~ /^package=/ { mine = 0 }
      mine && /state=PlaybackState/ && !done {
        match($0, /position=[0-9]+/); p = substr($0, RSTART+9, RLENGTH-9) + 0
        match($0, /speed=[0-9.]+/); sp = substr($0, RSTART+6, RLENGTH-6) + 0
        match($0, /updated=[0-9]+/); u = substr($0, RSTART+8, RLENGTH-8) + 0
        if ($0 ~ /PLAYING/) p += (now - u) * sp
        print int(p); done = 1 }'
}

player_log() {
  adb logcat -d 2>/dev/null | tr -d '\r' | grep -E "MpvPlayer|JustPlayer|SubtitleFiles" | tail -n "${1:-40}"
}

server() {
  curl -s "$SERVER/ctl?$1" >/dev/null
}

# The seek bar's row, so a tap can land at a share of its width.
timebar_bounds() {
  bounds_of resource-id "$PKG:id/exo_progress"
}

tap_timebar_at() {
  local share="$1" b
  b="$(timebar_bounds)"
  [ -z "$b" ] && return 1
  set -- $b
  local x=$(( $1 + ( ($3 - $1) * share / 1000 ) ))
  local y=$(( ($2 + $4) / 2 ))
  adb shell "input tap $x $y" >/dev/null 2>&1
}

# A double tap the system cannot mistake for two single taps.
# each input tap takes a few hundred ms to start, longer than the double-tap
# window, so two are started 150 ms apart in parallel; raw touch is refused.
double_tap_at() {   # double_tap_at <x> <y> [taps]
  local x="$1" y="$2" n="${3:-2}" cmd="" i
  for i in $(seq 1 $((n - 1))); do cmd="$cmd input tap $x $y & sleep 0.15;"; done
  adb shell "$cmd input tap $x $y; wait" >/dev/null 2>&1
}
