package com.nasmanagerapp.data.theme

import android.content.Context

/** Light/dark override for [com.nasmanagerapp.ui.theme.NasManagerAppTheme]. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Persists the user's light/dark theme choice. Defaults to [ThemeMode.SYSTEM] (phone's setting)
 * until the user explicitly toggles it from the drawer, at which point the explicit choice is
 * cached here and takes priority over the system setting on every subsequent launch.
 */
class ThemePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME_MODE, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    private companion object {
        const val PREFS_NAME = "truenas_theme"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
