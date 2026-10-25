package com.brouken.player.online;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// a source without a language is skipped rather than sent a code it turns away
public final class SubtitleLanguages {

    private SubtitleLanguages() {
    }

    static final class Language {
        final String code;
        // the tag Android names it by
        final String tag;
        @Nullable final String openSubtitles;
        @Nullable final String subdl;
        @Nullable final String wyzie;
        final String english;

        Language(final String code, final String tag, @Nullable final String openSubtitles,
                 @Nullable final String subdl, @Nullable final String wyzie, final String english) {
            this.code = code;
            this.tag = tag;
            this.openSubtitles = openSubtitles;
            this.subdl = subdl;
            this.wyzie = wyzie;
            this.english = english;
        }

        // the language without its country or script: pt for pt-br, es for sp
        String base() {
            return tag.contains("+") ? code : Locale.forLanguageTag(tag).getLanguage();
        }
    }

    // code, tag, OpenSubtitles, SubDL, Wyzie, English; "-" where a source has none
    private static final String[] TABLE = {
            "ab|ab|ab|-|ab|Abkhazian", "af|af|af|-|af|Afrikaans", "sq|sq|sq|SQ|sq|Albanian",
            "am|am|am|-|am|Amharic", "ar|ar|ar|AR|ar|Arabic", "an|an|an|-|an|Aragonese",
            "hy|hy|hy|HY|hy|Armenian", "as|as|as|-|as|Assamese", "at|ast|at|-|-|Asturian",
            "az-az|az|az-az|AZ|az|Azerbaijani", "az-zb|azb|az-zb|-|-|South Azerbaijani",
            "eu|eu|eu|EU|eu|Basque", "be|be|be|BE|be|Belarusian", "bn|bn|bn|BN|bn|Bengali",
            "bs|bs|bs|BS|bs|Bosnian", "br|br|br|-|br|Breton", "bg|bg|bg|BG|bg|Bulgarian",
            "bg-en|bg+en|-|BG_EN|-|Bulgarian + English", "my|my|my|MY|my|Burmese",
            "ca|ca|ca|CA|ca|Catalan", "zh|zh|zh-cn,zh-tw|ZH,ZH_BG|zh|Chinese",
            "zh-cn|zh-Hans|zh-cn|ZH|zh|Chinese (Simplified)",
            "zh-tw|zh-Hant|zh-tw|ZH_BG|zh|Chinese (Traditional)",
            "ze|zh+en|ze|-|-|Chinese + English", "zh-ca|yue|zh-ca|-|-|Cantonese",
            "hr|hr|hr|HR|hr|Croatian", "cs|cs|cs|CS|cs|Czech", "da|da|da|DA|da|Danish",
            "pr|fa-AF|pr|-|-|Dari", "nl|nl|nl|NL|nl|Dutch", "nl-en|nl+en|-|NL_EN|-|Dutch + English",
            "en|en|en|EN|en|English", "en-de|en+de|-|EN_DE|-|English + German",
            "eo|eo|eo|EO|eo|Esperanto", "et|et|et|ET|et|Estonian", "ex|ext|ex|-|-|Extremaduran",
            "fi|fi|fi|FI|fi|Finnish", "fr|fr|fr|FR|fr|French", "fr-ca|fr-CA|-|FR_CA|-|French (Canada)",
            "gd|gd|gd|-|gd|Scottish Gaelic", "gl|gl|gl|GL|gl|Galician", "ka|ka|ka|KA|ka|Georgian",
            "de|de|de|DE|de|German", "el|el|el|EL|el|Greek", "kl|kl|-|KL|kl|Kalaallisut",
            "he|he|he|HE|he|Hebrew", "hi|hi|hi|HI|hi|Hindi", "hu|hu|hu|HU|hu|Hungarian",
            "hu-en|hu+en|-|HU_EN|-|Hungarian + English", "is|is|is|IS|is|Icelandic",
            "ig|ig|ig|-|ig|Igbo", "id|id|id|ID|id|Indonesian", "ia|ia|ia|-|ia|Interlingua",
            "ga|ga|ga|GA|ga|Irish", "it|it|it|IT|it|Italian", "ja|ja|ja|JA|ja|Japanese",
            "jv|jv|-|JV|jv|Javanese", "kn|kn|kn|KN|kn|Kannada", "kk|kk|kk|KK|kk|Kazakh",
            "km|km|km|KM|km|Khmer", "ko|ko|ko|KO|ko|Korean", "ku|ku|ku|KU|ku|Kurdish",
            "ky|ky|-|KY|ky|Kyrgyz", "lv|lv|lv|LV|lv|Latvian", "lt|lt|lt|LT|lt|Lithuanian",
            "lb|lb|lb|-|lb|Luxembourgish", "mk|mk|mk|MK|mk|Macedonian", "ms|ms|ms|MS|ms|Malay",
            "ml|ml|ml|ML|ml|Malayalam", "ma|mni|ma|MNI|-|Manipuri", "mr|mr|mr|-|mr|Marathi",
            "mn|mn|mn|MN|mn|Mongolian", "me|cnr|me|-|-|Montenegrin", "nv|nv|nv|-|nv|Navajo",
            "ne|ne|ne|-|ne|Nepali", "se|se|se|-|se|Northern Sami", "no|no|no|NO|no|Norwegian",
            "oc|oc|oc|-|oc|Occitan", "or|or|or|-|or|Odia", "fa|fa|fa|FA|fa|Persian",
            "pl|pl|pl|PL|pl|Polish", "pt|pt|pt-br,pt-pt|BR_PT,PT|pt|Portuguese",
            "pt-br|pt-BR|pt-br|BR_PT|pt|Portuguese (Brazil)",
            "pt-pt|pt-PT|pt-pt|PT|pt|Portuguese (Portugal)",
            "pm|pt-MZ|pm|-|-|Portuguese (Mozambique)", "ps|ps|ps|-|ps|Pashto",
            "ro|ro|ro|RO|ro|Romanian", "ru|ru|ru|RU|ru|Russian", "sx|sat|sx|-|-|Santali",
            "sr|sr|sr|SR|sr|Serbian", "sr-cyrl|sr-Cyrl|-|SR_CYRL|-|Serbian (Cyrillic)",
            "sd|sd|sd|-|sd|Sindhi", "si|si|si|SI|si|Sinhala", "sk|sk|sk|SK|sk|Slovak",
            "sl|sl|sl|SL|sl|Slovenian", "so|so|so|-|so|Somali", "es|es|es|ES|es|Spanish",
            "ea|es-419|ea|-|-|Spanish (Latin America)", "sp|es-ES|sp|-|-|Spanish (Spain)",
            "su|su|-|SU|su|Sundanese", "sw|sw|sw|-|sw|Swahili", "sv|sv|sv|SV|sv|Swedish",
            "sy|syr|sy|-|-|Syriac", "tl|fil|tl|TL|tl|Filipino", "ta|ta|ta|TA|ta|Tamil",
            "tt|tt|tt|-|tt|Tatar", "te|te|te|TE|te|Telugu", "tm-td|tet|tm-td|-|-|Tetum",
            "th|th|th|TH|th|Thai", "tp|tok|tp|-|-|Toki Pona", "tr|tr|tr|TR|tr|Turkish",
            "tk|tk|tk|-|tk|Turkmen", "uk|uk|uk|UK|uk|Ukrainian", "ur|ur|ur|UR|ur|Urdu",
            "uz|uz|uz|-|uz|Uzbek", "vi|vi|vi|VI|vi|Vietnamese", "cy|cy|cy|-|cy|Welsh",
    };

