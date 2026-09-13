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
tools/deeper.sh  app.justplayerpro.android               # layer 5, the parts the rest miss
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

### One row is checked but never pressed

The settings sweep presses every row it finds. **"Built by" is the exception**,
because it does exactly what it says: it hands off to whatever opens
github.com, and pressing it put the GitHub app in front of the phone. That is
the one thing this harness must never do, and it did it — in the run before
this one.

So the row is checked for being there, and where it points is established by
asking the package manager which application would answer
`https://github.com/Zain-Imam`. That is the whole of what pressing it would do,
established without doing it. The address itself is asserted from the source,
at `SettingsActivity.java:346`.

### The one other exception, named

This phone's Security Hub puts a **"Potentially risky website"** page in front
whenever the player fetches from a host it does not recognise — a subtitle
source, a test stream. It steals focus and the run stops behind it.

`dismiss_security_prompt` presses **"Cancel and exit"** and nothing else. Never
"Continue anyway", never "Add site to allow list". A test does not get to change
what a phone trusts. It only ever declines.

### What was changed on the device

Only this app was touched:

* the release build was installed and reinstalled;
* it was granted the storage permissions it declares — `READ_MEDIA_VIDEO` and
  `MANAGE_EXTERNAL_STORAGE` — which is what the first run asks for anyway, and
  without which a handed-over subtitle file cannot be read at all. Revoke them
  in Settings if you would rather;
* its playback engine was moved between Media3, mpv and back to Auto, and every
  switch in settings was toggled and toggled back;
* its orientation was cycled through the app's own rotate button and back.
  **The phone's own rotation setting was never touched** — that belongs to
  whoever owns the phone, not to a test;
* a test video and subtitle were pushed to /sdcard/Movies and deleted after.

The phone's own risky-site warning was declined, which grants nothing.

### Audit of what was opened

Every activity this session started, from logcat:

```
app.justplayerpro.android/com.brouken.player.PlayerActivity
app.justplayerpro.android/com.brouken.player.SettingsActivity
```

Two stray presses, before the interlock covered them, opened the GitHub app;
both times it was closed and the phone returned to its launcher, and the sweep
no longer presses that row. Nothing else was opened, and no other app's data
was read or changed.

---

## Layer 1 — on this machine

`tests=6 failures=0 errors=0`

**The name parsing** everything downstream depends on: a corpus of real names —
scene releases, anime, torbox and Stremio links, a WhatsApp filename, a bare
hash — and input written to break a parser: empty, dots, unbalanced brackets,
lone percent signs, emoji, bare schemes.

**And the Media3 modules against the ExoPlayer they run on.** ExoPlayer here is
a patched build kept in `app/libs`; the rest of Media3 comes from Maven, and
the two are separate artefacts, so javac and R8 both accept a call to a method
that is not there. The test reads the class files of every Media3 jar — the
published modules and the aars, in both directions — finds every method and
field they name in one another, and checks each one exists. At the pinned
version it finds nothing.

**Proved to have teeth.** Put the version skew back and it reports 218 calls
that would throw the moment they ran — across HLS, DASH, SmoothStreaming and ExoPlayer itself — out
of 43,775 checked across 17 modules.

## Layer 2 — on the device

`4 tests, 0 failures`

Two of these exist because a laptop cannot answer the question:

* **Every class holding a regular expression is loaded and every pattern in it
  compiled, on the phone.** Android uses ICU and rejects patterns desktop Java
  accepts. That is exactly how a pattern that passed here killed the app there.
* **The same name corpus, parsed on the device**, so the two engines of regular
  expression cannot disagree quietly.

**This was proved to have teeth too**: the old broken pattern was put back and
the suite failed with the same error the phone gave —
`ReleaseName would not load: java.lang.ExceptionInInitializerError` — then
restored, and it passed.

## Layer 3 — the quick driven suite

`39 passed, 0 failed`, against the shipped APK, twice over.

Opens and plays · header names format and engine · intent subtitle listed under
the launcher's name · pickers reach the trailing edge · Off works · audio track
described not numbered · quick panel carries every row · "Identifying" always
stops · eleven presses through every scaling mode · locked: back does not exit,
nothing plays or seeks, controls stay hidden, held-OK lifts it and a single
press does not · every settings screen opens and returns · update check answers
· zero crashes · settings unchanged by the run.

## Layer 4 — the long one

`144 passed, 0 failed`

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

Then:

