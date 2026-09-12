# What was verified, and how

Run against the exact APK in `release-apks/`, on a moto g54 5G (Android 15),
2026-09-12. Every claim below is something a command printed, not something
somebody read in the code and believed.

Re-run it yourself:

```
./gradlew :app:testLatestUniversalDebugUnitTest          # layer 1
./gradlew :app:connectedLatestUniversalDebugAndroidTest  # layer 2
tools/smoke.sh app.justplayerpro.android                 # layer 3
```

---

## Layer 1 — on this machine

`tests="5" skipped="0" failures="0" errors="0"`

Covers the parsing everything downstream depends on: a corpus of real names
(scene releases, anime, torbox and Stremio links, a WhatsApp filename, a bare
hash) and input written to break a parser — empty, dots, unbalanced brackets,
lone percent signs, emoji, bare schemes.

## Layer 2 — on the device

`4 tests, 0 failures`

Two of them exist because a laptop cannot answer the question:

* **Every class holding a regular expression is loaded and every pattern in it
  compiled, on the phone.** Android uses ICU and rejects patterns desktop Java
  accepts. That is exactly how a pattern that passed on this machine killed the
  app on the phone — it failed in a static initialiser, on the thread that works
  out what is playing, and took the process with it.
* **The same name corpus, parsed on the device**, so the two engines of regular
  expression cannot disagree quietly.

**This test was proved to have teeth**, not assumed to: the old broken pattern
was put back and the suite failed with the same error the phone gave —
`ReleaseName would not load: java.lang.ExceptionInInitializerError`. Then it was
restored and the suite passed again.

## Layer 3 — the player, driven

`39 passed, 0 failed` — three consecutive runs, the last against the shipped
arm64 APK.

| What it proves | |
|---|---|
| It opens, plays, and does not crash | PASS |
| The header names the file, format **and engine** (`1920×1080 · H.264 · AAC 2ch · Media3`) | PASS |
| A subtitle handed over on the intent is listed **under the name the launcher gave it** | PASS |
| The subtitle picker reaches the trailing edge (to x=1020 of 1080) | PASS |
| Off is offered, and turning subtitles off survives | PASS |
| The audio picker shows the channel layout and the codec, not "Track 1" | PASS |
| The quick panel carries Speed, Playback engine, Sleep timer, Show info card, Audio track | PASS |
| The quick panel reaches the trailing edge (to x=1060 of 1080) | PASS |
| Asking for the info card **always stops saying "Identifying"** | PASS |
| Eleven presses of the frame button — every scaling mode — breaks nothing | PASS |
| Locked: back does not leave the player | PASS |
| Locked: nothing starts or stops playing | PASS |
| Locked: the controls stay hidden | PASS |
| Locked: holding OK lifts it, a single press does not | PASS |
| Every screen in settings opens and comes back: theme, engine, addons, URLs, About | PASS |
| The update check answers rather than hanging | PASS |
| Nothing crashed at any point | PASS |
| The run left the settings as it found them | PASS |

### Two bugs this suite found that reading the code had not

* **Back walked out of a locked player.** From Android 13 back is not a key
  event, so a lock that works by swallowing key events never saw it — and back
  is the first button anybody presses, and on a television it is how you leave
  everything.
* **A single press of OK lifted the lock**, so on a remote the lock lasted
  exactly one keystroke. It has to be held now, like the two-finger hold that
  sets it.

---

## The interlock

The driven suite presses fixed screen coordinates. If the player is not the
thing in front — it failed to start, it crashed, an install killed it — those
presses land on the launcher and open whatever is under them. **That happened
once, and opened private apps.**

Every press now goes through `require_player`, which asks two questions and
stops the run rather than guessing:

* `mFocusedApp` — the activity behind whatever has focus — must be this app.
* `mCurrentFocus` must be this app, or a package-less window (its own popups
  and dialogs). Any other package, and the run stops.

Both halves were proved:

```
GUARD REFUSED
  activity:  mFocusedApp=ActivityRecord{... com.motorola.launcher3/...}
  window:    mCurrentFocus=Window{... com.motorola.launcher3/...}
exit=3
```

and the run that found the back bug stopped itself the same way rather than
pressing on.

The suite also refuses to start at all if the package under test is not
installed, which is the state that caused the incident.

**Audit of the whole session's runs** — every activity started, from logcat:

```
7  cmp=app.justplayerpro.android/com.brouken.player.SettingsActivity
1  cmp=app.justplayerpro.android/com.brouken.player.PlayerActivity
```

Nothing else was opened. Test media was deleted; the settings-drift check
reported no lasting change.

---

## What this does not prove

Being straight about the edges, because a list of passes with no limits on it
is not worth much:

* **One device, one screen, one orientation.** A moto g54 in portrait, Android
  15. Not a television, not a tablet, not Android 8.
* **The release build has no TMDB key**, so "Show info card" was exercised down
  its no-key path. The with-key path — identify, TMDB search, results list,
  subtitle download — was driven by hand earlier in the session and worked, but
  is not in the automated suite.
* **Subtitle rendering is not asserted.** Media3 draws cues on a canvas, so
  nothing in the view hierarchy says where the text is. Position and the
  letterbox clamp were verified by eye from screenshots, on both engines, not by
  a test.
* **The mpv engine is not covered by the driven suite.** Switching engines
  rebuilds the player mid-run; that was verified by hand.
* **Heat and battery** were measured once, on this device, on one file.

Everything in the first table is checked on every run. Everything in this last
list is checked by somebody watching.
