# What was verified, and how

Run against the APKs in `release-apks/`, on a moto g54 5G (Android 15).
Everything below is something a command printed. Where something was not
covered, it says so at the end rather than being left out.

Re-run any of it:

```
./gradlew :app:testLatestUniversalDebugUnitTest          # layer 1, on this machine
./gradlew :app:connectedLatestUniversalDebugAndroidTest  # layer 2, on the device
tools/smoke.sh   app.justplayerpro.android               # layer 3, quick
tools/verify.sh  app.justplayerpro.android               # layer 4, the long one
tools/probe.sh   app.justplayerpro.android hls|mpvsubs|panel   # one question at a time
```

---

## The interlock, first

Layers 3 and 4 press fixed screen coordinates. If the player is not the thing
in front — it failed to start, it crashed, an install killed it — those presses
land on the launcher and open whatever is under them. **That happened, and it
opened private apps.** It is the reason for everything in this section.

Every press goes through `require_player`, which:

* checks `mFocusedApp` — the activity behind whatever has focus — is this app;
* checks `mCurrentFocus` is this app, or a package-less window (its own popups);
* if not, waits up to twenty seconds, since phones put things in front of you
  unasked;
* then tries to bring **this app's own screen** back, which can only ever start
  this app;
* and only then gives up, printing what was in front instead and stopping the
  run.

It also refuses to start at all if the package under test is not installed,
which is the state that caused the incident.

Proved, not assumed:

```
GUARD REFUSED
  activity:  mFocusedApp=ActivityRecord{... com.motorola.launcher3/...}
  window:    mCurrentFocus=Window{... com.motorola.launcher3/...}
exit=3
```

### The one exception, named

This phone's Security Hub puts a **"Potentially risky website"** page in front
whenever the player fetches from a host it does not recognise — a subtitle
source, a test stream. It steals focus and the run stops behind it.

`dismiss_security_prompt` presses **"Cancel and exit"** and nothing else. Never
"Continue anyway", never "Add site to allow list". A test does not get to change
what a phone trusts. It is the only place anything outside the player is ever
pressed, and it only ever declines.

### What was changed on the device

Only this app was touched:

* the release build was installed and reinstalled;
* it was granted the storage permissions it declares — `READ_MEDIA_VIDEO` and
  `MANAGE_EXTERNAL_STORAGE` — which is what the first run asks for anyway, and
  without which a handed-over subtitle file cannot be read at all. Revoke them
  in Settings if you would rather;
* its playback engine was moved between Media3, mpv and back to Auto, and every
  switch in settings was toggled and toggled back;
* a test video and subtitle were pushed to /sdcard/Movies and deleted after.

The phone's own risky-site warning was declined twice, which grants nothing.

### Audit of what was opened

Every activity this session started, from logcat:

```
app.justplayerpro.android/com.brouken.player.PlayerActivity
app.justplayerpro.android/com.brouken.player.SettingsActivity
```

One stray tap, before the interlock existed, opened the GitHub app; it was
closed with HOME. Nothing else was opened, and no other app's data was touched.

---

## Layer 1 — on this machine

`tests=5 failures=0 errors=0`

The parsing everything downstream depends on: a corpus of real names — scene
releases, anime, torbox and Stremio links, a WhatsApp filename, a bare hash —
and input written to break a parser: empty, dots, unbalanced brackets, lone
percent signs, emoji, bare schemes.

## Layer 2 — on the device

`4 tests, 0 failures`

Two of these exist because a laptop cannot answer the question:

* **Every class holding a regular expression is loaded and every pattern in it
  compiled, on the phone.** Android uses ICU and rejects patterns desktop Java
  accepts. That is exactly how a pattern that passed here killed the app there.
* **The same name corpus, parsed on the device**, so the two engines of regular
  expression cannot disagree quietly.

**This was proved to have teeth**: the old broken pattern was put back and the
suite failed with the same error the phone gave —
`ReleaseName would not load: java.lang.ExceptionInInitializerError` — then
restored, and it passed.

## Layer 3 — the quick driven suite

`39 passed, 0 failed`, three consecutive runs, the last against the shipped APK.

Opens and plays · header names format and engine · intent subtitle listed under
the launcher's name · pickers reach the trailing edge · Off works · audio track
described not numbered · quick panel carries every row · "Identifying" always
stops · eleven presses through every scaling mode · locked: back does not exit,
nothing plays or seeks, controls stay hidden, held-OK lifts it and a single
press does not · every settings screen opens and returns · update check answers
· zero crashes · settings unchanged by the run.

## Layer 4 — the long one

Configured from `.env` through the app's own setup page, which tests that page
at the same time. **No key is ever printed**; the file is parsed by hand rather
than sourced, because sourcing `NAME:value` lines makes the shell try to run
them and print every secret in the failure.

All four services answered live:

```
TMDB accepted the key
OpenSubtitles accepted the key
SubDL accepted the key
Wyzie accepted the key
the built-in addon answers: OpenSubtitles v3 · 90 subtitles
```

Then: **every setting** in the app found, pressed and survived — 36 rows,
switches toggled and toggled back so the run changes nothing; **both engines**
end to end — plays, header names the right one, the launcher's subtitle is
listed, the audio track is described, the arrows seek, every scaling mode;
**driven by arrows and OK alone**, as a remote would, with focus walked along
the controls and into the quick panel; **playing from the web**, a plain MP4
and an HLS stream; and **a real film identified** against TMDB with a real
subtitle search behind it.

---

## What the long run found that reading the code had not

Six real bugs, all now fixed:

1. **The mpv engine could not open an https address at all.** It does its own
   TLS and knows nothing of Android's trust store, so every stream over https —
   which today is every stream — failed the handshake and then went looking for
   youtube-dl, which is not on a phone either. Debrid links, Stremio's,
   anything. Now given the device's own certificates; verified by playing an
   HLS stream over https on that engine, which before reported "Failed to open"
   every time.
2. **An HLS link with its own mime type was refused.** Anything that sets a type
   sends `application/x-mpegURL`; the app claimed only `video/*` and so was not
   offered at all.
3. **Sidecar subtitles never reached mpv.** They were added straight after
   `loadfile`, which only asks for the file — it opens later and the track list
   is built then, so the addition applied to nothing.
4. **A subtitle handed over as a file path went missing silently** under scoped
   storage.
5. **Back walked out of a locked player**, because from Android 13 back is not a
   key event and the lock worked by swallowing key events.
6. **A single press of OK lifted the lock**, so on a remote it lasted one
   keystroke.

And one thing that is the phone, not the player: this device's Security Hub
intercepts unfamiliar hosts with a full-screen warning. If a stream will not
start and there is a "Potentially risky website" page behind it, that is why.

---

## What this does not prove

* **One device, one orientation.** A moto g54 in portrait, Android 15. Not a
  television, not a tablet, not Android 8.
* **Subtitle rendering is not asserted.** Media3 draws cues on a canvas, so
  nothing in the view hierarchy says where the text is. Position, and the clamp
  that keeps it out of the letterbox, were verified by eye from screenshots on
  both engines.
* **A bare `file://` subtitle on shared storage still fails with no storage
  permission granted.** The app asks for storage access on first run; without
  it, the operating system will not open a `.srt` that is not a media file, and
  no copying trick gets round that. Granting it fixes it.
* **The heat and battery figures** were measured once, on this device, on one
  file.
* **The settings sweep presses every row and checks the app survives.** It does
  not check that each setting then does what it says — that a toggled switch
  changes playback. That is what a person watching is for.
