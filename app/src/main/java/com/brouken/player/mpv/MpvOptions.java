package com.brouken.player.mpv;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.brouken.player.BufferProfile;

import dev.jdtech.mpv.MPVLib;

public final class MpvOptions {

    @Nullable
    private final Uri uri;

    /** Headers the launching app asked to be sent with the stream. */
    private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

    public MpvOptions(@Nullable final Uri uri) {
        this.uri = uri;
    }

    public MpvOptions withHeaders(final java.util.Map<String, String> extra) {
        headers.clear();
        if (extra != null) {
            headers.putAll(extra);
        }
        return this;
    }

    public void applyTo(final MPVLib mpv, final Context context) {
        // -- core performance and battery -----------------------------------

        // The same request headers Media3 is given, in mpv's own form: a
        // stream behind a token plays on one engine and not the other if only
        // one of them is told.
        if (!headers.isEmpty()) {
            final StringBuilder fields = new StringBuilder();
            for (final java.util.Map.Entry<String, String> header : headers.entrySet()) {
                if (fields.length() > 0) {
                    fields.append(",");
                }
                fields.append(header.getKey()).append(": ").append(header.getValue());
            }
            mpv.setOptionString("http-header-fields", fields.toString());
        }

        mpv.setOptionString("osd-level", "0");
        mpv.setOptionString("osd-bar", "no");
        mpv.setOptionString("osd-on-seek", "no");
        mpv.setOptionString("input-default-bindings", "no");
        mpv.setOptionString("osc", "no");

        // mpv's lightweight profile: less work on weak devices, better battery
        // on strong ones.
        mpv.setOptionString("profile", "fast");

        // Hardware decoding where the device supports it, falling back on its
        // own when it does not.
        final int priority = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString("decoderPriority", "1"));
        final String hwdec;
        switch (priority) {
            case 2:  // prefer app decoders
                hwdec = "no";
                break;
            case 0:  // device decoders only
                hwdec = "mediacodec";
                break;
            case 1:
            default:
                hwdec = "auto-safe";
                break;
        }
        mpv.setOptionString("hwdec", hwdec);
        mpv.setOptionString("vd-lavc-threads", "0");

        mpv.setOptionString("framedrop", "decoder");

        // Both are GPU-expensive and neither is worth it on the hardware this
        // engine exists to rescue.
        mpv.setOptionString("interpolation", "no");
        mpv.setOptionString("deband", "no");

        mpv.setOptionString("vo", "gpu");

        /*
         * Tell the display what it is being sent, so HDR stays HDR.
         *
         * Media3 hands an HDR stream to the panel, which turns its own HDR mode
         * on and drives the backlight harder. mpv, left to itself, tone-maps
         * HDR down to SDR and renders that — the same film then looks flatter
         * and dimmer than it does on the other engine, which is exactly the
         * difference that gets noticed. Signalling the colour space lets the
         * panel do what it does under Media3.
         *
         * Measured rather than assumed: on SDR content the two engines come out
         * within half a percent of each other, so this is the only case where
         * the brightness ever actually differs.
         */
        mpv.setOptionString("target-colorspace-hint", "yes");
        // Headroom for the volume boost setting; the player only goes above 100
        // when that is switched on.
        mpv.setOptionString("volume-max", "200");
        mpv.setOptionString("gpu-context", "android");
        mpv.setOptionString("ao", "audiotrack");

        // -- subtitles ------------------------------------------------------

        // The whole reason someone switches to this engine: real libass
        // rendering, with the file's own positioning and effects kept.
        mpv.setOptionString("sub-auto", "fuzzy");
        mpv.setOptionString("sub-file-paths", "Subs:subs:Subtitles:subtitles");
        mpv.setOptionString("sub-fix-timing", "yes");
        mpv.setOptionString("sub-ass-override", "scale");
        mpv.setOptionString("embeddedfonts", "yes");

        // Both orders come from the same place the other engine reads, so a
        // file opens on the same audio track and the same subtitles either way.
        final String slang = com.brouken.player.Languages.forMpv(
                com.brouken.player.Languages.subtitle(context));
        if (!slang.isEmpty()) {
            mpv.setOptionString("slang", slang);
        }

        final String alang = com.brouken.player.Languages.forMpv(
                com.brouken.player.Languages.audio(context));
        if (!alang.isEmpty()) {
            mpv.setOptionString("alang", alang);
        }

        // When nothing in the order above matches, pick nothing — mpv would
        // otherwise fall back to whichever subtitle track came first, which is
        // a track the other engine would have left alone.
        mpv.setOptionString("subs-fallback", "no");

        // -- network --------------------------------------------------------

        mpv.setOptionString("network-timeout", "30");
        mpv.setOptionString("stream-lavf-o-append", "reconnect=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_streamed=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_delay_max=30");

        // -- cache ----------------------------------------------------------

        applyBuffering(mpv, context);
    }

    private void applyBuffering(final MPVLib mpv, final Context context) {
        final boolean adaptive = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("adaptiveBuffering", true);

        mpv.setOptionString("cache", "auto");
        mpv.setOptionString("cache-on-disk", "no");
        mpv.setOptionString("demuxer-donate-buffer", "yes");
        mpv.setOptionString("cache-pause", "yes");
        mpv.setOptionString("cache-pause-initial", "no");

        if (!adaptive) {
            // The conf's own safe fallback, which is what the file uses before
            // any profile is chosen.
            mpv.setOptionString("demuxer-max-bytes", "256MiB");
            mpv.setOptionString("demuxer-max-back-bytes", "64MiB");
            mpv.setOptionString("cache-pause-wait", "3");
            return;
        }

        final BufferProfile.Tier tier = BufferProfile.tierFor(context, uri);

        final String forward;
        final String backward;
        final String pauseWait;

        switch (tier) {
            case HIGH:
                forward = "768MiB";
                backward = "192MiB";
                pauseWait = "4";
                break;
            case BALANCED:
                forward = "512MiB";
                backward = "128MiB";
                pauseWait = "3";
                break;
            case LIVE:
                // Small on purpose: buffering ahead of a live stream is just
                // latency behind the broadcast.
                forward = "64MiB";
                backward = "16MiB";
                pauseWait = "1";
                mpv.setOptionString("cache-secs", "10");
                break;
            case BATTERY_SAVER:
            case LOW:
            default:
                forward = "256MiB";
                backward = "64MiB";
                pauseWait = "2";
                break;
        }

        mpv.setOptionString("demuxer-max-bytes", forward);
        mpv.setOptionString("demuxer-max-back-bytes", backward);
        mpv.setOptionString("cache-pause-wait", pauseWait);
    }
}
