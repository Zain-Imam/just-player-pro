set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
prepare
adb push "$(hostpath "$WORK/first.srt")"  /sdcard/Movies/first.srt  >/dev/null 2>&1
adb push "$(hostpath "$WORK/second.srt")" /sdcard/Movies/second.srt >/dev/null 2>&1
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission --esa subs file:///sdcard/Movies/first.srt,file:///sdcard/Movies/second.srt --esa subs.name FirstOne,SecondOne" >/dev/null 2>&1
for i in $(seq 1 25); do sleep 1; case "$(focused)" in *"$PKG"*) break ;; esac; done
sleep 6
echo "playing: [$(playing)]  alive: [$(alive)]"
show_controls
for n in 1 2 3 4; do
  onscreen content-desc Settings && break
  echo "   (controls not up; pressing again #$n)"
  key KEYCODE_DPAD_UP; sleep 2
done
echo "-- content-descs:"; dump | grep -oE 'content-desc="[^"]+"' | grep -v '=""' | tr '\n' ' '; echo
echo "-- texts:"; dump | grep -oE 'text="[^"]+"' | head -10 | tr '\n' ' '; echo
