package com.brouken.player;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// request headers from launch extras: MX Player's name/value array,
// "Name: value" strings, a Bundle, a list or lines; invalid ones are dropped
public final class LaunchHeaders {

    private LaunchHeaders() {
    }

    @NonNull
    public static LinkedHashMap<String, String> read(@Nullable final Bundle bundle,
                                                     @NonNull final String key) {
        final LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (bundle == null || !bundle.containsKey(key)) {
            return headers;
        }
        final Object raw;
        try {
            raw = bundle.get(key);
        } catch (Exception e) {
            // a class this app does not have; nothing usable
            return headers;
        }
        parse(raw, headers);
        return headers;
    }

    static void parse(@Nullable final Object raw, @NonNull final Map<String, String> into) {
        if (raw instanceof String[]) {
            fromStrings((String[]) raw, into);
        } else if (raw instanceof List) {
            final List<?> list = (List<?>) raw;
            final String[] strings = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                strings[i] = list.get(i) == null ? null : String.valueOf(list.get(i));
            }
            fromStrings(strings, into);
        } else if (raw instanceof Bundle) {
            final Bundle bundle = (Bundle) raw;
            for (final String name : bundle.keySet()) {
                final Object value = bundle.get(name);
                if (value != null) {
                    put(into, name, String.valueOf(value));
                }
            }
        } else if (raw instanceof Map) {
            for (final Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    put(into, String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        } else if (raw instanceof CharSequence) {
            fromStrings(raw.toString().split("\\r?\\n"), into);
        }
    }

    // a colon in an even entry means whole headers (names have none, values may)
    private static void fromStrings(@NonNull final String[] values,
                                    @NonNull final Map<String, String> into) {
        boolean whole = false;
        for (int i = 0; i < values.length; i += 2) {
            if (values[i] != null && values[i].indexOf(':') > 0) {
                whole = true;
                break;
            }
        }
        if (whole || values.length == 1) {
            for (final String value : values) {
                if (value == null) {
                    continue;
                }
                final int colon = value.indexOf(':');
                if (colon > 0) {
                    put(into, value.substring(0, colon), value.substring(colon + 1));
                }
            }
            return;
        }
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i] != null && values[i + 1] != null) {
                put(into, values[i], values[i + 1]);
            }
        }
    }

    private static void put(final Map<String, String> into, final String name,
                            final String value) {
        final String cleanName = name.trim();
        final String cleanValue = value.trim();
        if (!isToken(cleanName) || cleanValue.indexOf('\r') >= 0 || cleanValue.indexOf('\n') >= 0) {
            return;
        }
        into.put(cleanName, cleanValue);
    }

    // HTTP token: no spaces or separators
    static boolean isToken(final String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
