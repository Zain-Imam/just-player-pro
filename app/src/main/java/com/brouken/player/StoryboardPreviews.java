package com.brouken.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import com.brouken.player.ytdlp.WebVideo;

import okhttp3.Request;
import okhttp3.Response;

// seek previews from the small pictures YouTube makes of every video, a few to a sheet
final class StoryboardPreviews implements SeekFrames {

    // a sheet of 3 x 3 pictures at 320 x 180 is about 2 MB once decoded
    private static final int SHEETS_KEPT = 6;
    private static final long RETRY_AFTER_MS = 15_000;

    private final WebVideo.Storyboard board;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread;
    private final Handler worker;
    private final LruCache<Integer, Bitmap> sheets = new LruCache<>(SHEETS_KEPT);
    private volatile boolean released;
    private volatile long wanted = -1;
    private volatile long failedAt = -1;

    StoryboardPreviews(final WebVideo.Storyboard board) {
        this.board = board;
        thread = new HandlerThread("jpp-storyboard", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public boolean failed() {
        return failedAt >= 0 && SystemClock.uptimeMillis() - failedAt < RETRY_AFTER_MS;
    }

    @Override
    public void request(final long positionMs, final PreviewGrabber.Callback callback) {
        if (released || failed()) {
            return;
        }
        final int perSheet = board.columns * board.rows;
        final int last = board.sheets.size() * perSheet - 1;
        final int picture = (int) Math.min(last, Math.max(0, Math.floor(positionMs / 1000.0 * board.fps)));
        final int sheet = picture / perSheet;
        final Bitmap known = sheets.get(sheet);
        if (known != null) {
            callback.onFrame(positionMs, cut(known, picture % perSheet));
            return;
        }
        wanted = positionMs;
        worker.post(() -> {
            if (released || wanted != positionMs) {
                return;
            }
            Bitmap loaded = sheets.get(sheet);
            if (loaded == null) {
                loaded = download(board.sheets.get(sheet));
                if (loaded == null) {
                    failedAt = SystemClock.uptimeMillis();
                } else {
                    sheets.put(sheet, loaded);
                }
            }
            final Bitmap frame = loaded == null ? null : cut(loaded, picture % perSheet);
            main.post(() -> {
                if (!released && wanted == positionMs) {
                    callback.onFrame(positionMs, frame);
                }
            });
        });
    }

    // the last sheet can be shorter than the rest, so the picture is kept inside it
    private Bitmap cut(final Bitmap sheet, final int index) {
        final int x = Math.min((index % board.columns) * board.width, Math.max(0, sheet.getWidth() - board.width));
        final int y = Math.min((index / board.columns) * board.height, Math.max(0, sheet.getHeight() - board.height));
        return Bitmap.createBitmap(sheet, x, y, Math.min(board.width, sheet.getWidth() - x),
                Math.min(board.height, sheet.getHeight() - y));
    }

    private static Bitmap download(final String url) {
        try (Response response = com.brouken.player.online.Http.client()
                .newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                android.util.Log.i(Utils.TAG, "Storyboard sheet refused: HTTP " + response.code());
                return null;
            }
            final byte[] bytes = response.body().bytes();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            android.util.Log.i(Utils.TAG, "Storyboard sheet failed: " + e);
            return null;
        }
    }

    @Override
    public void cancel() {
        wanted = -1;
    }

    @Override
    public void rest() {
        // the sheets are small; keeping them makes the next drag instant
    }

    @Override
    public void release() {
        released = true;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        main.removeCallbacksAndMessages(null);
    }
}
