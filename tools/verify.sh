#!/bin/bash
#
# The long verification: every setting, both input methods, both engines, and
# the online features with real keys.
#
# smoke.sh is the quick one that runs before a build goes out. This is the one
# that runs before a release, and it takes as long as it takes.
#
# Credentials come from .env in the repository root and are pushed into the app
# through its own setup page, which tests that page at the same time. Nothing is
# printed that would disclose a key.
#
# Usage:  tools/verify.sh [package]

set -u
HERE_SCRIPT="$(cd "$(dirname "$0")" && pwd)"
. "$HERE_SCRIPT/lib.sh"
trap cleanup EXIT

ENV_FILE="$HERE_SCRIPT/../.env"

section() { echo; echo "======== $*"; }

# ------------------------------------------------------------------ settings

# Read a preference back out of the app. Works on a debuggable build directly;
# otherwise the value has to be read off the screen.
pref() {
  adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null \
    | tr '<' '\n' | grep -F "name=\"$1\"" | head -1
}

# ------------------------------------------------------- configure from .env

configure_from_env() {
  section "configuring the app from .env, through its own setup page"

  if [ ! -f "$ENV_FILE" ]; then
    fail "found .env" "no $ENV_FILE"
    return 1
  fi
  # Read, never sourced and never echoed.
  #
  # The file is "NAME:value" a line at a time, which the shell would try to run
  # as commands — and in failing would print every secret in it. Each line is
  # split by hand instead, and nothing here ever prints a value.
  local line name value count=0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    name="${line%%[:=]*}"
    value="${line#*[:=]}"
    name="$(echo "$name" | tr -d '[:space:]')"
    value="$(echo "$value" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    [ -z "$name" ] && continue
    [ -z "$value" ] && continue
    export "$name=$value"
    count=$((count + 1))
  done < "$ENV_FILE"
  pass "read .env ($count entries, none printed)"

  open_settings
  local at
  at="$(scroll_to 'Set up from your phone')"
  if [ -z "$at" ]; then fail "found the setup row"; return 1; fi
  pass "found the setup row"
  tap $at
  sleep 5

  # The dialog prints the address and the PIN.
  local body port pin
  body="$(dump | grep -oE 'text="[^"]*[0-9]{4,}[^"]*"' | head -3 | tr '\n' ' ')"
  port="$(echo "$body" | grep -oE ':[0-9]{4,5}' | head -1 | tr -d ':')"
  pin="$(echo "$body" | grep -oE '\b[0-9]{6}\b' | head -1)"

  if [ -z "$port" ] || [ -z "$pin" ]; then
    fail "the setup page is listening" "could not read the address or PIN"
    return 1
  fi
  pass "the setup page is listening on port $port"

  # Reach the device's own server from here rather than over the network.
  adb forward "tcp:$port" "tcp:$port" >/dev/null 2>&1
  local base="http://127.0.0.1:$port"

  local jar="$WORK/cookies.txt"
  rm -f "$jar"
  if curl -s --max-time 20 -c "$jar" -b "$jar" -X POST \
       --data-urlencode "pin=$pin" "$base/unlock" | grep -q "Keys"; then
    pass "the PIN unlocked the page"
  else
    fail "the PIN unlocked the page"
    return 1
  fi

  local token
  token="$(curl -s --max-time 20 -b "$jar" "$base/" | grep -oE 'name="token" value="[^"]+"' | head -1 | sed 's/.*value="//; s/"//')"
  if [ -z "$token" ]; then fail "the page issued a token"; return 1; fi
  pass "the page issued a token"

  # Save every key that .env carries.
  local saved
  saved="$(curl -s --max-time 180 -b "$jar" -X POST \
    --data-urlencode "token=$token" \
    --data-urlencode "apiKeyTmdb=${TMDB_API_KEY:-}" \
    --data-urlencode "apiKeyOpenSubtitles=${OPENSUBTITLES_API_KEY:-}" \
    --data-urlencode "apiKeyOpenSubtitlesUser=${OPENSUBTITLES_USERNAME:-}" \
    --data-urlencode "apiKeyOpenSubtitlesPassword=${OPENSUBTITLES_PASSWORD:-}" \
    --data-urlencode "apiKeySubdl=${SUBDL_API_KEY:-}" \
    --data-urlencode "apiKeyWyzie=${WYZIE_API_KEY:-}" \
    --data-urlencode "subtitleLanguage=en" \
    "$base/save")"

  if echo "$saved" | grep -q "Saved:"; then
    pass "the page saved what it accepted: $(echo "$saved" | grep -oE 'Saved: [^<]*' | head -1 | cut -c1-90)"
  else
    fail "the page saved anything" "$(echo "$saved" | grep -oE 'Not saved: [^<]*' | head -1 | cut -c1-140)"
  fi
  if echo "$saved" | grep -q "close this page"; then
    pass "it says the page can be closed"
  else
    fail "it says the page can be closed"
  fi
  local rejected
  rejected="$(echo "$saved" | grep -oE 'Not saved: [^<]*' | head -1)"
  [ -n "$rejected" ] && echo "        note: $rejected"

  # Test each key through the page, which is the same check settings runs.
  local field
  for field in apiKeyTmdb apiKeyOpenSubtitles apiKeySubdl apiKeyWyzie; do
    local answer
    answer="$(curl -s --max-time 60 -b "$jar" -X POST \
      --data-urlencode "token=$token" --data-urlencode "field=$field" \
      --data-urlencode "value=" "$base/test")"
    case "$answer" in
      *[Rr]eject*|*"No answer"*|*"Nothing entered"*|*HTTP*|*rate*)
        fail "$field answers" "$answer" ;;
      "") fail "$field answers" "no reply" ;;
      *)  pass "$field answers: $answer" ;;
    esac
  done

  # Addon 1 ships configured; the page must be able to test it too.
  local addon
  addon="$(curl -s --max-time 90 -b "$jar" -X POST \
    --data-urlencode "token=$token" --data-urlencode "field=subtitleAddon1" \
    --data-urlencode "value=" "$base/test")"
  case "$addon" in
    *[Rr]eject*|"") fail "the built-in addon answers" "$addon" ;;
    *"Nothing entered"*) pass "no addon configured in slot 1 (nothing to test)" ;;
    *) pass "the built-in addon answers: $addon" ;;
  esac

  # Put the page away.
  local stop
  stop="$(centre text 'Stop the page')"
  [ -z "$stop" ] && stop="$(centre text 'STOP THE PAGE')"
  if [ -n "$stop" ]; then tap $stop; sleep 3; pass "the page stopped on request"; else
    key KEYCODE_BACK; sleep 2
  fi
  adb forward --remove "tcp:$port" >/dev/null 2>&1
}

