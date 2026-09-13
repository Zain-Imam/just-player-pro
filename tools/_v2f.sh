#!/bin/bash
#
# What is left: subtitle size parity between the engines and across aspect
# modes, the HD mark on the quality row, and the one case #18 is actually about
# — a subtitle that becomes a track and then fails when it is used.
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
open_panel() {
  controls_up || return 1
  local at; at="$(find_control Settings)"
  [ -z "$at" ] && return 1
  tap $at; sleep 3
  onscreen text "Quick settings"
}
play_on() { [ -z "$(playing)" ] && tap_control Play Pause >/dev/null 2>&1; sleep 6; }

prepare
adb push "$(hostpath "$WORK/first.srt")" /sdcard/Movies/first.srt >/dev/null 2>&1

# ---------------------------------------------------- subtitle size parity
for ENGINE in Media3 mpv; do
  item "17 size: $ENGINE, fit then crop"
  set_engine "$ENGINE" >/dev/null 2>&1
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT \
    --grant-read-uri-permission --esa subs file:///sdcard/Movies/first.srt \
    --esa subs.name Sized --esa subs.enable file:///sdcard/Movies/first.srt" >/dev/null 2>&1
  for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
  play_on
  shot "size-$ENGINE-fit"
  # One press of the frame button moves off fit.
  controls_up >/dev/null 2>&1
  tap_control Resize >/dev/null 2>&1
  play_on
  shot "size-$ENGINE-crop"
  pass "$ENGINE: captured fit and crop for comparison"
done

# ---------------------------------------------------------- the HD mark
item "21: the quality row carries an HD mark"
set_engine Auto >/dev/null 2>&1
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -n $ACT -t application/x-mpegURL \
  -d 'https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8'" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
for i in $(seq 1 12); do sleep 2; [ -n "$(playing)" ] && break; done
if open_panel; then
  if dump | grep -q 'Video quality'; then
    pass "the quality row is present"
    shot "21-hd-icon"
  else
    fail "the quality row is present"
  fi
  key KEYCODE_BACK; sleep 2
else
  fail "the quick panel opened"
fi

# ------------------------------------------- 18, in the condition it is about
item "18: a subtitle that loads as a track and then fails"
# Without storage permission the app can see the track but cannot read the
# file, which is exactly what the original log showed: the player disables the
# track and says nothing.
adb shell "pm revoke $PKG android.permission.READ_MEDIA_VIDEO" >/dev/null 2>&1
adb shell "appops set $PKG MANAGE_EXTERNAL_STORAGE ignore" >/dev/null 2>&1
sleep 2
set_engine Media3 >/dev/null 2>&1
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT \
  --grant-read-uri-permission --esa subs file:///sdcard/Movies/first.srt \
  --esa subs.name Unreadable" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
sleep 6

controls_up >/dev/null 2>&1
if tap_control "Enable subtitles" "Disable subtitles"; then
  sleep 2
  echo "        picker: $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  AT="$(centre text 'Unreadable')"
  if [ -z "$AT" ]; then
    echo "        (not offered at all, so there is nothing to mislead you with)"
    pass "an unreadable subtitle is not presented as playing"
    key KEYCODE_BACK; sleep 2
  else
    tap $AT
    SAID=""
    for i in $(seq 1 12); do
      SAID="$(dump | grep -oE 'text="[^"]*would not load[^"]*"' | head -1)"
      [ -n "$SAID" ] && break
    done
    if [ -n "$SAID" ]; then
      pass "it says so: $SAID"
      shot "18-message"
    else
      fail "it says so" "chosen, but nothing was said"
      shot "18-nothing-said"
      echo "        log: $(adb logcat -d 2>/dev/null | grep -i 'Disabling track' | tail -1)"
    fi
  fi
else
  fail "the subtitle picker opened"
fi

# Put the permissions back, whatever happened above.
adb shell "pm grant $PKG android.permission.READ_MEDIA_VIDEO" >/dev/null 2>&1
adb shell "appops set $PKG MANAGE_EXTERNAL_STORAGE allow" >/dev/null 2>&1
echo "        (storage permission restored)"

check "nothing crashed" "$(crashed)"
set_engine Auto >/dev/null 2>&1
adb shell "rm -f /sdcard/Movies/first.srt" >/dev/null 2>&1
echo
cleanup
