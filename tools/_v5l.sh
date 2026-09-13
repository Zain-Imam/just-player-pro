#!/bin/bash
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
prepare
open_film
show_controls >/dev/null
echo "strip row: [$(bounds_of resource-id "$PKG:id/controls_scroll_view")]"
echo "visible now: $(dump | grep -oE 'content-desc="[^"]+"' | sort -u | tr '\n' ' ')"
row="$(bounds_of resource-id "$PKG:id/controls_scroll_view")"
y="$(echo "$row" | awk '{print int(($2 + $4) / 2)}')"
echo "y=$y"
for n in 1 2 3; do
  swipe $((SCREEN_W - 60)) "$y" 100 "$y" 250
  sleep 1
  echo "after swipe $n: $(dump | grep -oE 'content-desc="[^"]+"' | sort -u | tr '\n' ' ')"
done
