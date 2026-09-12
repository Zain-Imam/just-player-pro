package com.brouken.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/*
 * Checking GitHub for a newer build, since that is where this one came from.
 *
 * There is no store to do it, and an app distributed as a file is an app that
 * never gets updated unless somebody goes looking. This asks the releases page
 * what the newest tag is, and if it is newer than what is running, offers to
 * fetch it and hand it to the system installer.
 *
 * Never automatic. It checks when asked and downloads when told, because a
 * player that reaches the network on its own is not what was advertised.
 */
public final class Updater {

    private static final String RELEASES =
            "https://api.github.com/repos/Zain-Imam/just-player-pro/releases/latest";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Updater(final Activity activity) {
        this.activity = activity;
    }

    public void check(final boolean quiet) {
        new Thread(() -> {
            final Release release = fetch();
            main.post(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                if (release == null) {
                    if (!quiet) {
                        toast(activity.getString(R.string.update_failed));
                    }
                    return;
                }
                if (!isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    if (!quiet) {
                        toast(activity.getString(R.string.update_none,
                                BuildConfig.VERSION_NAME));
                    }
                    return;
                }
                offer(release);
            });
        }).start();
    }

    private void offer(final Release release) {
        Utils.showFocused(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_title, release.version))
                .setMessage(release.notes == null || release.notes.isEmpty()
                        ? activity.getString(R.string.update_ready)
                        : trim(release.notes))
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.update_open, (dialog, which) ->
                        open(Uri.parse(release.page)))
                .setPositiveButton(R.string.update_install, (dialog, which) -> download(release))
                .create(), AlertDialog.BUTTON_POSITIVE);
    }

    private static String trim(final String notes) {
        final String[] lines = notes.split("\n");
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 14); i++) {
            text.append(lines[i]).append('\n');
        }
        return text.toString().trim();
    }

    private void download(final Release release) {
        if (release.apk == null) {
            open(Uri.parse(release.page));
            return;
        }
        toast(activity.getString(R.string.update_downloading));
        new Thread(() -> {
            final File file = new File(activity.getCacheDir(), "update.apk");
            boolean ok = false;
            try {
                final HttpURLConnection connection =
                        (HttpURLConnection) new URL(release.apk).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(60_000);
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(file)) {
                    final byte[] chunk = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        out.write(chunk, 0, read);
                    }
                }
                ok = file.length() > 0;
            } catch (Exception ignored) {
            }
            final boolean done = ok;
            main.post(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                if (!done) {
                    toast(activity.getString(R.string.update_failed));
                    return;
                }
                install(file);
            });
        }).start();
    }

    private void install(final File file) {
        try {
            final Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    activity, activity.getPackageName() + ".updates", file);
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            // A television with the installer locked down, most likely.
            toast(activity.getString(R.string.update_failed));
        }
    }

    private void open(final Uri uri) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            toast(activity.getString(R.string.update_failed));
        }
    }

    private void toast(final String text) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ data

    private static final class Release {
        String version;
        String page;
        String apk;
        String notes;
    }

    @Nullable
    private Release fetch() {
        try {
            final HttpURLConnection connection =
                    (HttpURLConnection) new URL(RELEASES).openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);

            final StringBuilder body = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    body.append(new String(chunk, 0, read, "UTF-8"));
                }
            }

            final JSONObject json = new JSONObject(body.toString());
            final Release release = new Release();
            release.version = json.optString("tag_name", "").replaceFirst("^v", "");
            release.page = json.optString("html_url", null);
            release.notes = json.optString("body", "");

            // Prefer the build for this device's architecture over the
            // universal one, which is four times the size.
            final JSONArray assets = json.optJSONArray("assets");
            String universal = null;
            for (int i = 0; assets != null && i < assets.length(); i++) {
                final JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                final String name = asset.optString("name", "");
                final String url = asset.optString("browser_download_url", null);
                if (!name.endsWith(".apk") || url == null) {
                    continue;
                }
                if (name.contains("universal")) {
                    universal = url;
                } else if (matchesThisDevice(name)) {
                    release.apk = url;
                }
            }
            if (release.apk == null) {
                release.apk = universal;
            }
            return release.version.isEmpty() ? null : release;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matchesThisDevice(final String assetName) {
        for (final String abi : android.os.Build.SUPPORTED_ABIS) {
            if (assetName.contains(abi)) {
                return true;
            }
        }
        return false;
    }

    /** Compares dotted versions, so 1.10.0 is newer than 1.9.0 rather than older. */
    static boolean isNewer(final String candidate, final String running) {
        final String[] a = candidate.split("[^0-9]+");
        final String[] b = running.split("[^0-9]+");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final int left = i < a.length ? parse(a[i]) : 0;
            final int right = i < b.length ? parse(b[i]) : 0;
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int parse(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
