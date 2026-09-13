#!/bin/bash
# Are the two smoke failures a button that is missing, or one that is merely
# past the edge of a strip that scrolls?
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
held() {
  set -- $(dump | grep -m1 -oE 'bounds="\[0,0\]\[[0-9]+,[0-9]+\]"' \
    | sed -E 's/.*\[0,0\]\[([0-9]+),([0-9]+)\].*/\1 \2/')
  if [ "${1:-0}" -gt "${2:-0}" ]; then echo landscape; else echo portrait; fi
}
prepare
open_film
echo "held: $(held)"
echo "--- in whatever it opened in ---"
for name in Resize "Lock screen" Settings; do
  if tap_control "$name" >/dev/null 2>&1; then echo "  reached: $name"; else echo "  MISSED:  $name"; fi
  key KEYCODE_BACK >/dev/null 2>&1
  sleep 1
done

echo "--- turned to landscape, where the whole strip fits ---"
for n in 1 2 3; do
  AT="$(find_control Rotate)"
  if [ -z "$AT" ]; then show_controls >/dev/null; AT="$(find_control Rotate)"; fi
  [ -z "$AT" ] && { row="$(bounds_of resource-id "$PKG:id/controls_scroll_view")"
                    y="$(echo "$row" | awk '{print int(($2 + $4) / 2)}')"
                    swipe $((SCREEN_W - 60)) "$y" 100 "$y" 250; sleep 1; AT="$(find_control Rotate)"; }
  [ -z "$AT" ] && break
  tap $AT
  sleep 3
  [ "$(held)" = landscape ] && break
done
echo "held: $(held)"
for name in Resize "Lock screen" Settings; do
  if tap_control "$name" >/dev/null 2>&1; then echo "  reached: $name"; else echo "  MISSED:  $name"; fi
  key KEYCODE_BACK >/dev/null 2>&1
  sleep 1
done
