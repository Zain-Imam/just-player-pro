# 2.0.0

A year's worth of things I kept meaning to fix, plus the parts of the other
Just Player and mpv forks that were worth having.

Nothing was removed. If 1.0 did it, 2.0 still does it.

## The headline

**The same player whichever way you are driving it.** A pile of behaviour used
to be switched on by asking whether the device was a television, when what it
actually depended on was whether a key had been pressed rather than a finger.
Holding the frame button to zoom, keys reaching the player at all, back closing
the controls, lifting the lock, whether the settings button was live before a
file was open — all of it was television-only, so a phone with a remote plugged
into it got the wrong answer, and so did a television with a touchscreen. They
are unconditional now. What is left of the television test is the handful of
things a television genuinely cannot do: it has no rotation, no day-and-night
setting, no document picker, and it reports a headphone unplug that never
happened.

**The same player whichever engine is playing.** Several differences that had
been there since the second engine was added are gone: mpv was told which
subtitle language to prefer and Media3 was not; a drag on the seek bar finished
in a different place on each; a subtitle handed over by another app was listed
on both and switched on by one; mpv reported almost nothing about a track while
Media3 reported everything.

## Fixed along the way

* **Every HLS stream died the moment it started, on the Media3 engine.** The
  player opened, showed the title, and sat at 00:00 — no error, no dialog,
  nothing to say the stream had gone. ExoPlayer here is a patched build kept in
  the repository; the rest of Media3 comes from Maven, and the two had drifted
  a version apart, so `HlsMediaSource` called a method that no longer existed
  and the playback thread was killed by `NoSuchMethodError`. DASH and
  SmoothStreaming were broken in the same way by different missing methods.
  They are back in step, and a test now reads every Media3 module and checks
  each call it makes into the others is really there — the answer costs a
  second at build time instead of a black screen at midnight.
* **On the mpv engine, a file that would not open said nothing at all.** No
  error, no dialog, no message — the player opened, showed the name, and sat at
  00:00 for as long as you left it. An expired debrid link, a dead host, a 403,
  a file it had no permission to read: every one of them looked exactly like a
  file that was merely slow. It now says so, and says which: a refused request
  reads "The server refused the request. A link from a debrid service may have
  expired", a host that cannot be reached says that instead.
* **The quick panel could not be driven with a remote.** It opened with the
  focus nowhere at all: the rows are laid out a frame after the panel appears,
  the one attempt to focus them came before that and was dropped, and so every
  arrow press afterwards went nowhere. Back was the only way out. It waits for
  the rows now, on both panels.
* **The mpv engine could not open an https address at all.** It does its own
  TLS and knows nothing of Android's trust store, so every stream over https —
  which today is every stream — failed the handshake and then went looking for
  youtube-dl, which is not on a phone either. What you saw was a stream that
  never started, on one engine, with no reason given. It is given the device's
  own certificates now.
* **An HLS link with its own mime type was refused.** A browser, Stremio or
  anything else that sets a type sends `application/x-mpegURL`; the app claimed
  only `video/*` and so was not offered at all. The `.m3u8` path patterns only
  ever helped when nobody set a type.
* **A subtitle handed over as a file path went missing without a word.** Under
  scoped storage a `.srt` on shared storage is not a media file, so mpv said
  "Permission denied" and Media3 listed a track with nothing in it. A copy is
  taken through the content resolver, which honours whatever the intent granted.
* **Sidecar subtitles never reached mpv at all.** They were added immediately
  after `loadfile`, which only asks for the file — it is opened later, and the
  track list is built then, so the addition applied to nothing.

* **The app no longer disappears mid-film.** A brace written the way desktop
  Java accepts and Android does not meant the release-name parser failed to
  load the first time anything touched it — which is as a file opens, on the
  thread that works out what you are watching. The process died with nothing
  said. That one bug accounts for the player vanishing, for subtitles that were
  there a second ago and then were not, and for Change title closing
  everything. Parsing a name can no longer throw, and a background thread can
  no longer take the app down with it.
* **The subtitle position slider does something on Media3.** It had no effect
  at all on any subtitle carrying its own placement.
* **Subtitles stay on the picture** on both engines instead of sliding into the
  letterbox and being drawn on black, or off the frame entirely.
* **The theme colour list shows colours**, once, rather than a list of names
  with the colours hiding behind it.
* **The clock no longer sits on top of the title.**
* **Locking has its own gesture again** — two fingers held — so the plain hold
  can be double speed without one of them losing out.
* **The update check says which of the three things happened**: newer build,
  nothing newer, or could not ask. And it names the version it found.
* **The setup page tests addons**, not only keys, and probes one before saving
  it rather than taking the URL at its word.

## Playback

* **Video quality.** A stream served as a ladder of bitrates, or a file with
  more than one video track, now gets a list with Auto at the top.