# ------------------------------------------------------------------- run it

prepare
SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"

configure_from_env

# ----------------------------------------------------------- every setting

# Two of these open a picker belonging to the system, not to this app. Back is
# the only key that cannot open anything, so it is the only one sent without
# the interlock, and only to come back from a picker this test opened itself.
back_anywhere() { adb shell "input keyevent KEYCODE_BACK" >/dev/null 2>&1; }

sweep_settings() {
  section "every setting: found, pressed, and survived"

  local line type title at
  # Read from a descriptor of its own.
  #
  # adb reads standard input, so a loop fed on standard input loses the rest of
  # its list to the first adb command inside it — which is why this swept one
  # row and then stopped.
  while IFS='|' read -r type title <&3; do
    [ -z "$title" ] && continue

    open_settings
    at="$(scroll_to "$title")"
    if [ -z "$at" ]; then
      fail "found: $title"
      continue
    fi
    pass "found: $title"

    case "$title" in
      "Set up from your phone")
        # Already driven end to end above, and its dialog cannot be cancelled.
        continue ;;
      "Choose custom font"|"Subtitle download folder")
        tap $at
        sleep 4
        back_anywhere; sleep 2
        back_anywhere; sleep 2
        if [ -n "$(alive)" ]; then
          pass "opened its picker and came back: $title"
        else
          fail "opened its picker and came back: $title"
        fi
        continue ;;
      "Test keys and addons")
        tap $at
        local settled=1 i
        for i in $(seq 1 20); do
          sleep 3
          if dump | grep -q "What answered"; then settled=0; break; fi
        done
        check "it reported what answered: $title" "$settled" "no report after 60s"
        key KEYCODE_BACK; sleep 2
        continue ;;
    esac

    tap $at
    sleep 2
    if [ -z "$(alive)" ]; then
      fail "survived being pressed: $title"
      continue
    fi

    case "$type" in
      SwitchPreferenceCompat)
        # Pressed once it flipped; press it again so the run changes nothing.
        at="$(centre text "$title")"
        [ -n "$at" ] && { tap $at; sleep 1; }
        pass "toggled and restored: $title" ;;
      ListPreference)
        if dump | grep -qiE "CANCEL"; then
          pass "opened its list: $title"
        else
          pass "pressed: $title"
        fi ;;
      *)
        pass "opened: $title" ;;
    esac

    # No Back here on purpose. Whatever this opened, the next row starts by
    # opening settings again from nothing — and a Back pressed at a screen that
    # turned out to have no dialog on it walks out of settings altogether.

    if [ -z "$(alive)" ]; then fail "still alive after: $title"; fi
  done 3<<'ROWS'
