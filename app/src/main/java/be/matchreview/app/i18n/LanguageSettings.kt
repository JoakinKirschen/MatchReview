package be.matchreview.app.i18n

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the chosen language is kept. On Android 13 and newer the phone's own per-app
 * language setting (Settings > Apps > MatchReview > Language) and the menu in the app are
 * the same setting: choosing in one shows up in the other.
 */
object LanguageSettings {
    private const val PREFERENCES = "matchreview_display"
    private const val KEY = "language"

    private val _selected = MutableStateFlow(AppLanguage.SYSTEM)
    /** The choice as made, which can be System. */
    val selected: StateFlow<AppLanguage> = _selected.asStateFlow()

    /** Reads the choice and applies it; call when the app or a screen starts. */
    fun refresh(context: Context) {
        val stored = AppLanguage.fromName(
            context.getSharedPreferences(PREFERENCES, 0).getString(KEY, null)
        )
        val language = phoneSetting(context) ?: stored
        if (language != stored) store(context, language)
        _selected.value = language
        I18n.apply(language)
    }

    /** Switches the language right away and remembers it. */
    fun choose(context: Context, language: AppLanguage) {
        store(context, language)
        _selected.value = language
        I18n.apply(language)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    language.code?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            }
        }
    }

    /** The per-app language chosen in the phone's settings; null before Android 13. */
    private fun phoneSetting(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = runCatching {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
        }.getOrNull() ?: return null
        return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.forCode(locales[0].language)
    }

    private fun store(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES, 0).edit().putString(KEY, language.name).apply()
    }
}
