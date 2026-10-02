#!/bin/bash
# Attaching a subtitle keeps the position and the resize button, and the new
# subtitle replaces the old one. Local files only, so no online quota is used.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT

SUB_A="/sdcard/Movies/jpp-first.srt"
SUB_B="/sdcard/Movies/jpp-second.srt"
# real files: mpv lists only what it could load, Media3 lists missing ones too
adb push "$(hostpath "$WORK/first.srt")" "$SUB_A" >/dev/null 2>&1
adb push "$(hostpath "$WORK/second.srt")" "$SUB_B" >/dev/null 2>&1
trap 'adb shell "rm -f $SUB_A $SUB_B" >/dev/null 2>&1; cleanup' EXIT

position() {
  adb shell dumpsys media_session 2>/dev/null \
    | grep -m1 -oE 'state=[A-Z]+\([0-9]\), position=[0-9]+' \
    | grep -oE 'position=[0-9]+' | cut -d= -f2
}
state() {
  adb shell dumpsys media_session 2>/dev/null \
    | grep -m1 -oE 'state=[A-Z]+\([0-9]\)'
}

prepare

echo "=== a film with two subtitles handed over, so both are attached ==="
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT \
  --grant-read-uri-permission \
  --esa subs file://$SUB_A,file://$SUB_B \
  --esa subs.name FirstOne,SecondOne" >/dev/null 2>&1
waited=0
while [ $waited -lt 25 ]; do
  case "$(focused)" in *"$PKG"*) break ;; esac
  sleep 1; waited=$((waited + 1))
done
sleep 6

if [ -n "$(position)" ]; then
  pass "the film opened with both subtitles attached"
else
  fail "the film did not open" "$(state)"
fi

echo
echo "=== both are listed, and under the names they were given ==="
show_controls >/dev/null
tap_control "Disable subtitles" "Enable subtitles" Subtitles Subtitle
sleep 3
LIST="$(dump | grep -oE 'text="[^"]+"' | sed 's/text=//;s/"//g' | tr '\n' '|')"
echo "  the picker shows: $LIST"
if echo "$LIST" | grep -q 'FirstOne' && echo "$LIST" | grep -q 'SecondOne'; then
  pass "both attached subtitles are listed, each under its own name"
else
  fail "the picker is missing one of them" "$LIST"
fi
key KEYCODE_BACK
sleep 2

echo
echo "=== the position holds after a seek ==="
# Far enough in that a jump back to the start is unmistakable.
require_player
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
sleep 2
BEFORE="$(position)"
echo "  playing at: ${BEFORE:-unknown} ms"
sleep 6
AFTER="$(position)"
echo "  six seconds later: ${AFTER:-unknown} ms"
if [ -n "$BEFORE" ] && [ -n "$AFTER" ] && [ "$AFTER" -gt "$BEFORE" ]; then
  pass "the film is moving forward, not being pulled back"
else
  fail "the position did not advance" "before=$BEFORE after=$AFTER"
fi

echo
echo "=== the aspect-ratio button still cycles ==="
show_controls >/dev/null
# read the saved state: the announcement is gone before a dump finishes.
# aspectMap keeps the mode per film; no entry means step 0.
aspect_step() {
  local step
  step="$(adb shell "run-as $PKG cat /data/data/$PKG/shared_prefs/${PKG}_preferences.xml" 2>/dev/null \
    | tr -d '\r' | grep -F 'name="aspectMap"' | sed 's/&quot;/"/g' \
    | grep -oE '"name":"f:jpp-smoke\.ts","delay":-?[0-9]+' | grep -oE '[0-9-]+$')"
  echo "${step:-0}"
}
SHAPES=""
for n in 1 2 3 4 5; do
  tap_control Resize "Aspect ratio" Frame >/dev/null 2>&1
  sleep 2
  SHAPES="$SHAPES $(aspect_step)"
  show_controls >/dev/null
done
echo "  the stored shape after each press:$SHAPES"
DISTINCT="$(echo "$SHAPES" | tr ' ' '\n' | grep -v '^$' | sort -u | wc -l)"
if [ "$DISTINCT" -ge 2 ]; then
  pass "the aspect ratio changes when the button is pressed ($DISTINCT shapes)"
else
  fail "the aspect ratio is stuck" "saw:$SHAPES"
fi

echo
echo "=== and the film is still playing after all of that ==="
if [ -n "$(position)" ]; then
  pass "still playing: $(state)"
else
  fail "the player is gone" "$(state)"
fi

echo
echo "PASSED $PASSED   FAILED $FAILED"
[ "$FAILED" -eq 0 ]
