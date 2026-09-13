#!/bin/bash
# 1: two pointers on a first run, whichever way each one is dismissed.
#
# The pictures are the evidence: a tap target is drawn on a canvas, so there is
# no text in the view tree for a screen dump to find.
#
# Run it with a path: "away" taps clear of the circle, "press" presses the
# circle itself -- which is the one that was broken, because pressing it used to
# open the file picker there and then and the second pointer waited for a
# callback that never came.
. "$(dirname "$0")/lib.sh"
WAY="${1:-away}"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1
         echo "        shot: $1"; }

adb shell "pm clear $PKG" >/dev/null 2>&1
sleep 2
CURRENT_SCREEN="$ACT"
adb shell "am start -n $ACT" >/dev/null 2>&1
n=0
while [ $n -lt 20 ]; do
  case "$(focused)" in *"$PKG"*) break ;; esac
  sleep 1; n=$((n + 1))
done
sleep 4
shot "$WAY-1"

# The circle is centred on the button it points at, which is the middle of the
# bottom strip; clear of it means the far top corner of the picture.
if [ "$WAY" = press ]; then
  set -- $(bounds_of content-desc "Open file")
  if [ $# -ne 4 ]; then set -- $(bounds_of content-desc "Open"); fi
  if [ $# -eq 4 ]; then
    AT="$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
  else
    echo "could not find the open button"; exit 1
  fi
  echo "--- pressing the circle itself, at $AT ---"
  tap $AT
else
  echo "--- tapping clear of the circle ---"
  tap 150 400
fi
sleep 3
shot "$WAY-2"

echo "--- and dismissing the second one ---"
tap 150 400
sleep 3
shot "$WAY-3"
echo "focus: $(focused)"
