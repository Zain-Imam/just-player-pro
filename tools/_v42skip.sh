#!/bin/bash
# Skipping the intro works, and keeps working after the player is rebuilt.
# Markers come from a Sintel copy with chapters renamed Intro and Credits.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42skip.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
SKIPFILE="/sdcard/Movies/jpp-skip.mkv"
trap 'adb shell "rm -f $SKIPFILE" >/dev/null 2>&1; [ -n "$SKIP_ID" ] && adb shell "content delete --uri content://media/external/video/media/$SKIP_ID" >/dev/null 2>&1; cleanup' EXIT

ENGINE="${ENGINE:-mpv}"
setpref string playbackEngine "$ENGINE"
setpref boolean overlayOnPause false
setpref boolean skipSegments true
setpref boolean backgroundAudio false

# The renamed copy, made once.
if [ ! -s "$WORK/skip.mkv" ]; then
  echo "making the chaptered copy"
  node -e '
    const fs = require("fs");
    const [src, dst] = process.argv.slice(1);
    fs.copyFileSync(src, dst);
    const fd = fs.openSync(dst, "r+");
    const head = Buffer.alloc(8192);
    fs.readSync(fd, head, 0, head.length, 0);
    for (const [from, to] of [["Chapter 01", "Intro     "], ["Chapter 08", "Credits   "]]) {
      const at = head.indexOf(Buffer.from(from, "latin1"));
      if (at < 0) { console.error("no " + from); process.exit(1); }
      fs.writeSync(fd, Buffer.from(to, "latin1"), 0, to.length, at);
    }
    fs.closeSync(fd);' "$(hostpath "$WORK/sintel.mkv")" "$(hostpath "$WORK/skip.mkv")" || { fail "made the chaptered copy"; exit 1; }
fi
echo "pushing it ($(du -h "$WORK/skip.mkv" | cut -f1))"
adb push "$(hostpath "$WORK/skip.mkv")" "$SKIPFILE" >/dev/null 2>&1 || { fail "pushed the test film"; exit 1; }
adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$SKIPFILE" >/dev/null 2>&1
SKIP_ID=""
for n in $(seq 1 15); do
  SKIP_ID="$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='jpp-skip.mkv'\"" 2>/dev/null \
      | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2)"
  [ -n "$SKIP_ID" ] && break
  sleep 1
done
[ -z "$SKIP_ID" ] && { fail "the media store took the test film"; exit 1; }
SKIP_URI="content://media/external/video/media/$SKIP_ID"

open_at() {   # open_at <ms> [remote]
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d $SKIP_URI -t video/x-matroska -n $ACT --grant-read-uri-permission --ei position $1" >/dev/null 2>&1
  if [ "${2:-}" = "remote" ]; then
    # a new window starts in touch mode; a key at once, before the offer, leaves it
    sleep 0.8
    adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  fi
  sleep 9
}

remote_mode() {
  # Any key leaves touch mode; on the home screen it moves nothing that matters.
  adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  sleep 1
}

press_skip() {
  adb shell "input keyevent KEYCODE_DPAD_CENTER" >/dev/null 2>&1
  sleep 4
}

# Where the button sits on this phone: bottom right, above the controls.
tap_skip() {
  local w h
  w="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '')"
  h="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1 | tr -d '')"
  adb shell "input tap $((w - 211)) $((h * 722 / 1000))" >/dev/null 2>&1
  sleep 4
}

rebuilt_in_intro() {
  open_at 2000
  adb shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
  sleep 4
  CURRENT_SCREEN="$ACT"
  adb shell "am start -n $ACT" >/dev/null 2>&1
  sleep 9
}

check_skipped() {   # check_skipped <label> <before> <after>
  if [ -n "$2" ] && [ "$2" -lt 100000 ] && [ -n "$3" ] && [ "$3" -ge 100000 ] && [ "$3" -le 115000 ]; then
    pass "$1"
  else
    fail "$1" "$2ms -> $3ms"
  fi
}

echo "== $ENGINE"
echo "-- A: remote, a fresh film"
open_at 2000 remote
P0="$(position_ms)"; press_skip; P1="$(position_ms)"
echo "  ${P0}ms -> ${P1}ms"
check_skipped "A: remote, OK inside the intro skipped to its end" "$P0" "$P1"

echo "-- B: remote, the same film after the player has been rebuilt"
remote_mode
rebuilt_in_intro
P0="$(position_ms)"; press_skip; P1="$(position_ms)"
echo "  ${P0}ms -> ${P1}ms"
check_skipped "B: remote, after a rebuild OK inside the intro still skipped" "$P0" "$P1"

echo "-- C: touch, the same film after the player has been rebuilt"
rebuilt_in_intro
P0="$(position_ms)"; tap_skip; P1="$(position_ms)"
echo "  ${P0}ms -> ${P1}ms"
check_skipped "C: touch, after a rebuild tapping the button skipped" "$P0" "$P1"
check "no crash" "$(crashed)"
echo
echo "$PASSED passed, $FAILED failed"
