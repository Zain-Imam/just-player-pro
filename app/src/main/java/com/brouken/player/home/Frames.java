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

// Thumbnail frames for local videos: the media store's own thumbnail on
// Android 10+, otherwise a decoded frame on a small background pool.
public final class Frames {

    // 12 MB or an eighth of the heap, whichever is smaller
    private static final int CACHE_BYTES =
            (int) Math.min(12L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);

    private static final int TARGET_PX = 160;

    // three decoders: one is slow, more would take cores playback needs
    private final ExecutorService worker = Background.pool("frames", 3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(@NonNull final String key, @NonNull final Bitmap value) {
            return value.getByteCount();
        }
    };

    /** Files that had no frame to give, so they are not opened again. */
    private final Map<String, Boolean> hopeless = new ConcurrentHashMap<>();

    // the tag drops late frames meant for a row that has since been recycled
    public void into(@NonNull final Context context, @NonNull final ImageView view,
                     @NonNull final Uri uri, final int fallbackIcon) {
        final String key = uri.toString();
        view.setTag(key);

        final Bitmap known = cache.get(key);
        if (known != null) {
            show(view, known);
            return;
        }

        // so a recycled row never shows the last file's picture
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

    // clear the placeholder's tint, or it is applied to the frame too
    private static void show(final ImageView view, final Bitmap frame) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setImageTintList(null);
        view.setImageBitmap(frame);
        view.setAlpha(1f);
    }

    public static void placeholder(final Context context, final ImageView view,
                                   final int icon) {
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(icon);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(secondary(context)));
        view.setAlpha(0.45f);
    }

    private static int secondary(final Context context) {
        final android.util.TypedValue value = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, value, true);
        if (value.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(context, value.resourceId);
        }
        return value.data;
    }

    public void close() {
        worker.shutdownNow();
        cache.evictAll();
    }

    @Nullable
    private static Bitmap load(final Context context, final Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.getContentResolver()
                        .loadThumbnail(uri, new Size(TARGET_PX, TARGET_PX), null);
            } catch (Exception ignored) {
                // no thumbnail: decode a frame instead
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
                // scaled while decoding, so a 4K frame never sits in memory
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
