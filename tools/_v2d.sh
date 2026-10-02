#!/bin/bash
# The rotate button cycles three modes. In portrait it sits past the strip's
# right edge, and the announcement lasts 2.5s, so it is read at once.
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

# tap_control scrolls the strip to the button; after that its position is known
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
  # no sleep: read the announcement while it is up
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
