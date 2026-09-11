package dev.omakey.core.predict.eval

import dev.omakey.core.predict.lm.LanguageModel
import dev.omakey.core.predict.spatial.KeyboardGeometry
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
        val strictFloor = sorted[sorted.size - 1 - STRICT_RANK]

        var outOfVocabulary = 0
        var belowFloor = 0
        var firstLetterUnreachable = 0
        var tooManyEdits = 0
        var reachable = 0

        val editHistogram = IntArray(8)

        for (pair in pairs) {
            val id = model.indexOf(pair.correct)
            if (id == LanguageModel.NO_WORD) { outOfVocabulary++; continue }

            // The first letter is no longer required to match exactly: a slip onto an adjacent key,
            // a stray leading character, a transposition or a dropped first letter all still reach
            // the intended word. What they don't get is the ordinary floor — a candidate found by
            // assuming the first letter was wrong is held to the stricter one.
            val offFirstLetter = pair.typo.firstOrNull() != pair.correct.firstOrNull()
            if (offFirstLetter && !firstLetterReachable(pair.typo, pair.correct)) {
                firstLetterUnreachable++
                continue
            }
            val floor = if (offFirstLetter) maxOf(correctionFloor, strictFloor) else correctionFloor
            if (model.unigramLogProbability(id) < floor) { belowFloor++; continue }

            val distance = damerauLevenshtein(pair.typo, pair.correct)
            editHistogram[distance.coerceAtMost(editHistogram.size - 1)]++

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
        println("  first letter differs, and not by a slip        ${percent(firstLetterUnreachable)}")
        println("  not in the vocabulary at all                   ${percent(outOfVocabulary)}")
        println("-".repeat(66))
        println("edit distance between typo and intended word:")
        for (d in editHistogram.indices) {
            if (editHistogram[d] > 0) println("  distance $d${if (d == editHistogram.size - 1) "+" else " "}  ${percent(editHistogram[d])}")
        }
        println("=".repeat(66))
    }

    /**
     * Whether `AutocorrectIndex`'s candidate generation can reach [correct] from [typo] despite
     * their first letters differing — the four position-0 error types it now covers. Deliberately
     * restated here rather than shared with production code: a diagnostic that asks the
     * implementation whether it can find something can only ever answer yes.
     */
    private fun firstLetterReachable(typo: String, correct: String): Boolean {
        val typed = typo.firstOrNull() ?: return false
        val intended = correct.firstOrNull() ?: return false
        // Slip onto a neighbouring key.
        if (KeyboardGeometry.areAdjacent(typed, intended)) return true
        // Stray leading character, or the first two letters transposed — both leave the intended
        // word starting with the typo's second letter.
        if (typo.length > 1 && typo[1] == intended) return true
        // First letter never typed: the intended word is the typo with one letter prepended.
        if (correct.length == typo.length + 1 && correct.substring(1) == typo) return true
        return false
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
        const val STRICT_RANK = 8_000
        const val MAX_EDITS = 2
    }
}
