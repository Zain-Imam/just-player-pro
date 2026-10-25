package com.brouken.player.online;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.brouken.player.R;

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

// LAN page locked by the on-screen PIN; plain HTTP, only while settings is open
public final class SetupServer {

    public interface Listener {
        void onSaved(int count);
    }

    private static final int PORT_FIRST = 8723;
    private static final int PORT_LAST = 8733;
    private static final int READ_LIMIT = 64 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 15_000;

    private static final String MARK =
            "<svg width=\"56\" height=\"56\" viewBox=\"0 0 108 108\" xmlns=\"http://www.w3.org/2000/svg\">"
                    + "<path d=\"M54 6 L98 84 a12 12 0 0 1 -10 18 H20 a12 12 0 0 1 -10 -18 Z\""
                    + " fill=\"#F2761E\"/>"
                    + "<path d=\"M44 40 L74 60 L44 80 Z\" fill=\"#fff\"/></svg>";

    private final Context context;
    // the page's words, in the language of the screen that started it
    private final Context words;
    private final Listener listener;
    private final String pin;
    private final String token;

    private ServerSocket socket;
    private volatile boolean running;
    private volatile boolean unlocked;

    public SetupServer(final Context context, final Listener listener) {
        this.context = context.getApplicationContext();
        this.words = context.createConfigurationContext(context.getResources().getConfiguration());
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
                // accept() throws when stop() closes the socket
                return;
            }
            // a thread per connection: browsers open several at once
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
                        lock(text(R.string.setup_wrong_pin)));
            }
            return;
        }

        // the rest needs the PIN; writes also need the form's token
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

    // "1" or "0" and a line break first, so the page colours it without reading the words
    private String test(final Map<String, String> form) {
        final String field = value(form, "field");
        if (field == null) {
            return "0\n" + text(R.string.setup_nothing_to_test);
        }
        final boolean addon = field.startsWith("subtitleAddon");

        String entered = value(form, "value");
        if (entered == null || entered.isEmpty()) {
            // empty means test the value already saved
            entered = addon
                    ? PreferenceManager.getDefaultSharedPreferences(context)
                            .getString(field, "")
                    : ApiKeys.get(context, field);
        }
        if (entered == null || entered.isEmpty()) {
            return "0\n" + text(R.string.setup_nothing_entered);
        }
        if (addon) {
            return describe(entered);
        }
        final KeyCheck.Result result = KeyCheck.check(field, entered);
        return (result.ok ? "1\n" : "0\n") + result.message(words);
    }

    // asks the addon for a real subtitle, the same check the app makes
    private String describe(final String entered) {
        final String normalized = SubtitleAddons.normalizeUrl(entered);
        if (normalized == null) {
            return "0\n" + text(R.string.pref_addon_invalid);
        }
        final SubtitleAddons.Probe probe = SubtitleAddons.probe(normalized);
        if (!probe.accepted()) {
            return "0\n" + probe.reason(words);
        }
        return "1\n" + text(R.string.setup_addon_works, probe.name(words), probe.subtitles, probe.languages)
                + (probe.downloadVerified ? "" : " " + text(R.string.pref_addon_ok_unverified));
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
                bad.append(field.getValue()).append(": ").append(result.message(words)).append(" · ");
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
            final String slotName = text(R.string.pref_addon_slot, slot);
            if (normalized == null) {
                bad.append(slotName).append(": ").append(text(R.string.pref_addon_invalid)).append(" · ");
                continue;
            }
            final SubtitleAddons.Probe probe = SubtitleAddons.probe(normalized);
            if (!probe.accepted()) {
                bad.append(slotName).append(": ").append(probe.reason(words)).append(" · ");
                continue;
            }
            editor.putString(key, normalized);
            saved++;
            good.append(slotName).append(" · ");
        }

        final String language = value(form, "subtitleLanguage");
        if (language != null && !language.isEmpty()) {
            editor.putString("subtitleLanguage", language.trim());
            saved++;
            good.append(text(R.string.pref_subtitle_language)).append(" · ");
        }

        editor.apply();
        if (listener != null) {
            listener.onSaved(saved);
        }
        return form(trim(good), trim(bad));
    }

    private String text(final int id, final Object... args) {
        return words.getString(id, args);
    }

    @Nullable
    private static String trim(final StringBuilder text) {
        final String value = text.toString().trim();
        if (value.endsWith("·")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private Map<String, String> keyFields() {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ApiKeys.PREF_TMDB, text(R.string.pref_key_tmdb));
        fields.put(ApiKeys.PREF_OPENSUBTITLES, text(R.string.pref_key_opensubtitles));
        fields.put(ApiKeys.PREF_OPENSUBTITLES_USER, text(R.string.pref_key_opensubtitles_user));
        fields.put(ApiKeys.PREF_OPENSUBTITLES_PASSWORD, text(R.string.pref_key_opensubtitles_password));
        fields.put(ApiKeys.PREF_SUBDL, text(R.string.pref_key_subdl));
        fields.put(ApiKeys.PREF_WYZIE, text(R.string.pref_key_wyzie));
        return fields;
    }

    private String head(final String title) {
        final java.util.Locale locale = words.getResources().getConfiguration().getLocales().get(0);
        final boolean rtl = android.text.TextUtils.getLayoutDirectionFromLocale(locale)
                == android.view.View.LAYOUT_DIRECTION_RTL;
        return "<!doctype html><html lang=\"" + escape(locale.toLanguageTag()) + "\""
                + (rtl ? " dir=\"rtl\"" : "") + "><head><meta charset=\"utf-8\">"
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
                .append("<small>").append(escape(text(R.string.setup_enter_pin))).append("</small></div>");
        if (error != null) {
            html.append("<div class=\"banner bad\">").append(escape(error)).append("</div>");
        }
        html.append("<form method=\"post\" action=\"/unlock\">")
                .append("<label>").append(escape(text(R.string.setup_pin))).append("</label>")
                .append("<input name=\"pin\" inputmode=\"numeric\" autocomplete=\"off\" ")
                .append("autofocus required>")
                .append("<button class=\"wide\" type=\"submit\">")
                .append(escape(text(R.string.setup_continue))).append("</button>")
                .append("</form></body></html>");
        return html.toString();
    }

    private String form(@Nullable final String good, @Nullable final String bad) {
        final StringBuilder html = new StringBuilder(head("Just Player Pro"));
        html.append("<div class=\"center\">" + MARK + "")
                .append("<h1>Just Player Pro</h1></div>");

        if (good != null) {
            html.append("<div class=\"banner ok\">").append(escape(text(R.string.setup_saved, good)))
                    .append("</div>");
        }
        if (bad != null) {
            html.append("<div class=\"banner bad\">").append(escape(text(R.string.setup_not_saved, bad)))
                    .append("</div>");
        }

        final String test = escape(text(R.string.setup_test));
        html.append("<form method=\"post\" action=\"/save\" id=\"f\">")
                .append("<input type=\"hidden\" name=\"token\" value=\"").append(token).append("\">")
                .append("<h2>").append(escape(text(R.string.setup_keys))).append("</h2>")
                .append("<small>").append(escape(text(R.string.setup_keys_note))).append("</small>");

        for (final Map.Entry<String, String> field : keyFields().entrySet()) {
            final boolean set = ApiKeys.has(context, field.getKey());
            html.append("<label>").append(escape(set
                            ? text(R.string.setup_already_set, field.getValue()) : field.getValue()))
                    .append("</label>")
                    .append("<div class=\"row\"><input name=\"").append(field.getKey())
                    .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\">")
                    .append("<button type=\"button\" onclick=\"t('")
                    .append(field.getKey()).append("')\">").append(test).append("</button></div>")
                    .append("<div class=\"res\" id=\"r_").append(field.getKey()).append("\"></div>");
        }

        html.append("<h2>").append(escape(text(R.string.pref_subtitle_language))).append("</h2>")
                .append("<label>").append(escape(text(R.string.setup_two_letter_code))).append("</label>")
                .append("<input name=\"subtitleLanguage\" autocomplete=\"off\" value=\"")
                .append(escape(language())).append("\">")
                .append("<h2>").append(escape(text(R.string.pref_addons))).append("</h2>")
                .append("<small>").append(escape(text(R.string.setup_addons_note))).append("</small>");

        for (int slot = 1; slot <= SubtitleAddons.MAX; slot++) {
            final String key = SubtitleAddons.key(slot);
            final String saved = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(key, "");
            html.append("<label>").append(escape(text(R.string.pref_addon_slot, slot))).append("</label>")
                    .append("<div class=\"row\"><input name=\"").append(key)
                    .append("\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\"")
                    .append(" value=\"").append(escape(saved == null ? "" : saved)).append("\">")
                    .append("<button type=\"button\" onclick=\"t('").append(key)
                    .append("')\">").append(test).append("</button></div>")
                    .append("<div class=\"res\" id=\"r_").append(key).append("\"></div>");
        }

        html.append("<button class=\"wide\" type=\"submit\">")
                .append(escape(text(R.string.setup_check_and_save))).append("</button></form>")
                .append("<script>")
                .append("function t(k){var r=document.getElementById('r_'+k);")
                .append("r.textContent=").append(js(text(R.string.pref_checking_all))).append(";r.className='res';")
                .append("var b=new URLSearchParams();b.append('token','").append(token)
                .append("');b.append('field',k);")
                .append("b.append('value',document.getElementsByName(k)[0].value);")
                .append("fetch('/test',{method:'POST',body:b}).then(function(x){return x.text()})")
                .append(".then(function(m){r.textContent=m.substring(2);")
                .append("r.className='res '+(m.charAt(0)==='1'?'ok':'bad')})")
                .append(".catch(function(){r.textContent=").append(js(text(R.string.setup_gone))).append(";")
                .append("r.className='res bad'})}")
                .append("</script></body></html>");
        return html.toString();
    }

    private String language() {
        final String value = PreferenceManager.getDefaultSharedPreferences(context)
                .getString("subtitleLanguage", "en");
        return value == null ? "en" : value;
    }

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

    // a quoted string for the page's script; a translation may hold quotes or "</"
    private static String js(final String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("<", "\\u003c") + "'";
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
