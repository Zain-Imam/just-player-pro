#!/bin/bash
#
# The parts smoke.sh and verify.sh do not reach.
#
# Five things, each of which can only be answered by using the app:
#
#   * the decoder extensions — the ffmpeg, AV1, IAMF and MPEG-H aars in
#     app/libs are built separately from the Media3 they run against, which is
#     exactly the mismatch that broke HLS, and nothing else here plays a file
#     that needs one of them;
#   * the track pickers under a remote, since the focus bugs that made the
#     quick panel unusable were the same in those and were fixed by analogy;
#   * picture-in-picture;
#   * leaving and coming back;
#   * and landscape, which is a different layout pass from portrait.
#
# Usage:  tools/deeper.sh [package]
set -u
HERE_SCRIPT="$(cd "$(dirname "$0")" && pwd)"
. "$HERE_SCRIPT/lib.sh"
trap cleanup EXIT

SCREEN_W="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f1)"
SCREEN_H="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1 | cut -dx -f2)"
MEDIA_DIR="$WORK/codecs"

section() { echo; echo "======== $*"; }
position_ms() { adb shell "dumpsys media_session | grep -oE 'position=[0-9]+' | head -1" 2>/dev/null | tr -d '\r' | cut -d= -f2; }
focused_row() {
  local node named
  node="$(dump | grep -F 'focused="true"' | head -1)"
  [ -z "$node" ] && return 0
  named="$(echo "$node" | grep -oE '(text|content-desc)="[^"]+"' | grep -v '=""' | head -1)"
  if [ -n "$named" ]; then echo "$named"; else echo "$node" | grep -oE 'bounds="[^"]+"' | head -1; fi
}

# ------------------------------------------------- the decoder extensions

# The samples, fetched once and kept.
#
# None of these are in the repository: they are tens of megabytes of somebody
# else's audio, and they only have to exist while this is running. They come
# from the MPlayer sample archive, which has hosted them for twenty years.
fetch_samples() {
  mkdir -p "$MEDIA_DIR"
  local base="https://samples.mplayerhq.hu/A-codecs"
  local name from
  while IFS='|' read -r name from; do
    [ -z "$name" ] && continue
    [ -s "$MEDIA_DIR/$name" ] && continue
    if ! curl -s --max-time 300 -o "$MEDIA_DIR/$name" "$from"; then
      echo "        (could not fetch $name; it will be reported as missing)"
      rm -f "$MEDIA_DIR/$name"
    fi
  done <<FETCH
ac3-5.1.ac3|$base/AC3/monsters_inc_5.1_448.ac3
eac3-mono.eac3|$base/AC3/eac3/casablanca_aht_mono_64.eac3
eac3-video.mp4|$base/AC3/eac3/channelcheck-ddplus_480.mp4
dts-5.1.dts|$base/DTS/lotr_5.1_768.dts
truehd.m2ts|$base/TrueHD/vc1-with-truehd.m2ts
sample.flac|https://filesamples.com/samples/audio/flac/sample1.flac
sample.opus|https://filesamples.com/samples/audio/opus/sample1.opus
FETCH
}

# Push one of the fetched files and open it, by whichever URI the system
# will give for it.
stage_and_open() {   # stage_and_open <local name> <mime>
  local name="$1" mime="$2" remote="/sdcard/Movies/$1"
  [ -s "$MEDIA_DIR/$name" ] || return 1
  adb push "$(hostpath "$MEDIA_DIR/$name")" "$remote" >/dev/null 2>&1 || return 1
  adb shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://$remote'" >/dev/null 2>&1
  sleep 3
  local id store uri=""
  for store in video audio; do
    id="$(adb shell "content query --uri content://media/external/$store/media --projection _id --where \"_display_name='$name'\"" 2>/dev/null \
          | grep -oE '_id=[0-9]+' | head -1 | cut -d= -f2 | tr -d '\r')"
    if [ -n "$id" ]; then uri="content://media/external/$store/media/$id"; break; fi
  done
  # A codec the scanner does not index at all still has a path.
  [ -z "$uri" ] && uri="file://$remote"

  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d '$uri' -t $mime -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) break ;; esac
    sleep 1; waited=$((waited + 1))
  done
  echo "$remote"
}