    // the three-letter forms addons use (both ISO 639-2 spellings) and the odd ones
    private static final String[][] ALIASES = {
            {"pob", "pt-br"}, {"pom", "pm"}, {"por", "pt-pt"}, {"zht", "zh-tw"}, {"zhe", "ze"},
            {"zhc", "zh-ca"}, {"chi", "zh"}, {"zho", "zh"}, {"spn", "sp"}, {"spl", "ea"},
            {"prs", "pr"}, {"mne", "me"}, {"mni", "ma"}, {"sat", "sx"}, {"syr", "sy"},
            {"tet", "tm-td"}, {"tok", "tp"}, {"ast", "at"}, {"ext", "ex"}, {"azb", "az-zb"},
            {"fre", "fr"}, {"fra", "fr"}, {"ger", "de"}, {"deu", "de"}, {"cze", "cs"}, {"ces", "cs"},
            {"per", "fa"}, {"fas", "fa"}, {"alb", "sq"}, {"sqi", "sq"}, {"may", "ms"}, {"msa", "ms"},
            {"slo", "sk"}, {"slk", "sk"}, {"ice", "is"}, {"isl", "is"}, {"mac", "mk"}, {"mkd", "mk"},
            {"baq", "eu"}, {"eus", "eu"}, {"bur", "my"}, {"mya", "my"}, {"arm", "hy"}, {"hye", "hy"},
            {"geo", "ka"}, {"kat", "ka"}, {"dut", "nl"}, {"nld", "nl"}, {"rum", "ro"}, {"ron", "ro"},
            {"gre", "el"}, {"ell", "el"}, {"wel", "cy"}, {"cym", "cy"}, {"az", "az-az"}, {"aze", "az-az"},
            {"brazilian portuguese", "pt-br"}, {"br_pt", "pt-br"}, {"zh_bg", "zh-tw"},
            {"farsi_persian", "fa"}, {"big 5 code", "zh-tw"}, {"chinese bg code", "zh-cn"},
    };

