# Third-party notices

Just Player Pro is distributed under the GNU General Public License v3.0 (see
`LICENSE`). It ships other people's work alongside its own, and that work keeps
its own licence. This file lists what is inside a release build and where to
get its source.

Nothing here is optional reading for anyone redistributing the app: the licences
below require these notices to travel with the binary.

**The licence texts themselves are in [`licenses/`](licenses/).** Naming a
licence is not the same as providing one — MIT and ISC require their permission
notice in every copy, and Apache-2.0 section 4(a) requires a copy of the licence
to reach every recipient. That folder carries the full text of each, and
[`licenses/README.md`](licenses/README.md) maps every component below to the
licence that covers it.

---

## Shipped inside the APK as native libraries

These arrive through the `dev.jdtech.mpv:libmpv` package, which bundles a built
mpv and the libraries mpv itself uses. They are the `.so` files under `jni/` in
the APK.

| Component | Licence | Source |
|---|---|---|
| **mpv** | LGPL-2.1-or-later (parts GPL-2.0-or-later, depending on build options) | https://github.com/mpv-player/mpv |
| **FFmpeg** — libavcodec, libavformat, libavutil, libavfilter, libavdevice, libswscale, libswresample | LGPL-2.1-or-later (GPL-2.0-or-later if built with `--enable-gpl`) | https://ffmpeg.org/download.html |
| **libass** | ISC | https://github.com/libass/libass |
| **libplacebo** | LGPL-2.1-or-later | https://code.videolan.org/videolan/libplacebo |
| **libmpv Android wrapper** (`dev.jdtech.mpv:libmpv`) | MIT | https://github.com/jarnedemeulemeester/libmpv-android |
| **libc++_shared** (LLVM libc++) | Apache-2.0 with LLVM exception | https://libcxx.llvm.org/ |

**Obtaining the source of the LGPL parts.** The binaries above are taken
unmodified from the published `dev.jdtech.mpv:libmpv` package. Their complete
corresponding source is available from the projects linked above and from that
package's own repository, which documents the exact versions and build flags
used. No modifications to mpv, FFmpeg, libass or libplacebo are made by this
project.

**Replacing them.** The LGPL requires that a user be able to run the app with a
modified version of these libraries. They are separate `.so` files loaded
dynamically, and the app itself is under the GPL with its full source in this
repository, so anyone may rebuild it against their own copies.

---

## Java and Kotlin libraries

| Library | Licence | Source |
|---|---|---|
| **AndroidX Media3 / ExoPlayer** — including the patched `lib-exoplayer`, `lib-ui` and decoder AARs in `app/libs/` | Apache-2.0 | https://github.com/androidx/media |
| **AndroidX** — core, appcompat, preference, recyclerview, coordinatorlayout | Apache-2.0 | https://github.com/androidx/androidx |
| **Material Components for Android** | Apache-2.0 | https://github.com/material-components/material-components-android |
| **OkHttp** | Apache-2.0 | https://github.com/square/okhttp |
| **ZXing** (`core`, for the QR code on the setup screen) | Apache-2.0 | https://github.com/zxing/zxing |
| **chardet4j** | Apache-2.0 | https://github.com/sigpwned/chardet4j |
| **TapTargetView** | Apache-2.0 | https://github.com/KeepSafe/TapTargetView |

## Vendored modules in this repository

Kept in-tree rather than pulled as dependencies, and modified where the player
needed something the published versions do not do.

| Module | Upstream | Licence |
|---|---|---|
| `doubletapplayerview/` (resources) and `app/src/main/java/com/brouken/player/dtpv/` (the Java, translated from the original Kotlin) | [vkay94/DoubleTapPlayerView](https://github.com/vkay94/DoubleTapPlayerView) — Copyright (c) 2019 Viktor Krez | MIT, text in [`licenses/MIT-DoubleTapPlayerView.txt`](licenses/MIT-DoubleTapPlayerView.txt) |
| `android-file-chooser/` | [hedzr/android-file-chooser](https://github.com/hedzr/android-file-chooser) | Apache-2.0, text in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |

## The project this one is built on

| Project | Licence |
|---|---|
| [Just Player](https://github.com/moneytoo/Player) by Marcel Dopita — the fork this project began from | Unlicense (public domain) |

---

## Online services

The app talks to these only when asked, and only with keys the user supplies.
They are services rather than bundled code, and are listed for completeness:
TMDB, OpenSubtitles, SubDL, Wyzie, and any Stremio subtitle addon the user adds.
Each has its own terms, and an API key is issued to the person using it rather
than to this project.
