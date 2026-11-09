package be.matchreview.app

import be.matchreview.app.data.MatchStatus
import be.matchreview.app.domain.RecordingStopMode
import be.matchreview.app.i18n.AppLanguage
import be.matchreview.app.i18n.Dutch
import be.matchreview.app.i18n.I18n
import be.matchreview.app.i18n.tr
import be.matchreview.app.ui.ThemeMode
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text shown through tr() has a Dutch translation with the same placeholders. */
class TranslationCoverageTest {
    @After
    fun backToEnglish() = I18n.apply(AppLanguage.ENGLISH)

    private fun sourceTexts(): Map<String, String> {
        val root = File("src/main/java/be/matchreview/app")
        val call = Regex("""\btr\("((?:[^"\\]|\\.)*)"""")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "i18n" !in it.path }
            .flatMap { file -> call.findAll(file.readText()).map { unescape(it.groupValues[1]) to file.name } }
            .toMap()
    }

    private fun unescape(text: String) = text
        .replace("\\\"", "\"").replace("\\$", "$").replace("\\n", "\n").replace("\\\\", "\\")

    /** Texts translated from a variable rather than a literal. */
    private val dynamicTexts =
        ThemeMode.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } +
            listOf("Report", "Timeline", "Video", "Data", "System", "Home|navigation", "Teams", "Matches", "Language") +
            listOf("Only when I stop it", "When I end the period", "When the planned time is up") +
            listOf("manual", "at period end", "when time is up") +
            MatchStatus.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) } +
            listOf(
                "Goal", "Shot", "Chance", "Pass", "Cross", "Corner", "Free kick", "Foul", "Save", "Turnover",
                "Pressing", "Defensive error", "Set piece", "Custom", "Positive", "Neutral", "Improve"
            ) +
            listOf("top", "middle", "bottom").flatMap { height -> listOf("left", "centre", "right").map { "$height $it" } }
                .filter { it != "middle centre" } + "centre"

    private val translated = AppLanguage.entries.filter { it.texts != null }

    @Test
    fun `every text has a translation in every language`() {
        val needed = sourceTexts().keys + dynamicTexts
        val missing = translated.flatMap { language ->
            needed.filter { it !in language.texts!! }.map { "${language.name}: $it" }
        }
        assertTrue("Missing translations:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `translations keep the same placeholders`() {
        val wrong = translated.flatMap { language ->
            language.texts!!.filter { (english, text) -> I18n.placeholders(english) != I18n.placeholders(text) }
                .map { "${language.name}: ${it.key} -> ${it.value}" }
        }
        assertTrue("Placeholders differ:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** Real words in a text, leaving out placeholders such as {minutes}. */
    private fun words(text: String) =
        Regex("[A-Za-z]{2,}").findAll(text.replace(Regex("\\{[a-zA-Z]+\\}"), "")).count()

    @Test
    fun `no translation is left in English by accident`() {
        // Short words such as "Video" or "Pressing" may be the same; whole sentences may not.
        val untranslated = translated.flatMap { language ->
            language.texts!!.filter { (english, text) -> english == text && words(english) >= 3 }
                .map { "${language.name}: ${it.key}" }
        }
        assertTrue("Same as English:\n" + untranslated.joinToString("\n"), untranslated.isEmpty())
    }

    @Test
    fun `every language switches the texts`() {
        val expected = mapOf(
            AppLanguage.FRENCH to "Mode remplacements",
            AppLanguage.GERMAN to "Wechselmodus",
            AppLanguage.SPANISH to "Modo cambios",
            AppLanguage.ITALIAN to "Modalità cambi",
            AppLanguage.TURKISH to "Değişiklik modu",
            AppLanguage.DUTCH to "Wisselmodus"
        )
        expected.forEach { (language, text) ->
            I18n.apply(language)
            assertEquals(text, tr("Substitution mode"))
        }
    }

    @Test
    fun `the phone language picks a supported language or English`() {
        I18n.apply(AppLanguage.SYSTEM, systemLanguage = "tr")
        assertEquals(AppLanguage.TURKISH, I18n.current)
        I18n.apply(AppLanguage.SYSTEM, systemLanguage = "pl")
        assertEquals(AppLanguage.ENGLISH, I18n.current)
    }

    @Test
    fun `texts switch language and fill in values`() {
        I18n.apply(AppLanguage.DUTCH)
        assertEquals("Periode 2 starten", tr("Start period {currentPeriod}", "currentPeriod" to 2))
        assertEquals("Wisselmodus", tr("Substitution mode"))
        assertEquals("Buiten", ThemeMode.OUTDOOR.label)
        assertEquals("einde periode", RecordingStopMode.PERIOD_END.shortLabel)
        I18n.apply(AppLanguage.ENGLISH)
        assertEquals("Start period 2", tr("Start period {currentPeriod}", "currentPeriod" to 2))
        assertEquals("Outdoor", ThemeMode.OUTDOOR.label)
    }

    @Test
    fun `a word with two meanings gets the right translation`() {
        assertEquals("Home", tr("Home|navigation"))
        I18n.apply(AppLanguage.DUTCH)
        assertEquals("Start", tr("Home|navigation"))
        assertEquals("Thuis", tr("Home"))
        assertEquals("Redding", tr("Save|keeper"))
        assertEquals("Bewaren", tr("Save"))
    }

    @Test
    fun `system language follows the phone`() {
        I18n.apply(AppLanguage.SYSTEM, systemLanguage = "nl")
        assertTrue(I18n.isDutch)
        I18n.apply(AppLanguage.SYSTEM, systemLanguage = "fr")
        assertTrue(!I18n.isDutch)
    }

    @Test
    fun `a text without translation stays English`() {
        I18n.apply(AppLanguage.DUTCH)
        assertEquals("Not translated {x}", tr("Not translated {x}"))
    }
}
