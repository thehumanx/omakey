package dev.omakey.core.translit

import dev.omakey.core.predict.lm.LanguageModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Romanized Nepali the way it is actually typed in chat, against the built Nepali model
 * (`build/packs/ne_NP/lm.bin`; skipped without it), at the keyboard's own limit: two candidates,
 * the first of which the field shows and space commits.
 *
 * Aksharantar ([TransliterationEvaluationTest]) is isolated, often formal words; this is the
 * everyday vocabulary a family chat is made of, where the reported failures were.
 *
 * 2026-11-12, Nepali pack 1.0.1 (93 words; plus haha, asserted on its own):
 *   before  first 67/93   in the two 82/93   (and haha → ः, eh → एहिमे, bhat → बाट)
 *   after   first 76/93   in the two 93/93   ("k" → के is a shorthand, not a spelling)
 * Aksharantar, same change (limit 3): top-1 43.0 → 41.0 %, top-3 49.5 → 49.3 %; in-vocabulary
 * top-1 66.6 → 62.6 %, top-3 75.8 → 76.1 %. The top-1 drop is the literal spelling now going first
 * where no dictionary word is a close match (the dictionary word is then second), which is the
 * point: the field shows what was typed rather than a different word.
 * Most remaining misses are the model's (trained on Wikipedia): खान over खाना, आम over आमा.
 */
class NepaliEverydayWordsTest {

    companion object {
        private var transliterator: Transliterator? = null

        @BeforeClass @JvmStatic fun load() {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "build/packs/ne_NP/lm.bin").isFile) dir = dir.parentFile
            if (dir == null) return
            val model = LanguageModel.load(File(dir, "build/packs/ne_NP/lm.bin"))
            val index = TransliterationIndex.from(ByteBuffer.wrap(TransliterationIndex.build(model, NepaliScheme)))
            transliterator = Transliterator(model, index, NepaliScheme)
        }
    }

    private fun engine(): Transliterator {
        assumeTrue("no built Nepali model", transliterator != null)
        return transliterator!!
    }

    /** What the keyboard asks for: the strip shows these two, then the Latin. */
    private fun strip(latin: String) = engine().candidates(latin, limit = 2)

    @Test fun `reported - haha is never a lone visarga`() {
        val c = strip("haha")
        assertFalse(c.toString(), "ः" in c)
        assertEquals(c.toString(), "हहा", c.first())
    }

    @Test fun `reported - eh is eh, not a longer word`() {
        assertEquals(strip("eh").toString(), "एह", strip("eh").first())
    }

    @Test fun `reported - a typed h counts - bhat is rice`() {
        assertEquals(strip("bhat").toString(), "भात", strip("bhat").first())
        assertEquals(strip("bholi").toString(), "भोलि", strip("bholi").first())
    }

    @Test fun `no candidate is a fragment that is not a word`() {
        for ((latin, _) in cases()) for (word in strip(latin)) {
            assertTrue("$latin → $word", Devanagari.isWellFormed(word))
        }
    }

    @Test fun `everyday words`() {
        val cases = cases()
        var first = 0
        var shown = 0
        val misses = StringBuilder()
        for ((latin, wanted) in cases) {
            val c = strip(latin)
            if (c.firstOrNull() in wanted) first++
            if (c.any { it in wanted }) shown++ else misses.append("$latin → $c (want $wanted)\n")
        }
        println("ne_NP everyday (${cases.size}): first $first, in the two $shown\n$misses")
        assertTrue("first regressed: $first/${cases.size}", first >= 75)
        assertTrue("in-the-two regressed: $shown/${cases.size}\n$misses", shown >= 92)
    }

    private fun cases(): List<Pair<String, List<String>>> =
        File(javaClass.classLoader!!.getResource("eval/ne_NP/everyday.tsv")!!.toURI()).readLines()
            .filter { '\t' in it && !it.startsWith("#") }
            .map { line -> line.split('\t').let { it[0] to it[1].split('|') } }
}
