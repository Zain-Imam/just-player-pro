package com.brouken.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.sigpwned.chardet4j.Chardet;
import com.sigpwned.chardet4j.io.DecodedInputStreamReader;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SubtitleConverter {

    private volatile OkHttpClient okHttpClient;

    public List<Uri> convertSubtitles(Context context, List<Uri> uris) {
        CountDownLatch countDownLatch = new CountDownLatch(uris.size());
        Uri[] results = new Uri[uris.size()];

        for (int i = 0; i < uris.size(); i++) {
            convertSubtitle(context, countDownLatch, results, i, uris.get(i));
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }

        return Arrays.asList(results);
    }

    private void convertSubtitle(Context context, CountDownLatch countDownLatch, Uri[] results, int positionOnResults, Uri sourceUri) {
        String scheme = sourceUri.getScheme();
        if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
            convertSubtitleFromHttp(context, countDownLatch, results, positionOnResults, sourceUri);
            return;
        }

        /*
         * A path an app hands over is not always a path this app may open.
         *
         * Some launchers pass a subtitle as file:///sdcard/… rather than as a
         * content URI. Under scoped storage a .srt on shared storage is not a
         * media file, so there is no permission for it: mpv, which opens the
         * path itself, answers "Permission denied" and the subtitle silently
         * never appears, while Media3 lists the track and then finds nothing in
         * it. Neither says why.
         *
         * The content resolver honours whatever the intent granted, so it is
         * asked first, and what it gives back is copied somewhere this app can
         * certainly read. A few kilobytes, once, and both engines can open it.
         */
        if ("file".equals(scheme) && !canReadDirectly(sourceUri)) {
            final Uri copied = copyIntoCache(context, sourceUri);
            results[positionOnResults] = copied != null ? copied : sourceUri;
            countDownLatch.countDown();
            return;
        }

        results[positionOnResults] = sourceUri;
        countDownLatch.countDown();
    }

    private static boolean canReadDirectly(final Uri uri) {
        final String path = uri.getPath();
        if (path == null) {
            return false;
        }
        try {
            return new File(path).canRead();
        } catch (Exception e) {
            return false;
        }
    }

    private Uri copyIntoCache(final Context context, final Uri sourceUri) {
        final String path = sourceUri.getPath();
        final String name = path == null ? "handed-over.srt" : new File(path).getName();
        try (java.io.InputStream in = context.getContentResolver().openInputStream(sourceUri)) {
            if (in == null) {
                return null;
            }
            final File dir = new File(context.getCacheDir(), "subtitles");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            final File out = new File(dir, System.currentTimeMillis() + "-" + name);
            try (java.io.OutputStream sink = new java.io.FileOutputStream(out)) {
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    sink.write(chunk, 0, read);
                }
            }
            return out.length() > 0 ? Uri.fromFile(out) : null;
        } catch (Exception e) {
            // No permission for it by either route; the engines will say so.
            Log.w("SubtitleConverter", "Could not copy a handed-over subtitle: " + e);
            return null;
        }
    }

    private void convertSubtitleFromHttp(Context context, CountDownLatch countDownLatch, Uri[] results, int positionOnResults, Uri sourceUri) {
        new Thread(() -> {
            OkHttpClient client = getOrCreateOkHttpClient();

            Request request = new Request.Builder()
                    .url(sourceUri.toString())
                    .build();

            Uri convertedUri = sourceUri;

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    ResponseBody responseBody = response.body();
                    //noinspection DataFlowIssue
                    try (DecodedInputStreamReader reader = Chardet.decode(responseBody.byteStream(), StandardCharsets.UTF_8)) {
                        File subtitleCacheDir = getSubtitleCacheDir(context);
                        String fileName = Utils.getFileName(context, sourceUri, true);
                        File subtitleFile = new File(subtitleCacheDir, fileName);
                        try (Writer writer = new FileWriter(subtitleFile)) {
                            char[] buffer = new char[4096];
                            int read;
                            while ((read = reader.read(buffer)) != -1) {
                                writer.write(buffer, 0, read);
                            }
                            writer.flush();
                            convertedUri = Uri.fromFile(subtitleFile);
                        }
                    }
                }
            } catch (IOException e) {
                Log.w(Utils.TAG, e);
            }

            results[positionOnResults] = convertedUri;
            countDownLatch.countDown();


        }).start();
    }

    private OkHttpClient getOrCreateOkHttpClient() {
        if (okHttpClient == null) {
            synchronized (this) {
                if (okHttpClient == null) {
                    okHttpClient = new OkHttpClient.Builder().build();
                }
            }
        }
        return okHttpClient;
    }

    private static synchronized File getSubtitleCacheDir(Context context) throws IOException {
        File subtitleCacheDir = new File(context.getCacheDir(), "subtitles");
        if (!subtitleCacheDir.exists()) {
            if (!subtitleCacheDir.mkdirs()) {
                throw new IOException("Couldn't create subtitles cache directory");
            }
        }
        return subtitleCacheDir;
    }

}
