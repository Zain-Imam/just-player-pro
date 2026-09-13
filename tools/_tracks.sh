#!/bin/bash
# Does choosing a different subtitle track actually change what is shown?
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"

ENGINE="${2:-Auto}"

set_engine() {
  open_settings
  local at; at="$(scroll_to 'Playback engine')"
  [ -z "$at" ] && return 1
  tap $at; sleep 2
  local c; c="$(centre text "$1")"
  [ -z "$c" ] && { key KEYCODE_BACK; return 1; }
  tap $c; sleep 2
}

# The info card hides the controls when a paused film has been identified, so
# one press is not always enough to get them back.
controls_up() {
  show_controls
  local n
  for n in 1 2 3 4; do
    onscreen content-desc Settings && return 0
    key KEYCODE_DPAD_UP; sleep 2
  done
  onscreen content-desc Settings
}

open_picker() {
  controls_up || return 1
  local at
  at="$(find_control "Disable subtitles" "Enable subtitles")"
  [ -z "$at" ] && return 1
  tap $at; sleep 3
  return 0
}

cues() { dump | grep -oE 'text="[^"]*subtitle line[^"]*"' | head -1; }

prepare
adb push "$(hostpath "$WORK/first.srt")"  /sdcard/Movies/first.srt  >/dev/null 2>&1
adb push "$(hostpath "$WORK/second.srt")" /sdcard/Movies/second.srt >/dev/null 2>&1

echo "== engine: $ENGINE"
set_engine "$ENGINE" || echo "   (could not set engine; using current)"

adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT \
  --grant-read-uri-permission \
  --esa subs file:///sdcard/Movies/first.srt,file:///sdcard/Movies/second.srt \
  --esa subs.name FirstOne,SecondOne" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
sleep 6

echo "== the picker lists:"
if open_picker; then
  dump | grep -oE 'text="[^"]+"' | head -10 | tr '\n' ' '; echo
else
  echo "   could not open the picker"
fi

choose() {   # choose <row text>
  local at
  at="$(centre text "$1")"
  if [ -z "$at" ]; then echo "   no row called $1"; return 1; fi
  tap $at; sleep 5
  return 0
}

try() {   # try <row text>
  echo
  echo "== choosing $1"
  # Close whatever is open first: the picker from the previous round is still
  # on top, and the controls cannot be reached through it.
  if dump | grep -q 'text="Subtitles"'; then key KEYCODE_BACK; sleep 2; fi
  open_picker >/dev/null 2>&1 || { echo "   picker would not open"; return; }
  choose "$1" || return
  # Let it play a little so a cue is on screen.
  key KEYCODE_DPAD_CENTER >/dev/null 2>&1
  sleep 5
  local c; c="$(cues)"; echo "   showing: ${c:-nothing}"
}

try "SecondOne"
try "FirstOne"
try "Off"

echo
echo "crashes: $(crashed)"
adb shell "rm -f /sdcard/Movies/first.srt /sdcard/Movies/second.srt" >/dev/null 2>&1
cleanup
