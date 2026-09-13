#!/bin/bash
# Why does the panel check not reach the audio row?
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
prepare
open_film
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
echo "list bounds: [$(bounds_of resource-id "$PKG:id/list")]"
echo "rows now:    $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
AT="$(panel_row 'Audio track')"
echo "audio row:   [$AT]"
echo "rows after:  $(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
