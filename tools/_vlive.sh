#!/bin/bash
# Live streams and adaptive playlists (live HLS, on-demand HLS, DASH) on the
# selected engine. A live stream has no position, so it is judged on playing
# without error.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
position() { adb shell "dumpsys media_session | grep -oE 'position=[0-9]+'" 2>/dev/null \
  | tr -d '\r' | head -1 | cut -d= -f2; }

play_url() {
  local what="$1" live="$2" url="$3" type="$4"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$url' -t '$type' -n $ACT" >/dev/null 2>&1
  local n
  for n in $(seq 1 20); do
    [ -n "$(playing)" ] && break
    sleep 2
  done
  sleep 4
  shot "live-$what"
  local state pos pos2 crashes
  state="$(playing)"
  pos="$(position)"
  sleep 6
  pos2="$(position)"
  crashes="$(crashed)"
  echo "    $what: state=[$state] ${pos:--} -> ${pos2:--}  crashes=$crashes"
  if [ "$crashes" != "0" ]; then
    fail "$what crashed the player"
    return
  fi
  if [ -z "$state" ]; then
    fail "$what never started playing"
    return
  fi
  if [ "$live" = live ]; then
    pass "$what plays"
    return
  fi
  if [ -n "$pos" ] && [ -n "$pos2" ] && [ "$pos2" -gt "$pos" ]; then
    pass "$what plays and keeps moving"
  else
    fail "$what started but the position did not move" "${pos:--} -> ${pos2:--}"
  fi
}

prepare
echo "=== on the engine as it is set now ==="
play_url "hls-live" live    "https://demo.unified-streaming.com/k8s/live/stable/scte35.isml/.m3u8" "application/x-mpegURL"
play_url "hls-vod"  ondemand "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"                    "application/x-mpegURL"
play_url "dash"     ondemand "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"            "application/dash+xml"
