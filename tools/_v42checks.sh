#!/bin/bash
# Checks that drive the player from outside, on one engine.
# Usage: ENGINE=<mpv|media3> PKG=<package> tools/_v42checks.sh [A B C D E]
# C, D and E need the debug build (preferences and logs).

. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"
trap cleanup EXIT

ENGINE="${ENGINE:-mpv}"
WANT="${*:-A B C D E}"
DEBUG_BUILD=0
case "$PKG" in *.debug) DEBUG_BUILD=1 ;; esac
FROM_HOME="--ez com.brouken.player.FROM_HOME true"

if [ $DEBUG_BUILD -eq 1 ]; then
  setpref string playbackEngine "$ENGINE"
  setpref boolean autoPiP false
fi
echo "== $PKG on $ENGINE"

playing_now() {
  adb shell dumpsys media_session | tr -d '\r' | grep -A14 "package=$PKG\$" \
    | grep -m1 -oE 'state=[A-Z_]+'
}

advancing() {
  local a b
  a="$(position_ms)"; sleep 4; b="$(position_ms)"
  [ -n "$a" ] && [ -n "$b" ] && [ $((b - a)) -gt 2000 ]
}

has() { case " $WANT " in *" $1 "*) return 0 ;; esac; return 1; }

# --- A
if has A; then
  echo; echo "-- A: a film from another app while an older player screen is in memory"
  server "reset=1"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -n $ACT -d '$SERVER/sintel.mkv' $FROM_HOME" >/dev/null 2>&1
  sleep 8
  adb shell "input keyevent KEYCODE_HOME" >/dev/null 2>&1
  sleep 3
  # A second task, as another app's launch makes one.
  adb shell "am start -a android.intent.action.VIEW -d '$SERVER/clip.ts' -n $ACT -f 0x18000000 --es title Clip" >/dev/null 2>&1
  sleep 12
  STATE="$(playing_now)"
  if advancing; then
    pass "the new film plays ($STATE)"
  else
    fail "the new film does not play" "state: $STATE, position: $(position_ms)"
  fi
  check "no crash" "$(crashed)"
fi

# --- B
if has B; then
  echo; echo "-- B: headers as whole \"Name: value\" strings, and as pairs"
  server "reset=1"
  server "needHeader=Referer"
  launch_url "$SERVER/secure/sintel.mkv" "--esa headers 'Referer: http://launcher.example/,User-Agent: LaunchTest/1.0' --es title Secure"
  sleep 10
  if advancing; then pass "a stream that needs a Referer plays"; else fail "a stream that needs a Referer plays" "state $(playing_now)"; fi
  if curl -s "$SERVER/log" | tail -20 | grep -q "referer=http://launcher.example/"; then
    pass "the Referer reached the server"
  else
    fail "the Referer reached the server" "$(curl -s "$SERVER/log" | grep -E 'secure' | tail -3)"
  fi
  if curl -s "$SERVER/log" | tail -20 | grep -q "ua=LaunchTest/1.0"; then
    pass "the User-Agent reached the server"
  else
    fail "the User-Agent reached the server" "$(curl -s "$SERVER/log" | grep -E 'secure' | tail -2)"
  fi
  launch_url "$SERVER/secure/sintel.mkv" "--esa headers Referer,http://pairs.example/ --es title Pairs"
  sleep 10
  if curl -s "$SERVER/log" | tail -20 | grep -q "referer=http://pairs.example/"; then
    pass "name/value pairs still work"
  else
    fail "name/value pairs still work"
  fi
  # a browser User-Agent has commas; it is sent with --es, since in an --esa list
  # an escaped comma reaches the app with its backslash
  UA='Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
  server "reset=1"
  curl -s -G "$SERVER/ctl" --data-urlencode "needHeader=User-Agent" --data-urlencode "needValue=$UA" >/dev/null
  launch_url "$SERVER/secure/sintel.mkv" "--es headers 'User-Agent: $UA' --es title Commas"
  sleep 10
  if advancing; then
    pass "a header value with commas in it arrives whole"
  else
    fail "a header value with commas in it arrives whole" "$(curl -s "$SERVER/log" | grep -E '403|secure' | tail -2)"
  fi
  server "reset=1"
fi

