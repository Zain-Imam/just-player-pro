#!/bin/bash
# The subtitle a launcher hands over is listed, under the name it was given --
# and when it cannot be read, the viewer is told. On either engine.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }

prepare
open_film
show_controls >/dev/null
tap_control "Disable subtitles" "Enable subtitles" Subtitles Subtitle
sleep 3
shot sidecar-picker
LIST="$(dump | grep -oE 'text="[^"]+"' | sed 's/text=//;s/"//g' | tr '\n' '|')"
echo "  the picker shows: $LIST"
if echo "$LIST" | grep -q 'Smoke'; then
  pass "listed under the name the launcher gave it"
else
  fail "the handed-over subtitle is not in the picker" "$LIST"
fi

echo "--- and a subtitle that cannot be read is reported ---"
key KEYCODE_BACK
sleep 2
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
# A path that is not there at all: the same thing a dead hand-off looks like.
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --esa subs file:///sdcard/Movies/jpp-nothing-here.srt --esa subs.name Missing" >/dev/null 2>&1
for n in $(seq 1 15); do
  case "$(focused)" in *"$PKG"*) break ;; esac
  sleep 1
done
# Paused first, because uiautomator will not describe a screen that never stops
# moving and comes back with nothing at all. The message is posted when the file
# opens, which is after this, and it stays up whether or not the film is
# running.
sleep 1
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 2
shot sidecar-missing
# Read more than once: on this engine a moving picture often comes back as an
# empty dump, and the message is only up for a few seconds.
SAID=""
for n in 1 2 3; do
  SAID="$SAID|$(dump | grep -oE 'text="[^"]+"' | sed 's/text=//;s/"//g' | tr '\n' '|')"
done
echo "  on screen: $SAID"
# The message lives for three and a half seconds and a screen dump takes two of
# them, so catching it on screen is a race the harness loses as often as it
# wins. The player's own log line is the same event without the stopwatch --
# read from this app's tag alone, never from the whole buffer, which carries
# whatever else the phone happens to be doing.
LOGGED="$(adb logcat -d -s JustPlayer 2>/dev/null | grep -c 'Subtitle would not load')"
echo "  the player logged it: $LOGGED time(s)"
if echo "$SAID" | grep -q 'would not load' || [ "${LOGGED:-0}" -gt 0 ]; then
  pass "it says the subtitle would not load"
else
  fail "nothing was said about a subtitle that could not load" "$SAID"
fi
if [ -n "$(playing)" ] || [ -n "$(alive)" ]; then
  pass "the film carries on regardless"
else
  fail "the player did not survive an unreadable subtitle"
fi