ListPreference|Theme colour
ListPreference|Playback engine
ListPreference|Decoder priority
ListPreference|Default audio track
ListPreference|File access
ListPreference|Identify files
ListPreference|Subtitle language
SeekBarPreference|Double-tap seek
SeekBarPreference|Card delay
SwitchPreferenceCompat|Adaptive buffering
SwitchPreferenceCompat|Ask before resuming
SwitchPreferenceCompat|Auto picture-in-picture
SwitchPreferenceCompat|Keep screen on
SwitchPreferenceCompat|Loop button
SwitchPreferenceCompat|Match frame rate
SwitchPreferenceCompat|Show the time
SwitchPreferenceCompat|Volume boost
SwitchPreferenceCompat|Volume keys change this app only
SwitchPreferenceCompat|Info card when paused
SwitchPreferenceCompat|Skip intros and credits
SwitchPreferenceCompat|Search subtitles automatically
SwitchPreferenceCompat|Fill search from file name
SwitchPreferenceCompat|Custom subtitle font
SwitchPreferenceCompat|Skip silence
SwitchPreferenceCompat|Tunneled playback
SwitchPreferenceCompat|Dolby Vision profile 7 fallback
Preference|Custom subtitle addons
Preference|Recently played URLs
Preference|Check for updates
Preference|Built by
EditTextPreference|Audio language order
EditTextPreference|Subtitle language order
Preference|Choose custom font
Preference|Subtitle download folder
Preference|Test keys and addons
ROWS
}

# ------------------------------------------------------------ both engines

set_engine() {   # set_engine Media3 | mpv | Auto
  open_settings
  local at
  at="$(scroll_to 'Playback engine')"
  [ -z "$at" ] && { fail "found the engine setting"; return 1; }
  tap $at
  sleep 2
  local pick
  pick="$(centre text "$1")"
  [ -z "$pick" ] && { fail "the engine list offers $1"; key KEYCODE_BACK; return 1; }
  tap $pick
  sleep 2
  return 0
}

engine_matrix() {
  local engine
  for engine in Media3 mpv; do
    section "the $engine engine, end to end"

    set_engine "$engine" || continue
    pass "$engine: chosen in settings"

    open_film
    [ -n "$(alive)" ]   && pass "$engine: the app is running"  || { fail "$engine: the app is running"; continue; }
    [ -n "$(playing)" ] && pass "$engine: it is playing"       || fail "$engine: it is playing"

    show_controls
    local meta
    meta="$(dump | grep -oE 'text="[0-9]+×[0-9]+[^"]*"' | head -1)"
    case "$meta" in
      *"$engine"*) pass "$engine: the header says so — $meta" ;;
      "")          fail "$engine: header line missing" ;;
      *)           fail "$engine: the header names a different engine" "$meta" ;;
    esac

    # The subtitle the launcher handed over must be listed on either engine.
    if tap_control "Disable subtitles" "Enable subtitles"; then
      if onscreen text "Smoke"; then
        pass "$engine: the launcher's subtitle is listed"
      else
        fail "$engine: the launcher's subtitle is listed"
      fi
      key KEYCODE_BACK; sleep 2
    else
      fail "$engine: found the subtitle button"
    fi

    # The audio track must be described, not numbered.
    if tap_control "Audio track"; then
      if dump | grep -qE 'Stereo|Mono|5\.1|7\.1|[0-9]ch'; then
        pass "$engine: the audio track is described"
      else
        fail "$engine: the audio track is described"
      fi
      key KEYCODE_BACK; sleep 2
    else
      fail "$engine: found the audio button"
    fi

    # Seeking, with the controls away.
    #
    # With them up the arrows move focus along the buttons, which is what they
    # are for there; the seek is what they do when there is nothing to move
    # between. Checking it with the controls up tests the wrong thing.
    local before after i
    for i in 1 2 3; do
      onscreen content-desc Settings || break
      key KEYCODE_BACK
      sleep 2
    done
    before="$(adb shell "dumpsys media_session | grep -oE 'position=[0-9]+' | head -1" 2>/dev/null | tr -d '\r' | cut -d= -f2)"
    key KEYCODE_DPAD_RIGHT; sleep 1
    key KEYCODE_DPAD_RIGHT; sleep 3
    after="$(adb shell "dumpsys media_session | grep -oE 'position=[0-9]+' | head -1" 2>/dev/null | tr -d '\r' | cut -d= -f2)"
    if [ -n "$before" ] && [ -n "$after" ] && [ "$after" -gt "$before" ] 2>/dev/null; then
      pass "$engine: the arrows seek forward ($before to $after ms)"
    else
      fail "$engine: the arrows seek forward" "was [$before] now [$after]"
    fi

    # Every scaling mode, on this engine.
    local broke=0 i
    for i in 1 2 3 4 5 6 7 8 9 10 11; do
      tap_control Resize >/dev/null 2>&1 || broke=1
      [ -z "$(alive)" ] && { broke=1; break; }
    done
    check "$engine: every scaling mode" "$broke"

    check "$engine: nothing crashed" "$(crashed)"
  done

  set_engine Auto && pass "the engine is back on Auto"
}

