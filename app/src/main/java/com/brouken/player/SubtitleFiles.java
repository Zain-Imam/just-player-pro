package com.brouken.player;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// subtitle copies, one folder per source URI, stored under the real file name
// content URIs often end in a row id (msf:123), which makes a poor file name
public final class SubtitleFiles {

    private static final String TAG = "SubtitleFiles";
    private static final String ROOT = "subtitles";
    // unreferenced copies are kept this long in case the film is reopened
    private static final long KEEP_UNREFERENCED_MS = 3L * 24 * 60 * 60 * 1000;

    private SubtitleFiles() {
    }

    @NonNull
    public static File fileFor(@NonNull final Context context, @NonNull final Uri source,
                               @Nullable final String preferredName) {
        final File dir = new File(new File(context.getFilesDir(), ROOT), hash(source.toString()));
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, safeName(context, source, preferredName));
    }

    public static boolean isOwnCopy(@NonNull final Context context, @Nullable final Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_FILE.equals(uri.getScheme())
                || uri.getPath() == null) {
            return false;
        }
        final File root = new File(context.getFilesDir(), ROOT);
        return uri.getPath().startsWith(root.getAbsolutePath() + File.separator);
    }

    @Nullable
    public static Uri copy(@NonNull final Context context, @NonNull final Uri source,
                           @Nullable final String preferredName) {
        if (isOwnCopy(context, source)) {
            return source;
        }
        final File out = fileFor(context, source, preferredName);
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream sink = new FileOutputStream(out)) {
            if (in == null) {
                return null;
            }
            final byte[] chunk = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(chunk)) > 0) {
                sink.write(chunk, 0, read);
                total += read;
                if (total > 10_000_000) {
                    // Nothing that size is a subtitle.
                    throw new java.io.IOException("too large for a subtitle");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not copy " + source + ": " + e);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
        return out.length() > 0 ? Uri.fromFile(out) : null;
    }

    public static void prune(@NonNull final Context context, @NonNull final Collection<Uri> inUse) {
        final File root = new File(context.getFilesDir(), ROOT);
        final File[] folders = root.listFiles();
        if (folders == null) {
            return;
        }
        final Set<String> keep = new HashSet<>();
        for (final Uri uri : inUse) {
            if (uri != null && uri.getPath() != null) {
                keep.add(new File(uri.getPath()).getAbsolutePath());
            }
        }
        final long now = System.currentTimeMillis();
        for (final File folder : folders) {
            final File[] files = folder.listFiles();
            if (files == null) {
                continue;
            }
            boolean removable = true;
            for (final File file : files) {
                if (keep.contains(file.getAbsolutePath())
                        || now - file.lastModified() < KEEP_UNREFERENCED_MS) {
                    removable = false;
                    break;
                }
            }
            if (removable) {
                for (final File file : files) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                folder.delete();
            }
        }
    }

    // an extension is added when missing, since the format is read from it
    @NonNull
    static String safeName(@NonNull final Context context, @NonNull final Uri source,
                           @Nullable final String preferredName) {
        String name = preferredName;
        if (name == null || name.trim().isEmpty()) {
            name = Utils.getFileName(context, source, true);
        }
        if (name == null || name.trim().isEmpty()) {
            name = "subtitle";
        }
        name = name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.length() > 120) {
            name = name.substring(name.length() - 120);
        }
        if (!hasSubtitleExtension(name)) {
            name = name + extensionFor(context, source);
        }
        return name;
    }

    private static final String[] EXTENSIONS = {
            ".srt", ".ass", ".ssa", ".vtt", ".ttml", ".dfxp", ".xml", ".sub", ".smi", ".txt"};

    static boolean hasSubtitleExtension(@NonNull final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (final String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String extensionFor(final Context context, final Uri source) {
        String mime = null;
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(source.getScheme())) {
                mime = context.getContentResolver().getType(source);
            }
        } catch (Exception ignored) {
            // A provider that will not say; the format is guessed below.
        }
        if (mime != null) {
            mime = mime.toLowerCase(Locale.ROOT);
            if (mime.contains("vtt")) {
                return ".vtt";
            }
            if (mime.contains("ssa") || mime.contains("ass")) {
                return ".ass";
            }
            if (mime.contains("ttml") || mime.contains("xml")) {
                return ".ttml";
            }
        }
        return ".srt";
    }

    private static String hash(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes("UTF-8"));
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                out.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
