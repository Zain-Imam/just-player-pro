package com.brouken.player.online;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SubtitleAddons {

    public static final int MAX = 5;

    public static final String PREF_PREFIX = "subtitleAddon";

    private static final String PROBE_PATH = "/subtitles/movie/tt1375666.json";

    private static final int MAX_PROBE_BYTES = 2 * 1024 * 1024;

    private static final int DOWNLOAD_ATTEMPTS = 3;

    private SubtitleAddons() {
    }

    public static final class Addon {
        @NonNull
        public final String name;
        @NonNull
        public final String baseUrl;

        Addon(@NonNull String name, @NonNull String baseUrl) {
            this.name = name;
            this.baseUrl = baseUrl;
        }
    }

    // ------------------------------------------------------------- the list

    private static final String DEFAULT_ADDON = "https://opensubtitles-v3.strem.io";
    private static final String PREF_SEEDED = "subtitleAddonsSeeded";

    public static void seedDefault(final Context context) {
        final android.content.SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        if (preferences.getBoolean(PREF_SEEDED, false)) {
            return;
        }
        final android.content.SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean(PREF_SEEDED, true);
        if (preferences.getString(key(1), null) == null) {
            editor.putString(key(1), DEFAULT_ADDON);
        }
        editor.apply();
    }

    public static String key(final int slot) {
        return PREF_PREFIX + slot;
    }

    @NonNull
    public static List<Addon> saved(final Context context) {
        final List<Addon> out = new ArrayList<>();
        for (int slot = 1; slot <= MAX; slot++) {
            final String raw = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(key(slot), null);
            final String baseUrl = normalizeUrl(raw);
            if (baseUrl == null) {
                continue;
            }
            out.add(new Addon(hostOf(baseUrl), baseUrl));
        }
        return out;
    }

    public static boolean any(final Context context) {
        return !saved(context).isEmpty();
    }

    @Nullable
    public static String normalizeUrl(@Nullable final String input) {
        if (input == null) {
            return null;
        }
        String raw = input.trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.regionMatches(true, 0, "stremio://", 0, 10)) {
            raw = "https://" + raw.substring(10);
        }

        final Uri uri = Uri.parse(raw);
        final String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        final String authority = uri.getAuthority();
        if (authority == null || authority.isEmpty()) {
            return null;
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        path = path.replaceAll("(?i)/manifest\\.json$", "");
        // Someone may paste the subtitles URL rather than the manifest.
        path = path.replaceAll("(?i)/subtitles/(movie|series)/[^/]*$", "");
        path = path.replaceAll("/+$", "");

        return scheme.toLowerCase(Locale.ROOT) + "://" + authority + path;
    }

    private static String hostOf(final String baseUrl) {
        final String host = Uri.parse(baseUrl).getHost();
        return host == null || host.isEmpty() ? baseUrl : host;
    }

    // --------------------------------------------------------------- search

    @Nullable
    static String path(final Identity identity) {
        final String imdb = identity.isSeries
                ? (identity.parentImdbId != null ? identity.parentImdbId : identity.imdbId)
                : identity.imdbId;
        if (imdb == null || !imdb.matches("tt\\d{5,12}")) {
            return null;
        }
        if (!identity.isSeries) {
            return "/subtitles/movie/" + imdb + ".json";
        }
        if (identity.season == null || identity.episode == null) {
            return null;
        }
        return "/subtitles/series/" + imdb + ":" + identity.season + ":" + identity.episode + ".json";
    }

    @NonNull
    static List<Subtitles.Result> search(final Context context, final Subtitles.Target target) {
        final List<Subtitles.Result> out = new ArrayList<>();
        final List<Addon> addons = saved(context);
        if (addons.isEmpty()) {
            return out;
        }

        final String path = path(target.identity);
        if (path == null) {
            Subtitles.Log.note("Addons skipped: no addressable id for this title");
            return out;
        }

        for (final Addon addon : addons) {
            final Http.Result response = Http.get(addon.baseUrl + path, null);
            final JSONObject body = response.json();
            if (body == null) {
                Subtitles.Log.note("Addon " + addon.name + " -> HTTP " + response.code);
                continue;
            }
            final JSONArray rows = body.optJSONArray("subtitles");
            final List<Subtitles.Result> mapped = rows(rows, addon);
            Subtitles.Log.note("Addon " + addon.name + " -> " + mapped.size() + " subtitles");
            out.addAll(mapped);
        }
        return out;
    }

    private static List<Subtitles.Result> rows(@Nullable final JSONArray rows, final Addon addon) {
        final List<Subtitles.Result> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }

        for (int i = 0; i < rows.length(); i++) {
            final JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            final String url = firstString(row, "url");
            // No URL means nothing to download. Unlike a stream there is no
            // hash to fall back on, so the row is worthless, not incomplete.
            if (url == null || !url.matches("(?i)^https?://.+")) {
                continue;
            }

            final String release = firstString(row, "releaseName", "movieReleaseName", "title",
                    "fileName", "subtitleFileName", "label", "id");
            final String language = languageCode(
                    firstString(row, "lang", "lang_code", "language"));
            final String extension = extensionOf(row, url);
            final boolean hearingImpaired = row.optBoolean("hearing_impaired", false)
                    || (release != null && release.matches(".*\\bSDH\\b.*"));

            out.add(Subtitles.addonResult(
                    addon.name,
                    release == null ? "" : release,
                    language,
                    url,
                    extension,
                    hearingImpaired,
                    row.optBoolean("ai_translated", false),
                    row.optBoolean("from_trusted", false)));
        }
        return out;
    }

    private static String extensionOf(final JSONObject row, final String url) {
        final String named = firstString(row, "fileName", "subtitleFileName", "filename");
        if (named != null) {
            final String fromName = matchExtension(named);
            if (fromName != null) {
                return fromName;
            }
        }
        final String path = Uri.parse(url).getPath();
        final String fromUrl = path == null ? null : matchExtension(path);
        return fromUrl == null ? "srt" : fromUrl;
    }

    @Nullable
    private static String matchExtension(final String name) {
        final java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("(?i)\\.(srt|ass|ssa|vtt|sub)$").matcher(name);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    @Nullable
    private static String firstString(final JSONObject row, final String... keys) {
        for (final String key : keys) {
            final String value = row.optString(key, "").trim();
            if (!value.isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        return null;
    }

    static String languageCode(@Nullable final String raw) {
        if (raw == null || raw.isEmpty()) {
            return "und";
        }
        final String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.length() == 2) {
            return value;
        }
        final String mapped = LANGUAGES.get(value);
        if (mapped != null) {
            return mapped;
        }
        // A regional tag such as pt-br keeps its base language for matching.
        final int dash = value.indexOf('-');
        if (dash == 2) {
            return value.substring(0, 2);
        }
        return value;
    }

    private static final Map<String, String> LANGUAGES = new HashMap<>();

    static {
        final String[][] pairs = {
                {"ara", "ar"}, {"arabic", "ar"},
                {"ben", "bn"}, {"bengali", "bn"},
                {"bul", "bg"}, {"bulgarian", "bg"},
                {"ces", "cs"}, {"cze", "cs"}, {"czech", "cs"},
                {"dan", "da"}, {"danish", "da"},
                {"deu", "de"}, {"ger", "de"}, {"german", "de"},
                {"ell", "el"}, {"gre", "el"}, {"greek", "el"},
                {"eng", "en"}, {"english", "en"},
                {"est", "et"}, {"estonian", "et"},
                {"fas", "fa"}, {"per", "fa"}, {"persian", "fa"},
                {"fin", "fi"}, {"finnish", "fi"},
                {"fra", "fr"}, {"fre", "fr"}, {"french", "fr"},
                {"heb", "he"}, {"hebrew", "he"},
                {"hin", "hi"}, {"hindi", "hi"},
                {"hrv", "hr"}, {"croatian", "hr"},
                {"hun", "hu"}, {"hungarian", "hu"},
                {"ind", "id"}, {"indonesian", "id"},
                {"ita", "it"}, {"italian", "it"},
                {"jpn", "ja"}, {"japanese", "ja"},
                {"kor", "ko"}, {"korean", "ko"},
                {"mal", "ml"}, {"malayalam", "ml"},
                {"msa", "ms"}, {"may", "ms"}, {"malay", "ms"},
                {"nld", "nl"}, {"dut", "nl"}, {"dutch", "nl"},
                {"nor", "no"}, {"norwegian", "no"},
                {"pol", "pl"}, {"polish", "pl"},
                {"por", "pt"}, {"portuguese", "pt"}, {"pob", "pt"}, {"pb", "pt"},
                {"ron", "ro"}, {"rum", "ro"}, {"romanian", "ro"},
                {"rus", "ru"}, {"russian", "ru"},
                {"slk", "sk"}, {"slo", "sk"}, {"slovak", "sk"},
                {"slv", "sl"}, {"slovenian", "sl"},
                {"spa", "es"}, {"spanish", "es"},
                {"srp", "sr"}, {"serbian", "sr"},
                {"swe", "sv"}, {"swedish", "sv"},
                {"tam", "ta"}, {"tamil", "ta"},
                {"tel", "te"}, {"telugu", "te"},
                {"tha", "th"}, {"thai", "th"},
                {"tur", "tr"}, {"turkish", "tr"},
                {"ukr", "uk"}, {"ukrainian", "uk"},
                {"urd", "ur"}, {"urdu", "ur"},
                {"vie", "vi"}, {"vietnamese", "vi"},
                {"zho", "zh"}, {"chi", "zh"}, {"chinese", "zh"}, {"zht", "zh"}, {"zhe", "zh"},
        };
        for (final String[] pair : pairs) {
            LANGUAGES.put(pair[0], pair[1]);
        }
    }

    // ---------------------------------------------------------------- probe

    public enum Verdict {
        OK,
        UNREACHABLE,
        NOT_AN_ADDON,
        NO_SUBTITLE_RESOURCE,
        NEEDS_CONFIGURATION,
        SUBTITLE_FETCH_FAILED,
        NO_SUBTITLES,
        NO_USABLE_URL,
        NOT_DOWNLOADABLE,
        INVALID_URL,
    }

    public static final class Probe {
        public final Verdict verdict;
        @Nullable
        public final String name;
        public final int subtitles;
        public final int languages;
        public final boolean downloadVerified;

        Probe(Verdict verdict, @Nullable String name, int subtitles, int languages,
              boolean downloadVerified) {
            this.verdict = verdict;
            this.name = name;
            this.subtitles = subtitles;
            this.languages = languages;
            this.downloadVerified = downloadVerified;
        }

        public boolean accepted() {
            return verdict == Verdict.OK;
        }
    }

    @NonNull
    public static Probe probe(@Nullable final String rawUrl) {
        final String baseUrl = normalizeUrl(rawUrl);
        if (baseUrl == null) {
            return new Probe(Verdict.INVALID_URL, null, 0, 0, false);
        }

        final Http.Result manifestResponse = Http.get(baseUrl + "/manifest.json", null);
        final JSONObject manifest = manifestResponse.json();
        if (manifest == null) {
            return new Probe(Verdict.UNREACHABLE, null, 0, 0, false);
        }
        if (manifest.optString("id", "").isEmpty()) {
            return new Probe(Verdict.NOT_AN_ADDON, null, 0, 0, false);
        }

        // `subtitles`, not `stream` — the structural difference from the stream
        // addon gate, and the reason the two cannot share an implementation.
        if (!declaresSubtitles(manifest)) {
            return new Probe(Verdict.NO_SUBTITLE_RESOURCE, null, 0, 0, false);
        }

        final String rawName = manifest.optString("name", "").trim();
        final String name = rawName.isEmpty()
                ? null
                : rawName.substring(0, Math.min(40, rawName.length()));

        // Believe an addon that says it is unconfigured rather than waiting to
        // be told the same thing by an empty list.
        final JSONObject hints = manifest.optJSONObject("behaviorHints");
        if (hints != null && hints.optBoolean("configurationRequired", false)) {
            return new Probe(Verdict.NEEDS_CONFIGURATION, name, 0, 0, false);
        }

        final Http.Result answer = Http.get(baseUrl + PROBE_PATH, null);
        final JSONObject body = answer.json();
        if (body == null) {
            return new Probe(Verdict.SUBTITLE_FETCH_FAILED, name, 0, 0, false);
        }

        final JSONArray rows = body.optJSONArray("subtitles");
        final int total = rows == null ? 0 : rows.length();
        // Empty is a rejection: for a title this popular it can only mean the
        // addon does not work.
        if (total == 0) {
            return new Probe(Verdict.NO_SUBTITLES, name, 0, 0, false);
        }

        final List<String> urls = new ArrayList<>();
        final Set<String> languages = new LinkedHashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            final JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                continue;
            }
            final String url = firstString(row, "url");
            if (url == null || !url.matches("(?i)^https://.+")) {
                continue;
            }
            urls.add(url);
            final String language = firstString(row, "lang", "lang_code", "language");
            if (language != null) {
                languages.add(languageCode(language));
            }
        }
        if (urls.isEmpty()) {
            return new Probe(Verdict.NO_USABLE_URL, name, total, 0, false);
        }

        final Download download = verifyDownloads(urls);
        if (download == Download.DISPROVED) {
            return new Probe(Verdict.NOT_DOWNLOADABLE, name, urls.size(), languages.size(), false);
        }

        return new Probe(Verdict.OK, name, urls.size(), languages.size(),
                download == Download.VERIFIED);
    }

    private static boolean declaresSubtitles(final JSONObject manifest) {
        final JSONArray resources = manifest.optJSONArray("resources");
        if (resources == null) {
            return false;
        }
        for (int i = 0; i < resources.length(); i++) {
            final String flat = resources.optString(i, "");
            if ("subtitles".equals(flat)) {
                return true;
            }
            final JSONObject object = resources.optJSONObject(i);
            if (object != null && "subtitles".equals(object.optString("name", ""))) {
                return true;
            }
        }
        return false;
    }

    private enum Download {
        VERIFIED,
        DISPROVED,
        UNKNOWN,
    }

    private static Download verifyDownloads(final List<String> urls) {
        boolean sawMissing = false;

        for (int i = 0; i < urls.size() && i < DOWNLOAD_ATTEMPTS; i++) {
            final byte[] bytes = Http.getBytes(urls.get(i), null);
            if (bytes == null) {
                // Gone, blocked or throttled — indistinguishable from here, and
                // none of them is evidence about the addon itself.
                sawMissing = true;
                continue;
            }
            if (bytes.length == 0 || bytes.length > MAX_PROBE_BYTES) {
                sawMissing = true;
                continue;
            }
            // A zip is legitimate — some addons serve archives — and proving it
            // opens is the download path's job, not the gate's.
            if (bytes.length > 1 && bytes[0] == 0x50 && bytes[1] == 0x4B) {
                return Download.VERIFIED;
            }
            if (looksLikeSubtitle(bytes)) {
                return Download.VERIFIED;
            }
            return Download.DISPROVED;
        }

        return sawMissing ? Download.UNKNOWN : Download.UNKNOWN;
    }

    static boolean looksLikeSubtitle(final byte[] bytes) {
        final int length = Math.min(bytes.length, 4000);
        final String head = new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
        if (head.contains("-->")) {
            return true; // SRT, VTT
        }
        if (head.trim().startsWith("WEBVTT") || head.startsWith("﻿WEBVTT")) {
            return true;
        }
        return head.contains("[Script Info]") || head.matches("(?s).*Dialogue:\\s*\\d.*");
    }

    @Nullable
    public static String describe(final Context context, final int slot) {
        final String raw = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(key(slot), null);
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        final String baseUrl = normalizeUrl(raw);
        return baseUrl == null ? raw : hostOf(baseUrl);
    }
}
