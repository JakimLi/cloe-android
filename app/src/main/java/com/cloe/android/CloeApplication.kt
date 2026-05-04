package com.cloe.android

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class CloeApplication : Application() {

    companion object {
        private const val KEY_APP_LOCALE = "app_locale"
    }

    override fun onCreate() {
        super.onCreate()
        applyLocaleFromPrefs(this)
    }

    private fun applyLocaleFromPrefs(context: Context) {
        val tag = context.getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LOCALE, "system") ?: "system"
        val locales = when (tag) {
            "en" -> LocaleListCompat.forLanguageTags("en-US")
            "zh" -> LocaleListCompat.forLanguageTags("zh-CN")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
