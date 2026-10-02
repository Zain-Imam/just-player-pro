package com.brouken.player.mpv;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// mpv's TLS (FFmpeg + gnutls) ignores Android's trust store and needs a single PEM
// bundle. Built from the system store; rebuilt when the store gains certificates.
public final class CaBundle {

    private static final String[] STORES = {
            // Android 14+ keeps the store in the conscrypt module
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
    };

    private CaBundle() {
    }

    @Nullable
    public static String path(final Context context) {
        try {
            // not in the cache dir: mpv rereads it on every connection (each seek), and
            // the cache can be cleared during playback
            final File bundle = new File(context.getNoBackupFilesDir(), "cacert.pem");
            final File old = new File(context.getCacheDir(), "cacert.pem");
            if (old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
            final int available = countAvailable();
            if (available == 0) {
                return null;
            }
            if (bundle.isFile() && bundle.length() > 0 && countIn(bundle) >= available) {
                return bundle.getAbsolutePath();
            }
            return build(bundle, available) ? bundle.getAbsolutePath() : null;
        } catch (Throwable error) {
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
                // system cert files have a text dump after the PEM block
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
