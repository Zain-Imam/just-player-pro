#!/bin/bash
# A forced ratio survives the settings screen, and the resize button carries on
# from the current mode. Settings is started over the player, as the panel does.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
picture() { bounds_of resource-id "$PKG:id/exo_content_frame"; }

# Media3 only: on mpv the frame is always the whole window
if adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null     | grep -q 'name="playbackEngine">mpv<'; then
  echo "SKIP  the frame is not measurable on mpv (see _v42checks D, _v2e)"
  exit 0
fi

prepare
# start from Default: the mode is remembered per film
adb shell "am force-stop $PKG" >/dev/null 2>&1
prefs_now="$(adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null | tr -d '\r' | grep -v 'name="aspectMap"')"
[ -n "$prefs_now" ] && echo "$prefs_now" | adb shell "run-as $PKG sh -c 'cat > shared_prefs/${PKG}_preferences.xml'"
open_film

# Four presses: Crop, Stretch, 16:9, 4:3.
for n in 1 2 3 4; do tap_control Resize >/dev/null 2>&1; sleep 1; done
sleep 1
BEFORE="$(picture)"
echo "4:3 before the trip: [$BEFORE]"
shot trip-before

echo "--- into settings ---"
CURRENT_SCREEN="$SETTINGS"
adb shell "am start -n $SETTINGS" >/dev/null 2>&1
sleep 5
echo "  in: $(focused)"
CURRENT_SCREEN="$ACT"
key KEYCODE_BACK
sleep 8
echo "  back in: $(focused)"
AFTER="$(picture)"
shot trip-after
echo "after the trip:      [$AFTER]"
if [ "$BEFORE" = "$AFTER" ]; then
  pass "the shape came back exactly as it was"
else
  fail "the shape changed across the settings screen" "[$BEFORE] became [$AFTER]"
fi

tap_control Resize >/dev/null 2>&1
sleep 2
NEXT="$(picture)"
shot trip-next
echo "one press on:        [$NEXT]"
# 4:3 is followed by 16:10, which on this film is 1728 wide.
case "$NEXT" in
  "336 0 2064 1080") pass "the next press moves on from where the picture was" ;;
  *) fail "the next press went somewhere else" "[$NEXT]" ;;
esac
