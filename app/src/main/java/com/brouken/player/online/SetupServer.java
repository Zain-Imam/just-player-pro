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
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/*
 * A page on the local network for filling in the long things.
 *
 * An API key is thirty-odd characters and a Stremio addon URL is longer, and
 * entering either on a television means a D-pad and an on-screen keyboard. This
 * puts a small page on the same network so a phone can do the typing, and the
 * keys are checked here rather than in two places.
 *
 * Deliberately small: one socket, no library, a connection at a time on its own
 * thread. It exists only while the settings screen that started it is open, and
 * a browser pointed at a stopped server is simply refused.
 *
 * Nothing is shown until the PIN on the television has been entered. What it is
 * not is secure against somebody already on your network -- the traffic is plain
 * HTTP across the LAN -- but it cannot be browsed by someone who has not seen
 * the screen, and it stops the moment that screen closes.
 */
public final class SetupServer {

    public interface Listener {
        void onSaved(String summary);
    }

    private static final int PORT_FIRST = 8723;
    private static final int PORT_LAST = 8733;
    private static final int READ_LIMIT = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 15_000;

    // The badge, drawn rather than fetched: serving the launcher icon meant a
    // second request and a resource decode that came back empty on the device.
    private static final String MARK =
            "<svg width=\"56\" height=\"56\" viewBox=\"0 0 108 108\" xmlns=\"http://www.w3.org/2000/svg\">"
                    + "<path d=\"M54 6 L98 84 a12 12 0 0 1 -10 18 H20 a12 12 0 0 1 -10 -18 Z\""
                    + " fill=\"#F2761E\"/>"
                    + "<path d=\"M44 40 L74 60 L44 80 Z\" fill=\"#fff\"/></svg>";

    private final Context context;
    private final Listener listener;
    private final String pin;
    private final String token;

    private ServerSocket socket;
    private volatile boolean running;
    private volatile boolean unlocked;

    public SetupServer(final Context context, final Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        final SecureRandom random = new SecureRandom();
        this.pin = String.format(java.util.Locale.US, "%06d", random.nextInt(1_000_000));
        this.token = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
    }

    public String pin() {
        return pin;
    }

