# Reference material

Not compiled, not shipped. These are the sources of truth for features being
ported into Just Player Pro, kept here so the intent survives without having to
re-derive it from a device export.

- `auto_profile.lua` — the mpvRx / Mpv-infinity script whose behaviour the
  device-aware buffering feature reproduces natively, for both the Media3 and
  mpv backends. Provided by the app's author (the same person as this fork's).

## What the buffering feature has to reproduce

The script's decision table, read off the source:

| Profile | Condition |
|---|---|
| `device-low` | `/proc/meminfo` MemTotal < 4500 MiB (also the fallback when unreadable) |
| `device-balanced` | 4500–6499 MiB |
| `device-high` | >= 6500 MiB |
| `battery-saver` | level <= 20% AND not charging; cleared at >= 25% or on power |
| `live-stream` | network source AND (no duration AND not seekable), or HLS with no duration, or rtmp/rtsp/udp/srt with no duration |
| `high-bitrate-stream` | network AND not live AND `device-high` AND not battery-saver AND bitrate >= 15 Mbps |

Notes that matter for the port:

- Profiles are re-applied base-first on every file so a previous file's settings
  cannot leak forward.
- Analysis is deliberately deferred ~1.5s after `file-loaded`, because duration,
  seekability and bitrate are not reliable before then. The Android port must
  do the same rather than deciding at prepare time.
- Bitrate is resolved in falling order of trust: selected track demux bitrate,
  then video+audio bitrate properties, then `file-size * 8 / duration`.
- Disk caching is never enabled.

## The Media3 divergence

mpv buffers in bytes of native memory; Media3's `DefaultAllocator` allocates
`byte[]` on the Java heap, which Android caps per app. The byte figures in the
mpv profiles (up to 1 GiB) cannot be transferred to the Media3 backend — it gets
the same tiering expressed in buffer durations with modest byte caps instead.
