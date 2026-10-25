package com.elvan.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Handles in-launcher language selection across Android 8 through 14 without needing root or system locale changes.
 */
public final class LocaleHelper {
    public static final String KEY_LOCALE = "app_language";

    private LocaleHelper() {}

    public static Context wrap(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        String lang = prefs.getString(KEY_LOCALE, "");
        if (lang == null || lang.isEmpty() || "system".equals(lang)) {
            return context;
        }
        Locale locale = getLocaleForTag(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(new LocaleList(locale));
        }
        return context.createConfigurationContext(config);
    }

    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LOCALE, "system");
    }

    public static void setLanguage(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LOCALE, languageCode).apply();
    }

    public static Locale getLocaleForTag(String tag) {
        if ("ta".equals(tag)) return new Locale("ta", "IN");
        if ("en".equals(tag)) return Locale.ENGLISH;
        return new Locale(tag);
    }
}