    private static final Map<String, Language> BY_CODE = new LinkedHashMap<>();
    private static final Map<String, String> SPELLINGS = new HashMap<>();

    static {
        for (final String row : TABLE) {
            final String[] f = row.split("\\|");
            final Language language = new Language(f[0], f[1], none(f[2]), none(f[3]), none(f[4]), f[5]);
            BY_CODE.put(language.code, language);
            SPELLINGS.put(language.code, language.code);
        }
        // other spellings never take a code's place: SubDL's PT is pt-pt, not pt
        for (final Language language : BY_CODE.values()) {
            spelling(language.english.toLowerCase(Locale.ROOT), language.code);
            if (language.subdl != null && !language.subdl.contains(",")) {
                spelling(language.subdl.toLowerCase(Locale.ROOT).replace('_', '-'), language.code);
                spelling(language.subdl.toLowerCase(Locale.ROOT), language.code);
            }
            if (!language.tag.contains("+")) {
                try {
                    final String iso3 = Locale.forLanguageTag(language.tag).getISO3Language();
                    if (!iso3.isEmpty()) {
                        spelling(iso3, language.code);
                    }
                } catch (java.util.MissingResourceException ignored) {
                    // no three-letter form known for it
                }
            }
        }
        for (final String[] alias : ALIASES) {
            SPELLINGS.put(alias[0], alias[1]);
        }
    }

    private static void spelling(final String spelling, final String code) {
        if (!SPELLINGS.containsKey(spelling)) {
            SPELLINGS.put(spelling, code);
        }
    }

    @Nullable
    private static String none(final String value) {
        return "-".equals(value) ? null : value;
    }

    public static List<String> codes() {
        return new ArrayList<>(BY_CODE.keySet());
    }

    @Nullable
    private static Language of(@Nullable final String code) {
        if (code == null) {
            return null;
        }
        final String known = SPELLINGS.get(code.trim().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (known != null) {
            return BY_CODE.get(known);
        }
        final String spelled = SPELLINGS.get(code.trim().toLowerCase(Locale.ROOT));
        return spelled == null ? null : BY_CODE.get(spelled);
    }

    public static String name(final String code) {
        final Language language = of(code);
        if (language == null) {
            final String own = new Locale(code).getDisplayLanguage();
            return own.isEmpty() || own.equalsIgnoreCase(code) ? code : capitalised(own);
        }
        if (language.tag.contains("+")) {
            final String[] parts = language.tag.split("\\+");
            return capitalised(nameOf(parts[0], language.english)) + " + " + nameOf(parts[1], language.english);
        }
        return capitalised(nameOf(language.tag, language.english));
    }

    // for inside a sentence ("Recherche en anglais"), so not capitalised
    public static String nameInSentence(final String code) {
        final Language language = of(code);
        if (language == null || language.tag.contains("+")) {
            return name(code);
        }
        return nameOf(language.tag, language.english);
    }

    private static String nameOf(final String tag, final String english) {
        final Locale locale = Locale.forLanguageTag(tag);
        final String name = locale.getDisplayName();
        // a language Android has no name for comes back as its own code
        return name.isEmpty() || name.equalsIgnoreCase(tag) || name.equalsIgnoreCase(locale.getLanguage())
                ? english : name;
    }

    private static String capitalised(final String name) {
        if (name.isEmpty()) {
            return name;
        }
        final int first = name.offsetByCodePoints(0, 1);
        return name.substring(0, first).toUpperCase(Locale.getDefault()) + name.substring(first);
    }

    // what each source is sent; null when it has no such language
    @Nullable
    static String forOpenSubtitles(final String code) {
        final Language language = of(code);
        return language == null ? plain(code) : language.openSubtitles;
    }

    @Nullable
    static String forSubdl(final String code) {
        final Language language = of(code);
        if (language == null) {
            final String plain = plain(code);
            return plain == null ? null : plain.toUpperCase(Locale.ROOT);
        }
        return language.subdl;
    }

    @Nullable
    static String forWyzie(final String code) {
        final Language language = of(code);
        return language == null ? plain(code) : language.wyzie;
    }

    // a code typed on the setup page that the table does not know goes as typed
    @Nullable
    private static String plain(final String code) {
        final String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z]{2}") ? value : null;
    }

    // the same language, or the same base language where one side names no country
    static boolean same(@Nullable final String result, final String wanted) {
        final Language a = of(result);
        final Language b = of(wanted);
        if (a == null || b == null) {
            return result != null && result.equalsIgnoreCase(wanted);
        }
        if (a == b) {
            return true;
        }
        final boolean aGeneral = a.code.equals(a.base());
        final boolean bGeneral = b.code.equals(b.base());
        return a.base().equals(b.base()) && (aGeneral || bGeneral);
    }

    public static String trackTag(final String code) {
        final Language language = of(code);
        if (language == null || language.tag.contains("+")) {
            return code;
        }
        return language.tag;
    }
}
