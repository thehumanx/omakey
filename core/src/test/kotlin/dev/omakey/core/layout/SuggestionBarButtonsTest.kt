package dev.omakey.core.layout

import dev.omakey.core.layout.SuggestionBarButtons.Trailing
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionBarButtonsTest {

    @Test
    fun `one language has no trailing button whatever the settings`() {
        for (swap in listOf(false, true)) for (swipe in listOf(false, true)) {
            assertEquals(Trailing.NONE, SuggestionBarButtons.trailing(1, swap, swipe))
            assertEquals(1, SuggestionBarButtons.count(1, swap, swipe))
        }
    }

    @Test
    fun `several languages show the language button`() {
        assertEquals(Trailing.LANGUAGE, SuggestionBarButtons.trailing(2, false, false))
        assertEquals(2, SuggestionBarButtons.count(2, false, false))
    }

    @Test
    fun `swiping the spacebar for language hides the language button`() {
        assertEquals(Trailing.NONE, SuggestionBarButtons.trailing(3, false, true))
        assertEquals(1, SuggestionBarButtons.count(3, false, true))
    }

    @Test
    fun `swapped, the emoji button stays even with the spacebar swipe on`() {
        assertEquals(Trailing.EMOJI, SuggestionBarButtons.trailing(2, true, false))
        assertEquals(Trailing.EMOJI, SuggestionBarButtons.trailing(2, true, true))
        assertEquals(2, SuggestionBarButtons.count(2, true, true))
    }

    @Test
    fun `without gestures the language button stays, since the swipe cannot work`() {
        // Accessible mode (TalkBack, or the Settings override) turns swipes off; hiding the button
        // there would leave no one-tap way to change language.
        assertEquals(Trailing.LANGUAGE, SuggestionBarButtons.trailing(3, false, true, gesturesActive = false))
        assertEquals(2, SuggestionBarButtons.count(3, false, true, gesturesActive = false))
    }
}