# --- C
if has C && [ $DEBUG_BUILD -eq 1 ]; then
  echo; echo "-- C: opened again from inside the app, with what came with it"
  server "reset=1"
  server "needHeader=Referer"
  launch_url "$SERVER/secure/clip.ts?token=one" "--esa headers 'Referer: http://launcher.example/' --es title 'Remembered Clip' --esa subs $SERVER/smoke.srt --esa subs.name 'English (Test Addon)' --esa subs.enable $SERVER/smoke.srt"
  sleep 10
  advancing && pass "launched with headers and a subtitle" || fail "launched with headers and a subtitle"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 1
  adb logcat -c >/dev/null 2>&1
  # The way the home screen and Recents open a film: no extras at all.
  adb shell "am start -n $ACT -d '$SERVER/secure/clip.ts?token=one' $FROM_HOME" >/dev/null 2>&1
  sleep 12
  if advancing; then
    pass "reopened from inside the app, the stream still gets its headers"
  else
    fail "reopened from inside the app, the stream still gets its headers" "$(curl -s "$SERVER/log" | tail -3)"
  fi
  TRACKS="$(player_log 200 | grep -oE 'tracks\(v/a/t\)=[0-9]+/[0-9]+/[0-9]+' | tail -1)"
  case "$TRACKS" in
    */*/0|"") fail "the handed-over subtitle came back" "$TRACKS" ;;
    *) pass "the handed-over subtitle came back ($TRACKS)" ;;
  esac
  if getpref launchMemory | grep -q "English (Test Addon)"; then
    pass "remembered under the name it was sent with"
  else
    fail "remembered under the name it was sent with"
  fi
  server "reset=1"
fi

# --- D
if has D && [ $DEBUG_BUILD -eq 1 ]; then
  echo; echo "-- D: the shape chosen for a film, on a link with a new token"
  setpref string aspectMap "[]"
  server "reset=1"
  launch_url "$SERVER/sintel.mkv?token=first" "--ei position 30000 --es title Sintel"
  sleep 8
  adb shell "input keyevent KEYCODE_MEDIA_PAUSE" >/dev/null 2>&1
  sleep 1
  adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  sleep 1
  AT="$(centre content-desc Resize)"
  [ -z "$AT" ] && AT="$(bounds_of resource-id "$PKG:id/controls" >/dev/null; dump | grep -F 'content-desc="Aspect' | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' | awk 'NF==4 {print int(($1+$3)/2), int(($2+$4)/2)}')"
  if [ -n "$AT" ]; then
    adb shell "input tap $AT" >/dev/null 2>&1
    sleep 2
    player_log 60 | grep -q "Aspect step 1 chosen" && pass "crop chosen" || fail "crop chosen" "$(player_log 40 | grep Aspect | tail -2)"
    launch_url "$SERVER/sintel.mkv?token=second" "--ei position 30000 --es title Sintel"
    sleep 8
    if player_log 200 | grep -q "Aspect step 1 applied"; then
      pass "the same film on a new link opens cropped"
    else
      fail "the same film on a new link opens cropped" "$(player_log 200 | grep Aspect | tail -3)"
    fi
    launch_url "$SERVER/clip.ts" "--es title Clip"
    sleep 6
    if player_log 200 | grep -q "Aspect step 0 applied"; then
      pass "a different film opens at Default"
    else
      fail "a different film opens at Default" "$(player_log 200 | grep Aspect | tail -3)"
    fi
    launch_url "$SERVER/sintel.mkv?token=third" "--ei position 30000 --es title Sintel"
    sleep 8
    player_log 200 | grep -q "Aspect step 1 applied" && pass "and the first film is still cropped after it" \
      || fail "and the first film is still cropped after it"
  else
    fail "found the aspect button" "$(dump | grep -oE 'content-desc="[^"]+"' | head -20 | tr '\n' ' ')"
  fi
fi

# --- E
if has E && [ $DEBUG_BUILD -eq 1 ]; then
  echo; echo "-- E: play again at the end"
  server "reset=1"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  # From inside the app: a film another app sent closes at its end instead.
  adb shell "am start -n $ACT -d '$SERVER/clip.ts' $FROM_HOME" >/dev/null 2>&1
  sleep 8
  adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  sleep 1
  # The very end of the seek bar on this phone.
  adb shell "input tap 2250 926" >/dev/null 2>&1
  sleep 15
  echo "  state at the end: $(playing_now) at $(position_ms)ms"
  adb shell "input keyevent KEYCODE_DPAD_UP" >/dev/null 2>&1
  sleep 1
  PLAY="$(centre content-desc Play)"
  if [ -z "$PLAY" ]; then
    fail "found the play button at the end" "$(dump | grep -oE 'content-desc="[^"]+"' | head -12 | tr '\n' ' ')"
  else
    adb shell "input tap $PLAY" >/dev/null 2>&1
    sleep 5
    P="$(position_ms)"
    if [ -n "$P" ] && [ "$P" -lt 15000 ] && advancing; then
      pass "play at the end starts the film again (at ${P}ms)"
    else
      fail "play at the end starts the film again" "position ${P}ms, state $(playing_now)"
    fi
  fi
fi