* **the names R8 was told to leave alone** — the four private Media3 fields the
  player reaches by name, checked against the release mapping file, and the
  rules themselves checked to be still in `proguard-rules.pro`;
* **every setting** found, pressed and survived — 36 rows, switches toggled and
  toggled back so the run changes nothing;
* **both engines** end to end — plays, header names the right one, the
  launcher's subtitle is listed, the audio track is described, the arrows seek,
  every scaling mode, **and an HLS stream over https on each engine**, which is
  where the two differ most and where both have already broken;
* **rotating**, through the app's own button, and a **link that answers but not
  with a file**, which must be explained rather than ignored;
* **driven by arrows and OK alone**, as a remote would, with focus walked along
  the controls, into the quick panel, and — separately asserted — moved down the
  panel by an arrow and back up;
* **playing from the web**, a plain MP4 and an HLS stream;
* and **a real film identified** against TMDB with a real subtitle search behind
  it.

## Layer 5 — the parts the others do not reach

`32 passed, 0 failed`

Five things none of the layers above touched:

* **The decoder extensions.** The ffmpeg, AV1, IAMF and MPEG-H aars in
  `app/libs` are built separately from the Media3 they run against — the same
  arrangement that broke HLS when the versions drifted apart — and nothing else
  here played a file that needed one of them. Seven now play, every one of them
  through to sound: **AC-3 5.1, E-AC-3 bare, E-AC-3 in MP4, DTS 5.1, TrueHD in
  M2TS, FLAC and Opus**, with an empty crash log after each. The samples are
  fetched on demand and are not in the repository.
* **The track pickers under a remote.** The focus bug that made the quick panel
  unusable was the same in the track lists and the poster grid, and those were
  fixed by analogy rather than by being driven. Now driven: the picker takes the
  focus when it opens, and an arrow moves it.
* **Picture-in-picture** — enters it, keeps running inside it, comes back out.
* **Leaving and coming back** — the film carries on from where it was
  (12,639 ms to 13,190 ms).
* **Landscape** — the quick panel opens, and nothing is drawn outside the
  window once it has turned.

---

## What the driven runs found that reading the code had not

Nine real bugs, all now fixed. The first three are the ones that would have
reached you.

1. **Every HLS stream died the instant it started, on the Media3 engine.**
   ExoPlayer here is not the published one — it is a patched build kept in
   `app/libs`, because the player calls three methods upstream has not got. The
   rest of Media3 comes from Maven, and the rebrand commit raised that to
   1.11.0 while the aars stayed at 1.10.0. Nothing warned: they are separate
   artefacts, so javac and R8 both accept a call to a method that is not there,
   and the program finds out when it runs the line. `HlsMediaSource` 1.11.0
   calls `BaseMediaSource.getBandwidthMeter()`, added after 1.10.0, so the
   playback thread was killed by `NoSuchMethodError` — and what you saw was a
   player that opened, showed the title, and sat at 00:00 with no error, no
   dialog and nothing in the interface at all. DASH and SmoothStreaming were
   broken the same way by different missing methods.
2. **On the mpv engine, a file that would not open said nothing whatsoever.**
   `MPV_EVENT_END_FILE` was reported as "ended" whatever had happened, and the
   error was never set, so the activity had nothing to show. An expired debrid
   link, a dead host, a 403, a path with no permission — every one of them
   looked exactly like a file that was merely slow, for as long as you left it.
   It now tells a failure from an ending by order (an end before any beginning),
   and reads mpv's own log to say which failure it was: a refused request now
   reads "The server refused the request. A link from a debrid service may have
   expired."
3. **The quick panel could not be driven with a remote.** It opened with the
   focus nowhere: the rows are laid out a frame after the panel appears, the
   single attempt to focus one came before that and was dropped, and every
   arrow press afterwards went nowhere. Back was the only way out. The track
   pickers and the poster grid had the same bug, and theirs had no fallback at
   all. On a touchscreen nothing looked wrong, because a finger does not need
   focus.
4. **The mpv engine could not open an https address at all.** It does its own
   TLS and knows nothing of Android's trust store, so every stream over https —
   which today is every stream — failed the handshake and then went looking for
   youtube-dl, which is not on a phone either. Now given the device's own
   certificates.
5. **An HLS link with its own mime type was refused.** Anything that sets a type
   sends `application/x-mpegURL`; the app claimed only `video/*` and so was not
   offered at all.
