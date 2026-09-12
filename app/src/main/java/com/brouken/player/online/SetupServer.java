package com.brouken.player.online;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/*
 * A page on the local network for filling in the long things.
 *
 * An API key is thirty-odd characters and a Stremio addon URL is longer, and
 * entering either on a television means a D-pad and an on-screen keyboard. This
 * puts a small page on the same network so a phone can do the typing, and the
 * keys are checked here rather than in two places.
 *
 * Deliberately small: one socket, one connection at a time, no library. It
 * exists only while the settings screen that started it is open.
 *
 * What it is not is secure against somebody already on your network. A PIN
 * shown on the television has to be entered before anything is accepted, the
 * page never sends back what is already stored, and the whole thing stops when
 * the screen closes -- but the traffic is plain HTTP across the LAN. That is a
 * fair trade on a home network and not one to make on a shared one.
 */
public final class SetupServer {

    public interface Listener {
        void onSaved(String summary);
    }

    private static final int PORT_FIRST = 8723;
    private static final int PORT_LAST = 8733;
    private static final int READ_LIMIT = 64 * 1024;

    private final Context context;
    private final Listener listener;
    private final String pin;

    private ServerSocket socket;
    private Thread thread;
    private volatile boolean running;

    public SetupServer(final Context context, final Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.pin = String.format(java.util.Locale.US, "%06d", new Random().nextInt(1_000_000));
    }

    public String pin() {
        return pin;
    }

