#!/bin/bash
# Screens for the README and the promo video, taken on the debug build in its
# screenshot mode (Showcase.java): only the demo folders on Home, no phone bars.
# Films: Blender open movies in .smoke-media/showcase (CC BY, Blender Foundation).
# Usage: tools/_showcase.sh <setup|recent|home|player|extras|tv-on|tv-off|undo>

STEP="${1:-}"
[ $# -gt 0 ] && shift
ARGS=("$@")
export PKG="${PKG:-app.justplayerpro.android.debug}"
set --
. "$(dirname "$0")/lib.sh"
. "$(dirname "$0")/_v42lib.sh"

ROOT="/sdcard/JPP-Showcase"
FILMS="$WORK/showcase/download"
SHOTS="$WORK/showcase/shots"
mkdir -p "$SHOTS"

place() {
  case "$1" in
    Coffee.Run*|Hero*|Agent.327*|Charge*|Wing.It*) echo "$ROOT/Shorts" ;;
    Caminandes*) echo "$ROOT/Caminandes" ;;
    *) echo "$ROOT/Movies" ;;
  esac
}

uri_of() {   # uri_of <file name>: its media store address
  local id
  id="$(adb shell "content query --uri content://media/external/video/media --projection _id --where \"_display_name='$1'\"" 2>/dev/null \
    | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')"
  [ -n "$id" ] && echo "content://media/external/video/media/$id"
}

shot() {   # shot <name>
  adb exec-out screencap -p > "$SHOTS/$1.png"
  echo "  shot $1"
}

setup() {
  adb shell "mkdir -p $ROOT/Movies $ROOT/Shorts $ROOT/Caminandes" >/dev/null 2>&1
  local f name dest have want
  for f in "$FILMS"/*; do
    name="$(basename "$f")"
    [ "$name" = "Sintel.2010.1080p.mkv" ] && f="$WORK/showcase/Sintel.2010.1080p.chaptered.mkv"
    dest="$(place "$name")/$name"
    want="$(stat -c %s "$f")"
    have="$(adb shell "stat -c %s '$dest'" 2>/dev/null | tr -d '\r')"
    if [ "$have" != "$want" ]; then
      adb push "$(hostpath "$f")" "$dest" >/dev/null 2>&1 && echo "  pushed $name" || echo "  FAILED to push $name"
    fi
    adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$dest'" >/dev/null 2>&1
  done
  sleep 5
  for f in "$FILMS"/*; do
    name="$(basename "$f")"
    [ -n "$(uri_of "$name")" ] || echo "  not indexed yet: $name"
  done

  adb shell "am force-stop $PKG"
  setpref string debugShowcaseFolders "/storage/emulated/0/JPP-Showcase/"
  setpref boolean debugShowcaseHideBars true
  setpref boolean debugShowcaseTv false
  setpref boolean introSeen true
  setpref string startOn home
  setpref boolean askResume false
  setpref boolean recentPosters true
  setpref int recentCount 10
  setpref string homeLayout list
  setpref string playerStyle classic
  setpref string clockMode off
  setpref string batteryMode off
  setpref boolean overlayOnPause true
  setpref boolean skipSegments true
  setpref string playbackEngine auto
  setpref string appTheme dark
  local key
  key="$(grep -E '^TMDB_API_KEY' "$HERE/../.env" | head -1 | sed -E 's/^TMDB_API_KEY\s*[=:]\s*//' | tr -d '\r" ')"
  setpref string apiKeyTmdb "$key" >/dev/null 2>&1
  key="$(grep -E '^MDBLIST_API_KEY' "$HERE/../.env" | head -1 | sed -E 's/^MDBLIST_API_KEY\s*[=:]\s*//' | tr -d '\r" ')"
  setpref string apiKeyMdblist "$key" >/dev/null 2>&1
  setpref boolean mdblistEnabled true
  adb shell "run-as $PKG sed -i -e '/name=\"urlHistory\"/d' -e '/name=\"trackMemory\"/d' $PREFS_FILE"
  adb shell settings get system font_scale | tr -d '\r' > "$WORK/showcase/font_scale.txt"
  adb shell settings put system font_scale 1.0
  echo "set up"
}

