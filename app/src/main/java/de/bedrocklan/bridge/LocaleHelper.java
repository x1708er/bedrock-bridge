package de.bedrocklan.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/** Hilfsklasse für die In-App-Sprache (EN/DE) über einen Configuration-Context. */
public final class LocaleHelper {
    private static final String PREFS = "MainActivity";
    private static final String KEY = "language";

    private LocaleHelper() {}

    static String read(Context base) {
        SharedPreferences prefs = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String language = prefs.getString(KEY, null);
        if (language == null) return null;
        return language.equals("de") || language.equals("en") ? language : null;
    }

    static void write(Context context, String language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, language)
                .apply();
    }

    static Context wrap(Context base) {
        String language = read(base);
        if (language == null) return base;
        Locale locale = new Locale(language);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLocales(LocaleList.forLanguageTags(language));
        return base.createConfigurationContext(config);
    }
}
