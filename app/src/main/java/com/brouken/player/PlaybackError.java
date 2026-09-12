package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;

// Says what went wrong in words rather than in an error code, and offers the
// details to whoever is going to be asked about it.
public final class PlaybackError {

    private PlaybackError() {
    }

    public static void show(final Activity activity, @Nullable final PlaybackException error,
                            final String engine, @Nullable final String uri) {
        final String plain = plainly(activity, error);
        final String details = details(activity, error, engine, uri);

        Utils.showFocused(new AlertDialog.Builder(activity)
                .setTitle(R.string.error_title)
                .setMessage(plain)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.error_share, (dialog, which) -> share(activity, details))
                .create(), AlertDialog.BUTTON_POSITIVE);
    }

    /*
     * The same failure in a sentence somebody can act on.
     *
     * Media3 names its errors after the layer that raised them, which is the
     * right thing for a bug report and no use at all to the person holding the
     * remote. The code is still in the details.
     */
    private static String plainly(final Activity activity, @Nullable final PlaybackException error) {
        if (error == null) {
            return activity.getString(R.string.error_unknown);
        }
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return activity.getString(R.string.error_network);
            case PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS:
                return activity.getString(R.string.error_http);
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
                return activity.getString(R.string.error_missing);
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION:
                return activity.getString(R.string.error_permission);
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
                return activity.getString(R.string.error_codec);
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return activity.getString(R.string.error_damaged);
            case PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED:
                return activity.getString(R.string.error_drm);
            default:
                return activity.getString(R.string.error_unknown);
        }
    }

    private static String details(final Activity activity, @Nullable final PlaybackException error,
                                  final String engine, @Nullable final String uri) {
        final StringBuilder text = new StringBuilder();
        text.append("Just Player Pro ").append(BuildConfig.VERSION_NAME).append('\n')
                .append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                .append("Engine: ").append(engine).append('\n');
        // The address itself can carry a token, so only its shape is included.
        if (uri != null) {
            final android.net.Uri parsed = android.net.Uri.parse(uri);
            text.append("Source: ").append(parsed.getScheme()).append("://")
                    .append(parsed.getHost() == null ? "local" : parsed.getHost()).append('\n');
        }
        if (error != null) {
            text.append("Code: ").append(error.getErrorCodeName())
                    .append(" (").append(error.errorCode).append(")\n")
                    .append("Message: ").append(error.getMessage()).append('\n');
            if (error.getCause() != null) {
                text.append("Cause: ").append(error.getCause()).append('\n');
            }
        }
        return text.toString();
    }

    private static void share(final Activity activity, final String details) {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Just Player Pro playback error");
        intent.putExtra(Intent.EXTRA_TEXT, details);
        try {
            activity.startActivity(Intent.createChooser(intent,
                    activity.getString(R.string.error_share)));
        } catch (Exception ignored) {
            // Nothing to share with: a television with no mail or messaging app.
            Utils.showFocused(new AlertDialog.Builder(activity)
                    .setTitle(R.string.error_title)
                    .setMessage(details)
                    .setPositiveButton(android.R.string.ok, null)
                    .create(), AlertDialog.BUTTON_POSITIVE);
        }
    }
}
