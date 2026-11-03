package com.brouken.player.ytdlp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.brouken.player.mpv.CaBundle;
import com.brouken.player.online.Http;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Request;
import okhttp3.Response;

// yt-dlp is optional and downloaded from Settings: Python from this app's runtime repo, yt-dlp
// itself straight from its own GitHub releases, so neither makes the APK bigger
public final class YtDlp {

    public static final String PREF_UPDATES = "ytdlpUpdates";
    public static final String UPDATES_AUTO = "auto";
    public static final String UPDATES_ASK = "ask";
    public static final String UPDATES_MANUAL = "manual";
    private static final String PREF_CHECKED_AT = "ytdlpCheckedAt";
    private static final String PREF_LATEST = "ytdlpLatest";
    private static final long CHECK_EVERY_MS = TimeUnit.HOURS.toMillis(24);

    private static final String PYTHON = "3.14.9";
    private static final String PACK_BASE =
            "https://github.com/Zain-Imam/just-player-pro-runtime/releases/download/python-" + PYTHON + "/";
    // fixed per app version, so a changed pack is refused
    private static final Map<String, String> PACK_SHA256 = new HashMap<>();

    static {
        PACK_SHA256.put("arm64-v8a", "0009bd7eea1f46e2d4b68c29d414348b5454d3fde44b651fb94315ed16c00557");
        PACK_SHA256.put("armeabi-v7a", "141a3b845ed60e8351ace69c35346e1c52efde479a6366b9eed69d618a545585");
        PACK_SHA256.put("x86", "caa51d264e46fba2d2a88e6755368bbfc9d128b4cdd499c2c861d92b16d58154");
        PACK_SHA256.put("x86_64", "c23f83dc3ae914ae292250d6b40c0690dbd4426f6f4a2eeea8feb6345d446553");
    }

    private static final String RELEASES = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest";
    private static final String DOWNLOAD = "https://github.com/yt-dlp/yt-dlp/releases/download/";

    public interface Progress {
        // step: 0 Python, 1 yt-dlp, 2 checking
        void onProgress(int step, int percent);
    }

    public static final class Result {
        public final int exitCode;
        public final String out;
        public final String err;

        Result(final int exitCode, final String out, final String err) {
            this.exitCode = exitCode;
            this.out = out;
            this.err = err;
        }
    }

    private YtDlp() {
    }

    private static File root(final Context context) {
        return new File(context.getNoBackupFilesDir(), "ytdlp");
    }

    private static File python(final Context context) {
        return new File(root(context), "python");
    }

    private static File program(final Context context) {
        return new File(root(context), "yt-dlp");
    }

    private static File starter(final Context context, final String name) {
        return new File(context.getApplicationInfo().nativeLibraryDir, name);
    }

    // for this APK's ABI: a 32-bit APK put over a 64-bit one cannot load the old pack
    public static boolean installed(final Context context) {
        return hasPython(context)
                && new File(program(context), "__main__.py").isFile()
                && starter(context, "libytdl.so").isFile();
    }

    private static boolean hasPython(final Context context) {
        return new File(python(context), "lib/libpython3.14.so").isFile()
                && abi(context).equals(readText(new File(root(context), "python.abi")));
    }

