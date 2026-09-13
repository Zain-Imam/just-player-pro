#!/bin/bash
# 9, the other half: the info card must not describe the film before this one.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
seen() { dump | grep -q "$1"; }

prepare
SECOND="/sdcard/Movies/jpp-second.ts"
adb shell "cp $MEDIA $SECOND" >/dev/null 2>&1
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$SECOND" >/dev/null 2>&1
sleep 3
ID2=$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-second.ts'\"" 2>/dev/null \
     | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')
[ -z "$ID2" ] && { fail "could not index the second file"; exit 2; }
URI2="content://media/external/video/media/$ID2"

open_film
echo "--- telling it the first film is Inception ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
AT="$(centre content-desc 'Search again')"
[ -z "$AT" ] && { fail "no search icon"; exit 1; }
tap $AT
sleep 3
FIELD="$(centre class android.widget.EditText)"
CLEAR="$(centre text 'CLEAR')"
[ -n "$CLEAR" ] && { tap $CLEAR; sleep 1; }
tap $FIELD
sleep 1
adb shell "input text 'Inception'" >/dev/null 2>&1
sleep 2
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 2
GO="$(centre text 'SEARCH')"
[ -z "$GO" ] && { fail "no Search button"; exit 1; }
tap $GO
sleep 8
PICK="$(centre text 'Inception')"
[ -z "$PICK" ] && { fail "no Inception in the results"; exit 1; }
tap $PICK
sleep 5
shot card2-first
if seen 'Inception (2010)'; then
  pass "the first film is described on the card"
else
  fail "the card never showed the first film" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
  exit 1
fi

echo "--- and now a different film, without closing the player ---"
adb shell "am start -a android.intent.action.VIEW -d $URI2 -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
sleep 8
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 8
shot card2-second
if seen 'Inception (2010)'; then
  fail "the card is still describing the film before it"
else
  pass "the card let go of the previous film"
fi
if seen 'text="jpp-second"'; then
  pass "the title is the new film"
else
  fail "the title is not the new film" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi

adb shell "rm -f $SECOND" >/dev/null 2>&1
adb shell "content delete --uri $URI2" >/dev/null 2>&1
