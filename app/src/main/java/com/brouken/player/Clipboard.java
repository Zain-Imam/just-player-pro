package com.brouken.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;

/*
 * Putting a link on the clipboard, in the form somebody can actually use.
 *
 * A network URL is copied as it stands. A local file is the awkward case: what
 * the player is handed by a file manager is usually an opaque
 * content://media/external/video/media/1234, which is accurate and of no use to
 * anyone pasting it somewhere. Where the real path can be recovered it is
 * copied instead, and where it cannot the URI is, because a URI you can paste
 * back into this player beats nothing at all.
 */
final class Clipboard {

    private Clipboard() {
    }

    /** What would be copied for this URI, or null if there is nothing to copy. */
    @Nullable
    static String textFor(final Context context, @Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        final String scheme = uri.getScheme();

        // A network address is already the thing you would want to share.
        if (History.isNetworkUri(uri)) {
            return uri.toString();
        }

        if ("file".equals(scheme)) {
            final String path = uri.getPath();
            return path != null ? path : uri.toString();
        }

        final String resolved = pathOf(context, uri);
        return resolved != null ? resolved : uri.toString();
    }

    /**
     * The filesystem path behind a content URI, where the system will say.
     *
     * MediaStore still answers for its own items, which covers files opened
     * from the gallery or a media browser. Documents from other providers do
     * not have a path at all, and asking harder does not produce one.
     */
    @Nullable
    private static String pathOf(final Context context, final Uri uri) {
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        final String column = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? android.provider.MediaStore.MediaColumns.DATA
                : "_data";
        try (android.database.Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{column}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(column);
                if (index >= 0) {
                    final String path = cursor.getString(index);
                    if (path != null && !path.isEmpty() && new File(path).exists()) {
                        return path;
                    }
                }
            }
        } catch (Exception unavailable) {
            // A provider that does not expose a path, or will not be queried
            // for one. The URI is the answer in that case.
        }
        return null;
    }

    /**
     * Copy it, and say so — except where the system says so itself.
     *
     * Android 13 and later show their own confirmation for anything put on the
     * clipboard, so a toast as well is the same sentence twice.
     */
    static void copy(final Context context, @Nullable final Uri uri, final CharSequence label) {
        final String text = textFor(context, uri);
        if (text == null) {
            Toast.makeText(context, R.string.copy_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        final ClipboardManager manager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(context, R.string.copy_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText(label, text));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.copy_done, Toast.LENGTH_SHORT).show();
        }
    }
}
