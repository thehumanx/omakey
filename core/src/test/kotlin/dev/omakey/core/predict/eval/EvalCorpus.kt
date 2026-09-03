package dev.omakey.core.predict.eval

import java.io.File

/**
 * Loaders for the test-only evaluation fixtures in `src/test/resources/eval`. See that directory's
 * README for each corpus's provenance.
 *
 * The model under test is loaded from its real location in the repo by [TestLanguageModel] rather
 * than from a copy, deliberately: the point of this harness is to measure the data users actually
 * get, and drift between a shipped asset and a test copy is exactly the class of bug it exists to
 * catch.
 */
object EvalCorpus {

    data class SpellPair(val correct: String, val typo: String)

    /** Misspelling pairs, capped at [limit] taken evenly across the file rather than from the
     * front — the source is grouped by correct word, so a prefix slice would only ever cover
     * words starting with a handful of letters. */
    fun spellErrors(limit: Int = Int.MAX_VALUE): List<SpellPair> {
        val all = resource("eval/spell-errors.txt").readLines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else SpellPair(line.substring(0, tab), line.substring(tab + 1))
        }
        return all.evenSample(limit)
    }

    /** Tokenized sentences (already lowercase, alphabetic words only — see the fixture README). */
    fun sentences(limit: Int = Int.MAX_VALUE): List<List<String>> =
        resource("eval/sentences.txt").readLines()
            .filter { it.isNotBlank() }
            .map { it.trim().split(' ') }
            .evenSample(limit)

    private fun <T> List<T>.evenSample(limit: Int): List<T> {
        if (limit >= size || limit <= 0) return this
        val stride = size.toDouble() / limit
        return (0 until limit).map { this[(it * stride).toInt()] }
    }

    private fun resource(path: String): File {
        val url = javaClass.classLoader?.getResource(path)
            ?: error("Missing test resource '$path' — expected under core/src/test/resources/")
        return File(url.toURI())
    }
}
