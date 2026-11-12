package dev.omakey.core.translit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules behind Nepali transliteration ranking, without a model. */
class NepaliRulesTest {

    @Test fun `fragments are not words`() {
        for (junk in listOf("ः", "ँ", "ं", "ा", "ि", "्", "हो्", "ँ्", "‌औं", "ान")) {
            assertFalse(junk, Devanagari.isWellFormed(junk))
        }
    }

    @Test fun `real words are words`() {
        for (word in listOf("भात", "कहाँ", "तपाईं", "गर्नुहोस्", "क्षमा", "ज्ञान", "दुःख", "आमा", "एक", "छ", "ड़", "सम्झ‍ना")) {
            assertTrue(word, Devanagari.isWellFormed(word))
        }
    }

    private fun d(typed: String, word: String) = NepaliScheme.distance(typed, NepaliScheme.romanize(word), prefix = false)

    @Test fun `a typed h is evidence`() {
        assertTrue(d("bhat", "भात") < d("bhat", "बाट"))
        assertTrue(d("bholi", "भोलि") < d("bholi", "बोली"))
    }

    @Test fun `an untyped h or long vowel is cheap, a typed one is not`() {
        assertTrue(d("bat", "भात") < 1f)
        assertTrue(d("khana", "खाना") < 0.5f)
        assertTrue(d("baat", "बत") > d("bat", "बात"))
    }

    @Test fun `a final a the word does not have is free`() {
        assertEquals(0f, d("aja", "आज") - d("aaj", "आज"), 0.11f)
        assertTrue(d("kina", "किन") <= 0.01f)
    }

    @Test fun `an untyped nasal sign is nearly free`() {
        assertTrue(d("kaha", "कहाँ") < 0.5f)
        assertTrue(d("jandai", "जाँदै") < 1f)
    }

    @Test fun `a doubled consonant is cheap but not free`() {
        assertTrue(d("bhat", "भात") < d("bhat", "भट्ट"))
        assertTrue(d("bhatta", "भट्ट") < 0.5f)
    }

    @Test fun `an untyped n is also looked up without it`() {
        assertEquals(listOf(NepaliScheme.skeleton("jadai")), NepaliScheme.alternativeKeys("jandai"))
        assertTrue(NepaliScheme.alternativeKeys("nepal").isEmpty())
        assertEquals(listOf(NepaliScheme.skeleton("sasar")), NepaliScheme.alternativeKeys("samsar"))
    }

    @Test fun `nasal signs never reach the index key`() {
        assertEquals(NepaliScheme.skeleton("kaha"), NepaliScheme.skeleton(NepaliScheme.romanize("कहाँ")))
    }
}
