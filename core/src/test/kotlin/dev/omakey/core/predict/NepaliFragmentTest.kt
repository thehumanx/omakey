package dev.omakey.core.predict

import dev.omakey.core.predict.eval.InMemoryWordDao
import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.translit.Devanagari
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Nepali pack 1.0.1 already on phones has 26 vocabulary fragments (ँ ं ः ा …). They are rare,
 * but 31 of them are bigram continuations, so after the wrong word ordinary Devanagari typing would
 * offer one as the next word. Needs the built model (`build/packs/ne_NP/lm.bin`); skipped without it.
 */
class NepaliFragmentTest {

    private fun model(): LanguageModel {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build/packs/ne_NP/lm.bin").isFile) dir = dir.parentFile
        assumeTrue("no built Nepali model", dir != null)
        return LanguageModel.load(File(dir, "build/packs/ne_NP/lm.bin"))
    }

    @Test fun `the model still has fragments, and isFragment finds them`() {
        val model = model()
        val fragments = (0 until model.vocabularySize).filter(model::isFragment)
        assertTrue("expected the shipped pack's fragments", fragments.isNotEmpty())
        for (id in fragments) assertFalse(model.wordAt(id), Devanagari.isWellFormed(model.wordAt(id)))
        assertFalse(model.isFragment(model.indexOf("नेपाल")))
    }

    @Test fun `predictions never offer a fragment`() = runBlocking {
        val model = model()
        val engine = NgramPredictionEngine(model, InMemoryWordDao(), PersonalLanguageModel(), "ne_NP")
        // Exactly the contexts the model would continue with a fragment, plus no context at all.
        val contexts = (0 until model.vocabularySize)
            .filter { previous -> model.bigramRow(previous).any { model.isFragment(model.bigramWordId(it)) } }
            .map(model::wordAt)
        assertTrue("expected contexts that lead to fragments", contexts.isNotEmpty())
        for (previous in contexts + listOf<String?>(null)) {
            for (word in engine.suggestNext(null, previous, "", 10)) {
                assertFalse("after $previous: '$word'", model.isFragment(model.indexOf(word)))
            }
        }
    }
}
