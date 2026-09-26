package dev.omakey.core.translit

import org.junit.Assert.assertEquals
import org.junit.Test

class RomanizationTest {

    @Test
    fun `romanization follows Nepali schwa rules`() {
        assertEquals("nepaal", Romanization.romanize("नेपाल"))    // ा is long "aa"
        assertEquals("ram", Romanization.romanize("राम").replace("aa", "a")) // final schwa dropped
        assertEquals("ra", Romanization.romanize("र"))            // ...unless the word would have no vowel
        assertEquals("chha", Romanization.romanize("छ"))
        assertEquals("namaste", Romanization.romanize("नमस्ते"))  // virama suppresses the schwa
        assertEquals("garnu", Romanization.romanize("गर्नु"))
        assertEquals("timii", Romanization.romanize("तिमी"))
        assertEquals("gyaan", Romanization.romanize("ज्ञान"))      // ज्ञ is "gy" in Nepali
        assertEquals("raamro", Romanization.romanize("राम्रो"))
        assertEquals("sansaar", Romanization.romanize("संसार"))   // anusvara
    }

    @Test
    fun `the skeleton folds the ways people spell the same word`() {
        fun same(vararg spellings: String) {
            val keys = spellings.map(Romanization::skeleton).toSet()
            assertEquals(spellings.joinToString(), 1, keys.size)
        }
        same("chha", "cha", "xa", Romanization.romanize("छ"))
        same("timi", "timee", Romanization.romanize("तिमी"))
        same("bhayo", "vayo", "bayo", Romanization.romanize("भयो"))
        same("ramro", "raamro", Romanization.romanize("राम्रो"))
        same("garnu", "garnoo", "grnu", Romanization.romanize("गर्नु"))
        same("shanti", "santi", Romanization.romanize("शान्ति"))
    }

    @Test
    fun `the literal transliteration spells common words`() {
        assertEquals("नमस्ते", LiteralTransliterator.transliterate("namaste"))
        assertEquals("गर्नु", LiteralTransliterator.transliterate("garnu"))
        assertEquals("मेरो", LiteralTransliterator.transliterate("mero"))
        assertEquals("छ", LiteralTransliterator.transliterate("xa"))
        assertEquals("छ", LiteralTransliterator.transliterate("chha"))
        assertEquals("क", LiteralTransliterator.transliterate("k"))
        assertEquals("आमा", LiteralTransliterator.transliterate("aama"))
        assertEquals("ज्ञान", LiteralTransliterator.transliterate("gyaan"))
        assertEquals("खाना", LiteralTransliterator.transliterate("khaana"))
        assertEquals("खना", LiteralTransliterator.transliterate("khana")) // final "a" after 2+ syllables is ा; the first can't be known
        assertEquals("र", LiteralTransliterator.transliterate("ra"))       // ...but inherent in one syllable
    }
}
