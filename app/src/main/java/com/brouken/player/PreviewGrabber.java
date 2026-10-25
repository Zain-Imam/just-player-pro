package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.MediaExtractorCompat;
import androidx.media3.extractor.DefaultExtractorsFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// seek previews from a second reader: the keyframe first, then the exact frame
final class PreviewGrabber {

    interface Callback {
        // keyframe first, then the exact frame; null when nothing came for the time
        void onFrame(long positionMs, @Nullable Bitmap frame);
    }

    private static final int WIDTH = 320;
    private static final int CACHE_ENTRIES = 40;
    private static final long FRAME_TIMEOUT_MS = 6_000;
    // the walk from the keyframe to the exact frame; a slow decoder keeps the keyframe
    private static final long EXACT_BUDGET_MS = 4_000;
    private static final long CLOSE_ENOUGH_US = 20_000;
    // the exact walk waits for rest: decoding runs outside this low-priority thread
    private static final long REST_MS = 600;
    private static final long RETRY_AFTER_MS = 15_000;
    // a decoder holds back at most 16 pictures, so twice that with none out is a dud
    private static final int NO_PICTURE_SAMPLES = 32;
    private static final long NO_PICTURE_MS = 2_000;
    // online each jump can take seconds, so a jump waits for the drag to pause
    private static final long SLOW_SEEK_MS = 800;
    private static final long SETTLE_MS = 300;
    // a keyframe this far before the wanted time means the file has no seek index
    private static final long NO_INDEX_GAP_US = 60_000_000;
    private static final int TIMEOUT_MS = 15_000;

    // Dolby Vision profile 5 decodes as HEVC but in its own colours
    private static final int DOLBY_VISION_PROFILE_5 = 0x20;

    private final Context context;
    private final Uri uri;
    private final Map<String, String> headers;
    private final long nearMs;
    private final boolean mayUseHardware;
    private final boolean hardwareBrokenForTests;

    private final Handler main = new Handler(Looper.getMainLooper());
    // exact frames by the second they show, keyframes by their own time
    private final LruCache<Long, Bitmap> exact = new LruCache<>(CACHE_ENTRIES);
    private final LruCache<Long, Bitmap> keyframes = new LruCache<>(CACHE_ENTRIES);
    private final HandlerThread thread;
    private final Handler worker;

    private volatile boolean released;
    // no frames at all for this file, whatever is tried
    private volatile boolean hopeless;
    private volatile long failedAt = -1;
    private volatile long wanted = -1;
    private volatile boolean slowSeeks;

    // worker thread only
    private boolean hardwareCodec;
    private boolean softwareOnly;
    private int videoTrack = -1;
    @Nullable
    private String videoMime;
    @Nullable
    private MediaExtractorCompat extractor;
    @Nullable
    private MediaCodec codec;

