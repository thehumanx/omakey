package dev.omakey.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SnippetPlaceholdersTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val now = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 23, 14, 5) }.time

    private fun expand(text: String, clipboard: String? = "copied") =
        SnippetPlaceholders.expand(text, now, Locale.US, utc) { clipboard }

    @Test
    fun `date uses the medium date format`() = assertEquals("Today is Nov 23, 2026", expand("Today is {date}"))

    @Test
    fun `time uses the short time format`() {
        val expected = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.US).apply { timeZone = utc }.format(now)
        assertEquals("At $expected", expand("At {time}"))
        assertFalse(expand("{time}").contains("{"))
    }

    @Test
    fun `clipboard fills in, and types nothing when empty`() {
        assertEquals("Link: copied", expand("Link: {clipboard}"))
        assertEquals("Link: ", expand("Link: {clipboard}", clipboard = null))
    }

    @Test
    fun `doubled braces type a literal brace`() = assertEquals("{date}", expand("{{date}}"))

    @Test
    fun `an unknown placeholder is typed as written`() = assertEquals("{nope} and {", expand("{nope} and {"))

    @Test
    fun `plain text is unchanged`() = assertEquals("bibek@example.com", expand("bibek@example.com"))

    @Test
    fun `a huge clipboard is capped`() =
        assertEquals(SnippetRules.MAX_TEXT, expand("{clipboard}", clipboard = "x".repeat(1_000_000)).length)
}
