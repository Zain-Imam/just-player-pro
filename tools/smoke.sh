#!/bin/bash
#
# Drive the player through the things that break, and say what failed.
#
# The unit tests cover what can be checked without a screen and the
# instrumented tests cover what only the device can answer. This covers the
# third kind: behaviour you can only see by using the thing — that a lock
# actually locks, that a panel opens on the edge it is supposed to, that a
# button saying "Identifying" eventually stops saying it.
#
# Every check prints PASS or FAIL and the script exits non-zero if any failed,
# so it can be run before a build goes out rather than after somebody complains.

HERE_SCRIPT="$(cd "$(dirname "$0")" && pwd)"
. "$HERE_SCRIPT/lib.sh"
trap cleanup EXIT

# ------------------------------------------------------------------- checks

prepare
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"

echo
echo "== it opens and plays"
open_film
[ -n "$(alive)" ]   && pass "the app is running"  || fail "the app is running"
[ -n "$(playing)" ] && pass "it is playing"       || fail "it is playing"
check "no crash on opening" "$(crashed)"

echo
echo "== the header names the file, the format and the engine"
show_controls
META=$(dump | grep -oE 'text="[0-9]+×[0-9]+[^"]*"' | head -1)
case "$META" in
  *Media3*|*mpv*) pass "header: $META" ;;
  "")             fail "header line missing" ;;
  *)              fail "header line names no engine" "$META" ;;
esac

echo
echo "== the subtitle handed over on the intent is there"
tap_control "Disable subtitles" "Enable subtitles" || fail "found the subtitle button"
if onscreen text "Smoke"; then
  pass "listed under the name the launcher gave it"
