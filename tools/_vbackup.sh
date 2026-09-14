#!/bin/bash
# Export and import are offered, and the export asks what should go in the file.
#
# The file picker itself is Android's, not ours, and the interlock in lib.sh
# refuses to press anything outside this app -- which is the point of it. So
# what is checked here is everything up to the picker; what the file itself
# holds is checked by BackupTest, which does not need a device.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-backup.txt"
snap() {
  local n
  for n in 1 2 3; do
    dump > "$SNAP"
    [ -s "$SNAP" ] && grep -q 'bounds=' "$SNAP" && return 0
    sleep 2
  done
  return 0
}
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
centre_like() {
  dump | grep -F "$1" | head -1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
    | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}'
}
reach() {
  local n at
  for n in $(seq 1 18); do
    at="$(centre_like "$1")"
    [ -n "$at" ] && { echo "$at"; return 0; }
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
    sleep 1
  done
  echo ""
  return 1
}

prepare
open_settings

echo "--- the settings offer both ---"
EXPORT_AT="$(reach 'Export to a file')"
if [ -n "$EXPORT_AT" ]; then
  pass "settings offers an export"
else
  fail "no export row in settings"
  exit 1
fi
if [ -n "$(centre_like 'Import from a file')" ]; then
  pass "settings offers an import"
else
  fail "no import row in settings"
fi

echo "--- and the export asks what goes in the file ---"
tap $EXPORT_AT
sleep 3
snap
shot backup-parts
echo "  the dialog offers: $(grep -oE 'text="[^"]+"' "$SNAP" | sed 's/text=//;s/"//g' | tr '\n' '|')"

for want in 'What should go in it?' 'Settings' 'Keys and addons' 'History and remembered titles' 'Delays and speeds, per file'; do
  if grep -qF "$want" "$SNAP"; then
    pass "it offers: $want"
  else
    fail "the export dialog is missing: $want"
  fi
done

if grep -qF 'cannot travel in a file' "$SNAP"; then
  pass "it says the folder grants cannot travel"
else
  fail "nothing said about what cannot be exported"
fi

echo "--- leaving without exporting ---"
CANCEL="$(centre_like 'CANCEL')"
[ -z "$CANCEL" ] && CANCEL="$(centre_like 'Cancel')"
if [ -n "$CANCEL" ]; then
  tap $CANCEL
  sleep 2
  pass "the dialog can be left alone"
else
  key KEYCODE_BACK
  sleep 2
fi

if [ "$(crashed)" = 0 ]; then
  pass "nothing crashed"
else
  fail "the app crashed" "$(adb logcat -b crash -d | grep -A6 'FATAL EXCEPTION' | head -12)"
fi
