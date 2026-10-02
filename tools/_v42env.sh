#!/bin/bash
# Puts the debug app in a tester's install state: .env service keys, first-run
# intro and pointers seen, permissions granted. Prints names only, never values.
export PATH="$PATH:$HOME/AppData/Local/Android/Sdk/platform-tools"
export PKG=app.justplayerpro.android.debug
ROOT="/c/Users/SC/Downloads/Just Player Pro"
cd "$ROOT/tools" || exit 1
. ./lib.sh >/dev/null 2>&1
# lib.sh derives WORK from $0, which is wrong after a cd from a relative path
WORK="$ROOT/.smoke-media"
V42_SCRATCH="$WORK"
. ./_v42lib.sh

value() { grep -E "^$1[:=]" "$ROOT/.env" | head -1 | sed -E "s/^$1[:=]//" | tr -d '\r'; }
stored() { prefs_read | grep -q "name=\"$1\"" && echo "  set: $1" || echo "  NOT STORED: $1"; }
put() {
  local pref="$1" envname="$2" v
  v="$(value "$envname")"
  if [ -n "$v" ]; then setpref string "$pref" "$v"; stored "$pref"; else echo "  MISSING in .env: $envname"; fi
}
put apiKeyTmdb TMDB_API_KEY
put apiKeyWyzie WYZIE_API_KEY
put apiKeyOpenSubtitles OPENSUBTITLES_API_KEY
put apiKeyOpenSubtitlesUser OPENSUBTITLES_USERNAME
put apiKeyOpenSubtitlesPassword OPENSUBTITLES_PASSWORD
put apiKeySubdl SUBDL_API_KEY
setpref boolean introSeen true
stored introSeen
setpref boolean firstRun false
stored firstRun

for p in android.permission.READ_MEDIA_VIDEO android.permission.READ_MEDIA_AUDIO \
         android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant $PKG $p >/dev/null 2>&1 && echo "  granted: ${p##*.}" || echo "  not grantable: ${p##*.}"
done
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
echo "  all-files access: $(adb shell appops get $PKG MANAGE_EXTERNAL_STORAGE | tr -d '\r' | cut -d';' -f1)"
echo "  stored key names: $(prefs_read | grep -oE 'name="apiKey[A-Za-z]*"' | tr '\n' ' ')"
