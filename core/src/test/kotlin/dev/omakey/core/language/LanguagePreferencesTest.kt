package dev.omakey.core.language

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanguagePreferencesTest {

    private lateinit var prefs: LanguagePreferences

    @Before
    fun setUp() {
        prefs = LanguagePreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `only English is enabled by default`() {
        assertEquals(setOf(Languages.EnglishUS.id), prefs.settings.value.enabledLanguageIds)
        assertEquals(Languages.EnglishUS.id, prefs.settings.value.activeLanguageId)
    }

    @Test
    fun `enabling a language adds it without disabling English`() {
        prefs.setEnabled(Languages.Nepali.id, true)

        val settings = prefs.settings.value
        assertTrue(Languages.Nepali.id in settings.enabledLanguageIds)
        assertTrue(Languages.EnglishUS.id in settings.enabledLanguageIds)
    }

    @Test
    fun `English can never be disabled`() {
        prefs.setEnabled(Languages.EnglishUS.id, false)

        assertTrue(Languages.EnglishUS.id in prefs.settings.value.enabledLanguageIds)
    }

    @Test
    fun `disabling the active language falls back to English`() {
        prefs.setEnabled(Languages.Nepali.id, true)
        prefs.setActiveLanguage(Languages.Nepali.id)
        assertEquals(Languages.Nepali.id, prefs.settings.value.activeLanguageId)

        prefs.setEnabled(Languages.Nepali.id, false)

        assertEquals(Languages.EnglishUS.id, prefs.settings.value.activeLanguageId)
        assertFalse(Languages.Nepali.id in prefs.settings.value.enabledLanguageIds)
    }

    @Test
    fun `setting the active language implicitly enables it`() {
        prefs.setActiveLanguage(Languages.Nepali.id)

        val settings = prefs.settings.value
        assertEquals(Languages.Nepali.id, settings.activeLanguageId)
        assertTrue(Languages.Nepali.id in settings.enabledLanguageIds)
    }

    @Test
    fun `input method choice persists per language`() {
        val traditional = Languages.Nepali.inputMethods.last()
        prefs.setInputMethodForLanguage(Languages.Nepali.id, traditional.id)

        assertEquals(traditional.id, prefs.settings.value.inputMethodByLanguage[Languages.Nepali.id])
        // English's own choice is untouched by changing Nepali's.
        assertEquals(Languages.EnglishUS.defaultInputMethod.id, prefs.settings.value.inputMethodByLanguage[Languages.EnglishUS.id])
    }

    @Test
    fun `settings survive a fresh instance backed by the same SharedPreferences`() {
        prefs.setEnabled(Languages.Nepali.id, true)
        prefs.setActiveLanguage(Languages.Nepali.id)
        prefs.setInputMethodForLanguage(Languages.Nepali.id, Languages.Nepali.inputMethods.last().id)

        val reloaded = LanguagePreferences(ApplicationProvider.getApplicationContext())

        assertEquals(prefs.settings.value, reloaded.settings.value)
    }
}
