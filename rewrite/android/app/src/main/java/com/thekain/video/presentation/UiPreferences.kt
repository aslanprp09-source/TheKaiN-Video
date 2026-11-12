package com.thekain.video.presentation

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class Page { HOME, DOWNLOADS, LIBRARY, SETTINGS }
data class UiSettings(val theme: ThemeMode = ThemeMode.SYSTEM, val language: String = "ru")

class UiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UiSettings(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        prefs.getString("language", "ru").let { if (it == "en") "en" else "ru" }))
    val state = mutable.asStateFlow()
    fun theme(mode: ThemeMode) { prefs.edit().putString("theme", mode.name).apply(); mutable.value = mutable.value.copy(theme = mode) }
    fun language(value: String) {
        if (value !in setOf("ru", "en")) return
        prefs.edit().putString("language", value).apply(); mutable.value = mutable.value.copy(language = value)
    }
}

fun Context.localized(language: String? = null): Context {
    val selected = language ?: getSharedPreferences("interface", Context.MODE_PRIVATE).getString("language", "ru")!!
    val configuration = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(selected)) }
    return createConfigurationContext(configuration)
}
