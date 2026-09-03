package dev.omakey.core.predict.eval

import dev.omakey.core.predict.lm.LanguageModel
import org.junit.Test
import kotlin.math.abs

/**
 * Asks *why* the intended word wasn't offered, rather than only how often.
 *
 * The aggregate scorecard says correction is wrong more often than right, but not which of the
 * several possible causes is responsible, and guessing has a poor track record here — two separate
 * hypotheses about the candidate set (that first-letter pruning was the ceiling; that widening the
 * candidate pool would help) were both contradicted by measurement, the second while also being
 * fourteen times slower. This test attributes each failure to a specific, actionable cause so the
 * next piece of work is chosen from evidence.
 *
 * Causes are checked in the order that makes them mutually exclusive: a word that is out of
 * vocabulary can't also be "below the frequency floor".
 */
class RecallDiagnosticTest {

    @Test
    fun `why is the intended word not reachable`() {
        val model = TestLanguageModel.load()
        val pairs = EvalCorpus.spellErrors(limit = 6_000)

        // Mirrors AutocorrectIndex's own floor derivation, so the numbers describe the real gate.
        val sorted = FloatArray(model.vocabularySize) { model.unigramLogProbability(it) }
        sorted.sort()
        val correctionFloor = sorted[sorted.size - 1 - CORRECTION_RANK]

        var outOfVocabulary = 0
        var belowFloor = 0
        var firstLetterDiffers = 0
        var tooManyEdits = 0
        var reachable = 0

        val editHistogram = IntArray(8)

        for (pair in pairs) {
            val id = model.indexOf(pair.correct)
            if (id == LanguageModel.NO_WORD) { outOfVocabulary++; continue }
            if (model.unigramLogProbability(id) < correctionFloor) { belowFloor++; continue }

            val distance = damerauLevenshtein(pair.typo, pair.correct)
            editHistogram[distance.coerceAtMost(editHistogram.size - 1)]++

            if (pair.typo.firstOrNull() != pair.correct.firstOrNull()) { firstLetterDiffers++; continue }
            if (distance > MAX_EDITS) { tooManyEdits++; continue }
            reachable++
        }

        val total = pairs.size.toDouble()
        fun percent(count: Int) = String.format("%6.2f %%", 100.0 * count / total)

        println("=".repeat(66))
        println("why the intended word is not in the candidate set  (n=${pairs.size})")
        println("=".repeat(66))
        println("  reachable by the current search                ${percent(reachable)}")
        println("  ---- unreachable, by cause ----")
        println("  edit distance > $MAX_EDITS                              ${percent(tooManyEdits)}")
        println("  intended word below the correction floor       ${percent(belowFloor)}")
        println("  first letter differs                           ${percent(firstLetterDiffers)}")
        println("  not in the vocabulary at all                   ${percent(outOfVocabulary)}")
        println("-".repeat(66))
        println("edit distance between typo and intended word:")
        for (d in editHistogram.indices) {
            if (editHistogram[d] > 0) println("  distance $d${if (d == editHistogram.size - 1) "+" else " "}  ${percent(editHistogram[d])}")
        }
        println("=".repeat(66))
    }

    private fun damerauLevenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + cost)
                }
            }
        }
        return d[a.length][b.length]
    }

    private companion object {
        const val CORRECTION_RANK = 25_000
        const val MAX_EDITS = 2
    }
}
