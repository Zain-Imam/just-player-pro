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
import java.util.ArrayList;
import java.util.List;

// checks GitHub releases for a newer build and hands the APK to the installer
public final class Updater {

    private static final String RELEASES =
            "https://api.github.com/repos/Zain-Imam/just-player-pro/releases/latest";

    private static final String LATEST_PAGE =
            "https://github.com/Zain-Imam/just-player-pro/releases/latest";

    // marks the fixed-name copies for TV download codes; the versioned file wins
    private static final String DOWNLOADER_COPY = "downloader";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean nothingPublished;

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
                        toast(activity.getString(nothingPublished
                                ? R.string.update_none_published
                                : R.string.update_failed, BuildConfig.VERSION_NAME));
                    }
                    return;
                }
                if (!isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    if (!quiet) {
                        Utils.showFocused(new AlertDialog.Builder(activity)
                                .setTitle(R.string.update_none_title)
                                .setMessage(activity.getString(R.string.update_none,
                                        BuildConfig.VERSION_NAME, release.version))
                                .setPositiveButton(android.R.string.ok, null)
                                .create(), AlertDialog.BUTTON_POSITIVE);
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
                // /releases/latest always points at the newest release
                .setNeutralButton(R.string.update_open, (dialog, which) ->
                        open(Uri.parse(LATEST_PAGE)))
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
            open(Uri.parse(release.page == null ? LATEST_PAGE : release.page));
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
            // installer locked down, as on some TVs
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

    private static final class Release {
        String version;
        String page;
        String apk;
        String notes;
    }

    @Nullable
    private Release fetch() {
        nothingPublished = false;
        try {
            final HttpURLConnection connection =
                    (HttpURLConnection) new URL(RELEASES).openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);

            // a repository with no releases answers 404
            if (connection.getResponseCode() == 404) {
                nothingPublished = true;
                return null;
            }

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

            // SUPPORTED_ABIS is in preference order; the first ABI with an asset wins
            final JSONArray assets = json.optJSONArray("assets");
            final List<String> names = new ArrayList<>();
            final List<String> urls = new ArrayList<>();
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
                } else {
                    names.add(name);
                    urls.add(url);
                }
            }
            for (final String abi : android.os.Build.SUPPORTED_ABIS) {
                // match the whole -<abi>.apk tail: "x86" is a substring of "x86_64"
                final String tail = "-" + abi + ".apk";
                for (int i = 0; i < names.size(); i++) {
                    if (!names.get(i).endsWith(tail)) {
                        continue;
                    }
                    if (release.apk == null || !names.get(i).contains(DOWNLOADER_COPY)) {
                        release.apk = urls.get(i);
                    }
                }
                if (release.apk != null) {
                    break;
                }
            }
            if (release.apk == null) {
                release.apk = universal;
            }
            if (BuildConfig.DEBUG) {
                Utils.log("Update: this device offers "
                        + android.text.TextUtils.join(", ", android.os.Build.SUPPORTED_ABIS)
                        + " and will fetch " + release.apk);
            }
            if (release.version.isEmpty()) {
                nothingPublished = true;
                return null;
            }
            return release;
        } catch (Exception e) {
            return null;
        }
    }

    // numeric per part, so 1.10.0 is newer than 1.9.0
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
