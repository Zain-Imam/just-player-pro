#!/bin/bash
. "$(dirname "$0")/lib.sh" app.justplayerpro.android.debug
. "$(dirname "$0")/_v42lib.sh"
setpref string playbackEngine mpv
getpref playbackEngine
server "reset=1"
launch_url "$SERVER/sintel.mkv" "--es title Sintel" || echo "did not open"
sleep 8
echo "focus: $(focused)"
echo "pos1: $(position_ms)"
sleep 5
echo "pos2: $(position_ms)"
player_log 15
adb shell dumpsys media_session | tr -d '\r' | grep -m2 "state=PlaybackState"
