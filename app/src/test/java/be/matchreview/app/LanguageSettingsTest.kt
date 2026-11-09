package be.matchreview.app

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import be.matchreview.app.i18n.AppLanguage
import be.matchreview.app.i18n.I18n
import be.matchreview.app.i18n.LanguageSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The in-app language menu and Android's per-app language setting stay the same. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LanguageSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val localeManager get() = context.getSystemService(LocaleManager::class.java)

    @After
    fun reset() = LanguageSettings.choose(context, AppLanguage.ENGLISH)

    @Test
    fun `choosing in the app sets the phone's per-app language`() {
        LanguageSettings.choose(context, AppLanguage.GERMAN)
        assertEquals("de", localeManager.applicationLocales[0].language)
        assertEquals(AppLanguage.GERMAN, I18n.current)

        LanguageSettings.choose(context, AppLanguage.SYSTEM)
        assertTrue(localeManager.applicationLocales.isEmpty)
    }

    @Test
    fun `a language chosen in the phone's settings is used by the app`() {
        LanguageSettings.choose(context, AppLanguage.ENGLISH)
        localeManager.applicationLocales = LocaleList.forLanguageTags("it")
        LanguageSettings.refresh(context)
        assertEquals(AppLanguage.ITALIAN, LanguageSettings.selected.value)
        assertEquals(AppLanguage.ITALIAN, I18n.current)
    }
}