recent() {
  local entry name share uri n server_pid
  node "$(hostpath "$HERE/faultserver.js")" 8090 >/dev/null 2>&1 &
  server_pid=$!
  sleep 2
  adb reverse tcp:8090 tcp:8090 >/dev/null 2>&1
  for entry in Wing.It.2023.mp4:850 Charge.2022.mp4:300 Agent.327.Operation.Barbershop.2017.mp4:700       Coffee.Run.2020.mp4:500 Sprite.Fright.2024.mp4:120 Cosmos.Laundromat.2015.mp4:350       Tears.of.Steel.2012.1080p.mov:800 Spring.2019.mp4:220 Big.Buck.Bunny.2008.1080p.mp4:630       Sintel.2010.1080p.mkv:410; do
    name="${entry%%:*}"; share="${entry##*:}"
    uri="$SERVER/showcase/download/$name"
    adb shell "am force-stop $PKG" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    CURRENT_SCREEN="$ACT"
    adb shell "am start -a android.intent.action.VIEW -d '$uri' -t 'video/*' -n $ACT" >/dev/null 2>&1
    for n in $(seq 1 20); do adb logcat -d 2>/dev/null | grep -q "First frame on" && break; sleep 1; done
    sleep 45
    show_controls
    tap_timebar_at "$share"
    sleep 3
    pause_player
    sleep 1
    adb shell input keyevent KEYCODE_BACK; sleep 1
    adb shell input keyevent KEYCODE_BACK; sleep 2
    echo "  played $name"
  done
  adb shell "am force-stop $PKG"
  kill $server_pid 2>/dev/null
  adb reverse --remove tcp:8090 >/dev/null 2>&1
}

