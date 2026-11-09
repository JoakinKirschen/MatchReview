package be.matchreview.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The language the app is shown in; System follows the phone's language. */
enum class AppLanguage(
    /** ISO 639-1 code; null for System. */
    val code: String?,
    private val nativeName: String
) {
    SYSTEM(null, "System"),
    // Each language is named in itself, so it can be found whatever is shown now.
    ENGLISH("en", "English"),
    DUTCH("nl", "Nederlands"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    SPANISH("es", "Español"),
    ITALIAN("it", "Italiano"),
    TURKISH("tr", "Türkçe");

    val label: String get() = if (this == SYSTEM) tr(nativeName) else nativeName

    /** The translations, keyed by the English text; null for English itself. */
    internal val texts: Map<String, String>?
        get() = when (this) {
            SYSTEM, ENGLISH -> null
            DUTCH -> Dutch.texts
            FRENCH -> French.texts
            GERMAN -> German.texts
            SPANISH -> Spanish.texts
            ITALIAN -> Italian.texts
            TURKISH -> Turkish.texts
        }

    companion object {
        fun fromName(name: String?): AppLanguage = entries.firstOrNull { it.name == name } ?: SYSTEM

        /** The supported language for a phone language code; English when it is not supported. */
        fun forCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code != null && it.code == code } ?: ENGLISH
    }
}

/**
 * Translations keyed by the English text. Code writes `tr("Our goal")`; in another
 * language the text comes from that language's table (for example [Dutch]); when a
 * translation is missing the English text is used. This works in screens, click handlers,
 * services and exports alike.
 *
 * Values are put in with named placeholders: `tr("Period {number}", "number" to 2)`.
 * A word with two meanings carries a note after a bar: `tr("Save|keeper")`.
 * Screens recompose by themselves when the language changes, because the active language
 * is Compose state.
 */
object I18n {
    private var active by mutableStateOf(AppLanguage.ENGLISH)

    /** The language texts are shown in now; never System. */
    val current: AppLanguage get() = active

    val isDutch: Boolean get() = active == AppLanguage.DUTCH

    /** The locale for dates and numbers in the chosen language. */
    val locale: Locale
        get() = when (active) {
            AppLanguage.DUTCH -> Locale("nl", "BE")
            AppLanguage.FRENCH -> Locale("fr", "BE")
            AppLanguage.ENGLISH, AppLanguage.SYSTEM -> Locale.getDefault()
            else -> Locale(active.code!!)
        }

    fun apply(language: AppLanguage, systemLanguage: String = Locale.getDefault().language) {
        active = if (language == AppLanguage.SYSTEM) AppLanguage.forCode(systemLanguage) else language
    }

    fun translate(english: String, args: Array<out Pair<String, Any?>>): String {
        val template = active.texts?.get(english) ?: english.substringBefore(CONTEXT)
        return fill(template, args)
    }

    /**
     * Separates a word from a note on its meaning, for words that need different
     * translations in different places: `tr("Home|navigation")` shows "Home" in English.
     */
    const val CONTEXT = '|'

    internal fun fill(template: String, args: Array<out Pair<String, Any?>>): String =
        args.fold(template) { text, (name, value) -> text.replace("{$name}", value.toString()) }

    /** The placeholders in a text, such as `{number}`; translations must keep the same ones. */
    fun placeholders(text: String): Set<String> =
        Regex("""\{([a-zA-Z]+)\}""").findAll(text).map { it.groupValues[1] }.toSet()
}

/** The text in the active language; see [I18n]. */
fun tr(english: String, vararg args: Pair<String, Any?>): String = I18n.translate(english, args)
