package dev.omakey.core.translit

import dev.omakey.core.predict.lm.LanguageModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Nepali transliteration against the built Nepali model (`build/packs/ne_NP/lm.bin`; skipped
 * without it) and Aksharantar's Nepali test split (`test/resources/eval/ne_NP/aksharantar_test.tsv`).
 *
 * Top-1 / top-3 are reported over all pairs and over pairs whose Devanagari word is in the model's
 * vocabulary — the first measures the keyboard, the second the ranking, since an out-of-vocabulary
 * word can only ever be reached through the literal form or a stem + suffix split.
 *
 * 2026-09-25, Nepali model 144,310 words (Wikipedia-trained):
 *   all 4,101 pairs      top-1 43.0 %   top-3 49.5 %
 *   in-vocabulary 2,306  top-1 66 %     top-3 76 %
 *   index build ~0.3 s on the JVM (once per install); candidates() ~30 µs
 * Most remaining misses are English loanwords (public, administration), inflections whose stem
 * isn't known either, and spellings the skeleton can't merge (the exonym "kathmandu").
 */
class TransliterationEvaluationTest {

    @get:Rule val temp = TemporaryFolder()

    private fun model(): LanguageModel {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build/packs/ne_NP/lm.bin").isFile) dir = dir.parentFile
        assumeTrue("no built Nepali model", dir != null)
        return LanguageModel.load(File(dir, "build/packs/ne_NP/lm.bin"))
    }

    @Test
    fun nepali() {
        val model = model()
        val started = System.nanoTime()
        val index = TransliterationIndex.openOrBuild(File(temp.root, "translit.idx"), model, NepaliScheme)
        val buildMs = (System.nanoTime() - started) / 1_000_000
        val transliterator = Transliterator(model, index, NepaliScheme)

        fun top(latin: String, n: Int) = transliterator.candidates(latin, limit = n)
        assertTrue(top("namaste", 3).toString(), "नमस्ते" in top("namaste", 3))
        assertTrue(top("nepal", 1).toString(), "नेपाल" in top("nepal", 1))
        assertTrue(top("mero", 3).toString(), "मेरो" in top("mero", 3))
        assertTrue(top("chha", 3).toString(), "छ" in top("chha", 3))
        assertTrue(top("xa", 3).toString(), "छ" in top("xa", 3))
        assertTrue(top("ramro", 3).toString(), "राम्रो" in top("ramro", 3))
        // Known gap: the English exonym "kathmandu" has an "n" the Nepali spelling काठमाडौं doesn't
        // (its nasal is the final ं), so their skeletons differ; the variant काठमान्डू is offered.

        val pairs = File(javaClass.classLoader!!.getResource("eval/ne_NP/aksharantar_test.tsv")!!.toURI())
            .readLines().filter { '\t' in it }.map { it.split('\t').let { p -> p[0] to p[1] } }
        var top1 = 0; var top3 = 0; var inVocab = 0; var top1V = 0; var top3V = 0
        var totalNanos = 0L
        for ((latin, native) in pairs) {
            val t = System.nanoTime()
            val candidates = transliterator.candidates(latin, limit = 3)
            totalNanos += System.nanoTime() - t
            val known = model.indexOf(native) != LanguageModel.NO_WORD
            if (known) inVocab++
            if (candidates.firstOrNull() == native) { top1++; if (known) top1V++ }
            if (native in candidates) { top3++; if (known) top3V++ }
        }
        fun pct(a: Int, b: Int) = "%.1f %%".format(100.0 * a / b)
        println("ne_NP index built in $buildMs ms, ${index.size} entries")
        println("ne_NP all pairs (${pairs.size}): top-1 ${pct(top1, pairs.size)}, top-3 ${pct(top3, pairs.size)}")
        println("ne_NP in-vocabulary ($inVocab): top-1 ${pct(top1V, inVocab)}, top-3 ${pct(top3V, inVocab)}")
        println("ne_NP mean candidates() time: ${totalNanos / pairs.size / 1000} µs")
        assertTrue("top-3 regressed: $top3", 100.0 * top3 / pairs.size >= 47.0)
        assertTrue("in-vocabulary top-3 regressed: $top3V", 100.0 * top3V / inVocab >= 73.0)
    }
}
