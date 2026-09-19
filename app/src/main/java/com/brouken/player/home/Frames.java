package com.brouken.player.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.brouken.player.Background;
import com.brouken.player.Utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * One frame out of each video, for the lists.
 *
 * <p>This is the expensive part of a screen like this and the reason browsers
 * of this kind feel slow, so the rules are strict: never on the main thread,
 * never twice for the same file, never at all for a row that has scrolled away,
 * and never for anything that is not on this device.
 *
 * <p>Two ways of getting a frame, in order of what they cost. From Android 10
 * the media store keeps thumbnails of its own and hands one over without
 * opening the film at all. Below that, and where it has none, the frame is
 * decoded — which is the slow path, and the reason it happens on a small pool of
 * low-priority threads rather than as fast as the list can ask.
 *
 * <p>A file with no frame to give is remembered as such, so a folder of audio
 * or of broken files is not re-opened every time it scrolls past.
 */
public final class Frames {

    /** Roughly an eighth of what this process is allowed, which is plenty. */
    private static final int CACHE_BYTES =
            (int) Math.min(12L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);

    private static final int TARGET_PX = 160;

    /*
     * Three at a time. One was noticeably slow -- the fifth row in a folder
     * waited about five seconds for its picture while the device had cores
     * doing nothing -- and more than a handful would take cores the film needs.
     */
    private final ExecutorService worker = Background.pool("frames", 3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(@NonNull final String key, @NonNull final Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Files that have already been asked and had nothing to give. */
    private final Map<String, Boolean> hopeless = new ConcurrentHashMap<>();

    /**
     * Fill this view with this file's frame, or leave it as it is.
     *
     * <p>The view is tagged with what was asked for. A recycled row asks for a
     * different file and replaces the tag, so a frame that arrives late finds
     * the tag changed and is quietly dropped — without that, scrolling quickly
     * leaves the wrong picture beside half the names in the list.
     */
    public void into(@NonNull final Context context, @NonNull final ImageView view,
                     @NonNull final Uri uri, final int fallbackIcon) {
        final String key = uri.toString();
        view.setTag(key);

        final Bitmap known = cache.get(key);
        if (known != null) {
            show(view, known);
            return;
        }

        // Back to the plain icon while there is nothing better, so a recycled
        // row never shows the last file's picture.
        placeholder(context, view, fallbackIcon);

        if (Boolean.TRUE.equals(hopeless.get(key))) {
            return;
        }

        worker.execute(Background.safely(() -> {
            final Bitmap frame = load(context, uri);
            if (frame == null) {
                hopeless.put(key, Boolean.TRUE);
                return;
            }
            cache.put(key, frame);
            main.post(() -> {
                if (key.equals(view.getTag())) {
                    show(view, frame);
                }
            });
        }));
    }

    /**
     * Filled edge to edge, at full strength, and in its own colours.
     *
     * <p>Clearing the tint is the whole of that last part. A tint set on an
     * ImageView is applied to whatever drawable it is holding, and the one here
     * is meant for the grey placeholder icon — so every frame that replaced the
     * icon was being painted through it, and a folder of photographs came out
     * looking like a folder of grey rectangles. It is put back with the icon
     * and taken away with the frame.
     */
    private static void show(final ImageView view, final Bitmap frame) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageTintList(null);
        view.setImageBitmap(frame);
        view.setAlpha(1f);
    }

    /** The icon a row wears until its frame arrives, or instead of one. */
    public static void placeholder(final Context context, final ImageView view,
                                   final int icon) {
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(icon);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(secondary(context)));
        view.setAlpha(0.45f);
    }

    /** The colour the theme uses for text that is not the main thing. */
    private static int secondary(final Context context) {
        final android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, value, true);
        if (value.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    /** Let go of everything, for a screen that is going away. */
    public void close() {
        worker.shutdownNow();
        cache.evictAll();
    }

    @Nullable
    private static Bitmap load(final Context context, final Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // The store's own thumbnail, where it has one: no decoding, no
                // opening the film, and already about the right size.
                return context.getContentResolver()
                        .loadThumbnail(uri, new Size(TARGET_PX, TARGET_PX), null);
            } catch (Exception ignored) {
                // No thumbnail for this one. Fall through and decode a frame.
            }
        }
        return decode(context, uri);
    }

    @Nullable
    private static Bitmap decode(final Context context, final Uri uri) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // Scaled while it is decoded rather than afterwards, which is
                // the difference between a thumbnail and a full 4K frame in
                // memory for every row of a folder.
                frame = retriever.getScaledFrameAtTime(-1,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX);
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return frame;
        } catch (Exception error) {
            Utils.log("No frame for " + uri.getLastPathSegment() + ": " + error);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }
}
