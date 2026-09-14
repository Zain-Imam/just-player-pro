#!/bin/bash
# A key that is set says so, and one that is not says that.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
SNAP="$WORK/snap-ticks.txt"
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

# The row's own summary: the line under a title, in the same block as it.
summary_under() {
  snap
  grep -A6 -F "text=\"$1" "$SNAP" | grep -oE 'text="(✓[^"]*|Not set)"' | head -1 \
    | sed 's/text=//;s/"//g'
}
reach() {
  local n at
  for n in $(seq 1 18); do
    at="$(dump | grep -F "$1" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)"
    [ -n "$at" ] && return 0
    swipe $((SCREEN_W / 2)) $((SCREEN_H * 70 / 100)) $((SCREEN_W / 2)) $((SCREEN_H * 40 / 100)) 700
    sleep 1
  done
  return 1
}

prepare
open_settings

echo "--- the keys that are in ---"
for row in "TMDB key" "Wyzie key"; do
  if ! reach "$row"; then
    fail "no row called $row"
    continue
  fi
  SUM="$(summary_under "$row")"
  echo "  $row: [$SUM]"
  case "$SUM" in
    ✓*) pass "$row is ticked" ;;
    *)  fail "$row is set but not ticked" "[$SUM]" ;;
  esac
done
shot ticks-set

echo "--- and the ones that are not ---"
for row in "SubDL key" "OpenSubtitles key"; do
  if ! reach "$row"; then
    echo "  (no row called $row)"
    continue
  fi
  SUM="$(summary_under "$row")"
  echo "  $row: [$SUM]"
  case "$SUM" in
    "Not set") pass "$row says it is not set" ;;
    ✓*)        fail "$row is ticked without a key" "[$SUM]" ;;
    *)         fail "$row says nothing either way" "[$SUM]" ;;
  esac
done

echo "--- the addons row counts them ---"
if reach "Custom subtitle addons"; then
  snap
  SUM="$(grep -A6 -F 'text="Custom subtitle addons' "$SNAP" | grep -oE 'text="[^"]+"' | sed -n '2p' | sed 's/text=//;s/"//g')"
  echo "  Subtitle addons: [$SUM]"
  case "$SUM" in
    ✓*) pass "the addons row is ticked" ;;
    *)  fail "the addons row does not say whether any are set" "[$SUM]" ;;
  esac
fi
shot ticks-addons
