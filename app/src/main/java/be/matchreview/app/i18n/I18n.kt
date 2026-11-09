package be.matchreview.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The language the app is shown in; System follows the phone's language. */
enum class AppLanguage(private val englishLabel: String) {
    SYSTEM("System"),
    // Each language is named in itself, so it can be found whatever is shown now.
    ENGLISH("English"),
    DUTCH("Nederlands");

    val label: String get() = if (this == SYSTEM) tr(englishLabel) else englishLabel

    companion object {
        fun fromName(name: String?): AppLanguage = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * Translations keyed by the English text. Code writes `tr("Our goal")`; when Dutch is
 * active the text comes from [Dutch], otherwise (or when a translation is missing) the
 * English text is used. This works in screens, click handlers, services and exports alike.
 *
 * Values are put in with named placeholders: `tr("Period {number}", "number" to 2)`.
 * A word with two meanings carries a note after a bar: `tr("Save|keeper")`.
 * Screens recompose by themselves when the language changes, because the active language
 * is Compose state.
 */
object I18n {
    private var dutch by mutableStateOf(false)

    val isDutch: Boolean get() = dutch

    /** The locale for dates and numbers in the chosen language. */
    val locale: Locale get() = if (dutch) Locale("nl", "BE") else Locale.getDefault()

    fun apply(language: AppLanguage, systemLanguage: String = Locale.getDefault().language) {
        dutch = when (language) {
            AppLanguage.SYSTEM -> systemLanguage == "nl"
            AppLanguage.ENGLISH -> false
            AppLanguage.DUTCH -> true
        }
    }

    fun translate(english: String, args: Array<out Pair<String, Any?>>): String {
        val template = (if (dutch) Dutch.texts[english] else null) ?: english.substringBefore(CONTEXT)
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
