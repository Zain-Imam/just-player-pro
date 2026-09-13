#!/bin/bash
#
# #19: changing the shape while paused, on mpv, which used to stay wrong until
#      playback resumed.
# #18: a subtitle that cannot be read must say so rather than be disabled
#      silently while the picker claims it is playing.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
OUT="$WORK/shots"; mkdir -p "$OUT"
item() { echo; echo "-------- $*"; }
shot() { adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "$(hostpath "$OUT/$1.png")" >/dev/null 2>&1; echo "        shot: $1.png"; }

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

prepare

item "19: changing the shape while paused, on mpv"
set_engine mpv || fail "mpv could be selected"
open_film
sleep 3
# show_controls pauses on its way in, which is the state this is about.
controls_up >/dev/null 2>&1
echo "        playing while shape is changed: [$(playing)]  (empty means paused, which is the case under test)"
shot "19-paused-before"
for n in 1 2 3; do
  controls_up >/dev/null 2>&1
  if tap_control Resize; then
    sleep 3
    shot "19-paused-after-$n"
  else
    fail "the resize button can be reached"
    break
  fi
done
[ -n "$(alive)" ] && pass "mpv survived changing shape while paused" \
                  || fail "mpv survived changing shape while paused"
check "nothing crashed changing shape" "$(crashed)"
echo "        (the shots show whether the picture is drawn correctly at once)"

item "18: a subtitle that will not load says so"
# A path that is not there at all: the track is offered, and fails on use.
adb shell "rm -f /sdcard/Movies/missing.srt" >/dev/null 2>&1
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT \
  --grant-read-uri-permission \
  --esa subs file:///sdcard/Movies/missing.srt \
  --esa subs.name BrokenOne" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
sleep 6

controls_up >/dev/null 2>&1
if tap_control "Enable subtitles" "Disable subtitles"; then
  sleep 2
  LIST="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  echo "        picker: $LIST"
  AT="$(centre text 'BrokenOne')"
  if [ -z "$AT" ]; then
    echo "        (the broken subtitle was not even listed; nothing to choose)"
    pass "a subtitle that cannot be read is not offered as though it worked"
    key KEYCODE_BACK; sleep 2
  else
    tap $AT
    SAID=""
    for i in $(seq 1 10); do
      SAID="$(dump | grep -oE 'text="[^"]*would not load[^"]*"' | head -1)"
      [ -n "$SAID" ] && break
    done
    if [ -n "$SAID" ]; then
      pass "it says so: $SAID"
      shot "18-message"
    else
      fail "it says so" "no message seen after choosing a subtitle that cannot be read"
      shot "18-nothing-said"
    fi
  fi
else
  fail "the subtitle picker opened"
fi
check "nothing crashed over a broken subtitle" "$(crashed)"

set_engine Auto >/dev/null 2>&1
echo
cleanup