    PreviewGrabber(final Context context, final Uri uri, final Map<String, String> headers,
                   final long durationMs, final boolean mayUseHardware) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.headers = headers;
        // a frame for a time this close to the one now wanted is still worth showing
        this.nearMs = Math.max(2_000, durationMs / 300);
        this.mayUseHardware = mayUseHardware;
        hardwareBrokenForTests = BuildConfig.DEBUG && androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context).getBoolean("debugPreviewHardwareBroken", false);
        thread = new HandlerThread("jpp-previews", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    boolean failed() {
        return hopeless
                || (failedAt >= 0 && SystemClock.uptimeMillis() - failedAt < RETRY_AFTER_MS);
    }

    // a cached exact frame comes back at once on the calling thread
    void request(final long positionMs, final Callback callback) {
        if (released || failed()) {
            return;
        }
        final long position = Math.max(0, positionMs);
        final Bitmap known = exact.get(position / 1000);
        if (known != null) {
            callback.onFrame(positionMs, known);
            return;
        }
        wanted = position;
        worker.postDelayed(() -> {
            if (released || wanted != position) {
                return;
            }
            final boolean[] any = {false};
            grab(position, (frame, isExact) -> {
                if (frame == null) {
                    return;
                }
                any[0] = true;
                if (isExact) {
                    exact.put(position / 1000, frame);
                }
                main.post(() -> {
                    if (!released && Math.abs(wanted - position) <= nearMs) {
                        callback.onFrame(positionMs, frame);
                    }
                });
            });
            if (!any[0]) {
                main.post(() -> {
                    if (!released && wanted == position) {
                        callback.onFrame(positionMs, null);
                    }
                });
            }
        }, slowSeeks ? SETTLE_MS : 0);
    }

    // the drag is over: no walk on to a frame nobody will see
    void cancel() {
        wanted = -1;
    }

    // online the connection closes too, so the host sees only playback's
    void rest() {
        exact.evictAll();
        keyframes.evictAll();
        worker.post(Utils.isSupportedNetworkUri(uri) ? this::closeAll : this::closeDecoder);
    }

    void release() {
        released = true;
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            closeAll();
            thread.quitSafely();
        });
        exact.evictAll();
        keyframes.evictAll();
    }

    private interface Sink {
        void frame(@Nullable Bitmap frame, boolean isExact);
    }

    private void grab(final long positionMs, final Sink sink) {
        if (grabWith(positionMs, sink)) {
            grabWith(positionMs, sink);
        }
    }

    // true when the decoder was swapped and the frame is worth asking again
    private boolean grabWith(final long positionMs, final Sink sink) {
        if (!opened()) {
            return false;
        }
        if (codec == null) {
            codec = newDecoder();
            if (codec == null) {
                // maybe all in use for now
                failedAt = SystemClock.uptimeMillis();
                android.util.Log.i(Utils.TAG, "No seek previews for now: no decoder for " + videoMime);
                return false;
            }
        }
        final MediaExtractorCompat source = extractor;
        final MediaCodec decoder = codec;
        final long target = positionMs * 1000;
        try {
            // TS and others only estimate seek points and can land late, so step back
            long key = -1;
            final long seeking = SystemClock.uptimeMillis();
            for (int tries = 0; tries < 5; tries++) {
                source.seekTo(Math.max(0, target - (tries == 0 ? 0 : 1_000_000L << tries)),
                        MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC);
                key = source.getSampleTime();
                if (key < 0 || key <= target + CLOSE_ENOUGH_US) {
                    break;
                }
            }
            final long took = SystemClock.uptimeMillis() - seeking;
            slowSeeks = took > SLOW_SEEK_MS;
            Utils.log("Preview reader: seek for " + positionMs + "ms took " + took + "ms");
            if (key < 0) {
                return false;
            }
            if (target - key > NO_INDEX_GAP_US) {
                // every seek lands at the start: the film's opening would show for any time
                hopeless = true;
                android.util.Log.i(Utils.TAG, "No seek previews for this file: it has no seek index");
                return false;
            }
            decoder.flush();

            Bitmap first = keyframes.get(key);
            if (first != null) {
                sink.frame(first, key >= target - CLOSE_ENOUGH_US);
                if (key >= target - CLOSE_ENOUGH_US || !rested(positionMs)) {
                    return false;
                }
            }
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            final long started = SystemClock.uptimeMillis();
            long deadline = started + (first != null ? EXACT_BUDGET_MS : FRAME_TIMEOUT_MS);
            boolean inputEnded = false;
            boolean anyPicture = false;
            int unreadable = 0;
            int fed = 0;
            long refusedSince = 0;
            while (SystemClock.uptimeMillis() < deadline) {
                // the keyframe is worth finishing for a nearby drag; the walk on is not
                if (released || (first != null ? wanted != positionMs
                        : Math.abs(wanted - positionMs) > nearMs)) {
                    return false;
                }
                // some hardware decoders give nothing without a surface; counted in samples fed
                if (!anyPicture && (fed >= NO_PICTURE_SAMPLES || (refusedSince > 0
                        && SystemClock.uptimeMillis() - refusedSince > NO_PICTURE_MS))) {
                    return toSoftware("no picture");
                }
                if (!inputEnded) {
                    final int in = decoder.dequeueInputBuffer(10_000);
                    if (in < 0) {
                        if (refusedSince == 0) {
                            refusedSince = SystemClock.uptimeMillis();
                        }
                    } else {
                        refusedSince = 0;
                        final ByteBuffer buffer = decoder.getInputBuffer(in);
                        final int size = buffer == null ? -1 : source.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, source.getSampleTime(), 0);
                            source.advance();
                            fed++;
                        }
                    }
                }
                final int out = decoder.dequeueOutputBuffer(info, 10_000);
                if (out < 0) {
                    continue;
                }
                if (info.size > 0) {
                    anyPicture = true;
                }
                final boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                final boolean reached = last || info.presentationTimeUs >= target - CLOSE_ENOUGH_US;
                Bitmap frame = null;
                // only the frames shown are turned into pictures; the rest just pass
                if (info.size > 0 && (first == null || reached)) {
                    try (Image image = decoder.getOutputImage(out)) {
                        frame = image == null || (hardwareBrokenForTests && hardwareCodec)
                                ? null : toBitmap(image);
                    }
                }
                decoder.releaseOutputBuffer(out, false);
                if (frame == null && info.size > 0 && (first == null || reached) && ++unreadable >= 2) {
                    // pictures in a layout this reader does not know
                    return toSoftware("no readable picture");
                }
                if (first == null && frame != null) {
                    first = frame;
                    keyframes.put(key, frame);
                    sink.frame(frame, reached);
                    if (reached) {
                        Utils.log("Preview exact frame at " + info.presentationTimeUs / 1000
                                + "ms for " + positionMs + "ms");
                        return false;
                    }
                    if (!rested(positionMs)) {
                        return false;
                    }
                    deadline = Math.min(deadline, SystemClock.uptimeMillis() + EXACT_BUDGET_MS);
                } else if (reached) {
                    if (frame != null) {
                        Utils.log("Preview exact frame at " + info.presentationTimeUs / 1000
                                + "ms for " + positionMs + "ms");
                        sink.frame(frame, true);
                    }
                    return false;
                }
            }
            Utils.log("Preview kept the keyframe at " + key / 1000 + "ms for " + positionMs
                    + "ms: the exact frame took too long");
        } catch (Exception e) {
            Utils.log("Preview frame failed: " + e);
            return toSoftware("an error");
        }
        return false;
    }

    // once per film; false when already on software or none would start
    private boolean toSoftware(final String what) {
        if (!hardwareCodec || softwareOnly || videoMime == null || videoTrack < 0 || extractor == null) {
            return false;
        }
        android.util.Log.i(Utils.TAG, "Preview reader: " + what + " from the hardware decoder, trying software");
        softwareOnly = true;
        closeDecoder();
        codec = newDecoder();
        Utils.log("Preview reader: now on " + (codec == null ? "no decoder" : codec.getName()));
        return codec != null;
    }

    private boolean opened() {
        if (extractor != null) {
            return true;
        }
        if (failed()) {
            return false;
        }
        final long started = SystemClock.uptimeMillis();
        if (!open()) {
            failedAt = SystemClock.uptimeMillis();
            closeAll();
            return false;
        }
        failedAt = -1;
        final long took = SystemClock.uptimeMillis() - started;
        // a slow index means slow jumps, before the first one shows it
        slowSeeks = took > SLOW_SEEK_MS;
        Utils.log("Preview reader: index read in " + took + "ms");
        return true;
    }

    @Nullable
    private MediaCodec newDecoder() {
        if (extractor == null || videoMime == null) {
            return null;
        }
        final MediaFormat format = extractor.getTrackFormat(videoTrack);
        format.setString(MediaFormat.KEY_MIME, videoMime);
        return decoderFor(videoMime, format);
    }

    private boolean rested(final long positionMs) {
        final long until = SystemClock.uptimeMillis() + REST_MS;
        while (SystemClock.uptimeMillis() < until) {
            if (released || wanted != positionMs) {
                return false;
            }
            SystemClock.sleep(50);
        }
        return !released && wanted == positionMs;
    }

    private boolean open() {
        try {
            final DataSource.Factory factory;
            if (Utils.isSupportedNetworkUri(uri)) {
                final DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                        // as mpv does: many hosts send an http link on to https
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(TIMEOUT_MS)
                        .setReadTimeoutMs(TIMEOUT_MS);
                // the data source writes its own User-Agent over these, so set it directly
                for (final Map.Entry<String, String> header : headers.entrySet()) {
                    if ("User-Agent".equalsIgnoreCase(header.getKey())) {
                        http.setUserAgent(header.getValue());
                    }
                }
                http.setDefaultRequestProperties(headers);
                factory = http;
            } else {
                factory = new DefaultDataSource.Factory(context);
            }
            final MediaExtractorCompat source =
                    new MediaExtractorCompat(new DefaultExtractorsFactory(), factory);
            extractor = source;
            source.setDataSource(uri, 0);

            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < source.getTrackCount(); i++) {
                final MediaFormat candidate = source.getTrackFormat(i);
                final String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0) {
                hopeless = true;
                android.util.Log.i(Utils.TAG, "No seek previews for this file: no video track the reader knows");
                return false;
            }
            source.selectTrack(track);

            String mime = format.getString(MediaFormat.KEY_MIME);
            if (androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(mime)) {
                if (format.containsKey(MediaFormat.KEY_PROFILE)
                        && format.getInteger(MediaFormat.KEY_PROFILE) == DOLBY_VISION_PROFILE_5) {
                    hopeless = true;
                    android.util.Log.i(Utils.TAG, "No seek previews for this file: Dolby Vision profile 5");
                    return false;
                }
                // the other profiles carry an HEVC picture underneath
                mime = androidx.media3.common.MimeTypes.VIDEO_H265;
            }
            videoMime = mime;
            videoTrack = track;
            return true;
        } catch (Exception e) {
            Utils.log("No seek previews for this file: " + e);
            // the class only: the message may hold the link, and links hold tokens
            android.util.Log.i(Utils.TAG, "No seek previews for this file: " + e.getClass().getSimpleName());
            return false;
        }
    }

    // hardware first where allowed; a TV may have only one, and playback holds it
    @Nullable
    private MediaCodec decoderFor(final String mime, final MediaFormat format) {
        final List<String> software = new ArrayList<>();
        final List<String> hardware = new ArrayList<>();
        for (final MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (final String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    (isSoftware(info) ? software : hardware).add(info.getName());
                    break;
                }
            }
        }
        final List<String> order = new ArrayList<>();
        if (mayUseHardware && !softwareOnly) {
            order.addAll(hardware);
        }
        order.addAll(software);
        for (final String name : order) {
            MediaCodec candidate = null;
            try {
                candidate = MediaCodec.createByCodecName(name);
                candidate.configure(format, null, null, 0);
                candidate.start();
                hardwareCodec = hardware.contains(name);
                return candidate;
            } catch (Exception e) {
                if (candidate != null) {
                    candidate.release();
                }
            }
        }
        return null;
    }

    private static boolean isSoftware(final MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isSoftwareOnly();
        }
        final String name = info.getName();
        return name.startsWith("OMX.google.") || name.startsWith("c2.android.");
    }

    private void closeDecoder() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
            codec = null;
        }
    }

    private void closeAll() {
        closeDecoder();
        if (extractor != null) {
            extractor.release();
            extractor = null;
        }
    }

    // a small RGB copy of the decoded picture, by nearest sample: it is a thumbnail
    @Nullable
    private static Bitmap toBitmap(final Image image) {
        final int format = image.getFormat();
        final boolean tenBit = Build.VERSION.SDK_INT >= 33 && format == ImageFormat.YCBCR_P010;
        if (format != ImageFormat.YUV_420_888 && !tenBit) {
            return null;
        }
        final Rect crop = image.getCropRect();
        final int cw = crop.width();
        final int ch = crop.height();
        if (cw <= 0 || ch <= 0) {
            return null;
        }
        final int tw = WIDTH;
        final int th = Math.max(1, Math.round(WIDTH * (float) ch / cw));

        final Image.Plane[] planes = image.getPlanes();
        final ByteBuffer y = planes[0].getBuffer();
        final ByteBuffer u = planes[1].getBuffer();
        final ByteBuffer v = planes[2].getBuffer();
        final int yRow = planes[0].getRowStride();
        final int yPixel = planes[0].getPixelStride();
        final int uRow = planes[1].getRowStride();
        final int uPixel = planes[1].getPixelStride();
        final int vRow = planes[2].getRowStride();
        final int vPixel = planes[2].getPixelStride();
        // P010 keeps each sample in the top bits of two bytes; the high byte is enough here
        final int high = tenBit ? 1 : 0;

        final int[] argb = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            final int sy = crop.top + ty * ch / th;
            for (int tx = 0; tx < tw; tx++) {
                final int sx = crop.left + tx * cw / tw;
                final int luma = y.get(sy * yRow + sx * yPixel + high) & 0xFF;
                final int cb = (u.get((sy / 2) * uRow + (sx / 2) * uPixel + high) & 0xFF) - 128;
                final int cr = (v.get((sy / 2) * vRow + (sx / 2) * vPixel + high) & 0xFF) - 128;
                // BT.709, limited range
                final float l = 1.164f * (luma - 16);
                final int r = clamp(l + 1.793f * cr);
                final int g = clamp(l - 0.213f * cb - 0.533f * cr);
                final int b = clamp(l + 2.112f * cb);
                argb[ty * tw + tx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, tw, th, Bitmap.Config.ARGB_8888);
    }

    private static int clamp(final float value) {
        return value < 0 ? 0 : value > 255 ? 255 : (int) value;
    }
}
