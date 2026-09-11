package com.brouken.player.online;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

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
                    // In KiB, matching the cache size above, which is in KiB too.
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

    public static void load(final ImageView view, @Nullable final String url,
                            final int placeholder) {
        view.setTag(TAG_KEY, url);

        if (url == null) {
            view.setImageResource(placeholder);
            return;
        }

        final Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        view.setImageResource(placeholder);

        WORKER.execute(() -> {
            final byte[] bytes = Http.getBytes(url, null);
            if (bytes == null) {
                return;
            }

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) {
                return;
            }
            CACHE.put(url, bitmap);

            MAIN.post(() -> {
                // Still the row that asked for it?
                if (url.equals(view.getTag(TAG_KEY))) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }
}