    /** The address to type into a browser, or null if nothing could be opened. */
    @Nullable
    public String start() {
        for (int port = PORT_FIRST; port <= PORT_LAST; port++) {
            try {
                socket = new ServerSocket(port);
                break;
            } catch (IOException ignored) {
                socket = null;
            }
        }
        if (socket == null) {
            return null;
        }
        final String host = localAddress();
        if (host == null) {
            stop();
            return null;
        }
        running = true;
        thread = new Thread(this::serve, "setup-server");
        thread.start();
        return "http://" + host + ":" + socket.getLocalPort();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private void serve() {
        while (running) {
            try (Socket client = socket.accept()) {
                handle(client);
            } catch (IOException e) {
                // accept() throws when stop() closes the socket, which is normal.
                if (running) {
                    Subtitles.Log.note("setup server: " + e.getMessage());
                }
            }
        }
    }

    private void handle(final Socket client) throws IOException {
        final InputStream in = client.getInputStream();
        final OutputStream out = client.getOutputStream();

        final String head = readLine(in);
        if (head == null) {
            return;
        }
        final String[] parts = head.split(" ");
        if (parts.length < 2) {
            return;
        }
        final String method = parts[0];
        final String path = parts[1];

        int length = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            final String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if ("POST".equals(method) && path.startsWith("/save")) {
            final Map<String, String> form = parseForm(readBody(in, length));
            respond(out, "text/html; charset=utf-8", save(form));
            return;
        }
        respond(out, "text/html; charset=utf-8", page(null));
    }

    private String save(final Map<String, String> form) {
        if (!pin.equals(value(form, "pin"))) {
            return page("That PIN does not match the one on screen.");
        }

        final SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        final StringBuilder report = new StringBuilder();
        int saved = 0;

        for (final Map.Entry<String, String> field : fields().entrySet()) {
            final String entered = value(form, field.getKey());
            if (entered == null || entered.isEmpty()) {
                continue;
            }
            final KeyCheck.Result result = KeyCheck.check(field.getKey(), entered);
            if (result.ok) {
                editor.putString(field.getKey(), entered);
                saved++;
            }
            report.append("<p class=\"").append(result.ok ? "ok" : "bad").append("\">")
                    .append(escape(field.getValue())).append(": ")
                    .append(escape(result.message)).append("</p>");
        }

        for (int slot = 1; slot <= SubtitleAddons.MAX; slot++) {
            final String entered = value(form, SubtitleAddons.key(slot));
            if (entered == null || entered.isEmpty()) {
                continue;
            }
            final String normalized = SubtitleAddons.normalizeUrl(entered);
            if (normalized == null) {
                report.append("<p class=\"bad\">Addon ").append(slot)
                        .append(": that does not look like an addon URL</p>");
                continue;
            }
            editor.putString(SubtitleAddons.key(slot), normalized);
            saved++;
            report.append("<p class=\"ok\">Addon ").append(slot).append(": saved</p>");
        }

        final String language = value(form, "subtitleLanguage");
        if (language != null && !language.isEmpty()) {
            editor.putString("subtitleLanguage", language.trim());
            saved++;
            report.append("<p class=\"ok\">Subtitle language: saved</p>");
        }

        editor.apply();
        if (listener != null) {
            listener.onSaved(saved + " saved");
        }
        return page(report.toString());
    }

    private static Map<String, String> fields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ApiKeys.PREF_TMDB, "TMDB key");
        fields.put(ApiKeys.PREF_OPENSUBTITLES, "OpenSubtitles key");
        fields.put(ApiKeys.PREF_OPENSUBTITLES_USER, "OpenSubtitles user");
        fields.put(ApiKeys.PREF_OPENSUBTITLES_PASSWORD, "OpenSubtitles password");
        fields.put(ApiKeys.PREF_SUBDL, "SubDL key");
        fields.put(ApiKeys.PREF_WYZIE, "Wyzie key");
        return fields;
    }

    // Nothing already stored is ever sent back: somebody who reaches this page
    // should not be able to read the keys, only replace them.
    private String page(@Nullable final String report) {
        final StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Just Player Pro setup</title><style>")
                .append("body{font:16px system-ui,sans-serif;margin:0;padding:24px;")
                .append("background:#111;color:#eee;max-width:640px;margin:auto}")
                .append("h1{font-size:20px}label{display:block;margin:16px 0 4px;color:#bbb}")
                .append("input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;")
                .append("border:1px solid #444;border-radius:8px;background:#1c1c1c;color:#eee}")
                .append("button{margin-top:24px;width:100%;padding:14px;font-size:17px;border:0;")
                .append("border-radius:8px;background:#F2761E;color:#fff;font-weight:600}")
                .append("h2{font-size:15px;color:#888;margin-top:28px;font-weight:600}")
                .append(".ok{color:#7ddc7d}.bad{color:#ff8a80}small{color:#888}")
                .append("</style></head><body><h1>Just Player Pro</h1>");

        if (report != null && !report.isEmpty()) {
            html.append(report);
        }

        html.append("<form method=\"post\" action=\"/save\">")
                .append("<label>PIN shown on the player</label>")
                .append("<input name=\"pin\" inputmode=\"numeric\" autocomplete=\"off\" required>")
                .append("<small>Leave a field empty to keep what is already set.</small>")
                .append("<h2>Keys</h2>");

        for (final Map.Entry<String, String> field : fields().entrySet()) {
            html.append("<label>").append(escape(field.getValue())).append("</label>")
                    .append("<input name=\"").append(field.getKey())
                    .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        }

        html.append("<h2>Subtitle language</h2>")
                .append("<label>Two-letter code, such as en</label>")
                .append("<input name=\"subtitleLanguage\" autocomplete=\"off\">")
                .append("<h2>Stremio subtitle addons</h2>");

        for (int slot = 1; slot <= SubtitleAddons.MAX; slot++) {
            html.append("<label>Addon ").append(slot).append("</label>")
                    .append("<input name=\"").append(SubtitleAddons.key(slot))
                    .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">");
        }

        html.append("<button type=\"submit\">Check and save</button></form></body></html>");
        return html.toString();
    }

    // ------------------------------------------------------------ plumbing

    private static void respond(final OutputStream out, final String type, final String body)
            throws IOException {
        final byte[] bytes = body.getBytes("UTF-8");
        final String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    @Nullable
    private static String readLine(final InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
            if (buffer.size() > 8192) {
                break;
            }
        }
        if (c == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString("UTF-8");
    }

    private static String readBody(final InputStream in, final int length) throws IOException {
        final int wanted = Math.min(length, READ_LIMIT);
        final byte[] body = new byte[wanted];
        int read = 0;
        while (read < wanted) {
            final int n = in.read(body, read, wanted - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return new String(body, 0, read, "UTF-8");
    }

    private static Map<String, String> parseForm(final String body) {
        final Map<String, String> form = new LinkedHashMap<>();
        for (final String pair : body.split("&")) {
            final int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            try {
                form.put(URLDecoder.decode(pair.substring(0, equals), "UTF-8"),
                        URLDecoder.decode(pair.substring(equals + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return form;
    }

    @Nullable
    private static String value(final Map<String, String> form, final String key) {
        final String raw = form.get(key);
        return raw == null ? null : raw.trim();
    }

    private static String escape(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Nullable
    private static String localAddress() {
        try {
            final Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (final NetworkInterface network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (final InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
