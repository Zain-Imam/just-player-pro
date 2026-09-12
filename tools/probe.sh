#!/bin/bash
#
# A scratch pad for one question at a time, on the same interlock as the rest.
# Not part of the suite; kept so the next investigation does not start with
# somebody typing an unguarded tap into a terminal.

set -u
HERE_SCRIPT="$(cd "$(dirname "$0")" && pwd)"
. "$HERE_SCRIPT/lib.sh"

SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"

case "${2:-}" in
  hls)
    echo "== launching an HLS link with its own mime type"
    adb shell "am force-stop $PKG" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    CURRENT_SCREEN=""
    adb shell "am start -a android.intent.action.VIEW -n $ACT -t application/x-mpegurl -d 'https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8'" 2>&1 | tail -2
    for i in $(seq 1 25); do
      sleep 2
      case "$(focused)" in *"$PKG"*) break ;; esac
    done
    echo "focus:   $(focused)"
    echo "playing: [$(playing)]"
    echo "screen:  $(dump | grep -oE 'text="[^"]+"' | head -5 | tr '\n' ' ')"
    echo "crash:   $(crashed)"
    ;;

  mpvsubs)
    echo "== can the app read a bare .srt off /sdcard at all?"
    adb shell "run-as $PKG ls /sdcard/Movies/" >/dev/null 2>&1 \
      && echo "(debuggable build: could check directly)" \
      || echo "(release build: cannot look from here, inferring from behaviour)"
    echo
    echo "== mpv, with the subtitle in the app's own external folder instead"
    local_dir="/sdcard/Android/data/$PKG/files"
    adb shell "mkdir -p '$local_dir'" >/dev/null 2>&1
    if adb shell "cp '$SUBS' '$local_dir/probe.srt' && ls '$local_dir/probe.srt'" 2>&1 | grep -q probe.srt; then
      echo "staged at $local_dir/probe.srt"
      adb shell "am force-stop $PKG" >/dev/null 2>&1
      CURRENT_SCREEN=""
      adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --esa subs file://$local_dir/probe.srt --esa subs.name Probe" >/dev/null 2>&1
      for i in $(seq 1 20); do sleep 2; case "$(focused)" in *"$PKG"*) break ;; esac; done
      sleep 6
      tap_control "Disable subtitles" "Enable subtitles" || echo "could not open the picker"
      echo "picker: $(dump | grep -oE 'text="[^"]+"' | head -8 | tr '\n' ' ')"
    else
      echo "could not stage a file in the app's own folder"
    fi
    ;;

  panel)
    echo "== the quick panel under a D-pad"
    open_film
    key KEYCODE_DPAD_CENTER; sleep 2
    tap_control Settings || echo "could not open the panel"
    sleep 2
    echo "focused now: $(dump | grep -F 'focused="true"' | grep -oE '(text|content-desc)="[^"]+"' | head -2 | tr '\n' ' ')"
    for n in 1 2 3; do
      key KEYCODE_DPAD_DOWN; sleep 1
      echo "  after down $n: $(dump | grep -F 'focused="true"' | grep -oE '(text|content-desc)="[^"]+"' | head -2 | tr '\n' ' ')"
    done
    ;;

  *)
    echo "usage: tools/probe.sh <package> hls|mpvsubs|panel"
    ;;
esac