else
  fail "listed under the name the launcher gave it" \
       "$(dump | grep -oE 'text="[^"]+"' | head -6 | tr '\n' ' ')"
fi
SB="$(bounds_of text Subtitles)"
if [ -n "$SB" ]; then
  set -- $SB
  if [ "$3" -gt $(((SCREEN_W * 85) / 100)) ]; then
    pass "the picker reaches the trailing edge (to x=$3 of $SCREEN_W)"
  else
    fail "the picker does not reach the trailing edge" "ends at x=$3 of $SCREEN_W"
  fi
fi
onscreen text "Off" && pass "Off is offered" || fail "Off is offered"
OFFAT="$(centre text Off)"
if [ -n "$OFFAT" ]; then tap $OFFAT; sleep 2; fi
[ -n "$(alive)" ] && pass "turning subtitles off survives" || fail "turning subtitles off survives"

echo
echo "== the audio picker describes the track rather than numbering it"
if tap_control "Audio track"; then
  if dump | grep -qE 'Stereo|Mono|5\.1|7\.1|[0-9]ch'; then
    pass "the channel layout is shown"
  else
    fail "the channel layout is shown" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
  fi
  if dump | grep -qE 'AAC|AC-3|E-AC-3|DTS|Opus|FLAC|MP3'; then
    pass "the codec is shown"
  else
    fail "the codec is shown"
  fi
  key KEYCODE_BACK; sleep 2
else
  fail "found the audio button"
fi

echo
echo "== the quick panel opens and carries everything it should"
if tap_control Settings; then
  for row in "Speed" "Playback engine" "Sleep timer" "Show info card" "Audio track"; do
    onscreen text "$row" && pass "quick panel has: $row" || fail "quick panel has: $row"
  done
  QP="$(bounds_of text 'Quick settings')"
  if [ -n "$QP" ]; then
    set -- $QP
    if [ "$3" -gt $(((SCREEN_W * 85) / 100)) ]; then
      pass "the quick panel reaches the trailing edge (to x=$3 of $SCREEN_W)"
    else
      fail "the quick panel does not reach the trailing edge" "ends at x=$3"
    fi
  fi
  key KEYCODE_BACK; sleep 2
else
  fail "found the settings button"
fi

echo
echo "== asking for the info card always finishes"
if tap_control Settings; then
  CARD="$(centre text 'Show info card')"
  if [ -z "$CARD" ]; then
    fail "found Show info card"
  else
    pass "found Show info card"
    tap $CARD
    SETTLED=1
    for i in $(seq 1 14); do
      sleep 2
      if [ "$(dump | grep -c 'Identifying')" = "0" ]; then SETTLED=0; break; fi
    done
    check "it stops saying Identifying" "$SETTLED" "still identifying after 28s"
    [ -n "$(alive)" ] && pass "the app survived asking" || fail "the app survived asking"
  fi
fi

echo
echo "== stepping through every scaling mode breaks nothing"
open_film
BROKE=0
for i in 1 2 3 4 5 6 7 8 9 10 11; do
  tap_control Resize >/dev/null 2>&1 || BROKE=1
  if [ -z "$(alive)" ]; then BROKE=1; break; fi
done
check "eleven presses of the frame button" "$BROKE"
[ -n "$(alive)" ] && pass "still running after every scaling mode" || fail "still running after every scaling mode"
check "no crash from resizing" "$(crashed)"

echo
echo "== the lock locks"
open_film
if ! tap_control "Lock screen"; then
  fail "found the padlock"
else
  if onscreen content-desc Settings; then tap_control "Lock screen" || true; fi
  if onscreen content-desc Settings; then
    fail "the padlock engaged" "the controls are still up"
  else
    pass "the padlock engaged"
    BEFORE="$(playing)"
    key KEYCODE_DPAD_CENTER; sleep 1
    key KEYCODE_DPAD_RIGHT;  sleep 1
    key KEYCODE_DPAD_LEFT;   sleep 1
    key KEYCODE_ENTER;       sleep 1
    key KEYCODE_BACK;        sleep 2
    if [ -n "$(alive)" ]; then
      pass "locked: back did not leave the player"
    else
      fail "locked: back left the player"
    fi
    tap $((SCREEN_W / 2)) $((SCREEN_H / 2)); sleep 2

    if [ -z "$(alive)" ]; then
      fail "locked: the app is still there"
    else
      pass "locked: the app is still there"
      if [ "$BEFORE" = "$(playing)" ]; then
        pass "locked: nothing started or stopped playing"
      else
        fail "locked: playback state changed"
      fi
      if onscreen content-desc Settings; then
        fail "locked: the controls came back"
      else
        pass "locked: the controls stay hidden"
      fi

      hold KEYCODE_DPAD_CENTER
      sleep 2
      show_controls
      if onscreen content-desc Settings; then
        pass "locked: holding OK lifts it"
      else
        fail "locked: holding OK did not lift it"
      fi
    fi
  fi
fi

echo
echo "== every screen in settings opens and comes back"
open_settings
for row in "Theme colour" "Playback engine" "Subtitle addons" "Recently played URLs"; do
  open_settings
  AT="$(scroll_to "$row")"
  if [ -z "$AT" ]; then
    fail "found in settings: $row"
  else
    pass "found in settings: $row"
    tap $AT; sleep 3
    [ -n "$(alive)" ] && pass "opened without dying: $row" || fail "opened without dying: $row"
    key KEYCODE_BACK; sleep 2
  fi
done
open_settings
AT="$(scroll_to 'Built by')"
[ -n "$AT" ] && pass "found in settings: Built by" || fail "found in settings: Built by"

echo
echo "== the update check answers rather than hanging"
open_settings
AT="$(scroll_to 'Check for updates')"
if [ -n "$AT" ]; then
  tap $AT
  sleep 10
  [ -n "$(alive)" ] && pass "the update check came back" || fail "the update check came back"
else
  fail "found Check for updates"
fi

echo
echo "== nothing crashed at any point"
check "crash log is empty" "$(crashed)" "$(adb logcat -b crash -d 2>/dev/null | head -8)"

echo
echo "== the run left the settings as it found them"
adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null > "$WORK/prefs-after.xml"
if [ -s "$WORK/prefs-before.xml" ]; then
  DRIFT="$(diff "$WORK/prefs-before.xml" "$WORK/prefs-after.xml" 2>/dev/null \
           | grep -E '^[<>]' | grep -oE 'name="[^"]+"' | sort -u \
           | grep -vE 'mediaUri|mediaType|position|urlHistory|onlineIdentities|subtitleUri|subtitleTrackId|resizeMode|aspectStep|brightness|scale|speed' \
           | tr '\n' ' ')"
  if [ -z "$DRIFT" ]; then
    pass "no lasting setting was changed"
  else
    fail "the run changed settings" "$DRIFT"
  fi
fi
