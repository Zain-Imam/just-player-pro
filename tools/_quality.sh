#!/bin/bash
#
# Does choosing a different video quality actually change the picture?
#
# On both engines, because the mime-prefix bug that stopped subtitles being
# chosen had no video branch at all — mpv never wrote vid, silently. The header
# line names the resolution in use, which is how the answer is read.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
OUT="$WORK/shots"; mkdir -p "$OUT"
HLS="https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8"

item() { echo; echo "-------- $*"; }
shot() { adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "$(hostpath "$OUT/$1.png")" >/dev/null 2>&1; }

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
  local n; for n in 1 2 3 4; do onscreen content-desc Settings && return 0; key KEYCODE_DPAD_UP; sleep 2; done
  onscreen content-desc Settings
}

# What the header says the picture is, e.g. 1920×1080.
resolution() { dump | grep -oE 'text="[0-9]+×[0-9]+[^"]*"' | head -1; }

open_panel() {
  controls_up || return 1
  local at; at="$(find_control Settings)"
  [ -z "$at" ] && return 1
  tap $at; sleep 3
  onscreen text "Quick settings"
}

prepare

for ENGINE in Media3 mpv; do
  item "$ENGINE: switching video quality on a stream with a ladder"
  set_engine "$ENGINE" || { fail "$ENGINE: could not be selected"; continue; }

  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -n $ACT -t application/x-mpegURL -d '$HLS'" >/dev/null 2>&1
  for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
  for i in $(seq 1 12); do sleep 2; [ -n "$(playing)" ] && break; done

  if [ -z "$(playing)" ]; then
    fail "$ENGINE: the stream plays at all"
    continue
  fi
  pass "$ENGINE: the stream plays"

  BEFORE="$(resolution)"
  echo "        header before: ${BEFORE:-none}"

  if ! open_panel; then
    fail "$ENGINE: the quick panel opened"
    continue
  fi

  ROWS="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  case "$ROWS" in
    *"Video quality"*) pass "$ENGINE: a Video quality row is offered" ;;
    *) fail "$ENGINE: a Video quality row is offered" \
            "the panel only lists one video track, so there is nothing to choose: $ROWS"
       key KEYCODE_BACK; sleep 2; continue ;;
  esac

  AT="$(centre text 'Video quality')"
  tap $AT; sleep 3
  LADDER="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  echo "        rungs: $LADDER"
  shot "$ENGINE-quality-list"

  # Pick the lowest rung offered, which is the one most likely to differ from
  # whatever Auto settled on.
  PICK=""
  for P in 234p 270p 360p 396p 480p 540p; do
    if echo "$LADDER" | grep -q "\"$P\""; then PICK="$P"; break; fi
  done
  if [ -z "$PICK" ]; then
    fail "$ENGINE: a specific rung could be chosen" "no recognisable rung in: $LADDER"
    key KEYCODE_BACK; sleep 2; continue
  fi

  C="$(centre text "$PICK")"
  if [ -z "$C" ]; then
    fail "$ENGINE: could reach the rung $PICK"
    key KEYCODE_BACK; sleep 2; continue
  fi
  tap $C; sleep 6
  # Let it actually switch, then read the header again.
  controls_up >/dev/null 2>&1
  AFTER="$(resolution)"
  echo "        header after choosing $PICK: ${AFTER:-none}"
  shot "$ENGINE-quality-after"

  if [ -n "$AFTER" ] && [ "$AFTER" != "$BEFORE" ]; then
    pass "$ENGINE: choosing $PICK changed the picture ($BEFORE to $AFTER)"
  else
    fail "$ENGINE: choosing $PICK changed the picture" \
         "header stayed at ${AFTER:-nothing}"
  fi
  check "$ENGINE: nothing crashed" "$(crashed)"
done

set_engine Auto >/dev/null 2>&1
echo
cleanup
