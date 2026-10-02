#!/bin/bash
# _v42regress.sh <engine> <script...>: runs device scripts on one engine,
# one log each, and a summary line per script.
export PATH="$PATH:$HOME/AppData/Local/Android/Sdk/platform-tools"
export PKG=app.justplayerpro.android.debug
TOOLS="/c/Users/SC/Downloads/Just Player Pro/tools"
OUT="/c/Users/SC/Downloads/Just Player Pro/.smoke-media/regress"
mkdir -p "$OUT"
ENGINE="$1"; shift
export ENGINE
cd "$TOOLS" || exit 1
for s in "$@"; do
  adb shell am force-stop app.justplayerpro.android >/dev/null 2>&1
  adb shell am force-stop $PKG >/dev/null 2>&1
  bash -c '. ./lib.sh >/dev/null 2>&1; . ./_v42lib.sh; setpref string playbackEngine '"$ENGINE" >/dev/null 2>&1
  adb logcat -b crash -c >/dev/null 2>&1
  log="$OUT/$ENGINE-$s.log"
  start=$(date +%s)
  timeout 600 bash "./$s.sh" > "$log" 2>&1
  code=$?
  p=$(grep -c "^PASS" "$log"); f=$(grep -c "^FAIL" "$log")
  crash=$(adb logcat -b crash -d 2>/dev/null | grep -c "FATAL\|AndroidRuntime")
  echo "$ENGINE $s pass=$p fail=$f exit=$code crash=$crash $(( $(date +%s) - start ))s"
done
