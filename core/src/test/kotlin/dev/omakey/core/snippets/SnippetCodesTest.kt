package dev.omakey.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnippetCodesTest {

    private val email = Snippet(1, "Email", "bibek@example.com", secret = false, position = 0, shortcut = "@@")
    private val addr = Snippet(2, "", "Kathmandu", secret = false, position = 1, shortcut = "Addr")
    private val bank = Snippet(3, "Bank", null, secret = true, position = 2, shortcut = "bank")
    private val all = listOf(email, addr, bank)

    @Test
    fun `the token is everything since the last whitespace`() {
        assertEquals("@@", SnippetCodes.tokenBeforeCursor("hi @@"))
        assertEquals("addr", SnippetCodes.tokenBeforeCursor("addr"))
        assertEquals("", SnippetCodes.tokenBeforeCursor("x\n"))
        assertEquals("", SnippetCodes.tokenBeforeCursor(""))
    }

    @Test
    fun `a code matches ignoring case`() {
        assertEquals(email, SnippetCodes.match("mail me at @@", all))
        assertEquals(addr, SnippetCodes.match("ADDR", all))
    }

    @Test
    fun `secrets are never offered`() = assertNull(SnippetCodes.match("bank", all))

    @Test
    fun `nothing is offered after a space or for a partial code`() {
        assertNull(SnippetCodes.match("@@ ", all))
        assertNull(SnippetCodes.match("ad", all))
    }

    @Test
    fun `code validation`() {
        assertNull(SnippetCodes.validate("", all, selfId = null))
        assertEquals("No spaces in a short code", SnippetCodes.validate("my code", all, null))
        assertEquals("Up to 32 characters", SnippetCodes.validate("x".repeat(33), all, null))
        assertEquals("Already used by Email", SnippetCodes.validate("@@", all, null))
        assertEquals("Already used by Kathmandu", SnippetCodes.validate("addr", all, null))
        assertNull(SnippetCodes.validate("@@", all, selfId = 1))
        // A secret's code doesn't count: secrets have none once saved.
        assertNull(SnippetCodes.validate("bank", all, null))
    }

    @Test
    fun `an offer is still good only while its snippet is plain and keeps that code`() {
        assertEquals(true, SnippetCodes.stillOffered(1, "@@", all))
        assertEquals(true, SnippetCodes.stillOffered(2, "ADDR", all))
        assertEquals(false, SnippetCodes.stillOffered(1, "##", all))
        assertEquals(false, SnippetCodes.stillOffered(3, "bank", all))
        assertEquals(false, SnippetCodes.stillOffered(1, "@@", all.map { if (it.id == 1L) it.copy(secret = true) else it }))
        assertEquals(false, SnippetCodes.stillOffered(9, "@@", all))
    }
}
