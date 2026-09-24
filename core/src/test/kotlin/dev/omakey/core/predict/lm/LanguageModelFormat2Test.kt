package dev.omakey.core.predict.lm

import dev.omakey.core.locale.LanguageProfile
import dev.omakey.core.locale.Script
import dev.omakey.core.predict.AutocorrectIndex
import dev.omakey.core.predict.PersonalLanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Format 2 (AGENTS.md §66 Phase 3) against a tiny Spanish + Devanagari model built by the real
 * writer (`scripts/build_lm_test_fixture.py`). Format 1 stored raw UTF-8 and read it one byte per
 * character, so none of these words could exist in it.
 */
class LanguageModelFormat2Test {

    private val file = File(javaClass.classLoader!!.getResource("lm/tiny_multiscript.bin")!!.toURI())
    private val model = LanguageModel.load(file)

    @Test
    fun `accented and Devanagari words are found`() {
        for (word in listOf("canción", "niño", "año", "cómo", "नमस्ते", "किताब", "संसार")) {
            val id = model.indexOf(word)
            assertNotEquals(word, LanguageModel.NO_WORD, id)
            assertEquals(word, model.wordAt(id))
        }
        assertEquals(LanguageModel.NO_WORD, model.indexOf("cancion"))
    }

    @Test
    fun `charAt and copyWord return real characters`() {
        val id = model.indexOf("canción")
        assertEquals('ó', model.charAt(id, 5))
        val buffer = CharArray(16)
        val length = model.copyWord(id, buffer)
        assertEquals("canción", String(buffer, 0, length))
        val devanagari = model.indexOf("नमस्ते")
        assertEquals('्', model.charAt(devanagari, 3)) // the halant in स्
    }

    @Test
    fun `prefix ranges work past ASCII`() {
        val words = model.prefixRange("can").map(model::wordAt)
        assertEquals(listOf("cancha", "canción"), words) // code-point order: h < i
        assertEquals(listOf("cómo"), model.prefixRange("có").map(model::wordAt))
        assertEquals(listOf("किताब"), model.prefixRange("कि").map(model::wordAt))
    }

    @Test
    fun `the alphabet is sorted and complete`() {
        val alphabet = (0 until model.alphabetSize).map(model::alphabetChar)
        assertEquals(alphabet.sorted(), alphabet)
        assertTrue(model.alphabetIndexOf('ñ') >= 0)
        assertTrue(model.alphabetIndexOf('ि') >= 0)
        assertEquals(-1, model.alphabetIndexOf('z'))
    }

    @Test
    fun `context still reaches bigrams`() {
        val mi = model.indexOf("mi")
        val continuations = model.bigramRow(mi).map { model.wordAt(model.bigramWordId(it)) }
        assertEquals(listOf("canción", "casa"), continuations)
    }

    @Test
    fun `metadata travels with the model`() {
        assertTrue(model.metadataJson.contains("\"locale\": \"test\""))
    }

    private val spanish = LanguageProfile(
        script = Script.LATIN,
        hasCase = true,
        capitalizeAfter = setOf('.', '!', '?'),
        doubleSpaceInserts = '.',
        punctuationCycle = listOf('.', ','),
        equivalentLetters = listOf("aáà", "eéè", "iíì", "oóò", "uúü", "nñ"),
    )

    @Test
    fun `equivalent letters restore a dropped accent`() {
        val index = AutocorrectIndex().apply { load(model, PersonalLanguageModel(), spanish) }
        assertEquals("canción", index.correct("cancion"))
        assertEquals("niño", index.correct("nino"))
        assertTrue(index.alternatives("cancion", limit = 3).contains("canción"))
    }

    @Test
    fun `decomposed input finds the precomposed entry`() {
        val index = AutocorrectIndex().apply { load(model, PersonalLanguageModel(), spanish) }
        assertTrue(index.isKnown("canción")) // o + combining acute, as some hosts send it
        assertTrue(index.isKnown("CANCIÓN"))
    }
}