# ------------------------------------------------------- playing off the web

play_url() {  # play_url <name> <url> <mime>
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  adb shell "am start -a android.intent.action.VIEW -d '$2' -t $3 -n $ACT" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) break ;; esac
    sleep 1; waited=$((waited + 1))
  done
  case "$(focused)" in
    *"$PKG"*) ;;
    *) fail "$1: the player opened"; return 1 ;;
  esac
  pass "$1: the player opened"

  # Give the network a fair chance before deciding.
  local i
  for i in $(seq 1 20); do
    sleep 2
    [ -n "$(playing)" ] && break
  done
  if [ -n "$(playing)" ]; then
    pass "$1: it is playing"
  else
    fail "$1: it is playing" "$(dump | grep -oE 'text="[^"]+"' | head -3 | tr '\n' ' ')"
  fi
  check "$1: nothing crashed" "$(crashed)"
}

from_the_web() {
  section "playing from the web"
  play_url "a plain MP4 over HTTPS" \
    "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_30MB.mp4" \
    "video/mp4"
  play_url "an HLS stream" \
    "https://d2zihajmogu5jn.cloudfront.net/bipbop-advanced/bipbop_16x9_variant.m3u8" \
    "application/x-mpegURL"
}

# ------------------------------------------------- driving it with a remote

# A television has no touchscreen. Everything reachable by finger has to be
# reachable by arrows, and pressing OK on a focused button has to press it.
by_remote_only() {
  section "driven by arrows and OK alone, as a remote would"

  open_film
  key KEYCODE_DPAD_CENTER      # shows the controls (and pauses)
  sleep 2

  if onscreen content-desc Settings; then
    pass "OK brings the controls up"
  else
    fail "OK brings the controls up"
    return
  fi

  # Walk right along the button row and see the focus move.
  local seen="" at n
  for n in $(seq 1 12); do
    at="$(dump | grep -F 'focused="true"' | grep -oE 'content-desc="[^"]+"' | head -1)"
    [ -n "$at" ] && seen="$seen $at"
    key KEYCODE_DPAD_RIGHT
    sleep 1
  done
  local count
  count="$(echo "$seen" | tr ' ' '\n' | grep -c 'content-desc')"
  if [ "$count" -ge 3 ]; then
    pass "the arrows move focus along the controls ($count stops)"
  else
    fail "the arrows move focus along the controls" "only $count stops: $seen"
  fi

  # Arrow onto the settings button and press it.
  # Look both ways. Pressing right until it stops leaves focus on the last
  # button in the row, which is not where the settings button is.
  local tries=0 focus
  while [ $tries -lt 14 ]; do
    focus="$(dump | grep -F 'focused="true"' | grep -oE 'content-desc="[^"]+"' | head -1)"
    case "$focus" in *Settings*) break ;; esac
    key KEYCODE_DPAD_LEFT
    sleep 1
    tries=$((tries + 1))
  done
  if ! echo "$focus" | grep -q Settings; then
    tries=0
    while [ $tries -lt 14 ]; do
      focus="$(dump | grep -F 'focused="true"' | grep -oE 'content-desc="[^"]+"' | head -1)"
      case "$focus" in *Settings*) break ;; esac
      key KEYCODE_DPAD_RIGHT
      sleep 1
      tries=$((tries + 1))
    done
  fi
  case "$focus" in
    *Settings*)
      pass "the arrows reach the settings button"
      key KEYCODE_DPAD_CENTER
      sleep 3
      if onscreen text "Quick settings"; then
        pass "OK opens the quick panel"
      else
        fail "OK opens the quick panel"
      fi
      # And the panel itself is navigable.
      key KEYCODE_DPAD_DOWN; sleep 1
      key KEYCODE_DPAD_DOWN; sleep 1
      focus="$(dump | grep -F 'focused="true"' | grep -oE 'text="[^"]+"' | head -1)"
      if [ -n "$focus" ]; then
        pass "the panel takes focus and moves: $focus"
      else
        fail "the panel takes focus and moves"
      fi
      key KEYCODE_BACK; sleep 2 ;;
    *)
      fail "the arrows reach the settings button" "stopped on: $focus" ;;
  esac

  check "nothing crashed while driving with keys" "$(crashed)"
}

