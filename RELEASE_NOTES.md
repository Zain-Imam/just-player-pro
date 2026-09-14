# 3.0.0

Twelve things that were asked for, the bugs found while proving each one works,
and a licence that finally says what this project actually wants.

Nothing was removed. If 2.0 did it, 3.0 still does it.

Every feature below was tested on a device **on both engines and under both
input methods** — finger and remote — before it shipped. Where that is not true
of something, it says so.

## The headline

**The player stops interrupting itself.** Three separate things used to throw
the film away and open it again: nudging the subtitle delay by a tenth of a
second, walking into the settings screen and back, and changing the audio delay
in either direction. On a local file that is a stutter. On a stream it is a
spinner, a stall, and the film starting over — for a setting that needed none of
it. All three are gone, and the places where a reopen is genuinely unavoidable
now say so in the row that causes it.

**Sound you can move, and sound that keeps going.** An audio delay on both
engines, ±5 seconds either way, remembered per file — and, if you ask for it, a
player that carries on playing when the screen goes off or the app is put away.

## New

* **Audio delay** — ±5 s either way, per file, holding an arrow to move it
  quickly. mpv has such a property; Media3 has nothing of the kind, so the delay
  is applied to the clock the audio renderer reports, which is the clock the
  picture follows. It is reachable from the audio button as well as the quick
  panel, because that is where somebody fixing the sound will look for it.
* **Subtitle delay in the quick panel too**, beside the audio delay, still in the
  subtitle panel where it has always been, and the two never disagree.
* **Playback speed per file** — a documentary left at 1.25× opens at 1.25× next
  time, however many films at normal speed came in between.
* **Keep playing the sound** *(off by default)* — the screen can go off, or the
  player be put away, and the sound carries on. Picture-in-picture still wins on
  Home when that is switched on.
* **Preview while seeking** *(on by default)* — dragging the bar shows the frame
  you are heading for. Files on the device only: doing it over a connection
  would mean fetching the film a second time to look at pictures of it.
* **A warning before a file stalls the device.** A 4K file on hardware that
  cannot manage it does not fail — it plays the sound, never shows a picture, and
  has to be killed from the recents list. The decoders are now measured against
  what is about to play, and a file beyond them offers *play anyway*, *try the
  other engine* where the other engine has a real chance, or *close*. Asked once
  per file, with a *do not warn me again*, and a switch in settings to bring the
  warnings back.
* **Network speed on the top line** — `1280×720 · H.264 · 25 fps · AAC · mpv ·
  109 KB/s` — counted from the bytes that actually arrive rather than from a
  bandwidth estimate, which goes on claiming a number long after the downloading
  has stopped. Streams only; a file on the device is given none.
* **Export and import** — settings, keys and addons, history, and the per-file
  delays and speeds, written to one file through the system picker. The export
  asks which parts to include, so a file can be handed to somebody else without
  your keys in it; the import takes whatever the file holds. Folder permissions
  belong to one installation and cannot travel, and the dialog says so rather
  than failing quietly later.
* **Open a subtitle you already have** — a row in the subtitle picker, rather
  than a long press nobody finds.
* **Any number of folders the player may read.** There was one, and granting a
  second silently replaced the first — so a library split across internal storage
  and a card could never work: whichever half was granted second was the only
  half searched for the next episode or a subtitle sitting beside the film.
* **A delay that moves as fast as you need it to.** Holding the arrow
  accelerates: ten seconds is about a second and a half of holding rather than a
  hundred presses. Both delays, both engines, finger or remote.
* **A green tick against every key that is set.** A key is never shown back once
  entered, so the screen used to look identical whether one had been typed in or
  never had.
* **Settings say which of them reopen the file.** Five do — engine, buffering,
  decoder priority, tunneling, Dolby Vision mapping — because they are decided
  when the player is built. Each of those rows says so now. Everything else
  applies to the film already playing.

## Fixed along the way

* **A downloaded subtitle was listed as a row of digits.** You picked
  `Inception.2010.1080p.BluRay.DTS.x264-CtrlHD` and the picker showed
  `1000161164`. The release name was filed under the address the file arrived at
  — and every subtitle is rewritten as UTF-8 on the way in, which gives it a
  different address, so the name was never found again and the last part of the
  new address was shown instead. The name follows the file now.
* **On mpv, a subtitle that would not load said nothing at all.** Media3 reports
  a load failure; mpv's `sub-add` reports nothing a caller can read, so a file it
  could not open — no permission, a dead link, a format it will not parse — left
  the picker looking exactly as though nothing had been asked. It now counts the
  track list either side of the command and says the same sentence the other
  engine says.
* **On mpv, changing the speed did nothing for about twelve seconds.** The
  property took the new value immediately and the sound went on at the old one:
  mpv rebuilds its audio chain only at a playback restart, and set from a panel
  that pauses the film to show itself, that restart could be a long way off. A
  zero-length seek is a restart that does not move.