codec_matrix() {
  local engine="$1"
  section "the decoder extensions on $engine"
  fetch_samples

  local row name mime label
  while IFS='|' read -r name mime label <&3; do
    [ -z "$name" ] && continue
    local remote
    remote="$(stage_and_open "$name" "$mime")" \
      || { fail "$engine: $label is there to play" "the sample could not be fetched or pushed"; continue; }

    local ok=1 i
    for i in $(seq 1 10); do
      sleep 2
      [ -n "$(playing)" ] && { ok=0; break; }
    done
    if [ $ok -eq 0 ]; then
      pass "$engine: $label plays"
    else
      fail "$engine: $label plays" "$(dump | grep -oE 'text="[^"]+"' | head -4 | tr '\n' ' ')"
    fi
    check "$engine: $label did not crash" "$(crashed)"
    adb shell "rm -f '$remote'" >/dev/null 2>&1
  done 3<<'FILES'
ac3-5.1.ac3|audio/ac3|AC-3 5.1
eac3-video.mp4|video/mp4|E-AC-3 in MP4
eac3-mono.eac3|audio/eac3|E-AC-3 mono
dts-5.1.dts|audio/vnd.dts|DTS 5.1
truehd.m2ts|video/mp2t|TrueHD in M2TS
sample.flac|audio/flac|FLAC
sample.opus|audio/opus|Opus
FILES
}

# --------------------------------------------- the pickers, driven by keys

reach_by_keys() {   # reach_by_keys <name...> — walk the control row and press it
  local tries=0 focus=""
  while [ $tries -lt 16 ]; do
    focus="$(dump | grep -F 'focused="true"' | grep -oE 'content-desc="[^"]+"' | head -1)"
    for want in "$@"; do
      case "$focus" in *"$want"*) key KEYCODE_DPAD_CENTER; sleep 3; return 0 ;; esac
    done
    key KEYCODE_DPAD_RIGHT; sleep 1
    tries=$((tries + 1))
  done
  return 1
}

pickers_by_keys() {
  section "the track pickers, driven by arrows and OK alone"

  open_film

  local which
  for which in "subtitles:Disable subtitles:Enable subtitles" "audio:Audio track:Audio"; do
    local label="${which%%:*}" rest="${which#*:}"
    local a="${rest%%:*}" b="${rest##*:}"

    # show_controls, not a bare OK. With the controls already up, OK activates
    # whatever holds the focus — it was pressing play/pause and then hunting for
    # a button on a strip that had moved.
    show_controls
    if ! reach_by_keys "$a" "$b"; then
      fail "the arrows reach the $label button"
      continue
    fi
    pass "the arrows reach the $label button"

    local first second
    first="$(focused_row)"
    if [ -n "$first" ]; then
      pass "the $label picker takes the focus when it opens: $first"
    else
      fail "the $label picker takes the focus when it opens" \
           "nothing inside it is focused, so the arrows have nothing to move"
    fi
    key KEYCODE_DPAD_DOWN; sleep 1
    second="$(focused_row)"
    if [ -n "$second" ] && [ "$second" != "$first" ]; then
      pass "the arrows move down the $label picker: $first to $second"
    else
      fail "the arrows move down the $label picker" "was $first, is now ${second:-nothing}"
    fi
    key KEYCODE_BACK; sleep 2
  done

  check "nothing crashed driving the pickers" "$(crashed)"
}

# ------------------------------------------------ picture-in-picture

# What the activity manager actually calls it here. The first guess was
# mLastReportedPictureInPictureMode, which this Android does not report at all,
# so the check failed while the detail line it printed said, in plain words,
# mIsInPictureInPictureMode=true.
in_pip() { adb shell "dumpsys activity $PKG | grep -c 'mIsInPictureInPictureMode=true'" 2>/dev/null | tr -d '\r'; }

pip_check() {
  section "picture-in-picture"

  open_film
  if ! tap_control "Picture-in-picture"; then
    fail "found the picture-in-picture button"
    return
  fi
  sleep 4
  if [ "$(in_pip)" != "0" ]; then
    pass "it went into picture-in-picture"
  else
    fail "it went into picture-in-picture" "$(adb shell "dumpsys activity $PKG | grep -m1 PictureInPicture" | tr -d '\r')"
  fi
  [ -n "$(alive)" ] && pass "it is still running in picture-in-picture" \
                    || fail "it is still running in picture-in-picture"

  # Back to full screen the only way a test may: by starting this app's screen.
  adb shell "am start -n $ACT" >/dev/null 2>&1
  sleep 4
  [ -n "$(alive)" ] && pass "and it comes back out again" \
                    || fail "and it comes back out again"
  check "nothing crashed around picture-in-picture" "$(crashed)"
}

# ------------------------------------------------ resuming where it left off

# Open the film the way a file manager does: no extras at all.
#
# open_film hands over a sidecar subtitle, and that is not a neutral thing to
# do. A launcher that passes subtitles, a start position, or asks for a result
# is treated as owning the playback: apiAccess goes true, persistent mode goes
# off, and the position is kept in memory and handed back to the launcher
# rather than written down. That is deliberate — Stremio and the rest keep
# their own place — so a film opened that way is expected not to remember
# anything, and testing resume through it asks the player to break its own
# contract. It duly "failed", twice, doing exactly what it is supposed to.
open_film_plainly() {
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) sleep 4; return 0 ;; esac
    sleep 1; waited=$((waited + 1))
  done
  return 1
}