6. **Sidecar subtitles never reached mpv.** They were added straight after
   `loadfile`, which only asks for the file — it opens later and the track list
   is built then, so the addition applied to nothing.
7. **A subtitle handed over as a file path went missing silently** under scoped
   storage.
8. **Back walked out of a locked player**, because from Android 13 back is not a
   key event and the lock worked by swallowing key events.
9. **A single press of OK lifted the lock**, so on a remote it lasted one
   keystroke.

And one thing that is the phone, not the player: this device's Security Hub
intercepts unfamiliar hosts with a full-screen warning. If a stream will not
start and there is a "Potentially risky website" page behind it, that is why.

## Three bugs the harness had, found by its own runs

Worth writing down, because a test that reports the wrong thing is worse than
no test — and two of these were reporting the wrong thing about a player that
was behaving perfectly.

* **The settings sweep pressed "Built by"**, which does exactly what it says and
  handed off to the GitHub app — putting another application in front of the
  phone, which is the one thing this harness must never do. It checks the row
  is there and asks the package manager which application would answer that
  address instead, which is the whole of what pressing it would do.
* **The panel check could not have passed.** It read the focused node's text,
  and panel rows are ViewGroups with no text of their own — the label and value
  are children. So the answer was empty whether the panel worked or not. It
  reported a real bug by accident, and would have gone on reporting it after
  the fix. It now asks two questions separately — something takes the focus,
  and an arrow moves it — with rows told apart by position when they have no
  text.
* **The rotation check asserted the film was still playing**, which it could not
  have been: reaching the rotate button means showing the controls, and
  `show_controls` pauses first, deliberately, so a test is not racing the film.
  It was asking the harness to contradict itself. What rotating can actually
  break is the place in the film, so that is what is checked now.

And one that ended a run rather than misreporting it: a **Back pressed with
nothing open** left the player, after which every press would have landed on a
launcher. The interlock stopped the run, correctly — but it could not recover,
because `open_film` had cleared the record of which screen to bring back. Back
is now only pressed when there is something for it to close, and the interlock
has a screen of this app to return to.


---

## What this does not prove

* **One device, one orientation.** A moto g54 in portrait, Android 15. Not a
  television, not a tablet, not Android 8. The remote is simulated by sending
  key events to a phone, which is the same input path a television uses but not
  the same device.
* **Subtitle rendering is not asserted.** Media3 draws cues on a canvas, so
  nothing in the view hierarchy says where the text is. Position, and the clamp
  that keeps it out of the letterbox, were verified by eye from screenshots on
  both engines.
* **A bare `file://` subtitle on shared storage still fails with no storage
  permission granted.** The app asks for storage access on first run; without
  it, the operating system will not open a `.srt` that is not a media file, and
  no copying trick gets round that. Granting it fixes it.
* **The heat and battery figures** were measured once, on this device, on one
  file, and before the Media3 version was put back. They are a comparison
  against MPVRX on the same clip, not a battery life claim.
* **The settings sweep presses every row and checks the app survives.** It does
  not check that each setting then does what it says — that a toggled switch
  changes playback. That is what a person watching is for.
* **The AV1, IAMF and MPEG-H decoders are still not exercised.** The ffmpeg one
  is, seven codecs deep. No sample needing the other three was to hand.
* **Nothing was played over HDMI**, so audio passthrough — AC-3, E-AC-3 and
  TrueHD sent to a receiver rather than decoded — is untested, as is HDR and
  Dolby Vision output. Those need a television, and are the most likely place
  for a television to differ from this phone in something other than layout.
* **Tablet layout was not tested.** Simulating one means overriding the display
  size, which is the phone's setting and not this harness's to change.

## Two behaviours worth knowing about, which are not faults

Both were found by tests that failed against a player doing exactly what it
was designed to do.

* **A launcher that passes subtitles owns the position.** Handing over a
  sidecar subtitle, a start position, or asking for a result back all set
  `apiAccess`, which turns persistent mode off: the position is then kept in
  memory and handed back to the launcher instead of being written down. That is
  right for Stremio, Nuvio and torbox, which keep their own place. It does mean
  a launcher that passes subtitles but does not track position will not have the
  film remembered for it.
* **A player killed outright loses its place** back to the last time it stopped,
  because the position is written in the lifecycle callback rather than
  periodically. Pressing Back or Home saves it; being force-stopped, or killed
  by the system under memory pressure, does not.