* **A remembered subtitle delay was restored on one engine only.** It was kept
  per file and put back on Media3; on mpv the file opened at zero every time.
* **The buffering spinner vanished with the controls.** The moment you most want
  to know the film is still loading is exactly the moment you have pressed Back
  to get the furniture out of the way.
* **Leaving settings restarted the film.** It is held at the frame it was on now
  — paused for the trip if it was playing, left alone if it was not — and only
  the five that cannot be applied to a running player reopen anything.
* **The subtitle and audio language order used to arrive by accident**, carried
  in on that restart. It is handed to the track selector directly, so it applies
  without reopening anything.

## Licence

**Just Player Pro is now under the GNU General Public License v3.0.** It was
previously the Unlicense, inherited from upstream, which granted everyone the
right to take it, close it and sell it without so much as a mention — not
something anybody had decided on purpose.

Nothing about using the app changes. What changes is what a fork owes: the code
can be changed and rebuilt for whatever you need, and a version handed to
somebody else has to come with its source under the same terms, so the next
person gets what you got.

* Parts inherited from [Just Player](https://github.com/moneytoo/Player) remain
  available from upstream under the Unlicense. The work done here is GPL.
* The name and the logo are not covered by the licence — fork the code and give
  the fork its own name and icon, so people can tell which one they installed.
* **THIRD-PARTY-NOTICES.md** now lists every bundled component, its licence and
  where its source can be had. The app ships mpv, FFmpeg, libass and libplacebo
  as native libraries, all LGPL, and carried no licence text for any of them
  before this release.

Releases are signed with one key, and its fingerprint is published in the README,
so any build claiming to be this one can be checked with `apksigner`.

## Known limits

* On mpv, 2× on 1080p60 film asks for 120 frames a second of decoding, which
  ordinary phone hardware cannot do — playback runs at whatever it manages. At 30
  fps it reaches 2× exactly. Media3 drops frames instead and keeps the clock.
* The Media3 audio delay costs a moment of catching up after a seek: the picture
  and the sound start a seek together, so one of them has to move. mpv avoids
  this by seeking the two streams to different places, which a Media3 media
  source cannot do.
* Seek previews are decoded from the file and need a container that can be
  indexed — mp4 and mkv are fine, a raw transport stream often is not.
* Keeping the sound going has no foreground service behind it, so Android may
  reclaim the app under memory pressure during a long screen-off listen.

## Which APK

One per architecture, plus a universal one that contains all four and is about
four times the size. Almost every phone, tablet and television made since 2017
is **arm64-v8a**. If in doubt, take the universal one.

---

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
* **Ten scaling modes, each with its own icon** — Default, Crop, Stretch, then
  16:9, 4:3, 16:10, 2:1, 2.35:1, 2.39:1 and 5:4, stepped through from the frame
  button with free zoom on a long press, on any device. There were three icons
  before, chosen from the resize mode, so all seven forced ratios showed the
  same picture: the button told you it was doing something to the shape but
  never which.

  Default is the film's own shape, and a ratio forced on one film belongs to
  that film. It used to be kept for the app as a whole, so squeezing one badly
  authored file into 2.35 left every film afterwards squeezed into 2.35, and the
  only way back was to press the button round the whole cycle.

  Two ways it drew the wrong shape are gone with it. Coming back from the
  settings screen restored the picture from the saved resize mode, which
  describes only the first three steps, so the step said 4:3 while the picture
  said something else and every press after that moved on from a position that
  was not the one on screen. And a forced ratio works by telling the frame what
  shape to be, which nothing ever told it to stop doing — so coming back round
  to Default, Crop or Stretch left the frame still drawing the last ratio forced
  on it, until the player was reopened.
* **Landscape, portrait or auto-rotate** — three plain choices in place of
  "video orientation" and "device orientation", which describe how the player
  decides rather than what you get. The choice survives closing the app now,
  too: it was written by number and read back by position in the list, and the
  two stopped matching when a third mode was added in the middle, so picking
  auto-rotate saved a 3 and came back as whatever happened to be fourth.
* **Live streams play** — HLS live, HLS on demand and DASH, on Media3, on mpv
  and on Auto. The crash above was every one of them.
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

* **Subtitles handed over by another app are kept.** Stremio, Nuvio and the rest
  each pass them in a slightly different shape — an
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
* **It asks which film before it searches.** Turning "Search subtitles
  automatically" off says: do not go and find subtitles without me. Pressing the
  button then went straight to a search anyway, because the film had been
  identified as it opened and the answer was sitting there — so there was no way
  to say which film you wanted subtitles for. The box comes up every time now,
  and **Search again…** sits at the top of the results, because a dialog button
  at the bottom is not where anybody looks when the list is plainly for the
  wrong film.
* **It says so when a subtitle will not load.** ExoPlayer disables the renderer
  internally and the track list goes on reporting the track as selected and
  supported, which is why the picker said "Playing now" over a blank screen.

## Skipping intros and credits

* **Chapters first, databases second.** A file whose chapters name an intro or
  the credits has told you exactly where they are, at the right timings for the
  cut you actually have — read from mpv directly, and parsed out of the Matroska
  container for Media3, which has no chapter API of its own. Only when there are
  none does it ask the internet. Which also means a file full of perfectly good
  chapter marks no longer has to be identified online before it will offer to
  skip anything.
* **Sources merged, not stacked.** Markers from chapters, IntroDB, TheIntroDB,
  SkipDB and AniSkip are clustered by overlap; the most corroborated cluster
  wins, ties break by source reliability, and precise bounds beat placeholder
  ones.
* **The button comes back if you seek into the segment again**, which is the
  moment you are most likely to want it — it used to strike a skipped segment
  off the list for good.
* **Undo lasts three seconds**, by the clock. It was eight seconds of playback,
  which is long enough that the button is still sitting there well after you
  have stopped thinking about it — and counting in playback time means a paused
  film never counts at all, so pausing just after a skip left the offer up for
  as long as you left it.

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
* **A title chosen by hand stays chosen.** Identification reads the file name,
  which is a guess, and correcting it used to last only until the file was
  identified again — which happens on its own, from that same file name, and put
  the guess straight back. What you choose wins from now on, for that file,
  until you change it again.
* **One title, or two.** The card and the subtitle search share a title by
  default, so correcting either corrects both — they are both answers to "what
  is this?". Turned off, they are independent: the card can show one film while
  subtitles are searched for another.
* **How solid the card sits over the picture is a slider**, from nothing at all
  to opaque. Half transparent reads well over most films and badly over a few: a
  dark scene behind pale text, or a busy one behind the synopsis.
* **A second film handed over while the player is still open is the film that
  gets described.** Another app sending a video to a player already in memory
  left everything belonging to the one before it on screen — the title across
  the top, the poster and synopsis, the intro markers, even the subtitles its
  launcher had handed over. A different address playing, and the previous film
  described underneath it.

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
* **Copy link** in the quick panel, for the address of what is playing — which
  any other way means going back to whatever opened the player, and for a link
  handed over by another app that is often nowhere at all.
* **Two pointers on a first run**: where the files are, then where the key goes.
  Everything the player knows about a film comes from one free TMDB key that
  somebody has to paste in, and nothing said so — an empty info card and a
  subtitle search that finds nothing look like a broken player rather than an
  unfinished setup.
* **A remote can reach the second button on a row.** Right from "Show info card"
  lands on its search button, which a plain direction search never does: a
  direction search only offers views beyond the rectangle of the one that has
  the focus, and that button sits inside it.
* **OK presses what it has highlighted.** With the controls hidden the player
  handled every key itself and offered none of them to the view holding the
  focus — right for a player with nothing on it, wrong the moment something is.
  A highlighted skip button looked ready and paused the film instead. Back had
  the same trouble while a first-run pointer was up, and walked out of the
  player: from Android 13 it is not a key event at all, and the registration the
  activity already had never ran either.

## Which APK

One per architecture, plus a universal one that contains all four and is about
four times the size. Almost every phone, tablet and television made since 2017
is **arm64-v8a**. If in doubt, take the universal one.

## Thanks

The README names the three this leans on hardest. Here is the rest of it, so
every borrowing is written down beside what it gave:

* **[Just Player](https://github.com/moneytoo/Player)** by Marcel Dopita — the
  player core, the gesture controls, the file handling and the Android TV
  behaviour. The foundation, still.
* **[just-player-plus](https://github.com/wasky/just-player-plus)** by Michal
  Wolski — the subtitle settings panel, custom subtitle fonts, the Outline &
  shadow edge style, the Medium typeface, MicroDVD and MPL2 support, and the TV
  back-button behaviour.
* **[Morveus/just-player-plus](https://github.com/Morveus/just-player-plus)** —
  subtitle delay applied at render time, which is the reason negative delays
  move embedded MKV subtitles earlier rather than just shortening them.
* **[Just+ Player](https://github.com/just-plus-player/just-plus-player)** — the
  most feature-complete Just Player fork going, and worth a look in its own
  right.
* **[mpvNova](https://github.com/Laskco/mpvNova)** (MIT) — how a launcher's
  subtitles and start position are taken off an intent, and that a
  `content://` handed to a native player has to be turned into something it can
  actually open.
* **[mpvEx](https://github.com/marlboro-advance/mpvEx)** and
  **[mpvRex](https://github.com/sfsakhawat999/mpvRex)** (Apache 2.0) — the shape
  of a release-name parser, itself after
  [kahari-parser](https://github.com/GizmoH2o/kahari-parser); and the decoder
  fallback order that turned out to be the whole of the difference in battery
  and heat on the mpv engine here.
* **[mpvRx](https://github.com/Riteshp2001/mpvRx)** (AGPL, so read rather than
  used) — the several shapes a launching app can put subtitles in, all of which
  now get read. Nothing was copied from it; the idea is theirs and the code here
  is not.
