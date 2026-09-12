package com.brouken.player.mpv;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/*
 * The certificates mpv needs to open an https address at all.
 *
 * mpv does its own networking through FFmpeg and its own TLS through gnutls,
 * and neither of them knows anything about Android's trust store. Given an
 * https URL with no bundle to check it against, it fails the handshake, reports
 * "Failed to open", and then — because that is what desktop mpv does next —
 * goes looking for youtube-dl, which is not there either. What the person
 * watching sees is a stream that does not start, on one engine, with no reason
 * given.
 *
 * Every https stream was affected: debrid links, Stremio's, anything served
 * over TLS, which is everything now.
 *
 * Rather than shipping somebody else's copy of the Mozilla bundle and letting
 * it go stale, the device's own certificates are used. They live as separate
 * PEM files in the system store, so they are concatenated once into a single
 * file in the cache, which is the shape gnutls wants. It is rebuilt whenever
 * the store has more certificates in it than the copy was made from, so a
 * system update is picked up.
 */
public final class CaBundle {

    private static final String[] STORES = {
            // Android 14 and later keep the store in the conscrypt module.
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
    };

    private CaBundle() {
    }

    @Nullable
    public static String path(final Context context) {
        try {
            final File bundle = new File(context.getCacheDir(), "cacert.pem");
            final int available = countAvailable();
            if (available == 0) {
                return null;
            }
            if (bundle.isFile() && bundle.length() > 0 && countIn(bundle) >= available) {
                return bundle.getAbsolutePath();
            }
            return build(bundle, available) ? bundle.getAbsolutePath() : null;
        } catch (Throwable error) {
            // A device that keeps its certificates somewhere else entirely.
            return null;
        }
    }

    private static int countAvailable() {
        for (final String store : STORES) {
            final File dir = new File(store);
            final String[] names = dir.list();
            if (names != null && names.length > 0) {
                return names.length;
            }
        }
        return 0;
    }

    /** How many certificates went into the copy, recorded in its last line. */
    private static int countIn(final File bundle) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(bundle))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("-----BEGIN CERTIFICATE-----")) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean build(final File bundle, final int expected) {
        File source = null;
        for (final String store : STORES) {
            final File dir = new File(store);
            final String[] names = dir.list();
            if (names != null && names.length > 0) {
                source = dir;
                break;
            }
        }
        if (source == null) {
            return false;
        }

        final File[] certificates = source.listFiles();
        if (certificates == null || certificates.length == 0) {
            return false;
        }

        final File working = new File(bundle.getAbsolutePath() + ".part");
        int written = 0;
        try (OutputStream out = new FileOutputStream(working)) {
            for (final File certificate : certificates) {
                if (!certificate.isFile() || !certificate.canRead()) {
                    continue;
                }
                // The files carry a readable description after the PEM block;
                // only the block itself is wanted.
                final String text = read(certificate);
                if (text == null) {
                    continue;
                }
                int from = text.indexOf("-----BEGIN CERTIFICATE-----");
                while (from >= 0) {
                    final int to = text.indexOf("-----END CERTIFICATE-----", from);
                    if (to < 0) {
                        break;
                    }
                    final int end = to + "-----END CERTIFICATE-----".length();
                    out.write(text.substring(from, end).getBytes("US-ASCII"));
                    out.write('\n');
                    written++;
                    from = text.indexOf("-----BEGIN CERTIFICATE-----", end);
                }
            }
        } catch (Exception e) {
            working.delete();
            return false;
        }

        if (written == 0) {
            working.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        bundle.delete();
        return working.renameTo(bundle);
    }

    @Nullable
    private static String read(final File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int count;
            while ((count = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), "US-ASCII");
        } catch (Exception e) {
            return null;
        }
    }
}