play_link() {
  local n server_pid
  node "$(hostpath "$HERE/faultserver.js")" 8090 >/dev/null 2>&1 &
  server_pid=$!
  sleep 2
  adb reverse tcp:8090 tcp:8090 >/dev/null 2>&1
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$SERVER/showcase/download/$1' -t 'video/*' -n $ACT" >/dev/null 2>&1
  for n in $(seq 1 20); do adb logcat -d 2>/dev/null | grep -q "First frame on" && break; sleep 1; done
  sleep "$2"
  pause_player
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input keyevent KEYCODE_BACK; sleep 2
  adb shell "am force-stop $PKG"
  kill $server_pid 2>/dev/null
  adb reverse --remove tcp:8090 >/dev/null 2>&1
  echo "  played $1"
}

home_now() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$HOME_ACT"
  adb shell "am start -n $HOME_ACT" >/dev/null 2>&1
  sleep 5
}

row_of() {   # row_of <text>: the centre of the first line showing it
  centre text "$1"
}

home() {
  setpref string homeLayout list
  home_now
  shot home-list
  setpref string appTheme light
  home_now
  shot home-list-light
  setpref string appTheme dark
  setpref string homeLayout wall
  home_now
  shot home-wall
  setpref string homeLayout list
  home_now
  local at
  at="$(row_of 'Sintel (2010)')"
  if [ -n "$at" ]; then
    set -- $at
    adb shell input swipe "$1" "$2" "$1" "$2" 900
    sleep 2
    shot recent-menu
    at="$(centre resource-id "$PKG:id/recent_menu_info")"
    if [ -n "$at" ]; then
      tap $at; sleep 4
      shot film-info
      set -- $(screen_now)
      swipe $(( $1 / 2 )) $(( $2 * 80 / 100 )) $(( $1 / 2 )) $(( $2 * 20 / 100 )) 600; sleep 1
      at="$(centre resource-id "$PKG:id/film_change")"
      [ -n "$at" ] && { tap $at; sleep 5; shot film-change; }
    fi
  fi
  home_now
  set -- $(screen_now)
  swipe $(( $1 / 2 )) $(( $2 * 80 / 100 )) $(( $1 / 2 )) $(( $2 * 20 / 100 )) 600; sleep 1
  swipe $(( $1 / 2 )) $(( $2 * 80 / 100 )) $(( $1 / 2 )) $(( $2 * 20 / 100 )) 600; sleep 1
  at="$(row_of 'Movies')"
  [ -n "$at" ] && { tap $at; sleep 3; shot folder-movies; }
  home_now
  at="$(centre content-desc 'Search')"
  [ -n "$at" ] && { tap $at; sleep 1; adb shell input text "in"; sleep 1; adb shell input keyevent KEYCODE_ENTER; sleep 3; shot home-search; }
  home_now
  at="$(centre content-desc 'Sort')"
  [ -n "$at" ] && { tap $at; sleep 2; shot home-sort; }
  adb shell "am force-stop $PKG"
}

film() {   # film <file> <ms>: opened there, a moment played, then paused
  local uri n
  uri="$(uri_of "$1")"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$uri' -t 'video/*' -n $ACT --ei position $2" >/dev/null 2>&1
  for n in $(seq 1 25); do
    adb logcat -d 2>/dev/null | grep -q "First frame on" && break
    sleep 1
  done
  sleep 2
  pause_player
  sleep 2
}

player() {
  local style at
  setpref boolean overlayOnPause false
  setpref string playbackEngine media3
  for style in classic glass material; do
    setpref string playerStyle "$style"
    film Sintel.2010.1080p.mkv 319000
    show_controls; sleep 1
    shot "player-$style"
  done
  setpref string playerStyle classic
  setpref string playbackEngine mpv
  film Sintel.2010.1080p.mkv 319000
  show_controls; sleep 1
  shot player-mpv
  setpref string playbackEngine media3

  setpref boolean overlayOnPause true
  film Sintel.2010.1080p.mkv 295000
  sleep 5
  shot player-card
  setpref boolean overlayOnPause false

  film Sintel.2010.1080p.mkv 319000
  tap_control "Settings" >/dev/null; sleep 2
  shot player-quick
  key KEYCODE_BACK; sleep 1
  tap_control "Subtitles" "Enable subtitles" "Disable subtitles" >/dev/null; sleep 2
  shot player-subs
  at="$(centre text 'Search online…')"
  [ -z "$at" ] && at="$(centre text 'Search online...')"
  [ -n "$at" ] && { tap $at; sleep 12; shot player-subsearch; key KEYCODE_BACK; sleep 1; }
  key KEYCODE_BACK; sleep 1
  tap_control "Audio track" >/dev/null; sleep 2
  shot player-audio
  key KEYCODE_BACK; sleep 1

  setpref boolean skipSegments true
  local uri
  uri="$(uri_of Sintel.2010.1080p.mkv)"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$uri' -t 'video/*' -n $ACT --ei position 14000" >/dev/null 2>&1
  for at in $(seq 1 15); do
    dump | grep -q 'text="Skip intro"' && break
    sleep 1
  done
  shot player-skip
  adb shell "am force-stop $PKG"
}

langs() {
  local tag
  for tag in en es ja ar hi de fr zh-CN ru pt-BR; do
    if [ "$tag" = en ]; then
      adb shell "cmd locale set-app-locales $PKG --locales ''" >/dev/null 2>&1
    else
      adb shell "cmd locale set-app-locales $PKG --locales $tag" >/dev/null 2>&1
    fi
    adb shell "am force-stop $PKG" >/dev/null 2>&1
    sleep 1
    CURRENT_SCREEN="$SETTINGS"
    adb shell "am start -n $SETTINGS" >/dev/null 2>&1
    sleep 4
    shot "lang-$tag"
  done
  adb shell "cmd locale set-app-locales $PKG --locales ''" >/dev/null 2>&1
  adb shell "am force-stop $PKG"
}

record() {
  local name="$1" secs="$2"
  shift 2
  adb shell "rm -f /sdcard/jpp-clip.mp4" >/dev/null 2>&1
  adb shell "screenrecord --bit-rate 16000000 --time-limit $secs /sdcard/jpp-clip.mp4" >/dev/null 2>&1 &
  local rec=$!
  sleep 1
  "$@"
  wait $rec
  sleep 1
  adb pull /sdcard/jpp-clip.mp4 "$(hostpath "$SHOTS/clip-$name.mp4")" >/dev/null 2>&1 && echo "  clip $name"
  adb shell "rm -f /sdcard/jpp-clip.mp4" >/dev/null 2>&1
}

walk_to() {   # walk_to <content-desc>
  local n
  for n in $(seq 1 20); do
    dump | grep 'focused="true"' | grep -q "content-desc=\"$1\"" && return 0
    key KEYCODE_DPAD_RIGHT
    sleep 0.6
  done
  return 1
}

tv() {
  local at
  tv_on
  setpref string homeLayout list
  home_now
  key KEYCODE_DPAD_DOWN; sleep 1
  shot tv-home-list
  setpref string homeLayout wall
  home_now
  key KEYCODE_DPAD_DOWN; sleep 1; key KEYCODE_DPAD_RIGHT; sleep 1
  shot tv-home-wall
  key KEYCODE_DPAD_LEFT; sleep 1
  adb shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 2
  shot tv-recent-menu
  at="$(centre resource-id "$PKG:id/recent_menu_info")"
  [ -n "$at" ] && { tap $at; sleep 4; key KEYCODE_DPAD_DOWN; sleep 1; shot tv-film-info; }
  setpref string homeLayout list

  CURRENT_SCREEN="$SETTINGS"
  adb shell "am force-stop $PKG"; adb shell "am start -n $SETTINGS" >/dev/null 2>&1; sleep 4
  key KEYCODE_DPAD_DOWN; sleep 1
  shot tv-settings
  key KEYCODE_DPAD_CENTER; sleep 2; key KEYCODE_DPAD_RIGHT; sleep 1
  shot tv-settings-appearance

  setpref boolean overlayOnPause false
  film Sintel.2010.1080p.mkv 319000
  key KEYCODE_DPAD_UP; sleep 1
  walk_to "Subtitles" || walk_to "Enable subtitles" || walk_to "Disable subtitles"
  shot tv-player
  walk_to "Settings" && { key KEYCODE_DPAD_CENTER; sleep 2; shot tv-player-quick; }
  setpref boolean overlayOnPause true
  film Sintel.2010.1080p.mkv 295000
  sleep 5
  shot tv-player-card
  adb shell "am force-stop $PKG"

  setpref boolean introSeen false
  home_now
  sleep 2; key KEYCODE_DPAD_DOWN; sleep 1
  shot tv-intro-1
  adb shell "am force-stop $PKG"
  setpref boolean introSeen true
  tv_off
}

setup_page() {
  local at pin mode="${1:-phone}"
  [ "$mode" = tv ] && tv_on
  home_now
  CURRENT_SCREEN="$SETTINGS"
  adb shell "am start -n $SETTINGS" >/dev/null 2>&1; sleep 6
  at="$(centre text 'Online info')"; [ -n "$at" ] && { tap $at; sleep 2; }
  for n in 1 2 3 4 5 6; do
    at="$(centre text 'Set up from your phone')"
    [ -n "$at" ] && break
    set -- $(screen_now); swipe $(( $1 * 3 / 4 )) $(( $2 * 75 / 100 )) $(( $1 * 3 / 4 )) $(( $2 * 35 / 100 )) 600; sleep 1
  done
  [ -z "$at" ] && { echo "  no setup row"; return 1; }
  tap $at; sleep 3
  shot "$mode-setup"
  pin="$(dump | grep -oE 'PIN[^"]*[0-9]{6}' | grep -oE '[0-9]{6}' | tail -1)"
  echo "  PIN found: ${pin:+yes}"
  adb forward tcp:8723 tcp:8723 >/dev/null 2>&1
  (cd "$WORK/showcase/video" && node setup-page.mjs "http://localhost:8723/" "$pin" "$(hostpath "$SHOTS")") 2>&1 | head -3
  adb forward --remove tcp:8723 >/dev/null 2>&1
  [ "$mode" = tv ] && tv_off
}

clips() {
  CURRENT_SCREEN="$SETTINGS"
  adb shell "am force-stop $PKG"; adb shell "am start -n $SETTINGS" >/dev/null 2>&1; sleep 4
  type_search() {
    local at c
    at="$(centre content-desc 'Search settings')"
    [ -n "$at" ] && adb shell input tap $at
    sleep 1.2
    for c in s u b t i t l e; do adb shell input text "$c"; sleep 0.25; done
    sleep 3
  }
  record settings-search 9 type_search

  home_now
  CURRENT_SCREEN="$SETTINGS"
  adb shell "am start -n $SETTINGS" >/dev/null 2>&1; sleep 4
  tap $(centre text "Appearance"); sleep 2
  tap $(centre text "Buttons and their order"); sleep 3
  move_open() {
    local b n
    sleep 1
    for n in 1 2 3; do
      b="$(dump | grep -m1 'id/row_down"' | grep -oE '[[0-9]+,[0-9]+][[0-9]+,[0-9]+]' | grep -oE '[0-9]+' | tr '
' ' ')"
      [ -z "$b" ] && break
      set -- $b
      adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
      sleep 0.4
    done
    sleep 1
  }
  record reorder 9 move_open
  adb exec-out screencap -p > "$SHOTS/reorder-after.png"
}

panel_scroll() {
  local list
  list="$(dump | grep -E 'class="android(x.recyclerview.widget.RecyclerView|.widget.ListView)"' | tail -1     | grep -oE 'bounds="[[0-9]+,[0-9]+][[0-9]+,[0-9]+]"' | grep -oE '[0-9]+' | tr '
' ' ')"
  [ -z "$list" ] && return 1
  set -- $list
  adb shell input swipe $((($1 + $3) / 2)) $(($2 + ($4 - $2) * 80 / 100)) $((($1 + $3) / 2)) $(($2 + ($4 - $2) * 20 / 100)) 500
  sleep 1
}

player_panels() {
  local at n
  setpref boolean overlayOnPause false
  setpref string playbackEngine media3
  setpref string playerStyle classic
  film Sintel.2010.1080p.mkv 319000
  tap_control "Settings" >/dev/null; sleep 2
  shot player-quick
  key KEYCODE_BACK; sleep 1
  tap_control "Subtitles" "Enable subtitles" "Disable subtitles" >/dev/null; sleep 2
  for n in 1 2 3 4; do
    at="$(centre text 'Search online subtitles…')"
    [ -n "$at" ] && break
    panel_scroll || break
  done
  if [ -n "$at" ]; then
    tap $at; sleep 3
    at="$(centre resource-id android:id/button1)"
    [ -n "$at" ] && { tap $at; sleep 3; }
    shot player-subsearch-asks
    sleep 8
    shot player-identify-grid
    at="$(dump | grep -F 'text="Sintel"' | grep -v EditText | head -1 | grep -oE '[[0-9]+,[0-9]+][[0-9]+,[0-9]+]' | grep -oE '[0-9]+' | tr '
' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2) - 150}')"
    [ -n "$at" ] && { tap $at; sleep 14; }
    shot player-subsearch
  else
    echo "  no search row"
  fi
  adb shell "am force-stop $PKG"
}

extras() {   # theme colours, subtitle colour, ratings order, and clips of the card and a colour change
  local at accent n px
  open_settings; sleep 1
  at="$(centre text "Appearance")"; tap $at; sleep 2
  at="$(centre text "Theme colour")"; tap $at; sleep 2
  shot settings-accent-dialog
  key KEYCODE_BACK; sleep 1
  accentclip
  for accent in blue green violet pink orange; do
    setpref string accentColor "$accent"
    home_now
    shot "home-accent-$accent"
  done

  open_settings; sleep 1
  at="$(centre text "Online info")"; tap $at; sleep 2
  scroll_to "Ratings and their order" >/dev/null 2>&1
  at="$(centre text "Ratings and their order")"; [ -n "$at" ] && { tap $at; sleep 2; shot ratings-order; }

  subtitles
  cardclip
}

subtitles() {   # yellow subtitles, then the panel open on Text colour; 2:02 has a line
  local at n px
  setpref boolean overlayOnPause false
  setpref string subtitleColor yellow
  film Sintel.2010.1080p.mkv "${SUB_AT:-120500}"
  show_controls; sleep 1
  shot player-subyellow
  at="$(find_control "Subtitles" "Enable subtitles" "Disable subtitles")"
  [ -n "$at" ] && adb shell "input swipe $at $at 900" >/dev/null 2>&1
  sleep 2
  px=""
  for n in 1 2 3 4 5; do px="$(centre text "Position" | awk '{print $1}')"; [ -n "$px" ] && break; sleep 1; done
  for n in 1 2 3 4 5; do
    [ -z "$px" ] && break
    onscreen text "Text colour" && break
    set -- $(screen_now); swipe "$px" $(( $2 * 75 / 100 )) "$px" $(( $2 * 35 / 100 )) 500; sleep 1
  done
  shot player-subcolour
  setpref string subtitleColor white
  setpref boolean overlayOnPause true
}

cardclip() {   # recorded once the film plays in landscape, or the clip comes out portrait
  local uri
  setpref boolean overlayOnPause true
  uri="$(uri_of Sintel.2010.1080p.mkv)"
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$uri' -t 'video/*' -n $ACT --ei position 288000" >/dev/null 2>&1
  sleep 7
  record card 12 card_moment
  adb shell "am force-stop $PKG"
}

accentclip() {   # the picker opened first, so the clip holds only the app, never the launcher
  local at
  setpref string accentColor orange
  open_settings; sleep 1
  at="$(centre text "Appearance")"; tap $at; sleep 1.5
  at="$(centre text "Theme colour")"; tap $at; sleep 2
  ACCENT_AT="$(centre text "Blue")"
  record accent 5 pick_accent
}

pick_accent() {   # for the clip: a colour tapped in the open picker
  sleep 1.5
  [ -n "$ACCENT_AT" ] && tap $ACCENT_AT
  sleep 3
}

card_moment() {   # for the clip: a moment of play, then paused, and the info card coming up
  sleep 2
  key KEYCODE_MEDIA_PAUSE
  sleep 9
}

tv_settings() {
  tv_on
  home_now
  CURRENT_SCREEN="$SETTINGS"
  adb shell "am start -n $SETTINGS" >/dev/null 2>&1; sleep 8
  key KEYCODE_DPAD_DOWN; sleep 2
  shot tv-settings
  key KEYCODE_DPAD_DOWN; sleep 1; key KEYCODE_DPAD_DOWN; sleep 1
  key KEYCODE_DPAD_CENTER; sleep 2; key KEYCODE_DPAD_RIGHT; sleep 2
  shot tv-settings-playback
  tv_off
}

tv_on() {
  setpref boolean debugShowcaseTv true
  adb shell wm size 1920x1080
  adb shell wm density 320
  sleep 3
}

tv_off() {
  setpref boolean debugShowcaseTv false
  adb shell wm size reset
  density_reset
}

undo() {
  adb shell "am force-stop $PKG"
  adb shell "run-as $PKG sed -i -e '/name=\"debugShowcase/d' $PREFS_FILE"
  local scale
  scale="$(cat "$WORK/showcase/font_scale.txt" 2>/dev/null)"
  if [ -z "$scale" ] || [ "$scale" = "null" ]; then
    adb shell settings delete system font_scale
  else
    adb shell settings put system font_scale "$scale"
  fi
  echo "screenshot mode off, font scale $scale"
}

case "$STEP" in
  setup) setup ;;
  recent) recent ;;
  home) home ;;
  player) player ;;
  langs) langs ;;
  tv) tv ;;
  clips) clips ;;
  tv-on) tv_on ;;
  tv-off) tv_off ;;
  undo) undo ;;
  *) if [ -n "$STEP" ] && declare -F "$STEP" >/dev/null; then "$STEP" "${ARGS[@]}"; else echo "usage: $0 <step> (setup, recent, home, player, langs, tv-on, tv-off, undo, or any function)"; exit 1; fi ;;
esac
