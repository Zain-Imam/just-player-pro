#!/bin/bash
# The subtitle you load is the one you get, where you were.
# Part A downloads from Wyzie, so it needs the keys in .env and the network.
# Usage: PKG=<debug package> ENGINE=<media3|mpv> tools/_v42subfix.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap 'adb shell "rm -f /sdcard/Download/jpp-picked.srt" >/dev/null 2>&1; cleanup' EXIT
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
ENGINE="${ENGINE:-media3}"
setpref string playbackEngine "$ENGINE"
setpref boolean overlayOnPause false

SNAP="$WORK/snap-subfix.txt"
snap() { local n; for n in 1 2 3; do dump > "$SNAP"; [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0; sleep 2; done; }
texts() { snap; grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g'; }
# A dump can fail while video plays; ask twice.
at() { local n c; for n in 1 2; do c="$(centre "$@")"; [ -n "$c" ] && { echo "$c"; return 0; }; sleep 2; done; }
said() { adb logcat -d 2>/dev/null | tr -d '\r' | grep JustPlayer | grep -cE "$1"; }

open_subtitles() {
  show_controls >/dev/null
  tap_control 'Disable subtitles' 'Enable subtitles' Subtitles Subtitle >/dev/null
  sleep 2
}
# The rows of the subtitle picker down to its actions, then closed again.
playing_row() {
  open_subtitles
  texts | awk '/^(Load a subtitle file…|Search online subtitles…)$/{exit} {print}' \
    | grep -v '^Subtitles$' > "$WORK/subfix-rows.txt"
  adb shell input keyevent KEYCODE_BACK; sleep 1
}
open_search() {
  open_subtitles
  local s i
  for i in 1 2 3 4; do
    s="$(at text 'Search online subtitles…')"; [ -n "$s" ] && break
    adb shell "input swipe $((SCREEN_W * 3 / 4)) $((SCREEN_H * 3 / 4)) $((SCREEN_W * 3 / 4)) $((SCREEN_H / 3)) 400"; sleep 2
  done
  [ -z "$s" ] && return 1
  adb shell input tap $s; sleep 4
  if texts | grep -qE '(Wyzie|OpenSubtitles|SubDL)'; then return 0; fi
  local f; f="$(at class android.widget.EditText)"; [ -z "$f" ] && return 1
  local c; c="$(at text 'CLEAR')"; [ -n "$c" ] && { adb shell input tap $c; sleep 1; }
  adb shell input tap $f; sleep 1; adb shell "input text 'Inception'"; sleep 2
  adb shell input keyevent KEYCODE_BACK; sleep 2
  local g; g="$(at text 'SEARCH')"; [ -z "$g" ] && g="$(at text 'Search')"; adb shell input tap $g; sleep 8
  local p; p="$(at text 'Inception')"; [ -n "$p" ] && { adb shell input tap $p; sleep 12; }
  texts | grep -qE '(Wyzie|OpenSubtitles|SubDL)'
}
# The first Wyzie result, scrolling the list until one shows.
download_wyzie() {
  local i name x
  for i in $(seq 1 70); do
    name="$(texts | awk '$0 ~ /·/ && $0 ~ /Wyzie/ { print previous; exit } { previous = $0 }')"
    if [ -n "$name" ]; then
      WANTED="$name"; adb shell input tap $(at text "$name"); return 0
    fi
    x="$(at class android.widget.TextView | cut -d' ' -f1)"; [ -z "$x" ] && x=$((SCREEN_W / 2))
    adb shell "input swipe $x $((SCREEN_H * 3 / 4)) $x $((SCREEN_H / 4)) 300"; sleep 1
  done
  return 1
}

prepare >/dev/null
open_film
sleep 3

echo "-- A: the same name downloaded twice, on $ENGINE"
WANTED=""
for round in 1 2; do
  if ! open_search || ! download_wyzie; then
    fail "A: reached a Wyzie result (round $round)" "$(texts | head -8 | tr '\n' '|')"
    break
  fi
  echo "  round $round: $WANTED"
  sleep 15
done
playing_row
ROWS="$(cat "$WORK/subfix-rows.txt")"
COPIES="$(grep -cxF "$WANTED" "$WORK/subfix-rows.txt")"
# The row after the playing one's title is its "Playing now" line.
LAST_PLAYING="$(awk -v w="$WANTED" '$0 == w { n++ } /^Playing now/ { if (prev == w) p = n } { prev = $0 } END { print p + 0 }' "$WORK/subfix-rows.txt")"
echo "  rows: $(echo "$ROWS" | tr '\n' '|')"
[ "$COPIES" -ge 2 ] && [ "$LAST_PLAYING" = "$COPIES" ] \
  && pass "A: of $COPIES subtitles called $WANTED, the newest is the one playing" \
  || fail "A: of $COPIES subtitles called $WANTED, the newest is the one playing" "playing copy: $LAST_PLAYING"

echo "-- B: a subtitle from the device, mid-film, on $ENGINE"
awk 'BEGIN{for(i=0;i<120;i++){s=i*2; printf "%d\n00:%02d:%02d,000 --> 00:%02d:%02d,900\nPICKED line %d\n\n", i+1, int(s/60), s%60, int((s+1)/60), (s+1)%60, i+1}}' > "$WORK/picked.srt"
adb push "$(hostpath "$WORK/picked.srt")" /sdcard/Download/jpp-picked.srt >/dev/null 2>&1
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/jpp-picked.srt" >/dev/null 2>&1
adb shell input keyevent KEYCODE_MEDIA_PLAY >/dev/null; sleep 6
open_subtitles
adb logcat -c
L="$(at text 'Load a subtitle file…')"
[ -z "$L" ] && { fail "B: the picker offers to load a file"; exit 1; }
adb shell input tap $L; sleep 5
C="$(at text 'CANCEL')"; [ -n "$C" ] && { adb shell input tap $C; sleep 4; }
S="$(at content-desc 'Search')"
[ -z "$S" ] && { fail "B: the file picker opened" "$(focused)"; exit 1; }
adb shell input tap $S; sleep 2
adb shell input text jpp-picked; sleep 1; adb shell input keyevent KEYCODE_ENTER; sleep 4
F="$(at text 'jpp-picked.srt')"
[ -z "$F" ] && { fail "B: found the file in the picker" "$(texts | head -8 | tr '\n' '|')"; exit 1; }
adb shell input tap $F; sleep 8
BUILDS="$(said 'Building the player')"
BACK_AT="$(adb logcat -d | tr -d '\r' | grep JustPlayer | grep -oE 'PARITY tracks .*position=[0-9]+' | tail -1 | grep -oE 'position=[0-9]+$' | cut -d= -f2)"
echo "  player built $BUILDS times; came back at ${BACK_AT:-?}ms"
[ "$BUILDS" = 0 ] && pass "B: the film was not built again" \
  || fail "B: the film was not built again" "built $BUILDS times"
[ -n "$BACK_AT" ] && [ "$BACK_AT" -gt 5000 ] && pass "B: it carried on from where it was, not from the start (${BACK_AT}ms)" \
  || fail "B: it carried on from where it was, not from the start" "came back at ${BACK_AT:-nothing}"
playing_row
awk '/^jpp-picked$/ { getline; if ($0 ~ /^Playing now/) found = 1 } END { exit !found }' "$WORK/subfix-rows.txt" \
  && pass "B: the file picked is the one playing" \
  || fail "B: the file picked is the one playing" "$(tr '\n' '|' < "$WORK/subfix-rows.txt")"

if [ "$ENGINE" = "mpv" ]; then
  echo "-- C: mpv's lowest position"
  setpref int subtitleVerticalPosition_mpv -40
  adb logcat -c
  open_film
  sleep 3
  POS="$(adb logcat -d | tr -d '\r' | grep JustPlayer | grep -oE 'mpv sub-pos = [0-9.]+' | tail -1)"
  echo "  $POS"
  echo "$POS" | grep -q "= 105.0" && pass "C: -40 is held at -13, the bottom edge (sub-pos 105)" \
    || fail "C: -40 is held at -13, the bottom edge (sub-pos 105)" "${POS:-no sub-pos}"
  setpref int subtitleVerticalPosition_mpv 0
fi

check "no crash" "$(crashed)"
