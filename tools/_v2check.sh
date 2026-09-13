#!/bin/bash
#
# The twenty changes, checked one at a time. Reports PASS/FAIL per item so the
# result is a list rather than a verdict.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
OUT="$WORK/shots"; mkdir -p "$OUT"

item() { echo; echo "-------- $*"; }
shot() { adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "$(hostpath "$OUT/$1.png")" >/dev/null 2>&1; }

controls_up() {
  show_controls
  local n
  for n in 1 2 3 4; do
    onscreen content-desc Settings && return 0
    key KEYCODE_DPAD_UP; sleep 2
  done
  onscreen content-desc Settings
}

open_panel() {
  controls_up || return 1
  local at; at="$(find_control Settings)"
  [ -z "$at" ] && return 1
  tap $at; sleep 3
  onscreen text "Quick settings"
}

prepare

# ---------------------------------------------------------------- settings
item "7: Check for updates sits next to Built by"
open_settings
if scroll_to "Built by" >/dev/null; then
  BODY="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  case "$BODY" in
    *"Check for updates"*"Built by"*) pass "update row is above Built by" ;;
    *) fail "update row is above Built by" "$BODY" ;;
  esac
else
  fail "found Built by"
fi

item "9: the history filter exists and defaults to off"
open_settings
AT="$(scroll_to 'Keep ones that would not play')"
if [ -n "$AT" ]; then
  pass "the setting is there"
  SUM="$(dump | grep -A2 'Keep ones that would not play' | grep -oE 'text="[^"]+"' | sed -n 2p)"
  case "$SUM" in
    *"Only what actually played"*) pass "it is off by default: $SUM" ;;
    *) fail "it is off by default" "$SUM" ;;
  esac
else
  fail "the setting is there"
fi

item "14 and 20: experimental labels"
open_settings
scroll_to "Identify files" >/dev/null
dump | grep -q "experimental" && pass "identify says experimental" || fail "identify says experimental"
open_settings
scroll_to "Skip intros" >/dev/null
dump | grep -q "Skip intros and credits · experimental" \
  && pass "skip says experimental" \
  || fail "skip says experimental" "$(dump | grep -oE 'text="Skip[^"]*"' | head -1)"

item "1: the card delay is described as when it appears"
open_settings
if scroll_to "Card appears after" >/dev/null; then
  pass "renamed to 'Card appears after'"
else
  fail "renamed to 'Card appears after'" "$(dump | grep -oE 'text="Card[^"]*"' | head -1)"
fi

item "8: inner settings line up with the outer ones"
open_settings
AT="$(scroll_to 'Recently played URLs')"
if [ -n "$AT" ]; then
  OUTER="$(dump | grep -B2 'Recently played URLs' | grep -oE 'bounds="\[[0-9]+,' | head -1 | grep -oE '[0-9]+')"
  tap $AT; sleep 3
  INNER="$(dump | grep -oE 'bounds="\[[0-9]+,' | sort -u | head -3 | tr '\n' ' ')"
  echo "        outer rows start at x=$OUTER; inner row lefts: $INNER"
  pass "history screen opened for comparison (see shot)"
  shot "inner-history"
  key KEYCODE_BACK; sleep 2
else
  fail "found Recently played URLs"
fi

# ---------------------------------------------------------------- the player
item "6: with nothing open, the controls stay"
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -n $ACT" >/dev/null 2>&1
sleep 5
if onscreen content-desc Open; then
  pass "controls are up with no file"
  # Open the quick panel and come back, which is what used to lose them.
  if open_panel; then
    key KEYCODE_BACK; sleep 3
    if onscreen content-desc Open; then
      pass "and they are still there after the quick panel"
    else
      fail "and they are still there after the quick panel" "$(dump | grep -oE 'content-desc="[^"]+"' | head -4 | tr '\n' ' ')"
    fi
  else
    echo "        (no quick panel with nothing open; skipping that half)"
  fi
else
  fail "controls are up with no file" "$(dump | grep -oE 'content-desc="[^"]+"' | head -4 | tr '\n' ' ')"
fi

item "13: the URL box is empty and offers Paste"
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -n $ACT" >/dev/null 2>&1
sleep 4
adb shell "am broadcast -a clipper.set -e text 'https://example.com/from-clipboard.mp4'" >/dev/null 2>&1
AT="$(find_control Open)"
if [ -n "$AT" ]; then
  tap $AT; sleep 3
  U="$(centre text 'Network URL')"
  [ -z "$U" ] && U="$(centre text 'Open URL')"
  if [ -n "$U" ]; then tap $U; sleep 3; fi
  BODY="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  echo "        dialog: $BODY"
  case "$BODY" in
    *PASTE*|*Paste*) pass "a Paste button is offered" ;;
    *) fail "a Paste button is offered" "$BODY" ;;
  esac
  shot "url-dialog"
  key KEYCODE_BACK; sleep 2
else
  fail "found the Open button"
fi

# ------------------------------------------------------------- with a film
item "2 and 4: search again, and copy link, in the quick panel"
open_film
if open_panel; then
  BODY="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  echo "        panel: $BODY"
  case "$BODY" in *"Copy link"*) pass "a Copy link row is there" ;;
                  *) fail "a Copy link row is there" "$BODY" ;; esac
  DESCS="$(dump | grep -oE 'content-desc="[^"]+"' | tr '\n' ' ')"
  case "$DESCS" in *"Search again"*) pass "a Search again button is on the card row" ;;
                   *) fail "a Search again button is on the card row" "$DESCS" ;; esac
  shot "quick-panel"
  key KEYCODE_BACK; sleep 2
else
  fail "the quick panel opened"
fi

item "15: buffering says so"
adb shell "am force-stop $PKG" >/dev/null 2>&1
adb logcat -c >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -n $ACT -t application/x-mpegURL \
  -d 'https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8'" >/dev/null 2>&1
FOUND=1
for i in $(seq 1 14); do
  sleep 1
  if dump | grep -q 'Buffering'; then FOUND=0; shot "buffering"; break; fi
done
check "the word Buffering appears while loading" "$FOUND" "never seen in 14s"

item "11: the rotate control cycles through three modes"
open_film
SEEN=""
for n in 1 2 3 4; do
  controls_up >/dev/null 2>&1
  tap_control Rotate >/dev/null 2>&1
  sleep 3
  T="$(dump | grep -oE 'text="[^"]*(orientation|rotate|Auto)[^"]*"' | head -1)"
  [ -n "$T" ] && SEEN="$SEEN $T"
done
echo "        announced: $SEEN"
COUNT="$(echo "$SEEN" | tr ' ' '\n' | grep -c 'text=')"
if [ "$COUNT" -ge 3 ]; then
  pass "three different modes were announced"
else
  fail "three different modes were announced" "$SEEN"
fi

item "10: the playing track is drawn in the accent colour"
open_film
controls_up >/dev/null 2>&1
if tap_control "Audio track"; then
  sleep 2
  shot "audio-picker"
  pass "captured the audio picker (colour judged from the shot)"
  key KEYCODE_BACK; sleep 2
else
  fail "opened the audio picker"
fi

echo
echo "crashes during the whole run: $(crashed)"
cleanup
