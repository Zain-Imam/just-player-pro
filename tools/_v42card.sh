#!/bin/bash
# On mpv the info card is as wide as the picture.
# A 16:9 clip on this 20:9 phone is about 1920 of the 2400 pixels, centred.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42card.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap cleanup EXIT

ENGINE="${ENGINE:-mpv}"
setpref string playbackEngine "$ENGINE"
setpref boolean overlayOnPause true
setpref int overlayDelaySeconds 0
# a stored identity, as if the film had been looked up online
setpref string onlineIdentities "{&quot;$SERVER/clip.ts&quot;:{&quot;isSeries&quot;:false,&quot;tmdbId&quot;:10378,&quot;title&quot;:&quot;Big Buck Bunny&quot;,&quot;year&quot;:&quot;2008&quot;,&quot;overview&quot;:&quot;A large and lovable rabbit deals with three tiny bullies.&quot;,&quot;rating&quot;:6.5}}"
echo "== $ENGINE"

server "reset=1"
launch_url "$SERVER/clip.ts" "--es title Clip"
sleep 8
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 4
CARD="$(bounds_of resource-id "$PKG:id/online_overlay")"
adb exec-out screencap -p > "$WORK/shots/card-$ENGINE.png"
if [ -z "$CARD" ]; then
  fail "the info card appeared" "$(dump | grep -oE 'resource-id="[^"]*overlay[^"]*"' | head -5 | tr '\n' ' ')"
  exit 1
fi
set -- $CARD
WIDTH=$(( $3 - $1 ))
echo "  card: x $1..$3 (width $WIDTH of 2400)"
if [ $WIDTH -ge 1800 ] && [ $WIDTH -le 2000 ] && [ $1 -ge 180 ]; then
  pass "the card is as wide as the picture, not the screen"
else
  fail "the card is as wide as the picture, not the screen" "width $WIDTH from x=$1"
fi
check "no crash" "$(crashed)"
