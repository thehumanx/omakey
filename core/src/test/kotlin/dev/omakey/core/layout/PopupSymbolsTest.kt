package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PopupSymbolsTest {

    private fun KeyboardLayout.popupOf(label: String) = rows.flatMap { it.keys }.first { it.label == label }.popupChars

    @Test
    fun `symbols typed with or without spaces are the same symbols`() {
        val expected = listOf("@", "#", "$", "_", "&")
        assertEquals(expected, parsePopupSymbols("@#\$_&"))
        assertEquals(expected, parsePopupSymbols(" @ # \$  _ & "))
    }

    @Test
    fun `an emoji made of several code points stays one symbol`() {
        assertEquals(listOf("👍🏽", "🇳🇵", "@"), parsePopupSymbols("👍🏽🇳🇵@"))
        assertEquals(listOf("👩‍💻", "1️⃣", "❤️", "e\u0301"), parsePopupSymbols("👩‍💻1️⃣❤️é"))
        assertEquals(listOf("🇺🇸", "🇫🇷"), parsePopupSymbols("🇺🇸🇫🇷"))
    }

    @Test
    fun `duplicates are dropped and the list is capped`() {
        assertEquals(listOf("@", "#"), parsePopupSymbols("@#@#"))
        assertEquals(MAX_POPUP_SYMBOLS, parsePopupSymbols("1234567890!@#$%").size)
    }

    @Test
    fun `the field accepts up to ten different symbols and refuses more or a repeat`() {
        assertEquals(listOf("@", "#", "👍🏽"), popupSymbolsAsTyped("@ #👍🏽"))
        assertEquals(10, popupSymbolsAsTyped("1234567890")?.size)
        assertNull(popupSymbolsAsTyped("1234567890!"))
        assertNull(popupSymbolsAsTyped("@#@"))
        assertEquals(emptyList<String>(), popupSymbolsAsTyped("  "))
    }

    @Test
    fun `every letter offers exactly the user's symbols, in order`() {
        val layout = Layouts.QwertyEnUS.withPopupSymbols(listOf("@", "#", "$"))
        assertEquals(listOf("@", "#", "$"), layout.popupOf("e"))
        assertEquals(listOf("@", "#", "$"), layout.popupOf("q"))
        assertEquals(listOf("@", "#", "$"), layout.popupOf("m"))
    }

    @Test
    fun `the period key keeps its own punctuation list`() {
        val layout = Layouts.QwertyEnUS.withPopupSymbols(listOf("@", "#"))
        assertEquals(Layouts.QwertyEnUS.popupOf("."), layout.popupOf("."))
    }

    @Test
    fun `a symbol that is the letter itself is not offered twice`() {
        assertEquals(listOf("@"), Layouts.QwertyEnUS.withPopupSymbols(listOf("a", "@")).popupOf("a"))
    }

    @Test
    fun `no symbols leaves the layout alone`() {
        assertSame(Layouts.QwertyEnUS, Layouts.QwertyEnUS.withPopupSymbols(emptyList()))
    }
}
