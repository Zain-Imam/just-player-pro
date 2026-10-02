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

    private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

    public MpvOptions(@Nullable final Uri uri) {
        this.uri = uri;
    }

    public boolean isNetwork() {
        return com.brouken.player.Utils.isSupportedNetworkUri(uri);
    }

    public MpvOptions withHeaders(final java.util.Map<String, String> extra) {
        headers.clear();
        if (extra != null) {
            headers.putAll(extra);
        }
        return this;
    }

    public void applyTo(final MPVLib mpv, final Context context) {
        // mpv splits this list at commas, so commas inside values are escaped;
        // the -append form doesn't work as an option name here
        if (!headers.isEmpty()) {
            final StringBuilder fields = new StringBuilder();
            for (final java.util.Map.Entry<String, String> header : headers.entrySet()) {
                if (fields.length() > 0) {
                    fields.append(",");
                }
                fields.append((header.getKey() + ": " + header.getValue()).replace(",", "\\,"));
            }
            mpv.setOptionString("http-header-fields", fields.toString());
        }

        // otherwise mpv closes the file at the end and replay has nothing to seek in
        mpv.setOptionString("keep-open", "yes");

        mpv.setOptionString("osd-level", "0");
        mpv.setOptionString("osd-bar", "no");
        mpv.setOptionString("osd-on-seek", "no");
        mpv.setOptionString("input-default-bindings", "no");
        mpv.setOptionString("osc", "no");

        mpv.setOptionString("profile", "fast");

        final int priority = Integer.parseInt(PreferenceManager
                .getDefaultSharedPreferences(context)
                .getString("decoderPriority", "1"));
        // zero-copy mediacodec first; auto-safe only offers mediacodec-copy, which
        // costs about twice the CPU
        final String hwdec;
        switch (priority) {
            case 2:  // prefer app decoders
                hwdec = "no";
                break;
            case 0:  // device decoders only
                hwdec = "mediacodec,mediacodec-copy";
                break;
            case 1:
            default:
                hwdec = "mediacodec,mediacodec-copy,no";
                break;
        }
        mpv.setOptionString("hwdec", hwdec);
        // hwdec-codecs stays at mpv's default so rare formats decode in software
        mpv.setOptionString("vd-lavc-threads", "0");

        mpv.setOptionString("framedrop", "decoder");

        // both are GPU-expensive
        mpv.setOptionString("interpolation", "no");
        mpv.setOptionString("deband", "no");

        mpv.setOptionString("vo", "gpu");

        // lets the panel switch to HDR instead of mpv tone-mapping to SDR
        mpv.setOptionString("target-colorspace-hint", "yes");
        // headroom for the volume boost setting
        mpv.setOptionString("volume-max", "200");
        mpv.setOptionString("gpu-context", "android");
        mpv.setOptionString("ao", "audiotrack");

        // subtitles
        mpv.setOptionString("sub-auto", "fuzzy");
        mpv.setOptionString("sub-file-paths", "Subs:subs:Subtitles:subtitles");
        mpv.setOptionString("sub-fix-timing", "yes");
        mpv.setOptionString("sub-ass-override", "scale");
        mpv.setOptionString("embeddedfonts", "yes");

        // same language order as Media3
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

        // otherwise mpv picks the first subtitle track when no language matches
        mpv.setOptionString("subs-fallback", "no");

        // network: mpv's TLS needs its own CA file for https
        final String certificates = CaBundle.path(context);
        if (certificates != null) {
            mpv.setOptionString("tls-verify", "yes");
            mpv.setOptionString("tls-ca-file", certificates);
        }

        // no youtube-dl here; looking for it delays the error by a second
        mpv.setOptionString("ytdl", "no");

        // a failed reconnect after a seek makes FFmpeg keep reading the old position,
        // so retry connect and 5xx errors too, with short waits
        mpv.setOptionString("network-timeout", "20");
        mpv.setOptionString("stream-lavf-o-append", "reconnect=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_streamed=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_on_network_error=1");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_on_http_error=5xx");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_delay_max=5");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_max_retries=5");
        mpv.setOptionString("stream-lavf-o-append", "reconnect_delay_total_max=20");

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
                // small: buffering ahead of a live stream only adds latency
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
