#!/bin/bash
#
# The back arrow in the player's title bar, and where leaving actually goes.
#
# Two things are being proved, and only the second is interesting: that the
# arrow is there and can be pressed with a finger and with a remote, and that
# pressing it leaves for wherever the film came from rather than for the
# launcher. The second is the whole point of it existing.
#
# One engine per run, the way every other script in here works: the engine is
# whatever the settings say when it starts, and _setengine.sh decides that
# beforehand. Switching it from inside would mean running that script as a
# child, and its cleanup deletes the test media the parent is still using.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT

SNAP="$WORK/snap-back.txt"
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

arrow_at() {
  snap
  grep -F 'content-desc="Back"' "$SNAP" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}

ENGINE="$(adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null \
          | grep -oE 'name="playbackEngine">[a-z0-9]+' | cut -d'>' -f2 | tr -d '\r')"
[ -z "$ENGINE" ] && ENGINE="auto (unset)"

prepare
mkdir -p "$WORK/shots"

echo
echo "======== engine: $ENGINE ========"

open_film
show_controls >/dev/null
sleep 2

echo "--- the arrow is in the title bar ---"
AT="$(arrow_at)"
shot "back-$ENGINE"
if [ -n "$AT" ]; then
  pass "the back arrow is on screen"
  set -- $AT
  echo "  arrow centre: $1,$2"
else
  fail "no back arrow in the title bar"
  exit 1
fi

echo "--- a finger on it leaves the player ---"
tap $1 $2
sleep 4
WHERE="$(current_activity)"
echo "  in front: ${WHERE:-nothing of ours}"
if [ "$WHERE" != "PlayerActivity" ]; then
  pass "tapping the arrow leaves the player"
else
  fail "tapping the arrow did nothing"
fi

echo "--- and a remote can reach it ---"
open_film
show_controls >/dev/null
sleep 2
REACHED=0
for n in $(seq 1 8); do
  snap
  focused_desc_is "$SNAP" Back && { REACHED=1; break; }
  key KEYCODE_DPAD_UP
  sleep 1
done
if [ "$REACHED" = 1 ]; then
  pass "the remote reaches the back arrow"
  key KEYCODE_DPAD_CENTER
  sleep 4
  WHERE="$(current_activity)"
  if [ "$WHERE" != "PlayerActivity" ]; then
    pass "the remote's centre key on the arrow leaves"
  else
    fail "the arrow did nothing from the remote"
  fi
else
  fail "the remote never focused the back arrow" \
       "focus ended on: $(grep 'focused="true"' "$SNAP" | tail -1 | grep -oE 'content-desc="[^"]*"')"
fi

echo
echo "--- from another application, leaving goes back to that application ---"
#
# The device's own settings stand in for Stremio: already installed, not one of
# the user's own applications, and nothing is pressed in it. All that matters is
# that the player was started by something sitting in another task.
adb shell "am force-stop $PKG" >/dev/null 2>&1
sleep 1
adb shell "am start -a android.settings.SETTINGS" >/dev/null 2>&1
sleep 4
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
sleep 8
if [ "$(current_activity)" = "PlayerActivity" ]; then
  pass "an external launch opens the player"
  key KEYCODE_BACK
  sleep 2
  case "$(current_activity)" in
    PlayerActivity) key KEYCODE_BACK; sleep 4 ;;
  esac
  FOCUS="$(focused)"
  echo "  after leaving: $FOCUS"
  case "$FOCUS" in
    *"$PKG"*)  fail "leaving went back into this app rather than to the sender" "$FOCUS" ;;
    *settings*) pass "leaving returns to whatever sent the film" ;;
    *)         fail "leaving went somewhere unexpected" "$FOCUS" ;;
  esac
else
  fail "the external launch did not open the player" "$(current_activity)"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the app crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
