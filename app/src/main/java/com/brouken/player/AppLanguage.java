package com.brouken.player;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import java.util.Locale;

// before Android 13 AppCompat localises only its own screens; this covers the rest
public final class AppLanguage {

    public static final String PREF_KEY = "appLanguage";
    static final String PREF_OLD_WAY = "debugOldLanguageWay";

    private AppLanguage() {
    }

    private static SharedPreferences prefs(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static boolean phoneKeepsIt(final Context context) {
        return Build.VERSION.SDK_INT >= 33
                && !(BuildConfig.DEBUG && prefs(context).getBoolean(PREF_OLD_WAY, false));
    }

    public static String chosen(final Context context) {
        if (Build.VERSION.SDK_INT >= 33 && phoneKeepsIt(context)) {
            final LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty() ? "" : locales.get(0).toLanguageTag();
        }
        return prefs(context).getString(PREF_KEY, "");
    }

    // Android may hold a listed language in another form, like en-GB or zh-Hant-HK
    @Nullable
    public static String listed(final String tag, final CharSequence[] values) {
        if (tag.isEmpty()) {
            return "";
        }
        for (final CharSequence value : values) {
            if (value.toString().equalsIgnoreCase(tag)) {
                return value.toString();
            }
        }
        final Locale locale = Locale.forLanguageTag(tag);
        if ("zh".equals(locale.getLanguage())) {
            final boolean traditional = "Hant".equals(locale.getScript())
                    || "TW".equals(locale.getCountry()) || "HK".equals(locale.getCountry())
                    || "MO".equals(locale.getCountry());
            return traditional ? "zh-TW" : "zh-CN";
        }
        for (final CharSequence value : values) {
            if (value.length() > 0 && Locale.forLanguageTag(value.toString()).getLanguage()
                    .equals(locale.getLanguage())) {
                return value.toString();
            }
        }
        return null;
    }

    public static void choose(final Activity activity, final String tag) {
        prefs(activity).edit().putString(PREF_KEY, tag).apply();
        if (Build.VERSION.SDK_INT >= 33 && phoneKeepsIt(activity)) {
            activity.getSystemService(LocaleManager.class).setApplicationLocales(
                    tag.isEmpty() ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(tag));
            return;
        }
        applyToApp(activity.getApplicationContext(), tag);
        if (Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(tag.isEmpty()
                    ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(tag));
        } else {
            activity.recreate();
        }
    }

    static void start(final Context app) {
        final String tag = keep(app);
        if (tag != null && Build.VERSION.SDK_INT < 33) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag));
        }
    }

    // needed after every configuration change, which puts the phone's language back
    @Nullable
    static String keep(final Context app) {
        if (phoneKeepsIt(app)) {
            return null;
        }
        final String tag = prefs(app).getString(PREF_KEY, "");
        if (tag.isEmpty()) {
            return null;
        }
        applyToApp(app, tag);
        return tag;
    }

    private static void applyToApp(final Context app, final String tag) {
        final LocaleList locales = tag.isEmpty()
                ? Resources.getSystem().getConfiguration().getLocales()
                : new LocaleList(Locale.forLanguageTag(tag));
        Locale.setDefault(locales.get(0));
        final Resources resources = app.getResources();
        final Configuration config = new Configuration(resources.getConfiguration());
        config.setLocales(locales);
        config.setLayoutDirection(locales.get(0));
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    // for a screen that is not AppCompat's, before it reads any resource
    @Nullable
    static Configuration override(final Context base) {
        if (phoneKeepsIt(base)) {
            return null;
        }
        final String tag = prefs(base).getString(PREF_KEY, "");
        if (tag.isEmpty()) {
            return null;
        }
        final Configuration config = new Configuration();
        // 0 leaves the phone's text size alone; a new Configuration says 1
        config.fontScale = 0;
        config.setLocale(Locale.forLanguageTag(tag));
        return config;
    }
}
