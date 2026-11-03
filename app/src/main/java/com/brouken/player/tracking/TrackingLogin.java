package com.brouken.player.tracking;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.brouken.player.R;
import com.brouken.player.Utils;
import com.brouken.player.Watched;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

// Simkl on a phone signs in through the browser, which comes back here (justplayerpro://auth/simkl).
// Trakt always uses a code, as its new apps may only redirect to localhost; so do TVs and phones without a browser.
public class TrackingLogin extends Activity {

    private static final long PENDING_MS = 10 * 60 * 1000L;

    public interface Done {
        void done();
    }

    public static void start(final Activity activity, final String service, final Done done) {
        if (Utils.isTvBox(activity) || Tracking.TRAKT.equals(service)) {
            withCode(activity, service, done);
            return;
        }
        final String state = random(24);
        final String verifier = random(48);
        Tracking.prefs(activity).edit().putString("pending.verifier", verifier).apply();
        final Uri page = Simkl.authorizeUrl(challenge(verifier), state);
        Tracking.prefs(activity).edit().putString("pending.service", service)
                .putString("pending.state", state).putLong("pending.at", System.currentTimeMillis()).apply();
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, page));
        } catch (ActivityNotFoundException e) {
            withCode(activity, service, done);
        }
    }

    public static void signOut(final Context context, final String service) {
        if (Tracking.SIMKL.equals(service)) {
            Simkl.signOut(context);
        } else {
            Trakt.signOut(context);
        }
    }

    public static boolean available(final String service) {
        return Tracking.SIMKL.equals(service) ? Simkl.INSTANCE.available() : Trakt.INSTANCE.available();
    }

    public static boolean connected(final Context context, final String service) {
        return Tracking.SIMKL.equals(service) ? Simkl.INSTANCE.connected(context) : Trakt.INSTANCE.connected(context);
    }

    // the browser came back with a code
    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Uri back = getIntent().getData();
        final android.content.SharedPreferences prefs = Tracking.prefs(this);
        final String service = prefs.getString("pending.service", null);
        final String state = prefs.getString("pending.state", null);
        final String verifier = prefs.getString("pending.verifier", null);
        final boolean fresh = System.currentTimeMillis() - prefs.getLong("pending.at", 0) < PENDING_MS;
        prefs.edit().remove("pending.service").remove("pending.state").remove("pending.verifier")
                .remove("pending.at").apply();
        final String code = back == null ? null : back.getQueryParameter("code");
        // Simkl names itself as the issuer; anything else did not come from Simkl
        final String issuer = back == null ? null : back.getQueryParameter("iss");
        if (back == null || service == null || code == null || !fresh || state == null
                || !state.equals(back.getQueryParameter("state"))
                || (issuer != null && !"https://simkl.com".equals(issuer))
                || !service.equalsIgnoreCase(back.getLastPathSegment())) {
            Toast.makeText(this, getString(R.string.tracking_failed, service != null ? service : ""),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok;
            try {
                Simkl.exchange(app, code, verifier);
                ok = true;
            } catch (Exception e) {
                Utils.log(service + " sign-in failed: " + e);
                ok = false;
            }
            final boolean signedIn = ok;
            runOnUiThread(() -> {
                Toast.makeText(app, signedIn
                        ? getString(R.string.tracking_connected_toast, service)
                        : getString(R.string.tracking_failed, service), Toast.LENGTH_LONG).show();
                if (signedIn) {
                    Tracking.refresh(app, true, null);
                }
                finish();
            });
        }, "tracking sign-in").start();
    }

    // a code to type on the service's site; checked every few seconds until done or expired
    private static void withCode(final Activity activity, final String service, final Done done) {
        final Context app = activity.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                final JSONObject code = Tracking.SIMKL.equals(service) ? Simkl.deviceCode() : Trakt.deviceCode();
                final String userCode = code.getString("user_code");
                final String where = codePage(code, service);
                // on the phone itself, a page with the code already filled in where there is one
                final String complete = Tracking.text(code, "verification_uri_complete");
                final String page = complete != null ? complete : where;
                final long interval = Math.max(3, code.optLong("interval", 5)) * 1000;
                final long until = System.currentTimeMillis() + Math.max(60, code.optLong("expires_in", 600)) * 1000;
                final AlertDialog[] dialog = new AlertDialog[1];
                final boolean[] cancelled = new boolean[1];
                main.post(() -> {
                    if (activity.isFinishing()) {
                        cancelled[0] = true;
                        return;
                    }
                    final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.tracking_connect_title, service))
                            .setMessage(activity.getString(R.string.tracking_code_message,
                                    where.replaceFirst("^https?://", ""), userCode))
                            .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled[0] = true)
                            .setOnCancelListener(d -> cancelled[0] = true);
                    // on a phone the page opens here; the dialog waits behind it
                    if (!Utils.isTvBox(activity)) {
                        builder.setPositiveButton(R.string.tracking_open_page, null);
                    }
                    dialog[0] = builder.show();
                    final android.widget.Button open = dialog[0].getButton(AlertDialog.BUTTON_POSITIVE);
                    if (open != null) {
                        open.setOnClickListener(v -> {
                            try {
                                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
                            } catch (ActivityNotFoundException ignored) {
                                // the code can still be typed on another device
                            }
                        });
                    }
                });
                final String device = code.getString("device_code");
                boolean signedIn = false;
                while (!cancelled[0] && System.currentTimeMillis() < until) {
                    Thread.sleep(interval);
                    if (cancelled[0]) {
                        break;
                    }
                    try {
                        signedIn = Tracking.SIMKL.equals(service)
                                ? Simkl.deviceDone(app, device) : Trakt.deviceDone(app, device);
                    } catch (Exception e) {
                        // not yet, or a short network drop
                    }
                    if (signedIn) {
                        break;
                    }
                }
                final boolean ok = signedIn;
                main.post(() -> {
                    if (dialog[0] != null && dialog[0].isShowing()) {
                        dialog[0].dismiss();
                    }
                    if (ok) {
                        Toast.makeText(app, app.getString(R.string.tracking_connected_toast, service),
                                Toast.LENGTH_LONG).show();
                        Tracking.refresh(app, true, null);
                        if (done != null) {
                            done.done();
                        }
                    }
                });
            } catch (Exception e) {
                Utils.log(service + " code sign-in failed: " + e);
                main.post(() -> Toast.makeText(app, app.getString(R.string.tracking_failed, service),
                        Toast.LENGTH_LONG).show());
            }
        }, "tracking code").start();
    }

    // where the code is typed: Simkl says verification_uri, Trakt verification_url
    private static String codePage(final JSONObject code, final String service) {
        final String simkl = Tracking.text(code, "verification_uri");
        if (simkl != null) {
            return simkl;
        }
        final String trakt = Tracking.text(code, "verification_url");
        if (trakt != null) {
            return trakt;
        }
        return Tracking.SIMKL.equals(service) ? "https://simkl.com/pin" : "https://trakt.tv/activate";
    }

    // after connecting: the films marked in this app before, sent once if wanted
    public static void offerMarked(final Activity activity, final String service) {
        final JSONArray marked = Watched.markedByApp(
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(activity));
        final String asked = service + ".offered";
        if (marked.length() == 0 || Tracking.prefs(activity).getBoolean(asked, false)) {
            return;
        }
        Tracking.prefs(activity).edit().putBoolean(asked, true).apply();
        new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.tracking_send_question, marked.length(), service))
                .setPositiveButton(R.string.tracking_send, (d, w) -> {
                    final List<String> keys = new ArrayList<>();
                    for (int i = 0; i < marked.length(); i++) {
                        keys.add(marked.optString(i));
                    }
                    Tracking.history(activity, keys, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String random(final int bytes) {
        final byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String challenge(final String verifier) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
