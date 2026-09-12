# Just Player Pro

**It plays anything you throw at it.** Two playback engines ship inside the app — Android's own
Media3/ExoPlayer and a full build of mpv — and the player moves between them on its own, so a file
that one cannot decode is handled by the other instead of failing.

Built on [Just Player](https://github.com/moneytoo/Player) by Marcel Dopita, whose strengths are
kept intact: no ads, no tracking, barely any permissions, and playback that hands the bitstream
straight to the device's decoders.

It installs as a separate app (`app.justplayerpro.android`), so it can sit alongside any other
player you already use.

> This was tailored to my own use. It is shared in case it is useful, but it is not a product:
> do not expect new features, a roadmap, or fixes on request. I may add things, I may not.

---

## The two engines

| | Media3 / ExoPlayer | mpv |
|---|---|---|
| Decoding | the device's own hardware decoders | the device's where it has one, its own 501 where it does not |
| Best at | smoothness, battery, TV boxes | anything the device has no decoder for |
| Version here | 1.11.0 | 0.41.0 with FFmpeg n8.1 |

Both engines reach for the chip first. mpv asks for the zero-copy path before the copying one, which
on a 1080p film is the difference between about 1.5 processor cores and about 0.95 — measured, on
the same file, over the same seven minutes. Media3 is still the lighter of the two, at about 0.8,
because it never touches the frame at all.

**Media3** is the default. It hands the bitstream straight to the device's decoders and renders
zero-copy onto a `SurfaceView`, which is why it plays smoothly on TV boxes where GPU-rendering
players stutter, and why it is the one that does tunneled playback and display frame-rate matching.

**mpv** carries its own decoders — **501 of them** — so it does not care what the device supports.
10-bit and 4:2:2 H.264, interlaced video, VC-1, RealVideo, Cinepak, Indeo, ProRes, DNxHD, FFV1,
HuffYUV, styled ASS subtitles, DTS, TrueHD, Musepack, APE, ATRAC3, QDM2 — all of it decodes in
software on any device.

**Auto** (the recommended setting) starts every file on Media3 and switches to mpv only when Media3
reports it cannot play the video. Nothing is tried twice, and the switch keeps your position.

### About the claim

"Plays anything" is a large thing to say, so here is exactly what backs it:

* The bundled mpv reports **501 decoders** and essentially the whole FFmpeg demuxer set — the same
  library VLC and mpv on the desktop use. This is not a cut-down build.
* The only two codecs checked for and *not* found in mpv are **AMR-NB and AMR-WB**, the narrowband
  speech codecs — and those are exactly the ones Android's own decoders have always provided, so
  Media3 covers them. The two engines complement each other rather than overlap.
* Container support comes from FFmpeg's demuxers: MKV, MP4, AVI, TS, FLV, OGG, WMV/ASF, RM, VOB,
  M2TS, WebM, and the long tail of formats nobody has opened since 2004.

The honest exception: **DRM-protected streams** (Widevine and friends) are not supported by either
engine here, and nothing in this app tries to work around that.

---

## What this fork adds

### Playback

* **Dual engines** — Media3, mpv, or Auto, chosen in settings. Auto falls back only on a real
  decode failure.
* **Adaptive buffering** — a port of my mpv `auto_profile.lua` and `mpv.conf`, applied to *both*
  engines so they buffer alike. The profile is picked from device memory, battery level and whether
  the source is a live stream: `device-low` (30s/120s), `device-balanced` (50s/300s),
  `device-high` (60s/600s) or `live-stream` (5s/15s). Back buffer is held at 25% of the forward
  buffer at every tier, matching the mpv config rather than approximating it.
* **Volume boost** — up to 150%, applied through a `LoudnessEnhancer` on Media3 and mpv's own
  volume on mpv. No restart, and the scale stays 0–100 either way.
* **Keep screen on** while playing.
* **Picture-in-picture on Home** — pressing Home drops the film into PiP instead of pausing it.
* **Double-tap seek step** is configurable, and the arrows on a remote use the same setting rather
  than a fixed ten seconds.
* **Hold the picture for double speed**, let go to drop back.
* **Sleep timer** — a set number of minutes or at the end of the file, fading the sound out over the
  last thirty seconds rather than cutting it.
* **Ten scaling modes** — fit, fill, crop, stretch and the fixed ratios (4:3, 16:9, 1.85:1, 2.35:1,
  2.39:1), stepped through from the frame button, with free zoom on a long press.
* **A clock on screen** while the controls are up.
* **Volume keys change the film, not the ringtone**, when that is what you want.
* **Video track picker** — a stream served as a ladder of bitrates, or a file carrying more than one
  video track, gets a list with Auto at the top.
* **Check for updates** — an entry in settings that asks the releases page whether there is a newer
  build and offers to fetch it. Never automatic; nothing reaches the network on its own.

### Identifying what you are watching

* **TMDB lookup** turns a release filename into a real title, year, rating, poster and synopsis.
* **Titles pulled out of links, not just filenames.** Query strings and tokens are dropped, the last
  segment that looks like a name rather than an id is taken, percent-encoding is undone (twice,
  where a name has been through two services), and what is left is parsed: brackets, checksums,
  release groups, resolutions, codecs and sources come out, `S01E02`, `1x02`, `EP1089`,
  `Episode 12`, `Season 3` and the anime `Title - 08` form all go in. Whatever the launching app
  called it wins over the address.
* **Info card while paused** — poster, title, season/episode, date, rating and synopsis, centred on
  the picture and sized to it, so on a letterboxed film it stops where the picture stops. It is
  half-transparent, hides itself during seeking, menus, dialogs, PiP and lock, and the centre
  controls step aside into the time row while it is up.
* **Change title…** — when the guess is wrong, search by hand; season and episode are always
  offered rather than inferred from the filename.
* **Identity survives regenerated links** — debrid URLs expire, so a file is remembered by path and
  filename as well as URL. Re-opening the same film through a fresh link keeps its identity, its
  subtitles and its skip markers.
* **History with real titles** — the recently-played list and the "Play last video?" prompt show
  the film's name, not a UUID from the URL.

### Subtitles

* **Online search and download** from OpenSubtitles, SubDL and Wyzie, with the language you set.
* **Custom Stremio subtitle addons** — up to five, each verified against a known film before it is
  saved, so a broken addon is caught when you add it and not when you need it. Addon 1 comes
  pre-filled with the official OpenSubtitles addon and works as-is.
* **Downloads never restart playback** — the subtitle is attached in place, your position is kept,
  and the new track is selected automatically.
* **Named by release, not by file** — a downloaded subtitle appears under the provider's release
  name rather than the filename it happened to be saved as.
* **A subtitle button that is always available** — it no longer greys out when a file has no
  subtitle tracks; it offers to search online instead.
* **Subtitle folder** — choose where downloaded subtitles are kept.
* **Styling works on both engines** — size, position, edge style and typeface are translated into
  mpv's own vocabulary (`sub-pos`, `sub-scale`, `sub-ass-override`…) so the sliders mean the same
  thing whichever engine is playing, including below the default position.
* **Subtitles handed over by another app are kept.** Stremio, Nuvio, torbox-android and the rest
  each pass them in a slightly different shape — an array of Uri, a list of Uri, an array of plain
  strings, a list of strings, or one on its own — and all of them are read, along with the several
  spellings of the name and language keys. The one the launcher asked to have on is switched on,
  under both engines, and carries its name and language into the picker.
* **A language order rather than a single language** — `jpn, eng, spa`, and the first one the file
  actually has is the one that opens. There is one for audio and one for subtitles, and both engines
  read the same list.

### Skipping intros and credits

* **Chapters first, databases second.** If the file has chapters naming an intro or the credits,
  those are used — read from mpv directly, and parsed out of the Matroska container for Media3,
  which has no chapter API of its own. Otherwise the online markers are used.
* **Sources merged, not stacked** — markers from chapters, IntroDB, TheIntroDB, SkipDB and AniSkip
  are clustered by overlap; the most corroborated cluster wins, and ties break by source
  reliability. Precise bounds beat placeholder ones.
* A **Skip intro / Skip credits** button appears only while a marker is live, and is reachable with
  a remote.

### Interface

* **11 accent colours** (orange by default), matching my TorBox Android palette. The chosen colour
  runs through the controls, the seek bar and every highlight. The launcher icon stays orange.
* **Quick settings panel** on a single tap of the gear — speed, engine, info card, skip markers,
  adaptive buffering, audio tracks, subtitle settings, and a way into the full settings screen.
* **Audio track menu** for files with more than one soundtrack, on both engines.
* **Panels along the edge, not boxes in the middle.** The track lists and both settings panels slide
  in along the trailing edge at full height, all the same width, barely dimming the film — because
  what is being chosen is almost always a decision about what is on screen at that moment.
* **Tracks described properly.** Resolution, channel layout, codec, sample rate, bit rate, and
  whether a track is forced, for the hard of hearing, or an audio description — in one fixed order,
  identically on both engines, so two English soundtracks are never two identical rows.
* **A second line under the title** saying what is actually playing: `3840×2160 · HEVC · HDR ·
  E-AC-3 5.1`, built from the tracks the player settled on rather than from the file.
* **Errors in words**, with the details behind a button — shareable, or shown as a code a phone can
  scan when there is nothing on the device to share to.
* **A setup page you open on your phone** — settings offers a PIN-gated local web page so API keys
  and addon URLs can be typed on a keyboard instead of a D-pad.
* **Rebuilt bottom bar** — play/pause is the leftmost item in the time row, tapping the time
  toggles remaining-versus-total, and the row shares one line with the button strip so nothing
  becomes unreachable in portrait.
* **Tap the timeline to seek there** — anywhere *on* the line, and nothing outside it. The stock bar
  accepted a press anywhere in a 48dp band the height of the whole bottom bar.
* **The buffered band stays visible while you drag.** Media3 discards its buffer on a seek outside
  what it holds, so the white band used to vanish the moment a drag began; it is now held for the
  length of the drag.
* **Ask before resuming** — the last file is offered rather than started, and declining keeps it
  remembered.
* **Sectioned, shorter settings** with a searchable layout, plus a history screen.

### Inherited from upstream

Nothing was removed. Every setting the projects this builds on shipped is still present and works:
file access mode, preferred audio language, display frame-rate matching, PiP, skip silence, repeat,
custom subtitle fonts, decoder priority, tunneled playback and Dolby Vision profile 7 mapping —
along with the gesture controls, the SAF/MediaStore file handling, the Android TV behaviour and the
subtitle settings reachable by long-pressing the subtitle icon.

Subtitle delay is applied at render time, so negative delays actually move embedded MKV subtitles
earlier rather than just shortening them, in 100 ms steps with a `+` prefix on positive values.

---

## Televisions and remotes

The app is built to be driven entirely with a D-pad, and — as of 2.0 — to behave *identically*
whichever is being used. A pile of things used to be switched on by asking whether the device was a
television, when what they actually depended on was whether a key had been pressed: holding the
frame button to zoom, keys reaching the player at all, back closing the controls, lifting the lock,
whether the settings button was live before a file was open. A phone with a remote plugged into it
answered no to that question and got the wrong behaviour. They are all unconditional now.

What is left of the television test is the handful of things a television genuinely cannot do: it
has no rotation, no day-and-night setting, no document picker, and it reports a headphone unplug
that never happened.

* Every screen it adds — the quick panel, the track pickers, the search results, the poster
  picker, the addon settings — takes focus when it opens and is navigable with arrows, because a
  list that nothing has focused ignores a remote completely.
* The skip button is focusable. The info card deliberately is not, so it can never swallow a press
  meant for the controls.
* Dialogs open with a button already focused — the confirming one, except where agreeing by
  accident would delete a file, where Cancel has focus instead.
* Settings that belong to one engine are disabled, and say so, rather than silently doing nothing.
* The lock can be lifted by a key or by a long press on the picture, on any device. While locked,
  the volume keys still work: a lock is there to stop a pocket changing the film, and a pocket does
  not press volume buttons.
* Where a value could have been hard-coded for one input method it is not. The arrows seek by the
  step set in settings, the same step the double tap uses.

Layouts are in dp and sp throughout and reflow rather than clip, so the same build is used on
phones in either orientation, on tablets, and on televisions.

---

## Settings that belong to one engine

Most things work identically on both. These four are Media3's alone, and the settings screen
disables them and says so when mpv is selected:

* Tunneled playback
* Display frame-rate matching
* Skip silence
* Dolby Vision profile 7 → HEVC mapping

Custom subtitle *fonts* are also Media3-only; mpv uses its own font handling. The system equalizer
hook is Media3-only too, because mpv opens its own audio output and exposes no session to attach to.

Everything else — decoder priority, subtitle size, position, edge style, typeface and embedded
styles, volume boost, PiP, gestures, chapters, skip markers, online subtitles — works on both.

---

## Using it

### On the picture

| Gesture | What it does |
|---|---|
| Tap | Show or hide the controls |
| Double tap, left or right third | Jump back or forward. The step is yours to set in Settings |
| Double tap, middle | Play or pause |
| Drag up and down, **left** half | Brightness |
| Drag up and down, **right** half | Volume — keep going past 100% for the boost |
| Drag left and right | Scrub through the film |
| Long press | Hold for double speed; let go to drop back. While locked, it unlocks |
| Pinch | Zoom the picture, when the aspect is set to Crop |

### On the controls

| Control | What it does |
|---|---|
| Play/pause, far left of the clock | Play or pause |
| The clock | Tap it to swap between time remaining and total running time |
| The timeline | Tap anywhere **on the line** to seek there; drag the circle to scrub |
| Subtitle button | Pick a track, or search online for one |
| Subtitle button, long press | Subtitle size, position, delay, edge and typeface |
| Audio button | Pick a soundtrack, on files with more than one |
| Aspect button | Step through the ten scaling modes: fit, fill, crop, stretch, 4:3, 16:9, 1.85:1, 2.35:1, 2.39:1 |
| Aspect button, long press | Free zoom — arrows or a pinch resize the picture |
| Gear, one tap | Quick panel: speed, engine, video quality, info card, skip markers, buffering, sleep timer, tracks |
| Gear, long press | The full settings screen |
| Padlock | Lock the controls against accidental touches |
| Folder | Open another file |

### With a remote

| Button | What it does |
|---|---|
| Up / Down | Show and hide the controls |
| Left / Right, controls hidden | Jump back and forward by the step set in Settings — the same one the double tap uses |
| Left / Right, **on the timeline** | Drag the scrubber. A press is one second; hold it and the step grows, so you can cross a film and still stop on the second you want |
| OK, on the timeline | Play or pause |
| OK, on a button | Press it |
| Back | Hide the controls; again to leave |
| Back or OK, while locked | Unlock |
| Volume, while locked | Still changes the volume: the lock is for pockets, not for buttons |

The Skip intro and Skip credits buttons take focus while they are on screen, so
OK skips without hunting for them.

Every one of these works the same on a phone with a keyboard or a remote plugged into it as it does
on a television, and every gesture works the same on a television with a touchscreen. Nothing is
switched on by asking what kind of device it is.

### Setting up the online features

Everything online hangs off **TMDB**, which turns a file name into a title the
subtitle and skip databases understand. The key is free: themoviedb.org →
Settings → API. Paste it into **Settings → Online** and it is checked before it
is kept, so a typo is caught there rather than looking like an empty search
later.

Subtitles work with no key at all — a Stremio addon comes set up and ready.
OpenSubtitles, SubDL and Wyzie keys are optional and simply add more to choose
from. You can add up to five addons of your own under **Custom subtitle
addons**; each is tested against a known film before it is saved.

Two switches worth knowing:

* **Identify files** — automatic by default, which is what fills the info card,
  the skip markers and the titles in history. Set it to *Only when I ask* and
  nothing reaches the network until you say so.
* **Search subtitles automatically** — off by default, so a search only happens
  when you press search.

If the guess is wrong, **Change title…** in the subtitle results lets you search
by hand, and it always offers season and episode.

### Choosing an engine

Leave it on **Auto** unless you have a reason not to. It starts every file on
Media3 and moves to mpv only when Media3 says it cannot play the video, keeping
your position across the switch.

Pick one by hand and it stays picked — and if that engine cannot play something,
the player offers you the other one rather than just failing.

## Building

```
./gradlew :app:assembleLatestUniversalRelease
```

The APK lands in `app/build/outputs/apk/latestUniversal/release/just_player_pro.apk`.

Per-architecture builds, which are roughly half the size because they carry one copy of mpv
instead of four:

```
./gradlew :app:assembleLatestUniversalRelease -PabiFilter=arm64-v8a
./gradlew :app:assembleLatestUniversalRelease -PabiFilter=armeabi-v7a
./gradlew :app:assembleLatestUniversalRelease -PabiFilter=x86_64
./gradlew :app:assembleLatestUniversalRelease -PabiFilter=x86
```

Requirements: Android SDK 36, NDK 29, JDK 11+. Minimum supported device is Android 7.1 (API 25);
the mpv engine additionally needs Android 8.0, and is offered only there.

Release builds are signed from `keystore.properties`, which is not in the repository. Without it the
build falls back to the debug key, which is fine for building and testing but produces an APK that
cannot be installed over a released one. Copy `keystore.properties.example` and point it at your own
keystore if you are building your own releases.

### Online features need keys

TMDB is required for identification, and everything downstream of it — the info card, skip markers
and subtitle search — depends on that. The key is free. OpenSubtitles, SubDL and Wyzie keys are
optional and only enable those sources. All of them are entered in Settings → Online; none are
compiled into the app.

---

## Thanks

This stands on other people's work, and it would be poor form not to say so.

* **[Just Player](https://github.com/moneytoo/Player)** by Marcel Dopita — the original, and still
  the foundation: the player core, the gesture controls, the file handling and the Android TV
  behaviour all come from here.
* **[just-player-plus](https://github.com/wasky/just-player-plus)** by Michal Wolski — the subtitle
  settings panel, custom subtitle fonts, the Outline & shadow edge style, the Medium typeface,
  MicroDVD and MPL2 support, and the TV back-button behaviour.
* **[Morveus/just-player-plus](https://github.com/Morveus/just-player-plus)** — subtitle delay
  applied at render time, which is the reason negative delays work on embedded MKV subtitles at all.
* **[Just+ Player](https://github.com/just-plus-player/just-plus-player)** — the most
  feature-complete Just Player fork going, and worth a look in its own right. Ideas and code from it
  are noted in the release notes as they land.

The mpv-based players solved several of the same problems first, and reading how they did it saved a
lot of guessing. Nothing was copied from the AGPL one; the ideas are theirs and the code here is not.

* **[mpvNova](https://github.com/Laskco/mpvNova)** (MIT) — how a launcher's subtitles and start
  position are taken off an intent, and that a `content://` handed to a native player has to be
  turned into something it can actually open.
* **[mpvEx](https://github.com/marlboro-advance/mpvEx)** and
  **[mpvRex](https://github.com/sfsakhawat999/mpvRex)** (Apache 2.0) — the shape of a release-name
  parser, itself after [kahari-parser](https://github.com/GizmoH2o/kahari-parser); and the decoder
  fallback order that turned out to be the whole of the difference in battery and heat on the mpv
  engine here.
* **[mpvRx](https://github.com/Riteshp2001/mpvRx)** (AGPL, so read rather than used) — the several
  shapes a launching app can put subtitles in, all of which now get read.

### Libraries and services

* [libmpv](https://github.com/jarnedemeulemeester/libmpv-android) (`dev.jdtech.mpv:libmpv`), MIT —
  the mpv engine, bundling mpv, FFmpeg and libass.
* [AndroidX Media3](https://github.com/androidx/media), Apache 2.0.
* [zxing](https://github.com/zxing/zxing) core, Apache 2.0 — the encoder behind the error code, and
  nothing else.
* Skip markers from IntroDB, TheIntroDB, SkipDB and AniSkip. Titles and artwork from TMDB.
  Subtitles from OpenSubtitles, SubDL, Wyzie and any Stremio addon you add.

This product uses the TMDB API but is not endorsed or certified by TMDB.

Released into the public domain under the Unlicense, like Just Player.
