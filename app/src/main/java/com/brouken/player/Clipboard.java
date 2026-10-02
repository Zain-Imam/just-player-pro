package com.brouken.player;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.File;

// copies a link; content URIs are resolved to a real path where possible
final class Clipboard {

    private Clipboard() {
    }

    @Nullable
    static String textFor(final Context context, @Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        final String scheme = uri.getScheme();

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

    // only MediaStore items still expose a path; other providers have none
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
            // no path exposed; the URI is used instead
        }
        return null;
    }

    // Android 13+ shows its own clipboard confirmation, so no toast there
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
