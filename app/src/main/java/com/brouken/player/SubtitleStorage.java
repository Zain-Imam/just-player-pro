package com.brouken.player;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class SubtitleStorage {

    static final String PREF_KEY_SUBTITLE_FOLDER = "subtitleFolder";

    private static final String FOLDER_NAME = "Just Player Pro";

    private SubtitleStorage() {
    }

    public static final class Result {
        @Nullable
        public final Uri uri;
        @Nullable
        public final String location;

        Result(@Nullable Uri uri, @Nullable String location) {
            this.uri = uri;
            this.location = location;
        }

        public boolean saved() {
            return uri != null;
        }
    }

    @Nullable
    static Uri getFolder(final Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String stored = preferences.getString(PREF_KEY_SUBTITLE_FOLDER, null);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        return Uri.parse(stored);
    }

    static void setFolder(final Context context, @Nullable final Uri uri) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_KEY_SUBTITLE_FOLDER, uri == null ? null : uri.toString())
                .apply();
    }

    @NonNull
    public static String fileName(@Nullable final String videoName, @Nullable final String language,
                           @NonNull final String extension) {
        String stem = videoName == null ? "" : videoName.trim();
        stem = stem.replaceAll("(?i)\\.(srt|ass|ssa|vtt|sub|sup|idx|mkv|mp4|m4v|avi|mov|ts|m2ts|webm|flv|wmv|mpg|mpeg|ogv|3gp)$", "");
        if (stem.isEmpty()) {
            stem = "subtitle";
        }
        // Anything a file system is entitled to object to.
        stem = stem.replaceAll("[\\\\/:*?\"<>|]", "_");
        // And a length it will accept: some providers name a subtitle after
        // every release it matches, which runs to hundreds of characters.
        if (stem.length() > 120) {
            stem = stem.substring(0, 120).trim();
        }

        final StringBuilder sb = new StringBuilder(stem);
        if (language != null && !language.isEmpty()) {
            sb.append('.').append(language);
        }
        sb.append('.').append(extension.startsWith(".") ? extension.substring(1) : extension);
        return sb.toString();
    }

    @NonNull
    public static Result save(final Context context, @NonNull final String name, @NonNull final byte[] bytes) {
        final Uri chosen = getFolder(context);
        if (chosen != null) {
            final Result result = saveToTree(context, chosen, name, bytes);
            if (result.saved()) {
                return result;
            }
            // A chosen folder can stop being writable — the card came out, the
            // grant was revoked. Falling through beats refusing to save at all.
            Utils.log("Chosen subtitle folder rejected the write; falling back");
        }

        final Result downloads = saveToDownloads(context, name, bytes);
        if (downloads.saved()) {
            return downloads;
        }

        final Result external = saveToDir(context.getExternalFilesDir(null), name, bytes);
        if (external.saved()) {
            return external;
        }

        return saveToDir(context.getFilesDir(), name, bytes);
    }

    private static Result saveToTree(final Context context, final Uri tree,
                                     final String name, final byte[] bytes) {
        try {
            final DocumentFile folder = DocumentFile.fromTreeUri(context, tree);
            if (folder == null || !folder.canWrite()) {
                return new Result(null, null);
            }

            // Replaced rather than duplicated: saving the same subtitle twice
            // should not leave "name (1)" behind.
            final DocumentFile existing = folder.findFile(name);
            if (existing != null) {
                existing.delete();
            }

            final DocumentFile file = folder.createFile("application/x-subrip", name);
            if (file == null) {
                return new Result(null, null);
            }

            try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri())) {
                if (out == null) {
                    return new Result(null, null);
                }
                out.write(bytes);
            }
            return new Result(file.getUri(), describe(folder));
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            Utils.log("Could not write subtitle to the chosen folder: " + e);
            return new Result(null, null);
        }
    }

    private static Result saveToDownloads(final Context context, final String name, final byte[] bytes) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                final ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip");
                values.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + FOLDER_NAME);

                final ContentResolver resolver = context.getContentResolver();
                final Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return new Result(null, null);
                }
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        return new Result(null, null);
                    }
                    out.write(bytes);
                }
                return new Result(uri, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME);
            } catch (Exception e) {
                Utils.log("MediaStore refused the subtitle: " + e);
                return new Result(null, null);
            }
        }

        final File downloads =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return saveToDir(new File(downloads, FOLDER_NAME), name, bytes);
    }

    private static Result saveToDir(@Nullable final File dir, final String name, final byte[] bytes) {
        // Null is the case that started all this: a box with no external volume.
        if (dir == null) {
            return new Result(null, null);
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return new Result(null, null);
            }
            final File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(bytes);
            }
            return new Result(Uri.fromFile(file), dir.getAbsolutePath());
        } catch (IOException | SecurityException e) {
            Utils.log("Could not write subtitle to " + dir + ": " + e);
            return new Result(null, null);
        }
    }

    static String describe(@NonNull final DocumentFile folder) {
        final String name = folder.getName();
        return name == null || name.isEmpty() ? folder.getUri().toString() : name;
    }

    @Nullable
    static String describeFolder(final Context context) {
        final Uri chosen = getFolder(context);
        if (chosen == null) {
            return null;
        }
        try {
            final DocumentFile folder = DocumentFile.fromTreeUri(context, chosen);
            return folder == null ? chosen.toString() : describe(folder);
        } catch (Exception e) {
            return chosen.toString();
        }
    }
}
