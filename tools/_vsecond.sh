#!/bin/bash
# 9: a second film handed over while the player is still in memory must be the
# film that is described, not the one before it.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
seen() { dump | grep -q "$1"; }

prepare

# A second file, so the two have different addresses and different names.
SECOND="/sdcard/Movies/jpp-second.ts"
adb shell "cp $MEDIA $SECOND" >/dev/null 2>&1
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$SECOND" >/dev/null 2>&1
sleep 3
ID2=$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-second.ts'\"" 2>/dev/null \
     | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')
[ -z "$ID2" ] && { fail "could not index the second file"; exit 2; }
URI2="content://media/external/video/media/$ID2"
echo "second film at $URI2"

echo "--- the first film, with a title from its launcher ---"
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --es title 'Film One'" >/dev/null 2>&1
sleep 8
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 2
shot second-first
if seen 'text="Film One"'; then
  pass "the first film shows the title its launcher gave"
else
  fail "the first title never appeared" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi

echo "--- and now a second one, without closing the player ---"
adb shell "am start -a android.intent.action.VIEW -d $URI2 -t video/mp2t -n $ACT --grant-read-uri-permission --es title 'Film Two'" >/dev/null 2>&1
sleep 8
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 3
shot second-second
if seen 'text="Film Two"'; then
  pass "the second film shows its own title"
else
  fail "the title did not follow the second film" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi
if seen 'text="Film One"'; then
  fail "the first title is still on screen"
else
  pass "nothing of the first film is left on the title"
fi

echo "--- and a third, with no title at all, so the file name must show ---"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
sleep 8
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 3
shot second-third
if seen 'text="jpp-smoke"'; then
  pass "with no title given, the file name shows"
else
  fail "the file name did not appear" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
fi
if seen 'text="Film Two"'; then
  fail "the previous title is still stuck on"
else
  pass "the previous title is gone"
fi

adb shell "rm -f $SECOND" >/dev/null 2>&1
[ -n "$ID2" ] && adb shell "content delete --uri $URI2" >/dev/null 2>&1
