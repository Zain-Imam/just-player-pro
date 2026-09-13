#!/bin/bash
# Item 6: two pointers on a first run -- the files, then the key.
# They are drawn on a canvas, so uiautomator cannot read them: the evidence is
# the pictures.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1
         echo "        shot: $1"; }

prepare
adb shell "pm clear $PKG" >/dev/null 2>&1
sleep 2
open_film
sleep 3
shot p1-open
echo "--- tapping away from the circle, which is how it closes ---"
tap 60 60
sleep 3
shot p2-key
echo "--- and again ---"
tap 60 60
sleep 2
shot p3-gone
echo "focus: $(focused)"
