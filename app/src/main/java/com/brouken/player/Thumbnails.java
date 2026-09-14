package com.brouken.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.Nullable;

/**
 * The frame you are dragging towards, shown while you drag.
 *
 * Only for a file on the device. A frame is fetched by opening the file a
 * second time and decoding at a keyframe, which over a connection would mean
 * downloading a second copy of the film to look at pictures of it -- so a
 * stream is not offered this at all.
 *
 * Everything happens on one background thread and only the newest request
 * survives: a drag across the bar asks for fifty positions and forty-nine of
 * them are already wrong by the time they could be answered. Frames are kept
 * in a small cache because a drag goes back and forth over the same places.
 */
public final class Thumbnails {

    /** Frames are asked for at keyframes anyway, so nearby requests share one. */
    private static final long BUCKET_MS = 2000;
    private static final int CACHE_ENTRIES = 24;
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;

    public interface Callback {
        void onThumbnail(long positionMs, @Nullable Bitmap bitmap);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> cache = new LruCache<>(CACHE_ENTRIES);

    @Nullable
    private HandlerThread thread;
    @Nullable
    private Handler worker;
    @Nullable
    private MediaMetadataRetriever retriever;

    private volatile boolean released;
    private volatile long wanted = -1;

    /** Whether a preview is possible and asked for. A stream is neither. */
    public static boolean available(final Context context, @Nullable final Uri uri) {
        if (uri == null || Utils.isSupportedNetworkUri(uri)) {
            return false;
        }
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("scrubThumbnails", true);
    }

    public Thumbnails(final Context context, final Uri uri) {
        thread = new HandlerThread("jpp-thumbnails", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            final MediaMetadataRetriever opened = new MediaMetadataRetriever();
            try {
                opened.setDataSource(context, uri);
            } catch (Exception e) {
                // A file the retriever will not open simply has no previews.
                Utils.log("No thumbnails for this file: " + e);
                try {
                    opened.release();
                } catch (Exception ignored) {
                }
                return;
            }
            if (released) {
                try {
                    opened.release();
                } catch (Exception ignored) {
                }
                return;
            }
            retriever = opened;
        });
    }

    /**
     * Ask for the frame at a position. The newest ask wins.
     *
     * A cached frame comes back at once, on this thread, so a drag back over
     * ground it has covered draws without a flicker.
     */
    public void request(final long positionMs, final Callback callback) {
        if (released || worker == null) {
            return;
        }
        final long bucket = positionMs / BUCKET_MS;
        final Bitmap known = cache.get(bucket);
        if (known != null) {
            callback.onThumbnail(positionMs, known);
            return;
        }

        wanted = bucket;
        worker.post(() -> {
            if (released || retriever == null) {
                return;
            }
            // Anything but the latest is already a wrong answer.
            if (wanted != bucket) {
                return;
            }
            Bitmap frame = null;
            try {
                final long timeUs = bucket * BUCKET_MS * 1000;
                if (Build.VERSION.SDK_INT >= 27) {
                    frame = retriever.getScaledFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
                } else {
                    frame = retriever.getFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } catch (Exception e) {
                // A frame that will not decode is one frame, not a failure.
                return;
            }
            if (frame == null || released) {
                return;
            }
            cache.put(bucket, frame);
            final Bitmap delivered = frame;
            main.post(() -> {
                if (!released) {
                    callback.onThumbnail(positionMs, delivered);
                }
            });
        });
    }

    public void release() {
        released = true;
        final Handler handler = worker;
        final HandlerThread openThread = thread;
        worker = null;
        thread = null;
        if (handler != null) {
            handler.post(() -> {
                final MediaMetadataRetriever open = retriever;
                retriever = null;
                if (open != null) {
                    try {
                        open.release();
                    } catch (Exception ignored) {
                    }
                }
                if (openThread != null) {
                    openThread.quitSafely();
                }
            });
        } else if (openThread != null) {
            openThread.quitSafely();
        }
        cache.evictAll();
    }
}
