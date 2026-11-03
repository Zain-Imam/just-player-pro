package com.brouken.player.ytdlp;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

// yt-dlp's answers, kept while their links work: reopening a video asks the site nothing, and YouTube
// blocks a connection that asks too often. An answer whose links don't say when they expire is kept two hours.
final class Lookups {

    static final long KEEP_MS = 2 * 60 * 60 * 1000L;
    private static final long LONGEST_MS = 5 * 60 * 60 * 1000L;
    private static final long SAFETY_MS = 30 * 60 * 1000L;
    // expire=1761923456 in a query, /expire/1761923456/ in a manifest path
    private static final java.util.regex.Pattern EXPIRE =
            java.util.regex.Pattern.compile("expire[=/](\\d{10})");

    private Lookups() {
    }

    private static File dir(final Context context) {
        return new File(context.getCacheDir(), "web-lookups");
    }

    private static File fileFor(final Context context, final String key) {
        return new File(dir(context), Integer.toHexString(key.hashCode()) + ".json");
    }

    // until half an hour before its earliest link stops working
    static long keepFor(final String json, final long askedAt) {
        long earliest = Long.MAX_VALUE;
        final java.util.regex.Matcher expire = EXPIRE.matcher(json);
        while (expire.find()) {
            earliest = Math.min(earliest, Long.parseLong(expire.group(1)) * 1000);
        }
        if (earliest == Long.MAX_VALUE) {
            return KEEP_MS;
        }
        return Math.max(0, Math.min(LONGEST_MS, earliest - SAFETY_MS - askedAt));
    }

    // how much longer a kept answer may be used, 0 when it is too old
    static long leftMs(final Context context, final String key, final String json) {
        final File file = fileFor(context, key);
        final long asked = file.lastModified();
        return Math.max(0, asked + keepFor(json, asked) - System.currentTimeMillis());
    }

    @Nullable
    static String read(final Context context, final String key) {
        final File file = fileFor(context, key);
        final long age = System.currentTimeMillis() - file.lastModified();
        if (!file.isFile() || age < 0 || age > LONGEST_MS) {
            return null;
        }
        final String json = readAll(file);
        return json != null && leftMs(context, key, json) > 0 ? json : null;
    }

    @Nullable
    private static String readAll(final File file) {
        try (InputStream in = new FileInputStream(file)) {
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    static void write(final Context context, final String key, final String json) {
        final File dir = dir(context);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final File[] old = dir.listFiles();
        for (int i = 0; old != null && i < old.length; i++) {
            if (System.currentTimeMillis() - old[i].lastModified() > LONGEST_MS) {
                //noinspection ResultOfMethodCallIgnored
                old[i].delete();
            }
        }
        try (OutputStream out = new FileOutputStream(fileFor(context, key))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // asked again next time
        }
    }

    static void forget(final Context context, final String key) {
        //noinspection ResultOfMethodCallIgnored
        fileFor(context, key).delete();
    }
}
