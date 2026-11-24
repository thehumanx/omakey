package dev.omakey.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetRulesTest {

    private fun plain(id: Long, name: String = "", text: String = "x", position: Int = 0) =
        Snippet(id, name, text, secret = false, position = position)
    private fun secret(id: Long, name: String, position: Int = 0) =
        Snippet(id, name, text = null, secret = true, position = position)

    @Test
    fun `a secret chip shows the mask and its name, never the text`() =
        assertEquals("•••• Bank", SnippetRules.chipLabel(secret(1, "Bank")))

    @Test
    fun `a named plain chip shows its name`() =
        assertEquals("Work email", SnippetRules.chipLabel(plain(1, "Work email", "bibek@example.com")))

    @Test
    fun `an unnamed plain chip shows its text, cut to 24 characters`() {
        assertEquals("bibek@example.com", SnippetRules.chipLabel(plain(1, text = "bibek@example.com")))
        assertEquals("Kathmandu Metropolitan C…", SnippetRules.chipLabel(plain(1, text = "Kathmandu Metropolitan City, Ward 4")))
    }

    @Test
    fun `an unnamed multi-line chip shows its first line`() =
        assertEquals("Thanks,", SnippetRules.chipLabel(plain(1, text = "Thanks,\nBibek")))

    @Test
    fun `secrets are hidden while the phone is locked, in order otherwise`() {
        val all = listOf(plain(1, position = 0), secret(2, "Bank", position = 1), plain(3, position = 2))
        assertEquals(all, SnippetRules.visible(all, deviceLocked = false))
        assertEquals(listOf(1L, 3L), SnippetRules.visible(all, deviceLocked = true).map { it.id })
    }

    @Test
    fun `validation`() {
        assertEquals("Add what gets typed", SnippetRules.validate("", "   ", secret = false))
        assertEquals("Up to 2000 characters", SnippetRules.validate("", "a".repeat(2001), secret = false))
        assertEquals("Give the password a button label, like Bank", SnippetRules.validate(" ", "hunter2!X", secret = true))
        assertNull(SnippetRules.validate("", "a".repeat(2000), secret = false))
        assertNull(SnippetRules.validate("Bank", "hunter2!X", secret = true))
    }

    @Test
    fun `password-looking text`() {
        assertTrue(SnippetRules.looksLikePassword("Tr0ub4dor&3"))
        assertTrue(SnippetRules.looksLikePassword("hunter2!X"))
        assertFalse(SnippetRules.looksLikePassword("bibek@example.com"))
        assertFalse(SnippetRules.looksLikePassword("+977 9800000000"))
        assertFalse(SnippetRules.looksLikePassword("password"))
        assertFalse(SnippetRules.looksLikePassword("Ab1!"))
        assertFalse(SnippetRules.looksLikePassword("hello world"))
    }

    @Test
    fun `toString never prints the text`() =
        assertFalse(plain(1, "Bank", "hunter2!X").toString().contains("hunter2"))
}
