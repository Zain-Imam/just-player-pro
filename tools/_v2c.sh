#!/bin/bash
# #11 and #15, with the timing my first attempt got wrong.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
OUT="$WORK/shots"; mkdir -p "$OUT"
item() { echo; echo "-------- $*"; }
shot() { adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "$(hostpath "$OUT/$1.png")" >/dev/null 2>&1; }
controls_up() {
  show_controls
  local n; for n in 1 2 3 4; do onscreen content-desc Settings && return 0; key KEYCODE_DPAD_UP; sleep 2; done
  onscreen content-desc Settings
}

prepare

item "11: the rotate control cycles three ways"
# The announcement lasts 2.5 seconds, so it has to be read straight away —
# the first attempt slept three seconds and always missed it.
open_film
SEEN=""
for n in 1 2 3 4; do
  controls_up >/dev/null 2>&1
  AT="$(find_control Rotate)"
  if [ -z "$AT" ]; then echo "        (rotate button not reachable on pass $n)"; break; fi
  tap $AT
  T="$(dump | grep -oE 'text="[^"]*(orientation|rotate|Auto)[^"]*"' | head -1)"
  echo "        pass $n announced: ${T:-nothing}"
  SEEN="$SEEN
${T:-none}"
  sleep 2
done
UNIQ="$(echo "$SEEN" | sed '/^$/d' | grep -v '^none$' | sort -u | wc -l)"
if [ "$UNIQ" -ge 3 ]; then
  pass "three distinct orientation modes were announced"
else
  fail "three distinct orientation modes" "saw $UNIQ distinct: $(echo "$SEEN" | tr '\n' ' ')"
fi

item "15: buffering says so"
# A big file over the network, polled fast, because the label is only up while
# the player is actually refilling.
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -n $ACT -t video/mp4 \
  -d 'https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4'" >/dev/null 2>&1
FOUND=1
for i in $(seq 1 25); do
  if dump | grep -q 'Buffering'; then FOUND=0; shot "buffering"; break; fi
done
check "the word Buffering appears while loading" "$FOUND" "not seen across 25 looks"

# And whether the spinner itself was ever up, to tell "no label" from "no wait".
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb shell "am start -a android.intent.action.VIEW -n $ACT -t video/mp4 \
  -d 'https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4'" >/dev/null 2>&1
SPIN=1
for i in $(seq 1 25); do
  if dump | grep -qE 'ProgressBar|loading'; then SPIN=0; break; fi
done
if [ "$SPIN" = "0" ]; then
  echo "        (the spinner was seen, so the wait is real)"
else
  echo "        (no spinner seen either — it may simply never buffer here)"
fi

echo
echo "app crashes: $(crashed)"
cleanup
