#!/bin/bash
# A file this device has no hardware decoder for says so before it stalls.
#
# The test file is AV1, which this phone decodes only in software -- exactly the
# case the warning exists for. A file it can decode in hardware must produce no
# warning at all, which is the other half of the test: a warning that appears
# for everything is a warning nobody reads.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-capability.txt"
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
SAMPLE="/sdcard/Movies/jpp-av1.mp4"

prepare

echo "=== a file the chip can decode says nothing ==="
open_film
sleep 4
snap
shot capability-plain
if grep -q 'may struggle' "$SNAP"; then
  fail "an ordinary H.264 file was warned about"
else
  pass "no warning for a file the device decodes in hardware"
fi

echo
echo "=== a file it can only decode in software warns ==="
if [ ! -f "$WORK/av1.mp4" ]; then
  echo "fetching an AV1 sample"
  curl -sL --max-time 180 -o "$WORK/av1.mp4" \
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/av1/1080/Big_Buck_Bunny_1080_10s_1MB.mp4"
fi
adb push "$(hostpath "$WORK/av1.mp4")" "$SAMPLE" >/dev/null 2>&1 || { fail "could not push the sample"; exit 2; }
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$SAMPLE" >/dev/null 2>&1
sleep 3
SID=$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-av1.mp4'\"" 2>/dev/null \
     | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')
[ -z "$SID" ] && { fail "could not index the sample"; exit 2; }
SURI="content://media/external/video/media/$SID"
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $SURI -t video/mp4 -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
for n in $(seq 1 15); do
  case "$(focused)" in *"$PKG"*) break ;; esac
  sleep 1
done
sleep 6
snap
shot capability-warned

echo "  on screen: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"
if grep -q 'may struggle' "$SNAP"; then
  pass "the warning came up"
else
  fail "no warning for a file with no hardware decoder"
  adb shell "rm -f $SAMPLE" >/dev/null 2>&1
  adb shell "content delete --uri $SURI" >/dev/null 2>&1
  exit 1
fi

# The offer of the other engine belongs on Media3 only: from mpv there is
# nowhere better to go, and a button that changes nothing is worse than no
# button. So which engine is playing decides what must be there.
ENGINE="$(adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null \
  | grep -oE 'name="playbackEngine">[a-z0-9]+' | cut -d'>' -f2 | tr -d '\r')"
ENGINE="${ENGINE:-auto}"
echo "  engine: $ENGINE"
WANTED="Play it anyway|Close the file|Do not warn me again"
if [ "$ENGINE" = "mpv" ]; then
  if tr 'A-Z' 'a-z' < "$SNAP" | grep -qF "try mpv"; then
    fail "mpv was offered as a way out of mpv"
  else
    pass "no engine to switch to, so none is offered"
  fi
else
  WANTED="$WANTED|Try mpv"
fi

# Case-insensitively: the dialog theme puts its buttons in capitals.
for want in $(echo "$WANTED" | tr '|' '\n' | tr ' ' '_'); do
  want="$(echo "$want" | tr '_' ' ')"
  # Both sides folded by hand: grep -i on this shell dies on the dump.
  if tr 'A-Z' 'a-z' < "$SNAP" | grep -qF "$(echo "$want" | tr 'A-Z' 'a-z')"; then
    pass "it offers: $want"
  else
    fail "the warning is missing: $want"
  fi
done

echo "--- playing it anyway ---"
AT="$(centre text 'PLAY IT ANYWAY')"
[ -z "$AT" ] && AT="$(centre text 'Play it anyway')"
if [ -z "$AT" ]; then
  fail "could not reach the continue button"
else
  tap $AT
  sleep 6
  if [ -n "$(playing)" ]; then
    pass "it plays after the warning is accepted"
  else
    fail "nothing played after the warning" \
         "state: $(adb shell 'dumpsys media_session | grep -o \"state=[A-Z]*\" | head -1' | tr -d '\r')"
  fi
  snap
  if grep -q 'may struggle' "$SNAP"; then
    fail "the warning came back for the same file"
  else
    pass "it is asked once, not over and over"
  fi
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

adb shell "rm -f $SAMPLE" >/dev/null 2>&1
adb shell "content delete --uri $SURI" >/dev/null 2>&1
