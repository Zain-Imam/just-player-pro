package com.brouken.player.tracking;

import android.app.AlertDialog;
import android.content.Context;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.brouken.player.R;

// Settings > Integrations > Watch tracking: one row per service, tap to connect or disconnect
public final class TrackingSettings {

    private static final String KEY_SIMKL = "trackingSimkl";

    private TrackingSettings() {
    }

    public static void wire(final PreferenceFragmentCompat fragment) {
        wire(fragment, KEY_SIMKL, Tracking.SIMKL);
        show(fragment);
    }

    private static void wire(final PreferenceFragmentCompat fragment, final String key, final String service) {
        final Preference row = fragment.findPreference(key);
        if (row == null) {
            return;
        }
        row.setOnPreferenceClickListener(preference -> {
            final Context context = fragment.requireContext();
            if (!TrackingLogin.available(service)) {
                return true;
            }
            if (TrackingLogin.connected(context, service)) {
                new AlertDialog.Builder(fragment.requireActivity())
                        .setMessage(context.getString(R.string.tracking_disconnect_question, service))
                        .setPositiveButton(R.string.tracking_disconnect, (d, w) -> {
                            TrackingLogin.signOut(context, service);
                            show(fragment);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                TrackingLogin.start(fragment.requireActivity(), service, () -> {
                    if (fragment.isAdded()) {
                        show(fragment);
                        TrackingLogin.offerMarked(fragment.requireActivity(), service);
                    }
                });
            }
            return true;
        });
    }

    // also on return from the browser
    public static void show(final PreferenceFragmentCompat fragment) {
        show(fragment, KEY_SIMKL, Tracking.SIMKL);
    }

    private static void show(final PreferenceFragmentCompat fragment, final String key, final String service) {
        final Preference row = fragment.findPreference(key);
        if (row == null || !fragment.isAdded()) {
            return;
        }
        final Context context = fragment.requireContext();
        // a service this build has no app id for is not offered at all
        row.setVisible(TrackingLogin.available(service));
        if (!TrackingLogin.available(service)) {
            return;
        }
        if (TrackingLogin.connected(context, service)) {
            final String user = Tracking.user(context, service);
            row.setSummary(context.getString(R.string.tracking_connected, user != null ? user : service));
            // a sign-in finished in the browser: offered once, now that Settings is back
            TrackingLogin.offerMarked(fragment.requireActivity(), service);
        } else {
            row.setSummary(R.string.tracking_not_connected);
        }
    }
}
