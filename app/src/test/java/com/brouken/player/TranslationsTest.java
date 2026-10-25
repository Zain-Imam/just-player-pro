package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// a missing text shows as English, and a wrong placeholder crashes its screen
public class TranslationsTest {

    private static final File RES = new File("src/main/res");

    private static final Map<String, List<String>> LANGUAGES = new LinkedHashMap<>();

    static {
        final List<String> oneOther = Arrays.asList("one", "other");
        final List<String> slavic = Arrays.asList("one", "few", "many", "other");
        final List<String> otherOnly = Collections.singletonList("other");
        LANGUAGES.put("ar", Arrays.asList("zero", "one", "two", "few", "many", "other"));
        LANGUAGES.put("de", oneOther);
        LANGUAGES.put("es", oneOther);
        LANGUAGES.put("fa", oneOther);
        LANGUAGES.put("fr", oneOther);
        LANGUAGES.put("hi", oneOther);
        LANGUAGES.put("in", otherOnly);
        LANGUAGES.put("it", oneOther);
        LANGUAGES.put("ja", otherOnly);
        LANGUAGES.put("ko", otherOnly);
        LANGUAGES.put("pl", slavic);
        LANGUAGES.put("pt-rBR", oneOther);
        LANGUAGES.put("ru", slavic);
        LANGUAGES.put("tr", oneOther);
        LANGUAGES.put("uk", slavic);
        LANGUAGES.put("vi", otherOnly);
        LANGUAGES.put("zh-rCN", otherOnly);
        LANGUAGES.put("zh-rTW", otherOnly);
    }

    private static final Pattern STRING = Pattern.compile(
            "<string\\s+name=\"([^\"]+)\"([^>]*?)(?:/>|>(.*?)</string>)", Pattern.DOTALL);
    private static final Pattern PLURALS = Pattern.compile(
            "<plurals\\s+name=\"([^\"]+)\"[^>]*>(.*?)</plurals>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile(
            "<item\\s+quantity=\"([^\"]+)\"\\s*>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern HOLDER = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc]");

    private static final class Texts {
        final Map<String, String> strings = new LinkedHashMap<>();
        final Map<String, Map<String, String>> plurals = new LinkedHashMap<>();
    }

    private static Texts read(final File file) throws IOException {
        final String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "");
        final Texts texts = new Texts();
        final Matcher strings = STRING.matcher(xml);
        while (strings.find()) {
            if (!strings.group(2).contains("translatable=\"false\"")) {
                texts.strings.put(strings.group(1), strings.group(3) == null ? "" : strings.group(3));
            }
        }
        final Matcher plurals = PLURALS.matcher(xml);
        while (plurals.find()) {
            final Map<String, String> items = new LinkedHashMap<>();
            final Matcher item = ITEM.matcher(plurals.group(2));
            while (item.find()) {
                items.put(item.group(1), item.group(2));
            }
            texts.plurals.put(plurals.group(1), items);
        }
        return texts;
    }

    // %s and %1$s both count as the first
    private static String holders(final String text) {
        final List<String> found = new ArrayList<>();
        final Matcher m = HOLDER.matcher(text.replace("%%", ""));
        int plain = 0;
        while (m.find()) {
            final String position = m.group(1) != null ? m.group(1) : (++plain) + "$";
            found.add("%" + position + m.group().charAt(m.group().length() - 1));
        }
        Collections.sort(found);
        return String.join(" ", found);
    }

    @Test
    public void everyLanguageHasEveryTextWithItsPlaceholders() throws IOException {
        final Texts english = read(new File(RES, "values/strings.xml"));
        final List<String> problems = new ArrayList<>();
        for (final Map.Entry<String, List<String>> language : LANGUAGES.entrySet()) {
            final String lang = language.getKey();
            final File file = new File(RES, "values-" + lang + "/strings.xml");
            if (!file.exists()) {
                problems.add(lang + ": no strings.xml");
                continue;
            }
            final Texts translated = read(file);
            for (final Map.Entry<String, String> text : english.strings.entrySet()) {
                final String t = translated.strings.get(text.getKey());
                if (t == null) {
                    problems.add(lang + ": " + text.getKey() + " missing");
                } else if (!t.trim().startsWith("@string/") && !holders(t).equals(holders(text.getValue()))) {
                    problems.add(lang + ": " + text.getKey() + " has " + holders(t)
                            + " but English has " + holders(text.getValue()));
                }
            }
            for (final Map.Entry<String, Map<String, String>> plural : english.plurals.entrySet()) {
                final Map<String, String> items = translated.plurals.get(plural.getKey());
                if (items == null) {
                    problems.add(lang + ": plurals " + plural.getKey() + " missing");
                    continue;
                }
                final String want = holders(plural.getValue().get("other"));
                for (final String quantity : language.getValue()) {
                    final String item = items.get(quantity);
                    if (item == null) {
                        problems.add(lang + ": plurals " + plural.getKey() + " needs " + quantity);
                    } else if (!holders(item).equals(want) && !(holders(item).isEmpty()
                            && Arrays.asList("zero", "one", "two").contains(quantity))) {
                        problems.add(lang + ": plurals " + plural.getKey() + " " + quantity
                                + " has " + holders(item) + " but English has " + want);
                    }
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void settingsListsWhatAndroidLists() throws IOException {
        final String arrays = new String(Files.readAllBytes(new File(RES, "values/arrays.xml").toPath()),
                StandardCharsets.UTF_8);
        final Matcher block = Pattern.compile("(?s)<string-array name=\"app_language_values\"[^>]*>(.*?)</string-array>")
                .matcher(arrays);
        assertTrue(block.find());
        final TreeSet<String> settings = new TreeSet<>();
        final Matcher item = Pattern.compile("<item>([^<]*)</item>").matcher(block.group(1));
        while (item.find()) {
            if (!item.group(1).isEmpty()) {
                settings.add(item.group(1));
            }
        }
        final String config = new String(Files.readAllBytes(new File(RES, "xml/locales_config.xml").toPath()),
                StandardCharsets.UTF_8);
        final TreeSet<String> android = new TreeSet<>();
        final Matcher locale = Pattern.compile("android:name=\"([^\"]+)\"").matcher(config);
        while (locale.find()) {
            android.add(locale.group(1));
        }
        assertEquals(android, settings);
        // one folder per language, besides English
        assertEquals(settings.size() - 1, LANGUAGES.size());
    }
}
