#!/bin/bash
# Each key row says whether its key is set.
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

# the summary line under a row's title
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

# read from the app: the .env test keys may set every one of them
key_set() { adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null   | tr -d '' | grep -E "name=\"$1\">[^<]+<" >/dev/null; }
echo "--- the others, ticked exactly when they have a key ---"
for pair in "SubDL key:apiKeySubdl" "OpenSubtitles key:apiKeyOpenSubtitles"; do
  row="${pair%%:*}"; pref="${pair##*:}"
  if ! reach "$row"; then
    echo "  (no row called $row)"
    continue
  fi
  SUM="$(summary_under "$row")"
  echo "  $row: [$SUM]"
  if key_set "$pref"; then
    case "$SUM" in
      ✓*) pass "$row has a key and is ticked" ;;
      *)  fail "$row has a key but is not ticked" "[$SUM]" ;;
    esac
  else
    case "$SUM" in
      "Not set") pass "$row says it is not set" ;;
      ✓*)        fail "$row is ticked without a key" "[$SUM]" ;;
      *)         fail "$row says nothing either way" "[$SUM]" ;;
    esac
  fi
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
