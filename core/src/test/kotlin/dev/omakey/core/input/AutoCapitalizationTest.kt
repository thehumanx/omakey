package dev.omakey.core.input

import android.text.InputType
import dev.omakey.core.input.AutoCapitalization.FieldMode
import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCapitalizationTest {

    private val english = LanguageProfile.English
    private fun capital(textBefore: String, mode: FieldMode = FieldMode.SENTENCES) =
        AutoCapitalization.shouldCapitalize(textBefore, english, mode)

    @Test
    fun `sentence starts get a capital`() {
        assertTrue("empty field", capital(""))
        assertTrue(capital("Hello. "))
        assertTrue(capital("Really? "))
        assertTrue(capital("Wow! "))
        assertTrue("two spaces", capital("Hello.  "))
        assertTrue("after Enter", capital("Hello\n"))
        assertTrue("blank line after a sentence without a full stop", capital("Hi there\n\n"))
        assertTrue("spaces at the start of a line", capital("Hello\n  "))
        assertTrue("closing quote after the stop", capital("He said \"Stop.\" "))
        assertTrue("closing bracket after the stop", capital("(See above.) "))
        assertTrue("an opening bracket at a sentence start", capital("Done. ("))
        assertTrue("an opening quote at the field start", capital("\""))
    }

    @Test
    fun `mid-sentence and mid-word positions do not`() {
        assertFalse(capital("Hello "))
        assertFalse(capital("Hello, "))
        assertFalse("editing the sentence end", capital("Hello."))
        assertFalse("mid-word", capital("Hel"))
        assertFalse("bracket mid-sentence", capital("see ("))
        assertFalse(capital("He said \""))
    }

    @Test
    fun `field type decides first`() {
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val url = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val webPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val number = InputType.TYPE_CLASS_NUMBER
        listOf(email, url, password, webPassword, number).forEach {
            assertEquals(FieldMode.NONE, AutoCapitalization.fieldMode(it))
        }
        assertEquals(FieldMode.WORDS, AutoCapitalization.fieldMode(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS))
        assertEquals(FieldMode.CHARACTERS, AutoCapitalization.fieldMode(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
        assertEquals(FieldMode.SENTENCES, AutoCapitalization.fieldMode(InputType.TYPE_CLASS_TEXT))
        assertEquals(FieldMode.SENTENCES, AutoCapitalization.fieldMode(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))

        assertFalse("never in an email field, even at the start", capital("", FieldMode.NONE))
        assertTrue("every word in a name field", capital("Ram ", FieldMode.WORDS))
        assertFalse(capital("Ram", FieldMode.WORDS))
        assertTrue("every letter", capital("AB", FieldMode.CHARACTERS))
    }

    @Test
    fun `a caseless script never capitalises`() {
        val nepali = LanguageProfile(script = Script.DEVANAGARI, hasCase = false, capitalizeAfter = emptySet(), doubleSpaceInserts = '।', punctuationCycle = listOf('।'))
        assertFalse(AutoCapitalization.shouldCapitalize("", nepali, FieldMode.SENTENCES))
    }

    @Test
    fun `english i is always a capital`() {
        assertEquals("I", AutoCapitalization.alwaysCapitalized("i", english))
        assertNull("already right", AutoCapitalization.alwaysCapitalized("I", english))
        assertNull(AutoCapitalization.alwaysCapitalized("in", english))
        assertEquals("I'm", AutoCapitalization.capitalizedSuggestion("i'm", english))
        assertEquals("I'll", AutoCapitalization.capitalizedSuggestion("i'll", english))
        assertEquals("I", AutoCapitalization.capitalizedSuggestion("i", english))
        assertEquals("it's", AutoCapitalization.capitalizedSuggestion("it's", english))
        assertEquals("in", AutoCapitalization.capitalizedSuggestion("in", english))
    }
}