* **Ten scaling modes** — fit, fill, crop, stretch, 4:3, 16:9, 1.85:1, 2.35:1,
  2.39:1 — stepped through from the frame button, with free zoom on a long
  press, on any device.
* **Sleep timer**, by minutes or at the end of the file, fading out over the
  last thirty seconds instead of cutting.
* **Hold the picture for double speed**, and hold it with two fingers to lock.
  One gesture used to have to be both.
* **A clock on screen** while the controls are up.
* **Volume keys can change the film** rather than the ringtone.
* **The arrows seek by the step set in Settings**, not a fixed ten seconds.
* **Seeks land in the same place on both engines.** Media3 snaps to the nearest
  keyframe while dragging, because landing exactly costs a decode of everything
  since the last keyframe; mpv was exact. It is told the same thing now.
* **mpv runs cooler.** It was asking for `auto-safe` hardware decoding, whose
  only method here hauls every decoded frame back through main memory before
  handing it to the GPU. It asks for the zero-copy path first now, with the
  copying one and software decoding behind it, so nothing that played stops
  playing. Measured on the same 1080p film, on the same device, over the same
  seven minutes.

## Subtitles and languages

* **Subtitles handed over by another app are kept.** Stremio, Nuvio,
  torbox-android and the rest each pass them in a slightly different shape — an
  array of Uri, a list of Uri, an array of plain strings, a list of strings, or
  one on its own. Only the first was being read, so a subtitle sent by one app
  arrived and one sent by the next was dropped without a word. All of them are
  read now, along with the several spellings of the name and language keys.
  The one the launcher asked to have on is switched on under both engines, and
  carries its name and language into the picker.
* **A language order, not a single language.** `jpn, eng, spa`, and the first
  one the file actually has is the one that opens. One list for audio, one for
  subtitles, both read by both engines.
* **Media3 honours the subtitle language too.** It never had, so the same file
  opened with subtitles on one engine and without them on the other.

## Knowing what you are watching

* **Titles pulled out of links.** Query strings and tokens are dropped, the last
  path segment that looks like a name rather than an id is taken, and the
  percent-encoding is undone — twice, where a name has been through two
  services. What is left is parsed properly: brackets anywhere rather than only
  at the front, checksums, release groups, resolutions, codecs and sources come
  out; `S01E02`, `1x02`, `EP1089`, `Episode 12`, `Season 3` and the anime
  `Title - 08` form all go in, along with an episode title where there is one.
  Whatever the launching app called it wins over the address.
* **A second line under the title** — `3840×2160 · HEVC · HDR · E-AC-3 5.1 ·
  Media3` — built from the tracks the player settled on rather than from the
  file, which is the quickest way to notice a device quietly falling back to
  something lesser. It ends with the engine, which on Auto is the only way to
  know which one a file landed on.
* **Tracks described properly, identically on both engines.** Resolution,
  channel layout, codec, sample rate, bit rate, and whether a track is forced,
  for the hard of hearing, or an audio description. Two English soundtracks are
  no longer two identical rows.
* **The card shows the poster, not a frame from the episode.** A still is a
  frame out of the middle of the thing you are watching, which over a paused
  film reads as a second screenshot, and which for half the episodes ever made
  is a dark corridor.

## Interface

* **Panels along the edge, not boxes in the middle.** The track lists and both
  settings panels slide in along the trailing edge at full height, all the same
  width, barely dimming the film — because what is being chosen is almost always
  a decision about what is on screen at that moment.
* **Errors in words**, with the details behind a button: shareable, or shown as
  a code a phone can scan when there is nothing on the device to share to.
* **Check for updates** in Settings. It asks the releases page and offers to
  fetch a newer build, preferring the one built for this device's architecture
  over the universal one. Never automatic; nothing reaches the network on its
  own.
* **While locked, the volume keys still work.** A lock is there to stop a pocket
  changing the film, and a pocket does not press volume buttons.
* **Ask for the info card** from the quick panel rather than waiting for a
  pause, and read the setting to find out what it does rather than turning it
  on to see.
* **Test keys and addons** from Settings, on demand.
* **An About row** with the version and a mark that opens the repository.

## Which APK

One per architecture, plus a universal one that contains all four and is about
four times the size. Almost every phone, tablet and television made since 2017
is **arm64-v8a**. If in doubt, take the universal one.

## Thanks

Ideas in this release came from reading
[mpvNova](https://github.com/Laskco/mpvNova),
[mpvEx](https://github.com/marlboro-advance/mpvEx),
[mpvRex](https://github.com/sfsakhawat999/mpvRex) and
[mpvRx](https://github.com/Riteshp2001/mpvRx), on top of
[Just Player](https://github.com/moneytoo/Player),
[just-player-plus](https://github.com/wasky/just-player-plus) and
[Just+ Player](https://github.com/just-plus-player/just-plus-player). The
credits in the README say which idea came from where.