    @Nullable
    private static String readText(final File file) {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), "UTF-8").trim();
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    public static String version(final Context context) {
        final File file = new File(root(context), "yt-dlp.version");
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), "UTF-8").trim();
        } catch (IOException e) {
            return null;
        }
    }

    // the folder Android unpacked the libraries into is named after the APK's ABI
    static String abi(final Context context) {
        final String dir = new File(context.getApplicationInfo().nativeLibraryDir).getName();
        switch (dir) {
            case "arm64":
                return "arm64-v8a";
            case "arm":
                return "armeabi-v7a";
            default:
                return dir;
        }
    }

    public static void install(final Context context, final Progress progress) throws IOException {
        final File root = root(context);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        if (!hasPython(context)) {
            final String abi = abi(context);
            final String name = "python-" + PYTHON + "-" + abi + ".zip";
            final File zip = new File(context.getCacheDir(), name);
            try {
                download(PACK_BASE + name, zip, percent -> progress.onProgress(0, percent));
                final String expected = PACK_SHA256.get(abi);
                if (expected == null || !expected.equals(sha256(zip))) {
                    throw new IOException("The Python download did not match");
                }
                unzipInto(zip, python(context));
                try (OutputStream out = new FileOutputStream(new File(root, "python.abi"))) {
                    out.write(abi.getBytes("UTF-8"));
                }
            } finally {
                //noinspection ResultOfMethodCallIgnored
                zip.delete();
            }
        }
        update(context, true, percent -> progress.onProgress(1, percent));
    }

    public static void remove(final Context context) {
        deleteAll(root(context));
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(PREF_CHECKED_AT).remove(PREF_LATEST).apply();
    }

    @Nullable
    public static String latest() throws IOException {
        final Http.Result result = Http.get(RELEASES, null);
        if (result.code != 200 || result.body == null) {
            throw new IOException("GitHub did not answer (" + result.code + ")");
        }
        try {
            final String tag = new JSONObject(result.body).optString("tag_name", "");
            return tag.isEmpty() ? null : tag;
        } catch (Exception e) {
            throw new IOException("GitHub answered something else", e);
        }
    }

    // true when a newer yt-dlp was put in place
    public static boolean update(final Context context, final boolean evenIfSame,
                                 @Nullable final Download.Listener listener) throws IOException {
        final String tag = latest();
        if (tag == null) {
            throw new IOException("No yt-dlp release found");
        }
        remember(context, tag);
        if (!evenIfSame && tag.equals(version(context)) && installed(context)) {
            return false;
        }
        final File cache = context.getCacheDir();
        final File file = new File(cache, "yt-dlp-" + tag);
        final File sums = new File(cache, "yt-dlp-sums-" + tag);
        final File fresh = new File(root(context), "yt-dlp.new");
        try {
            download(DOWNLOAD + tag + "/SHA2-256SUMS", sums, null);
            download(DOWNLOAD + tag + "/yt-dlp", file, listener);
            final String expected = sumFor(sums, "yt-dlp");
            if (expected == null || !expected.equalsIgnoreCase(sha256(file))) {
                throw new IOException("The yt-dlp download did not match its checksum");
            }
            unzipInto(file, fresh);
            // like yt-dlp's own updater: the new one has to start before it replaces the old
            final Result check = run(context, fresh, Arrays.asList("--version"), 60_000, null);
            if (check.exitCode != 0 || !check.out.trim().equals(tag)) {
                throw new IOException("The new yt-dlp did not start: " + lastLine(check.err));
            }
            final File old = program(context);
            final File stale = new File(root(context), "yt-dlp.old");
            deleteAll(stale);
            if (old.exists() && !old.renameTo(stale)) {
                throw new IOException("Could not replace yt-dlp");
            }
            if (!fresh.renameTo(old)) {
                //noinspection ResultOfMethodCallIgnored
                stale.renameTo(old);
                throw new IOException("Could not replace yt-dlp");
            }
            deleteAll(stale);
            try (OutputStream out = new FileOutputStream(new File(root(context), "yt-dlp.version"))) {
                out.write(tag.getBytes("UTF-8"));
            }
            return true;
        } finally {
            deleteAll(fresh);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            sums.delete();
        }
    }

    private static void remember(final Context context, final String tag) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(PREF_CHECKED_AT, System.currentTimeMillis())
                .putString(PREF_LATEST, tag).apply();
    }

    public static String updates(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_UPDATES, UPDATES_AUTO);
    }

    // newer than the one installed, as of the last check
    @Nullable
    public static String waiting(final Context context) {
        final String latest = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_LATEST, null);
        final String have = version(context);
        return latest != null && have != null && !latest.equals(have) ? latest : null;
    }

    // once a day at most; quiet on failure. Call off the main thread.
    public static void checkNow(final Context context) {
        if (!installed(context) || UPDATES_MANUAL.equals(updates(context))) {
            return;
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (System.currentTimeMillis() - prefs.getLong(PREF_CHECKED_AT, 0) < CHECK_EVERY_MS) {
            return;
        }
        try {
            if (UPDATES_AUTO.equals(updates(context))) {
                update(context, false, null);
            } else {
                remember(context, latest());
            }
        } catch (IOException e) {
            android.util.Log.i("JustPlayer", "yt-dlp update check failed: " + e.getMessage());
        }
    }

    public static Result run(final Context context, final List<String> args, final long timeoutMs,
                             @Nullable final AtomicReference<Process> running) throws IOException {
        return run(context, program(context), args, timeoutMs, running);
    }

    private static Result run(final Context context, final File program, final List<String> args,
                              final long timeoutMs, @Nullable final AtomicReference<Process> running)
            throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(starter(context, "libytdl.so").getAbsolutePath());
        command.add(program.getAbsolutePath());
        command.addAll(args);
        final ProcessBuilder builder = new ProcessBuilder(command);
        final Map<String, String> env = builder.environment();
        final File python = python(context);
        final File lib = new File(python, "lib");
        env.put("JPP_PYTHON_LIB", new File(lib, "libpython3.14.so").getAbsolutePath());
        env.put("JPP_QJS_LIB", new File(lib, "libquickjs.so").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        env.put("PYTHONHOME", python.getAbsolutePath());
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONNOUSERSITE", "1");
        env.put("HOME", root(context).getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(context.getCacheDir(), "ytdlp").getAbsolutePath());
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        final String certificates = CaBundle.path(context);
        if (certificates != null) {
            env.put("SSL_CERT_FILE", certificates);
        }
        final Process process = builder.start();
        if (running != null) {
            running.set(process);
        }
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Thread errors = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                copy(in, err);
            } catch (IOException ignored) {
            }
        }, "yt-dlp errors");
        errors.start();
        final Thread timer = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
                process.destroy();
            } catch (InterruptedException ignored) {
            }
        }, "yt-dlp timeout");
        timer.start();
        final byte[] out;
        try (InputStream in = process.getInputStream()) {
            out = readAll(in);
        }
        int code;
        try {
            code = process.waitFor();
            errors.join(2000);
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            code = -1;
        } finally {
            timer.interrupt();
            if (running != null) {
                running.compareAndSet(process, null);
            }
        }
        return new Result(code, new String(out, "UTF-8"), err.toString("UTF-8"));
    }

    static String quickJs(final Context context) {
        return starter(context, "libqjs.so").getAbsolutePath();
    }

    static boolean hasQuickJs(final Context context) {
        return new File(python(context), "lib/libquickjs.so").isFile()
                && starter(context, "libqjs.so").isFile();
    }

    static String lastLine(final String text) {
        final String[] lines = text.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].trim().isEmpty()) {
                return lines[i].trim();
            }
        }
        return "";
    }

    public static final class Download {
        public interface Listener {
            void onPercent(int percent);
        }
    }

    private static void download(final String url, final File to,
                                 @Nullable final Download.Listener listener) throws IOException {
        final Request request = new Request.Builder().url(url).build();
        try (Response response = Http.client().newBuilder()
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.MINUTES)
                .build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Download failed (" + response.code() + "): " + url);
            }
            final long total = response.body().contentLength();
            try (InputStream in = response.body().byteStream();
                 OutputStream out = new FileOutputStream(to)) {
                final byte[] buffer = new byte[64 * 1024];
                long done = 0;
                int last = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (listener != null && total > 0) {
                        final int percent = (int) (done * 100 / total);
                        if (percent != last) {
                            last = percent;
                            listener.onPercent(percent);
                        }
                    }
                }
            }
        }
    }

    // yt-dlp is a zip behind a #! line, which Android's ZipFile refuses, so it is read in order
    // from the first entry
    private static void unzipInto(final File zip, final File dir) throws IOException {
        final File temp = new File(dir.getParentFile(), dir.getName() + ".part");
        deleteAll(temp);
        try (InputStream raw = new java.io.BufferedInputStream(new FileInputStream(zip))) {
            skipToZip(raw);
            final ZipInputStream file = new ZipInputStream(raw);
            final String base = temp.getCanonicalPath() + File.separator;
            ZipEntry entry;
            while ((entry = file.getNextEntry()) != null) {
                final File out = new File(temp, entry.getName());
                if (!out.getCanonicalPath().startsWith(base)) {
                    throw new IOException("Bad path in download: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
                try (OutputStream to = new FileOutputStream(out)) {
                    copy(file, to);
                }
            }
        } catch (IOException e) {
            deleteAll(temp);
            throw e;
        }
        deleteAll(dir);
        if (!temp.renameTo(dir)) {
            deleteAll(temp);
            throw new IOException("Could not unpack " + zip.getName());
        }
    }

    // to the first "PK\3\4"; a plain zip starts with it
    private static void skipToZip(final InputStream in) throws IOException {
        final byte[] magic = {'P', 'K', 3, 4};
        in.mark(4096);
        final byte[] head = new byte[4096];
        int length = 0;
        int read;
        while (length < head.length && (read = in.read(head, length, head.length - length)) != -1) {
            length += read;
        }
        in.reset();
        for (int i = 0; i + 4 <= length; i++) {
            if (head[i] == magic[0] && head[i + 1] == magic[1] && head[i + 2] == magic[2]
                    && head[i + 3] == magic[3]) {
                if (in.skip(i) != i) {
                    throw new IOException("Could not read the download");
                }
                return;
            }
        }
        throw new IOException("The download is not a zip");
    }

    @Nullable
    private static String sumFor(final File sums, final String name) throws IOException {
        try (InputStream in = new FileInputStream(sums)) {
            for (final String line : new String(readAll(in), "UTF-8").split("\n")) {
                final String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].equals(name)) {
                    return parts[0];
                }
            }
        }
        return null;
    }

    private static String sha256(final File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            final StringBuilder hex = new StringBuilder();
            for (final byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(final InputStream in, final OutputStream out) throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static void deleteAll(final File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteAll(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
