package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;

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
                .setNegativeButton(R.string.error_code, (dialog, which) -> showCode(activity, details))
                .setNeutralButton(R.string.error_share, (dialog, which) -> share(activity, details))
                .create(), AlertDialog.BUTTON_POSITIVE);
    }

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
        // scheme and host only: the full URL can carry a token
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

    // a QR code, since a TV usually has nowhere to share to
    private static void showCode(final Activity activity, final String details) {
        final int size = Math.round(Math.min(
                activity.getResources().getDisplayMetrics().widthPixels,
                activity.getResources().getDisplayMetrics().heightPixels) * 0.6f);
        final android.graphics.Bitmap code = QrCode.of(details, size);
        if (code == null) {
            showDetails(activity, details);
            return;
        }

        final android.widget.ImageView image = new android.widget.ImageView(activity);
        image.setImageBitmap(code);
        image.setAdjustViewBounds(true);
        final int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        image.setPadding(pad, pad, pad, pad);
        image.setBackgroundColor(android.graphics.Color.WHITE);

        Utils.showFocused(new AlertDialog.Builder(activity)
                .setTitle(R.string.error_code)
                .setView(image)
                .setPositiveButton(android.R.string.ok, null)
                .create(), AlertDialog.BUTTON_POSITIVE);
    }

    private static void showDetails(final Activity activity, final String details) {
        Utils.showFocused(new AlertDialog.Builder(activity)
                .setTitle(R.string.error_title)
                .setMessage(details)
                .setPositiveButton(android.R.string.ok, null)
                .create(), AlertDialog.BUTTON_POSITIVE);
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
            // no app to share to, e.g. on a TV
            showDetails(activity, details);
        }
    }
}
