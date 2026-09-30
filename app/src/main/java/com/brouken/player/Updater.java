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

    /** Where a person is sent, as opposed to where the answer is fetched from. */
    private static final String LATEST_PAGE =
            "https://github.com/Zain-Imam/just-player-pro/releases/latest";

    /**
     * Marks the copies kept under a name that never changes, so a television
     * download code keeps working between releases. They are the same build as
     * the versioned file beside them, and this is only here so the versioned
     * one is the one offered when both would do.
     */
    private static final String DOWNLOADER_COPY = "downloader";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The page answered, and said there is nothing released at all. */
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
                /*
                 * Say which of the three things happened, not one word for all
                 * of them.
                 *
                 * "You have the newest" is the answer to a question that was
                 * asked and answered. It is not the answer when the page could
                 * not be reached, and it is not the answer when there is
                 * nothing published to compare against — and reading it in
                 * either of those cases is how you learn not to trust it. The
                 * version it found is named, so the answer can be checked.
                 */
                if (release == null) {
                    if (!quiet) {
                        toast(activity.getString(nothingPublished
                                ? R.string.update_none_published
                                : R.string.update_failed, BuildConfig.VERSION_NAME));
                    }
                    return;
                }
                if (!isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    /*
                     * Said in a box, not a toast.
                     *
                     * Somebody who presses "check for updates" has asked a
                     * question and is waiting for the answer. A toast slides
                     * away after two seconds, frequently behind the finger that
                     * pressed the row, and leaves them none the wiser about
                     * whether anything happened at all.
                     *
                     * Only when asked out loud: the quiet check runs on its own
                     * and has no business putting a box in front of anyone to
                     * say that nothing has changed.
                     */
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
                /*
                 * The canonical address rather than the one this release
                 * happens to sit at.
                 *
                 * /releases/latest always points at whatever is newest and
                 * opens with the download list already on screen, where the tag
                 * URL is one release frozen in time -- and if a newer one lands
                 * between the check and the tap, the tag URL sends somebody to
                 * the old one.
                 *
                 * The button beside it does better still: it fetches the right
                 * build for this device straight into the installer, with no
                 * page to read and nothing to scroll past.
                 */
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
            // Nothing to fetch, so hand over the page that lists everything.
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
        nothingPublished = false;
        try {
            final HttpURLConnection connection =
                    (HttpURLConnection) new URL(RELEASES).openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);

            // A repository with no releases answers 404 here, which is a real
            // answer and not a failure to get one.
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

            /*
             * The best build for this device, not the last one that matches.
             *
             * SUPPORTED_ABIS lists every architecture the device can run, best
             * first -- a 64-bit phone reports arm64-v8a AND armeabi-v7a. This
             * used to assign on every match as it walked the assets, so the
             * winner was whichever matching file happened to come last in the
             * release, and every device ended up on the 32-bit ARM build: it
             * runs, but with 32-bit mpv and FFmpeg and less room on big files.
             *
             * So the architectures are walked in the order the device prefers
             * them, and the first one with a matching asset wins. The versioned
             * filename is taken over the fixed-name copy kept for television
             * download links -- they are the same build, but the versioned one
             * is the file this release is actually named for.
             */
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
                // The whole of the name's tail, not a substring of it: "x86" is
                // inside "x86_64", so a 32-bit x86 device asking for "x86"
                // matched the 64-bit file and installed something that cannot
                // run. Every asset ends in -<abi>.apk, which is exact.
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
