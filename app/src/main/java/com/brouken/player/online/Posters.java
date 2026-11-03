package com.brouken.player.online;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Posters {

    private static final ExecutorService WORKER = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8192)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    // in KiB, like the cache size
                    return value.getByteCount() / 1024;
                }
            };

    private static final int TAG_KEY = "posterUrl".hashCode();

    private Posters() {
    }

    @Nullable
    public static String url(@Nullable final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return path.startsWith("http") ? path : Tmdb.IMAGE_BASE + path;
    }

    @Nullable
    public static byte[] fetch(final String url) {
        final byte[] kept = com.brouken.player.ImageCache.read(url);
        if (kept != null) {
            com.brouken.player.Utils.log("Poster from the image cache");
            return kept;
        }
        final byte[] bytes = Http.getBytes(url, null);
        if (bytes != null) {
            com.brouken.player.ImageCache.write(url, bytes);
        }
        return bytes;
    }

    // clear the placeholder's tint and dimming
    private static void show(final ImageView view, final Bitmap bitmap) {
        view.setImageTintList(null);
        view.setAlpha(1f);
        view.setImageBitmap(bitmap);
    }

    // a website's wide thumbnail on the tall poster card: whole across the middle, over a blurred,
    // darkened copy of itself, so it fills the card the way a film poster does
    public static Bitmap asPoster(final Bitmap wide) {
        if (wide.getWidth() <= wide.getHeight()) {
            return wide;
        }
        final int width = 360;
        final int height = 540;
        final Bitmap poster = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        final android.graphics.Canvas canvas = new android.graphics.Canvas(poster);
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        // shrunk to a few pixels and drawn back large, which blurs it
        final int middle = Math.min(wide.getWidth(), wide.getHeight() * width / height);
        final Bitmap blur = Bitmap.createScaledBitmap(Bitmap.createBitmap(wide,
                (wide.getWidth() - middle) / 2, 0, middle, wide.getHeight()), 12, 18, true);
        canvas.drawBitmap(blur, null, new android.graphics.Rect(0, 0, width, height), paint);
        canvas.drawColor(0x8C000000);
        final int fitted = wide.getHeight() * width / wide.getWidth();
        final int top = (height - fitted) / 2;
        canvas.drawBitmap(wide, null, new android.graphics.Rect(0, top, width, top + fitted), paint);
        return poster;
    }

    // keeps a poster still loading from landing on a recycled view
    public static void forget(final ImageView view) {
        view.setTag(TAG_KEY, null);
    }

    public static void load(final ImageView view, @Nullable final String url,
                            final int placeholder) {
        view.setTag(TAG_KEY, url);

        if (url == null) {
            view.setImageResource(placeholder);
            return;
        }

        final Bitmap cached = CACHE.get(url);
        if (cached != null) {
            show(view, cached);
            return;
        }

        view.setImageResource(placeholder);

        WORKER.execute(() -> {
            final byte[] bytes = fetch(url);
            if (bytes == null) {
                return;
            }

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) {
                com.brouken.player.ImageCache.remove(url);
                return;
            }
            final Bitmap shown = url.startsWith("https://image.tmdb.org/") ? bitmap : asPoster(bitmap);
            CACHE.put(url, shown);

            MAIN.post(() -> {
                if (url.equals(view.getTag(TAG_KEY))) {
                    show(view, shown);
                }
            });
        });
    }
}
