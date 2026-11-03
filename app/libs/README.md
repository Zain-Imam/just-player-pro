The binary ffmpeg extension was build with following decoders:

```
ENABLED_DECODERS=(vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd)
```

Complete [build instructions](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md).

To assemble ``.aar``:

```
./gradlew :extension-ffmpeg:bundleReleaseAar
```

`lib-exoplayer-hls-release.aar` is Maven's `media3-exoplayer-hls` 1.10.0 with only `HlsChunkSource`
recompiled: the 1.10.0 tag plus upstream commit d5f9971ad4 (androidx/media#3161, shipped in 1.10.1).
Without it, a failed playlist or segment load on a stream whose variants are not all selected
(an audio-only variant is enough) crashes the playback thread instead of retrying. Go back to the
Maven module when Media3 moves to 1.10.1 or later.
