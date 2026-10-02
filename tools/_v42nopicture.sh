#!/bin/bash
# A film that plays its sound but never shows a picture is noticed.
# Uses Dolby Vision profile 5 and 8.1 samples from repo.jellyfin.org/test-videos.
# Usage: PKG=<debug package> tools/_v42nopicture.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
P5=/sdcard/Movies/jpp-dvp5.mp4
P81=/sdcard/Movies/jpp-dvp81.mp4
trap 'adb shell "rm -f $P5 $P81" >/dev/null 2>&1; for id in ${ID5:-} ${ID81:-}; do adb shell "content delete --uri content://media/external/video/media/$id" >/dev/null 2>&1; done; setpref string playbackEngine "$ENGINE_BEFORE"; cleanup' EXIT

ENGINE_BEFORE="$(getpref playbackEngine | grep -oE '>[a-z0-9]+<' | tr -d '<>')"
ENGINE_BEFORE="${ENGINE_BEFORE:-auto}"
setpref boolean overlayOnPause false

index() {   # index <device path> <name>
  adb push "$(hostpath "$WORK/$3")" "$1" >/dev/null 2>&1
  adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$1" >/dev/null 2>&1
  local id="" n
  for n in $(seq 1 15); do
    id="$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$2'\"" 2>/dev/null \
      | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')"
    [ -n "$id" ] && break
    sleep 1
  done
  echo "$id"
}
ID5="$(index $P5 jpp-dvp5.mp4 dv-p5.mp4)"
ID81="$(index $P81 jpp-dvp81.mp4 dv-p81.mp4)"
[ -z "$ID5" ] || [ -z "$ID81" ] && { fail "the samples are in the media store"; exit 1; }

open_on() {   # open_on <engine> <id>
  setpref string playbackEngine "$1"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d content://media/external/video/media/$2 -t video/mp4 -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
}
said() { adb logcat -d 2>/dev/null | tr -d '\r' | grep -E "JustPlayer" | grep -oE "$1" | head -1; }

echo "-- A: Media3 chosen, profile 5, by remote"
# a key takes the new window out of touch mode, so Try can hold focus for OK
open_on media3 "$ID5"
sleep 1
adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
sleep 13
NOPIC="$(said 'No picture after [0-9]+ms of playback on media3')"
OFFER="$(dump | grep -ioE 'text="No picture"|text="Try mpv"' | tr '\n' ' ')"
echo "  log: ${NOPIC:-nothing}; on screen: ${OFFER:-nothing}"
[ -n "$NOPIC" ] && echo "$OFFER" | grep -q 'No picture' && pass "A: no picture on Media3 is noticed and the other engine offered" \
  || fail "A: no picture on Media3 is noticed and the other engine offered" "log ${NOPIC:-none}, screen ${OFFER:-none}"
adb logcat -c >/dev/null 2>&1
adb shell "input keyevent KEYCODE_DPAD_CENTER" >/dev/null 2>&1
sleep 12
FIRST="$(said 'First frame on mpv')"
NOW="$(getpref playbackEngine | grep -oE '>[a-z0-9]+<' | tr -d '<>')"
echo "  remote OK: engine $NOW, ${FIRST:-no frame}"
[ "$NOW" = "mpv" ] && [ -n "$FIRST" ] && pass "A: remote -- OK on the offer moves it to mpv, which shows a picture" \
  || fail "A: remote -- OK on the offer moves it to mpv, which shows a picture" "engine $NOW, ${FIRST:-no frame}"

echo "-- A2: Media3 chosen, profile 5, by touch"
open_on media3 "$ID5"
sleep 14
TRY="$(bounds_of text 'Try mpv')"
[ -z "$TRY" ] && TRY="$(bounds_of text 'TRY MPV')"
if [ -z "$TRY" ]; then
  fail "A2: the offer has a Try mpv button" "$(dump | grep -oE 'text="[^"]+"' | head -8 | tr '\n' ' ')"
else
  set -- $TRY
  adb logcat -c >/dev/null 2>&1
  adb shell "input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))" >/dev/null 2>&1
  sleep 12
  FIRST="$(said 'First frame on mpv')"
  NOW="$(getpref playbackEngine | grep -oE '>[a-z0-9]+<' | tr -d '<>')"
  echo "  tapped Try mpv: engine $NOW, ${FIRST:-no frame}"
  [ "$NOW" = "mpv" ] && [ -n "$FIRST" ] && pass "A2: touch -- Try mpv moves it to mpv, which shows a picture" \
    || fail "A2: touch -- Try mpv moves it to mpv, which shows a picture" "engine $NOW, ${FIRST:-no frame}"
fi

echo "-- B: Auto, profile 5"
open_on auto "$ID5"
sleep 16
FIRST="$(said 'First frame on mpv')"
echo "  ${FIRST:-no frame on mpv}; $(said 'Falling back to mpv for this file|No picture after [0-9]+ms of playback on media3')"
[ -n "$FIRST" ] && pass "B: Auto ends up on mpv, with a picture" \
  || fail "B: Auto ends up on mpv, with a picture" "$(adb logcat -d | tr -d '\r' | grep JustPlayer | grep -iE 'fallback|picture|frame' | tail -3)"

echo "-- C: Media3 chosen, profile 8.1"
open_on media3 "$ID81"
sleep 14
FIRST="$(said 'First frame on media3')"; NOPIC="$(said 'No picture after')"
echo "  ${FIRST:-no frame}; ${NOPIC:-no alarm}"
[ -n "$FIRST" ] && [ -z "$NOPIC" ] && pass "C: profile 8.1 plays on Media3 with a picture, and no alarm" \
  || fail "C: profile 8.1 plays on Media3 with a picture, and no alarm" "${FIRST:-no frame}, ${NOPIC:-no alarm}"

echo "-- D: mpv chosen, profile 5"
open_on mpv "$ID5"
sleep 14
FIRST="$(said 'First frame on mpv')"; NOPIC="$(said 'No picture after')"
echo "  ${FIRST:-no frame}; ${NOPIC:-no alarm}"
[ -n "$FIRST" ] && [ -z "$NOPIC" ] && pass "D: mpv draws profile 5, and no alarm" \
  || fail "D: mpv draws profile 5, and no alarm" "${FIRST:-no frame}, ${NOPIC:-no alarm}"

check "no crash" "$(crashed)"
echo
echo "$PASSED passed, $FAILED failed"
