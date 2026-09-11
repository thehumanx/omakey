package dev.omakey.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSkinToneTest {

    @Test
    fun `applies a modifier to an emoji that supports one`() {
        assertEquals("👍🏽", EmojiSkinTone.MEDIUM.apply("👍"))
        assertEquals("✋🏿", EmojiSkinTone.DARK.apply("✋"))
    }

    @Test
    fun `the default tone strips any modifier rather than adding one`() {
        assertEquals("👍", EmojiSkinTone.DEFAULT.apply("👍"))
        assertEquals("👍", EmojiSkinTone.DEFAULT.apply("👍🏿"))
    }

    @Test
    fun `re-toning replaces rather than appends`() {
        // Appending would produce a sequence no font has a glyph for — two tofu boxes in the
        // user's message. Recents store whatever tone was in force when the emoji was used, so
        // this path is hit every time the setting changes.
        assertEquals("👍🏻", EmojiSkinTone.LIGHT.apply("👍🏿"))
        assertEquals(1, EmojiSkinTone.LIGHT.apply("👍🏿").count { it.isHighSurrogate() } - 1)
    }

    @Test
    fun `emoji without a skin tone are left alone`() {
        for (tone in EmojiSkinTone.ALL) {
            assertEquals("😀", tone.apply("😀"))
            assertEquals("🎉", tone.apply("🎉"))
            assertEquals("❤️", tone.apply("❤️"))
            assertEquals("🍕", tone.apply("🍕"))
        }
    }

    @Test
    fun `multi-code-point sequences are left alone rather than mangled`() {
        // Toning a ZWJ sequence correctly means applying the modifier to each person in it, which
        // needs emoji data this doesn't carry. Leaving it yellow is the safe failure.
        assertEquals("👨‍👩‍👧", EmojiSkinTone.DARK.apply("👨‍👩‍👧"))
        assertFalse(EmojiSkinTone.supportsSkinTone("👨‍👩‍👧"))
    }

    @Test
    fun `supportsSkinTone recognises people and hands but not objects`() {
        assertTrue(EmojiSkinTone.supportsSkinTone("👋"))
        assertTrue(EmojiSkinTone.supportsSkinTone("🧑"))
        assertTrue(EmojiSkinTone.supportsSkinTone("💪"))
        assertFalse(EmojiSkinTone.supportsSkinTone("🚗"))
        assertFalse(EmojiSkinTone.supportsSkinTone("😀"))
        assertFalse(EmojiSkinTone.supportsSkinTone(""))
    }

    @Test
    fun `every tone has a distinct sample and there are six of them`() {
        val samples = EmojiSkinTone.ALL.map { it.sample }
        assertEquals(6, EmojiSkinTone.ALL.size)
        assertEquals("samples must be distinct to be usable as swatches", 6, samples.toSet().size)
    }

    @Test
    fun `fromId round-trips and falls back to default`() {
        for (tone in EmojiSkinTone.ALL) {
            assertEquals(tone, EmojiSkinTone.fromId(tone.id))
        }
        assertEquals(EmojiSkinTone.DEFAULT, EmojiSkinTone.fromId(null))
        assertEquals(EmojiSkinTone.DEFAULT, EmojiSkinTone.fromId("nonsense"))
    }
}
