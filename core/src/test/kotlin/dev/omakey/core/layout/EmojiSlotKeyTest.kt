package dev.omakey.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSlotKeyTest {

    private fun slotKey(layout: KeyboardLayout): KeyDefinition {
        val bottom = layout.rows.last().keys
        val space = bottom.indexOfFirst { it.code == SpecialKeyCode.SPACE }
        return bottom[space - 1]
    }

    @Test
    fun `the default keeps the emoji key`() {
        assertSame(Layouts.QwertyEnUS, Layouts.QwertyEnUS.withEmojiSlotKey(EMOJI_SLOT_DEFAULT, languageCount = 2))
        assertFalse(emojiKeyInBar(EMOJI_SLOT_DEFAULT, languageCount = 2))
    }

    @Test
    fun `a character replaces the emoji key in place and types it`() {
        val before = slotKey(Layouts.QwertyEnUS)
        val after = Layouts.QwertyEnUS.withEmojiSlotKey(",", languageCount = 1)
        val key = slotKey(after)
        assertEquals(",", key.label)
        assertEquals(",", key.committedText)
        assertEquals(KeyType.CHARACTER, key.keyType)
        assertEquals(before.widthWeight, key.widthWeight)
        assertNull(after.keyForCode(SpecialKeyCode.EXTENSIONS))
        assertSame(key, after.keyForCode(key.code))
        assertEquals(emptyList<String>(), after.validate())
    }

    @Test
    fun `a character already elsewhere on the keyboard still gets a key of its own`() {
        // "." is the key right of the spacebar too; both keys must stay tappable and distinct.
        val after = Layouts.QwertyEnUS.withEmojiSlotKey(".", languageCount = 1)
        assertEquals(".", slotKey(after).committedText)
        assertEquals(emptyList<String>(), after.validate())
    }

    @Test
    fun `emoji moves to the bar whenever its key is taken`() {
        assertTrue(emojiKeyInBar(",", languageCount = 1))
        assertTrue(emojiKeyInBar(EMOJI_SLOT_LANGUAGE, languageCount = 2))
    }

    @Test
    fun `the language key needs a second language to switch to`() {
        assertSame(Layouts.QwertyEnUS, Layouts.QwertyEnUS.withEmojiSlotKey(EMOJI_SLOT_LANGUAGE, languageCount = 1))
        assertFalse(emojiKeyInBar(EMOJI_SLOT_LANGUAGE, languageCount = 1))
        val key = slotKey(Layouts.QwertyEnUS.withEmojiSlotKey(EMOJI_SLOT_LANGUAGE, languageCount = 2))
        assertEquals(SpecialKeyCode.LANGUAGE, key.code)
    }

    @Test
    fun `only one visible character, the language key, or the default are stored`() {
        assertEquals(",", sanitizeEmojiSlotKey(","))
        assertEquals("👍🏽", sanitizeEmojiSlotKey("👍🏽"))
        assertEquals(EMOJI_SLOT_LANGUAGE, sanitizeEmojiSlotKey(EMOJI_SLOT_LANGUAGE))
        assertEquals(EMOJI_SLOT_DEFAULT, sanitizeEmojiSlotKey("ab"))
        assertEquals(EMOJI_SLOT_DEFAULT, sanitizeEmojiSlotKey(" "))
        assertEquals(EMOJI_SLOT_DEFAULT, sanitizeEmojiSlotKey(null))
    }

    @Test
    fun `phones with the old swap switch on keep the language key`() {
        assertEquals(EMOJI_SLOT_LANGUAGE, emojiSlotKeyFromStored(null, legacySwap = true))
        assertEquals(EMOJI_SLOT_DEFAULT, emojiSlotKeyFromStored(null, legacySwap = false))
        assertEquals(",", emojiSlotKeyFromStored(",", legacySwap = true))
    }
}
