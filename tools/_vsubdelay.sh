#!/bin/bash
# Moving the subtitle delay must not reopen the file.
#
# It used to: a hundred milliseconds of delay called setMediaItem and prepare,
# which on a stream is a re-buffer -- a spinner and a stall for a number that
# the renderer reads on every frame anyway. This watches a stream across a
# delay change and asks three things: that nothing was restarted, that the
# position never stopped moving, and that the delay arrived all the same.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-subdelay.txt"
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
position() { adb shell "dumpsys media_session | grep -m1 -oE 'position=[0-9]+'" 2>/dev/null | tr -d '\r' | head -1 | cut -d= -f2; }
state()    { adb shell "dumpsys media_session | grep -m1 -oE 'state=[A-Za-z]+\([0-9]\)'" 2>/dev/null | tr -d '\r' | head -1; }
delay_now() { snap; grep -A4 '"Delay"' "$SNAP" | grep -oE 'text="[-+0-9. ]+s"' | head -1 | sed 's/text=//;s/"//g'; }
bounds_of_text() {
  grep -F "text=\"$1\"" "$SNAP" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
focus_bounds() {
  grep 'focused="true"' "$SNAP" | tail -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/'
}
focus_delay_row() {
  local n ry
  for n in $(seq 1 12); do
    snap
    if grep -q '"Delay"' "$SNAP"; then
      set -- $(bounds_of_text "Delay"); ry=$(( (${2:-0} + ${4:-0}) / 2 ))
      set -- $(focus_bounds)
      if [ $# -eq 4 ] && [ "$ry" -ge "$2" ] && [ "$ry" -le "$4" ] && [ $(( $4 - $2 )) -lt 300 ]; then
        return 0
      fi
    fi
    key KEYCODE_DPAD_DOWN
    sleep 1
  done
  return 1
}

prepare

echo "=== on a stream, where a reopen is a re-buffer ==="
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8' -t 'application/x-mpegURL' -n $ACT" >/dev/null 2>&1
for n in $(seq 1 25); do
  [ -n "$(playing)" ] && break
  sleep 2
done
sleep 6
BEFORE="$(position)"
echo "  playing at ${BEFORE}ms"

echo "--- open the subtitle settings and move the delay ---"
show_controls >/dev/null
AT="$(find_control 'Enable subtitles' 'Disable subtitles' Subtitles Subtitle)"
[ -z "$AT" ] && { fail "no subtitle button"; exit 1; }
set -- $AT
adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1
sleep 3
snap
if ! grep -q '"Delay"' "$SNAP"; then
  fail "the subtitle settings panel did not open"
  exit 1
fi
focus_delay_row || { fail "could not reach the delay row"; exit 1; }
WAS="$(delay_now)"
key KEYCODE_DPAD_RIGHT
sleep 1
NOW="$(delay_now)"
echo "  delay: $WAS -> $NOW"
if [ "$NOW" != "$WAS" ]; then
  pass "the delay moved"
else
  fail "the delay did not move" "$WAS -> $NOW"
fi

key KEYCODE_BACK
sleep 2
key KEYCODE_MEDIA_PLAY
sleep 6
AFTER="$(position)"
echo "  position after: ${AFTER}ms   state: $(state)"
shot subdelay-after

# The player logs a line whenever it reopens the file. Read from this app's own
# tag, never the whole buffer.
RESTARTS="$(adb logcat -d -s JustPlayer 2>/dev/null | grep -c 'Restarting playback')"
echo "  reopens logged: $RESTARTS"
if [ "$RESTARTS" = "0" ]; then
  pass "the file was not reopened for a subtitle delay"
else
  fail "the file was reopened $RESTARTS time(s) for a subtitle delay"
fi

if [ -n "$AFTER" ] && [ -n "$BEFORE" ] && [ "$AFTER" -gt "$BEFORE" ]; then
  pass "playback carried on across the change (${BEFORE} -> ${AFTER})"
else
  fail "playback did not carry on" "${BEFORE} -> ${AFTER}"
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the player crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi

echo
echo "--- put the delay back ---"
show_controls >/dev/null
AT="$(find_control 'Enable subtitles' 'Disable subtitles' Subtitles Subtitle)"
if [ -n "$AT" ]; then
  set -- $AT
  adb shell "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1
  sleep 3
  focus_delay_row >/dev/null && { key KEYCODE_DPAD_LEFT; sleep 1; echo "  left at: $(delay_now)"; }
  key KEYCODE_BACK
fi
