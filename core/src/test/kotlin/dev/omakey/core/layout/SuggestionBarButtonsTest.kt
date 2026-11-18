package dev.omakey.core.layout

import dev.omakey.core.layout.SuggestionBarButtons.Trailing
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionBarButtonsTest {

    private fun trailing(languages: Int, slot: String = EMOJI_SLOT_DEFAULT, swipe: Boolean = false, gestures: Boolean = true) =
        SuggestionBarButtons.trailing(languages, slot, swipe, gestures)

    @Test
    fun `one language with the emoji key has no trailing button whatever the settings`() {
        for (slot in listOf(EMOJI_SLOT_DEFAULT, EMOJI_SLOT_LANGUAGE)) for (swipe in listOf(false, true)) {
            assertEquals(emptyList<Trailing>(), trailing(1, slot, swipe))
            assertEquals(1, SuggestionBarButtons.count(1, slot, swipe))
        }
    }

    @Test
    fun `several languages show the language button`() {
        assertEquals(listOf(Trailing.LANGUAGE), trailing(2))
        assertEquals(2, SuggestionBarButtons.count(2, EMOJI_SLOT_DEFAULT, false))
    }

    @Test
    fun `swiping the spacebar for language hides the language button`() {
        assertEquals(emptyList<Trailing>(), trailing(3, swipe = true))
    }

    @Test
    fun `language key on the keyboard puts the emoji button in the bar, even with the spacebar swipe on`() {
        assertEquals(listOf(Trailing.EMOJI), trailing(2, EMOJI_SLOT_LANGUAGE))
        assertEquals(listOf(Trailing.EMOJI), trailing(2, EMOJI_SLOT_LANGUAGE, swipe = true))
    }

    @Test
    fun `a character in the emoji key's place keeps both emoji and language one tap away`() {
        assertEquals(listOf(Trailing.EMOJI), trailing(1, ","))
        assertEquals(listOf(Trailing.EMOJI, Trailing.LANGUAGE), trailing(2, ","))
        assertEquals(3, SuggestionBarButtons.count(2, ",", false))
        assertEquals(listOf(Trailing.EMOJI), trailing(2, ",", swipe = true))
    }

    @Test
    fun `without gestures the language button stays, since the swipe cannot work`() {
        // Accessible mode (TalkBack, or the Settings override) turns swipes off; hiding the button
        // there would leave no one-tap way to change language.
        assertEquals(listOf(Trailing.LANGUAGE), trailing(3, swipe = true, gestures = false))
    }
}
