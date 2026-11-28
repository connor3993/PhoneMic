package com.phonemic.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Stores the user's in-app language choice and applies it to activity/service contexts.
 * The app defaults to the system language, then lets the user switch at runtime.
 */
object LocaleManager {
    private const val PREFS_NAME = "phonemic_preferences"
    private const val KEY_LANGUAGE = "app_language"

    const val ZH = "zh"
    const val EN = "en"

    fun getLanguage(context: Context): String {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        if (saved == ZH || saved == EN) return saved
        return if (Locale.getDefault().language == ZH) ZH else EN
    }

    fun setLanguage(context: Context, language: String) {
        val normalized = if (language == ZH) ZH else EN
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .apply()
    }

    fun wrapContext(base: Context): Context {
        val language = getLanguage(base)
        val locale = Locale(language)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLocales(LocaleList(locale))
        return base.createConfigurationContext(configuration)
    }
}
