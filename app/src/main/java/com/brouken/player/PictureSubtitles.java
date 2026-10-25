package com.brouken.player;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Media3 reads picture subtitles only inside a video, so a loose one plays in mpv
public final class PictureSubtitles {

    // matches no parser, so Media3 lists the track as unknown rather than failing
    public static final String MIME = "application/x-picture-subtitle-file";

    // looked up once per address; the check may read the file or ask its provider
    private static final Map<String, Boolean> known = new ConcurrentHashMap<>();

    private PictureSubtitles() {
    }

    public static boolean isPicture(@NonNull final Context context, @Nullable final Uri uri) {
        if (uri == null) {
            return false;
        }
        final String key = uri.toString();
        final Boolean cached = known.get(key);
        if (cached != null) {
            return cached;
        }
        final boolean picture = check(context, uri);
        known.put(key, picture);
        return picture;
    }

    private static boolean check(final Context context, final Uri uri) {
        final String name = name(context, uri);
        if (isPictureName(name)) {
            return true;
        }
        if (!name.endsWith(".sub")) {
            return false;
        }
        final String scheme = uri.getScheme();
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme) && !ContentResolver.SCHEME_FILE.equals(scheme)) {
            // not worth a download to tell; a remote .sub is taken as text
            return false;
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return false;
            }
            final byte[] head = new byte[4];
            int read = 0;
            while (read < head.length) {
                final int n = in.read(head, read, head.length - read);
                if (n < 0) break;
                read += n;
            }
            return read == head.length && isVobSubData(head);
        } catch (Exception e) {
            return false;
        }
    }

    // mpv opens the .sub by swapping the extension, which a picked URI cannot grant
    @NonNull
    public static Uri forMpv(@NonNull final Context context, @NonNull final Uri uri,
                             @Nullable final Uri grantedFolder) {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !name(context, uri).endsWith(".idx")
                || !DocumentsContract.isDocumentUri(context, uri)) {
            return uri;
        }
        try {
            final String documentId = DocumentsContract.getDocumentId(uri);
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                final int colon = documentId.indexOf(':');
                if (colon > 0) {
                    final String volume = documentId.substring(0, colon);
                    final File root = "primary".equalsIgnoreCase(volume)
                            ? Environment.getExternalStorageDirectory()
                            : new File("/storage/" + volume);
                    final File idx = new File(root, documentId.substring(colon + 1));
                    final String path = idx.getPath();
                    final File sub = new File(path.substring(0, path.length() - 4) + ".sub");
                    if (idx.canRead() && sub.canRead()) {
                        return Uri.fromFile(idx);
                    }
                }
            }
            if (grantedFolder != null) {
                final String folderId = DocumentsContract.getTreeDocumentId(grantedFolder);
                final String inside = folderId == null || folderId.endsWith(":") || folderId.endsWith("/")
                        ? folderId : folderId + "/";
                if (inside != null && documentId.startsWith(inside)
                        && grantedFolder.getAuthority() != null
                        && grantedFolder.getAuthority().equals(uri.getAuthority())) {
                    return DocumentsContract.buildDocumentUriUsingTree(grantedFolder, documentId);
                }
            }
        } catch (Exception e) {
            // a provider with its own rules; mpv gets the address as picked
        }
        return uri;
    }

    // lower case; a content URI's path is often a row id, so ask for the real name
    @NonNull
    static String name(final Context context, final Uri uri) {
        String name = uri.getPath() == null ? "" : uri.getPath();
        final int slash = name.lastIndexOf('/');
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && name.indexOf('.', slash + 1) < 0) {
            final String displayName = Utils.getFileName(context, uri, true);
            if (displayName != null) {
                name = displayName;
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean isPictureName(@NonNull final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".sup") || lower.endsWith(".idx");
    }

    // a VobSub .sub is an MPEG program stream: it opens with a pack header
    static boolean isVobSubData(@NonNull final byte[] head) {
        return head.length >= 4 && head[0] == 0 && head[1] == 0 && head[2] == 1
                && (head[3] & 0xFF) == 0xBA;
    }
}