    public boolean isRunning() {
        return running;
    }

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
        new Thread(this::serve, "setup-server").start();
        return "http://" + host + ":" + socket.getLocalPort();
    }

    public void stop() {
        running = false;
        unlocked = false;
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
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                // accept() throws when stop() closes the socket, which is normal.
                return;
            }
            // A browser opens several connections at once -- the page, the
            // favicon, the logo. Serving them one after another on the accept
            // loop made a save look like it had been swallowed.
            new Thread(() -> {
                try {
                    client.setSoTimeout(SOCKET_TIMEOUT_MS);
                    handle(client);
                } catch (IOException ignored) {
                } finally {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                    }
                }
            }, "setup-client").start();
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
        boolean expectContinue = false;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            final String lower = line.toLowerCase(java.util.Locale.US);
            if (lower.startsWith("content-length:")) {
                try {
                    length = Integer.parseInt(line.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (lower.startsWith("expect:") && lower.contains("100-continue")) {
                expectContinue = true;
            }
        }
        if (expectContinue) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        }

        if (path.startsWith("/favicon.ico")) {
            respond(out, 404, "text/plain", "");
            return;
        }

        final Map<String, String> form = "POST".equals(method)
                ? parseForm(readBody(in, length)) : new LinkedHashMap<>();

        if (path.startsWith("/unlock")) {
            if (pin.equals(value(form, "pin"))) {
                unlocked = true;
                respond(out, 200, "text/html; charset=utf-8", form(null, null));
            } else {
                respond(out, 200, "text/html; charset=utf-8",
                        lock("That PIN does not match the one on the player."));
            }
            return;
        }

        // Everything past here needs the PIN to have been entered on this
        // server. Writing also needs the token the form carries, so a request
        // from somewhere else cannot ride on the fact that somebody unlocked.
        if (!unlocked) {
            respond(out, path.startsWith("/test") || path.startsWith("/save") ? 403 : 200,
                    path.startsWith("/test") || path.startsWith("/save")
                            ? "text/plain" : "text/html; charset=utf-8",
                    path.startsWith("/test") || path.startsWith("/save")
                            ? "Locked" : lock(null));
            return;
        }
        if ((path.startsWith("/test") || path.startsWith("/save"))
                && !token.equals(value(form, "token"))) {
            respond(out, 403, "text/plain", "Locked");
            return;
        }

        if (path.startsWith("/test")) {
            respond(out, 200, "text/plain", test(form));
            return;
        }
        if (path.startsWith("/save")) {
            respond(out, 200, "text/html; charset=utf-8", save(form));
            return;
        }
        respond(out, 200, "text/html; charset=utf-8", form(null, null));
    }

    // ---------------------------------------------------------------- actions

    private String test(final Map<String, String> form) {
        final String field = value(form, "field");
        if (field == null) {
            return "Nothing to test";
        }
        String entered = value(form, "value");
        if (entered == null || entered.isEmpty()) {
            // Empty means "the one already saved", so Test works on a key that
            // is set but not shown.
            entered = ApiKeys.get(context, field);
        }
        if (entered == null || entered.isEmpty()) {
            return "Nothing entered";
        }
        return KeyCheck.check(field, entered).message;
    }

    private String save(final Map<String, String> form) {
        final SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences(context).edit();
        final StringBuilder good = new StringBuilder();
        final StringBuilder bad = new StringBuilder();
        int saved = 0;

        for (final Map.Entry<String, String> field : keyFields().entrySet()) {
            final String entered = value(form, field.getKey());
            if (entered == null || entered.isEmpty()) {
                continue;
            }
            final KeyCheck.Result result = KeyCheck.check(field.getKey(), entered);
            if (result.ok) {
                editor.putString(field.getKey(), entered);
                saved++;
                good.append(field.getValue()).append(" · ");
            } else {
                bad.append(field.getValue()).append(": ").append(result.message).append(" · ");
            }
        }

        for (int slot = 1; slot <= SubtitleAddons.MAX; slot++) {
            final String key = SubtitleAddons.key(slot);
            if (!form.containsKey(key)) {
                continue;
            }
            final String entered = value(form, key);
            if (entered == null || entered.isEmpty()) {
                editor.remove(key);
                continue;
            }
            final String normalized = SubtitleAddons.normalizeUrl(entered);
            if (normalized == null) {
                bad.append("Addon ").append(slot).append(": not an addon URL · ");
                continue;
            }
            editor.putString(key, normalized);
            saved++;
            good.append("Addon ").append(slot).append(" · ");
        }

        final String language = value(form, "subtitleLanguage");
        if (language != null && !language.isEmpty()) {
            editor.putString("subtitleLanguage", language.trim());
            saved++;
            good.append("Language · ");
        }

        editor.apply();
        if (listener != null) {
            listener.onSaved(saved + " saved");
        }
        return form(trim(good), trim(bad));
    }

    @Nullable
    private static String trim(final StringBuilder text) {
        final String value = text.toString().trim();
        if (value.endsWith("·")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private static Map<String, String> keyFields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ApiKeys.PREF_TMDB, "TMDB key");
        fields.put(ApiKeys.PREF_OPENSUBTITLES, "OpenSubtitles key");
        fields.put(ApiKeys.PREF_OPENSUBTITLES_USER, "OpenSubtitles user");
        fields.put(ApiKeys.PREF_OPENSUBTITLES_PASSWORD, "OpenSubtitles password");
        fields.put(ApiKeys.PREF_SUBDL, "SubDL key");
        fields.put(ApiKeys.PREF_WYZIE, "Wyzie key");
        return fields;
    }

    // ------------------------------------------------------------------ pages

    private String head(final String title) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title><style>"
                + "body{font:16px system-ui,sans-serif;margin:0;padding:24px;background:#111;"
                + "color:#eee;max-width:620px;margin:auto}"
                + "h1{font-size:20px;margin:8px 0 4px}h2{font-size:14px;color:#888;"
                + "margin:28px 0 4px;text-transform:uppercase;letter-spacing:.06em}"
                + "label{display:block;margin:14px 0 4px;color:#bbb;font-size:14px}"
                + "input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;"
                + "border:1px solid #3a3a3a;border-radius:10px;background:#1c1c1c;color:#eee}"
                + "button{padding:12px 16px;font-size:15px;border:0;border-radius:10px;"
                + "background:#F2761E;color:#fff;font-weight:600}"
                + ".wide{width:100%;margin-top:24px;padding:15px;font-size:17px}"
                + ".row{display:flex;gap:8px;align-items:center}"
                + ".row input{flex:1}.row button{background:#333;white-space:nowrap}"
                + ".res{font-size:13px;color:#9a9a9a;min-height:18px;margin-top:4px}"
                + ".ok{color:#7ddc7d}.bad{color:#ff8a80}"
                + ".banner{padding:14px;border-radius:10px;margin:16px 0;font-size:15px}"
                + ".banner.ok{background:#14351a;border:1px solid #2f6b39}"
                + ".banner.bad{background:#3a1a1a;border:1px solid #7a3030}"
                + ".center{text-align:center}svg{width:56px;height:56px;display:inline-block}"
                + "small{color:#777;font-size:13px}"
                + "</style></head><body>";
    }

    private String lock(@Nullable final String error) {
        final StringBuilder html = new StringBuilder(head("Just Player Pro"));
        html.append("<div class=\"center\">" + MARK + "")
                .append("<h1>Just Player Pro</h1>")
                .append("<small>Enter the PIN shown on the player.</small></div>");
        if (error != null) {
            html.append("<div class=\"banner bad\">").append(escape(error)).append("</div>");
        }
        html.append("<form method=\"post\" action=\"/unlock\">")
                .append("<label>PIN</label>")
                .append("<input name=\"pin\" inputmode=\"numeric\" autocomplete=\"off\" ")
                .append("autofocus required>")
                .append("<button class=\"wide\" type=\"submit\">Continue</button>")
                .append("</form></body></html>");
        return html.toString();
    }

    private String form(@Nullable final String good, @Nullable final String bad) {
        final StringBuilder html = new StringBuilder(head("Just Player Pro"));
        html.append("<div class=\"center\">" + MARK + "")
                .append("<h1>Just Player Pro</h1></div>");

        if (good != null) {
            html.append("<div class=\"banner ok\">Saved: ").append(escape(good)).append("</div>");
        }
        if (bad != null) {
            html.append("<div class=\"banner bad\">Not saved: ").append(escape(bad)).append("</div>");
        }

        html.append("<form method=\"post\" action=\"/save\" id=\"f\">")
                .append("<input type=\"hidden\" name=\"token\" value=\"").append(token).append("\">")
                .append("<h2>Keys</h2>")
                .append("<small>A key already set is not shown. Leave it empty to keep it.</small>");

        for (final Map.Entry<String, String> field : keyFields().entrySet()) {
            final boolean set = ApiKeys.has(context, field.getKey());
            html.append("<label>").append(escape(field.getValue()))
                    .append(set ? " — already set" : "").append("</label>")
                    .append("<div class=\"row\"><input name=\"").append(field.getKey())
                    .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">")
                    .append("<button type=\"button\" onclick=\"t('")
                    .append(field.getKey()).append("')\">Test</button></div>")
                    .append("<div class=\"res\" id=\"r_").append(field.getKey()).append("\"></div>");
        }

        html.append("<h2>Subtitle language</h2><label>Two-letter code</label>")
                .append("<input name=\"subtitleLanguage\" autocomplete=\"off\" value=\"")
                .append(escape(language())).append("\">")
                .append("<h2>Stremio subtitle addons</h2>")
                .append("<small>These are shown because they are not secrets.</small>");

        for (int slot = 1; slot <= SubtitleAddons.MAX; slot++) {
            final String key = SubtitleAddons.key(slot);
            final String saved = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(key, "");
            html.append("<label>Addon ").append(slot).append("</label>")
                    .append("<input name=\"").append(key)
                    .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\"")
                    .append(" value=\"").append(escape(saved == null ? "" : saved)).append("\">");
        }

        html.append("<button class=\"wide\" type=\"submit\">Check and save</button></form>")
                .append("<script>")
                .append("function t(k){var r=document.getElementById('r_'+k);")
                .append("r.textContent='Checking…';r.className='res';")
                .append("var b=new URLSearchParams();b.append('token','").append(token)
                .append("');b.append('field',k);")
                .append("b.append('value',document.getElementsByName(k)[0].value);")
                .append("fetch('/test',{method:'POST',body:b}).then(function(x){return x.text()})")
                .append(".then(function(m){r.textContent=m;")
                .append("r.className='res '+(/reject|not|No answer|rate|HTTP/i.test(m)?'bad':'ok')})")
                .append(".catch(function(){r.textContent='The player is no longer listening';")
                .append("r.className='res bad'})}")
                .append("</script></body></html>");
        return html.toString();
    }

    private String language() {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("subtitleLanguage", "en");
        return value == null ? "en" : value;
    }

    // ------------------------------------------------------------- plumbing

    // Decoded and re-encoded rather than streamed off disk: the icon is a
    // density-qualified resource and opening it as a raw stream came back
    // empty, which showed as a broken image on the phone.


    private static void respond(final OutputStream out, final int code,
                                final String type, final String body) throws IOException {
        final byte[] bytes = body.getBytes("UTF-8");
        final String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " ERR") + "\r\n"
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
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
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
