#!/bin/bash
# 1, on a remote: OK presses the pointer, Back puts it away without pressing it,
# and neither walks out of the player.
#
# Run with "ok" or "back".
. "$(dirname "$0")/lib.sh"
WAY="${1:-ok}"
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
shot "$WAY-k1"

if [ "$WAY" = ok ]; then
  KEY=KEYCODE_DPAD_CENTER
else
  KEY=KEYCODE_BACK
fi

echo "--- $KEY on the first pointer ---"
key "$KEY"
sleep 3
shot "$WAY-k2"
case "$(focused)" in
  *"$PKG"*) pass "still in the player after the first press" ;;
  *) fail "the press left the player" "focus is $(focused)"; exit 1 ;;
esac

echo "--- $KEY on the second ---"
key "$KEY"
sleep 3
shot "$WAY-k3"
case "$(focused)" in
  *"$PKG"*) pass "still in the player after the second press" ;;
  *) fail "the press left the player" "focus is $(focused)" ;;
esac
echo "focus: $(focused)"
