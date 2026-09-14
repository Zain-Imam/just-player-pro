#!/bin/bash
# Dragging the bar shows the frame you are heading for -- on a file, not a stream.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-thumbs.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

# An mp4, not the smoke clip.
#
# Frames come from MediaMetadataRetriever, which opens the file a second time
# and decodes at a keyframe. It is good at mp4 and mkv -- what films actually
# come as -- and poor at a raw transport stream, which is what the smoke clip
# is: no index, so it often returns nothing at all. Testing the preview against
# the one container it cannot read would say nothing about the preview.
SAMPLE="/sdcard/Movies/jpp-thumbs.mp4"
prepare
if [ ! -f "$WORK/slow.mp4" ]; then
  echo "fetching a sample film"
  curl -sL --max-time 180 -o "$WORK/slow.mp4" \
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_5MB.mp4"
fi
adb push "$(hostpath "$WORK/slow.mp4")" "$SAMPLE" >/dev/null 2>&1 || { fail "could not push the sample"; exit 2; }
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$SAMPLE" >/dev/null 2>&1
sleep 3
SID=$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-thumbs.mp4'\"" 2>/dev/null \
     | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')
[ -z "$SID" ] && { fail "could not index the sample"; exit 2; }
SURI="content://media/external/video/media/$SID"
cleanup_sample() { adb shell "rm -f $SAMPLE" >/dev/null 2>&1; adb shell "content delete --uri $SURI" >/dev/null 2>&1; }
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $SURI -t video/mp4 -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
for n in $(seq 1 15); do
  case "$(focused)" in *"$PKG"*) break ;; esac
  sleep 1
done
sleep 4
show_controls >/dev/null
refresh_screen

BAR="$(bounds_of resource-id "$PKG:id/exo_progress")"
[ -z "$BAR" ] && { fail "no seek bar"; exit 1; }
set -- $BAR
# The bar is drawn at the bottom of its view and its touch target sits on the
# bar, not on the middle of the view: a drag four pixels above the bottom edge
# scrubs, and one through the middle does nothing at all.
BAR_Y=$(( $4 - 4 ))
FROM=$(( $1 + ($3 - $1) / 6 ))
TO=$(( $1 + ($3 - $1) * 3 / 4 ))
echo "  dragging the bar from $FROM to $TO at y=$BAR_Y"

position() { adb shell "dumpsys media_session | grep -m1 -oE 'position=[0-9]+'" 2>/dev/null | tr -d '\r' | cut -d= -f2; }
echo "  position before: $(position)"

# A long, slow drag, so the screen can be read while the finger is still down.
# `input swipe` holds for the duration it is given.
require_player
adb shell "input swipe $FROM $BAR_Y $TO $BAR_Y 9000" >/dev/null 2>&1 &
sleep 3
shot thumbs-dragging
echo "  position during: $(position)"

# Asked of the view hierarchy rather than of uiautomator.
#
# uiautomator describes what a screen reader would be told about, and a plain
# decorative image is not in that -- the preview never appears in a dump,
# visible or not. The hierarchy dump carries every view and its visibility
# flag: V for on screen, G for gone.
hierarchy() { adb shell "dumpsys activity top" 2>/dev/null | grep -F 'id/thumbnail_preview' | tail -1 | tr -d '\r'; }
DURING="$(hierarchy)"
echo "  the preview view: $DURING"
wait

if echo "$DURING" | grep -qE 'ImageView\{[0-9a-f]+ V'; then
  pass "the preview is on screen during the drag"
else
  fail "no preview while dragging" "the hierarchy says: [$DURING]"
fi

sleep 3
shot thumbs-after
AFTER="$(hierarchy)"
echo "  after the drag:   $AFTER"
if echo "$AFTER" | grep -qE 'ImageView\{[0-9a-f]+ V'; then
  fail "the preview stayed after the drag" "$AFTER"
else
  pass "the preview goes when the drag ends"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

cleanup_sample
