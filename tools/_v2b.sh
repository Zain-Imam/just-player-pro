#!/bin/bash
# The items the first sweep did not reach, plus the ones its own mistakes
# reported wrongly.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
OUT="$WORK/shots"; mkdir -p "$OUT"

item() { echo; echo "-------- $*"; }
shot() { adb shell screencap -p /sdcard/s.png >/dev/null 2>&1; adb pull /sdcard/s.png "$(hostpath "$OUT/$1.png")" >/dev/null 2>&1; }

# Opening with no file offers to resume the last one; that dialog sits over the
# controls, and the test is about the controls.
dismiss_resume() {
  if dump | grep -q 'Play last video'; then
    local at; at="$(centre text 'NOT NOW')"
    [ -z "$at" ] && at="$(centre text 'Not now')"
    [ -n "$at" ] && { tap $at; sleep 2; }
  fi
}

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

item "13: the URL box starts empty and offers Paste"
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -n $ACT" >/dev/null 2>&1
sleep 5
dismiss_resume
AT="$(find_control Open)"
if [ -z "$AT" ]; then
  fail "found the Open button" "$(dump | grep -oE 'content-desc="[^"]+"' | head -5 | tr '\n' ' ')"
else
  tap $AT; sleep 3
  MENU="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  echo "        open menu: $MENU"
  U="$(centre text 'Network URL')"
  [ -z "$U" ] && U="$(centre text 'Open URL')"
  [ -z "$U" ] && U="$(centre text 'URL')"
  if [ -n "$U" ]; then
    tap $U; sleep 3
    BODY="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
    echo "        dialog: $BODY"
    case "$BODY" in *PASTE*|*Paste*) pass "Paste is offered" ;;
                    *) fail "Paste is offered" "$BODY" ;; esac
    case "$BODY" in *CANCEL*|*Cancel*) pass "Cancel is offered" ;;
                    *) fail "Cancel is offered" ;; esac
    case "$BODY" in *PLAY*|*Play*) pass "Play is offered" ;;
                    *) fail "Play is offered" ;; esac
    # The field should be empty rather than holding whatever was copied.
    if dump | grep -qE 'text="https?://'; then
      fail "the box starts empty" "something is already in it"
    else
      pass "the box starts empty"
    fi
    shot "url-dialog"
    key KEYCODE_BACK; sleep 2
  else
    fail "found the network URL entry" "$MENU"
  fi
fi

item "2 and 4: Search again, and Copy link, in the quick panel"
open_film
if open_panel; then
  BODY="$(dump | grep -oE 'text="[^"]+"' | tr '\n' ' ')"
  DESCS="$(dump | grep -oE 'content-desc="[^"]+"' | grep -v '=""' | tr '\n' ' ')"
  echo "        rows: $BODY"
  echo "        buttons: $DESCS"
  case "$BODY" in *"Copy link"*) pass "Copy link row is present" ;;
                  *) fail "Copy link row is present" "$BODY" ;; esac
  case "$DESCS" in *"Search again"*) pass "Search again button is present" ;;
                   *) fail "Search again button is present" "$DESCS" ;; esac
  shot "quick-panel"
  key KEYCODE_BACK; sleep 2
else
  fail "the quick panel opened"
fi

item "11: the rotate control cycles three ways"
open_film
SEEN=""
for n in 1 2 3; do
  controls_up >/dev/null 2>&1
  tap_control Rotate >/dev/null 2>&1
  sleep 3
  T="$(dump | grep -oE 'text="[^"]*(orientation|rotate|Auto-rotate)[^"]*"' | head -1)"
  SEEN="$SEEN|${T:-none}"
done
echo "        announced: $SEEN"
UNIQ="$(echo "$SEEN" | tr '|' '\n' | sed '/^$/d' | sort -u | wc -l)"
if [ "$UNIQ" -ge 3 ]; then pass "three distinct modes ($UNIQ seen)"
else fail "three distinct modes" "$SEEN"; fi

item "10: the playing track is drawn in the accent colour"
open_film
controls_up >/dev/null 2>&1
if tap_control "Audio track"; then
  sleep 2
  shot "audio-picker"
  echo "        rows: $(dump | grep -oE 'text="[^"]+"' | head -6 | tr '\n' ' ')"
  pass "audio picker captured (colour judged from the shot)"
  key KEYCODE_BACK; sleep 2
else
  fail "opened the audio picker"
fi

item "15: buffering says so"
adb shell "am force-stop $PKG" >/dev/null 2>&1
CURRENT_SCREEN="$ACT"
adb shell "am start -a android.intent.action.VIEW -n $ACT -t application/x-mpegURL \
  -d 'https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8'" >/dev/null 2>&1
FOUND=1
for i in $(seq 1 16); do
  sleep 1
  if dump | grep -q 'Buffering'; then FOUND=0; shot "buffering"; break; fi
done
check "the word Buffering appears while loading" "$FOUND" "not seen in 16s"

echo
echo "app crashes during this run: $(crashed)"
cleanup
