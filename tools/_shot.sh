#!/bin/bash
# Select each subtitle in turn and photograph the screen, since Media3 draws
# cues on a canvas where the view tree cannot see them.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
OUT="${OUT:-$WORK/shots}"
mkdir -p "$OUT"
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
  if dump | grep -q 'text="Subtitles"'; then key KEYCODE_BACK; sleep 2; fi
  controls_up || return 1
  local at; at="$(find_control "Disable subtitles" "Enable subtitles")"
  [ -z "$at" ] && return 1
  tap $at; sleep 3
}

shot() { adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "$(hostpath "$OUT/$1.png")" >/dev/null 2>&1; echo "   shot: $OUT/$1.png"; }

prepare
adb push "$(hostpath "$WORK/first.srt")"  /sdcard/Movies/first.srt  >/dev/null 2>&1
adb push "$(hostpath "$WORK/second.srt")" /sdcard/Movies/second.srt >/dev/null 2>&1

echo "== engine: $ENGINE"
set_engine "$ENGINE" || echo "   (using current engine)"

adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT \
  --grant-read-uri-permission \
  --esa subs file:///sdcard/Movies/first.srt,file:///sdcard/Movies/second.srt \
  --esa subs.name FirstOne,SecondOne" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
sleep 6

pick() {   # pick <row> <shot name>
  echo "== $1"
  open_picker || { echo "   picker would not open"; return; }
  local at; at="$(centre text "$1")"
  [ -z "$at" ] && { echo "   no row $1"; return; }
  tap $at; sleep 3
  # Play again, by the control's name rather than a bare OK: with the
  # controls up, OK activates whatever holds the focus.
  if [ -z "$(playing)" ]; then tap_control Play Pause >/dev/null 2>&1; fi
  # Let the controls fade of their own accord, so they are not over the cue.
  sleep 9
  echo "   playing now: [$(playing)]"
  shot "$2"
}

pick "SecondOne" "$ENGINE-second"
pick "FirstOne"  "$ENGINE-first"
pick "Off"       "$ENGINE-off"

echo "crashes: $(crashed)"
adb shell "rm -f /sdcard/Movies/first.srt /sdcard/Movies/second.srt /sdcard/s.png" >/dev/null 2>&1
cleanup
