#!/bin/bash
# First-run pointers, whichever way each one is dismissed.
# Drawn on a canvas, so the screenshots are the evidence.
# Run with "away" (tap clear of the circle) or "press" (press the circle).
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

# the circle is centred on the button; "away" taps the far top corner
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
