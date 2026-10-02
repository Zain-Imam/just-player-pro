package com.brouken.player;

import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Android compiles regex with ICU, which rejects some patterns desktop Java accepts.
// Loads each class and compiles its patterns; add new pattern classes here.
@RunWith(AndroidJUnit4.class)
public class PatternsCompileTest {

    private static final Class<?>[] CLASSES_WITH_PATTERNS = {
            ReleaseName.class,
            Languages.class,
            TrackNames.class,
            LaunchSubtitles.class,
            SubtitleUtils.class,
            Utils.class,
            History.class,
            com.brouken.player.online.Subtitles.class,
            com.brouken.player.online.SubtitleAddons.class,
            com.brouken.player.online.Tmdb.class,
            com.brouken.player.online.SkipSegments.class,
            com.brouken.player.online.SetupServer.class,
            com.brouken.player.subtitle.parser.MicroDvdParser.class,
    };

    @Test
    public void everyClassLoadsAndEveryPatternCompiles() {
        final List<String> failures = new ArrayList<>();

        for (final Class<?> type : CLASSES_WITH_PATTERNS) {
            try {
                // runs the static initialiser, where a bad pattern throws
                Class.forName(type.getName(), true, type.getClassLoader());
            } catch (Throwable error) {
                failures.add(type.getSimpleName() + " would not load: " + error);
                continue;
            }
            failures.addAll(patternsIn(type));
        }

        if (!failures.isEmpty()) {
            fail(failures.size() + " problem(s):\n" + String.join("\n", failures));
        }
    }

    private static List<String> patternsIn(final Class<?> type) {
        final List<String> failures = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            final Object value;
            try {
                value = field.get(null);
            } catch (Throwable error) {
                continue;
            }
            if (value instanceof Pattern) {
                check(failures, type, field.getName(), ((Pattern) value).pattern());
            } else if (value instanceof String) {
                // regex strings for String.matches/replaceAll are not compiled at load
                final String text = (String) value;
                if (looksLikeAPattern(text)) {
                    check(failures, type, field.getName(), text);
                }
            }
        }
        return failures;
    }

    private static boolean looksLikeAPattern(final String text) {
        return text.contains("\\b") || text.contains("\\d") || text.contains("\\s")
                || text.contains("[^") || text.contains("(?");
    }

    private static void check(final List<String> failures, final Class<?> type,
                              final String name, final String pattern) {
        try {
            Pattern.compile(pattern);
        } catch (Throwable error) {
            failures.add(type.getSimpleName() + "." + name + ": " + error);
        }
    }
}
