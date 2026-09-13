#!/bin/bash
# 8: live streams and adaptive playlists, on whichever engine is selected.
#
# The crash reported against v1 was every HLS stream dying on
# NoSuchMethodError: getBandwidthMeter() -- the Media3 modules had been raised
# past the patched ExoPlayer aars, and HlsMediaSource called into a method the
# aar had not got. This proves the door is open: live HLS, on-demand HLS, DASH.
#
# A live stream reports no position through the media session -- there is no
# duration to be at a position within -- so it is judged on playing without
# error instead, which is all there is to judge.
#
# One stream was dropped from this list: Akamai's cph-p2p-msl test channel,
# whose master playlist points at a variant that answers 404. Both engines
# refuse it and both are right to; it is broken where it is served.
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
