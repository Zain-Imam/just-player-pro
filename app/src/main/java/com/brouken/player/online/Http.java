package com.brouken.player.online;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class Http {

    public static final String USER_AGENT = "JustPlayerPro v1.0";

    private static final int TIMEOUT_SECONDS = 20;

    private static final Pattern DISPOSITION_EXTENDED =
            Pattern.compile("filename\\*\\s*=\\s*[^']*'[^']*'([^;\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPOSITION_PLAIN =
            Pattern.compile("filename\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);

    private static volatile OkHttpClient client;

    private Http() {
    }

    public static OkHttpClient client() {
        if (client == null) {
            synchronized (Http.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    public static final class Result {
        public final int code;
        @Nullable
        public final String body;
        @Nullable
        public final String error;

        Result(int code, @Nullable String body, @Nullable String error) {
            this.code = code;
            this.body = body;
            this.error = error;
        }

        public boolean ok() {
            return code >= 200 && code < 300 && body != null;
        }

        @Nullable
        public JSONObject json() {
            if (!ok()) {
                return null;
            }
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                return null;
            }
        }

        @Nullable
        public JSONArray jsonArray() {
            if (!ok()) {
                return null;
            }
            try {
                return new JSONArray(body);
            } catch (JSONException e) {
                return null;
            }
        }
    }

    public static String query(final Map<String, String> params) {
        final List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);

        final StringBuilder sb = new StringBuilder();
        for (final String key : keys) {
            final String value = params.get(key);
            if (value == null || value.isEmpty()) {
                continue;
            }
            sb.append(sb.length() == 0 ? '?' : '&')
                    .append(key)
                    .append('=')
                    .append(encode(value));
        }
        return sb.toString();
    }

    private static String encode(final String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return value;
        }
    }

    public static Map<String, String> params() {
        return new LinkedHashMap<>();
    }

    @NonNull
    public static Result get(final String url, @Nullable final Map<String, String> headers) {
        final Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json");

        if (headers != null) {
            for (final Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }

        try (Response response = client().newCall(builder.build()).execute()) {
            final ResponseBody body = response.body();
            return new Result(response.code(), body == null ? null : body.string(), null);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return new Result(-1, null, e.toString());
        }
    }

    @NonNull
    public static Result post(final String url, @Nullable final Map<String, String> headers,
                              final String json) {
        final Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")));

        if (headers != null) {
            for (final Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }

        try (Response response = client().newCall(builder.build()).execute()) {
            final ResponseBody body = response.body();
            return new Result(response.code(), body == null ? null : body.string(), null);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return new Result(-1, null, e.toString());
        }
    }

    @Nullable
    public static byte[] getBytes(final String url, @Nullable final Map<String, String> headers) {
        final Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT);

        if (headers != null) {
            for (final Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }

        try (Response response = client().newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            final ResponseBody body = response.body();
            return body == null ? null : body.bytes();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    public static String serverFileName(final String url) {
        String name = dispositionName(head(url));
        if (name == null) {
            name = dispositionName(firstByte(url));
        }
        return name;
    }

    @Nullable
    private static Response head(final String url) {
        try {
            return client().newCall(new Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", USER_AGENT)
                    .build()).execute();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static Response firstByte(final String url) {
        try {
            return client().newCall(new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .build()).execute();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    @Nullable
    private static String dispositionName(@Nullable final Response response) {
        if (response == null) {
            return null;
        }
        try {
            final String header = response.header("Content-Disposition");
            if (header == null) {
                return null;
            }

            final Matcher extended = DISPOSITION_EXTENDED.matcher(header);
            if (extended.find()) {
                final String decoded = decode(extended.group(1));
                if (decoded != null && !decoded.isEmpty()) {
                    return sanitise(decoded);
                }
            }

            final Matcher plain = DISPOSITION_PLAIN.matcher(header);
            if (plain.find()) {
                final String value = plain.group(1) != null ? plain.group(1) : plain.group(2);
                if (value != null && !value.isEmpty()) {
                    return sanitise(value);
                }
            }
            return null;
        } finally {
            response.close();
        }
    }

    @Nullable
    private static String decode(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return URLDecoder.decode(raw, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return raw;
        }
    }

    private static String sanitise(final String value) {
        String name = value.trim();
        final int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        return name;
    }
}
