set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
controls_up() { show_controls; local n; for n in 1 2 3 4; do onscreen content-desc Settings && return 0; key KEYCODE_DPAD_UP; sleep 2; done; onscreen content-desc Settings; }
open_picker() {
  if dump | grep -q 'text="Subtitles"'; then key KEYCODE_BACK; sleep 2; fi
  controls_up || return 1
  local at; at="$(find_control "Disable subtitles" "Enable subtitles")"; [ -z "$at" ] && return 1
  tap $at; sleep 3
}
prepare
adb push "$(hostpath "$WORK/first.srt")"  /sdcard/Movies/first.srt  >/dev/null 2>&1
adb push "$(hostpath "$WORK/second.srt")" /sdcard/Movies/second.srt >/dev/null 2>&1
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --esa subs file:///sdcard/Movies/first.srt,file:///sdcard/Movies/second.srt --esa subs.name FirstOne,SecondOne" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
sleep 6
echo "== before: which row is current?"
open_picker && dump | grep -oE 'text="[^"]+"' | tr '\n' ' ' ; echo
echo
echo "== selecting FirstOne"
at="$(centre text FirstOne)"; [ -n "$at" ] && { tap $at; sleep 4; }
echo "== after: which row is current?"
open_picker && dump | grep -oE 'text="[^"]+"' | tr '\n' ' ' ; echo
echo
echo "== the subtitle button now reads:"
key KEYCODE_BACK; sleep 2; controls_up >/dev/null 2>&1
dump | grep -oE 'content-desc="(Enable|Disable) subtitles"' | head -1
echo "== app log about text tracks:"
adb logcat -d 2>/dev/null | grep -iE "subtitle|texttrack|cue" | tail -8
adb shell "rm -f /sdcard/Movies/first.srt /sdcard/Movies/second.srt" >/dev/null 2>&1
