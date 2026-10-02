#!/bin/bash
# What came with one film (title, headers, subtitles) does not go to the next.
# Checked through a shared link, the route adb can drive.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42next.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap 'server "reset=1"; cleanup' EXIT

ENGINE="${ENGINE:-mpv}"
setpref string playbackEngine "$ENGINE"
setpref boolean overlayOnPause false

session_title() {
  adb shell dumpsys media_session 2>/dev/null | tr -d '\r' | grep -A25 "package=$PKG" \
    | grep -m1 -oE "description=[^,]*" | sed 's/description=//'
}

echo "== $ENGINE"
server "reset=1"
launch_url "$SERVER/clip.ts" "--es title FirstFilm --esa headers 'Referer: http://first.example/'" \
  || { fail "opened"; exit 1; }
sleep 8
T1="$(session_title)"
echo "  first film: $T1"
[ "$T1" = "FirstFilm" ] && pass "the first film has the title it was given" \
  || fail "the first film has the title it was given" "$T1"

# The same player, handed a different film as a shared link.
NEXT="$SERVER/side/second$RANDOM.ts"
adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT '$NEXT' -n $ACT" >/dev/null 2>&1
sleep 10
T2="$(session_title)"
echo "  second film: $T2"
if [ -n "$T2" ] && [ "$T2" != "FirstFilm" ]; then
  pass "the second film is not shown under the first one's title"
else
  fail "the second film is not shown under the first one's title" "title: $T2"
fi
REQ="$(curl -s "$SERVER/log" | grep "/side/second" | tail -1)"
echo "  $REQ"
if [ -n "$REQ" ] && ! echo "$REQ" | grep -q "first.example"; then
  pass "the second film's requests do not carry the first one's headers"
else
  fail "the second film's requests do not carry the first one's headers" "$REQ"
fi
check "no crash" "$(crashed)"
echo
echo "$PASSED passed, $FAILED failed"
