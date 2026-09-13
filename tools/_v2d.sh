#!/bin/bash
# #11 again. The rotate button lives past the right edge of the strip in
# portrait, so it has to be scrolled into view before it can be pressed — and
# the announcement only lasts two and a half seconds, so it has to be read at
# once rather than after the helper's usual pause.
set -u
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
SCREEN_W="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
controls_up() {
  show_controls
  local n; for n in 1 2 3 4; do onscreen content-desc Settings && return 0; key KEYCODE_DPAD_UP; sleep 2; done
  onscreen content-desc Settings
}

prepare
open_film

# The first press goes through tap_control, which drags the strip along until
# the button is visible. After that its position is known.
controls_up >/dev/null 2>&1
if ! tap_control Rotate; then
  fail "the rotate button can be reached at all"
  cleanup
fi
pass "the rotate button can be reached"

SEEN=""
for n in 1 2 3 4; do
  controls_up >/dev/null 2>&1
  AT="$(centre content-desc Rotate)"
  if [ -z "$AT" ]; then
    echo "        (rotate slipped out of view on pass $n)"
    continue
  fi
  tap $AT
  # No sleep: read the announcement while it is still up.
  T="$(dump | grep -oE 'text="[^"]*(orientation|Auto-rotate)[^"]*"' | head -1)"
  echo "        pass $n: ${T:-nothing announced}"
  [ -n "$T" ] && SEEN="$SEEN
$T"
  sleep 2
done

UNIQ="$(echo "$SEEN" | sed '/^$/d' | sort -u)"
COUNT="$(echo "$UNIQ" | sed '/^$/d' | wc -l)"
echo "        distinct modes seen: $COUNT"
echo "$UNIQ" | sed '/^$/d' | sed 's/^/          /'
if [ "$COUNT" -ge 3 ]; then
  pass "the control cycles through three modes"
else
  fail "the control cycles through three modes" "only $COUNT distinct"
fi

echo
echo "app crashes: $(crashed)"
cleanup
