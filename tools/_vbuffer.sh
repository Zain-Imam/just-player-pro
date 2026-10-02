#!/bin/bash
# The spinner stays when the controls hide.
# Lets the controls auto-hide, then seeks far ahead so the player must refill;
# a spinner inside the controls would leave nothing on screen.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1
         echo "        shot: $1"; }

URL="https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

prepare
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d '$URL' -t application/x-mpegURL -n $ACT" >/dev/null 2>&1

n=0
while [ $n -lt 25 ]; do
  [ -n "$(playing)" ] && break
  sleep 1; n=$((n + 1))
done
echo "playing: $(playing)"

echo "--- waiting for the controls to take themselves away ---"
sleep 6
shot buf-controls-hidden

echo "--- jumping a long way forward, controls already hidden ---"
for i in $(seq 1 20); do
  adb shell "input keyevent KEYCODE_DPAD_RIGHT" >/dev/null 2>&1
done
shot buf-after-seek-1
shot buf-after-seek-2
shot buf-after-seek-3
echo "focus: $(focused)"
