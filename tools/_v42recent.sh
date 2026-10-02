#!/bin/bash
# Recent on the home screen: the file's own name, kept for good, lined up with
# the folders, and a poster only where asked for and confirmed.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42recent.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap 'server "reset=1"; setpref boolean recentPosters "${POSTERS_BEFORE:-false}"; cleanup' EXIT

ENGINE="${ENGINE:-mpv}"
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE "[0-9]+x[0-9]+" | head -1 | cut -dx -f2 | tr -d "")"
setpref string playbackEngine "$ENGINE"
# the switch is put back as it was when the script ends
POSTERS_BEFORE="$(prefs_read | grep -oE 'name="recentPosters" value="[a-z]+"' | grep -oE '(true|false)')"
setpref boolean recentPosters false
setpref boolean overlayOnPause true

FILE_A="Batman.2005.1080p.BluRay.x264.mkv"
FILE_D="Sintel.2010.1080p.BluRay.x264.mkv"

open_home() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$HOME_ACT"
  adb shell "am start -n $HOME_ACT" >/dev/null 2>&1
  sleep 5
  decline_resume >/dev/null 2>&1
  snap_home="$(dump)"
}
first_of() {   # first_of <resource id> <attribute>
  echo "$snap_home" | tr '<' '\n' | grep -F "resource-id=\"$PKG:id/$1\"" | head -1 \
    | grep -oE "$2=\"[^\"]*\"" | head -1 | sed -E "s/$2=\"(.*)\"/\1/"
}
left_of() { first_of "$1" bounds | sed -E 's/\[([0-9]+),.*/\1/'; }
size_of() { first_of "$1" bounds | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' \
  | awk '{print ($3-$1) " " ($4-$2)}'; }

echo "== $ENGINE"
server "reset=1"
curl -s -G "$SERVER/ctl" --data-urlencode "dispositionName=$FILE_A" >/dev/null

echo "-- A: a link of numbers, a launcher's title, the server's file name"
ID_A="8787$RANDOM"
launch_url "$SERVER/named/$ID_A" "--es title Batman" || { fail "opened"; exit 1; }
sleep 12
open_home
FIRST="$(first_of video_name text)"
echo "  first Recent row: $FIRST"
[ "$FIRST" = "$FILE_A" ] && pass "A: Recent names the file, not the title or the numbers" \
  || fail "A: Recent names the file" "shows '$FIRST'"

echo "-- C: lined up with the folders"
# show both kinds of row: in landscape five Recent rows fill the screen
refresh_screen
for n in 1 2 3 4; do
  [ -n "$(first_of video_icon bounds)" ] && [ -n "$(first_of folder_icon bounds)" ] && break
  swipe $((SCREEN_W / 2)) $((SCREEN_H * 60 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 35 / 100)) 600
  sleep 1.5
  snap_home="$(dump)"
done
IL="$(left_of video_icon)"; FL="$(left_of folder_icon)"
NL="$(left_of video_name)"; FNL="$(left_of folder_name)"
echo "  icon left: Recent $IL, folder $FL; name left: Recent $NL, folder $FNL"
if [ -n "$IL" ] && [ -n "$FL" ] && [ $((IL - FL)) -le 3 ] && [ $((FL - IL)) -le 3 ] \
   && [ -n "$NL" ] && [ -n "$FNL" ] && [ $((NL - FNL)) -le 3 ] && [ $((FNL - NL)) -le 3 ]; then
  pass "C: Recent's icon and name start where a folder's do"
else
  fail "C: Recent's icon and name start where a folder's do" "icon $IL vs $FL, name $NL vs $FNL"
fi

echo "-- B: the link has expired, and is opened again from Recent"
server "expireNamed=1"
# from the top again: the list was scrolled, so the first row is not the newest
open_home
B="$(first_of video_name bounds | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')"
CURRENT_SCREEN="$ACT"
adb shell "input tap $B" >/dev/null 2>&1
sleep 10
open_home
FIRST="$(first_of video_name text)"
echo "  first Recent row: $FIRST"
[ "$FIRST" = "$FILE_A" ] && pass "B: after an expired link, the row keeps the file name" \
  || fail "B: after an expired link, the row keeps the file name" "shows '$FIRST'"
server "expireNamed=0"

echo "-- D: watched with the info card, the film's poster"
curl -s -G "$SERVER/ctl" --data-urlencode "dispositionName=$FILE_D" >/dev/null
ID_D="5151$RANDOM"
launch_url "$SERVER/named/$ID_D" || { fail "opened"; exit 1; }
sleep 10
# pause so the card comes up, then play on
adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
sleep 8
CARD="$(dump | grep -oE 'text="Sintel[^"]*"' | head -1)"
echo "  card: ${CARD:-none}"
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1
sleep 50
POSTER="$(prefs_read | grep -F 'name="urlHistory"' | sed 's/&quot;/"/g' \
  | grep -oE "\"uri\":\"[^\"]*named\\\\/$ID_D\"[^}]*" | grep -oE '"poster":"[^"]*"')"
echo "  stored: ${POSTER:-no poster}"
[ -n "$POSTER" ] && pass "D: watched with the card, the poster was kept" \
  || fail "D: watched with the card, the poster was kept" "card ${CARD:-none}"

open_home
read -r W H <<<"$(size_of video_icon)"
echo "  switch off: icon ${W}x${H}"
[ -n "$W" ] && [ "$W" = "$H" ] && pass "D: with the switch off, no poster (the folder-sized icon)" \
  || fail "D: with the switch off, no poster" "icon ${W}x${H}"
setpref boolean recentPosters true
open_home
adb exec-out screencap -p > "$WORK/shots/recent-posters-$ENGINE.png" 2>/dev/null
read -r W H <<<"$(size_of video_icon)"
echo "  switch on: icon ${W}x${H}"
if [ -n "$W" ] && [ "$W" -gt 0 ] && [ $((H * 2)) -ge $((W * 3 - 6)) ] && [ $((H * 2)) -le $((W * 3 + 6)) ]; then
  pass "D: with the switch on, the row has a 2:3 poster"
else
  fail "D: with the switch on, the row has a 2:3 poster" "icon ${W}x${H}"
fi
setpref boolean recentPosters "${POSTERS_BEFORE:-false}"
check "no crash" "$(crashed)"
echo
echo "$PASSED passed, $FAILED failed"
