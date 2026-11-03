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

public final class Frames {

    private static final int CACHE_BYTES =
            (int) Math.min(12L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);

    private static final int TARGET_PX = 160;
    private static final int BETTER_PX = 480;

    // Settings > Home: a sharper frame from a tenth of the way in, not the picture Android keeps
    // for the file, which is often the black opening. Set by Home before it draws
    public static volatile boolean better;

    // three decoders: one is slow, more would take cores playback needs
    private final ExecutorService worker = Background.pool("frames", 3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(@NonNull final String key, @NonNull final Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Map<String, Boolean> hopeless = new ConcurrentHashMap<>();

    // the tag drops late frames meant for a row that has since been recycled
    public void into(@NonNull final Context context, @NonNull final ImageView view,
                     @NonNull final Uri uri, final int fallbackIcon) {
        into(context, view, uri, fallbackIcon, false);
    }

    // card: whole in the middle of a tall card over a blurred copy, as Recent shows a website's video
    public void into(@NonNull final Context context, @NonNull final ImageView view,
                     @NonNull final Uri uri, final int fallbackIcon, final boolean card) {
        final boolean sharper = better;
        final String key = (sharper ? "better:" : "") + (card ? "card:" : "") + uri;
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
            final Bitmap kept = kept(context, uri, sharper);
            if (kept == null) {
                hopeless.put(key, Boolean.TRUE);
                return;
            }
            final Bitmap frame = card ? cardOf(kept) : kept;
            cache.put(key, frame);
            main.post(() -> {
                if (key.equals(view.getTag())) {
                    show(view, frame);
                }
            });
        }));
    }

    private static Bitmap cardOf(final Bitmap frame) {
        try {
            return com.brouken.player.online.Posters.asPoster(frame);
        } catch (RuntimeException e) {
            // an odd frame size: the frame as it is
            return frame;
        }
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
    private static Bitmap kept(final Context context, final Uri uri, final boolean sharper) {
        final String key = diskKey(context, uri, sharper);
        final byte[] bytes = com.brouken.player.ImageCache.read(key);
        if (bytes != null) {
            final Bitmap known = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (known != null) {
                return known;
            }
            com.brouken.player.ImageCache.remove(key);
        }
        final Bitmap frame = sharper ? decode(context, uri, BETTER_PX, true) : load(context, uri);
        if (frame != null) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (frame.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                com.brouken.player.ImageCache.write(key, out.toByteArray());
            }
        }
        return frame;
    }

    // the file's size and date, so a changed file gets a new frame
    private static String diskKey(final Context context, final Uri uri, final boolean sharper) {
        long size = -1;
        long modified = -1;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.MediaStore.MediaColumns.SIZE,
                        android.provider.MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                size = cursor.getLong(0);
                modified = cursor.getLong(1);
            }
        } catch (Exception ignored) {
            // not a media store entry: the address alone
        }
        return "frame:" + (sharper ? BETTER_PX + ":10%" : TARGET_PX) + ":" + size + ":" + modified + ":" + uri;
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
        return decode(context, uri, TARGET_PX, false);
    }

    @Nullable
    private static Bitmap decode(final Context context, final Uri uri, final int px, final boolean tenth) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            long at = -1;
            if (tenth) {
                final String length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                at = length == null ? -1 : Long.parseLong(length) * 100;
            }
            Bitmap frame = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                // scaled while decoding, so a 4K frame never sits in memory
                frame = retriever.getScaledFrameAtTime(at,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px);
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
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