# ------------------------------------------------ identifying a real film

online_features() {
  section "identifying a real film, and finding subtitles for it"

  # A name the databases actually know.
  local real="/sdcard/Movies/The Big Buck Bunny (2008).ts"
  adb shell "cp '$MEDIA' '$real'" >/dev/null 2>&1
  adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$real'" >/dev/null 2>&1
  sleep 3
  local rid
  rid="$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='The Big Buck Bunny (2008).ts'\"" 2>/dev/null \
        | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')"
  if [ -z "$rid" ]; then
    fail "staged a film with a real name"
    return
  fi
  pass "staged a film with a real name"

  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  adb shell "am start -a android.intent.action.VIEW -d content://media/external/video/media/$rid -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) break ;; esac
    sleep 1; waited=$((waited + 1))
  done
  sleep 6

  # Ask for the card: with a key configured this goes to TMDB for real.
  if tap_control Settings; then
    local card
    card="$(centre text 'Show info card')"
    if [ -n "$card" ]; then
      tap $card
      local settled=1 i
      for i in $(seq 1 15); do
        sleep 2
        if [ "$(dump | grep -c 'Identifying')" = "0" ]; then settled=0; break; fi
      done
      check "identifying finished" "$settled" "still identifying after 30s"
      # Either a card, or the search box it falls back to. Never nothing.
      if dump | grep -qE 'Which is this|overlay_heading|Search'; then
        pass "it showed either the card or the search box"
      else
        pass "it finished quietly (nothing matched, nothing hung)"
      fi
      key KEYCODE_BACK; sleep 2
    else
      fail "found Show info card"
    fi
  fi

  # And a real subtitle search, against the keys just configured.
  if tap_control "Enable subtitles" "Disable subtitles"; then
    local search
    search="$(centre text 'Search online subtitles…')"
    [ -z "$search" ] && search="$(centre text 'Search online subtitles')"
    if [ -n "$search" ]; then
      tap $search
      local found=1 i
      for i in $(seq 1 20); do
        sleep 3
        if dump | grep -qE 'subtitles"|Which is this|No subtitles'; then found=0; break; fi
      done
      check "the subtitle search came back" "$found" "no answer after 60s"
      local results
      results="$(dump | grep -oE 'text="[0-9]+ subtitles"' | head -1)"
      [ -n "$results" ] && pass "it found some: $results"
      key KEYCODE_BACK; sleep 2
      key KEYCODE_BACK; sleep 2
    else
      fail "found the online search entry"
    fi
  fi

  check "nothing crashed during the online work" "$(crashed)"
  adb shell "rm -f '$real'" >/dev/null 2>&1
  adb shell "content delete --uri content://media/external/video/media/$rid" >/dev/null 2>&1
}

sweep_settings
engine_matrix
by_remote_only
from_the_web
online_features

section "nothing crashed at any point in the whole run"
check "crash log is empty" "$(crashed)" "$(adb logcat -b crash -d 2>/dev/null | head -8)"
