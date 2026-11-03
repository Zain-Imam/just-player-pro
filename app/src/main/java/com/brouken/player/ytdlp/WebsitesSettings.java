package com.brouken.player.ytdlp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.brouken.player.R;

// the Websites page: download or remove yt-dlp, its updates and the starting quality
public final class WebsitesSettings {

    private static final String KEY_INSTALL = "ytdlpInstall";
    private static final String KEY_UPDATE_NOW = "ytdlpUpdateNow";
    private static final String[] NEEDS_IT = {YtDlp.PREF_UPDATES, WebVideo.PREF_QUALITY, KEY_UPDATE_NOW};

    // the work goes on when the page is left; reopening it shows where it is
    private static volatile boolean busy;
    @Nullable
    private static volatile String status;

    private WebsitesSettings() {
    }

    public static void wire(final PreferenceFragmentCompat fragment) {
        final Preference install = fragment.findPreference(KEY_INSTALL);
        final Preference updateNow = fragment.findPreference(KEY_UPDATE_NOW);
        if (install == null || updateNow == null) {
            return;
        }
        final Context context = fragment.requireContext().getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                if (!fragment.isAdded()) {
                    return;
                }
                show(fragment, context);
                if (busy) {
                    main.postDelayed(this, 500);
                }
            }
        };
        install.setOnPreferenceClickListener(clicked -> {
            if (busy) {
                return true;
            }
            if (YtDlp.installed(context)) {
                new AlertDialog.Builder(fragment.requireContext())
                        .setTitle(R.string.ytdlp_remove_title)
                        .setMessage(R.string.ytdlp_remove_message)
                        .setPositiveButton(R.string.ytdlp_remove, (d, w) -> {
                            YtDlp.remove(context);
                            show(fragment, context);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            }
            work(context, () -> {
                YtDlp.install(context, (step, percent) -> status = context.getString(
                        step == 0 ? R.string.ytdlp_getting_python : R.string.ytdlp_getting_ytdlp, percent));
                return null;
            });
            main.post(refresh);
            return true;
        });
        updateNow.setOnPreferenceClickListener(clicked -> {
            if (busy || !YtDlp.installed(context)) {
                return true;
            }
            work(context, () -> {
                status = context.getString(R.string.ytdlp_checking);
                return YtDlp.update(context, false, percent -> status = context.getString(
                        R.string.ytdlp_getting_ytdlp, percent))
                        ? context.getString(R.string.ytdlp_updated, YtDlp.version(context))
                        : context.getString(R.string.ytdlp_up_to_date);
            });
            main.post(refresh);
            return true;
        });
        // "Ask me" learns of a new version here
        if (!busy && YtDlp.installed(context)) {
            new Thread(() -> {
                YtDlp.checkNow(context);
                main.post(refresh);
            }, "yt-dlp check").start();
        }
        main.post(refresh);
    }

    private interface Job {
        @Nullable
        String run() throws Exception;
    }

    private static void work(final Context context, final Job job) {
        busy = true;
        status = context.getString(R.string.ytdlp_starting);
        new Thread(() -> {
            try {
                status = job.run();
            } catch (Exception e) {
                android.util.Log.i(com.brouken.player.Utils.TAG, "yt-dlp download failed: " + e);
                status = context.getString(R.string.ytdlp_failed);
            } finally {
                busy = false;
            }
        }, "yt-dlp download").start();
    }

    private static void show(final PreferenceFragmentCompat fragment, final Context context) {
        final boolean installed = YtDlp.installed(context);
        final Preference install = fragment.findPreference(KEY_INSTALL);
        final Preference updateNow = fragment.findPreference(KEY_UPDATE_NOW);
        if (install == null || updateNow == null) {
            return;
        }
        final String version = YtDlp.version(context);
        if (busy && !installed) {
            install.setSummary(status);
        } else if (installed) {
            install.setSummary(context.getString(R.string.ytdlp_installed, version));
        } else {
            install.setSummary(status != null ? status + "\n" + context.getString(R.string.ytdlp_offer)
                    : context.getString(R.string.ytdlp_offer));
        }
        for (final String key : NEEDS_IT) {
            final Preference preference = fragment.findPreference(key);
            if (preference != null) {
                preference.setEnabled(installed && !busy || key.equals(WebVideo.PREF_QUALITY) && installed);
            }
        }
        final String waiting = YtDlp.waiting(context);
        if (busy && installed) {
            updateNow.setSummary(status);
        } else if (installed && status != null) {
            updateNow.setSummary(status);
        } else if (waiting != null) {
            updateNow.setSummary(context.getString(R.string.ytdlp_new_version, version, waiting));
        } else {
            updateNow.setSummary(version == null ? null : context.getString(R.string.ytdlp_version, version));
        }
    }
}
