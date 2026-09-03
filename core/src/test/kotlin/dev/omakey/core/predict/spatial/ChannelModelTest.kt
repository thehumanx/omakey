package dev.omakey.core.predict.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGeometryTest {

    @Test
    fun `keys on the same row are one width apart`() {
        assertEquals(1.0f, KeyboardGeometry.squaredDistance('q', 'w'), 0.01f)
        assertEquals(4.0f, KeyboardGeometry.squaredDistance('q', 'e'), 0.01f)
    }

    @Test
    fun `a key is zero distance from itself`() {
        assertEquals(0f, KeyboardGeometry.squaredDistance('k', 'k'), 0f)
    }

    @Test
    fun `the row stagger is reproduced, not idealised away`() {
        // The home row spreads 9 keys across the width the top row gives to 10, so 's' sits
        // between 'w' and 'e' rather than directly under 'w'. Getting this wrong would misprice
        // exactly the near-misses this table exists for.
        assertTrue(
            "'s' should be nearer 'w' than 'q' is to 'e'",
            KeyboardGeometry.squaredDistance('s', 'w') < KeyboardGeometry.squaredDistance('q', 'e'),
        )
        assertTrue(KeyboardGeometry.areAdjacent('s', 'w'))
        assertTrue(KeyboardGeometry.areAdjacent('s', 'e'))
    }

    @Test
    fun `opposite corners are far apart`() {
        assertTrue(KeyboardGeometry.squaredDistance('q', 'm') > 40f)
        assertTrue(!KeyboardGeometry.areAdjacent('q', 'm'))
    }

    @Test
    fun `non-layout characters have no meaningful distance`() {
        // Digits, punctuation and accented characters from a long-press popup genuinely have no
        // position relative to a letter; inventing one would be inventing evidence.
        assertEquals(KeyboardGeometry.UNRELATED, KeyboardGeometry.squaredDistance('a', '7'), 0f)
        assertEquals(KeyboardGeometry.UNRELATED, KeyboardGeometry.squaredDistance('é', 'a'), 0f)
    }
}

class ChannelModelTest {

    private val channel = ChannelModel()

    @Test
    fun `a slip onto a neighbouring key is much cheaper than one across the keyboard`() {
        val neighbour = channel.substitution('k', 'l')
        val distant = channel.substitution('q', 'm')
        assertTrue("neighbour=$neighbour should be well under distant=$distant", neighbour < distant / 2)
    }

    @Test
    fun `substitution cost is capped`() {
        // Past a point one wrong key is no more informative than another, and without the cap a
        // single far-away keypress would dominate the score and veto an otherwise strong word.
        assertEquals(ChannelModel.SUBSTITUTION_CAP, channel.substitution('q', 'm'), 0.001f)
        assertEquals(ChannelModel.SUBSTITUTION_CAP, channel.substitution('a', '7'), 0.001f)
    }

    @Test
    fun `matching characters cost nothing`() {
        assertEquals(0f, channel.substitution('a', 'a'), 0f)
    }

    @Test
    fun `a transposition costs less than the two substitutions it would otherwise be`() {
        // "teh" for "the" is one of the most common typing errors there is: both letters were
        // correct, only the ordering was wrong. Pricing it as two independent substitutions would
        // put it level with genuinely unrelated words.
        val asTransposition = channel.transposition()
        val asTwoSubstitutions = channel.substitution('e', 'h') + channel.substitution('h', 'e')
        assertTrue("$asTransposition should be below $asTwoSubstitutions", asTransposition < asTwoSubstitutions)
    }
}
