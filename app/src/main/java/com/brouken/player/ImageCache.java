package com.brouken.player;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

// posters and thumbnails on disk; the least used go first past the cap
public final class ImageCache {

    private static final String DIR = "images";
    private static final long MAX_BYTES = 150L * 1024 * 1024;

    @Nullable
    private static volatile File dir;

    private ImageCache() {
    }

    static void init(@NonNull final Context context) {
        dir = new File(context.getApplicationContext().getCacheDir(), DIR);
    }

    @Nullable
    public static byte[] read(@NonNull final String key) {
        final File file = file(key);
        if (file == null || !file.isFile()) {
            return null;
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            final byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                final int got = in.read(bytes, read, bytes.length - read);
                if (got < 0) {
                    return null;
                }
                read += got;
            }
            // a read counts as a use, so the cap keeps what is still shown
            file.setLastModified(System.currentTimeMillis());
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    public static void write(@NonNull final String key, @NonNull final byte[] bytes) {
        final File file = file(key);
        if (file == null) {
            return;
        }
        final File folder = file.getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            return;
        }
        // written aside and moved in, so a half-written file is never read
        final File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        } catch (IOException e) {
            part.delete();
            return;
        }
        if (!part.renameTo(file)) {
            part.delete();
            return;
        }
        trim();
    }

    // one that would not decode, so it is fetched or made again
    public static void remove(@NonNull final String key) {
        final File file = file(key);
        if (file != null) {
            file.delete();
        }
    }

    public static long size() {
        final File[] files = files();
        long total = 0;
        for (final File each : files) {
            total += each.length();
        }
        return total;
    }

    public static void clear() {
        for (final File each : files()) {
            each.delete();
        }
    }

    private static synchronized void trim() {
        final File[] files = files();
        long total = 0;
        for (final File each : files) {
            total += each.length();
        }
        if (total <= MAX_BYTES) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        // down to 90%, so it does not trim again at once
        for (final File each : files) {
            if (total <= MAX_BYTES * 9 / 10) {
                break;
            }
            total -= each.length();
            each.delete();
        }
    }

    @NonNull
    private static File[] files() {
        final File folder = dir;
        final File[] files = folder == null ? null : folder.listFiles();
        return files == null ? new File[0] : files;
    }

    @Nullable
    private static File file(final String key) {
        final File folder = dir;
        return folder == null ? null : new File(folder, hash(key));
    }

    private static String hash(final String key) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
