#!/bin/bash
# A subtitle sitting beside a stream is found, downloaded once, and attached.
# The player tries film.srt, film.en.srt... beside the film's address.
# Usage: ENGINE=<mpv|media3> PKG=<debug package> tools/_v42sidecar.sh

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap 'server "reset=1"; cleanup' EXIT

ENGINE="${ENGINE:-mpv}"
setpref string playbackEngine "$ENGINE"
setpref boolean overlayOnPause false

mkdir -p "$WORK/side"
# a fresh name each run, so earlier server log lines do not count
NAME="film$RANDOM"
rm -f "$WORK"/side/film*.srt
awk 'BEGIN{for(i=0;i<60;i++){s=i*2; printf "%d\n", i+1;
  printf "00:%02d:%02d,000 --> 00:%02d:%02d,900\n", int(s/60), s%60, int((s+1)/60), (s+1)%60;
  printf "Beside line %d — café ♪\n\n", i+1}}' > "$WORK/side/$NAME.srt"
# the cues above hold real UTF-8: plain ASCII is not detected as UTF-8

echo "== $ENGINE"
server "reset=1"
launch_url "$SERVER/side/$NAME.ts" "--es title Beside" || { fail "opened"; exit 1; }
sleep 25

GETS="$(curl -s "$SERVER/log" | grep -c "200 /side/$NAME.srt")"
echo "  downloads of $NAME.srt: $GETS"
curl -s "$SERVER/log" | grep "/side/$NAME.srt" | tail -4 | sed 's/^/    /'
# One to find it among the guesses, one to fetch it.
if [ "$GETS" -ge 1 ] && [ "$GETS" -le 2 ]; then
  pass "the subtitle beside the stream was downloaded once, not over and over"
else
  fail "the subtitle beside the stream was downloaded once" "$GETS downloads in 25 seconds"
fi

MEMORY="$(getpref launchMemory)"
if echo "$MEMORY" | grep -q "side.*$NAME.ts" && echo "$MEMORY" | grep -q "$NAME.srt"; then
  pass "it was attached to the film (and remembered with it)"
else
  fail "it was attached to the film" "$(echo "$MEMORY" | cut -c1-300)"
fi
if echo "$MEMORY" | grep -q "file:[^&]*$NAME.srt"; then
  pass "what was attached is the downloaded file, not the web address"
else
  fail "what was attached is the downloaded file" "$(echo "$MEMORY" | cut -c1-300)"
fi
check "no crash" "$(crashed)"
echo
echo "$PASSED passed, $FAILED failed"