resume_check() {
  section "resuming where it left off"

  open_film_plainly || { fail "the film opened"; return; }
  sleep 8
  local before
  before="$(position_ms)"
  if [ -z "$before" ] || [ "$before" -lt 3000 ] 2>/dev/null; then
    fail "the film got far enough in to resume from" "position $before"
    return
  fi
  pass "played to $before ms"

  # Leave the way a person leaves.
  #
  # This used to force-stop the app, which does not deliver onStop, so nothing
  # was ever saved and the test was asking the player to remember something it
  # had never been told. Back is what someone actually presses, and it is the
  # lifecycle callback that writes the position down.
  #
  # (What a force-stop does show is real, and is written up as a limitation
  # rather than a failure: a player killed outright — by the system under
  # memory pressure, say — loses its place back to the last time it stopped.)
  key KEYCODE_BACK
  sleep 3
  adb shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  adb logcat -c >/dev/null 2>&1
  CURRENT_SCREEN="$ACT"
  adb shell "am start -a android.intent.action.VIEW -d $URI -t video/mp2t -n $ACT --grant-read-uri-permission" >/dev/null 2>&1
  local waited=0
  while [ $waited -lt 25 ]; do
    case "$(focused)" in *"$PKG"*) break ;; esac
    sleep 1; waited=$((waited + 1))
  done
  sleep 5

  # It either resumes, or asks whether to — both are the setting doing its job.
  local asked after
  asked="$(dump | grep -oE 'text="[^"]*(Resume|resume)[^"]*"' | head -1)"
  after="$(position_ms)"
  if [ -n "$asked" ]; then
    pass "it asked whether to resume: $asked"
    key KEYCODE_BACK; sleep 2
  elif [ -n "$after" ] && [ "$after" -ge $((before - 5000)) ] 2>/dev/null; then
    pass "it carried on from where it was ($before to $after ms)"
  else
    fail "it resumed, or asked to" "was $before, reopened at ${after:-nothing}"
  fi
  check "nothing crashed reopening" "$(crashed)"
}

# ------------------------------------------------------------- landscape

landscape_check() {
  section "landscape"

  open_film
  local was now
  was="$(dump | grep -oE 'rotation="[0-9]"' | head -1 | grep -oE '[0-9]')"
  tap_control Rotate >/dev/null 2>&1
  sleep 4
  now="$(dump | grep -oE 'rotation="[0-9]"' | head -1 | grep -oE '[0-9]')"
  if [ "$was" = "$now" ]; then
    echo "        (the screen did not turn; checking the layout as it is)"
  fi

  local w
  w="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1)"
  echo "        screen now: $w, rotation $now"

  # The panel must still reach the trailing edge and not run off it.
  if tap_control Settings; then
    if onscreen text "Quick settings"; then
      pass "the quick panel opens in landscape"
    else
      fail "the quick panel opens in landscape"
    fi
    # How wide the window is once it has turned.
    #
    # wm size reports the panel the way it is built into the phone, 1080x2400,
    # whichever way up it is being held — so comparing against it in landscape
    # said everything was drawn off the edge when nothing was. The root of the
    # view tree is the window, so it is asked instead.
    local right screen_w
    screen_w="$(dump | grep -oE 'bounds="\[0,0\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+,[0-9]+\]"$' | cut -d, -f1)"
    [ -z "$screen_w" ] && screen_w="$(adb shell wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x '\n' | sort -n | tail -1)"
    right="$(dump | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '\]\[[0-9]+' | grep -oE '[0-9]+' | sort -n | tail -1)"
    if [ -n "$right" ] && [ -n "$screen_w" ] && [ "$right" -le "$screen_w" ] 2>/dev/null; then
      pass "nothing is drawn off the edge in landscape (widest $right of $screen_w)"
    else
      fail "nothing is drawn off the edge in landscape" "widest $right of $screen_w"
    fi
    key KEYCODE_BACK; sleep 2
  else
    fail "the quick panel opens in landscape"
  fi

  [ -n "$(alive)" ] && pass "still running in landscape" || fail "still running in landscape"
  check "nothing crashed in landscape" "$(crashed)"

  # Put the orientation back.
  show_controls
  tap_control Rotate >/dev/null 2>&1
  sleep 3
}

prepare
pickers_by_keys
pip_check
resume_check
landscape_check
codec_matrix "Auto"
