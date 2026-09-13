#!/bin/bash
# 6: OK on a highlighted skip button has to press the button, not pause the film.
#
# A phone starts in touch mode, where a request for focus is refused -- which is
# why the button never highlights here and always does on a television. One
# d-pad press leaves touch mode, and from then on this behaves as a remote does.
#
# The film is identified first, because the markers come from a database and
# this test clip is not in it under its own name.
. "$(dirname "$0")/lib.sh"
trap cleanup EXIT
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2 | tr -d '\r')"
shot() { adb shell screencap -p /sdcard/jpp-shot.png >/dev/null 2>&1
         adb pull /sdcard/jpp-shot.png "$(hostpath "$WORK/shots/$1.png")" >/dev/null 2>&1; }
seen() { dump | grep -q "text=\"$1\""; }
# lib.sh already knows how to ask whether the film is running, and asking the
# same way everywhere means one answer to be wrong about.
running() { if [ -n "$(playing)" ]; then echo running; else echo stopped; fi; }

prepare
open_film

echo "--- telling it what the film is, so there are markers to skip ---"
for try in 1 2 3; do tap_control Settings && break; sleep 2; done
sleep 2
AT="$(centre content-desc 'Search again')"
[ -z "$AT" ] && { fail "no search icon on the info card row"; exit 1; }
tap $AT
sleep 3
FIELD="$(centre class android.widget.EditText)"
[ -z "$FIELD" ] && { fail "no input box"; exit 1; }
# The dialog has its own Clear, which empties the field in one press and does
# not race with typing the way a row of deletes does.
CLEAR="$(centre text 'CLEAR')"
[ -n "$CLEAR" ] && { tap $CLEAR; sleep 1; }
tap $FIELD
sleep 1
adb shell "input text 'Inception'" >/dev/null 2>&1
sleep 2
# The keyboard is drawn over the dialog buttons in its own window, which the
# screen dump does not show -- so Search looks reachable and a tap at its
# coordinates lands on a letter key instead. Put the keyboard away first; back
# closes it before it touches the dialog.
adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1
sleep 2
GO="$(centre text 'SEARCH')"
[ -z "$GO" ] && { fail "could not reach the Search button"; exit 1; }
tap $GO
sleep 8
echo "  what came back: $(dump | grep -oE 'text="[^"]+"' | head -8 | tr '\n' ' ')"
PICK="$(centre text 'Inception')"
[ -z "$PICK" ] && { fail "no Inception to pick"; exit 1; }
tap $PICK
sleep 5
pass "the film is identified"

echo "--- and now the button ---"
open_film
# Out of touch mode before the offer arrives, so it can take the focus.
key KEYCODE_DPAD_DOWN
sleep 1
adb shell "input keyevent KEYCODE_MEDIA_PLAY" >/dev/null 2>&1

found=0
for n in $(seq 1 14); do
  if seen "Skip intro"; then found=1; break; fi
  sleep 2
done
if [ "$found" = 0 ]; then
  fail "the skip offer never appeared" "nothing to press"
  exit 1
fi
pass "the skip offer appeared"

FOCUS="$(dump | grep 'focused="true"' | tail -1 | grep -oE 'text="[^"]*"' | head -1)"
echo "  focus is on: $FOCUS"
case "$FOCUS" in
  *"Skip intro"*) pass "the offer takes the focus, as it does on a remote" ;;
  *) fail "the offer did not take the focus" "focus is $FOCUS" ;;
esac

# More than one session can be listed; the player's is the first.
position() { adb shell "dumpsys media_session | grep -oE 'position=[0-9]+'" 2>/dev/null \
  | tr -d '\r' | head -1 | cut -d= -f2; }
BEFORE_POS="$(position)"
echo "  before OK: $(running) at ${BEFORE_POS}ms"
shot skip-before
key KEYCODE_DPAD_CENTER
# A screen grab, not a screen dump: the undo offer only lasts three seconds and
# a dump takes longer than that to come back.
shot skip-after
AFTER_POS="$(position)"
# A seek puts the session into BUFFERING for a moment, which is not the same as
# having been paused -- so the question is asked once it has settled.
sleep 3
AFTER_STATE="$(running)"
echo "  after OK:  $AFTER_STATE at ${AFTER_POS}ms"

if [ "$AFTER_STATE" = running ]; then
  pass "the film is still running after OK"
else
  fail "OK stopped the film instead of pressing the button"
fi
if [ -n "$BEFORE_POS" ] && [ -n "$AFTER_POS" ] && [ "$AFTER_POS" -gt $(( BEFORE_POS + 5000 )) ]; then
  pass "OK pressed the button: the film jumped past the intro"
else
  fail "OK did not press the button" "${BEFORE_POS}ms to ${AFTER_POS}ms"
fi
